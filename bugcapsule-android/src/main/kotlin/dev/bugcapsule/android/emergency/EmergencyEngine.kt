package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Cancellable
import dev.bugcapsule.android.internal.Scheduler
import dev.bugcapsule.android.internal.SdkLog
import java.util.Collections
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/** The verified document in use, published as one immutable value (§5: never a half-applied revision). */
internal class Current(
    val document: PolicyDocument?,
    /** Response body of [document]'s envelope, persisted and re-verified on the next start. */
    val envelope: String?,
    val etag: String?,
    val receivedAtMs: Long?,
) {
    companion object {
        val EMPTY = Current(null, null, null, null)
    }
}

/**
 * The emergency client runtime of one process (emergency-client-v1 §5, §7):
 * startup from the re-verified cache, refresh with backoff, acceptance and
 * anti-replay, receipts, runtime reports, failure modes and replay mode.
 *
 * Threading: fetches, acceptance, persistence and timers run on [executor]
 * (one thread); [evaluate], [getPolicy] and [status] read the volatile
 * [current] snapshot from any thread without locking.
 */
internal class EmergencyEngine(
    val environment: String,
    private val refreshIntervalMs: Long,
    private val failureModes: Map<String, EmergencyFailureMode>,
    /** Null: the anchor is invalid and emergency policies are off. */
    val anchor: TrustAnchor?,
    private val anchorError: String?,
    private val store: EmergencyStore,
    /** Resolved for every fetch; null while there is no backend connection. */
    private val transport: () -> EmergencyTransport?,
    private val executor: ExecutorService,
    private val scheduler: Scheduler,
    private val nowMs: () -> Long,
    private val random: Random,
    private val replaying: () -> Boolean,
    /** The decision of a feature key and subject while [replaying]. */
    private val replayed: (String, String?) -> EmergencyDecision = { key, _ -> EmergencyContract.replayAllowed(key) },
    private val appVersion: () -> String?,
    private val sdkVersion: String,
    /** Called on [executor] after the snapshot changed (the facade hops to the main thread). */
    private val onSnapshot: (EmergencySnapshot) -> Unit,
    foreground: Boolean,
) {
    @Volatile
    var current: Current = Current.EMPTY
        private set

    @Volatile
    var installationId: String = ""
        private set

    @Volatile
    var lastFetchAtMs: Long? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var credentialRejected = false
        private set

    /** Delay of the scheduled refresh; null when none is scheduled (tests, diagnostics). */
    @Volatile
    var scheduledDelayMs: Long? = null
        private set

    @Volatile
    private var foreground = foreground

    @Volatile
    private var disposed = false

    // Confined to the executor after start().
    private var highestRevision = 0L
    private var failures = 0
    private var lastSuccessAtMs: Long? = null
    private var timer: Cancellable? = null
    private var notifiedState: EmergencyState? = null
    private var notifiedRevision: Long? = null

    // Runtime reports (§7.6), confined to the executor.
    private var runtime: Map<String, Any?>? = null
    private var runtimeKey: String? = null
    private var sentRuntimeKey: String? = null
    private var runtimeSentAtMs: Long? = null
    private var runtimeTimer: Cancellable? = null

    private val lock = Any()
    private var inFlight: FutureTask<EmergencyStatus>? = null
    private val sentReceipts = Collections.synchronizedSet(HashSet<String>())
    private val warned = Collections.synchronizedSet(HashSet<String>())

    /** §7.1 on the calling thread: re-verify the cache, publish it, then fetch in the background. */
    fun start() {
        val a = anchor
        if (a != null) loadCache(a)
        installationId = store.readInstallationId() ?: EmergencyStore.newInstallationId().also { id ->
            execute {
                try {
                    store.writeInstallationId(id)
                } catch (e: Exception) {
                    warnOnce("emergency: could not persist the installation id: $e")
                }
            }
        }
        notifiedState = stateOf(current.document, nowMs())
        notifiedRevision = current.document?.revision
        if (current.document != null) {
            val snapshot = snapshotOf(current)
            execute { onSnapshot(snapshot) }
        }
        if (a != null && !replaying()) refresh()
    }

    private fun loadCache(a: TrustAnchor) {
        val cached = store.read() ?: return
        if (cached.application != a.app || cached.environment != environment) {
            // Another application or environment: its revisions say nothing about this one.
            execute { persistQuietly() }
            return
        }
        highestRevision = cached.highestRevision
        val envelope = cached.envelope ?: return
        val verification = try {
            EnvelopeVerifier.verify(Json.parse(envelope), a, environment)
        } catch (_: Exception) {
            Verification.Failed(Reasons.MALFORMED)
        }
        when (verification) {
            is Verification.Ok -> {
                current = Current(verification.document, envelope, cached.etag, cached.receivedAtMs)
                highestRevision = maxOf(highestRevision, verification.document.revision)
            }
            is Verification.Failed -> {
                SdkLog.w("emergency: discarded the cached policy document (${verification.reason}), e.g. after a trust anchor change")
                execute { persistQuietly() }
            }
        }
    }

    // ----------------------------------------------------------- evaluation

    fun evaluate(featureKey: String, context: EmergencyContext?): EmergencyDecision {
        if (replaying()) return replayed(featureKey, context?.subject)
        val doc = current.document
        val now = nowMs()
        if (doc != null && now <= doc.expiresAtMs + EmergencyContract.GRACE_MS) {
            return EmergencyContract.decide(doc.policies, doc.revision, featureKey, input(context, now), EmergencyDecisionSource.POLICY)
        }
        return when (EmergencyContract.failureModeFor(failureModes, featureKey)) {
            EmergencyFailureMode.FAIL_OPEN -> unavailable(featureKey, EmergencyAction.ALLOW)
            EmergencyFailureMode.FAIL_CLOSED -> unavailable(featureKey, EmergencyAction.BLOCK)
            EmergencyFailureMode.USE_LAST_VALID_POLICY ->
                if (doc == null) {
                    unavailable(featureKey, EmergencyAction.ALLOW)
                } else {
                    EmergencyContract.decide(doc.policies, doc.revision, featureKey, input(context, now), EmergencyDecisionSource.STALE_POLICY)
                }
        }
    }

    /** The policy that decides [featureKey] now (from the document [evaluate] would use), or null. */
    fun getPolicy(featureKey: String, context: EmergencyContext?): EmergencyPolicy? {
        if (replaying()) return null
        val doc = current.document ?: return null
        val now = nowMs()
        val usable = now <= doc.expiresAtMs + EmergencyContract.GRACE_MS
        if (!usable && EmergencyContract.failureModeFor(failureModes, featureKey) != EmergencyFailureMode.USE_LAST_VALID_POLICY) return null
        return EmergencyContract.winner(doc.policies, featureKey, input(context, now))
    }

    private fun input(context: EmergencyContext?, now: Long) =
        EmergencyContract.Input(EmergencyContract.PLATFORM, appVersion(), context?.subject ?: installationId, now)

    private fun unavailable(featureKey: String, action: EmergencyAction) =
        EmergencyContract.fixed(featureKey, action, EmergencyContract.POLICY_UNAVAILABLE, EmergencyDecisionSource.FAILURE_MODE)

    fun status(): EmergencyStatus {
        val c = current
        val d = c.document
        return EmergencyStatus(
            initialized = true,
            state = stateOf(d, nowMs()),
            revision = d?.revision,
            issuedAtMs = d?.issuedAtMs,
            expiresAtMs = d?.expiresAtMs,
            receivedAtMs = c.receivedAtMs,
            lastFetchAtMs = lastFetchAtMs,
            lastError = lastError ?: anchorError,
            credentialRejected = credentialRejected,
            trustAnchorValid = anchor != null,
            environment = environment,
            replaying = replaying(),
        )
    }

    // -------------------------------------------------------------- refresh

    /** Fetches in the background; a call while a fetch is in flight joins it (§7.2). */
    fun refresh(): Future<EmergencyStatus> {
        synchronized(lock) {
            inFlight?.let { if (!it.isDone) return it }
            val task = FutureTask {
                try {
                    fetchOnce()
                } catch (e: Throwable) {
                    lastError = "internal error: $e"
                    warnOnce("emergency: refresh failed: $e")
                }
                try {
                    notifyIfChanged()
                } catch (e: Throwable) {
                    warnOnce("emergency: notifying listeners failed: $e")
                }
                status()
            }
            return try {
                executor.execute(task)
                inFlight = task
                task
            } catch (_: RejectedExecutionException) {
                FutureTask { status() }.apply { run() }
            }
        }
    }

    private fun fetchOnce() {
        if (disposed) return
        cancelTimer()
        val a = anchor ?: return
        if (replaying() || credentialRejected) return
        val t = transport()
        if (t == null) {
            fail("no backend connection: pass token(…) or backend(…) to EmergencyConfig, or call BugCapsule.init with a token", null)
            return
        }
        val cur = current
        val result = try {
            t.fetchPolicies(a.app, environment, if (cur.document != null) cur.etag else null, appVersion())
        } catch (e: Exception) {
            EmergencyFetchResult.Failed(e.toString())
        }
        if (disposed) return
        lastFetchAtMs = nowMs()
        when (result) {
            is EmergencyFetchResult.NotModified ->
                if (cur.document != null) succeed() else fail("HTTP 304 without a cached document", null)
            is EmergencyFetchResult.Failed ->
                if (result.credentialRejected) {
                    credentialRejected = true
                    failures++
                    lastError = result.message
                    SdkLog.e(
                        "emergency: the backend refused the credential (${result.message}); no more fetches in this process. " +
                            "The cached policy document stays in use until it expires.",
                    )
                } else {
                    fail(result.message, result.retryAfterMs)
                }
            is EmergencyFetchResult.Fetched -> handle(a, t, result)
        }
    }

    /** §4 then §5. */
    private fun handle(a: TrustAnchor, t: EmergencyTransport, result: EmergencyFetchResult.Fetched) {
        val text: String? = try {
            Json.decodeUtf8(result.body)
        } catch (_: Exception) {
            null
        }
        val parsed: Any? = try {
            text?.let { Json.parse(it) }
        } catch (_: Exception) {
            null
        }
        val verification = EnvelopeVerifier.verify(parsed, a, environment)
        if (verification is Verification.Failed) {
            reject(a, t, verification.reason, verification.revision ?: result.revisionHint ?: 0)
            return
        }
        val doc = (verification as Verification.Ok).document
        val cur = current.document
        val now = nowMs()
        when {
            doc.revision < highestRevision -> reject(a, t, Reasons.STALE_REVISION, doc.revision)
            cur != null && doc.revision == cur.revision && doc.issuedAtMs <= cur.issuedAtMs -> succeed()
            now - doc.expiresAtMs > EmergencyContract.GRACE_MS -> reject(a, t, Reasons.EXPIRED_ON_ARRIVAL, doc.revision)
            else -> {
                current = Current(doc, text, result.etag, now)
                highestRevision = maxOf(highestRevision, doc.revision)
                persistQuietly()
                succeed()
                receipt(a, t, doc.revision, "APPLIED", null)
            }
        }
    }

    private fun reject(a: TrustAnchor, t: EmergencyTransport, reason: String, revision: Long) {
        warnOnce("emergency: rejected a policy document ($reason); the current one stays in use")
        receipt(a, t, revision, "FAILED", reason)
        fail("document rejected: $reason", null)
    }

    private fun succeed() {
        failures = 0
        lastError = null
        lastSuccessAtMs = nowMs()
        schedule(refreshIntervalMs)
    }

    /** §7.2: `min(interval × 2^(failures−1), 15 min)`, raised to Retry-After, ±20 % jitter. */
    private fun fail(message: String, retryAfterMs: Long?) {
        failures++
        lastError = message
        var delay = minOf(refreshIntervalMs * (1L shl minOf(failures - 1, 20)), MAX_BACKOFF_MS)
        if (retryAfterMs != null && retryAfterMs > delay) delay = retryAfterMs
        schedule(Math.round(delay * (0.8 + random.nextDouble() * 0.4)))
    }

    private fun schedule(delayMs: Long) {
        cancelTimer()
        if (disposed || !foreground || credentialRejected || anchor == null || replaying()) return
        scheduledDelayMs = delayMs
        timer = scheduler.schedule(delayMs) { refresh() }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        scheduledDelayMs = null
    }

    /** §7.3: once per (revision, status, errorCode) and process; fire-and-forget. APPLIED and FAILED carry the latest runtime (§7.6). */
    private fun receipt(a: TrustAnchor, t: EmergencyTransport, revision: Long, status: String, errorCode: String?) {
        if (replaying()) return
        if (!sentReceipts.add("$revision|$status|$errorCode")) return
        send(a, t, receiptBody(revision, status, errorCode, runtime))
    }

    private fun receiptBody(revision: Long, status: String, errorCode: String?, runtime: Map<String, Any?>?): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "environment" to environment,
            "platform" to EmergencyContract.PLATFORM,
            "installationId" to installationId,
            "appVersion" to appVersion()?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
            "sdkVersion" to sdkVersion.take(64),
            "revision" to maxOf(revision, 0L),
            "status" to status,
            "errorCode" to errorCode,
        ).apply { if (runtime != null) put("runtime", runtime) }

    private fun send(a: TrustAnchor, t: EmergencyTransport, body: Map<String, Any?>) {
        execute {
            try {
                val code = t.sendReceipt(a.app, body)
                if (code !in 200..299) SdkLog.w("emergency: receipt refused (HTTP $code)")
            } catch (e: Exception) {
                SdkLog.w("emergency: receipt not sent: $e")
            }
        }
    }

    // ------------------------------------------------------- runtime reports

    /**
     * §7.6: [runtime] is the validated `runtime` object (any thread). It is
     * sent when it changes, never the same object twice in a row, and at most
     * once every [RUNTIME_INTERVAL_MS]; a change within the interval is sent
     * when the interval ends, and only the latest one. RUNTIME receipts are
     * exempt from the once-per-(revision, status, errorCode) rule of §7.3.
     */
    fun reportRuntime(runtime: Map<String, Any?>) {
        execute {
            val key = Json.stringify(runtime)
            if (key == runtimeKey) return@execute
            this.runtime = runtime
            runtimeKey = key
            flushRuntime()
        }
    }

    private fun flushRuntime() {
        val key = runtimeKey ?: return
        if (disposed || key == sentRuntimeKey || runtimeTimer != null) return
        val now = nowMs()
        val last = runtimeSentAtMs
        if (last != null && now - last in 0 until RUNTIME_INTERVAL_MS) {
            runtimeTimer = scheduler.schedule(last + RUNTIME_INTERVAL_MS - now) {
                execute {
                    runtimeTimer = null
                    flushRuntime()
                }
            }
            return
        }
        if (replaying() || credentialRejected) return
        val a = anchor ?: return
        val t = transport() ?: return
        sentRuntimeKey = key
        runtimeSentAtMs = now
        send(a, t, receiptBody(highestRevision, "RUNTIME", null, runtime))
    }

    // ------------------------------------------------------------- lifecycle

    /** The app came to the foreground: fetch, unless a fetch succeeded moments ago. */
    fun onForeground() {
        foreground = true
        execute {
            if (disposed || anchor == null || credentialRejected || replaying()) return@execute
            val last = lastSuccessAtMs
            val age = last?.let { nowMs() - it }
            if (failures == 0 && age != null && age in 0 until FOREGROUND_REFETCH_AFTER_MS) {
                if (timer == null) schedule(refreshIntervalMs - age)
            } else {
                refresh()
            }
        }
    }

    /** No timers run in the background (§7.2). */
    fun onBackground() {
        foreground = false
        execute { cancelTimer() }
    }

    fun dispose() {
        disposed = true
        timer?.cancel()
        runtimeTimer?.cancel()
    }

    // ------------------------------------------------------------- internals

    private fun persistQuietly() {
        val a = anchor ?: return
        val c = current
        try {
            store.write(EmergencyStore.Cached(a.app, environment, c.envelope, c.etag, c.receivedAtMs, highestRevision))
        } catch (e: Exception) {
            warnOnce("emergency: could not persist the policy document: $e")
        }
    }

    private fun notifyIfChanged() {
        val c = current
        val state = stateOf(c.document, nowMs())
        val revision = c.document?.revision
        if (state == notifiedState && revision == notifiedRevision) return
        notifiedState = state
        notifiedRevision = revision
        onSnapshot(snapshotOf(c))
    }

    private fun snapshotOf(c: Current): EmergencySnapshot {
        val d = c.document
        return EmergencySnapshot(stateOf(d, nowMs()), d?.revision, d?.issuedAtMs, d?.expiresAtMs, d?.policies ?: emptyList())
    }

    private fun execute(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Throwable) {
                    warnOnce("emergency: internal error ignored: $e")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun warnOnce(message: String) {
        if (warned.add(message)) SdkLog.w(message)
    }

    companion object {
        const val MAX_BACKOFF_MS = 15 * 60_000L

        /** At most one runtime report per this interval (§7.6). */
        const val RUNTIME_INTERVAL_MS = 60_000L

        /** A foreground transition within this long of a successful fetch only re-arms the timer. */
        const val FOREGROUND_REFETCH_AFTER_MS = EmergencyConfig.MIN_REFRESH_INTERVAL_MS

        fun stateOf(doc: PolicyDocument?, now: Long): EmergencyState = when {
            doc == null -> EmergencyState.NONE
            now <= doc.expiresAtMs + EmergencyContract.GRACE_MS -> EmergencyState.USABLE
            else -> EmergencyState.EXPIRED
        }
    }
}
