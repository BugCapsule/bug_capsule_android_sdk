package dev.bugcapsule.android.core

import java.util.regex.Pattern

/**
 * Per-endpoint normalization applied before a request key is computed.
 *
 * [path] matches the URL path exactly, with `:name` matching one segment and a
 * trailing `*` any remainder. [method] may be `*`. A plain name in
 * [ignoreBodyFields] is removed at any depth; a dotted path only at that path.
 * When [matchBodyFields] is set only those top-level fields take part.
 */
class EndpointRule @JvmOverloads constructor(
    method: String,
    val path: String,
    val ignoreQuery: Set<String> = emptySet(),
    val ignoreBodyFields: Set<String> = emptySet(),
    val matchBodyFields: Set<String>? = null,
) {
    val method: String = method.uppercase()
    private val pattern: Pattern = compile(path)

    fun matches(requestMethod: String, requestPath: String): Boolean =
        (method == "*" || method == requestMethod.uppercase()) && pattern.matcher(requestPath).find()

    private companion object {
        fun compile(path: String): Pattern {
            val wildcard = path.endsWith("*")
            val body = if (wildcard) path.substring(0, path.length - 1) else path
            val segments = body.split("/").map { if (it.startsWith(":")) "[^/]+" else Pattern.quote(it) }
            return Pattern.compile("^" + segments.joinToString("/") + if (wildcard) ".*" else "/?$")
        }
    }
}

/** Builds the stable key matching a replayed request to its recording (capsule-v1 *Network matching*). */
class RequestKeyBuilder @JvmOverloads constructor(
    val rules: List<EndpointRule> = emptyList(),
    val globalIgnoreQuery: Set<String> = DEFAULT_IGNORE_QUERY,
    val globalIgnoreBodyFields: Set<String> = emptySet(),
) {
    fun ruleFor(method: String, uri: DartUri): EndpointRule? = rules.firstOrNull { it.matches(method, uri.path) }

    /** `METHOD host/path?sortedQuery#bodyHash`. [body] must already be redacted. */
    fun keyFor(method: String, uri: DartUri, body: Any?): String {
        val m = method.uppercase()
        val rule = ruleFor(m, uri)
        val ignore = globalIgnoreQuery + (rule?.ignoreQuery ?: emptySet())
        val params = uri.queryParametersAll()
        val query = ArrayList<String>()
        for (name in params.keys.sorted()) {
            if (name in ignore) continue
            for (v in params[name]!!) query.add("$name=$v")
        }
        val sb = StringBuilder("$m ${uri.host}${uri.path.ifEmpty { "/" }}")
        if (query.isNotEmpty()) sb.append('?').append(query.joinToString("&"))
        val normalized = normalizeBody(body, rule)
        if (normalized !== NO_BODY) sb.append('#').append(hash(Json.canonical(normalized)))
        val key = sb.toString()
        return if (key.length <= MAX_KEY_LENGTH) key else key.substring(0, MAX_KEY_LENGTH - 20) + "~" + hash(key)
    }

    fun keyFor(method: String, url: String, body: Any?): String = keyFor(method, DartUri.parse(url), body)

    private fun normalizeBody(body: Any?, rule: EndpointRule?): Any? {
        if (body == null) return NO_BODY
        var value: Any? = body
        if (value is String) {
            if (value.isEmpty()) return NO_BODY
            value = try {
                Json.parse(value)
            } catch (_: JsonFormatException) {
                return value
            } catch (_: JsonDepthException) {
                return value
            }
        }
        val ignore = globalIgnoreBodyFields + (rule?.ignoreBodyFields ?: emptySet())
        val match = rule?.matchBodyFields
        if (match != null && value is Map<*, *>) {
            val m = value
            value = LinkedHashMap<String, Any?>().also { out ->
                for (k in match) if (m.containsKey(k)) out[k] = m[k]
            }
        }
        val out = if (ignore.isEmpty()) value else strip(value, ignore, "")
        // Like the Dart reference, a body that normalizes to null is not hashed.
        return out ?: NO_BODY
    }

    private fun strip(value: Any?, ignore: Set<String>, path: String): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            for ((k, v) in value) {
                val key = k.toString()
                val full = if (path.isEmpty()) key else "$path.$key"
                if (key !in ignore && full !in ignore) out[key] = strip(v, ignore, full)
            }
        }
        is List<*> -> value.map { strip(it, ignore, path) }
        else -> value
    }

    companion object {
        @JvmField
        val DEFAULT_IGNORE_QUERY: Set<String> = setOf("_", "ts", "timestamp", "nonce", "cachebust")
        const val MAX_KEY_LENGTH = 8000
        private val NO_BODY = Any()

        /** Two 32-bit FNV-1a passes with different offsets over UTF-8; 16 hex chars. */
        @JvmStatic
        fun hash(input: String): String {
            val bytes = input.toByteArray(Charsets.UTF_8)
            fun fnv(offset: Long): Long {
                var h = offset
                for (b in bytes) {
                    h = h xor (b.toLong() and 0xff)
                    h = (h * 0x01000193L) and 0xFFFFFFFFL
                }
                return h
            }
            return "%08x%08x".format(fnv(0x811C9DC5L), fnv(0x050C5D1FL))
        }
    }
}

/** One recorded HTTP exchange, as stored in an `http` event. */
class HttpFixture(val event: CapsuleEvent) {
    val data: Map<String, Any?> get() = event.data
    val key: String get() = data["key"] as String
    val occurrence: Int get() = J.int(data["occurrence"]) ?: 0
    val status: Int get() = J.int(data["status"]) ?: 0
    val durationMs: Long get() = J.long(data["durationMs"]) ?: 0
    val responseBody: Any? get() = data["responseBody"]
    val bodyEncoding: String get() = J.str(data["bodyEncoding"]) ?: "omitted"
    val statusMessage: String? get() = J.str(data["statusMessage"])

    @Suppress("UNCHECKED_CAST")
    val responseHeaders: Map<String, String>
        get() = (data["responseHeaders"] as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap()
    val error: Map<String, Any?>? get() = J.obj(data["error"])
    val hasBody: Boolean get() = bodyEncoding != "omitted" && bodyEncoding != "truncated"
}

class FixtureLookup(val fixture: HttpFixture, val reused: Boolean = false)

/** Serves recorded responses in occurrence order per request key (thread-safe). */
class FixtureStore(events: Iterable<CapsuleEvent>) {
    private val byKey = LinkedHashMap<String, MutableList<HttpFixture>>()
    private val served = HashMap<String, Int>()

    init {
        for (e in events) {
            if (e.type != EventTypes.HTTP) continue
            val f = HttpFixture(e)
            byKey.getOrPut(f.key) { ArrayList() }.add(f)
        }
        for (list in byKey.values) list.sortBy { it.occurrence }
    }

    val keyCount: Int get() = byKey.size

    @Synchronized
    fun take(key: String): FixtureLookup? {
        val list = byKey[key]
        if (list.isNullOrEmpty()) return null
        val n = served[key] ?: 0
        served[key] = n + 1
        return if (n < list.size) FixtureLookup(list[n]) else FixtureLookup(list.last(), reused = true)
    }

    /** Keys recorded but never requested during replay. */
    @Synchronized
    fun unusedKeys(): List<String> = byKey.keys.filter { it !in served }
}
