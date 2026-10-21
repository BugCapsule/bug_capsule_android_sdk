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
import dev.bugcapsule.android.internal.Host
import dev.bugcapsule.android.internal.Runtime
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.internal.UploadResult
import java.io.File
import java.util.Random
import java.util.concurrent.CompletableFuture
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
 *         BugCapsule.init(this, BugCapsuleConfig.builder().mode(CaptureMode.QA).build(),
 *             listOf(okHttp, navigation, BugCapsuleSharedPreferences.builder().allowFile("prefs").build()))
 *     }
 * }
 * ```
 *
 * Call [init] first in `Application.onCreate` (not from a ContentProvider or
 * App Startup initializer): replay restores storage right before
 * `Application.onCreate`. BugCapsule never stops the app from starting: if
 * setup fails the app runs without capture.
 */
object BugCapsule {
    @Volatile
    private var runtime: Runtime? = null
    private val actions = ActionRegistry()
    private val fallbackRandom = Random()

    /** Test hook: storage, transport and sampling randomness. */
    @VisibleForTesting
    @JvmStatic
    var testOverrides: BugCapsuleTestOverrides? = null

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun init(application: Application, config: BugCapsuleConfig, integrations: List<BugCapsuleIntegration> = emptyList()) {
        if (runtime != null) {
            SdkLog.w("BugCapsule.init called twice; ignoring the second call")
            return
        }
        try {
            val rt = Runtime(Host(application), config, integrations, testOverrides, actions)
            runtime = rt
            rt.start()
        } catch (e: Throwable) {
            SdkLog.w("BugCapsule setup failed; the app runs without capture: $e")
        }
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
        if (!rt.isRecording) return
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
            if (!rt.isRecording) return
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

    /** Reports a caught exception as a failure (captured like an uncaught one, without the crash). */
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
        val rt = runtime ?: return CompletableFuture.completedFuture(null)
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
        val rt = runtime ?: return CompletableFuture.completedFuture(null)
        return rt.submit { rt.remote?.refresh() }
    }

    /** Uploads queued capsules now, if the remote config allows it. */
    @JvmStatic
    fun flushUploads(): Future<*> {
        val rt = runtime ?: return CompletableFuture.completedFuture(null)
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
        actions.clear()
        dev.bugcapsule.android.replay.BugCapsuleReplay.resetForTest()
    }

    @VisibleForTesting
    internal fun runtimeForTest(): Runtime? = runtime

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)
}
