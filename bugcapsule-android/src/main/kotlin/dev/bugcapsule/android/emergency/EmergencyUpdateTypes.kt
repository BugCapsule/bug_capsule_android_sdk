package dev.bugcapsule.android.emergency

import android.app.Activity

/** How an update reaches the user (emergency-client-v1 §7.6 `update.flow`). */
enum class EmergencyUpdateFlow(
    /** The lower-case name used in runtime reports. */
    val wire: String,
) {
    /** A full-screen flow the user has to finish or cancel (Play immediate update). */
    IMMEDIATE("immediate"),

    /** The update downloads in the background; the app installs it when it chooses (Play flexible update). */
    FLEXIBLE("flexible"),

    /** The provider opens the store's product page. */
    STORE_PAGE("store_page"),

    /** An app-defined flow, e.g. an enterprise distribution channel. */
    CUSTOM("custom"),
}

/** Where an update stands (emergency-client-v1 §7.6 `update.state`). */
enum class EmergencyUpdateState(
    /** The lower-case name used in runtime reports. */
    val wire: String,
) {
    /** The store has no version at least the required one. */
    NOT_AVAILABLE("not_available"),

    /** An update that satisfies the request can be started. */
    AVAILABLE("available"),

    /** The user accepted; the update is downloading or installing. */
    IN_PROGRESS("in_progress"),

    /** A flexible update finished downloading and waits for [EmergencyUpdates.completeUpdate]. */
    DOWNLOADED("downloaded"),

    /** The user cancelled the flow or the download. */
    DECLINED("declined"),

    /** The check, the flow or the install failed. */
    FAILED("failed"),

    /** The provider sent the user to the store's product page. */
    OPENED_STORE("opened_store"),
}

/**
 * What the app asks an update for: usually built from a `REQUIRE_UPDATE`
 * decision with [from].
 *
 * [requiredVersion] is the first version that no longer needs the
 * restriction (null: any newer store version). [action] and [severity] let a
 * provider pick a more insistent flow for urgent decisions (§7.7).
 */
class EmergencyUpdateRequest @JvmOverloads constructor(
    val requiredVersion: String? = null,
    val action: EmergencyAction? = null,
    /** The deciding policy's severity, e.g. `P0`; null when unknown. */
    val severity: String? = null,
) {
    /** `REQUIRE_UPDATE` with severity `P0` or `P1`: the decision condition of an immediate update (§7.7). */
    val isUrgent: Boolean
        get() = action == EmergencyAction.REQUIRE_UPDATE && (severity == "P0" || severity == "P1")

    override fun toString() = "EmergencyUpdateRequest(requiredVersion=$requiredVersion, action=$action, severity=$severity)"

    companion object {
        /** The required version, action and severity of [decision]. */
        @JvmStatic
        fun from(decision: EmergencyDecision) = EmergencyUpdateRequest(decision.requiredVersion, decision.action, decision.severity)
    }
}

/** The answer of [EmergencyUpdateProvider.checkAvailability]. */
class EmergencyUpdateAvailability @JvmOverloads constructor(
    /** The store has a version at least [requiredVersion] (any newer one when it is null). */
    val available: Boolean,
    /** The flows the store allows for that version; empty when none is [available]. */
    val allowedFlows: Set<EmergencyUpdateFlow> = emptySet(),
    /** The flow [EmergencyUpdateProvider.startUpdate] would use for the same request; null when none is available. */
    val flow: EmergencyUpdateFlow? = null,
    /** The store's version, when the provider can name it. */
    val availableVersion: String? = null,
    val requiredVersion: String? = null,
    /** Days since the store first offered the update, when the store says. */
    val stalenessDays: Int? = null,
    /** The store's priority of the update (Play: 0–5), when the store says. */
    val priority: Int? = null,
    /** Why the check failed; null after a successful check. */
    val error: String? = null,
) {
    override fun toString() =
        "EmergencyUpdateAvailability(available=$available, flows=${allowedFlows.map { it.wire }}, flow=${flow?.wire}, " +
            "availableVersion=$availableVersion, requiredVersion=$requiredVersion, error=$error)"
}

/**
 * What an update provider reports. The provider, flow, state and versions
 * are sent in runtime reports (emergency-client-v1 §7.6); the byte counts are
 * for the app's progress UI only.
 */
class EmergencyUpdateStatus @JvmOverloads constructor(
    /** The provider name, `^[a-z][a-z0-9_]{0,31}$` (`google_play`). */
    val provider: String,
    val flow: EmergencyUpdateFlow,
    val state: EmergencyUpdateState,
    /** At most 64 characters, or null. */
    val availableVersion: String? = null,
    /** At most 64 characters, or null. */
    val requiredVersion: String? = null,
    val bytesDownloaded: Long = 0,
    /** 0 when unknown. */
    val totalBytesToDownload: Long = 0,
) {
    override fun toString() =
        "EmergencyUpdateStatus($provider, ${flow.wire}, ${state.wire}, available=$availableVersion, required=$requiredVersion" +
            (if (totalBytesToDownload > 0) ", $bytesDownloaded/$totalBytesToDownload bytes)" else ")")
}

/** Receives the answer of an availability check, once. */
fun interface EmergencyUpdateCallback {
    fun onResult(availability: EmergencyUpdateAvailability)
}

/** Receives every update status change. */
fun interface EmergencyUpdateListener {
    fun onUpdateStatusChanged(status: EmergencyUpdateStatus)
}

/**
 * Brings a newer store build to the user when a decision says
 * `REQUIRE_UPDATE` (emergency-client-v1 §7.7). Set it with
 * [EmergencyConfig.Builder.updateProvider]; the app drives it through
 * [BugCapsuleEmergency.updates].
 *
 * `bugcapsule-android-play` implements it with Google Play In-App Updates.
 * Apps distributed outside Google Play implement it themselves, for example
 * by opening their store's page ([EmergencyUpdateFlow.STORE_PAGE],
 * [EmergencyUpdateState.OPENED_STORE]). The SDK never installs a package; a
 * provider only uses a mechanism its distribution channel allows.
 *
 * An implementation reports every change of its status to the listener the
 * SDK sets ([setListener]); the SDK forwards it to the app's listeners and
 * into runtime reports. Calls may come from any thread and must not throw.
 */
interface EmergencyUpdateProvider {
    /** Name in runtime reports, `^[a-z][a-z0-9_]{0,31}$` (e.g. `google_play`). */
    val provider: String

    /** Checks the store for a version that satisfies [request] and calls [callback] once. */
    fun checkAvailability(request: EmergencyUpdateRequest, callback: EmergencyUpdateCallback)

    /** Starts the update flow that fits [request], from [activity]; the outcome arrives as status changes. */
    fun startUpdate(activity: Activity, request: EmergencyUpdateRequest)

    /** Picks up a flow this process started and that was interrupted (call it from `onResume`). */
    fun resumeUpdate(activity: Activity)

    /**
     * Installs an update that finished downloading ([EmergencyUpdateState.DOWNLOADED]);
     * this usually restarts the app. Returns false when there is none.
     */
    fun completeUpdate(): Boolean

    /** The SDK's listener for status changes; null detaches it. */
    fun setListener(listener: EmergencyUpdateListener?)
}
