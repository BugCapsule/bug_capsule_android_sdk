package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.Ids as CoreIds
import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.internal.Cancellable
import dev.bugcapsule.android.internal.RemoteCaptureConfig
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.internal.Transport
import dev.bugcapsule.android.internal.UploadOutbox
import dev.bugcapsule.android.internal.UploadResult
import dev.bugcapsule.android.internal.UploadStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** App identity used for `release` and `contexts.app`. */
internal data class AppIdentity(val id: String, val version: String, val build: String, val name: String?)

/** What [Monitoring] needs from the runtime (one place to fake in tests). */
internal interface MonitoringEnvironment {
    val config: BugCapsuleConfig
    val mode: CaptureMode
    val redactor: Redactor
    val app: AppIdentity

    /** `<storage root>/monitoring`. */
    val dir: File
    val transport: Transport?
    val remote: RemoteCaptureConfig?

    /** Refreshes the remote config when older than its TTL (on the executor). */
    fun ensureConfigFresh()

    /** A recent enabled config allows uploads (same rule as capsules). */
    val uploadsAllowed: Boolean

    /** The server answered `capture_disabled`, or refused the credential: refresh the remote config. */
    fun onCaptureDisabled()
    fun schedule(delayMs: Long, task: Runnable): Cancellable
    fun <T> submit(task: Callable<T>): Future<T>
    val isForeground: Boolean

    /** `device`, `os`, `app`, `culture`, ... contexts of this device. */
    fun deviceContexts(): Map<String, Map<String, Any?>>

    /** `debugImages` of this build (the ProGuard mapping id). */
    val debugImages: List<Map<String, Any?>>
    val random: Random
    fun nowMs(): Long = System.currentTimeMillis()
    fun nanoTime(): Long = System.nanoTime()
}

/**
 * Monitoring (monitoring-v1): error events, release-health sessions,
 * transactions and feedback, sent as envelopes through their own persisted
 * outbox. Created by the runtime in capture mode (never during replay).
 * Every item is redacted with the capsule rules before it is persisted.
 */
internal class Monitoring(private val env: MonitoringEnvironment) : TracingHub {
    private val config = env.config
    val scope = Scope(maxBreadcrumbs())
    private val scopeStack = ThreadLocal<ArrayList<Scope>>()
    private val deriver = BreadcrumbDeriver()

    val release: String = config.release ?: "${env.app.id}@${env.app.version}+${env.app.build}"
    val environment: String = config.environment ?: when (env.mode) {
        CaptureMode.PRODUCTION -> "production"
        CaptureMode.QA -> "qa"
        CaptureMode.DEBUG -> "development"
    }
    val inApp = InAppRules(config.inAppIncludes.ifEmpty { listOf(env.app.id) }, config.inAppExcludes)

    @Volatile
    var lastEventId: String? = null
        private set

    /** False after `monitoringEnabled: false`, a kill switch or [close]: nothing is queued or sent. */
    @Volatile
    var active = true
        private set

    private val outbox: UploadOutbox? = env.transport?.let {
        UploadOutbox(File(env.dir, "envelopes"), config.envelopeOutboxLimits, extension = "envelope")
    }

    val installId: String by lazy { readInstallId() }

    val sessions: SessionTracker? =
        if (config.enableAutoSessionTracking) newSessionTracker() else null

    /** Trace of errors and requests made while no span is active (a new one per screen). */
    @Volatile
    private var propagation = Pair(Ids.traceId(), Ids.spanId())

    @Volatile
    private var flushScheduled = false

    // ------------------------------------------------------------- settings

    private fun maxBreadcrumbs(): Int = minOf(config.maxBreadcrumbs, env.remote?.maxBreadcrumbs ?: 100)

    private val errorSampleRate: Double get() = env.remote?.errorSampleRate ?: config.errorSampleRate

    val tracePropagationTargets: List<String> get() = env.remote?.tracePropagationTargets ?: config.tracePropagationTargets

    val sessionTrackingAllowed: Boolean get() = env.remote?.sessionTracking ?: true

    /** Applies a fetched remote config. */
    fun onRemoteConfig(next: RemoteCaptureConfig) {
        scope.maxBreadcrumbs = maxBreadcrumbs()
        if (!next.enabled || !next.monitoringEnabled) {
            if (active) SdkLog.w("monitoring disabled by remote config")
            disable(purge = true)
        }
    }

    /** Stops queueing; [purge] deletes queued envelopes (kill switch). */
    fun disable(purge: Boolean) {
        active = false
        if (purge) {
            try {
                outbox?.purge()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- scope

    fun currentScope(): Scope = scopeStack.get()?.lastOrNull() ?: scope

    fun <T> withScope(block: (Scope) -> T): T {
        val stack = scopeStack.get() ?: ArrayList<Scope>().also { scopeStack.set(it) }
        stack.add(currentScope().copy())
        try {
            return block(stack.last())
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    fun addBreadcrumb(breadcrumb: Breadcrumb) {
        if (!active) return
        val hook = config.beforeBreadcrumb
        val b = if (hook == null) {
            breadcrumb
        } else {
            try {
                hook.apply(breadcrumb) ?: return
            } catch (e: Throwable) {
                SdkLog.once("beforeBreadcrumb failed: $e")
                breadcrumb
            }
        }
        currentScope().addBreadcrumb(b)
    }

    /** A flight-recorder event (already redacted) becomes a breadcrumb. */
    fun onRecorded(type: String, data: Map<String, Any?>) {
        if (!active) return
        val b = deriver.derive(type, data, env.nowMs()) ?: return
        addBreadcrumb(b)
    }

    // ---------------------------------------------------------------- errors

    class Captured(val eventId: String, val traceId: String?)

    /**
     * Captures [error] as an event. [fatal]: the process is about to die
     * (persisted synchronously, level `fatal`, the session crashes).
     * [capsuleId]: the capsule written for this failure.
     */
    fun captureException(
        error: Throwable,
        mechanism: ExceptionMapper.Mechanism,
        options: CaptureOptions? = null,
        fatal: Boolean = false,
        capsuleId: String? = null,
    ): Captured? = capture(
        level = if (fatal) Level.FATAL else Level.ERROR,
        options = options,
        fatal = fatal,
        capsuleId = capsuleId,
        hint = Hint(error, mechanism.type, capsuleId),
    ) { ev ->
        ev["exception"] = linkedMapOf("values" to ExceptionMapper.values(error, mechanism, inApp, env.redactor::scrubText))
        // A crash: what every other thread was doing (the crashing one's stack is the exception's).
        if (fatal) ev["threads"] = ThreadDump.threads(Thread.currentThread(), Thread.currentThread(), inApp)
    }

    fun captureMessage(message: String, level: Level, options: CaptureOptions? = null): Captured? =
        capture(level, options, fatal = false, capsuleId = null, hint = Hint(null, null, null)) { ev ->
            ev["message"] = linkedMapOf("formatted" to Limits.cut(message, 8192))
            if (config.attachStacktrace) {
                ev["threads"] = listOf(
                    linkedMapOf(
                        "id" to Thread.currentThread().id,
                        "name" to Thread.currentThread().name,
                        "crashed" to false,
                        "current" to true,
                        "stacktrace" to linkedMapOf("frames" to ExceptionMapper.currentFrames(inApp)),
                    ),
                )
            }
        }

    /** A failed `BugCapsule.check` / `fail` assertion: an exception-like value typed by the assertion id. */
    fun captureAssertion(assertionId: String, message: String, capsuleId: String?): Captured? =
        capture(Level.ERROR, null, fatal = false, capsuleId = capsuleId, hint = Hint(null, "assertion", capsuleId)) { ev ->
            ev["exception"] = linkedMapOf(
                "values" to listOf(
                    linkedMapOf(
                        "type" to Limits.cut(assertionId, 200),
                        "value" to Limits.cut(message, 8192),
                        "mechanism" to ExceptionMapper.Mechanism("assertion", true).toJson(),
                        "stacktrace" to linkedMapOf("frames" to ExceptionMapper.currentFrames(inApp)),
                    ),
                ),
            )
        }

    /**
     * A prepared event body (ANR, native crash); [timestampMs] defaults to now.
     * [previousScope]: the scope of the process the event happened in, as
     * mirrored by [scopeJson] (a crash reported on the next launch); used in
     * place of the current scope.
     */
    fun captureRaw(
        level: Level,
        hint: Hint,
        fatal: Boolean,
        timestampMs: Long? = null,
        releaseOverride: String? = null,
        environmentOverride: String? = null,
        countInSession: Boolean = true,
        previousScope: Map<String, Any?>? = null,
        /** A capsule already queued for this event (crash capsules), with the [eventId] it links to. */
        capsuleId: String? = null,
        eventId: String? = null,
        fill: (MutableMap<String, Any?>) -> Unit,
    ): Captured? = capture(level, null, fatal, capsuleId, hint, timestampMs, releaseOverride, environmentOverride, countInSession, previousScope, eventId, fill)

    private fun capture(
        level: Level,
        options: CaptureOptions?,
        fatal: Boolean,
        capsuleId: String?,
        hint: Hint,
        timestampMs: Long? = null,
        releaseOverride: String? = null,
        environmentOverride: String? = null,
        countInSession: Boolean = true,
        previousScope: Map<String, Any?>? = null,
        presetEventId: String? = null,
        fill: (MutableMap<String, Any?>) -> Unit,
    ): Captured? {
        if (!active) return null
        return try {
            val snap = if (previousScope != null) Scope(0).snapshot() else currentScope().snapshot()
            val eventId = presetEventId ?: CoreIds.uuidV4()
            val ev = linkedMapOf<String, Any?>(
                "eventId" to eventId,
                "timestamp" to Iso8601.format(timestampMs ?: env.nowMs()),
                "level" to (options?.level ?: snap.level ?: level).wire,
                "runtime" to "android",
                "release" to (releaseOverride ?: release),
                "environment" to (environmentOverride ?: environment),
            )
            config.dist?.let { ev["dist"] = it }
            fill(ev)
            ev["fingerprint"] = Limits.fingerprint(options?.fingerprint ?: snap.fingerprint)
            ev["tags"] = Limits.tags(snap.tags + (options?.tags ?: emptyMap()))
            ev["user"] = user(snap.user)
            val trace = traceContext(snap)
            ev["contexts"] = contexts(snap, options?.contexts ?: emptyMap(), trace)
            ev["breadcrumbs"] = snap.breadcrumbs.takeLast(100).map { it.toJson() }
            ev["debugImages"] = (J.list(ev["debugImages"]) ?: emptyList()) + env.debugImages
            ev["request"] = null
            ev["extra"] = snap.extras + (options?.extra ?: emptyMap())
            ev["capsuleId"] = capsuleId
            if (previousScope != null) applyPreviousScope(ev, previousScope)
            J.list(ev["threads"])?.let { list -> ev["threads"] = ThreadDump.fit(list.mapNotNull { J.obj(it) }) }
            if (fatal) ev["level"] = Level.FATAL.wire

            // Release health counts every error, sampled or not.
            val sessionUpdate = if (countInSession) sessions?.onError(fatal) else null
            val keep = fatal || capsuleId != null || env.random.nextDouble() < errorSampleRate
            var payload: MutableMap<String, Any?>? = if (keep) ItemRedaction.redact(env.redactor, ev) else null
            if (payload != null) {
                val hook = config.beforeSend
                if (hook != null) {
                    payload = try {
                        hook.apply(BugCapsuleEvent(payload), hint)?.payload
                    } catch (e: Throwable) {
                        SdkLog.once("beforeSend failed: $e")
                        payload
                    }
                }
            }
            val items = ArrayList<Pair<String, Map<String, Any?>>>()
            payload?.let { items.add("event" to it) }
            sessionUpdate?.let { items.add("session" to it) }
            if (items.isNotEmpty()) enqueue(items, sync = fatal)
            if (payload == null) return null
            lastEventId = eventId
            Captured(eventId, J.str(J.obj(J.obj(payload["contexts"])?.get("trace"))?.get("traceId")))
        } catch (e: Throwable) {
            SdkLog.once("could not capture an event: $e")
            null
        }
    }

    private fun user(u: User?): Map<String, Any?>? {
        val json = LinkedHashMap(u?.toJson() ?: emptyMap())
        if (config.sendDefaultPii && json["ipAddress"] == null) json["ipAddress"] = "{{auto}}"
        return json.takeIf { it.isNotEmpty() }
    }

    private fun activeSpan(snap: Scope.Snapshot?): Span? =
        CurrentSpan.get() ?: (snap?.span ?: currentScope().activeSpan)?.takeIf { !it.isFinished }

    private fun traceContext(snap: Scope.Snapshot?): Map<String, Any?> {
        val span = activeSpan(snap)
        return if (span != null) {
            linkedMapOf("traceId" to span.traceId, "spanId" to span.spanId, "op" to span.op)
        } else {
            linkedMapOf("traceId" to propagation.first, "spanId" to propagation.second)
        }
    }

    private fun contexts(snap: Scope.Snapshot, extra: Map<String, Map<String, Any?>>, trace: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        try {
            out.putAll(env.deviceContexts())
        } catch (_: Throwable) {
        }
        (out["app"] as? Map<*, *>)?.let { app ->
            out["app"] = LinkedHashMap<String, Any?>().apply {
                for ((k, v) in app) put(k.toString(), v)
                put("inForeground", env.isForeground)
            }
        }
        out.putAll(snap.contexts)
        out.putAll(extra)
        out["trace"] = trace
        return out
    }

    // ---------------------------------------------------- crash-time scope

    @Volatile
    private var nativeScopeSink: ((ByteArray) -> Unit)? = null

    @Volatile
    private var scopeFile: File? = null

    @Volatile
    private var mirrorScheduled = false

    @Volatile
    private var fileWriteScheduled = false

    @Volatile
    private var lastScopeFileWriteMs = 0L

    /**
     * Keeps a copy of the global scope where the next launch can find it:
     * in [nativeSink] (the NDK handler's buffer, written into its crash
     * record) at most every [SCOPE_MIRROR_DELAY_MS], and in [file] at most
     * every [SCOPE_FILE_INTERVAL_MS] (for exits the process cannot record
     * itself: ANRs and low-memory kills, see [ExitReasons]).
     */
    fun mirrorScope(nativeSink: ((ByteArray) -> Unit)?, file: File?) {
        nativeScopeSink = nativeSink
        scopeFile = file
        scope.onChange = { scheduleMirror() }
        scheduleMirror()
    }

    private fun scheduleMirror() {
        if ((nativeScopeSink == null && scopeFile == null) || mirrorScheduled) return
        mirrorScheduled = true
        env.schedule(SCOPE_MIRROR_DELAY_MS) {
            mirrorScheduled = false
            mirrorScopeNow()
        }
    }

    /** [force]: write the file now even within [SCOPE_FILE_INTERVAL_MS] (tests). */
    internal fun mirrorScopeNow(force: Boolean = false) {
        if (!active) return
        val sink = nativeScopeSink
        val file = scopeFile
        if (sink == null && file == null) return
        try {
            val json = scopeJson(scope.snapshot())
            sink?.invoke(json)
            if (file != null) {
                val now = env.nowMs()
                if (force || now - lastScopeFileWriteMs >= SCOPE_FILE_INTERVAL_MS) {
                    lastScopeFileWriteMs = now
                    dev.bugcapsule.android.internal.Files.writeAtomically(file, json)
                } else if (!fileWriteScheduled) {
                    // Changed within the interval: one trailing write keeps the file current.
                    // Its own flag: the native buffer keeps getting every change meanwhile.
                    fileWriteScheduled = true
                    env.schedule(SCOPE_FILE_INTERVAL_MS - (now - lastScopeFileWriteMs)) {
                        fileWriteScheduled = false
                        mirrorScopeNow()
                    }
                }
            }
        } catch (e: Throwable) {
            SdkLog.once("could not mirror the scope for crash reports: $e")
        }
    }

    /**
     * The scope parts of an event (redacted, `app` context included) plus
     * this process's `pid`, `release` and `environment`, as one line of JSON
     * of at most [CRASH_SCOPE_MAX_BYTES]: the oldest breadcrumbs go first,
     * then extras and contexts.
     */
    internal fun scopeJson(snap: Scope.Snapshot): ByteArray {
        val contexts = LinkedHashMap<String, Any?>()
        try {
            env.deviceContexts()["app"]?.let { contexts["app"] = it }
        } catch (_: Throwable) {
        }
        contexts.putAll(snap.contexts)
        var crumbs = snap.breadcrumbs.takeLast(100).map { it.toJson() }
        var withExtras = true
        while (true) {
            val m = linkedMapOf<String, Any?>(
                "pid" to android.os.Process.myPid(),
                "release" to release,
                "environment" to environment,
                "level" to snap.level?.wire,
                "fingerprint" to snap.fingerprint,
                "tags" to snap.tags,
                "user" to user(snap.user),
                "extra" to if (withExtras) snap.extras else emptyMap<String, Any?>(),
                "contexts" to if (withExtras) contexts else emptyMap<String, Any?>(),
                "breadcrumbs" to crumbs,
            )
            val bytes = Json.stringify(ItemRedaction.redact(env.redactor, m)).toByteArray(Charsets.UTF_8)
            if (bytes.size <= CRASH_SCOPE_MAX_BYTES || (crumbs.isEmpty() && !withExtras)) return bytes
            if (crumbs.isNotEmpty()) crumbs = crumbs.drop(maxOf(1, crumbs.size / 4)) else withExtras = false
        }
    }

    // -------------------------------------------------------------- feedback

    fun captureFeedback(
        message: String,
        name: String?,
        email: String?,
        eventId: String?,
        capsuleId: String?,
        url: String?,
    ): String? {
        if (!active) return null
        val text = message.trim()
        if (text.isEmpty()) return null
        return try {
            val snap = currentScope().snapshot()
            val feedbackId = CoreIds.uuidV4()
            val payload = linkedMapOf<String, Any?>(
                "feedbackId" to feedbackId,
                "timestamp" to Iso8601.format(env.nowMs()),
                "eventId" to eventId,
                "name" to name?.let { Limits.cut(it, 200) },
                "email" to email?.let { Limits.cut(it, 200) },
                "message" to Limits.cut(text, 5000),
                "url" to url,
                "release" to release,
                "environment" to environment,
                "runtime" to "android",
                "user" to user(snap.user),
                "contexts" to linkedMapOf("trace" to traceContext(snap)),
                "tags" to Limits.tags(snap.tags),
                "capsuleId" to capsuleId,
            )
            enqueue(listOf("feedback" to ItemRedaction.redact(env.redactor, payload)), sync = false)
            feedbackId
        } catch (e: Throwable) {
            SdkLog.once("could not capture feedback: $e")
            null
        }
    }

    // --------------------------------------------------------------- tracing

    override fun nowMs(): Long = env.nowMs()

    override fun nanoTime(): Long = env.nanoTime()

    override fun schedule(delayMs: Long, task: Runnable): Cancellable = env.schedule(delayMs, task)

    override fun baggage(span: Span): String = TraceHeaders.baggage(
        span.traceId, release, environment, span.transaction.sampleRate, span.sampled,
    )

    /** The span outgoing work attaches to: the thread's current span, else the scope's. */
    fun activeSpan(): Span? = activeSpan(null)

    fun startTransaction(
        name: String,
        op: String,
        context: TraceContext? = null,
        bindToScope: Boolean = false,
        startMs: Long? = null,
    ): Transaction {
        val parentSampled = context?.parentSampled
        var rate: Double? = null
        val sampled = when {
            !active -> false
            parentSampled != null -> parentSampled.also { rate = context!!.sampleRate }
            else -> {
                rate = tracesRate(name, op, null)
                env.random.nextDouble() < (rate ?: 0.0)
            }
        }
        val tx = Transaction(
            this, Limits.cut(name, 200), op,
            context?.traceId ?: Ids.traceId(), Ids.spanId(), context?.parentSpanId,
            sampled, rate, startMs,
        )
        if (bindToScope) {
            val s = currentScope()
            s.activeSpan?.let { previous -> if (previous is Transaction && previous !== tx) previous.finish() }
            s.activeSpan = tx
            tx.doOnFinish { if (s.activeSpan === it) s.activeSpan = null }
            propagation = Pair(tx.traceId, tx.spanId)
        }
        return tx
    }

    private fun tracesRate(name: String, op: String, parentSampled: Boolean?): Double? {
        val sampler = config.tracesSampler
        if (sampler != null) {
            val r = try {
                sampler.sample(SamplingContext(name, op, parentSampled))
            } catch (e: Throwable) {
                SdkLog.once("tracesSampler failed: $e")
                null
            }
            if (r != null) return r.coerceIn(0.0, 1.0)
            return config.tracesSampleRate
        }
        return env.remote?.tracesSampleRate ?: config.tracesSampleRate
    }

    /** True when a trace started now could be sampled (skip work otherwise). */
    val tracingEnabled: Boolean
        get() = active && (config.tracesSampler != null || ((env.remote?.tracesSampleRate ?: config.tracesSampleRate ?: 0.0) > 0.0))

    fun continueTrace(traceparent: String?, baggage: String?): TraceContext? {
        val parent = TraceHeaders.parseTraceparent(traceparent) ?: return null
        val entries = TraceHeaders.parseBaggage(baggage)
        propagation = Pair(parent.traceId, Ids.spanId())
        return TraceContext(parent.traceId, parent.spanId, parent.sampled, entries["sample_rate"]?.toDoubleOrNull())
    }

    /** `traceparent` of the active span (or of the propagation context). */
    fun traceparent(): String {
        val span = activeSpan()
        return if (span != null) span.toTraceparent() else TraceHeaders.traceparent(propagation.first, propagation.second, null)
    }

    fun baggageHeader(): String {
        val span = activeSpan()
        return if (span != null) span.toBaggage() else TraceHeaders.baggage(propagation.first, release, environment, null, null)
    }

    override fun onTransactionFinished(transaction: Transaction) {
        if (!transaction.sampled || !active) return
        try {
            val snap = scope.snapshot()
            val payload = linkedMapOf<String, Any?>(
                "eventId" to CoreIds.uuidV4(),
                "traceId" to transaction.traceId,
                "spanId" to transaction.spanId,
                "parentSpanId" to transaction.parentSpanId,
                "name" to transaction.name,
                "op" to transaction.op,
                "startTimestamp" to Iso8601.format(transaction.startTimestampMs),
                "timestamp" to Iso8601.format(transaction.endTimestampMs ?: transaction.startTimestampMs),
                "status" to (transaction.status ?: SpanStatus.OK).wire,
                "release" to release,
                "environment" to environment,
                "runtime" to "android",
                "tags" to Limits.tags(snap.tags + transaction.tagsSnapshot()),
                "user" to user(snap.user),
                "contexts" to contexts(
                    snap,
                    emptyMap(),
                    linkedMapOf("traceId" to transaction.traceId, "spanId" to transaction.spanId, "op" to transaction.op),
                ),
                "measurements" to transaction.measurementsJson(),
                "spans" to transaction.childrenSnapshot().filter { it.isFinished }.take(Transaction.MAX_SPANS).map { it.toJson() },
            )
            config.dist?.let { payload["dist"] = it }
            transaction.dataSnapshot().takeIf { it.isNotEmpty() }?.let { payload["extra"] = it }
            var out: MutableMap<String, Any?>? = ItemRedaction.redact(env.redactor, payload)
            val hook = config.beforeSendTransaction
            if (hook != null && out != null) {
                out = try {
                    hook.apply(BugCapsuleEvent(out))?.payload
                } catch (e: Throwable) {
                    SdkLog.once("beforeSendTransaction failed: $e")
                    out
                }
            }
            if (out != null) enqueue(listOf("transaction" to out), sync = false)
        } catch (e: Throwable) {
            SdkLog.once("could not send a transaction: $e")
        }
    }

    /** An `http.client` span for an OkHttp call; null when no transaction is running and nothing is propagated. */
    fun startHttpSpan(method: String, url: String): HttpSpanHandle {
        val parent = activeSpan()
        val clean = env.redactor.redactUrl(url)
        val noQuery = clean.substringBefore('?').substringBefore('#')
        val span = parent?.startChild("http.client", "${method.uppercase()} $noQuery")?.also {
            it.setData("url", noQuery)
            it.setData("http.request.method", method.uppercase())
            clean.substringAfter('?', "").substringBefore('#').takeIf { q -> q.isNotEmpty() }?.let { q -> it.setData("http.query", q) }
        }
        val propagate = active && PropagationTargets.matches(url, tracePropagationTargets)
        return HttpSpanHandle(this, span, propagate)
    }

    // ------------------------------------------------------------- envelopes

    /**
     * Persists one envelope with [items], each redacted again here (after
     * `beforeSend` hooks, so nothing unredacted reaches disk). [sync]:
     * written before returning (crash handler); else on the executor, and a
     * send is scheduled.
     */
    fun enqueue(items: List<Pair<String, Map<String, Any?>>>, sync: Boolean) {
        val box = outbox ?: return
        if (!active) return
        val write = Runnable {
            try {
                val fitted = items.mapNotNull { (type, p) ->
                    val m = ItemRedaction.redact(env.redactor, p)
                    val f = if (type == "session") m else Limits.fit(m)
                    if (f == null) SdkLog.w("dropping a $type item larger than 1 MiB")
                    f?.let { linkedMapOf("type" to type, "payload" to it) }
                }
                if (fitted.isEmpty()) return@Runnable
                val envelope = linkedMapOf(
                    "sentAt" to null,
                    "sdk" to linkedMapOf("name" to SDK_NAME, "version" to dev.bugcapsule.android.BuildConfig.SDK_VERSION),
                    "items" to fitted,
                )
                val bytes = CapsuleCodec.gzip(Json.stringify(envelope).toByteArray(Charsets.UTF_8))
                box.enqueue(CoreIds.uuidV4(), bytes)
            } catch (e: Throwable) {
                SdkLog.once("could not queue an envelope: $e")
            }
        }
        if (sync) {
            write.run()
        } else {
            try {
                env.submit {
                    write.run()
                    scheduleFlush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        env.schedule(FLUSH_DELAY_MS) {
            flushScheduled = false
            flushNow()
        }
    }

    /** Sends queued envelopes (call on the executor). */
    fun flushNow() {
        val box = outbox ?: return
        val t = env.transport ?: return
        if (!active) return
        try {
            env.ensureConfigFresh()
            if (!env.uploadsAllowed || !active) return
            box.flush(
                upload = { bytes ->
                    val result = send(t, bytes)
                    if (result.message == "capture_disabled" || result.unauthorized) env.onCaptureDisabled()
                    result
                },
                allowed = { active && env.uploadsAllowed },
            )
        } catch (e: Throwable) {
            SdkLog.w("envelope flush failed: $e")
        }
    }

    /** Stamps `sentAt` and posts. */
    private fun send(t: Transport, stored: ByteArray): UploadResult {
        val envelope = try {
            J.obj(Json.parse(String(GZIPInputStream(ByteArrayInputStream(stored)).readBytes(), Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        } ?: return UploadResult(UploadStatus.REJECTED, message = "corrupt envelope")
        val out = LinkedHashMap(envelope)
        out["sentAt"] = Iso8601.format(env.nowMs())
        return t.sendEnvelope(CapsuleCodec.gzip(Json.stringify(out).toByteArray(Charsets.UTF_8)))
    }

    /** Sends everything queued, waiting up to [timeoutMs]; true when the queue is empty afterwards. */
    fun flush(timeoutMs: Long): Boolean {
        val box = outbox ?: return true
        return try {
            env.submit { flushNow() }.get(timeoutMs, TimeUnit.MILLISECONDS)
            box.entries().isEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** Queued envelope files (tests, diagnostics). */
    fun queuedEnvelopes(): List<File> = outbox?.entries()?.map { it.file } ?: emptyList()

    fun close(timeoutMs: Long) {
        sessions?.end()
        flush(timeoutMs)
        disable(purge = false)
        outbox?.close()
    }

    // --------------------------------------------------------------- helpers

    private fun newSessionTracker(): SessionTracker = SessionTracker(
        file = File(env.dir, "session.json"),
        installId = installId,
        release = release,
        environment = environment,
        timeoutMs = config.sessionTimeoutMs,
        nowMs = env::nowMs,
        userId = { scope.getUser()?.id },
        emit = { payload, sync -> if (sessionTrackingAllowed) enqueue(listOf("session" to payload), sync) },
        schedule = env::schedule,
    )

    private fun readInstallId(): String {
        val f = File(env.dir, "install_id")
        try {
            if (f.exists()) f.readText().trim().takeIf { it.length in 8..64 }?.let { return it }
        } catch (_: Exception) {
        }
        val id = CoreIds.uuidV4()
        try {
            dev.bugcapsule.android.internal.Files.writeAtomically(f, id.toByteArray())
        } catch (_: Exception) {
        }
        return id
    }

    companion object {
        /**
         * Puts a crashed process's scope (as [scopeJson] wrote it) on [ev]:
         * level, fingerprint, tags, user, extras, breadcrumbs, and its contexts
         * over this launch's device contexts.
         */
        fun applyPreviousScope(ev: MutableMap<String, Any?>, previous: Map<String, Any?>) {
            Level.parse(J.str(previous["level"]))?.let { ev["level"] = it.wire }
            J.list(previous["fingerprint"])?.let { list -> ev["fingerprint"] = Limits.fingerprint(list.mapNotNull { J.str(it) }) }
            J.obj(previous["tags"])?.let { tags -> ev["tags"] = Limits.tags(tags.mapNotNull { (k, v) -> J.str(v)?.let { k to it } }.toMap()) }
            J.obj(previous["user"])?.takeIf { it.isNotEmpty() }?.let { ev["user"] = it }
            J.obj(previous["extra"])?.let { ev["extra"] = it }
            J.list(previous["breadcrumbs"])?.let { ev["breadcrumbs"] = it.takeLast(100) }
            J.obj(previous["contexts"])?.let { mirrored ->
                // Device contexts are this launch's; `app` and the app's own contexts are the crashed process's.
                val merged = LinkedHashMap(J.obj(ev["contexts"]) ?: emptyMap())
                val trace = merged.remove("trace")
                merged.putAll(mirrored)
                if (trace != null) merged["trace"] = trace
                ev["contexts"] = merged
            }
        }

        const val SDK_NAME = "bugcapsule-android"
        const val FLUSH_DELAY_MS = 2_000L
        const val SCOPE_MIRROR_DELAY_MS = 100L
        const val SCOPE_FILE_INTERVAL_MS = 1_000L

        /** The native handler's scope buffer (bc_ndk_set_scope) is 64 KiB. */
        const val CRASH_SCOPE_MAX_BYTES = 64 * 1024
    }
}

/** An outgoing HTTP request's span and trace headers (see `bugcapsule-okhttp`). */
class HttpSpanHandle internal constructor(
    private val monitoring: Monitoring,
    /** The `http.client` span, null when no transaction is running. */
    val span: Span?,
    private val propagate: Boolean,
) {
    /**
     * Headers to add to the request: `traceparent` (unless the app set one)
     * and `baggage` (merged into the app's), only for `tracePropagationTargets`.
     */
    fun headers(existingTraceparent: String?, existingBaggage: String?): Map<String, String> {
        if (!propagate) return emptyMap()
        val out = LinkedHashMap<String, String>()
        if (existingTraceparent == null) {
            out[TraceHeaders.TRACEPARENT] = span?.toTraceparent() ?: monitoring.traceparent()
        }
        val ours = span?.toBaggage() ?: monitoring.baggageHeader()
        out[TraceHeaders.BAGGAGE] = TraceHeaders.mergeBaggage(existingBaggage, ours)
        return out
    }

    fun finish(statusCode: Int?, error: Throwable?) {
        val s = span ?: return
        if (statusCode != null && statusCode > 0) s.setData("http.response.status_code", statusCode)
        val status = when {
            statusCode != null && statusCode > 0 -> SpanStatus.fromHttpStatus(statusCode)
            error is java.io.InterruptedIOException && error.message == "timeout" -> SpanStatus.DEADLINE_EXCEEDED
            error is java.net.SocketTimeoutException -> SpanStatus.DEADLINE_EXCEEDED
            error != null && error.message == "Canceled" -> SpanStatus.CANCELLED
            error != null -> SpanStatus.INTERNAL_ERROR
            else -> SpanStatus.UNKNOWN
        }
        s.finish(status)
    }
}
