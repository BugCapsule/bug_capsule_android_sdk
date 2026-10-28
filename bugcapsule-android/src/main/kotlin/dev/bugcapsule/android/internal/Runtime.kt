package dev.bugcapsule.android.internal

import android.content.Context
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.BugCapsuleStatus
import dev.bugcapsule.android.CaptureResult
import dev.bugcapsule.android.ConnectionSource
import dev.bugcapsule.android.ConnectionState
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.StateRestorer
import dev.bugcapsule.android.StateSerializer
import dev.bugcapsule.android.core.AppInfo
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CapsuleManifest
import dev.bugcapsule.android.core.CapsuleValidator
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.FlightRecorder
import dev.bugcapsule.android.core.Ids
import dev.bugcapsule.android.core.InteractionLimits
import dev.bugcapsule.android.core.InteractionRecorder
import dev.bugcapsule.android.core.InteractionTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.PlatformInfo
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.RuntimeInfo
import dev.bugcapsule.android.core.SessionInfo
import dev.bugcapsule.android.feedback.BugCapsuleFeedbackDialog
import dev.bugcapsule.android.feedback.ShakeDetector
import dev.bugcapsule.android.monitoring.AnrWatchdog
import dev.bugcapsule.android.monitoring.AppIdentity
import dev.bugcapsule.android.monitoring.DeviceInfo
import dev.bugcapsule.android.monitoring.ExceptionMapper
import dev.bugcapsule.android.monitoring.ExitReasons
import dev.bugcapsule.android.monitoring.Hint
import dev.bugcapsule.android.monitoring.HttpSpanHandle
import dev.bugcapsule.android.monitoring.Level
import dev.bugcapsule.android.monitoring.Monitoring
import dev.bugcapsule.android.monitoring.MonitoringEnvironment
import dev.bugcapsule.android.monitoring.NativeCrashes
import dev.bugcapsule.android.monitoring.PreviousProcess
import dev.bugcapsule.android.monitoring.ScreenTracer
import dev.bugcapsule.android.replay.BugCapsuleReplay
import dev.bugcapsule.android.replay.CrashReplay
import dev.bugcapsule.android.replay.InteractionReplay
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
    /** Start the ANR watchdog and native crash handler (off in tests by default). */
    val monitoringThreads: Boolean = false,
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
    /** How [config]'s backend was configured (token or explicit), and why a token could not be used. */
    private val connection: ResolvedConnection = ResolvedConnection.NONE,
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

    /** The recorder mirrored to disk for crashes that leave no time to write a capsule (crash-capsules §1). */
    @Volatile
    private var journal: CrashJournal? = null

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

    /** Connection confirmation 2 (epoch ms): the backend accepted data from this process. */
    @Volatile
    private var firstEventAcceptedAt: Long? = null

    @Volatile
    private var firstCapsuleAcceptedAt: Long? = null
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

    /** Monitoring (monitoring-v1): null in replay, when disabled, or turned off by remote config at launch. */
    @Volatile
    var monitoring: Monitoring? = null
        private set
    private var screenTracer: ScreenTracer? = null
    private var anrWatchdog: AnrWatchdog? = null
    private var shakeDetector: ShakeDetector? = null

    @Volatile
    private var foreground = false

    @Volatile
    private var resumedActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /** Event of each failure signature, linked from capsules written later (manual capture). */
    private val failureEvents = ConcurrentHashMap<String, Monitoring.Captured>()

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
    override val isObserving: Boolean get() = recording || monitoring?.active == true
    override val isReplaying: Boolean get() = engine != null
    override val redactor: Redactor get() = currentRedactor
    override val captureNetworkBodies: Boolean get() = captureBodies
    override val maxBodyBytes: Int get() = config.maxBodyBytes
    override val applicationContext: Context get() = host.context
    override val replay: ReplayContext? get() = engine

    /** Capture of interaction dependencies (capsule-v2), shared by every adapter of the session. */
    private val interactionRecorder = InteractionRecorder(
        sink = { type, data -> record(type, data) },
        now = { now() },
        redactor = { currentRedactor },
        recording = { recording && !killed },
        payloadsEnabled = { captureBodies },
        disabledAdapters = { remote?.current?.disabledAdapters ?: emptyList() },
    )
    override val interactions: InteractionRecorder? get() = if (engine == null) interactionRecorder else null

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

    /**
     * Records an event in the flight recorder (when recording) and derives
     * a monitoring breadcrumb from it (when monitoring is on): integrations
     * record once, capsules and events both see it.
     */
    override fun record(type: String, data: Map<String, Any?>) {
        val mon = monitoring?.takeIf { it.active }
        if (!recording && mon == null) return
        try {
            val clean = sanitizeEvent(type, data) ?: return
            if (recording) {
                synchronized(lock) {
                    val r = recorder
                    val event = r?.add(now(), type, clean)
                    if (event != null) journal?.event(event, r.windowStartSeq)
                }
            }
            mon?.onRecorded(type, clean)
            if (type == EventTypes.ACTION && mon != null && config.enableUserInteractionTracing) {
                (clean["id"] as? String)?.let { id -> host.postToMain { screenTracer?.onAction(id) } }
            }
        } catch (e: Throwable) {
            warnOnce("dropping a $type event: $e")
        }
    }

    override fun startHttpSpan(method: String, url: String): HttpSpanHandle? {
        if (engine != null) return null
        val mon = monitoring?.takeIf { it.active } ?: return null
        return try {
            mon.startHttpSpan(method, url)
        } catch (_: Throwable) {
            null
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
                if (type == "screenshot") redacted["base64"] = data["base64"] // Binary pixels were masked by the provider.
                if (type == EventTypes.FAILURE && data["signature"] is String) redacted["signature"] = data["signature"]
                if (InteractionTypes.isInteraction(type)) {
                    // Match keys hash redacted values and payloads were redacted by the
                    // adapter's encoder; scrubbing them again would change keys and modes.
                    if (data["key"] is String) redacted["key"] = data["key"]
                    for (field in InteractionTypes.PAYLOAD_FIELDS) if (data.containsKey(field)) redacted[field] = data[field]
                }
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
        if (BugCapsuleReplay.isPostMortem() && host.isDebuggable) {
            // Judging how a replayed attempt died: this process must not capture, report or upload.
            SdkLog.i("replay post-mortem: capture is off in this process")
            return
        }
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
                monitoring?.flushNow()
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
        val t = (overrides?.transport ?: config.backend?.let { BackendClient(it) })?.let { observed(it) }
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
                onRejected = { f ->
                    val what = if (connection.source == ConnectionSource.TOKEN) "SDK token" else "ingest key"
                    SdkLog.w(
                        "the backend refused this app's $what (${f.reason ?: "HTTP ${f.status}"})" +
                            (f.detail?.let { ": $it" } ?: "") + ". Capture and uploads are off for this app session.",
                    )
                },
            ).also { it.loadCached() }
        }
        val cached = remote?.current
        currentRedactor = baseRedactor(cached?.redactKeys ?: emptyList())
        captureBodies = config.captureNetworkBodies && (cached?.captureNetworkBodies ?: true)
        maxCapsuleBytes = cached?.maxCapsuleBytes ?: maxCapsuleBytes
        interactionRecorder.limits = interactionLimits(cached)
        val record = decideRecording(cached)
        // Before the previous processes' crashes are reported: a journal left with this pid is theirs.
        val journalDir = if (record) CrashCapsules.prepare(File(s.root, CrashCapsules.DIR), android.os.Process.myPid()) else null

        for (integration in integrations) {
            try {
                integration.setUp(this)
            } catch (e: Throwable) {
                SdkLog.w("${integration.name} setup failed: $e")
            }
        }
        try {
            startMonitoring(s, t, cached)
        } catch (e: Throwable) {
            SdkLog.w("monitoring setup failed; the app runs without it: $e")
            monitoring = null
        }
        // After the crashes of earlier processes were reported (same executor): unclaimed journals go.
        // Only the main process reads exit records: another process must not delete a journal it would claim.
        if (PreviousProcess.isMainProcess(host.context)) {
            schedule(0) {
                CrashCapsules.cleanup(File(s.root, CrashCapsules.DIR), { pid -> CrashCapsules.isAlive(pid, host.context.packageName) })
            }
        }
        capture = AndroidCapture(this, host).also { it.install() }
        if (monitoring != null) installErrorHandler()
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
                if (journalDir != null) {
                    journal = CrashJournal(journalDir, { 2 * maxCapsuleBytes }).also { it.checkpoint(launch, 0) }
                }
            }
            recording = true
            if (journal != null) schedule(0) { refreshJournalHeader() }
            installErrorHandler()
            schedulePeriodicCheckpoint()
        }
    }

    // ----------------------------------------------------------- monitoring

    private val monitoringEnv = object : MonitoringEnvironment {
        override val config: BugCapsuleConfig get() = this@Runtime.config
        override val mode: CaptureMode get() = this@Runtime.mode
        override val redactor: Redactor get() = currentRedactor
        override val app: AppIdentity by lazy {
            val pkg = host.packageInfo()
            AppIdentity(config.appId ?: pkg.id, config.appVersion ?: pkg.version, config.appBuild ?: pkg.build, config.appName ?: pkg.name)
        }
        override val dir: File get() = File(store!!.root, "monitoring")
        override val transport: Transport? get() = this@Runtime.transport
        override val remote: RemoteCaptureConfig? get() = this@Runtime.remote?.current
        override fun ensureConfigFresh() {
            this@Runtime.remote?.ensureFresh()
        }
        override val uploadsAllowed: Boolean get() = this@Runtime.uploadsAllowed
        override fun onCaptureDisabled() {
            schedule(0) { this@Runtime.remote?.refresh() }
        }
        override fun schedule(delayMs: Long, task: Runnable): Cancellable = try {
            val f = executor.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS)
            Cancellable { f.cancel(false) }
        } catch (_: Exception) {
            Cancellable {}
        }
        override fun <T> submit(task: Callable<T>): Future<T> = executor.submit(task)
        override val isForeground: Boolean get() = foreground
        private val device by lazy { DeviceInfo(host.context, app) }
        override fun deviceContexts(): Map<String, Map<String, Any?>> = device.contexts()
        override val debugImages: List<Map<String, Any?>> by lazy {
            val id = config.proguardUuid ?: DeviceInfo.proguardUuid(host.context)
            if (id == null) emptyList() else listOf(linkedMapOf("type" to "proguard", "debugId" to id))
        }
        override val random: Random get() = samplingRandom
    }

    /**
     * Monitoring runs in every capture session (sampled for capsules or
     * not), unless turned off locally or by the cached remote config.
     */
    private fun startMonitoring(s: CapsuleStore, t: Transport?, cached: RemoteCaptureConfig?) {
        if (!config.monitoring) return
        if (cached != null && (!cached.enabled || !cached.monitoringEnabled)) return
        val mon = Monitoring(monitoringEnv)
        monitoring = mon
        val threads = overrides?.monitoringThreads ?: true

        // What the previous process left behind: native crash records, the
        // system's exit records, its scope, an unfinished session.
        val native = NativeCrashes(File(s.root, "native"))
        val mainProcess = PreviousProcess.isMainProcess(host.context)
        val readExits = mainProcess && (PreviousProcess.exitInfoOverride != null || (threads && config.enableExitInfoTracking))
        val exits = if (readExits) {
            ExitReasons(File(monitoringEnv.dir, "exit_info.json"), host.application.packageName, PreviousProcess.systemExits(host.context))
        } else {
            null
        }
        val scopeFile = File(monitoringEnv.dir, "scope.json")
        val watchdogAnrFile = File(monitoringEnv.dir, "watchdog_anr.json")
        val previous = PreviousProcess.collect(native, exits, scopeFile, watchdogAnrFile)
        mon.sessions?.recoverPrevious(previous.crashedAtMs)
        if (!previous.isEmpty) {
            // Tombstones and ANR dumps can be large: read them off the calling thread.
            monitoringEnv.submit {
                previous.report(monitoringEnv.app.id, mon.inApp) { c ->
                    val linked = crashCapsule(c, s, mon)
                    if (c.alreadyReported) return@report
                    mon.captureRaw(
                        Level.FATAL, Hint(null, c.mechanism, linked?.capsuleId), fatal = false,
                        timestampMs = c.timestampMs,
                        releaseOverride = c.release ?: linked?.release,
                        environmentOverride = c.environment ?: linked?.environment,
                        countInSession = false,
                        previousScope = c.scope,
                        capsuleId = linked?.capsuleId,
                        eventId = linked?.eventId,
                        fill = linked?.fill ?: c.fill,
                    )
                }
            }
        }
        val nativeInstalled = threads && config.enableNativeCrashHandling && native.install(mon.release, mon.environment)
        mon.mirrorScope(if (nativeInstalled) { json -> native.setScope(json) } else null, if (mainProcess) scopeFile else null)

        if (threads && config.enableAnrTracking) {
            anrWatchdog = AnrWatchdog(
                timeoutMs = config.anrTimeoutMs,
                postToMain = host::postToMain,
                mainThread = android.os.Looper.getMainLooper().thread,
                isForeground = { foreground },
                onAnr = { stack, blocked ->
                    if (mainProcess) PreviousProcess.noteWatchdogAnr(watchdogAnrFile)
                    journal?.flush()
                    val captured = mon.captureRaw(Level.ERROR, Hint(null, "anr", null), fatal = false) { ev ->
                        AnrWatchdog.fill(ev, stack, blocked, config.anrTimeoutMs, android.os.Looper.getMainLooper().thread, mon.inApp)
                    }
                    if (mainProcess) {
                        // Sent: if the system kills the app for this hang, its crash capsule links to this event.
                        // Sampled out or dropped by beforeSend: the ANR exit is reported as a fatal event instead.
                        if (captured != null) PreviousProcess.noteWatchdogAnr(watchdogAnrFile, captured.eventId) else Files.deleteQuietly(watchdogAnrFile)
                    }
                },
            ).also { it.start() }
        }
        if (config.enableActivityTracing || config.enableAppStartTracking) {
            screenTracer = ScreenTracer(mon, config, android.os.SystemClock.uptimeMillis()).also {
                host.application.registerActivityLifecycleCallbacks(it)
            }
        }
        if (config.enableShakeToReport) {
            shakeDetector = ShakeDetector(Runnable {
                host.postToMain {
                    val activity = resumedActivity?.get() ?: return@postToMain
                    try {
                        BugCapsuleFeedbackDialog.show(activity, eventId = mon.lastEventId)
                    } catch (e: Throwable) {
                        SdkLog.once("feedback dialog failed: $e")
                    }
                }
            })
        }
    }

    fun onActivityResumed(activity: android.app.Activity) {
        resumedActivity = java.lang.ref.WeakReference(activity)
    }

    fun startScreen(name: String, op: String, view: android.view.View?) = screenTracer?.startScreen(name, op, view)

    fun reportFullyDrawn() {
        screenTracer?.reportFullyDrawn()
    }

    fun captureFeedback(message: String, name: String?, email: String?, eventId: String?, captureCapsule: Boolean?): String? {
        val mon = monitoring?.takeIf { it.active } ?: return null
        val withCapsule = recording && (captureCapsule ?: config.captureCapsuleWithFeedback ?: (mode == CaptureMode.QA))
        val capsuleId = if (withCapsule) Ids.uuidV4() else null
        val id = mon.captureFeedback(message, name, email, eventId, capsuleId, null) ?: return null
        if (capsuleId != null) {
            val link = linkedMapOf<String, Any?>()
            if (eventId != null) link["eventId"] = eventId
            link["feedbackId"] = id
            link["release"] = mon.release
            link["environment"] = mon.environment
            submit { writeCapsule(lastFailure, mapOf("feedbackId" to id), capsuleId = capsuleId, monitoringLink = link) }
        }
        return id
    }

    /** Sends the failure's event; links [capsuleId] when a capsule will be written for it. */
    private fun failureEvent(mon: Monitoring, failure: FailureRecord, error: Throwable?, fatal: Boolean, capsuleId: String?): Monitoring.Captured? {
        val captured = if (error != null) {
            mon.captureException(
                error,
                if (fatal) ExceptionMapper.Mechanism("uncaught", false) else ExceptionMapper.Mechanism("manual", true),
                fatal = fatal,
                capsuleId = capsuleId,
            )
        } else {
            mon.captureAssertion(failure.assertionId ?: failure.type, failure.message, capsuleId)
        }
        if (captured != null) failureEvents[failure.signature] = captured
        return captured
    }

    /** `manifest.monitoring` of a capsule written for the failure with [event]. */
    private fun monitoringLink(event: Monitoring.Captured?, mon: Monitoring?): Map<String, Any?>? {
        if (event == null || mon == null) return null
        return linkedMapOf<String, Any?>("eventId" to event.eventId).also {
            if (event.traceId != null) it["traceId"] = event.traceId
            it["release"] = mon.release
            it["environment"] = mon.environment
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
            replaying.onFailure(failure, fatal = true)
            // The process dies right after: report synchronously now.
            replaying.finishNow("the app crashed: ${failure.signature}")
            return
        }
        onError(error, fatal = true)
        // The JVM crash wrote its capsule synchronously; the next launch has nothing to build from the journal.
        journal?.close(delete = true)
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
            handleFailure(FailureRecord.exception(error, now(), currentRedactor::scrubText), fatal, error)
        } catch (e: Throwable) {
            warnOnce("could not record failure: $e")
        }
    }

    /**
     * A failure: recorded, sent as a monitoring event, and captured as a
     * capsule (deduplicated by signature). When both happen the event
     * carries `capsuleId` and the capsule `manifest.monitoring.eventId`.
     */
    fun handleFailure(failure: FailureRecord, fatal: Boolean = false, error: Throwable? = null) {
        val replaying = engine
        if (replaying != null) {
            replaying.onFailure(failure)
            return
        }
        val mon = monitoring?.takeIf { it.active }
        if (!recording) {
            if (mon != null) failureEvent(mon, failure, error, fatal, null)
            return
        }
        val event = failure.toJson().toMutableMap().also { it.remove("stack") }
        record(EventTypes.FAILURE, event)
        journal?.flush()
        lastFailure = failure
        val capture = seenSignatures.add(failure.signature) &&
            config.autoCaptureOnFailure &&
            capsulesWritten.get() < config.maxCapsulesPerSession &&
            capturedSignatures.add(failure.signature)
        val capsuleId = if (capture) Ids.uuidV4() else null
        val captured = mon?.let { failureEvent(it, failure, error, fatal, capsuleId) }
        if (!capture || capsuleId == null) return
        val link = monitoringLink(captured, mon)
        if (fatal) {
            // The process is about to die: persist synchronously, upload next launch.
            try {
                val r = writeCapsule(failure, emptyMap(), crash = true, capsuleId = capsuleId, monitoringLink = link)
                if (r != null) SdkLog.w("crash captured ${r.capsuleId} (${r.eligibility.status.wire}) → ${r.file.path}; uploads on next launch")
            } catch (e: Throwable) {
                SdkLog.w("crash capture failed: $e")
            }
            return
        }
        // Let the failure settle (follow-up events) before writing.
        schedule(300) {
            val r = writeCapsule(failure, emptyMap(), crash = false, capsuleId = capsuleId, monitoringLink = link)
            if (r != null) SdkLog.i("captured ${r.capsuleId} (${r.eligibility.status.wire}) → ${r.file.path}")
        }
    }

    // ---------------------------------------------------- capture callbacks

    fun onFirstDraw() {
        if (anchorMs != null) return
        anchorMs = now()
        wallAtAnchorMs = System.currentTimeMillis()
        if (engine != null) BugCapsuleReplay.anchorReached()
        if (journal != null) schedule(0) { refreshJournalHeader() }
    }

    fun onLifecycle(state: String) {
        record(EventTypes.LIFECYCLE, mapOf("state" to state))
        if (state == "paused") journal?.flush()
        val mon = monitoring
        if (mon != null) {
            when (state) {
                "resumed" -> {
                    foreground = true
                    if (mon.active && mon.sessionTrackingAllowed) mon.sessions?.onForeground()
                    shakeDetector?.start(host.context)
                }
                "paused" -> {
                    foreground = false
                    mon.sessions?.onBackground()
                    shakeDetector?.stop()
                    schedule(0) { mon.flushNow() }
                }
            }
        }
        val controller = remote ?: return
        when (state) {
            "resumed" -> schedule(0) {
                controller.onResumed()
                flushOutbox(null)
                monitoring?.flushNow()
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
        if (kind == CheckpointKind.PERIODIC) {
            try {
                interactionRecorder.checkpoint()?.let { data[INTERACTIONS_KEY] = it }
            } catch (e: Throwable) {
                SdkLog.w("interaction checkpoint failed: $e")
            }
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
                    val checkpoint = Checkpoint(CheckpointKind.PERIODIC, t, System.currentTimeMillis(), afterSeq, data)
                    r.addCheckpoint(checkpoint)
                    journal?.checkpoint(checkpoint, r.windowStartSeq)
                }
            }
            if (journal != null) schedule(0) { refreshJournalHeader() }
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
        if (serializer == null) {
            states.remove(key)
        } else {
            // Conditional removal preserves a concurrently registered replacement.
            while (true) {
                val entry = states[key] ?: break
                if (entry.first !== serializer || states.remove(key, entry)) break
            }
        }
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

    fun writeCapsule(
        failure: FailureRecord?,
        labels: Map<String, String>,
        crash: Boolean = false,
        capsuleId: String = Ids.uuidV4(),
        monitoringLink: Map<String, Any?>? = null,
    ): CaptureResult? {
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
        val dependencies = try {
            interactionRecorder.dependencies(window.events)
        } catch (e: Throwable) {
            SdkLog.w("interaction dependencies failed: $e")
            emptyList()
        }
        windowEligibility(eligibility, window, dependencies)

        val events = window.events.map { it.shift(anchor) }
        val capsule = Capsule(
            manifest = manifest(
                capsuleId = capsuleId,
                wallAtAnchor = wallAtAnchor,
                durationMs = now() - anchor,
                labels = labels,
                dependencies = dependencies,
            ).copy(
                failure = failure?.shift(anchor),
                eligibility = eligibility.build(),
                eventCount = events.size,
                droppedEvents = window.droppedEvents,
                checkpointKind = window.checkpoint.kind,
                monitoring = monitoringLink ?: failure?.let { monitoringLink(failureEvents[it.signature], monitoring) },
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

    /** Eligibility gaps of [window] beyond the build and the recorder: integrations, dependencies, what a periodic checkpoint cannot restore. */
    private fun windowEligibility(eligibility: EligibilityBuilder, window: RecordedWindow, dependencies: List<CapsuleDependency>) {
        for (integration in integrations) {
            try {
                integration.describeEligibility(eligibility, window)
            } catch (e: Throwable) {
                eligibility.missing("integration.${integration.name}", "eligibility check failed: $e")
            }
        }
        for (d in dependencies) {
            if (d.gaps.isEmpty()) continue
            eligibility.missing(
                "dependency.${d.id}",
                "${d.adapter} coverage is partial: " + d.gaps.joinToString(", ") { g -> g.code + (g.count?.let { "×$it" } ?: "") },
            )
        }
        if (window.checkpoint.kind == CheckpointKind.PERIODIC) {
            val open = J.list(J.obj(window.checkpoint.data[INTERACTIONS_KEY])?.get("open")) ?: emptyList()
            val blocked = open.mapNotNull { J.obj(it) }.filter { it["restorable"] != true }
            if (blocked.isNotEmpty()) {
                eligibility.missing(
                    "interactions.restore",
                    "${blocked.size} connection(s)/subscription(s) were open at the periodic checkpoint and cannot be restored",
                    blocking = true,
                )
            }
            val stateData = J.obj(window.checkpoint.data[STATE_KEY]) ?: emptyMap()
            for ((k, v) in stateData) {
                if (StorageValues.containsPlaceholder(v)) eligibility.missing("state.$k", "state redacted on device")
            }
        }
    }

    /** The manifest fields this session knows at any time; the caller fills failure, eligibility and stats. */
    private fun manifest(
        capsuleId: String,
        wallAtAnchor: Long,
        durationMs: Long,
        labels: Map<String, String>,
        dependencies: List<CapsuleDependency>,
    ): CapsuleManifest {
        val pkg = host.packageInfo()
        val screen = host.screen()
        val capabilities = LinkedHashMap(BASE_CAPABILITIES)
        for (integration in integrations) {
            try {
                capabilities.putAll(integration.capabilities())
            } catch (_: Throwable) {
            }
        }
        return CapsuleManifest(
            capsuleId = capsuleId,
            createdAtMs = System.currentTimeMillis(),
            mode = mode,
            app = AppInfo(
                id = cut(config.appId ?: pkg.id, 200),
                name = (config.appName ?: pkg.name)?.let { cut(it, 200) },
                version = cut(config.appVersion ?: pkg.version, 200),
                build = cut(config.appBuild ?: pkg.build, 200),
                flavor = config.flavor?.let { cut(it, 200) },
            ),
            gitSha = gitSha,
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
            session = SessionInfo(sessionId, startedAtMs, wallAtAnchor, seed, durationMs),
            eligibility = dev.bugcapsule.android.core.Eligibility(dev.bugcapsule.android.core.EligibilityStatus.ELIGIBLE, emptyList()),
            integrations = integrations.map { it.name },
            eventCount = 0,
            droppedEvents = 0,
            checkpointKind = CheckpointKind.LAUNCH,
            labels = sanitizeLabels(config.labels + labels),
            redactionKeys = currentRedactor.extraKeys,
            redactionHeaders = currentRedactor.extraHeaders,
            runtime = RuntimeInfo("android", host.apiLevel().toString(), "kotlin", frameworks.toList()),
            capabilities = capabilities,
            dependencies = dependencies,
        )
    }

    // -------------------------------------------------------- crash journal

    /**
     * Writes `header.json` (crash-capsules §1): the manifest fields known
     * now, plus `pid`, `release`, `environment`, `writtenAt` and the
     * recorder time of the session anchor (`anchorT`, null before the first
     * frame). Runs on the executor; rewritten only when it changed.
     */
    private fun refreshJournalHeader() {
        val j = journal ?: return
        try {
            val dependencies = try {
                interactionRecorder.dependencies(emptyList(), all = true)
            } catch (_: Throwable) {
                emptyList()
            }
            val anchor = anchorMs
            val m = manifest("journal", wallAtAnchorMs ?: 0, 0, emptyMap(), dependencies).toJson()
            val header = linkedMapOf<String, Any?>("journal" to 1)
            for (key in JOURNAL_HEADER_KEYS) m[key]?.let { header[key] = it }
            header["session"] = LinkedHashMap(J.obj(m["session"])!!).also {
                it.remove("durationMs")
                if (anchor == null) it.remove("wallClockAtAnchorMs")
            }
            header["anchorT"] = anchor
            header["pid"] = android.os.Process.myPid()
            header["release"] = monitoring?.release
            header["environment"] = monitoring?.environment
            header["writtenAt"] = dev.bugcapsule.android.core.Iso8601.format(System.currentTimeMillis())
            j.header(header)
        } catch (e: Throwable) {
            warnOnce("crash journal header failed: $e")
        }
    }

    /** Tests: this process's journal directory once everything recorded so far is on disk; null when not journaling. */
    internal fun syncJournal(timeoutMs: Long = 5_000): File? {
        val j = journal ?: return null
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) // header writes are queued on the executor
        return if (j.sync(timeoutMs)) j.dir else null
    }

    /** After the recorder rewrote its buffer, the journal must not keep what it dropped or redacted. */
    private fun resetJournal() {
        if (journal == null) return
        synchronized(lock) {
            val r = recorder ?: return
            val (checkpoints, events) = r.retained()
            journal?.reset(checkpoints, events, r.windowStartSeq, r.lastSeq)
        }
        refreshJournalHeader()
    }

    /** A crash capsule queued for an earlier process's fatal event, and how to fill that event. */
    private class LinkedCrash(
        val capsuleId: String?,
        val eventId: String,
        val release: String?,
        val environment: String?,
        val fill: (MutableMap<String, Any?>) -> Unit,
    )

    /**
     * Builds the crash capsule of [c] from its process's journal (crash-capsules
     * §2) and queues it; the journal is deleted. Null without a journal.
     * Crash capsules do not count against `maxCapsulesPerSession`.
     */
    private fun crashCapsule(c: PreviousProcess.Crash, s: CapsuleStore, mon: Monitoring): LinkedCrash? {
        val pid = c.pid ?: return null
        if (killed || remote?.current?.enabled == false) return null
        val dir = CrashCapsules.find(File(s.root, CrashCapsules.DIR), pid) ?: return null
        return try {
            val journal = CrashCapsules.read(dir) ?: return null
            // Filled once: tombstones and ANR dumps are read here, and the event reuses it.
            val ev = LinkedHashMap<String, Any?>()
            c.fill(ev)
            val fill: (MutableMap<String, Any?>) -> Unit = { it.putAll(ev) }
            val eventId = c.reportedEventId ?: Ids.uuidV4()
            val release = c.release ?: J.str(journal.header["release"])
            val environment = c.environment ?: J.str(journal.header["environment"])
            val capsuleId = Ids.uuidV4()
            val link = linkedMapOf<String, Any?>(
                "eventId" to eventId,
                "release" to (release ?: mon.release),
                "environment" to (environment ?: mon.environment),
            )
            val built = CrashCapsules.build(
                journal,
                CrashCapsules.Crash(capsuleId, CrashCapsules.failureOf(ev, c.mechanism), c.mechanism, c.timestampMs, link),
                maxCapsuleBytes,
                ::windowEligibility,
            )
            var queued = false
            if (built != null) {
                try {
                    s.save(capsuleId, built.bytes)
                    queued = outbox?.enqueue(capsuleId, built.bytes) ?: true
                } catch (e: Exception) {
                    SdkLog.w("could not save crash capsule $capsuleId: ${e.message}")
                }
                if (queued) SdkLog.i("crash capsule $capsuleId built from the journal of pid $pid (${c.mechanism})")
            }
            LinkedCrash(if (queued) capsuleId else null, eventId, release, environment, fill)
        } catch (e: Throwable) {
            SdkLog.w("could not build the crash capsule of pid $pid: $e")
            null
        } finally {
            dir.deleteRecursively()
        }
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
                    // A kill switch or a refused credential since the last fetch: the config says what to do.
                    if (result.message == "capture_disabled" || result.unauthorized) schedule(0) { controller.refresh() }
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
        if (keyRejected) monitoring?.disable(purge = false) else monitoring?.onRemoteConfig(next)
        if (keyRejected) {
            // Logged once by the controller's onRejected, with the backend's reason.
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
        var rewritten = false
        if (captureBodies && !bodies) {
            rewritten = true
            synchronized(lock) {
                recorder?.rewrite(
                    event = { e ->
                        when {
                            e.type == EventTypes.HTTP -> e.copy(data = withoutBodies(e.data))
                            InteractionTypes.isInteraction(e.type) -> e.copy(data = withoutPayloads(e.data))
                            else -> e
                        }
                    },
                )
            }
        }
        if (dropDisabledAdapters(previous, next)) rewritten = true
        interactionRecorder.limits = interactionLimits(next)
        captureBodies = bodies
        if (currentRedactor.extraKeys != (config.redactKeys + next.redactKeys).distinct()) {
            rewritten = true
            currentRedactor = baseRedactor(next.redactKeys)
            try {
                synchronized(lock) {
                    recorder?.rewrite(
                        // Request keys were computed with the old rules; replay could not match them.
                        event = { e ->
                            if (e.type == EventTypes.HTTP) {
                                null
                            } else if (InteractionTypes.isInteraction(e.type)) {
                                droppedInteraction(e)
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
        if (rewritten) resetJournal()
    }

    /** Payload objects of an interaction event once payload capture is off: `omitted`, size and kind kept. */
    private fun withoutPayloads(data: Map<String, Any?>): Map<String, Any?> {
        val d = LinkedHashMap(data)
        for (field in InteractionTypes.PAYLOAD_FIELDS) {
            val p = J.obj(d[field]) ?: continue
            d[field] = linkedMapOf<String, Any?>("mode" to "omitted", "replayable" to false).also { out ->
                p["kind"]?.let { out["kind"] = it }
            }
        }
        return d
    }

    /** Interaction events cannot be partially kept: their dependency gets an `events.dropped` gap. */
    private fun droppedInteraction(e: CapsuleEvent) {
        val dep = e.data["dep"] as? String ?: return
        interactionRecorder.recorders().firstOrNull { it.descriptor.id == dep }
            ?.gap(DependencyGaps.EVENTS_DROPPED, "buffered events dropped (redaction rules or adapter disabled remotely)")
    }

    /** Remote `maxInteractionPayloadBytes` (capsule-v2 §7) lowers the per-payload limit of every adapter. */
    private fun interactionLimits(remote: RemoteCaptureConfig?) =
        InteractionLimits().lowered(maxPayloadBytes = remote?.maxInteractionPayloadBytes)

    /** Remote `disabledAdapters` (capsule-v2 §7): newly disabled adapters lose their buffered events; HTTP is untouched. */
    /** True when buffered events were dropped. */
    private fun dropDisabledAdapters(previous: RemoteCaptureConfig?, next: RemoteCaptureConfig): Boolean {
        val newly = next.disabledAdapters.toSet() - (previous?.disabledAdapters ?: emptyList()).toSet()
        if (newly.isEmpty()) return false
        val deps = interactionRecorder.recorders().filter { it.descriptor.adapter in newly }.map { it.descriptor.id }.toSet()
        if (deps.isEmpty()) return false
        SdkLog.i("interaction adapters disabled by remote config: ${newly.joinToString()}")
        synchronized(lock) {
            recorder?.rewrite(
                event = { e ->
                    if (InteractionTypes.isInteraction(e.type) && e.data["dep"] in deps) {
                        droppedInteraction(e)
                        null
                    } else {
                        e
                    }
                },
            )
        }
        return true
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
        val stopped = synchronized(lock) {
            recorder = null
            journal.also { journal = null }
        }
        // Outside the lock: closing waits for a write in progress, record() must not.
        stopped?.close(delete = true)
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
        val adapters = integrations.flatMap { i ->
            try {
                i.interactionAdapters()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val unsupported = InteractionReplay.preflight(capsule, adapters)
        if (unsupported != null) {
            SdkLog.w("replay incompatible: $unsupported")
            armed.reporter.report(
                ReplayReport(
                    outcome = ReplayOutcome.INCOMPATIBLE,
                    attempt = settings.attempt,
                    durationMs = 0,
                    strategy = settings.strategy,
                    expectedSignature = capsule.manifest.failure?.signature,
                    message = unsupported,
                    attemptId = settings.attemptId,
                    runtime = "android",
                ),
            )
            BugCapsuleReplay.publishEngine(null)
            return
        }
        val e = ReplayEngine(
            capsule, settings, actions, gitSha, armed.reporter,
            attemptMarker = CrashReplay.markerFile(host.context.noBackupFilesDir),
        )
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
            if (name !in names && name != STATE_KEY && name != INTERACTIONS_KEY) e.diverge("restore.missingIntegration", name)
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

    // ---------------------------------------------------------------- status

    /** Notes the first event and Capsule the backend accepted (connection confirmation), results unchanged. */
    private fun observed(t: Transport): Transport = object : Transport {
        override fun fetchConfig(): ConfigFetchResult = t.fetchConfig()

        override fun upload(bytes: ByteArray): UploadResult = t.upload(bytes).also {
            if (it.status == UploadStatus.UPLOADED && firstCapsuleAcceptedAt == null) firstCapsuleAcceptedAt = System.currentTimeMillis()
        }

        override fun sendEnvelope(gzippedJson: ByteArray): UploadResult = t.sendEnvelope(gzippedJson).also {
            if (it.status == UploadStatus.UPLOADED && firstEventAcceptedAt == null && hasEvent(gzippedJson)) {
                firstEventAcceptedAt = System.currentTimeMillis()
            }
        }
    }

    private fun hasEvent(gzippedJson: ByteArray): Boolean = try {
        val env = J.obj(Json.parse(String(java.util.zip.GZIPInputStream(gzippedJson.inputStream()).readBytes(), Charsets.UTF_8)))
        (J.list(env?.get("items")) ?: emptyList()).any { J.obj(it)?.get("type") == "event" }
    } catch (_: Exception) {
        false
    }

    /** Connection and instrumentation ([dev.bugcapsule.android.BugCapsule.status]). */
    fun status(): BugCapsuleStatus {
        val controller = remote
        val failure = controller?.lastFailure
        val tokenError = connection.tokenError
        val state = when {
            tokenError != null -> ConnectionState.valueOf(tokenError.name)
            config.backend == null && overrides?.transport == null -> ConnectionState.NOT_CONFIGURED
            !config.enabled || engine != null -> ConnectionState.DISABLED
            controller?.keyRejected == true -> ConnectionState.REJECTED
            controller?.current?.enabled == false -> ConnectionState.DISABLED
            controller?.fetchedThisSession != null -> ConnectionState.CONNECTED
            failure != null && failure.unreachable -> ConnectionState.OFFLINE
            else -> ConnectionState.CONNECTING
        }
        val reason = when {
            tokenError != null -> tokenError.code
            state == ConnectionState.REJECTED || state == ConnectionState.OFFLINE -> failure?.reason ?: failure?.message
            else -> null
        }
        val message = when {
            tokenError != null -> connection.tokenMessage
            state == ConnectionState.REJECTED -> failure?.detail ?: "the backend refused the credential; generate a new SDK token in the dashboard"
            state == ConnectionState.OFFLINE -> "the backend could not be reached; data is kept and retried"
            state == ConnectionState.NOT_CONFIGURED -> "no token or backend configured; capsules stay on the device"
            state == ConnectionState.DISABLED && engine != null -> "replaying: the SDK does not talk to the backend"
            state == ConnectionState.DISABLED && !config.enabled -> "disabled by the app (enabled = false)"
            state == ConnectionState.DISABLED -> "capture is turned off for this project (remote config)"
            else -> null
        }
        val names = integrations.map { it.name }
        val network = "okhttp" in names
        val navigation = "navigation" in names
        val appState = states.isNotEmpty() || names.any { it == "shared_preferences" || it == "datastore" }
        val missing = buildList {
            if (!network) add("network")
            if (!navigation) add("navigation")
            if (!appState) add("app_state")
        }
        return BugCapsuleStatus(
            BugCapsuleStatus.Connection(
                state = state,
                source = connection.source,
                apiUrl = config.backend?.url?.trimEnd('/'),
                reason = reason,
                message = message,
                configFetchedAt = controller?.fetchedThisSession,
                firstEventAcceptedAt = firstEventAcceptedAt,
                firstCapsuleAcceptedAt = firstCapsuleAcceptedAt,
            ),
            BugCapsuleStatus.Instrumentation(
                recording = recording,
                network = network,
                navigation = navigation,
                appState = appState,
                nativeCrashes = ndkPresent,
                frameworks = synchronized(frameworks) { frameworks.toList() },
                integrations = names,
                missing = missing,
            ),
        )
    }

    private val ndkPresent: Boolean by lazy {
        try {
            Class.forName("dev.bugcapsule.ndk.BugCapsuleNdk", false, Runtime::class.java.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun dispose() {
        disposed = true
        anrWatchdog?.shutdown()
        shakeDetector?.stop()
        screenTracer?.let { host.application.unregisterActivityLifecycleCallbacks(it) }
        monitoring?.disable(purge = false)
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

        /** capsule-v2 §6: `checkpoint.data.interactions` (open connections and subscriptions). */
        const val INTERACTIONS_KEY = "interactions"

        /** Manifest fields copied into the crash journal's header (crash-capsules §1). */
        private val JOURNAL_HEADER_KEYS = listOf(
            "mode", "app", "source", "sdk", "stateSchemaVersion", "platform",
            "integrations", "labels", "redaction", "runtime", "capabilities", "dependencies",
        )

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
