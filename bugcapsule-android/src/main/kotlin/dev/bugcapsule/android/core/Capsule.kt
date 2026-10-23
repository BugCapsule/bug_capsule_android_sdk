package dev.bugcapsule.android.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Capture mode recorded in `manifest.mode`. */
enum class CaptureMode(val wire: String) {
    DEBUG("debug"), QA("qa"), PRODUCTION("production");

    companion object {
        @JvmStatic
        fun parse(wire: String): CaptureMode = entries.first { it.wire == wire }
    }
}

enum class CheckpointKind(val wire: String) {
    LAUNCH("launch"), PERIODIC("periodic");

    companion object {
        @JvmStatic
        fun parse(wire: String): CheckpointKind = entries.first { it.wire == wire }
    }
}

enum class EligibilityStatus(val wire: String) {
    ELIGIBLE("eligible"), PARTIAL("partial"), INELIGIBLE("ineligible");

    companion object {
        @JvmStatic
        fun parse(wire: String): EligibilityStatus = entries.first { it.wire == wire }
    }
}

/** Event types of capsule spec v1 (plus `state`, runtimes-v1 §4). */
object EventTypes {
    const val LIFECYCLE = "lifecycle"
    const val POINTER = "pointer"
    const val ACTION = "action"
    const val TEXT = "text"
    const val ROUTE = "route"
    const val STATE = "state"
    const val HTTP = "http"
    const val LOG = "log"
    const val FAILURE = "failure"

    /**
     * Android extension (unknown to other readers, which ignore it): a
     * system key the app received, `{phase: down|up, keyCode, key}`. Only
     * the Back key is recorded; replayed with `Instrumentation.sendKeySync`.
     */
    const val KEY = "key"

    /** Types the replay engine dispatches back into the app. */
    val REPLAYABLE = setOf(LIFECYCLE, POINTER, ACTION, TEXT, KEY)
}

/** One recorded event. `t` is ms since the session anchor once written to a capsule. */
data class CapsuleEvent(
    val seq: Long,
    val t: Long,
    val type: String,
    val data: Map<String, Any?> = emptyMap(),
) {
    fun shift(offsetMs: Long) = copy(t = t - offsetMs)

    fun toJson(): Map<String, Any?> = linkedMapOf("seq" to seq, "t" to t, "type" to type, "data" to data)

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = CapsuleEvent(
            seq = J.long(j["seq"])!!,
            t = J.long(j["t"])!!,
            type = j["type"] as String,
            data = J.obj(j["data"]) ?: emptyMap(),
        )
    }
}

data class Checkpoint(
    val kind: CheckpointKind,
    val t: Long,
    val wallClockMs: Long,
    val afterSeq: Long,
    val data: Map<String, Any?> = emptyMap(),
) {
    fun shift(offsetMs: Long) = copy(t = t - offsetMs)

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.wire,
        "t" to t,
        "wallClockMs" to wallClockMs,
        "afterSeq" to afterSeq,
        "data" to data,
    )

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = Checkpoint(
            kind = CheckpointKind.parse(j["kind"] as String),
            t = J.long(j["t"])!!,
            wallClockMs = J.long(j["wallClockMs"])!!,
            afterSeq = J.long(j["afterSeq"]) ?: 0,
            data = J.obj(j["data"]) ?: emptyMap(),
        )
    }
}

data class MissingDependency(val dependency: String, val reason: String, val blocking: Boolean = false) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("dependency" to dependency, "reason" to reason)
        .also { if (blocking) it["blocking"] = true }

    companion object {
        fun fromJson(j: Map<String, Any?>) =
            MissingDependency(j["dependency"] as String, j["reason"] as String, J.bool(j["blocking"]) ?: false)
    }
}

data class Eligibility(val status: EligibilityStatus, val missing: List<MissingDependency>) {
    fun toJson(): Map<String, Any?> = linkedMapOf("status" to status.wire, "missing" to missing.map { it.toJson() })

    companion object {
        fun fromJson(j: Map<String, Any?>) = Eligibility(
            EligibilityStatus.parse(j["status"] as String),
            (J.list(j["missing"]) ?: emptyList()).map { MissingDependency.fromJson(J.obj(it)!!) },
        )
    }
}

/** Collects eligibility gaps from the core and every integration. */
class EligibilityBuilder {
    private val items = ArrayList<MissingDependency>()

    @JvmOverloads
    fun missing(dependency: String, reason: String, blocking: Boolean = false) {
        val d = if (dependency.length > 200) dependency.substring(0, 200) else dependency
        val r = if (reason.length > 2000) reason.substring(0, 2000) else reason
        if (items.size >= 200) return
        if (items.any { it.dependency == d && it.reason == r }) return
        items.add(MissingDependency(d, r, blocking))
    }

    fun build(): Eligibility {
        val status = when {
            items.any { it.blocking } -> EligibilityStatus.INELIGIBLE
            items.isEmpty() -> EligibilityStatus.ELIGIBLE
            else -> EligibilityStatus.PARTIAL
        }
        return Eligibility(status, items.toList())
    }
}

data class AppInfo(
    val version: String,
    val build: String,
    val id: String? = null,
    val name: String? = null,
    val flavor: String? = null,
) {
    fun toJson(): Map<String, Any?> =
        linkedMapOf("id" to id, "name" to name, "version" to version, "build" to build, "flavor" to flavor)

    companion object {
        fun fromJson(j: Map<String, Any?>) = AppInfo(
            id = J.str(j["id"]),
            name = J.str(j["name"]),
            version = j["version"] as String,
            build = j["build"] as String,
            flavor = J.str(j["flavor"]),
        )
    }
}

data class PlatformInfo(
    val os: String,
    val osVersion: String,
    val locale: String,
    val screenWidth: Double,
    val screenHeight: Double,
    val pixelRatio: Double,
    val dartVersion: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "os" to os,
        "osVersion" to osVersion,
        "locale" to locale,
        "screen" to linkedMapOf("width" to screenWidth, "height" to screenHeight, "pixelRatio" to pixelRatio),
    ).also { if (dartVersion != null) it["dartVersion"] = dartVersion }

    companion object {
        fun fromJson(j: Map<String, Any?>): PlatformInfo {
            val screen = J.obj(j["screen"]) ?: emptyMap()
            return PlatformInfo(
                os = j["os"] as String,
                osVersion = J.str(j["osVersion"]) ?: "",
                locale = J.str(j["locale"]) ?: "",
                screenWidth = J.num(screen["width"]) ?: 0.0,
                screenHeight = J.num(screen["height"]) ?: 0.0,
                pixelRatio = J.num(screen["pixelRatio"]) ?: 1.0,
                dartVersion = J.str(j["dartVersion"]),
            )
        }
    }
}

/** `manifest.runtime` (runtimes-v1 §2). */
data class RuntimeInfo(
    val name: String,
    val version: String? = null,
    val language: String? = null,
    val frameworks: List<String> = emptyList(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name).also {
        if (version != null) it["version"] = version
        if (language != null) it["language"] = language
        if (frameworks.isNotEmpty()) it["frameworks"] = frameworks
    }

    companion object {
        fun fromJson(j: Map<String, Any?>) = RuntimeInfo(
            name = j["name"] as String,
            version = J.str(j["version"]),
            language = J.str(j["language"]),
            frameworks = (J.list(j["frameworks"]) ?: emptyList()).map { it as String },
        )

        /** Runtime of a capsule without `runtime`, from `sdk.name`. */
        @JvmStatic
        fun infer(sdkName: String): String = when (sdkName) {
            "bug_capsule_flutter" -> "flutter"
            "bugcapsule-android" -> "android"
            "bugcapsule-ios" -> "ios"
            "@bugcapsule/web" -> "web"
            else -> "flutter"
        }
    }
}

data class SessionInfo(
    val id: String,
    val startedAtMs: Long,
    val wallClockAtAnchorMs: Long,
    val seed: Long,
    val durationMs: Long,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "startedAt" to Iso8601.format(startedAtMs),
        "wallClockAtAnchorMs" to wallClockAtAnchorMs,
        "seed" to seed,
        "durationMs" to durationMs,
    )

    companion object {
        fun fromJson(j: Map<String, Any?>) = SessionInfo(
            id = j["id"] as String,
            startedAtMs = Iso8601.parse(j["startedAt"] as String) ?: 0,
            wallClockAtAnchorMs = J.long(j["wallClockAtAnchorMs"])!!,
            seed = J.long(j["seed"])!!,
            durationMs = J.long(j["durationMs"])!!,
        )
    }
}

data class CapsuleManifest(
    val capsuleId: String,
    val createdAtMs: Long,
    val mode: CaptureMode,
    val app: AppInfo,
    val sdkName: String,
    val sdkVersion: String,
    val stateSchemaVersion: Int,
    val platform: PlatformInfo,
    val session: SessionInfo,
    val eligibility: Eligibility,
    val integrations: List<String>,
    val eventCount: Int,
    val droppedEvents: Int,
    val checkpointKind: CheckpointKind,
    val gitSha: String? = null,
    val dirty: Boolean? = null,
    val failure: FailureRecord? = null,
    val labels: Map<String, String> = emptyMap(),
    val redactionKeys: List<String> = emptyList(),
    val redactionHeaders: List<String> = emptyList(),
    val runtime: RuntimeInfo? = null,
    val capabilities: Map<String, String> = emptyMap(),
    /** monitoring-v1 §4.3: `{eventId, traceId?, release, environment}` of the event sent for this failure. */
    val monitoring: Map<String, Any?>? = null,
) {
    val runtimeName: String get() = runtime?.name ?: RuntimeInfo.infer(sdkName)

    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "capsuleId" to capsuleId,
        "createdAt" to Iso8601.format(createdAtMs),
        "mode" to mode.wire,
        "app" to app.toJson(),
        "source" to linkedMapOf("gitSha" to gitSha, "dirty" to dirty),
        "sdk" to linkedMapOf("name" to sdkName, "version" to sdkVersion),
        "stateSchemaVersion" to stateSchemaVersion,
        "platform" to platform.toJson(),
        "session" to session.toJson(),
        "failure" to failure?.toJson(),
        "eligibility" to eligibility.toJson(),
        "integrations" to integrations,
        "stats" to linkedMapOf(
            "eventCount" to eventCount,
            "droppedEvents" to droppedEvents,
            "checkpointKind" to checkpointKind.wire,
        ),
        "labels" to labels,
    ).also {
        if (redactionKeys.isNotEmpty() || redactionHeaders.isNotEmpty()) {
            it["redaction"] = linkedMapOf("extraKeys" to redactionKeys, "extraHeaders" to redactionHeaders)
        }
        if (runtime != null) it["runtime"] = runtime.toJson()
        if (capabilities.isNotEmpty()) it["capabilities"] = capabilities
        if (monitoring != null) it["monitoring"] = monitoring
    }

    companion object {
        fun fromJson(j: Map<String, Any?>): CapsuleManifest {
            val source = J.obj(j["source"]) ?: emptyMap()
            val sdk = J.obj(j["sdk"]) ?: emptyMap()
            val stats = J.obj(j["stats"]) ?: emptyMap()
            val redaction = J.obj(j["redaction"]) ?: emptyMap()
            return CapsuleManifest(
                capsuleId = j["capsuleId"] as String,
                createdAtMs = Iso8601.parse(j["createdAt"] as String) ?: 0,
                mode = CaptureMode.parse(j["mode"] as String),
                app = AppInfo.fromJson(J.obj(j["app"])!!),
                gitSha = J.str(source["gitSha"]),
                dirty = J.bool(source["dirty"]),
                sdkName = J.str(sdk["name"]) ?: "unknown",
                sdkVersion = J.str(sdk["version"]) ?: "unknown",
                stateSchemaVersion = J.int(j["stateSchemaVersion"]) ?: 1,
                platform = PlatformInfo.fromJson(J.obj(j["platform"])!!),
                session = SessionInfo.fromJson(J.obj(j["session"])!!),
                failure = J.obj(j["failure"])?.let { FailureRecord.fromJson(it) },
                eligibility = Eligibility.fromJson(J.obj(j["eligibility"])!!),
                integrations = (J.list(j["integrations"]) ?: emptyList()).map { it as String },
                eventCount = J.int(stats["eventCount"]) ?: 0,
                droppedEvents = J.int(stats["droppedEvents"]) ?: 0,
                checkpointKind = CheckpointKind.parse(J.str(stats["checkpointKind"]) ?: "launch"),
                labels = (J.obj(j["labels"]) ?: emptyMap()).mapValues { it.value as String },
                redactionKeys = (J.list(redaction["extraKeys"]) ?: emptyList()).map { it as String },
                redactionHeaders = (J.list(redaction["extraHeaders"]) ?: emptyList()).map { it as String },
                runtime = J.obj(j["runtime"])?.let { RuntimeInfo.fromJson(it) },
                capabilities = (J.obj(j["capabilities"]) ?: emptyMap()).mapValues { it.value as String },
                monitoring = J.obj(j["monitoring"]),
            )
        }
    }
}

data class Capsule(
    val manifest: CapsuleManifest,
    val checkpoint: Checkpoint,
    val events: List<CapsuleEvent>,
) {
    fun eventsOfType(type: String) = events.filter { it.type == type }
}

/** ISO-8601 in UTC with milliseconds, like Dart's `toUtc().toIso8601String()`. */
object Iso8601 {
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    private val pattern = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})$""",
        RegexOption.IGNORE_CASE,
    )

    @JvmStatic
    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))

    /** True when [s] is an ISO-8601 date-time with a time zone (validator rule). */
    @JvmStatic
    fun isValid(s: String): Boolean = pattern.matches(s)

    @JvmStatic
    fun parse(s: String): Long? {
        val m = pattern.matchEntire(s) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val sec = m.groupValues[6].ifEmpty { "0" }.toInt()
        val frac = m.groupValues[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), sec)
        }
        var ms = cal.timeInMillis + frac
        val zone = m.groupValues[8]
        if (!zone.equals("Z", ignoreCase = true)) {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
            ms -= sign * offset
        }
        return ms
    }
}
