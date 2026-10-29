package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.BugCapsuleBackendConfig

/**
 * Configuration of [BugCapsuleEmergency.initialize]. Build it with [builder]:
 *
 * ```kotlin
 * val config = EmergencyConfig.builder()
 *     .trustAnchor(BuildConfig.BUGCAPSULE_EMERGENCY_ANCHOR)   // bcek.v1.… from the dashboard
 *     .environment("production")
 *     .failureMode("checkout.*", EmergencyFailureMode.FAIL_CLOSED)
 *     .build()
 * ```
 *
 * The connection (API URL and ingest credential) defaults to the one
 * [dev.bugcapsule.android.BugCapsule.init] resolved; set [Builder.token] or
 * [Builder.backend] to use another one, or to use emergency policies without
 * capture.
 */
class EmergencyConfig private constructor(b: Builder) {
    /**
     * The trust anchor (`bcek.v1.…`) from the dashboard: the application id and
     * the public keys allowed to sign its documents. It is compiled into the
     * app and never fetched. An invalid anchor turns emergency policies off
     * (every key uses its failure mode) and logs one warning.
     */
    val trustAnchor: String? = b.trustAnchor

    /** Environment whose policies apply (`production` by default). */
    val environment: String = b.environment

    /** Time between refreshes while the app is in the foreground (default 60 s, at least 15 s). */
    val refreshIntervalMs: Long = b.refreshIntervalMs

    /** Failure modes by exact key or `prefix.*`; every other key is [EmergencyFailureMode.FAIL_OPEN]. */
    val failureModes: Map<String, EmergencyFailureMode> = LinkedHashMap(b.failureModes)

    /** SDK connection token (`bcsdk.v1.…`); null to reuse the connection of `BugCapsule.init`. */
    val token: String? = b.token

    /** Explicit backend; wins over [token]. */
    val backend: BugCapsuleBackendConfig? = b.backend

    /** App version used for version targeting; null: the configured BugCapsule app version, else the package's `versionName`. */
    val appVersion: String? = b.appVersion

    /** Brings store updates for `REQUIRE_UPDATE` decisions ([BugCapsuleEmergency.updates]); null: none. */
    val updateProvider: EmergencyUpdateProvider? = b.updateProvider

    class Builder internal constructor() {
        internal var trustAnchor: String? = null
        internal var environment = DEFAULT_ENVIRONMENT
        internal var refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS
        internal val failureModes = LinkedHashMap<String, EmergencyFailureMode>()
        internal var token: String? = null
        internal var backend: BugCapsuleBackendConfig? = null
        internal var appVersion: String? = null
        internal var updateProvider: EmergencyUpdateProvider? = null

        /** Required: the `bcek.v1.…` trust anchor from the dashboard. */
        fun trustAnchor(v: String?) = apply { trustAnchor = v?.trim() }

        /**
         * Lower-case environment name (`^[a-z0-9][a-z0-9_-]{0,39}$`).
         * @throws IllegalArgumentException when [v] does not match.
         */
        fun environment(v: String) = apply {
            require(EmergencyContract.ENVIRONMENT.matches(v)) { "emergency environment \"$v\" must match ^[a-z0-9][a-z0-9_-]{0,39}$" }
            environment = v
        }

        /** Refresh interval in the foreground; values under 15 s are raised to 15 s. */
        fun refreshIntervalMs(v: Long) = apply { refreshIntervalMs = v.coerceAtLeast(MIN_REFRESH_INTERVAL_MS) }

        /**
         * Sets the failure mode of [keyPattern]: an exact feature key or
         * `prefix.*` (covers every key below `prefix`, not `prefix` itself).
         * An exact key wins over a wildcard, then the longest prefix wins.
         * There is no global wildcard: the whole app never fails closed by default.
         * @throws IllegalArgumentException when [keyPattern] is not a feature key or `prefix.*`.
         */
        fun failureMode(keyPattern: String, mode: EmergencyFailureMode) = apply {
            require(EmergencyContract.isPolicyKey(keyPattern)) {
                "\"$keyPattern\" is not a feature key (lower-case dotted, e.g. checkout.confirm_order) or prefix.*"
            }
            failureModes[keyPattern] = mode
        }

        /** SDK connection token (`bcsdk.v1.…`); null reuses the connection of `BugCapsule.init`. */
        fun token(v: String?) = apply { token = v?.takeIf { it.isNotBlank() } }

        fun backend(v: BugCapsuleBackendConfig?) = apply { backend = v }
        fun backend(url: String, ingestKey: String) = apply { backend = BugCapsuleBackendConfig(url, ingestKey) }

        /** App version for version targeting (defaults to the BugCapsule app version, else the package's `versionName`). */
        fun appVersion(v: String?) = apply { appVersion = v?.takeIf { it.isNotBlank() } }

        /**
         * The update provider behind [BugCapsuleEmergency.updates], e.g.
         * `PlayEmergencyUpdateProvider` from `bugcapsule-android-play`. Apps
         * distributed outside Google Play implement [EmergencyUpdateProvider]
         * themselves.
         * @throws IllegalArgumentException when its name does not match `^[a-z][a-z0-9_]{0,31}$`.
         */
        fun updateProvider(v: EmergencyUpdateProvider?) = apply {
            if (v != null) {
                val name = v.provider
                require(RuntimeReport.PROVIDER.matches(name)) { "update provider name \"$name\" must match ^[a-z][a-z0-9_]{0,31}$" }
            }
            updateProvider = v
        }

        fun build() = EmergencyConfig(this)
    }

    companion object {
        const val DEFAULT_ENVIRONMENT = "production"
        const val DEFAULT_REFRESH_INTERVAL_MS = 60_000L
        const val MIN_REFRESH_INTERVAL_MS = 15_000L

        @JvmStatic
        fun builder() = Builder()
    }
}
