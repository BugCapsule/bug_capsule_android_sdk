package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.Redactor

/**
 * The capsule redaction rules applied to a whole envelope item before it is
 * persisted (monitoring-v1 §1 *Common rules*): sensitive keys anywhere,
 * free-text credentials in every string, URLs and headers like recorded
 * network events. Ids, timestamps and other structural fields are kept as
 * they are (a UUID can look like a card number to the free-text rules).
 */
internal object ItemRedaction {
    private val STRUCTURAL = setOf(
        "eventId", "feedbackId", "sid", "did", "seq", "init", "timestamp", "startTimestamp", "started", "sentAt",
        "traceId", "spanId", "parentSpanId", "debugId", "capsuleId", "release", "environment", "runtime", "dist",
        "level", "status", "op", "instructionAddr", "imageAddr", "imageVmAddr", "imageSize", "codeFile", "arch",
        "lineno", "colno", "inApp", "handled", "duration", "errors",
    )

    @Suppress("UNCHECKED_CAST")
    fun redact(redactor: Redactor, payload: Map<String, Any?>): MutableMap<String, Any?> =
        walk(redactor, payload, null) as MutableMap<String, Any?>

    private fun walk(r: Redactor, value: Any?, key: String?): Any? {
        if (key != null && key in STRUCTURAL) return value
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in value) {
                    val name = k.toString()
                    out[name] = when {
                        name in STRUCTURAL -> v
                        v != null && r.isSensitiveKey(name) -> Redactor.PLACEHOLDER
                        name == "headers" && v is Map<*, *> -> r.redactHeaders(v.entries.associate { it.key.toString() to it.value })
                        (name == "url" || name == "absPath") && v is String -> r.redactUrl(v)
                        else -> walk(r, v, name)
                    }
                }
                out
            }
            is List<*> -> value.map { walk(r, it, null) }
            is String -> r.redactJson(value)
            else -> value
        }
    }
}

/** Field limits of monitoring-v1 §2 that would otherwise reject an item; strings the server cuts anyway are cut here too. */
internal object Limits {
    private val TAG_KEY = Regex("^[A-Za-z0-9_.:-]{1,32}$")

    fun tags(tags: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in tags) {
            if (out.size >= 50) break
            if (v == null || !TAG_KEY.matches(k)) continue
            out[k] = cut(v.toString(), 200)
        }
        return out
    }

    fun fingerprint(fp: List<String>?): List<String>? = fp?.take(10)?.map { cut(it, 200) }?.takeIf { it.isNotEmpty() }

    fun cut(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)

    /**
     * Shrinks an item whose JSON exceeds [maxBytes] (1 MiB per item):
     * breadcrumbs, threads and extras first, then spans, then frames. Null
     * when it still does not fit.
     */
    @Suppress("UNCHECKED_CAST")
    fun fit(payload: MutableMap<String, Any?>, maxBytes: Int = 1024 * 1024): MutableMap<String, Any?>? {
        fun size() = Json.stringify(payload).toByteArray(Charsets.UTF_8).size
        if (size() <= maxBytes) return payload
        val steps: List<() -> Unit> = listOf(
            { payload["breadcrumbs"] = (J.list(payload["breadcrumbs"]) ?: emptyList()).takeLast(10) },
            { payload.remove("threads") },
            { payload["extra"] = mapOf("_trimmed" to true) },
            { payload["spans"] = (J.list(payload["spans"]) ?: emptyList()).take(100) },
            { payload["breadcrumbs"] = emptyList<Any?>() },
            {
                val values = J.list(J.obj(payload["exception"])?.get("values")) ?: emptyList()
                for (v in values) {
                    val st = J.obj(J.obj(v)?.get("stacktrace")) as? MutableMap<String, Any?> ?: continue
                    st["frames"] = (J.list(st["frames"]) ?: emptyList()).takeLast(50)
                }
            },
        )
        for (step in steps) {
            if (!payload.containsKey("breadcrumbs") && !payload.containsKey("spans") && !payload.containsKey("exception")) break
            step()
            if (size() <= maxBytes) return payload
        }
        return null
    }
}

/**
 * Breadcrumbs derived from flight-recorder events (monitoring-v1 §2): every
 * integration records once and both capsules and events see it. Pointer
 * events are not breadcrumbs; tracked text never carries its value.
 */
internal class BreadcrumbDeriver {
    @Volatile
    private var lastRoute: String? = null

    fun derive(type: String, data: Map<String, Any?>, timestampMs: Long): Breadcrumb? = when (type) {
        EventTypes.ROUTE -> {
            val to = J.str(data["location"])
            val from = lastRoute
            lastRoute = to
            Breadcrumb(null, "navigation", "navigation", Level.INFO, linkedMapOf("from" to from, "to" to to), timestampMs)
        }
        EventTypes.ACTION -> Breadcrumb(J.str(data["id"]), "user", "ui.action", Level.INFO, linkedMapOf("id" to data["id"]), timestampMs)
        EventTypes.TEXT -> Breadcrumb(null, "user", "ui.input", Level.INFO, linkedMapOf("id" to data["id"]), timestampMs)
        EventTypes.KEY -> if (data["phase"] == "up") {
            Breadcrumb(null, "user", "ui.key", Level.INFO, linkedMapOf("key" to data["key"]), timestampMs)
        } else {
            null
        }
        EventTypes.HTTP -> {
            val status = J.int(data["status"]) ?: 0
            val error = J.obj(data["error"])
            val level = when {
                error != null || status >= 500 -> Level.ERROR
                status >= 400 -> Level.WARNING
                else -> Level.INFO
            }
            val d = linkedMapOf<String, Any?>("method" to data["method"], "url" to data["url"])
            if (status > 0) d["status_code"] = status
            J.long(data["durationMs"])?.let { d["duration_ms"] = it }
            if (error != null) d["error"] = error["type"]
            Breadcrumb(null, "http", "http", level, d, timestampMs)
        }
        EventTypes.LIFECYCLE -> Breadcrumb(null, "navigation", "app.lifecycle", Level.INFO, linkedMapOf("state" to data["state"]), timestampMs)
        EventTypes.LOG -> {
            val level = when (J.str(data["level"])?.lowercase()) {
                "error", "severe", "fatal" -> Level.ERROR
                "warn", "warning" -> Level.WARNING
                "debug", "fine", "trace", "verbose" -> Level.DEBUG
                else -> Level.INFO
            }
            Breadcrumb(J.str(data["message"]), if (level == Level.DEBUG) "debug" else "info", "log", level, emptyMap(), timestampMs)
        }
        EventTypes.STATE -> Breadcrumb(null, "state", "state", Level.INFO, linkedMapOf("key" to data["key"]), timestampMs)
        EventTypes.FAILURE -> Breadcrumb(
            J.str(data["message"])?.takeIf { it.isNotEmpty() } ?: J.str(data["type"]),
            "error",
            "failure",
            Level.ERROR,
            linkedMapOf("signature" to data["signature"]),
            timestampMs,
        )
        EventTypes.POINTER -> null
        else -> Breadcrumb(null, "default", type, Level.INFO, emptyMap(), timestampMs)
    }
}
