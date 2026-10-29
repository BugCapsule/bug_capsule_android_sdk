package dev.bugcapsule.android.emergency

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleBackendConfig
import dev.bugcapsule.android.BuildConfig
import dev.bugcapsule.android.ConnectionToken
import dev.bugcapsule.android.ParsedConnectionToken
import dev.bugcapsule.android.internal.Cancellable
import dev.bugcapsule.android.internal.Scheduler
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.replay.BugCapsuleReplay
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Emergency recovery: remote, signed feature guards (`BugCapsule.emergency`).
 *
 * ```kotlin
 * BugCapsule.emergency.initialize(this, EmergencyConfig.builder()
 *     .trustAnchor(BuildConfig.BUGCAPSULE_EMERGENCY_ANCHOR)
 *     .failureMode("checkout.*", EmergencyFailureMode.FAIL_CLOSED)
 *     .build())
 *
 * val decision = BugCapsule.emergency.evaluate("checkout.confirm_order")
 * if (!decision.allowed) showBlocked(decision.publicUserMessage)
 * ```
 *
 * The backend signs a document of policies per environment (ECDSA P-256);
 * the app verifies it against the trust anchor compiled into it, caches it
 * and evaluates it locally. Nothing executable is downloaded. A client guard
 * only improves the user's experience: your server must enforce the same
 * restriction for anything that moves money or data.
 *
 * Evaluation is synchronous, in memory and thread-safe, and never throws.
 * Before [initialize], without a usable document, or with an invalid trust
 * anchor, each key gets its [EmergencyFailureMode] (`FAIL_OPEN` by default).
 * Emergency policies are independent of capture: they work when capture is
 * disabled, and the remote capture kill switch does not turn them off. While
 * BugCapsule replays a capsule nothing is fetched and every key is allowed.
 */
class BugCapsuleEmergency internal constructor() {
    @Volatile
    private var engine: EmergencyEngine? = null

    @Volatile
    private var failureModes: Map<String, EmergencyFailureMode> = emptyMap()

    @Volatile
    private var setupError: String? = null

    private val listeners = CopyOnWriteArrayList<EmergencyListener>()
    private var application: Application? = null
    private var tracker: ForegroundTracker? = null
    private var ownedExecutor: ScheduledThreadPoolExecutor? = null
    private val warnedKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Test hook: storage, transport, clock, threads. */
    @Volatile
    internal var testHooks: EmergencyTestHooks? = null

    /** True after [initialize] succeeded. */
    val isInitialized: Boolean get() = engine != null

    /**
     * Starts emergency policies. Call it once, early (e.g. in
     * `Application.onCreate`, after `BugCapsule.init` when you use it). It
     * reads and re-verifies the cached document on the calling thread (a
     * small file) and fetches in the background; it never waits for the
     * network and never throws. A second call is ignored.
     */
    @Synchronized
    fun initialize(context: Context, config: EmergencyConfig) {
        if (engine != null) {
            SdkLog.w("BugCapsule.emergency.initialize called twice; ignoring the second call")
            return
        }
        failureModes = config.failureModes
        try {
            setup(context, config)
            setupError = null
        } catch (e: Throwable) {
            setupError = "setup failed: $e"
            SdkLog.w("emergency policies setup failed; every feature uses its failure mode: $e")
        }
    }

    private fun setup(context: Context, config: EmergencyConfig) {
        val app = context.applicationContext as? Application ?: context as? Application
            ?: throw IllegalArgumentException("no Application context")
        val hooks = testHooks
        val anchorError: String?
        val anchor = when (val parsed = TrustAnchor.parse(config.trustAnchor)) {
            is TrustAnchor.Parsed.Valid -> {
                anchorError = null
                parsed.anchor
            }
            is TrustAnchor.Parsed.Invalid -> {
                anchorError = "invalid trust anchor: ${parsed.message}"
                SdkLog.w("emergency policies are off (${parsed.message}); every feature uses its failure mode")
                null
            }
        }
        val explicit: BugCapsuleBackendConfig? = config.backend ?: config.token?.let { token ->
            when (val parsed = ConnectionToken.parse(token)) {
                is ParsedConnectionToken.Valid -> parsed.backend
                is ParsedConnectionToken.Invalid -> {
                    SdkLog.e("the emergency SDK token can't be used (${parsed.error.code}): ${parsed.message}")
                    null
                }
            }
        }
        val usesOwnConnection = config.backend != null || config.token != null
        val transport: () -> EmergencyTransport? = hooks?.transport ?: {
            val backend = if (usesOwnConnection) explicit else BugCapsule.emergencyBackend()
            backend?.let { HttpEmergencyTransport(it, BuildConfig.SDK_VERSION) }
        }
        val packageVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val replaying: () -> Boolean = hooks?.replaying ?: {
            BugCapsule.isReplaying || (debuggable && (BugCapsuleReplay.isArmed() || BugCapsuleReplay.isPostMortem()))
        }
        val executor: ExecutorService = hooks?.executor ?: ScheduledThreadPoolExecutor(1, ThreadFactory { r ->
            Thread(r, "BugCapsule-Emergency").apply { isDaemon = true }
        }).apply { removeOnCancelPolicy = true }.also { ownedExecutor = it }
        val scheduler: Scheduler = hooks?.scheduler ?: run {
            val scheduled = executor as ScheduledThreadPoolExecutor
            Scheduler { delayMs, task ->
                val f = scheduled.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                Cancellable { f.cancel(false) }
            }
        }
        val main = Handler(Looper.getMainLooper())
        val deliver: (Runnable) -> Unit = hooks?.deliver ?: { main.post(it) }
        val root = hooks?.storageDir ?: File(app.noBackupFilesDir, "bugcapsule")
        val foreground = hooks?.foreground ?: isProcessForeground()

        val e = EmergencyEngine(
            environment = config.environment,
            refreshIntervalMs = config.refreshIntervalMs,
            failureModes = config.failureModes,
            anchor = anchor,
            anchorError = anchorError,
            store = EmergencyStore(File(root, "emergency")),
            transport = transport,
            executor = executor,
            scheduler = scheduler,
            nowMs = hooks?.nowMs ?: System::currentTimeMillis,
            random = hooks?.random ?: Random(),
            replaying = replaying,
            appVersion = { config.appVersion ?: BugCapsule.configuredAppVersion() ?: packageVersion },
            sdkVersion = BuildConfig.SDK_VERSION,
            onSnapshot = { snapshot -> notifyListeners(snapshot, deliver) },
            foreground = foreground,
        )
        e.start()
        engine = e
        val t = ForegroundTracker(e, foreground)
        app.registerActivityLifecycleCallbacks(t)
        application = app
        tracker = t
    }

    private fun notifyListeners(snapshot: EmergencySnapshot, deliver: (Runnable) -> Unit) {
        for (l in listeners) {
            deliver(Runnable {
                try {
                    l.onPoliciesChanged(snapshot)
                } catch (e: Throwable) {
                    SdkLog.w("an emergency listener failed: $e")
                }
            })
        }
    }

    // ------------------------------------------------------------ evaluation

    /**
     * Decides whether [featureKey] (e.g. `checkout.confirm_order`) may run.
     * Synchronous and in memory; never throws. [context] carries the rollout
     * subject (default: the installation id).
     */
    @JvmOverloads
    fun evaluate(featureKey: String, context: EmergencyContext? = null): EmergencyDecision {
        try {
            if (!EmergencyContract.isFeatureKey(featureKey) && warnedKeys.add(featureKey)) {
                SdkLog.w("emergency: \"$featureKey\" is not a feature key (lower-case dotted, e.g. checkout.confirm_order)")
            }
            engine?.let { return it.evaluate(featureKey, context) }
            if (BugCapsule.isReplaying) return EmergencyContract.fixed(featureKey, EmergencyAction.ALLOW, null, EmergencyDecisionSource.REPLAY)
        } catch (e: Throwable) {
            SdkLog.w("emergency evaluation failed; using the failure mode: $e")
        }
        return failureModeDecision(featureKey)
    }

    private fun failureModeDecision(featureKey: String): EmergencyDecision {
        val action = if (EmergencyContract.failureModeFor(failureModes, featureKey) == EmergencyFailureMode.FAIL_CLOSED) {
            EmergencyAction.BLOCK
        } else {
            EmergencyAction.ALLOW
        }
        return EmergencyContract.fixed(featureKey, action, EmergencyContract.POLICY_UNAVAILABLE, EmergencyDecisionSource.FAILURE_MODE)
    }

    /** `evaluate(featureKey, context).allowed`. */
    @JvmOverloads
    fun isFeatureAllowed(featureKey: String, context: EmergencyContext? = null): Boolean = evaluate(featureKey, context).allowed

    /**
     * The policy that decides [featureKey] right now (exact or wildcard), from
     * the document [evaluate] uses; null when no policy applies, there is no
     * usable document, or BugCapsule is replaying.
     */
    @JvmOverloads
    fun getPolicy(featureKey: String, context: EmergencyContext? = null): EmergencyPolicy? = try {
        engine?.getPolicy(featureKey, context)
    } catch (e: Throwable) {
        SdkLog.w("emergency getPolicy failed: $e")
        null
    }

    /**
     * Fetches the policy document now, in the background. A call while a
     * fetch is in flight joins it. The future completes with the status after
     * the fetch (immediately before [initialize]).
     */
    fun refreshPolicies(): Future<EmergencyStatus> =
        engine?.refresh() ?: FutureTask { status() }.apply { run() }

    /** Adds a listener, called on the main thread after the verified document or its state changed. */
    fun addListener(listener: EmergencyListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: EmergencyListener) {
        listeners.remove(listener)
    }

    /** Current document, last fetch and errors. Never null. */
    fun status(): EmergencyStatus = engine?.status() ?: EmergencyStatus(
        initialized = false,
        state = EmergencyState.NONE,
        revision = null,
        issuedAtMs = null,
        expiresAtMs = null,
        receivedAtMs = null,
        lastFetchAtMs = null,
        lastError = setupError ?: "BugCapsule.emergency.initialize has not been called",
        credentialRejected = false,
        trustAnchorValid = false,
        environment = null,
        replaying = BugCapsule.isReplaying,
    )

    /** Stops the module and forgets its configuration and listeners (persisted files stay). */
    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        engine?.dispose()
        tracker?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        ownedExecutor?.shutdownNow()
        engine = null
        tracker = null
        application = null
        ownedExecutor = null
        failureModes = emptyMap()
        setupError = null
        listeners.clear()
        warnedKeys.clear()
        testHooks = null
    }

    internal fun engineForTest(): EmergencyEngine? = engine

    /** Process foreground ↔ background from started activities; configuration changes are not transitions. */
    private class ForegroundTracker(private val engine: EmergencyEngine, foreground: Boolean) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        private var background = !foreground

        override fun onActivityStarted(activity: Activity) {
            started++
            if (background) {
                background = false
                engine.onForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            if (started == 0 && !activity.isChangingConfigurations && !background) {
                background = true
                engine.onBackground()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private companion object {
        fun isProcessForeground(): Boolean = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (_: Throwable) {
            true
        }
    }
}

/** Test seams of [BugCapsuleEmergency] (set [BugCapsuleEmergency.testHooks] before `initialize`). */
internal class EmergencyTestHooks(
    val storageDir: File? = null,
    /** Replaces the HTTP client; return null to simulate "no backend connection". */
    val transport: (() -> EmergencyTransport?)? = null,
    /** Runs fetches; with [scheduler] null it must be a [ScheduledThreadPoolExecutor]. */
    val executor: ExecutorService? = null,
    val scheduler: Scheduler? = null,
    val nowMs: (() -> Long)? = null,
    val random: Random? = null,
    val replaying: (() -> Boolean)? = null,
    /** Replaces posting listener calls to the main thread. */
    val deliver: ((Runnable) -> Unit)? = null,
    val foreground: Boolean? = null,
)
