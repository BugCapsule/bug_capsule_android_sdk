package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J

/** Event / breadcrumb severity (monitoring-v1 §2 `level`). */
enum class Level(val wire: String) {
    FATAL("fatal"), ERROR("error"), WARNING("warning"), INFO("info"), DEBUG("debug");

    companion object {
        @JvmStatic
        fun parse(wire: String?): Level? = entries.firstOrNull { it.wire == wire }
    }
}

/** The user an event, session or transaction is about (`user`). Never filled from the device automatically. */
class User @JvmOverloads constructor(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    /** `"{{auto}}"` lets the server fill it from the connection (kept only with `storeIpAddresses`). */
    val ipAddress: String? = null,
    val segment: String? = null,
    val data: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>().also {
        if (id != null) it["id"] = id
        if (email != null) it["email"] = email
        if (username != null) it["username"] = username
        if (ipAddress != null) it["ipAddress"] = ipAddress
        if (segment != null) it["segment"] = segment
        if (data.isNotEmpty()) it["data"] = data
    }
}

/**
 * A breadcrumb (`breadcrumbs[]`). [type] is one of `default`, `http`,
 * `navigation`, `user`, `ui`, `error`, `debug`, `info`, `query`, `state`.
 */
class Breadcrumb @JvmOverloads constructor(
    val message: String? = null,
    val type: String = "default",
    val category: String? = null,
    val level: Level = Level.INFO,
    val data: Map<String, Any?> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "timestamp" to dev.bugcapsule.android.core.Iso8601.format(timestampMs),
        "type" to type,
        "category" to category,
        "message" to message,
        "level" to level.wire,
        "data" to data,
    )
}

/**
 * An item about to be sent, as its JSON payload (monitoring-v1 §2 for
 * events, §8 for transactions). [beforeSend][dev.bugcapsule.android.BugCapsuleConfig.beforeSend]
 * hooks may change [payload] in place or return null to drop it.
 */
class BugCapsuleEvent internal constructor(val payload: MutableMap<String, Any?>) {
    val eventId: String get() = payload["eventId"] as String

    var level: Level?
        get() = Level.parse(J.str(payload["level"]))
        set(value) {
            payload["level"] = value?.wire
        }

    @Suppress("UNCHECKED_CAST")
    var fingerprint: List<String>?
        get() = payload["fingerprint"] as? List<String>
        set(value) {
            payload["fingerprint"] = value
        }

    /** The `tags` map (created when missing). */
    @Suppress("UNCHECKED_CAST")
    val tags: MutableMap<String, Any?>
        get() = (payload["tags"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { payload["tags"] = it }

    /** `type` of the last (outermost) exception value, if any. */
    val exceptionType: String?
        get() = J.list(J.obj(payload["exception"])?.get("values"))?.lastOrNull()?.let { J.str(J.obj(it)?.get("type")) }

    /** Transaction name (transactions only). */
    val transactionName: String? get() = J.str(payload["name"])
}

/** What a [BeforeSend] hook knows besides the event. */
class Hint internal constructor(
    /** The error that was captured, when there is one. */
    val throwable: Throwable?,
    /** `exception.values[].mechanism.type` (`uncaught`, `anr`, `signal`, `manual`, ...). */
    val mechanism: String?,
    /** The capsule recorded for this failure, if any. */
    val capsuleId: String?,
)

/** Last look at an error event after redaction: return the event (changed or not) or null to drop it. */
fun interface BeforeSend {
    fun apply(event: BugCapsuleEvent, hint: Hint): BugCapsuleEvent?
}

/** Last look at a transaction: return it or null to drop it. */
fun interface BeforeSendTransaction {
    fun apply(transaction: BugCapsuleEvent): BugCapsuleEvent?
}

/** Last look at a breadcrumb (manual or derived from recorded events): return it, a replacement, or null. */
fun interface BeforeBreadcrumb {
    fun apply(breadcrumb: Breadcrumb): Breadcrumb?
}

/** What a [TracesSampler] decides on: the root of a new trace. */
class SamplingContext internal constructor(
    val name: String,
    val op: String,
    /** The upstream decision when the trace was continued ([dev.bugcapsule.android.BugCapsule.continueTrace]). */
    val parentSampled: Boolean?,
)

/** Decides the sample rate (0..1) of a new trace; null falls back to `tracesSampleRate`. */
fun interface TracesSampler {
    fun sample(context: SamplingContext): Double?
}

/** Per-call additions to [dev.bugcapsule.android.BugCapsule.captureException]. */
class CaptureOptions @JvmOverloads constructor(
    val level: Level? = null,
    val tags: Map<String, String> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap(),
    val fingerprint: List<String>? = null,
    val contexts: Map<String, Map<String, Any?>> = emptyMap(),
)
