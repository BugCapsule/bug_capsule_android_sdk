package dev.bugcapsule.android.core

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Thrown for malformed JSON text. */
class JsonFormatException(message: String) : Exception(message)

/**
 * Minimal JSON reader/writer with the exact semantics of Dart's `dart:convert`
 * (the reference implementation), so that capsules, request keys and hashes
 * agree byte for byte across runtimes:
 *
 * * objects decode to [LinkedHashMap] (insertion order, last duplicate wins),
 *   arrays to [ArrayList], integers without fraction/exponent to [Long]
 *   (overflowing literals become [Double]), other numbers to [Double];
 * * strings are written with only `"`, `\` and control characters escaped
 *   (`\b \t \n \f \r`, otherwise `\u00xx`), non-ASCII text is written as is;
 * * doubles are written like Dart's `double.toString()` (shortest round-trip
 *   digits, `1.0` for integral values, `1e+21` exponent form).
 */
object Json {
    /** Nesting the parser accepts; capsule validation is stricter (100). */
    const val MAX_PARSE_DEPTH = 1000

    @JvmStatic
    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Strict UTF-8 decoding (malformed input is an error, like Dart's `utf8.decode`). */
    @JvmStatic
    fun parseUtf8(bytes: ByteArray): Any? = parse(decodeUtf8(bytes))

    @JvmStatic
    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw JsonFormatException("invalid UTF-8")
    }

    @JvmStatic
    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    /** Keys sorted by UTF-16 code units, no whitespace (request-key hashing). */
    @JvmStatic
    fun canonical(value: Any?): String = StringBuilder().also { writeCanonical(it, value) }.toString()

    private fun writeCanonical(out: StringBuilder, value: Any?) {
        when (value) {
            is Map<*, *> -> {
                val keys = value.keys.map { it.toString() }.sorted()
                out.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    writeString(out, k)
                    out.append(':')
                    writeCanonical(out, value[k] ?: value.entries.firstOrNull { it.key.toString() == k }?.value)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeCanonical(out, v)
                }
                out.append(']')
            }
            else -> write(out, value)
        }
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is String -> writeString(out, value)
            is CharSequence -> writeString(out, value.toString())
            is Long, is Int, is Short, is Byte -> out.append((value as Number).toLong())
            is Double -> out.append(formatDouble(value))
            is Float -> out.append(formatDouble(value.toDouble()))
            is Number -> out.append(formatDouble(value.toDouble()))
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, v)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            is Enum<*> -> writeString(out, value.name)
            else -> throw IllegalArgumentException("not JSON-encodable: ${value.javaClass.name}")
        }
    }

    private const val HEX = "0123456789abcdef"

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c.code < 0x20 -> when (c) {
                    '\b' -> out.append("\\b")
                    '\t' -> out.append("\\t")
                    '\n' -> out.append("\\n")
                    '\u000c' -> out.append("\\f")
                    '\r' -> out.append("\\r")
                    else -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
                }
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1]) -> {
                    out.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> {
                    // Lone surrogate: escaped, as Dart does.
                    out.append("\\u").append(HEX[(c.code shr 12) and 0xf]).append(HEX[(c.code shr 8) and 0xf])
                        .append(HEX[(c.code shr 4) and 0xf]).append(HEX[c.code and 0xf])
                }
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /** Dart's `double.toString()` (JavaScript number formatting plus `.0`). */
    @JvmStatic
    fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("JSON cannot encode $d")
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val negative = d < 0
        val bd = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        // value = 0.digits * 10^n
        val n = k - bd.scale()
        val sb = StringBuilder()
        if (negative) sb.append('-')
        when {
            n in k..21 -> {
                sb.append(digits)
                repeat(n - k) { sb.append('0') }
                sb.append(".0")
            }
            n in 1..21 -> sb.append(digits, 0, n).append('.').append(digits, n, k)
            n in -5..0 -> {
                sb.append("0.")
                repeat(-n) { sb.append('0') }
                sb.append(digits)
            }
            else -> {
                val e = n - 1
                sb.append(digits[0])
                if (k > 1) sb.append('.').append(digits, 1, k)
                sb.append('e').append(if (e >= 0) "+" else "-").append(Math.abs(e))
            }
        }
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = value(0)
            skipWs()
            if (i != s.length) fail("unexpected character")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonFormatException("$msg at offset $i")

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_PARSE_DEPTH) throw JsonDepthException()
            if (i >= s.length) fail("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected character")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("expected a string key")
                val k = string()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) fail("unexpected end of input")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (i >= s.length) fail("unexpected end of input")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    c.code < 0x20 -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                fail("bad number")
            }
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("bad number")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("bad number")
            }
            val text = s.substring(start, i)
            if (!isDouble) {
                text.toLongOrNull()?.let { return it }
            }
            return text.toDouble()
        }
    }
}

/** Nesting beyond [Json.MAX_PARSE_DEPTH]. */
class JsonDepthException : RuntimeException("JSON nests too deeply")

/** Typed reads of decoded JSON values (lenient, like the Dart reference casts). */
@Suppress("UNCHECKED_CAST")
object J {
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    fun list(v: Any?): List<Any?>? = v as? List<Any?>
    fun str(v: Any?): String? = v as? String
    fun long(v: Any?): Long? = v as? Long ?: (v as? Int)?.toLong()
    fun int(v: Any?): Int? = long(v)?.toInt()
    fun num(v: Any?): Double? = (v as? Number)?.toDouble()
    fun bool(v: Any?): Boolean? = v as? Boolean
}
