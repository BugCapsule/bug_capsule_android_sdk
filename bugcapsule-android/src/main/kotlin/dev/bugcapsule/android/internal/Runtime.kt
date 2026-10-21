package dev.bugcapsule.android.internal

import android.content.Context
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.CaptureResult
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.StateRestorer
import dev.bugcapsule.android.StateSerializer
import dev.bugcapsule.android.core.AppInfo
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CapsuleManifest
import dev.bugcapsule.android.core.CapsuleValidator
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.FlightRecorder
import dev.bugcapsule.android.core.Ids
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.PlatformInfo
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.RuntimeInfo
import dev.bugcapsule.android.core.SessionInfo
import dev.bugcapsule.android.replay.BugCapsuleReplay
import dev.bugcapsule.android.replay.ReplayEngine
import dev.bugcapsule.android.storage.EarlyRestore
import dev.bugcapsule.android.storage.StorageValues
import java.io.File
import java.util.Collections
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Replaces storage, transport and randomness in tests. */
class BugCapsuleTestOverrides @JvmOverloads constructor(
    val storageDir: File? = null,
    val transport: Transport? = null,
    val random: Random? = null,
)

internal const val SDK_NAME = "bugcapsule-android"

/**
 * The BugCapsule runtime of one process: capture (recorder, checkpoints,
 * failures, capsule writing, outbox, remote config) or replay (engine,
 * restoration). A port of the Flutter `_Runtime`. Every SDK error is caught:
 * capture problems never reach the app.
 */
internal class Runtime(
    val host: Host,
    override val config: BugCapsuleConfig,
    val integrations: List<BugCapsuleIntegration>,
    private val overrides: BugCapsuleTestOverrides?,
    val actions: ActionRegistry,
) : BugCapsuleSession {
    private val samplingRandom: Random = overrides?.random ?: Random()
    private val startElapsed = host.elapsedRealtime()
    private val sessionId = Ids.uuidV4()
    private val startedAtMs = System.currentTimeMillis()

    @Volatile
    private var seed: Long = Ids.newSeed()

    @Volatile
    var random: Random = Random(seed)
        private set

    private val lock = Any()
    private var recorder: FlightRecorder? = null

    @Volatile
    var engine: ReplayEngine? = null
        private set

    @Volatile
    private var currentRedactor = Redactor()

    @Volatile
    private var recording = false

    @Volatile
    private var captureBodies = true

    @Volatile
    private var maxCapsuleBytes = 5L * 1024 * 1024
    private var sessionRate = 1.0

    @Volatile
    private var killed = false

    @Volatile
    private var anchorMs: Long? = null

    @Volatile
    private var wallAtAnchorMs: Long? = null

    @Volatile
    var lastFailure: FailureRecord? = null
        private set
    private val capturedSignatures = Collections.synchronizedSet(HashSet<String>())
    private val seenSignatures = Collections.synchronizedSet(HashSet<String>())

    private val capsulesWritten = java.util.concurrent.atomic.AtomicInteger()
    private val frameworks = Collections.synchronizedSet(LinkedHashSet(listOf("views")))

    var store: CapsuleStore? = null
        private set
    private var transport: Transport? = null
    var outbox: UploadOutbox? = null
        private set
    var remote: RemoteConfigController? = null
        private set
    private var disposed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var handlerInstalled = false
    private var checkpointTask: Runnable? = null
    private var capture: AndroidCapture? = null

    private val states = ConcurrentHashMap<String, Pair<StateSerializer, StateRestorer?>>()
    private val restoredStates = Collections.synchronizedSet(HashSet<String>())
    private val warned = Collections.synchronizedSet(HashSet<String>())

    val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1, ThreadFactory { r ->
        Thread(r, "BugCapsule").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }

    // ------------------------------------------------------------- session

    override val mode: CaptureMode
        get() = config.mode ?: if (host.isDebuggable) CaptureMode.DEBUG else CaptureMode.PRODUCTION
    override val isRecording: Boolean get() = recording
    override val isReplaying: Boolean get() = engine != null
    override val redactor: Redactor get() = currentRedactor
    override val captureNetworkBodies: Boolean get() = captureBodies
    override val maxBodyBytes: Int get() = config.maxBodyBytes
    override val applicationContext: Context get() = host.context
    override val replay: ReplayContext? get() = engine

    override fun now(): Long = host.elapsedRealtime() - startElapsed

    fun currentTimeMillis(): Long = engine?.currentTimeMillis() ?: System.currentTimeMillis()

    val gitSha: String? by lazy { config.gitSha ?: host.metaData("dev.bugcapsule.GIT_SHA") }
    private val gitDirty: Boolean? by lazy {
        config.gitDirty ?: host.metaData("dev.bugcapsule.GIT_DIRTY")?.let { it == "true" }
    }

    private fun warnOnce(message: String) {
        if (warned.add(message)) SdkLog.w(message)
    }

    override fun schedule(delayMs: Long, task: Runnable) {
        try {
            executor.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun guarded(task: Runnable) = Runnable {
        try {
            task.run()
        } catch (e: Throwable) {
            warnOnce("internal error ignored: $e")
        }
    }

    fun <T> submit(task: Callable<T>): Future<T> = executor.submit(task)

    fun markFramework(name: String) {
        frameworks.add(name)
    }

    override fun record(type: String, data: Map<String, Any?>) {
        if (!recording) return
        try {
            val clean = sanitizeEvent(type, data) ?: return
            synchronized(lock) { recorder?.add(now(), type, clean) }
        } catch (e: Throwable) {
            warnOnce("dropping a $type event: $e")
        }
    }

    /**
     * Defense in depth: whatever an integration records goes through the
     * redactor again (idempotent), then the app's `beforeRecord`. Pointer and
     * lifecycle events carry only numbers and enum names.
     */
    private fun sanitizeEvent(type: String, data: Map<String, Any?>, hook: Boolean = true): Map<String, Any?>? {
        return try {
            var out: Map<String, Any?> = data
            if (type == EventTypes.HTTP && !captureBodies) out = withoutBodies(out)
            if (type != EventTypes.POINTER && type != EventTypes.LIFECYCLE && type != EventTypes.KEY) {
                @Suppress("UNCHECKED_CAST")
                val redacted = (currentRedactor.redactJson(out) as Map<String, Any?>).toMutableMap()
                if (type == EventTypes.HTTP) {
                    if (data.containsKey("key")) redacted["key"] = data["key"]
                    if (redacted["bodyEncoding"] == "base64") redacted["responseBody"] = data["responseBody"]
                    if (redacted["requestEncoding"] == "base64") redacted["requestBody"] = data["requestBody"]
                }
                if (type == EventTypes.FAILURE && data["signature"] is String) redacted["signature"] = data["signature"]
                out = redacted
            }
            val before = config.beforeRecord
            if (before == null || !hook) out else before.apply(type, out)
        } catch (e: Throwable) {
            warnOnce("dropping a $type event: sanitizer failed ($e)")
            null
        }
    }

    override fun reportError(error: Throwable) = onError(error, fatal = false)

    // -------------------------------------------------------------- startup

    fun start() {
        val armed = BugCapsuleReplay.armed()
        if (armed != null) {
            if (host.isDebuggable) {
                startReplay(armed)
                return
            }
            SdkLog.w("ignoring replay: the app is not debuggable")
        }
        try {
            prepareCapture()
        } catch (e: Throwable) {
            SdkLog.w("capture setup failed; the app runs without capture: $e")
            stopRecording()
        }
        if (remote != null) {
            schedule(0) {
                remote?.refresh()
                flushOutbox(null)
            }
        }
    }

    private fun scope(): String {
        val input = "${config.backend?.url ?: ""}|${config.backend?.ingestKey ?: ""}".toByteArray(Charsets.UTF_8)
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in input) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(h, 16)
    }

    private fun baseRedactor(remoteKeys: List<String> = emptyList()) = Redactor(
        config.redactKeys + remoteKeys,
        config.redactHeaders,
        config.redactPatterns,
    )

    private fun prepareCapture() {
        currentRedactor = baseRedactor()
        if (!config.enabled) return
        val s = CapsuleStore(overrides?.storageDir ?: host.storageRoot, config.maxSavedCaptures)
        store = s
        val t = overrides?.transport ?: config.backend?.let { BackendClient(it) }
        if (t != null) {
            transport = t
            outbox = UploadOutbox(s.outboxDir, config.outboxLimits)
            remote = RemoteConfigController(
                fetch = t::fetchConfig,
                cache = FileConfigCache(s.configFile),
                scope = scope(),
                onChange = { prev, next, rejected -> applyRemote(prev, next, rejected) },
                scheduler = { delay, task ->
                    val f = executor.schedule(guarded(task), delay, TimeUnit.MILLISECONDS)
                    Cancellable { f.cancel(false) }
                },
                maxStaleMs = config.maxStaleConfigMs,
            ).also { it.loadCached() }
        }
        val cached = remote?.current
        currentRedactor = baseRedactor(cached?.redactKeys ?: emptyList())
        captureBodies = config.captureNetworkBodies && (cached?.captureNetworkBodies ?: true)
        maxCapsuleBytes = cached?.maxCapsuleBytes ?: maxCapsuleBytes
        val record = decideRecording(cached)

        for (integration in integrations) {
            try {
                integration.setUp(this)
            } catch (e: Throwable) {
                SdkLog.w("${integration.name} setup failed: $e")
            }
        }
        capture = AndroidCapture(this, host).also { it.install() }
        if (record) {
            val launch = Checkpoint(
                kind = CheckpointKind.LAUNCH,
                t = now(),
                wallClockMs = System.currentTimeMillis(),
                afterSeq = 0,
                data = snapshot(CheckpointKind.LAUNCH),
            )
            synchronized(lock) {
                recorder = FlightRecorder(launch, config.maxEvents, config.windowMs)
            }
            recording = true
            installErrorHandler()
            schedulePeriodicCheckpoint()
        }
    }

    private fun effectiveRate(remote: RemoteCaptureConfig?): Double {
        val cap = config.productionSampleRate.coerceIn(0.0, 1.0)
        val remoteRate = remote?.sampleRate ?: 1.0
        return minOf(remoteRate, cap)
    }

    /** A cached kill switch applies however old; production sessions are sampled. */
    private fun decideRecording(remote: RemoteCaptureConfig?): Boolean {
        if (!config.enabled) return false
        if (remote != null && !remote.enabled) return false
        if (mode != CaptureMode.PRODUCTION) return true
        sessionRate = effectiveRate(remote)
        return samplingRandom.nextDouble() < sessionRate
    }

    private fun installErrorHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this, previous))
    }

    /** Called by [CrashHandler] on the crashing thread, before the previous handler. */
    fun onUncaught(uncaught: Throwable) {
        val error = unwrapFramework(uncaught)
        val replaying = engine
        if (replaying != null) {
            val failure = FailureRecord.exception(error, now(), currentRedactor::scrubText)
            replaying.onFailure(failure)
            // The process dies right after: report synchronously now.
            replaying.finishNow("the app crashed: ${failure.signature}")
            return
        }
        onError(error, fatal = true)
    }

    /**
     * `ActivityThread` wraps exceptions thrown from activity/service lifecycle
     * callbacks in `RuntimeException("Unable to start activity ...", cause)`;
     * the cause is the app's failure (and keeps signatures stable).
     */
    private fun unwrapFramework(error: Throwable): Throwable {
        var e = error
        repeat(3) {
            val cause = e.cause
            if (e.javaClass == RuntimeException::class.java && cause != null && e.message?.startsWith("Unable to ") == true) {
                e = cause
            } else {
                return e
            }
        }
        return e
    }

    private fun onError(error: Throwable, fatal: Boolean) {
        try {
            if (config.ignoreError?.ignore(error) == true) return
            handleFailure(FailureRecord.exception(error, now(), currentRedactor::scrubText), fatal)
        } catch (e: Throwable) {
            warnOnce("could not record failure: $e")
        }
    }

    fun handleFailure(failure: FailureRecord, fatal: Boolean = false) {
        val replaying = engine
        if (replaying != null) {
            replaying.onFailure(failure)
            return
        }
        if (!recording) return
        val event = failure.toJson().toMutableMap().also { it.remove("stack") }
        record(EventTypes.FAILURE, event)
        lastFailure = failure
        if (!seenSignatures.add(failure.signature)) return
        if (!config.autoCaptureOnFailure) return
        if (capsulesWritten.get() >= config.maxCapsulesPerSession) return
        if (!capturedSignatures.add(failure.signature)) return
        if (fatal) {
            // The process is about to die: persist synchronously, upload next launch.
            try {
                val r = writeCapsule(failure, emptyMap(), crash = true)
                if (r != null) SdkLog.w("crash captured ${r.capsuleId} (${r.eligibility.status.wire}) → ${r.file.path}; uploads on next launch")
            } catch (e: Throwable) {
                SdkLog.w("crash capture failed: $e")
            }
            return
        }
        // Let the failure settle (follow-up events) before writing.
        schedule(300) {
            val r = writeCapsule(failure, emptyMap(), crash = false)
            if (r != null) SdkLog.i("captured ${r.capsuleId} (${r.eligibility.status.wire}) → ${r.file.path}")
        }
    }

    // ---------------------------------------------------- capture callbacks

    fun onFirstDraw() {
        if (anchorMs != null) return
        anchorMs = now()
        wallAtAnchorMs = System.currentTimeMillis()
        if (engine != null) BugCapsuleReplay.anchorReached()
    }

    fun onLifecycle(state: String) {
        record(EventTypes.LIFECYCLE, mapOf("state" to state))
        val controller = remote ?: return
        when (state) {
            "resumed" -> schedule(0) {
                controller.onResumed()
                flushOutbox(null)
            }
            "paused" -> schedule(0) { controller.onPaused() }
        }
    }

    // ----------------------------------------------------------- snapshots

    private fun snapshot(kind: CheckpointKind): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        for (integration in integrations) {
            try {
                val value = integration.snapshot(kind) ?: continue
                var clean = currentRedactor.redactJson(value)
                config.beforeCheckpoint?.let { clean = it.apply(integration.name, clean) }
                if (clean != null) data[integration.name] = clean
            } catch (e: Throwable) {
                SdkLog.w("${integration.name} snapshot failed: $e")
            }
        }
        if (kind == CheckpointKind.PERIODIC && states.isNotEmpty()) {
            val out = LinkedHashMap<String, Any?>()
            for ((key, entry) in states.toSortedMap()) {
                try {
                    out[key] = currentRedactor.redactJson(entry.first.serialize())
                } catch (e: Throwable) {
                    SdkLog.w("state $key serializer failed: $e")
                }
            }
            var clean: Any? = out
            config.beforeCheckpoint?.let { clean = it.apply(STATE_KEY, clean) }
            if (clean != null) data[STATE_KEY] = clean
        }
        return data
    }

    private fun schedulePeriodicCheckpoint() {
        val task = object : Runnable {
            override fun run() {
                if (!recording || disposed) return
                periodicCheckpoint()
                host.postToMainDelayed(config.checkpointIntervalMs, this)
            }
        }
        checkpointTask = task
        host.postToMainDelayed(config.checkpointIntervalMs, task)
    }

    /** Runs on the main thread: state objects and navigation are main-thread objects. */
    fun periodicCheckpoint() {
        try {
            val r = synchronized(lock) { recorder } ?: return
            val t = now()
            val afterSeq = synchronized(lock) { r.lastSeq }
            val data = snapshot(CheckpointKind.PERIODIC)
            synchronized(lock) {
                if (recorder === r) {
                    r.addCheckpoint(Checkpoint(CheckpointKind.PERIODIC, t, System.currentTimeMillis(), afterSeq, data))
                }
            }
        } catch (e: Throwable) {
            warnOnce("periodic checkpoint failed: $e")
        }
    }

    // --------------------------------------------------------------- state

    fun registerState(key: String, serializer: StateSerializer, restorer: StateRestorer?) {
        states[key] = serializer to restorer
        val e = engine ?: return
        val data = J.obj(e.capsule.checkpoint.data[STATE_KEY]) ?: return
        if (!data.containsKey(key) || !restoredStates.add(key)) return
        if (e.inputsStarted) e.diverge("restore.late", "state $key registered after the first replayed input")
        val value = data[key]
        if (StorageValues.containsPlaceholder(value)) e.diverge("state.redacted", key)
        if (restorer == null) {
            e.diverge("restore.state", "$key has no restorer")
            return
        }
        try {
            restorer.restore(value)
        } catch (t: Throwable) {
            e.diverge("restore.state", "$key: $t")
        }
    }

    fun unregisterState(key: String, serializer: StateSerializer? = null) {
        if (serializer == null) states.remove(key) else states.computeIfPresent(key) { _, v -> if (v.first === serializer) null else v }
    }

    fun reportState(key: String) {
        if (!recording) return
        val entry = states[key]
        val value = try {
            entry?.first?.serialize()
        } catch (_: Throwable) {
            null
        }
        record(EventTypes.STATE, linkedMapOf("key" to key, "value" to value))
    }

    // -------------------------------------------------------------- capsule

    fun writeCapsule(failure: FailureRecord?, labels: Map<String, String>, crash: Boolean = false): CaptureResult? {
        if (!recording) return null
        for (integration in integrations) {
            try {
                integration.beforeCapsule(crash)
            } catch (_: Throwable) {
            }
        }
        val r = synchronized(lock) { recorder } ?: return null
        capsulesWritten.incrementAndGet()
        val (window, rewriteDropped) = synchronized(lock) { r.window() to r.rewriteDropped }
        val anchor = anchorMs ?: now()
        val wallAtAnchor = wallAtAnchorMs ?: (window.checkpoint.wallClockMs + anchor - window.checkpoint.t)

        val eligibility = EligibilityBuilder()
        val sha = gitSha
        if (sha == null) {
            eligibility.missing("build.gitSha", "BUGCAPSULE_GIT_SHA was not set at build time")
        } else if (gitDirty == true) {
            eligibility.missing("build.gitSha", "built from a tree with uncommitted changes")
        }
        if (failure == null) {
            eligibility.missing("failure.assertion", "manual capture without a failure assertion", blocking = true)
        }
        if (crash) {
            eligibility.missing("crash.persisted", "written by the crash handler; events after the crash are absent")
        }
        if (rewriteDropped > 0) {
            eligibility.missing("events.redaction", "$rewriteDropped buffered events were dropped when redaction rules changed")
        }
        if (window.hasGap) {
            eligibility.missing("events.window", "events between the checkpoint and the window were evicted; raise window or maxEvents")
        }
        for (integration in integrations) {
            try {
                integration.describeEligibility(eligibility, window)
            } catch (e: Throwable) {
                eligibility.missing("integration.${integration.name}", "eligibility check failed: $e")
            }
        }
        if (states.isNotEmpty() && window.checkpoint.kind == CheckpointKind.PERIODIC) {
            val stateData = J.obj(window.checkpoint.data[STATE_KEY]) ?: emptyMap()
            for ((k, v) in stateData) {
                if (StorageValues.containsPlaceholder(v)) eligibility.missing("state.$k", "state redacted on device")
            }
        }

        val pkg = host.packageInfo()
        val screen = host.screen()
        val events = window.events.map { it.shift(anchor) }
        val capabilities = LinkedHashMap(BASE_CAPABILITIES)
        for (integration in integrations) {
            try {
                capabilities.putAll(integration.capabilities())
            } catch (_: Throwable) {
            }
        }
        val capsule = Capsule(
            manifest = CapsuleManifest(
                capsuleId = Ids.uuidV4(),
                createdAtMs = System.currentTimeMillis(),
                mode = mode,
                app = AppInfo(
                    id = cut(config.appId ?: pkg.id, 200),
                    name = (config.appName ?: pkg.name)?.let { cut(it, 200) },
                    version = cut(config.appVersion ?: pkg.version, 200),
                    build = cut(config.appBuild ?: pkg.build, 200),
                    flavor = config.flavor?.let { cut(it, 200) },
                ),
                gitSha = sha,
                dirty = gitDirty,
                sdkName = SDK_NAME,
                sdkVersion = dev.bugcapsule.android.BuildConfig.SDK_VERSION,
                stateSchemaVersion = config.stateSchemaVersion,
                platform = PlatformInfo(
                    os = "android",
                    osVersion = host.osVersion(),
                    locale = host.locale(),
                    screenWidth = screen.widthDp,
                    screenHeight = screen.heightDp,
                    pixelRatio = screen.density,
                ),
                session = SessionInfo(sessionId, startedAtMs, wallAtAnchor, seed, now() - anchor),
                failure = failure?.shift(anchor),
                eligibility = eligibility.build(),
                integrations = integrations.map { it.name },
                eventCount = events.size,
                droppedEvents = window.droppedEvents,
                checkpointKind = window.checkpoint.kind,
                labels = sanitizeLabels(config.labels + labels),
                redactionKeys = currentRedactor.extraKeys,
                redactionHeaders = currentRedactor.extraHeaders,
                runtime = RuntimeInfo("android", host.apiLevel().toString(), "kotlin", frameworks.toList()),
                capabilities = capabilities,
            ),
            checkpoint = window.checkpoint.shift(anchor),
            events = events,
        )

        // Never queue what a reader would reject; keep it locally for inspection.
        val problem = CapsuleValidator.validate(CapsuleCodec.toJson(capsule))
        val bytes = CapsuleCodec.encode(capsule)
        if (killed) return null
        val s = store ?: return null
        val file = try {
            s.save(capsule.manifest.capsuleId, bytes)
        } catch (e: Exception) {
            SdkLog.w("could not save capsule (${e.message}); dropped")
            return null
        }
        if (killed) {
            s.clearSavedCaptures()
            return null
        }
        var upload: UploadResult? = null
        val box = outbox
        if (problem != null) {
            SdkLog.w("capsule ${capsule.manifest.capsuleId} failed validation, not uploaded: $problem")
            if (box != null) upload = UploadResult(UploadStatus.REJECTED, message = "invalid capsule: $problem")
        } else if (box != null) {
            upload = when {
                bytes.size > maxCapsuleBytes ->
                    UploadResult(UploadStatus.REJECTED, message = "capsule is ${bytes.size} bytes, limit $maxCapsuleBytes")
                !box.enqueue(capsule.manifest.capsuleId, bytes) ->
                    UploadResult(UploadStatus.REJECTED, message = "not queued (outbox limit or disk error)")
                killed -> {
                    box.purge()
                    return null
                }
                // Never promise an upload during process death: it waits for the next launch.
                crash -> UploadResult(UploadStatus.RETRY_LATER, message = "queued; uploads on next launch")
                else -> flushOutbox(capsule.manifest.capsuleId)
            }
        }
        return CaptureResult(capsule.manifest.capsuleId, file, capsule.manifest.eligibility, failure?.signature, upload)
    }

    private fun sanitizeLabels(labels: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in labels.entries.take(100)) {
            out[cut(k, 100)] = if (currentRedactor.isSensitiveKey(k)) Redactor.PLACEHOLDER else cut(currentRedactor.scrubText(v), 2000)
        }
        return out
    }

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)

    private val uploadsAllowed: Boolean get() = !disposed && (remote?.uploadsAllowed ?: false)

    /**
     * Uploads pending capsules (call on the executor). The remote config is
     * refreshed first when older than its TTL, so a kill switch set since the
     * last fetch is seen before anything is sent.
     */
    fun flushOutbox(onlyId: String?): UploadResult? {
        val box = outbox ?: return null
        val t = transport ?: return null
        val controller = remote ?: return null
        if (disposed) return null
        return try {
            controller.ensureFresh()
            if (!uploadsAllowed) {
                return if (onlyId == null) null else UploadResult(UploadStatus.RETRY_LATER, message = "waiting for remote config")
            }
            box.flush(
                upload = { bytes ->
                    val result = t.upload(bytes)
                    if (result.message == "capture_disabled") schedule(0) { controller.refresh() }
                    result
                },
                allowed = { uploadsAllowed },
                onlyId = onlyId,
            )
        } catch (e: Throwable) {
            SdkLog.w("outbox flush failed: $e")
            null
        }
    }

    // --------------------------------------------------------- remote config

    /**
     * Applies a fetched config: kill switch (stop, drop buffer, delete queued
     * and saved captures), rejected key (stop for the session, keep the
     * queue), sampling (lowered with probability new/old), bodies off (strip
     * buffered bodies), redaction keys (re-redact the buffer, drop http events).
     */
    private fun applyRemote(previous: RemoteCaptureConfig?, next: RemoteCaptureConfig, keyRejected: Boolean) {
        maxCapsuleBytes = next.maxCapsuleBytes
        if (keyRejected) {
            if (recording) SdkLog.w("capture stopped: ingest key rejected")
            stopRecording()
            return
        }
        if (!next.enabled) {
            disableCapture()
            return
        }
        if (mode == CaptureMode.PRODUCTION && recording) {
            val rate = effectiveRate(next)
            if (rate < sessionRate) {
                val keep = if (sessionRate <= 0) 0.0 else rate / sessionRate
                sessionRate = rate
                if (samplingRandom.nextDouble() >= keep) {
                    SdkLog.i("sample rate lowered to $rate; this session stops recording")
                    stopRecording()
                }
            }
        }
        val bodies = config.captureNetworkBodies && next.captureNetworkBodies
        if (captureBodies && !bodies) {
            synchronized(lock) {
                recorder?.rewrite(event = { e -> if (e.type == EventTypes.HTTP) e.copy(data = withoutBodies(e.data)) else e })
            }
        }
        captureBodies = bodies
        if (currentRedactor.extraKeys != (config.redactKeys + next.redactKeys).distinct()) {
            currentRedactor = baseRedactor(next.redactKeys)
            try {
                synchronized(lock) {
                    recorder?.rewrite(
                        // Request keys were computed with the old rules; replay could not match them.
                        event = { e ->
                            if (e.type == EventTypes.HTTP) {
                                null
                            } else {
                                sanitizeEvent(e.type, e.data, hook = false)?.let { CapsuleEvent(e.seq, e.t, e.type, it) }
                            }
                        },
                        checkpoint = { c ->
                            @Suppress("UNCHECKED_CAST")
                            c.copy(data = currentRedactor.redactJson(c.data) as Map<String, Any?>)
                        },
                    )
                }
            } catch (e: Throwable) {
                SdkLog.w("could not apply new redaction rules to the buffer ($e); dropping it")
                stopRecording()
            }
        }
    }

    private fun withoutBodies(data: Map<String, Any?>): Map<String, Any?> {
        val d = LinkedHashMap(data)
        d["requestBody"] = null
        d["responseBody"] = null
        d["bodyEncoding"] = "omitted"
        if (d["requestEncoding"] != null && d["requestEncoding"] != "json") d["requestEncoding"] = "omitted"
        return d
    }

    private fun stopRecording() {
        recording = false
        synchronized(lock) { recorder = null }
        checkpointTask?.let { host.removeFromMain(it) }
        checkpointTask = null
    }

    private fun disableCapture() {
        val was = recording
        killed = true
        stopRecording()
        if (was) SdkLog.w("capture disabled by remote config")
        try {
            outbox?.purge()
        } catch (_: Throwable) {
        }
        try {
            store?.clearSavedCaptures()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- replay

    private fun startReplay(armed: BugCapsuleReplay.Armed) {
        val capsule = armed.capsule
        val settings = armed.settings
        seed = capsule.manifest.session.seed
        random = Random(seed)
        // The capture's redaction rules, so request keys of redacted bodies match.
        currentRedactor = Redactor(
            (config.redactKeys + capsule.manifest.redactionKeys).distinct(),
            (config.redactHeaders + capsule.manifest.redactionHeaders).distinct(),
            config.redactPatterns,
        )
        if (capsule.manifest.stateSchemaVersion != config.stateSchemaVersion &&
            capsule.checkpoint.kind == CheckpointKind.PERIODIC
        ) {
            armed.reporter.report(
                ReplayReport(
                    outcome = ReplayOutcome.INCOMPATIBLE,
                    attempt = settings.attempt,
                    durationMs = 0,
                    strategy = settings.strategy,
                    expectedSignature = capsule.manifest.failure?.signature,
                    message = "state schema v${capsule.manifest.stateSchemaVersion} captured, app is v${config.stateSchemaVersion}; register a migration or mark the capsule stale",
                    attemptId = settings.attemptId,
                    runtime = "android",
                ),
            )
            BugCapsuleReplay.publishEngine(null)
            return
        }
        val e = ReplayEngine(capsule, settings, actions, gitSha, armed.reporter)
        engine = e
        e.precheck()
        for (d in armed.earlyDivergences) e.diverge(d.type, d.detail)
        installErrorHandler()

        val names = integrations.map { it.name }.toSet()
        for (name in capsule.manifest.integrations) {
            if (name !in names) e.diverge("integration.missing", "$name was used when capturing but is not set up")
        }
        for (integration in integrations) {
            try {
                integration.setUp(this)
            } catch (t: Throwable) {
                e.diverge("restore.${integration.name}", "setup failed: $t")
            }
        }
        for (name in capsule.checkpoint.data.keys) {
            if (name !in names && name != STATE_KEY) e.diverge("restore.missingIntegration", name)
        }
        for (integration in integrations) {
            if (integration.name in EarlyRestore.KEYS) continue // restored before Application.onCreate
            try {
                integration.restore(capsule.checkpoint.data[integration.name], this)
            } catch (t: Throwable) {
                e.diverge("restore.${integration.name}", t.toString())
            }
        }
        e.beforeReport = {
            val stateData = J.obj(capsule.checkpoint.data[STATE_KEY]) ?: emptyMap()
            for (key in stateData.keys) {
                if (key !in restoredStates) e.diverge("restore.missing", "state $key was captured but never registered")
            }
        }
        capture = AndroidCapture(this, host).also { it.install() }
        BugCapsuleReplay.publishEngine(e)
    }

    fun savedCaptures(): List<File> = store?.savedCaptures() ?: emptyList()

    fun dispose() {
        disposed = true
        stopRecording()
        remote?.dispose()
        capture?.uninstall()
        if (handlerInstalled) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler && current.runtime === this) Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        outbox?.close()
        executor.shutdownNow()
    }

    companion object {
        const val STATE_KEY = "state"

        val BASE_CAPABILITIES: Map<String, String> = linkedMapOf(
            "input.pointer.capture" to "supported",
            "input.pointer.replay" to "supported",
            "input.semantic.replay" to "explicit",
            "input.key.replay" to "supported",
            "text.replay" to "explicit",
            "lifecycle.replay" to "supported",
            "clock.virtual" to "explicit",
            "random.seeded" to "explicit",
            "crash.uncaught" to "partial",
        )
    }
}

/**
 * Chained default handler: records the failure (capture: synchronous crash
 * capsule; replay: synchronous report), then **always** calls the previous
 * handler, which terminates the process as before.
 */
internal class CrashHandler(
    val runtime: Runtime,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            runtime.onUncaught(error)
        } catch (_: Throwable) {
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                error.printStackTrace()
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }
}
