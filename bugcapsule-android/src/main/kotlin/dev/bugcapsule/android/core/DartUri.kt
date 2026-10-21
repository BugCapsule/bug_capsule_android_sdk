package dev.bugcapsule.android.core

import java.io.ByteArrayOutputStream

/**
 * A URI with the parsing, normalization and query semantics of Dart's `Uri`
 * (the reference implementation), so request keys and redacted URLs agree
 * with every other BugCapsule runtime:
 * host lower-cased, characters outside the RFC 3986 tables percent-encoded,
 * `%xx` escapes upper-cased (a stray `%` becomes `%25`), default ports
 * dropped, query parameters decoded with `+` as a space.
 */
class DartUri private constructor(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int?,
    /** Encoded path. */
    val path: String,
    val hasAuthority: Boolean,
    /** Encoded query (without `?`), null when absent. */
    val query: String?,
    /** Encoded fragment (without `#`), null when absent. */
    val fragment: String?,
) {
    val hasQuery: Boolean get() = query != null
    val hasFragment: Boolean get() = fragment != null

    /** Decoded parameters in order of first appearance; values keep their order. */
    fun queryParametersAll(): LinkedHashMap<String, MutableList<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        val q = query ?: return result
        var start = 0
        var equals = -1
        fun pair(end: Int) {
            if (start == end) return
            val key: String
            val value: String
            if (equals < 0) {
                key = decodeQueryComponent(q, start, end)
                value = ""
            } else {
                key = decodeQueryComponent(q, start, equals)
                value = decodeQueryComponent(q, equals + 1, end)
            }
            result.getOrPut(key) { ArrayList() }.add(value)
        }
        for (i in q.indices) {
            when (q[i]) {
                '=' -> if (equals < 0) equals = i
                '&' -> {
                    pair(i)
                    start = i + 1
                    equals = -1
                }
            }
        }
        pair(q.length)
        return result
    }

    fun replace(
        userInfo: String? = null,
        queryParameters: Map<String, List<String>>? = null,
        fragment: String? = null,
    ): DartUri = DartUri(
        scheme = scheme,
        userInfo = userInfo ?: this.userInfo,
        host = host,
        port = port,
        path = path,
        hasAuthority = hasAuthority,
        query = if (queryParameters != null) buildQuery(queryParameters) else query,
        fragment = if (fragment != null) normalize(fragment, QUERY_CHARS) else this.fragment,
    )

    /** Same URI without query and fragment. */
    fun withoutQueryAndFragment() = DartUri(scheme, userInfo, host, port, path, hasAuthority, null, null)

    override fun toString(): String {
        val sb = StringBuilder()
        if (scheme.isNotEmpty()) sb.append(scheme).append(':')
        if (hasAuthority) {
            sb.append("//")
            if (userInfo.isNotEmpty()) sb.append(userInfo).append('@')
            sb.append(host)
            if (port != null) sb.append(':').append(port)
        }
        sb.append(path)
        if (query != null) sb.append('?').append(query)
        if (fragment != null) sb.append('#').append(fragment)
        return sb.toString()
    }

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
        private const val UNRESERVED = "-._~"
        private const val SUB_DELIMS = "!$&'()*+,;="
        private val PATH_CHARS = allowed("$UNRESERVED$SUB_DELIMS:@/")
        private val QUERY_CHARS = allowed("$UNRESERVED$SUB_DELIMS:@/?")
        private val USERINFO_CHARS = allowed("$UNRESERVED$SUB_DELIMS:")
        private val QUERY_COMPONENT_CHARS = allowed(UNRESERVED)

        private fun allowed(extra: String): BooleanArray {
            val t = BooleanArray(128)
            for (c in 'a'..'z') t[c.code] = true
            for (c in 'A'..'Z') t[c.code] = true
            for (c in '0'..'9') t[c.code] = true
            for (c in extra) t[c.code] = true
            return t
        }

        /** Null when [input] cannot be parsed (e.g. a non-numeric port). */
        @JvmStatic
        fun tryParse(input: String): DartUri? = try {
            parse(input)
        } catch (_: IllegalArgumentException) {
            null
        }

        @JvmStatic
        fun parse(input: String): DartUri {
            var rest = input
            var scheme = ""
            SCHEME.find(rest)?.let {
                scheme = it.groupValues[1].lowercase()
                rest = rest.substring(it.range.last + 1)
            }
            var userInfo = ""
            var host = ""
            var port: Int? = null
            var hasAuthority = false
            if (rest.startsWith("//")) {
                hasAuthority = true
                val end = rest.indexOfAny(charArrayOf('/', '?', '#'), 2).let { if (it < 0) rest.length else it }
                var authority = rest.substring(2, end)
                rest = rest.substring(end)
                val at = authority.lastIndexOf('@')
                if (at >= 0) {
                    userInfo = normalize(authority.substring(0, at), USERINFO_CHARS)
                    authority = authority.substring(at + 1)
                }
                var portText: String? = null
                if (authority.startsWith("[")) {
                    val close = authority.indexOf(']')
                    require(close > 0) { "bad IPv6 host" }
                    host = authority.substring(0, close + 1).lowercase()
                    val after = authority.substring(close + 1)
                    if (after.startsWith(":")) portText = after.substring(1)
                    else require(after.isEmpty()) { "bad host" }
                } else {
                    val colon = authority.lastIndexOf(':')
                    if (colon >= 0) {
                        portText = authority.substring(colon + 1)
                        authority = authority.substring(0, colon)
                    }
                    host = normalize(authority, USERINFO_CHARS).lowercase()
                }
                if (!portText.isNullOrEmpty()) {
                    require(portText.all { it in '0'..'9' }) { "bad port" }
                    port = portText.toInt()
                }
                if (port != null && port == defaultPort(scheme)) port = null
            }
            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = normalize(rest.substring(hash + 1), QUERY_CHARS)
                rest = rest.substring(0, hash)
            }
            var query: String? = null
            val q = rest.indexOf('?')
            if (q >= 0) {
                query = normalize(rest.substring(q + 1), QUERY_CHARS)
                rest = rest.substring(0, q)
            }
            val path = normalize(rest, PATH_CHARS)
            return DartUri(scheme, userInfo, host, port, path, hasAuthority, query, fragment)
        }

        private fun defaultPort(scheme: String) = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** Percent-encodes characters outside [table]; normalizes existing escapes. */
        private fun normalize(s: String, table: BooleanArray): String {
            var sb: StringBuilder? = null
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                        val esc = "%" + s[i + 1].uppercaseChar() + s[i + 2].uppercaseChar()
                        if (sb != null || esc != s.substring(i, i + 3)) {
                            if (sb == null) sb = StringBuilder(s.substring(0, i))
                            sb.append(esc)
                        }
                        i += 3
                        continue
                    }
                    if (sb == null) sb = StringBuilder(s.substring(0, i))
                    sb.append("%25")
                    i++
                    continue
                }
                if (c.code < 128 && table[c.code]) {
                    sb?.append(c)
                    i++
                    continue
                }
                if (sb == null) sb = StringBuilder(s.substring(0, i))
                val cp = s.codePointAt(i)
                val chars = Character.charCount(cp)
                encodeUtf8(sb, String(Character.toChars(cp)))
                i += chars
            }
            return sb?.toString() ?: s
        }

        private fun encodeUtf8(sb: StringBuilder, s: String) {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xff
                sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0xf])
            }
        }

        /** Dart's `Uri.encodeQueryComponent`: unreserved kept, space as `+`. */
        @JvmStatic
        fun encodeQueryComponent(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xff
                when {
                    v < 128 && QUERY_COMPONENT_CHARS[v] -> sb.append(v.toChar())
                    v == 0x20 -> sb.append('+')
                    else -> sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0xf])
                }
            }
            return sb.toString()
        }

        /** Dart's `Uri._makeQueryFromParameters`: an empty value is written without `=`. */
        private fun buildQuery(params: Map<String, List<String>>): String {
            val sb = StringBuilder()
            var sep = ""
            for ((k, values) in params) {
                for (v in values) {
                    sb.append(sep)
                    sep = "&"
                    sb.append(encodeQueryComponent(k))
                    if (v.isNotEmpty()) sb.append('=').append(encodeQueryComponent(v))
                }
            }
            return sb.toString()
        }

        /** Decodes `+` and `%xx` (UTF-8); throws [IllegalArgumentException] on invalid UTF-8. */
        @JvmStatic
        fun decodeQueryComponent(s: String, start: Int = 0, end: Int = s.length): String {
            var simple = true
            for (i in start until end) {
                val c = s[i]
                if (c == '+' || c == '%') {
                    simple = false
                    break
                }
            }
            if (simple) return s.substring(start, end)
            val bytes = ByteArrayOutputStream()
            var i = start
            while (i < end) {
                val c = s[i]
                when {
                    c == '+' -> {
                        bytes.write(0x20)
                        i++
                    }
                    c == '%' && i + 2 < end && isHex(s[i + 1]) && isHex(s[i + 2]) -> {
                        bytes.write(s.substring(i + 1, i + 3).toInt(16))
                        i += 3
                    }
                    else -> {
                        val cp = s.codePointAt(i)
                        val str = String(Character.toChars(cp))
                        bytes.write(str.toByteArray(Charsets.UTF_8))
                        i += Character.charCount(cp)
                    }
                }
            }
            return try {
                Json.decodeUtf8(bytes.toByteArray())
            } catch (e: JsonFormatException) {
                throw IllegalArgumentException("invalid percent encoding")
            }
        }
    }
}
