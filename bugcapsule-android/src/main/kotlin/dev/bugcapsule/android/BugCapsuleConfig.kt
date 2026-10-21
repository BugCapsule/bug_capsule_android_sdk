package dev.bugcapsule.android

import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.monitoring.BeforeBreadcrumb
import dev.bugcapsule.android.monitoring.BeforeSend
import dev.bugcapsule.android.monitoring.BeforeSendTransaction
import dev.bugcapsule.android.monitoring.TracesSampler
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

    // ------------------------------------------------- monitoring (monitoring-v1 §11)

    /** False turns off everything monitoring adds (errors, sessions, tracing, feedback envelopes). */
    val monitoring: Boolean = b.monitoring

    /** Null: `<app id>@<version>+<build>`. */
    val release: String? = b.release

    /** Null: `production`, `qa` or `development` from the capture mode. */
    val environment: String? = b.environment
    val dist: String? = b.dist

    /** Share of non-fatal error events sent (remote `errorSampleRate` overrides it). */
    val errorSampleRate: Double = b.errorSampleRate

    /** Share of traces sampled when there is no [tracesSampler] (remote `tracesSampleRate` overrides it); null = 0. */
    val tracesSampleRate: Double? = b.tracesSampleRate
    val tracesSampler: TracesSampler? = b.tracesSampler

    /** URLs (substring, or `/regex/`) that receive `traceparent` / `baggage`; none by default. */
    val tracePropagationTargets: List<String> = b.tracePropagationTargets.toList()
    val enableAutoSessionTracking: Boolean = b.enableAutoSessionTracking

    /** A session ends after this long in the background. */
    val sessionTimeoutMs: Long = b.sessionTimeoutMs
    val maxBreadcrumbs: Int = b.maxBreadcrumbs
    val beforeSend: BeforeSend? = b.beforeSend
    val beforeSendTransaction: BeforeSendTransaction? = b.beforeSendTransaction
    val beforeBreadcrumb: BeforeBreadcrumb? = b.beforeBreadcrumb

    /** Package prefixes of in-app frames; empty: the app id. */
    val inAppIncludes: List<String> = b.inAppIncludes.toList()
    val inAppExcludes: List<String> = b.inAppExcludes.toList()

    /** Adds the calling thread's stack to `captureMessage` events. */
    val attachStacktrace: Boolean = b.attachStacktrace

    /** True: `user.ipAddress` is `{{auto}}` (the server fills it, kept only with `storeIpAddresses`). */
    val sendDefaultPii: Boolean = b.sendDefaultPii
    val enableAnrTracking: Boolean = b.enableAnrTracking
    val anrTimeoutMs: Long = b.anrTimeoutMs

    /** Needs the `bugcapsule-ndk` artifact; ignored without it. */
    val enableNativeCrashHandling: Boolean = b.enableNativeCrashHandling

    /** `ui.action.click` transactions for semantic actions ([BugCapsule.action], Compose/Views helpers). */
    val enableUserInteractionTracing: Boolean = b.enableUserInteractionTracing
    val enableAppStartTracking: Boolean = b.enableAppStartTracking

    /** `ui.load` transactions for activities (and spans for fragments). */
    val enableActivityTracing: Boolean = b.enableActivityTracing

    /** Screen transactions wait for [BugCapsule.reportFullyDrawn] (`ui.load.full_display`, TTFD). */
    val enableTimeToFullDisplayTracing: Boolean = b.enableTimeToFullDisplayTracing
    val enableFramesTracking: Boolean = b.enableFramesTracking

    /** Idle time after which a screen / interaction transaction finishes when no span is running. */
    val idleTransactionTimeoutMs: Long = b.idleTransactionTimeoutMs

    /** Null: true in [CaptureMode.QA]. Submitting feedback also writes a capsule, linked by `capsuleId`. */
    val captureCapsuleWithFeedback: Boolean? = b.captureCapsuleWithFeedback

    /** Shaking the device opens the feedback dialog on the resumed activity. */
    val enableShakeToReport: Boolean = b.enableShakeToReport

    /** Null: read from `assets/bugcapsule-debug-meta.properties` (written by the Gradle plugin). */
    val proguardUuid: String? = b.proguardUuid

    /** Bounds of the monitoring envelope outbox (default 30 envelopes / 5 MiB / 7 days). */
    val envelopeOutboxLimits: OutboxLimits = b.envelopeOutboxLimits

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
        internal var monitoring = true
        internal var release: String? = null
        internal var environment: String? = null
        internal var dist: String? = null
        internal var errorSampleRate = 1.0
        internal var tracesSampleRate: Double? = null
        internal var tracesSampler: TracesSampler? = null
        internal val tracePropagationTargets = ArrayList<String>()
        internal var enableAutoSessionTracking = true
        internal var sessionTimeoutMs = 30_000L
        internal var maxBreadcrumbs = 100
        internal var beforeSend: BeforeSend? = null
        internal var beforeSendTransaction: BeforeSendTransaction? = null
        internal var beforeBreadcrumb: BeforeBreadcrumb? = null
        internal val inAppIncludes = ArrayList<String>()
        internal val inAppExcludes = ArrayList<String>()
        internal var attachStacktrace = true
        internal var sendDefaultPii = false
        internal var enableAnrTracking = true
        internal var anrTimeoutMs = 5_000L
        internal var enableNativeCrashHandling = true
        internal var enableUserInteractionTracing = false
        internal var enableAppStartTracking = true
        internal var enableActivityTracing = true
        internal var enableTimeToFullDisplayTracing = false
        internal var enableFramesTracking = true
        internal var idleTransactionTimeoutMs = 3_000L
        internal var captureCapsuleWithFeedback: Boolean? = null
        internal var enableShakeToReport = false
        internal var proguardUuid: String? = null
        internal var envelopeOutboxLimits = OutboxLimits(maxCount = 30, maxBytes = 5L * 1024 * 1024)

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
            monitoring = c.monitoring; release = c.release; environment = c.environment; dist = c.dist
            errorSampleRate = c.errorSampleRate; tracesSampleRate = c.tracesSampleRate; tracesSampler = c.tracesSampler
            tracePropagationTargets += c.tracePropagationTargets; enableAutoSessionTracking = c.enableAutoSessionTracking
            sessionTimeoutMs = c.sessionTimeoutMs; maxBreadcrumbs = c.maxBreadcrumbs; beforeSend = c.beforeSend
            beforeSendTransaction = c.beforeSendTransaction; beforeBreadcrumb = c.beforeBreadcrumb
            inAppIncludes += c.inAppIncludes; inAppExcludes += c.inAppExcludes; attachStacktrace = c.attachStacktrace
            sendDefaultPii = c.sendDefaultPii; enableAnrTracking = c.enableAnrTracking; anrTimeoutMs = c.anrTimeoutMs
            enableNativeCrashHandling = c.enableNativeCrashHandling; enableUserInteractionTracing = c.enableUserInteractionTracing
            enableAppStartTracking = c.enableAppStartTracking; enableActivityTracing = c.enableActivityTracing
            enableTimeToFullDisplayTracing = c.enableTimeToFullDisplayTracing; enableFramesTracking = c.enableFramesTracking
            idleTransactionTimeoutMs = c.idleTransactionTimeoutMs; captureCapsuleWithFeedback = c.captureCapsuleWithFeedback
            enableShakeToReport = c.enableShakeToReport; proguardUuid = c.proguardUuid; envelopeOutboxLimits = c.envelopeOutboxLimits
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
        fun monitoring(v: Boolean) = apply { monitoring = v }
        fun release(v: String?) = apply { release = v?.takeIf { it.isNotBlank() } }
        fun environment(v: String?) = apply { environment = v?.takeIf { it.isNotBlank() } }
        fun dist(v: String?) = apply { dist = v?.takeIf { it.isNotBlank() } }
        fun errorSampleRate(v: Double) = apply { errorSampleRate = v.coerceIn(0.0, 1.0) }
        fun tracesSampleRate(v: Double?) = apply { tracesSampleRate = v?.coerceIn(0.0, 1.0) }
        fun tracesSampler(v: TracesSampler?) = apply { tracesSampler = v }
        fun tracePropagationTargets(vararg targets: String) = apply { tracePropagationTargets += targets }
        fun tracePropagationTargets(targets: List<String>) = apply { tracePropagationTargets += targets }
        fun enableAutoSessionTracking(v: Boolean) = apply { enableAutoSessionTracking = v }
        fun sessionTimeoutMs(v: Long) = apply { sessionTimeoutMs = v.coerceAtLeast(0) }
        fun maxBreadcrumbs(v: Int) = apply { maxBreadcrumbs = v.coerceIn(0, 100) }
        fun beforeSend(v: BeforeSend?) = apply { beforeSend = v }
        fun beforeSendTransaction(v: BeforeSendTransaction?) = apply { beforeSendTransaction = v }
        fun beforeBreadcrumb(v: BeforeBreadcrumb?) = apply { beforeBreadcrumb = v }
        fun inAppIncludes(vararg packages: String) = apply { inAppIncludes += packages }
        fun inAppExcludes(vararg packages: String) = apply { inAppExcludes += packages }
        fun attachStacktrace(v: Boolean) = apply { attachStacktrace = v }
        fun sendDefaultPii(v: Boolean) = apply { sendDefaultPii = v }
        fun enableAnrTracking(v: Boolean) = apply { enableAnrTracking = v }
        fun anrTimeoutMs(v: Long) = apply { anrTimeoutMs = v.coerceAtLeast(1000) }
        fun enableNativeCrashHandling(v: Boolean) = apply { enableNativeCrashHandling = v }
        fun enableUserInteractionTracing(v: Boolean) = apply { enableUserInteractionTracing = v }
        fun enableAppStartTracking(v: Boolean) = apply { enableAppStartTracking = v }
        fun enableActivityTracing(v: Boolean) = apply { enableActivityTracing = v }
        fun enableTimeToFullDisplayTracing(v: Boolean) = apply { enableTimeToFullDisplayTracing = v }
        fun enableFramesTracking(v: Boolean) = apply { enableFramesTracking = v }
        fun idleTransactionTimeoutMs(v: Long) = apply { idleTransactionTimeoutMs = v.coerceAtLeast(0) }
        fun captureCapsuleWithFeedback(v: Boolean?) = apply { captureCapsuleWithFeedback = v }
        fun enableShakeToReport(v: Boolean) = apply { enableShakeToReport = v }
        fun proguardUuid(v: String?) = apply { proguardUuid = v?.takeIf { it.isNotBlank() } }
        fun envelopeOutboxLimits(v: OutboxLimits) = apply { envelopeOutboxLimits = v }
        fun build() = BugCapsuleConfig(this)
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}
