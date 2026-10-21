package dev.bugcapsule.android.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * On-device redaction applied before anything is recorded (capsule-v1
 * *Redaction*). A precise port of the Dart reference: by name (JSON keys,
 * headers, query parameters, tracked-text ids), by shape (credentials in free
 * text) and by app rules ([extraKeys], [extraHeaders], [patterns]).
 *
 * Word boundaries are ASCII lookarounds and `\s` is JavaScript's whitespace
 * set, so results match the Dart and web implementations exactly.
 */
class Redactor @JvmOverloads constructor(
    extraKeys: Collection<String> = emptyList(),
    extraHeaders: Collection<String> = emptyList(),
    patterns: Collection<Pattern> = emptyList(),
) {
    /** Keys the app added (config and remote config), as given. */
    val extraKeys: List<String> = extraKeys.distinct()

    /** Headers the app added, as given. */
    val extraHeaders: List<String> = extraHeaders.distinct()
    private val normalizedExtraKeys: Set<String> = extraKeys.map { normalize(it) }.toSet()
    private val headers: Set<String> = DEFAULT_HEADERS + extraHeaders.map { it.lowercase() }
    val patterns: List<Pattern> = patterns.toList()

    /** A redactor with these rules plus [keys], [headers] and [patterns]. */
    @JvmOverloads
    fun extend(
        keys: Collection<String> = emptyList(),
        headers: Collection<String> = emptyList(),
        patterns: Collection<Pattern> = emptyList(),
    ) = Redactor(
        (extraKeys + keys).distinct(),
        (extraHeaders + headers).distinct(),
        this.patterns + patterns,
    )

    fun isSensitiveKey(key: String): Boolean {
        val n = normalize(key)
        if (n in EXACT_KEYS || n in normalizedExtraKeys) return true
        return SUBSTRING_KEYS.any { n.contains(it) }
    }

    /** Like [isSensitiveKey], plus names that are only secret inside URLs. */
    fun isSensitiveQueryKey(key: String): Boolean {
        if (isSensitiveKey(key)) return true
        val n = normalize(key)
        if (n in EXACT_QUERY_KEYS) return true
        return SUBSTRING_QUERY_KEYS.any { n.contains(it) }
    }

    fun isSensitiveHeader(name: String): Boolean = name.lowercase() in headers || isSensitiveKey(name)

    // ------------------------------------------------------------------ JSON

    /**
     * Deep copy of a JSON-like value: values under sensitive keys are
     * replaced, strings are [scrubText]-ed, and strings holding a JSON object
     * or array are redacted recursively (re-encoded only when that changed
     * them).
     */
    fun redactJson(value: Any?): Any? = redact(value, 0).first

    private fun redact(value: Any?, depth: Int): Pair<Any?, Boolean> {
        if (depth > MAX_DEPTH) return PLACEHOLDER to true
        return when (value) {
            is String -> redactString(value, depth)
            is Map<*, *> -> {
                var changed = false
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in value) {
                    val key = k.toString()
                    if (v != null && isSensitiveKey(key)) {
                        out[key] = PLACEHOLDER
                        changed = true
                    } else {
                        val (r, c) = redact(v, depth + 1)
                        out[key] = r
                        changed = changed || c
                    }
                }
                out to changed
            }
            is List<*> -> {
                var changed = false
                val out = ArrayList<Any?>(value.size)
                for (item in value) {
                    val (r, c) = redact(item, depth + 1)
                    out.add(r)
                    changed = changed || c
                }
                out to changed
            }
            else -> value to false
        }
    }

    private fun redactString(value: String, depth: Int): Pair<Any?, Boolean> {
        val trimmed = DartText.trimLeft(value)
        if (trimmed.length >= 2 && value.length <= MAX_EMBEDDED_JSON &&
            (trimmed.startsWith("{") || trimmed.startsWith("["))
        ) {
            val decoded = try {
                Json.parse(value)
            } catch (_: JsonFormatException) {
                null
            } catch (_: JsonDepthException) {
                null
            }
            if (decoded is Map<*, *> || decoded is List<*>) {
                val (r, changed) = redact(decoded, depth + 1)
                return if (changed) Json.stringify(r) to true else value to false
            }
        }
        val scrubbed = scrubText(value)
        return scrubbed to (scrubbed !== value)
    }

    // ------------------------------------------------------------ free text

    /** Removes credentials from free text; returns [text] itself when nothing matched. */
    fun scrubText(text: String): String {
        if (text.length < 5) return text
        var out = text
        out = replaceAll(out, AUTH_SCHEME) { m -> "${m.group(1)} $PLACEHOLDER" }
        out = JWT.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER))
        out = KNOWN_KEYS.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER))
        out = replaceAll(out, QUOTED_PAIR) { m ->
            if (!isSensitiveQueryKey(m.group(2)) || m.group(5) == PLACEHOLDER) {
                m.group(0)
            } else {
                "${m.group(1)}${m.group(2)}${m.group(1)}${m.group(3)}${m.group(4)}$PLACEHOLDER${m.group(4)}"
            }
        }
        out = replaceAll(out, EQUALS_PAIR) { m ->
            if (!isSensitiveQueryKey(m.group(1)) || m.group(2) == PLACEHOLDER) m.group(0) else "${m.group(1)}=$PLACEHOLDER"
        }
        out = replaceAll(out, COLON_PAIR) { m ->
            val value = m.group(3)
            // `https://...` is a URL scheme, not a key.
            if (value.startsWith("//") || !isSensitiveKey(m.group(1)) || value.contains(PLACEHOLDER)) {
                m.group(0)
            } else {
                "${m.group(1)}${m.group(2)}$PLACEHOLDER"
            }
        }
        out = replaceAll(out, CARD_DIGITS) { m -> if (luhn(m.group(0))) PLACEHOLDER else m.group(0) }
        for (p in patterns) {
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER))
        }
        return if (out == text) text else out
    }

    // ------------------------------------------------------ headers and URLs

    /** Lower-cases names, joins multi-value headers, redacts sensitive ones, scrubs the rest. */
    fun redactHeaders(headers: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            out[name.lowercase()] = if (isSensitiveHeader(name)) {
                PLACEHOLDER
            } else {
                scrubText(if (value is List<*>) value.joinToString(", ") else value.toString())
            }
        }
        return out
    }

    /**
     * Redacts user info, sensitive query parameters and sensitive `key=value`
     * pairs in the fragment. The path is kept. Malformed escapes drop query
     * and fragment rather than throw.
     */
    fun redactUri(uri: DartUri): DartUri = try {
        redactUriUnsafe(uri)
    } catch (_: RuntimeException) {
        uri.withoutQueryAndFragment()
    }

    private fun redactUriUnsafe(uri: DartUri): DartUri {
        var result = uri
        if (uri.userInfo.isNotEmpty()) result = result.replace(userInfo = "REDACTED")
        if (uri.hasQuery) {
            var changed = false
            val params = LinkedHashMap<String, List<String>>()
            for ((key, values) in uri.queryParametersAll()) {
                if (isSensitiveQueryKey(key)) {
                    changed = true
                    params[key] = listOf(PLACEHOLDER)
                } else {
                    val scrubbed = values.map { scrubText(it) }
                    for (i in values.indices) if (scrubbed[i] !== values[i]) changed = true
                    params[key] = scrubbed
                }
            }
            if (changed) result = result.replace(queryParameters = params)
        }
        val fragment = uri.fragment
        if (fragment != null && fragment.contains('=')) {
            val scrubbed = replaceAll(fragment, EQUALS_PAIR) { m ->
                if (isSensitiveQueryKey(m.group(1))) "${m.group(1)}=$PLACEHOLDER" else m.group(0)
            }
            if (scrubbed != fragment) result = result.replace(fragment = scrubbed)
        }
        return result
    }

    /** [redactUri] for a string; falls back to [scrubText] when it does not parse. */
    fun redactUrl(url: String): String {
        val uri = DartUri.tryParse(url) ?: return scrubText(url)
        val redacted = redactUri(uri)
        return if (redacted === uri) url else redacted.toString()
    }

    /** Tracked text: the whole value when the id looks sensitive or [sensitive], else scrubbed. */
    @JvmOverloads
    fun redactText(fieldId: String, value: String, sensitive: Boolean = false): String =
        if (sensitive || isSensitiveKey(fieldId)) PLACEHOLDER else scrubText(value)

    companion object {
        const val PLACEHOLDER = "[REDACTED]"

        private val DEFAULT_HEADERS = setOf(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key", "x-auth-token",
        )
        private val EXACT_KEYS = setOf("pin", "pan", "otp", "ssn", "cvv", "cvc", "passcode", "iban")
        private val SUBSTRING_KEYS = listOf(
            "password", "passwd", "token", "secret", "apikey", "authorization",
            "cookie", "cardnumber", "creditcard", "privatekey", "sessionid",
        )
        private val EXACT_QUERY_KEYS = setOf("code", "sig", "key", "auth", "sid", "session")
        private val SUBSTRING_QUERY_KEYS = listOf("signature", "credential")

        private const val MAX_DEPTH = 64
        private const val MAX_EMBEDDED_JSON = 64 * 1024

        private val NORMALIZE_CHARS = Regex("[_\\-${DartText.WS_CLASS}.]")
        private fun normalize(key: String) = key.lowercase().replace(NORMALIZE_CHARS, "")

        private const val WB = "(?<![A-Za-z0-9_])"
        private const val WE = "(?![A-Za-z0-9_])"
        private const val S = "[${DartText.WS_CLASS}]"

        private val AUTH_SCHEME =
            Pattern.compile("$WB(Bearer|Basic|Token|Digest)$S+[A-Za-z0-9\\-._~+/]{6,}=*", Pattern.CASE_INSENSITIVE)
        private val JWT = Pattern.compile("${WB}eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
        private val KNOWN_KEYS = Pattern.compile(
            "$WB(?:(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{8,}" +
                "|AKIA[0-9A-Z]{16}" +
                "|gh[pousr]_[A-Za-z0-9]{20,}" +
                "|xox[abprs]-[A-Za-z0-9-]{10,}" +
                "|AIza[0-9A-Za-z_-]{30,}" +
                "|bc[is]_[A-Za-z0-9_-]{20,})",
        )

        // JavaScript `.` excludes \n \r    ; spelled out so Java's
        // extra \u0085 exclusion does not differ.
        private val QUOTED_PAIR = Pattern.compile(
            "([\"'])([^\"'\\\\]{1,100})\\1($S*[:=]$S*)([\"'])((?:[^\"'\\\\]|\\\\[^\\n\\r\\u2028\\u2029])*)\\4",
        )
        private val EQUALS_PAIR = Pattern.compile(
            "(?<![A-Za-z0-9_\\-.\\[\\]])([A-Za-z0-9_\\-.\\[\\]]{1,100})=([^&${DartText.WS_CLASS}\"'#;,)<>]+)",
        )
        private val COLON_PAIR = Pattern.compile(
            "(?<![A-Za-z0-9_\\-.])([A-Za-z0-9_\\-.]{2,100})($S*:$S*)([^,${DartText.WS_CLASS}}{\\]\\[]+)",
        )

        /** Card-number shaped: 13-19 digits starting with a network prefix; Luhn-checked. */
        private val CARD_DIGITS = Pattern.compile("$WB[2-6][0-9](?:[ -]?[0-9]){11,17}$WE")

        private inline fun replaceAll(input: String, pattern: Pattern, fn: (Matcher) -> String): String {
            val m = pattern.matcher(input)
            if (!m.find()) return input
            val sb = StringBuilder()
            var last = 0
            do {
                sb.append(input, last, m.start())
                sb.append(fn(m))
                last = m.end()
            } while (m.find())
            sb.append(input, last, input.length)
            return sb.toString()
        }

        private fun luhn(candidate: String): Boolean {
            val digits = candidate.filter { it != ' ' && it != '-' }
            if (digits.length < 13 || digits.length > 19) return false
            var sum = 0
            var alt = false
            for (i in digits.length - 1 downTo 0) {
                var d = digits[i] - '0'
                if (alt) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
                alt = !alt
            }
            return sum % 10 == 0
        }
    }
}
