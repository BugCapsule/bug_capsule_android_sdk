package dev.bugcapsule.android.play

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dev.bugcapsule.android.emergency.EmergencyUpdateAvailability
import dev.bugcapsule.android.emergency.EmergencyUpdateCallback
import dev.bugcapsule.android.emergency.EmergencyUpdateFlow
import dev.bugcapsule.android.emergency.EmergencyUpdateListener
import dev.bugcapsule.android.emergency.EmergencyUpdateProvider
import dev.bugcapsule.android.emergency.EmergencyUpdateRequest
import dev.bugcapsule.android.emergency.EmergencyUpdateState
import dev.bugcapsule.android.emergency.EmergencyUpdateStatus
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * The emergency update provider for apps distributed through Google Play,
 * on the Play In-App Updates API (emergency-client-v1 §7.7):
 *
 * ```kotlin
 * BugCapsule.emergency.initialize(this, EmergencyConfig.builder()
 *     .trustAnchor(BuildConfig.BUGCAPSULE_EMERGENCY_ANCHOR)
 *     .updateProvider(PlayEmergencyUpdateProvider.builder(this)
 *         .allowImmediateUpdates(true)        // optional; flexible otherwise
 *         .build())
 *     .build())
 * ```
 *
 * - The flexible flow is the default. The immediate flow is used only when
 *   the app opted in ([Builder.allowImmediateUpdates]), the decision is
 *   `REQUIRE_UPDATE` with severity `P0` or `P1`, and Play allows an
 *   immediate update for that update.
 * - Play reports version codes. `required_version` becomes a version code
 *   through [Builder.versionCode], else its build number (`2.4.2+57` → 57).
 *   Without either, any newer Play update counts as available.
 * - A downloaded flexible update is installed only when the app calls
 *   `completeUpdate()`, which restarts the app.
 *
 * Flows start from a `ComponentActivity` (its `ActivityResultRegistry`), or
 * from your own `ActivityResultLauncher` with [startUpdate] and
 * [onUpdateFlowResult]. Play listeners run on the main thread. The provider
 * never installs a package itself; apps distributed outside Google Play
 * implement [EmergencyUpdateProvider] for their channel.
 */
class PlayEmergencyUpdateProvider private constructor(b: Builder) : EmergencyUpdateProvider {
    private val manager: AppUpdateManager = b.manager ?: AppUpdateManagerFactory.create(b.context)

    /** The app opted in to immediate updates for urgent decisions. */
    val immediateUpdatesAllowed: Boolean = b.allowImmediate

    private val versionCodes: Map<String, Int> = LinkedHashMap(b.versionCodes)

    override val provider: String get() = PROVIDER

    @Volatile
    private var listener: EmergencyUpdateListener? = null

    private val lock = Any()

    // Guarded by lock.
    private var current: EmergencyUpdateStatus? = null
    private var request: EmergencyUpdateRequest? = null
    private var flow = EmergencyUpdateFlow.FLEXIBLE
    private var availableVersion: String? = null

    /** The flow this process started last; [resumeUpdate] restarts an interrupted immediate one. */
    private var started: EmergencyUpdateFlow? = null

    /** The registry launcher of the last flow started from an activity; weak, so it never keeps the activity. */
    private var owned: WeakReference<ActivityResultLauncher<IntentSenderRequest>>? = null
    private var listening = false

    private val installListener = InstallStateUpdatedListener { onInstallState(it) }

    /** The last status this provider reported; null before the first one. */
    fun status(): EmergencyUpdateStatus? = synchronized(lock) { current }

    override fun setListener(listener: EmergencyUpdateListener?) {
        this.listener = listener
    }

    // ----------------------------------------------------------------- check

    override fun checkAvailability(request: EmergencyUpdateRequest, callback: EmergencyUpdateCallback) {
        withInfo(
            onError = { error ->
                remember(request, null)
                publish(EmergencyUpdateState.FAILED)
                callback.onResult(EmergencyUpdateAvailability(false, requiredVersion = request.requiredVersion, error = error))
            },
        ) { info ->
            val a = availability(info, request)
            remember(request, a)
            val state = stateOf(info, a)
            if (state == EmergencyUpdateState.IN_PROGRESS || state == EmergencyUpdateState.DOWNLOADED) listen()
            publish(state)
            callback.onResult(a)
        }
    }

    // ----------------------------------------------------------------- start

    /**
     * Starts the flow that fits [request] through [activity]'s
     * `ActivityResultRegistry`. [activity] must be a `ComponentActivity`
     * (`AppCompatActivity` and `FragmentActivity` are); with any other
     * activity use [startUpdate] with your own launcher.
     */
    override fun startUpdate(activity: Activity, request: EmergencyUpdateRequest) {
        val owner = activity as? ComponentActivity
        if (owner == null) {
            Log.w(TAG, "PlayEmergencyUpdateProvider needs a ComponentActivity, or startUpdate(launcher, request)")
            remember(request, null)
            publish(EmergencyUpdateState.FAILED)
            return
        }
        start(request) { registryLauncher(owner) }
    }

    /**
     * Starts the flow that fits [request] through [launcher], registered by
     * the app with `registerForActivityResult(StartIntentSenderForResult())`.
     * Its callback must pass the result code to [onUpdateFlowResult]. Unlike
     * [startUpdate] with an activity, this survives activity recreation.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>, request: EmergencyUpdateRequest) =
        start(request) { launcher }

    private fun start(request: EmergencyUpdateRequest, launcherOf: () -> ActivityResultLauncher<IntentSenderRequest>) {
        withInfo(
            onError = {
                remember(request, null)
                publish(EmergencyUpdateState.FAILED)
            },
        ) { info ->
            val a = availability(info, request)
            remember(request, a)
            val flowToUse = a.flow
            when {
                !a.available || flowToUse == null -> publish(EmergencyUpdateState.NOT_AVAILABLE)
                // Installing is the app's call (completeUpdate).
                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    listen()
                    publish(EmergencyUpdateState.DOWNLOADED)
                }
                // A flexible download already runs; an urgent decision may still switch it to immediate.
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                    flowToUse == EmergencyUpdateFlow.FLEXIBLE -> {
                    listen()
                    publish(EmergencyUpdateState.IN_PROGRESS)
                }
                else -> launch(info, flowToUse, stateOf(info, a), launcherOf())
            }
        }
    }

    private fun launch(
        info: AppUpdateInfo,
        flowToUse: EmergencyUpdateFlow,
        state: EmergencyUpdateState,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        val type = if (flowToUse == EmergencyUpdateFlow.IMMEDIATE) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (flowToUse == EmergencyUpdateFlow.FLEXIBLE) listen()
        val ok = try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        } catch (e: Exception) {
            Log.w(TAG, "Play could not start the ${flowToUse.wire} update flow: $e")
            false
        }
        synchronized(lock) {
            flow = flowToUse
            if (ok) started = flowToUse
        }
        if (ok) {
            publish(state)
        } else {
            Log.w(TAG, "Play did not start the ${flowToUse.wire} update flow (the update type is not allowed for this update)")
            releaseOwnedLauncher()
            publish(EmergencyUpdateState.FAILED)
        }
    }

    /**
     * The result code of the update flow, for flows started with your own
     * launcher: `RESULT_OK` → in progress, `RESULT_CANCELED` → declined,
     * `ActivityResult.RESULT_IN_APP_UPDATE_FAILED` (or anything else) → failed.
     */
    fun onUpdateFlowResult(resultCode: Int) {
        val state = when (resultCode) {
            Activity.RESULT_OK -> EmergencyUpdateState.IN_PROGRESS
            Activity.RESULT_CANCELED -> EmergencyUpdateState.DECLINED
            else -> EmergencyUpdateState.FAILED
        }
        // A late RESULT_OK never hides a finished download.
        if (state == EmergencyUpdateState.IN_PROGRESS && status()?.state == EmergencyUpdateState.DOWNLOADED) return
        publish(state)
    }

    // ---------------------------------------------------------------- resume

    /**
     * Picks up where Play stands (call it from `onResume`): restarts an
     * immediate update this process started and that was interrupted
     * (`DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`), and reports a running or
     * finished flexible download. It never installs a downloaded update.
     */
    override fun resumeUpdate(activity: Activity) {
        val owner = activity as? ComponentActivity
        resume(owner?.let { { registryLauncher(it) } })
    }

    /** [resumeUpdate] with your own launcher (see [startUpdate]). */
    fun resumeUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) = resume { launcher }

    private fun resume(launcherOf: (() -> ActivityResultLauncher<IntentSenderRequest>)?) {
        withInfo(onError = { Log.w(TAG, "could not resume the Play update: $it") }) { info ->
            val (req, wasStarted) = synchronized(lock) { (request ?: EmergencyUpdateRequest()) to started }
            val a = availability(info, req)
            remember(req, a)
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    listen()
                    publish(stateOf(info, a))
                }
                info.updateAvailability() != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> Unit
                wasStarted == EmergencyUpdateFlow.IMMEDIATE ->
                    if (launcherOf != null) {
                        launch(info, EmergencyUpdateFlow.IMMEDIATE, EmergencyUpdateState.IN_PROGRESS, launcherOf())
                    } else {
                        Log.w(TAG, "resuming an immediate update needs a ComponentActivity, or resumeUpdate(launcher)")
                    }
                else -> {
                    listen()
                    publish(EmergencyUpdateState.IN_PROGRESS)
                }
            }
        }
    }

    // -------------------------------------------------------------- complete

    /**
     * Installs a downloaded flexible update; Play restarts the app. Returns
     * false (and does nothing) unless the last status is
     * [EmergencyUpdateState.DOWNLOADED]. Never called by the SDK itself.
     */
    override fun completeUpdate(): Boolean {
        if (status()?.state != EmergencyUpdateState.DOWNLOADED) return false
        try {
            manager.completeUpdate().addOnCompleteListener { t ->
                if (!t.isSuccessful) {
                    Log.w(TAG, "Play could not install the downloaded update: ${describe(t.exception)}")
                    publish(EmergencyUpdateState.FAILED)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Play could not install the downloaded update: $e")
            publish(EmergencyUpdateState.FAILED)
        }
        return true
    }

    // ------------------------------------------------------------- internals

    private fun onInstallState(s: InstallState) {
        val state = when (s.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING, InstallStatus.REQUIRES_UI_INTENT ->
                EmergencyUpdateState.IN_PROGRESS
            InstallStatus.DOWNLOADED -> EmergencyUpdateState.DOWNLOADED
            InstallStatus.FAILED -> EmergencyUpdateState.FAILED
            InstallStatus.CANCELED -> EmergencyUpdateState.DECLINED
            // INSTALLED is never observed: the process that started the update is gone by then (§7.6).
            else -> return
        }
        publish(state, s.bytesDownloaded(), s.totalBytesToDownload())
        if (state == EmergencyUpdateState.FAILED || state == EmergencyUpdateState.DECLINED) unlisten()
    }

    /** §7.7: available only when Play's version code is at least the required one. */
    internal fun availability(info: AppUpdateInfo, request: EmergencyUpdateRequest): EmergencyUpdateAvailability {
        val ua = info.updateAvailability()
        val newer = ua == UpdateAvailability.UPDATE_AVAILABLE || ua == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        val required = requiredVersionCode(request.requiredVersion)
        val code = info.availableVersionCode()
        val available = newer && (required == null || code >= required)
        val flows = LinkedHashSet<EmergencyUpdateFlow>()
        if (available) {
            if (info.isUpdateTypeAllowed(options(AppUpdateType.FLEXIBLE))) flows += EmergencyUpdateFlow.FLEXIBLE
            if (info.isUpdateTypeAllowed(options(AppUpdateType.IMMEDIATE))) flows += EmergencyUpdateFlow.IMMEDIATE
        }
        return EmergencyUpdateAvailability(
            available = available,
            allowedFlows = flows,
            flow = if (available) flowFor(request, flows) else null,
            availableVersion = if (newer) versionNameFor(code, request.requiredVersion) else null,
            requiredVersion = request.requiredVersion,
            stalenessDays = info.clientVersionStalenessDays(),
            priority = info.updatePriority(),
        )
    }

    /** Immediate only when the app opted in, the decision is urgent and Play allows it; flexible otherwise. */
    private fun flowFor(request: EmergencyUpdateRequest, allowed: Set<EmergencyUpdateFlow>): EmergencyUpdateFlow =
        if (immediateUpdatesAllowed && request.isUrgent && EmergencyUpdateFlow.IMMEDIATE in allowed) {
            EmergencyUpdateFlow.IMMEDIATE
        } else {
            EmergencyUpdateFlow.FLEXIBLE
        }

    /** The app's mapping (exact, then without `+build`), else the build number; null: any newer update counts. */
    internal fun requiredVersionCode(requiredVersion: String?): Int? {
        val v = requiredVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        versionCodes[v]?.let { return it }
        versionCodes[v.substringBefore('+')]?.let { return it }
        return BUILD_NUMBER.find(v)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** The version name of a Play version code, when the mapping or the required version names it. */
    private fun versionNameFor(code: Int, requiredVersion: String?): String? =
        (versionCodes.entries.firstOrNull { it.value == code }?.key ?: requiredVersion?.takeIf { requiredVersionCode(it) == code })
            ?.takeIf { it.length <= MAX_VERSION_LENGTH }

    private fun stateOf(info: AppUpdateInfo, a: EmergencyUpdateAvailability): EmergencyUpdateState = when {
        !a.available -> EmergencyUpdateState.NOT_AVAILABLE
        info.installStatus() == InstallStatus.DOWNLOADED -> EmergencyUpdateState.DOWNLOADED
        info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> EmergencyUpdateState.IN_PROGRESS
        else -> EmergencyUpdateState.AVAILABLE
    }

    private fun remember(request: EmergencyUpdateRequest, a: EmergencyUpdateAvailability?) = synchronized(lock) {
        this.request = request
        flow = a?.flow ?: EmergencyUpdateFlow.FLEXIBLE
        availableVersion = a?.availableVersion
    }

    private fun publish(state: EmergencyUpdateState, bytesDownloaded: Long = 0, totalBytes: Long = 0) {
        val s = synchronized(lock) {
            EmergencyUpdateStatus(PROVIDER, flow, state, availableVersion, request?.requiredVersion, bytesDownloaded, totalBytes)
                .also { current = it }
        }
        try {
            listener?.onUpdateStatusChanged(s)
        } catch (e: Throwable) {
            Log.w(TAG, "an emergency update listener failed: $e")
        }
    }

    /** Reads [AppUpdateInfo] (Play answers on the main thread); [onError] gets a description. */
    private fun withInfo(onError: (String) -> Unit, onInfo: (AppUpdateInfo) -> Unit) {
        val task = try {
            manager.appUpdateInfo
        } catch (e: Exception) {
            onError(e.toString())
            return
        }
        task.addOnCompleteListener { t ->
            val info = if (t.isSuccessful) t.result else null
            try {
                if (info != null) onInfo(info) else onError(describe(t.exception))
            } catch (e: Throwable) {
                Log.w(TAG, "handling the Play update info failed: $e")
            }
        }
    }

    /** A launcher on [owner]'s registry for one flow; it unregisters itself on the result. */
    private fun registryLauncher(owner: ComponentActivity): ActivityResultLauncher<IntentSenderRequest> {
        releaseOwnedLauncher()
        lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
        launcher = owner.activityResultRegistry.register(
            KEY_PREFIX + keys.incrementAndGet(),
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            launcher.unregister()
            synchronized(lock) { if (owned?.get() === launcher) owned = null }
            onUpdateFlowResult(result.resultCode)
        }
        synchronized(lock) { owned = WeakReference(launcher) }
        return launcher
    }

    private fun releaseOwnedLauncher() {
        val l = synchronized(lock) { owned?.get().also { owned = null } }
        l?.unregister()
    }

    private fun listen() {
        synchronized(lock) {
            if (listening) return
            listening = true
        }
        manager.registerListener(installListener)
    }

    private fun unlisten() {
        synchronized(lock) {
            if (!listening) return
            listening = false
        }
        manager.unregisterListener(installListener)
    }

    /**
     * Builds a [PlayEmergencyUpdateProvider]. Only [build] talks to Play; the
     * other settings are plain values.
     */
    class Builder internal constructor(context: Context) {
        internal val context: Context = context.applicationContext ?: context
        internal var allowImmediate = false
        internal val versionCodes = LinkedHashMap<String, Int>()
        internal var manager: AppUpdateManager? = null

        /**
         * Lets urgent decisions (`REQUIRE_UPDATE` with severity `P0` or `P1`)
         * use Play's immediate, full-screen flow when Play allows it for the
         * update. Off by default: every update uses the flexible flow.
         */
        fun allowImmediateUpdates(v: Boolean) = apply { allowImmediate = v }

        /**
         * Maps a `required_version` (e.g. `2.4.2`) to the Play version code of
         * that release. A mapping wins over the build number of the required
         * version (`2.4.2+57` → 57).
         * @throws IllegalArgumentException when [versionCode] is not positive.
         */
        fun versionCode(version: String, versionCode: Int) = apply {
            require(versionCode > 0) { "Play version codes are positive, got $versionCode for \"$version\"" }
            versionCodes[version.trim()] = versionCode
        }

        /**
         * The Play client to use; by default `AppUpdateManagerFactory.create`.
         * Pass a `FakeAppUpdateManager` in your own tests.
         */
        fun appUpdateManager(v: AppUpdateManager) = apply { manager = v }

        fun build() = PlayEmergencyUpdateProvider(this)
    }

    companion object {
        /** The provider name in runtime reports. */
        const val PROVIDER = "google_play"

        private const val TAG = "BugCapsule"
        private const val KEY_PREFIX = "dev.bugcapsule.android.play.update#"
        private const val MAX_VERSION_LENGTH = 64
        private val BUILD_NUMBER = Regex("\\+(\\d{1,10})$")
        private val keys = AtomicInteger()

        @JvmStatic
        fun builder(context: Context) = Builder(context)

        private fun options(type: Int) = AppUpdateOptions.newBuilder(type).build()

        private fun describe(e: Exception?): String = when (e) {
            is InstallException -> "Play In-App Updates error ${e.errorCode}"
            null -> "Play In-App Updates returned no update info"
            else -> e.toString()
        }
    }
}
