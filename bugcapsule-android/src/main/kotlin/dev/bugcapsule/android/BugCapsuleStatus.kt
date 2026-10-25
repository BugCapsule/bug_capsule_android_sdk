package dev.bugcapsule.android

/** Connection to the BugCapsule backend, as [BugCapsule.status] reports it. [code] is the cross-SDK name. */
enum class ConnectionState(val code: String) {
    /** No token and no backend: capsules stay on the device. */
    NOT_CONFIGURED("not_configured"),

    /** The token can't be used; [BugCapsuleStatus.Connection.message] says why. The app runs without a backend. */
    INVALID_TOKEN("invalid_token"),

    /** The token needs a newer SDK. The app runs without a backend. */
    UNSUPPORTED_TOKEN_VERSION("unsupported_token_version"),

    /** Configured; the configuration has not been fetched yet in this process. */
    CONNECTING("connecting"),

    /** The backend answered the configuration request with this app's credential. */
    CONNECTED("connected"),

    /** The backend could not be reached (no network, timeout, 5xx): data is kept and retried. */
    OFFLINE("offline"),

    /** The backend refused the credential (revoked, expired, unknown): capture and uploads are off for this process. */
    REJECTED("rejected"),

    /** Turned off locally (`enabled(false)`), by the project's remote kill switch, or replaying. */
    DISABLED("disabled"),
}

/** How the backend was configured. */
enum class ConnectionSource(val code: String) {
    /** `BugCapsuleConfig.Builder.token` / `BugCapsule.init(app, token)`. */
    TOKEN("token"),

    /** An explicit backend URL + ingest key (`BugCapsuleConfig.Builder.backend`). */
    BACKEND("backend"),
}

/**
 * Connection and instrumentation, for setup checks and support
 * ([BugCapsule.status]). A snapshot: call again for the current state.
 */
data class BugCapsuleStatus(
    val connection: Connection,
    val instrumentation: Instrumentation,
) {
    /**
     * Two separate confirmations: [configFetchedAt] proves URL, credential
     * and network; [firstEventAcceptedAt] / [firstCapsuleAcceptedAt] prove
     * that captured data reached the backend. Times are epoch milliseconds.
     */
    data class Connection(
        val state: ConnectionState,
        val source: ConnectionSource?,
        /** The backend API URL in use (from the token or the explicit backend), or null. */
        val apiUrl: String?,
        /** Machine-readable cause: the token error code, the backend's refusal `reason` (e.g. `token_revoked`), or the network error. */
        val reason: String?,
        /** What to do about it, in one line. */
        val message: String?,
        /** Last configuration fetched in this process (confirmation 1). */
        val configFetchedAt: Long?,
        /** First error event the backend accepted in this process (confirmation 2). */
        val firstEventAcceptedAt: Long?,
        /** First Capsule the backend accepted in this process (confirmation 2). */
        val firstCapsuleAcceptedAt: Long?,
    )

    /**
     * Basic connectivity is not complete replay instrumentation: these are
     * the progressive setup steps the SDK can detect in this process.
     */
    data class Instrumentation(
        /** This session records a capsule window (enabled, sampled, not stopped). */
        val recording: Boolean,
        /** Network fixtures: a `bugcapsule-okhttp` integration was passed to `init`. */
        val network: Boolean,
        /** Navigation: a `bugcapsule-navigation` integration was passed to `init`. */
        val navigation: Boolean,
        /** App state: registered state / ViewModels, or SharedPreferences / DataStore checkpoints. */
        val appState: Boolean,
        /** `bugcapsule-ndk` is in the app (native crash events). */
        val nativeCrashes: Boolean,
        /** UI frameworks seen (`views`, plus `compose` once a Compose helper ran). */
        val frameworks: List<String>,
        /** Names of the integrations passed to `init`. */
        val integrations: List<String>,
        /** Replay adapters still missing: `network`, `navigation`, `app_state`. */
        val missing: List<String>,
    )
}
