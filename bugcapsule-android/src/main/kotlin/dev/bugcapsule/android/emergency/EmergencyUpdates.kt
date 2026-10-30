package dev.bugcapsule.android.emergency

import android.app.Activity
import android.os.Handler
import android.os.Looper
import dev.bugcapsule.android.internal.SdkLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Store updates for `REQUIRE_UPDATE` decisions (`BugCapsule.emergency.updates`),
 * through the [EmergencyUpdateProvider] set with
 * [EmergencyConfig.Builder.updateProvider]:
 *
 * ```kotlin
 * val decision = BugCapsule.emergency.evaluate("checkout.confirm_order")
 * if (decision.action == EmergencyAction.REQUIRE_UPDATE) {
 *     BugCapsule.emergency.updates.startUpdate(activity, decision)
 * }
 * ```
 *
 * Callbacks and listeners run on the main thread. Every status change is
 * also sent to the backend as a runtime report (emergency-client-v1 §7.6),
 * except while BugCapsule replays a capsule. Without a provider a check
 * answers "not available" and the other calls do nothing.
 */
class EmergencyUpdates internal constructor() {
    @Volatile
    private var provider: EmergencyUpdateProvider? = null

    @Volatile
    private var status: EmergencyUpdateStatus? = null

    @Volatile
    private var deliver: (Runnable) -> Unit = mainThread

    /** Receives each valid runtime object; set by [connect] once the engine runs. */
    @Volatile
    private var runtimeSink: ((Map<String, Any?>) -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<EmergencyUpdateListener>()
    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    /** True when [EmergencyConfig.Builder.updateProvider] set a provider. */
    val isConfigured: Boolean get() = provider != null

    /** The provider's name (`google_play`), or null without one. */
    val providerName: String? get() = provider?.provider

    /** The last status the provider reported; null before its first report. */
    fun status(): EmergencyUpdateStatus? = status

    /** Checks the store for a version that satisfies [request]; [callback] runs once, on the main thread. */
    fun checkForUpdate(request: EmergencyUpdateRequest, callback: EmergencyUpdateCallback) {
        val main: (EmergencyUpdateAvailability) -> Unit = { a ->
            deliver(
                Runnable {
                    try {
                        callback.onResult(a)
                    } catch (e: Throwable) {
                        SdkLog.w("an emergency update callback failed: $e")
                    }
                },
            )
        }
        val p = provider
        if (p == null) {
            main(EmergencyUpdateAvailability(false, requiredVersion = request.requiredVersion, error = NO_PROVIDER))
            return
        }
        try {
            p.checkAvailability(request, EmergencyUpdateCallback { main(it) })
        } catch (e: Throwable) {
            SdkLog.w("emergency: the update provider's check failed: $e")
            main(EmergencyUpdateAvailability(false, requiredVersion = request.requiredVersion, error = e.toString()))
        }
    }

    /** `checkForUpdate(EmergencyUpdateRequest.from(decision), callback)`. */
    fun checkForUpdate(decision: EmergencyDecision, callback: EmergencyUpdateCallback) =
        checkForUpdate(EmergencyUpdateRequest.from(decision), callback)

    /**
     * Starts the update flow that fits [request] from [activity]. The provider
     * picks the flow (Google Play: flexible, immediate only for urgent
     * decisions when the app opted in); follow it with [addListener].
     */
    fun startUpdate(activity: Activity, request: EmergencyUpdateRequest) {
        val p = provider ?: return warnOnce("emergency: startUpdate without an update provider; set EmergencyConfig.updateProvider(…)")
        try {
            p.startUpdate(activity, request)
        } catch (e: Throwable) {
            SdkLog.w("emergency: the update provider failed to start: $e")
        }
    }

    /** `startUpdate(activity, EmergencyUpdateRequest.from(decision))`. */
    fun startUpdate(activity: Activity, decision: EmergencyDecision) = startUpdate(activity, EmergencyUpdateRequest.from(decision))

    /** Resumes a flow this process started and that was interrupted; call it from `onResume`. */
    fun resumeUpdate(activity: Activity) {
        val p = provider ?: return
        try {
            p.resumeUpdate(activity)
        } catch (e: Throwable) {
            SdkLog.w("emergency: the update provider failed to resume: $e")
        }
    }

    /**
     * Installs a downloaded flexible update. It usually restarts the app, so
     * call it when the user agrees (the SDK never calls it by itself).
     * Returns false when no update is downloaded.
     */
    fun completeUpdate(): Boolean {
        val p = provider ?: return false
        return try {
            p.completeUpdate()
        } catch (e: Throwable) {
            SdkLog.w("emergency: the update provider failed to complete the update: $e")
            false
        }
    }

    /** Adds a listener, called on the main thread after each status change. */
    fun addListener(listener: EmergencyUpdateListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: EmergencyUpdateListener) {
        listeners.remove(listener)
    }

    // ---------------------------------------------------------------- wiring

    internal fun attach(p: EmergencyUpdateProvider?, deliverOn: ((Runnable) -> Unit)?) {
        deliver = deliverOn ?: mainThread
        provider = p
        try {
            p?.setListener(EmergencyUpdateListener { onStatus(it) })
        } catch (e: Throwable) {
            SdkLog.w("emergency: the update provider refused its listener: $e")
        }
    }

    /** Sends runtime objects to [sink] from now on, starting with the current status. */
    internal fun connect(sink: (Map<String, Any?>) -> Unit) {
        runtimeSink = sink
        status?.let { RuntimeReport.of(it) }?.let(sink)
    }

    internal fun detach() {
        try {
            provider?.setListener(null)
        } catch (_: Throwable) {
        }
        provider = null
        status = null
        runtimeSink = null
        deliver = mainThread
        listeners.clear()
        warned.clear()
    }

    private fun onStatus(s: EmergencyUpdateStatus) {
        status = s
        val runtime = RuntimeReport.of(s)
        if (runtime == null) {
            warnOnce("emergency: update status not reported (provider must match ^[a-z][a-z0-9_]{0,31}$, versions at most 64 characters): $s")
        } else {
            runtimeSink?.invoke(runtime)
        }
        for (l in listeners) {
            deliver(
                Runnable {
                    try {
                        l.onUpdateStatusChanged(s)
                    } catch (e: Throwable) {
                        SdkLog.w("an emergency update listener failed: $e")
                    }
                },
            )
        }
    }

    private fun warnOnce(message: String) {
        if (warned.add(message)) SdkLog.w(message)
    }

    internal companion object {
        const val NO_PROVIDER = "no update provider: set EmergencyConfig.updateProvider(…)"

        private val mainThread: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }
    }
}

/** The `runtime` object of runtime reports (emergency-client-v1 §7.6), validated before it is sent. */
internal object RuntimeReport {
    val PROVIDER = Regex("^[a-z][a-z0-9_]{0,31}$")
    const val MAX_STRING = 64

    /** `{"update": {…}}`, or null when [s] breaks a field rule. Android has no hotfix adapter: never `hotfix`. */
    fun of(s: EmergencyUpdateStatus): Map<String, Any?>? {
        if (!PROVIDER.matches(s.provider)) return null
        if ((s.availableVersion?.length ?: 0) > MAX_STRING || (s.requiredVersion?.length ?: 0) > MAX_STRING) return null
        val update = linkedMapOf<String, Any?>(
            "provider" to s.provider,
            "flow" to s.flow.wire,
            "state" to s.state.wire,
            "availableVersion" to s.availableVersion,
            "requiredVersion" to s.requiredVersion,
        )
        return linkedMapOf("update" to update)
    }
}
