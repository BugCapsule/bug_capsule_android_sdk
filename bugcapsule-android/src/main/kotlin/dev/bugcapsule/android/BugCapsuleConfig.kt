package dev.bugcapsule.android

import dev.bugcapsule.android.core.CaptureMode
import java.util.regex.Pattern

/** Where capsules are uploaded and remote capture config comes from. */
class BugCapsuleBackendConfig(
    /** e.g. `http://10.0.2.2:8787` from an emulator, `https://bugcapsule.example.com` hosted. */
    val url: String,
    /** Project ingest key (`bci_...`). Upload-only; safe to ship in the app. Never put a `bcs_` key in an app. */
    val ingestKey: String,
)

/** Bounds of the upload outbox; whichever is hit first evicts the oldest capsules. */
class OutboxLimits @JvmOverloads constructor(
    val maxCount: Int = 10,
    val maxBytes: Long = 10L * 1024 * 1024,
    val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
)

/** Last look at every recorded event after built-in redaction: return the data to keep, or null to drop it. */
fun interface BeforeRecord {
    fun apply(type: String, data: Map<String, Any?>): Map<String, Any?>?
}

/** Last look at each integration's checkpoint snapshot after redaction: return null to leave it out. */
fun interface BeforeCheckpoint {
    fun apply(integration: String, data: Any?): Any?
}

/** Return true for errors that must not count as failures. */
fun interface ErrorFilter {
    fun ignore(error: Throwable): Boolean
}

/**
 * Configuration of [BugCapsule.init]. Build it with [builder]:
 *
 * ```kotlin
 * val config = BugCapsuleConfig.builder()
 *     .mode(CaptureMode.QA)
 *     .backend("https://bugcapsule.example.com", BuildConfig.BUGCAPSULE_INGEST_KEY)
 *     .gitSha(BuildConfig.BUGCAPSULE_GIT_SHA)
 *     .redactKeys("loyaltyId")
 *     .build()
 * ```
 *
 * Every value has a default; app version/build/id default to the package info.
 */
class BugCapsuleConfig private constructor(b: Builder) {
    val appVersion: String? = b.appVersion
    val appBuild: String? = b.appBuild
    val appId: String? = b.appId
    val appName: String? = b.appName
    val flavor: String? = b.flavor

    /** Null: [CaptureMode.DEBUG] for debuggable builds, [CaptureMode.PRODUCTION] otherwise. */
    val mode: CaptureMode? = b.mode

    /** Local master switch; the backend kill switch can also disable capture. */
    val enabled: Boolean = b.enabled
    val backend: BugCapsuleBackendConfig? = b.backend

    /** Null: the `dev.bugcapsule.GIT_SHA` manifest meta-data, if any. */
    val gitSha: String? = b.gitSha
    val gitDirty: Boolean? = b.gitDirty
    val stateSchemaVersion: Int = b.stateSchemaVersion
    val checkpointIntervalMs: Long = b.checkpointIntervalMs
    val windowMs: Long = b.windowMs
    val maxEvents: Int = b.maxEvents

    /** Maximum fraction of production sessions that record (remote `sampleRate` can lower it, never raise it). */
    val productionSampleRate: Double = b.productionSampleRate
    val autoCaptureOnFailure: Boolean = b.autoCaptureOnFailure
    val maxCapsulesPerSession: Int = b.maxCapsulesPerSession
    val captureNetworkBodies: Boolean = b.captureNetworkBodies
    val maxBodyBytes: Int = b.maxBodyBytes
    val redactKeys: List<String> = b.redactKeys.toList()
    val redactHeaders: List<String> = b.redactHeaders.toList()
    val redactPatterns: List<Pattern> = b.redactPatterns.toList()
    val beforeRecord: BeforeRecord? = b.beforeRecord
    val beforeCheckpoint: BeforeCheckpoint? = b.beforeCheckpoint
    val outboxLimits: OutboxLimits = b.outboxLimits
    val maxStaleConfigMs: Long = b.maxStaleConfigMs
    val ignoreError: ErrorFilter? = b.ignoreError
    val labels: Map<String, String> = b.labels.toMap()
    val maxSavedCaptures: Int = b.maxSavedCaptures

    fun toBuilder(): Builder = Builder(this)

    class Builder() {
        internal var appVersion: String? = null
        internal var appBuild: String? = null
        internal var appId: String? = null
        internal var appName: String? = null
        internal var flavor: String? = null
        internal var mode: CaptureMode? = null
        internal var enabled = true
        internal var backend: BugCapsuleBackendConfig? = null
        internal var gitSha: String? = null
        internal var gitDirty: Boolean? = null
        internal var stateSchemaVersion = 1
        internal var checkpointIntervalMs = 30_000L
        internal var windowMs = 5 * 60_000L
        internal var maxEvents = 20_000
        internal var productionSampleRate = 0.1
        internal var autoCaptureOnFailure = true
        internal var maxCapsulesPerSession = 3
        internal var captureNetworkBodies = true
        internal var maxBodyBytes = 256 * 1024
        internal val redactKeys = ArrayList<String>()
        internal val redactHeaders = ArrayList<String>()
        internal val redactPatterns = ArrayList<Pattern>()
        internal var beforeRecord: BeforeRecord? = null
        internal var beforeCheckpoint: BeforeCheckpoint? = null
        internal var outboxLimits = OutboxLimits()
        internal var maxStaleConfigMs = 7L * 24 * 60 * 60 * 1000
        internal var ignoreError: ErrorFilter? = null
        internal val labels = LinkedHashMap<String, String>()
        internal var maxSavedCaptures = 20

        internal constructor(c: BugCapsuleConfig) : this() {
            appVersion = c.appVersion; appBuild = c.appBuild; appId = c.appId; appName = c.appName
            flavor = c.flavor; mode = c.mode; enabled = c.enabled; backend = c.backend; gitSha = c.gitSha
            gitDirty = c.gitDirty; stateSchemaVersion = c.stateSchemaVersion
            checkpointIntervalMs = c.checkpointIntervalMs; windowMs = c.windowMs; maxEvents = c.maxEvents
            productionSampleRate = c.productionSampleRate; autoCaptureOnFailure = c.autoCaptureOnFailure
            maxCapsulesPerSession = c.maxCapsulesPerSession; captureNetworkBodies = c.captureNetworkBodies
            maxBodyBytes = c.maxBodyBytes; redactKeys += c.redactKeys; redactHeaders += c.redactHeaders
            redactPatterns += c.redactPatterns; beforeRecord = c.beforeRecord; beforeCheckpoint = c.beforeCheckpoint
            outboxLimits = c.outboxLimits; maxStaleConfigMs = c.maxStaleConfigMs; ignoreError = c.ignoreError
            labels += c.labels; maxSavedCaptures = c.maxSavedCaptures
        }

        fun appVersion(v: String?) = apply { appVersion = v }
        fun appBuild(v: String?) = apply { appBuild = v }
        fun appId(v: String?) = apply { appId = v }
        fun appName(v: String?) = apply { appName = v }
        fun flavor(v: String?) = apply { flavor = v }
        fun mode(v: CaptureMode?) = apply { mode = v }
        fun enabled(v: Boolean) = apply { enabled = v }
        fun backend(v: BugCapsuleBackendConfig?) = apply { backend = v }
        fun backend(url: String, ingestKey: String) = apply { backend = BugCapsuleBackendConfig(url, ingestKey) }
        fun gitSha(v: String?) = apply { gitSha = v?.takeIf { it.isNotBlank() } }
        fun gitDirty(v: Boolean?) = apply { gitDirty = v }
        fun stateSchemaVersion(v: Int) = apply { stateSchemaVersion = v }
        fun checkpointIntervalMs(v: Long) = apply { checkpointIntervalMs = v.coerceAtLeast(1000) }
        fun windowMs(v: Long) = apply { windowMs = v }
        fun maxEvents(v: Int) = apply { maxEvents = v }
        fun productionSampleRate(v: Double) = apply { productionSampleRate = v }
        fun autoCaptureOnFailure(v: Boolean) = apply { autoCaptureOnFailure = v }
        fun maxCapsulesPerSession(v: Int) = apply { maxCapsulesPerSession = v }
        fun captureNetworkBodies(v: Boolean) = apply { captureNetworkBodies = v }
        fun maxBodyBytes(v: Int) = apply { maxBodyBytes = v }
        fun redactKeys(vararg keys: String) = apply { redactKeys += keys }
        fun redactHeaders(vararg headers: String) = apply { redactHeaders += headers }
        fun redactPatterns(vararg patterns: Pattern) = apply { redactPatterns += patterns }
        fun redactPattern(regex: String) = apply { redactPatterns += Pattern.compile(regex) }
        fun beforeRecord(v: BeforeRecord?) = apply { beforeRecord = v }
        fun beforeCheckpoint(v: BeforeCheckpoint?) = apply { beforeCheckpoint = v }
        fun outboxLimits(v: OutboxLimits) = apply { outboxLimits = v }
        fun maxStaleConfigMs(v: Long) = apply { maxStaleConfigMs = v }
        fun ignoreError(v: ErrorFilter?) = apply { ignoreError = v }
        fun label(key: String, value: String) = apply { labels[key] = value }
        fun maxSavedCaptures(v: Int) = apply { maxSavedCaptures = v }
        fun build() = BugCapsuleConfig(this)
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}
