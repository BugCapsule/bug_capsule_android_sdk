package dev.bugcapsule.android

import android.app.Application
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Eligibility
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.internal.ActionRegistry
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import dev.bugcapsule.android.internal.ConnectionResolver
import dev.bugcapsule.android.internal.Host
import dev.bugcapsule.android.internal.ResolvedConnection
import dev.bugcapsule.android.internal.Runtime
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.internal.UploadResult
import dev.bugcapsule.android.monitoring.Breadcrumb
import dev.bugcapsule.android.monitoring.CaptureOptions
import dev.bugcapsule.android.monitoring.CurrentSpan
import dev.bugcapsule.android.monitoring.Ids as TraceIds
import dev.bugcapsule.android.monitoring.Level
import dev.bugcapsule.android.monitoring.NoopHub
import dev.bugcapsule.android.monitoring.Scope
import dev.bugcapsule.android.monitoring.ScopeCallback
import dev.bugcapsule.android.monitoring.Span
import dev.bugcapsule.android.monitoring.SpanStatus
import dev.bugcapsule.android.monitoring.TraceContext
import dev.bugcapsule.android.monitoring.Transaction
import dev.bugcapsule.android.monitoring.User
import java.io.File
import java.util.Random
import java.util.concurrent.FutureTask
import java.util.concurrent.Future

/** Result of writing a capsule. [upload] is null when no backend is configured. */
class CaptureResult(
    val capsuleId: String,
    val file: File,
    val eligibility: Eligibility,
    val failureSignature: String?,
    val upload: UploadResult?,
)

/**
 * Entry point of the BugCapsule SDK for Android.
 *
 * ```kotlin
 * class ShopApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         BugCapsule.init(this, BuildConfig.BUGCAPSULE_TOKEN)   // bcsdk.v1.… from the dashboard
 *     }
 * }
 * ```
 *
 * Progressive setup adds integrations and options:
 * `BugCapsule.init(this, BugCapsuleConfig.builder().token(…).mode(CaptureMode.QA).build(), listOf(okHttp, navigation))`.
 * [status] reports the connection and which adapters are still missing.
 *
 * Call [init] first in `Application.onCreate` (not from a ContentProvider or
 * App Startup initializer): replay restores storage right before
 * `Application.onCreate`. BugCapsule never stops the app from starting: if
 * setup fails, or the token can't be used, the app runs without capture /
 * without the backend, and [status] says why. Network work never runs on
 * the calling thread.
 */
object BugCapsule {
    @Volatile
    private var runtime: Runtime? = null

    /** Set by [init] even when setup fails, so [status] can say why. */
    @Volatile
    private var connection: ResolvedConnection? = null
    private val actions = ActionRegistry()
    private val fallbackRandom = Random()

    /** Test hook: storage, transport and sampling randomness. */
    @VisibleForTesting
    @JvmStatic
    var testOverrides: BugCapsuleTestOverrides? = null

    /**
     * Starts BugCapsule. With [BugCapsuleConfig.token] (or an explicit
     * [BugCapsuleConfig.backend], which wins) capsules and monitoring are sent
     * to the backend; the configuration is fetched in the background.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun init(application: Application, config: BugCapsuleConfig, integrations: List<BugCapsuleIntegration> = emptyList()) {
        if (runtime != null) {
            SdkLog.w("BugCapsule.init called twice; ignoring the second call")
            return
        }
        try {
            val (resolved, conn) = ConnectionResolver.resolve(config)
            connection = conn
            val rt = Runtime(Host(application), resolved, integrations, testOverrides, actions, conn)
            runtime = rt
            rt.start()
        } catch (e: Throwable) {
            SdkLog.w("BugCapsule setup failed; the app runs without capture: $e")
        }
    }

    /**
     * Token-only setup: `BugCapsule.init(this, "bcsdk.v1.…")` with the SDK
     * connection token from the dashboard. Everything else uses defaults (app
     * version and build come from the package info); pass [integrations] for
     * the progressive adapters (OkHttp, Navigation, storage).
     */
    @JvmStatic
    @JvmOverloads
    fun init(application: Application, token: String, integrations: List<BugCapsuleIntegration> = emptyList()) =
        init(application, BugCapsuleConfig.builder().token(token).build(), integrations)

    /**
     * Connection (token or backend, state, the backend's refusal reason, the
     * two connection confirmations) and instrumentation (which progressive
     * adapters are installed or missing), for setup checks and support.
     * Never null: before [init] the state is [ConnectionState.NOT_CONFIGURED].
     */
    @JvmStatic
    fun status(): BugCapsuleStatus {
        runtime?.let { rt ->
            try {
                return rt.status()
            } catch (_: Throwable) {
            }
        }
        val conn = connection
        val tokenError = conn?.tokenError
        val state = when {
            tokenError != null -> ConnectionState.valueOf(tokenError.name)
            conn != null -> ConnectionState.DISABLED
            else -> ConnectionState.NOT_CONFIGURED
        }
        val message = when {
            tokenError != null -> conn.tokenMessage
            conn != null -> "setup failed; the app runs without BugCapsule"
            else -> "BugCapsule.init has not been called"
        }
        return BugCapsuleStatus(
            BugCapsuleStatus.Connection(state, conn?.source, null, tokenError?.code, message, null, null, null),
            BugCapsuleStatus.Instrumentation(
                recording = false, network = false, navigation = false, appState = false, nativeCrashes = false,
                frameworks = emptyList(), integrations = emptyList(), missing = listOf("network", "navigation", "app_state"),
            ),
        )
    }

    @JvmStatic
    val isInitialized: Boolean get() = runtime != null

    @JvmStatic
    val isReplaying: Boolean get() = runtime?.isReplaying ?: false

    @JvmStatic
    val isRecording: Boolean get() = runtime?.isRecording ?: false

    @JvmStatic
    val mode: CaptureMode? get() = runtime?.mode

    /** The running session (for integrations); null before [init]. */
    @JvmStatic
    val session: BugCapsuleSession? get() = runtime

    // ---------------------------------------------------------------- hooks

    /**
     * Seeded per session and restored in replay: use it for randomness that
     * affects behaviour you want reproducible.
     */
    @JvmStatic
    fun random(): Random = runtime?.random ?: fallbackRandom

    /** Wall clock that replay controls (the recorded dates come back). Use it instead of `System.currentTimeMillis()`. */
    @JvmStatic
    fun currentTimeMillis(): Long = runtime?.currentTimeMillis() ?: System.currentTimeMillis()

    // -------------------------------------------------------------- actions

    /**
     * Wraps a UI callback as a named semantic action: records it, and lets
     * semantic replay invoke it by [id]. `button.setOnClickListener { BugCapsule.action("cart.checkout") { ... }.run() }`
     * or keep the returned [Runnable].
     */
    @JvmStatic
    fun action(id: String, callback: Runnable): Runnable {
        actions.register(id) { callback.run() }
        return Runnable {
            recordAction(id, null)
            callback.run()
        }
    }

    /** Registers the handler semantic replay invokes for [id] (latest registration wins). */
    @JvmStatic
    fun registerAction(id: String, handler: ActionHandler) = actions.register(id, handler)

    @JvmStatic
    fun unregisterAction(id: String, handler: ActionHandler) = actions.unregister(id, handler)

    /** Records that the action [id] happened (call it from the real UI callback). */
    @JvmStatic
    @JvmOverloads
    fun recordAction(id: String, payload: Map<String, Any?>? = null) {
        val rt = runtime ?: return
        if (!rt.isObserving) return
        val data = linkedMapOf<String, Any?>("id" to cut(id, 200))
        if (payload != null) data["payload"] = payload
        rt.record(EventTypes.ACTION, data)
    }

    // ----------------------------------------------------------------- text

    /**
     * Tracks a text field under [id]: call [TextTracker.onChanged] on every
     * edit; replay calls [setter] on the main thread. Values are redacted when
     * [sensitive] (required for password fields) or the id looks sensitive.
     * Views and Compose helpers build on this ([dev.bugcapsule.android.views.BugCapsuleViews]).
     */
    @JvmStatic
    @JvmOverloads
    fun trackText(id: String, setter: TextSetter, sensitive: Boolean = false): TextTracker {
        actions.trackText(id, setter)
        return TextTracker(id, setter, sensitive)
    }

    class TextTracker internal constructor(val id: String, private val setter: TextSetter, val sensitive: Boolean) {
        @Volatile
        private var last: String? = null

        /** Records the new value (deduplicated); a no-op while replaying. */
        fun onChanged(value: String) {
            if (value == last) return
            last = value
            val rt = runtime ?: return
            if (!rt.isObserving) return
            val clean = rt.redactor.redactText(id, value, sensitive)
            rt.record(EventTypes.TEXT, linkedMapOf("id" to cut(id, 200), "value" to cut(clean, 100_000)))
        }

        fun untrack() = actions.untrackText(id, setter)
    }

    // ------------------------------------------------------------- failures

    /** Raises a named failure assertion; replay is judged against [assertionId]. */
    @JvmStatic
    @JvmOverloads
    fun fail(assertionId: String, message: String = "") {
        val rt = runtime ?: return
        rt.handleFailure(FailureRecord.assertion(assertionId, message, rt.now(), rt.redactor::scrubText))
    }

    /** Raises [assertionId] when [condition] is false. */
    @JvmStatic
    @JvmOverloads
    fun check(assertionId: String, condition: Boolean, message: String = "") {
        if (!condition) fail(assertionId, message)
    }

    /**
     * Reports a caught exception as a failure: recorded, captured as a
     * capsule like an uncaught one (without the crash) and sent as an error
     * event linked to it. Use [captureException] for errors that only need
     * an event.
     */
    @JvmStatic
    fun reportError(error: Throwable) {
        runtime?.reportError(error)
    }

    @JvmStatic
    @JvmOverloads
    fun log(message: String, level: String = "info") {
        runtime?.record(EventTypes.LOG, linkedMapOf("level" to level, "message" to message))
    }

    // -------------------------------------------------------------- capture

    /** Writes a capsule now (QA "capture" button); uses the latest failure as the assertion when there is one. */
    @JvmStatic
    @JvmOverloads
    fun capture(note: String? = null): Future<CaptureResult?> {
        val rt = runtime ?: return FutureTask<CaptureResult?> { null }.apply { run() }
        return rt.submit {
            rt.writeCapsule(rt.lastFailure, if (note != null) mapOf("note" to note) else emptyMap())
        }
    }

    /** Saved capsule files, newest first (at most `maxSavedCaptures`). */
    @JvmStatic
    fun savedCaptures(): List<File> = runtime?.savedCaptures() ?: emptyList()

    /** Fetches the remote capture config now (it is otherwise refreshed after its TTL and on foreground). */
    @JvmStatic
    fun refreshRemoteConfig(): Future<*> {
        val rt = runtime ?: return FutureTask<CaptureResult?> { null }.apply { run() }
        return rt.submit { rt.remote?.refresh() }
    }

    /** Uploads queued capsules now, if the remote config allows it. */
    @JvmStatic
    fun flushUploads(): Future<*> {
        val rt = runtime ?: return FutureTask<CaptureResult?> { null }.apply { run() }
        return rt.submit { rt.flushOutbox(null) }
    }

    // ---------------------------------------------------------------- state

    /**
     * Registers app state for periodic checkpoints. In replay from a periodic
     * checkpoint, [restorer] runs right here with the captured value (register
     * before the first input, e.g. in `onCreate`).
     */
    @JvmStatic
    @JvmOverloads
    fun registerState(key: String, serializer: StateSerializer, restorer: StateRestorer? = null) {
        runtime?.registerState(key, serializer, restorer)
    }

    @JvmStatic
    fun unregisterState(key: String) {
        runtime?.unregisterState(key)
    }

    /** Records a diagnostic `state` event with the current serialized value of [key]. */
    @JvmStatic
    fun reportState(key: String) {
        runtime?.reportState(key)
    }

    /** Registers a [ViewModel]'s state; unregistered automatically when the ViewModel is cleared. */
    @JvmStatic
    @JvmOverloads
    fun registerViewModel(viewModel: ViewModel, key: String, serializer: StateSerializer, restorer: StateRestorer? = null) {
        val rt = runtime ?: return
        rt.registerState(key, serializer, restorer)
        viewModel.addCloseable(AutoCloseable { rt.unregisterState(key, serializer) })
    }

    // ----------------------------------------------------------- monitoring

    /**
     * Sends [error] as an error event (mechanism `manual`, handled) without
     * recording a failure or writing a capsule. Returns the event id, or
     * null when monitoring is off, the event was sampled out or dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun captureException(error: Throwable, options: CaptureOptions? = null): String? =
        runtime?.monitoring?.captureException(
            error,
            dev.bugcapsule.android.monitoring.ExceptionMapper.Mechanism("manual", true),
            options,
        )?.eventId

    /** Sends a message event (the calling thread's stack is attached unless `attachStacktrace` is off). */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, level: Level = Level.INFO, options: CaptureOptions? = null): String? =
        runtime?.monitoring?.captureMessage(message, level, options)?.eventId

    /**
     * Sends user feedback (monitoring-v1 §9). [eventId] links it to an
     * event's issue; [captureCapsule] (default `captureCapsuleWithFeedback`,
     * true in QA mode) also writes a capsule of the session, linked by
     * `capsuleId`. Returns the feedback id.
     */
    @JvmStatic
    @JvmOverloads
    fun captureFeedback(
        message: String,
        name: String? = null,
        email: String? = null,
        eventId: String? = null,
        captureCapsule: Boolean? = null,
    ): String? = runtime?.captureFeedback(message, name, email, eventId, captureCapsule)

    /** Id of the last event sent (or queued) in this process. */
    @JvmStatic
    fun lastEventId(): String? = runtime?.monitoring?.lastEventId

    /** The global scope (null before [init] or with monitoring off). */
    @JvmStatic
    fun scope(): Scope? = runtime?.monitoring?.scope

    /** Changes the global scope. */
    @JvmStatic
    fun configureScope(callback: ScopeCallback) {
        runtime?.monitoring?.scope?.let { callback.configure(it) }
    }

    /** Runs [callback] with a temporary copy of the scope: events captured inside on this thread use it. */
    @JvmStatic
    fun withScope(callback: ScopeCallback) {
        val mon = runtime?.monitoring ?: return
        mon.withScope { callback.configure(it) }
    }

    @JvmStatic
    fun setUser(user: User?) = configureScope { it.setUser(user) }

    @JvmStatic
    fun setTag(key: String, value: String) = configureScope { it.setTag(key, value) }

    @JvmStatic
    fun setTags(tags: Map<String, String>) = configureScope { it.setTags(tags) }

    @JvmStatic
    fun setExtra(key: String, value: Any?) = configureScope { it.setExtra(key, value) }

    @JvmStatic
    fun setExtras(extras: Map<String, Any?>) = configureScope { it.setExtras(extras) }

    @JvmStatic
    fun setContext(name: String, value: Map<String, Any?>?) = configureScope { it.setContext(name, value) }

    @JvmStatic
    fun setLevel(level: Level?) = configureScope { it.setLevel(level) }

    @JvmStatic
    fun setFingerprint(fingerprint: List<String>?) = configureScope { it.setFingerprint(fingerprint) }

    /** Adds a breadcrumb (after `beforeBreadcrumb`). Recorded events become breadcrumbs automatically. */
    @JvmStatic
    fun addBreadcrumb(breadcrumb: Breadcrumb) {
        runtime?.monitoring?.addBreadcrumb(breadcrumb)
    }

    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String? = null) = addBreadcrumb(Breadcrumb(message, category = category))

    @JvmStatic
    fun clearBreadcrumbs() = configureScope { it.clearBreadcrumbs() }

    /**
     * Starts a transaction (sampled at this root with `tracesSampler` /
     * `tracesSampleRate`, or continuing [context]). [bindToScope] makes it
     * the scope's active span, the parent of HTTP and database spans. Always
     * [Transaction.finish] it.
     */
    @JvmStatic
    @JvmOverloads
    fun startTransaction(name: String, op: String, bindToScope: Boolean = false, context: TraceContext? = null): Transaction =
        runtime?.monitoring?.startTransaction(name, op, context, bindToScope)
            ?: Transaction(NoopHub, name, op, TraceIds.traceId(), TraceIds.spanId(), null, false, null, null)

    /**
     * Runs [block] in a span named [name]: a child of the active span, or a
     * new transaction. The span is current on this thread while [block]
     * runs and finishes after it (`internal_error` when it throws).
     */
    @JvmStatic
    fun <T> startSpan(name: String, op: String, block: SpanBlock<T>): T {
        val parent = activeSpan()
        val span = parent?.startChild(op, name) ?: startTransaction(name, op)
        val previous = CurrentSpan.get()
        CurrentSpan.set(span)
        try {
            val result = block.run(span)
            if (!span.isFinished) span.finish()
            return result
        } catch (e: Throwable) {
            span.finish(SpanStatus.INTERNAL_ERROR)
            throw e
        } finally {
            CurrentSpan.set(previous)
        }
    }

    /** The span new work attaches to: the current span of this thread, else the scope's. */
    @JvmStatic
    fun activeSpan(): Span? = runtime?.monitoring?.activeSpan()

    /** `traceparent` for a custom HTTP client (null when monitoring is off or replaying). */
    @JvmStatic
    fun getTraceparent(): String? = runtime?.takeIf { !it.isReplaying }?.monitoring?.traceparent()

    /** `baggage` for a custom HTTP client. */
    @JvmStatic
    fun getBaggage(): String? = runtime?.takeIf { !it.isReplaying }?.monitoring?.baggageHeader()

    /** Continues an incoming trace: pass the result to [startTransaction]. Null when the header is invalid. */
    @JvmStatic
    @JvmOverloads
    fun continueTrace(traceparent: String?, baggage: String? = null): TraceContext? =
        runtime?.monitoring?.continueTrace(traceparent, baggage)

    /** Starts a release-health session now (sessions are otherwise automatic). */
    @JvmStatic
    fun startSession() {
        runtime?.monitoring?.sessions?.start()
    }

    @JvmStatic
    fun endSession() {
        runtime?.monitoring?.sessions?.end()
    }

    /**
     * Marks the current screen fully drawn: ends `ui.load.full_display`
     * (TTFD) when `enableTimeToFullDisplayTracing` is on. Main thread.
     */
    @JvmStatic
    fun reportFullyDrawn() {
        runtime?.reportFullyDrawn()
    }

    /**
     * Starts a screen transaction for a single-activity app ([op]
     * `navigation` for Navigation destinations, `ui.load` for Compose
     * screens). [name] must not contain ids (`/product/{id}`, not `/product/42`).
     */
    @JvmStatic
    @JvmOverloads
    fun startScreen(name: String, op: String = "ui.load", view: android.view.View? = null): Transaction? =
        runtime?.startScreen(name, op, view)

    /** Sends queued envelopes now, waiting up to [timeoutMs]; true when nothing is left queued. */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 5_000): Boolean = runtime?.monitoring?.flush(timeoutMs) ?: true

    /** Ends the session, flushes and stops monitoring for this process (capsules are unaffected). */
    @JvmStatic
    @JvmOverloads
    fun close(timeoutMs: Long = 5_000) {
        runtime?.monitoring?.close(timeoutMs)
    }

    // -------------------------------------------------------------- support

    /** Marks a UI framework as used (`compose`); called by the helper modules. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @JvmStatic
    fun markFramework(name: String) {
        runtime?.markFramework(name)
    }

    /** Invokes a registered action as semantic replay would (tests). */
    @VisibleForTesting
    @JvmStatic
    fun invokeAction(id: String, payload: Map<String, Any?>? = null): Boolean = actions.invoke(id, payload)

    @VisibleForTesting
    @JvmStatic
    fun setTrackedText(id: String, value: String): Boolean = actions.setText(id, value)

    @VisibleForTesting
    @JvmStatic
    @Synchronized
    fun resetForTest() {
        runtime?.dispose()
        runtime = null
        connection = null
        actions.clear()
        dev.bugcapsule.android.replay.BugCapsuleReplay.resetForTest()
    }

    @VisibleForTesting
    internal fun runtimeForTest(): Runtime? = runtime

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)
}
