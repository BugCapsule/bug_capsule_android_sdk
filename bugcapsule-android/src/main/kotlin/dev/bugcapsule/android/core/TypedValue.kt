package dev.bugcapsule.android.core

import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * Lossless JSON for the values native SDKs and sockets exchange (capsule-v2
 * §3.1), a port of `typed_value.dart`: integers vs doubles, byte and numeric
 * arrays, and maps with non-string keys survive a round trip, so replay hands
 * the app the same value it saw when capturing.
 *
 * Kotlin mapping: `Int`/`Long`/`Short`/`Byte` are integers (beyond ±(2^53−1)
 * an `int` tag), `Double`/`Float` doubles (integral, NaN, ±Infinity and -0.0
 * as a `double` tag spelled like Dart's `toString`), `ByteArray`/`ByteBuffer`
 * `bytes`, `IntArray` `i32`, `LongArray` `i64`, `FloatArray` `f32`,
 * `DoubleArray` `f64`, `List`/`Array` lists, maps with only string keys (none
 * `$t`) objects and other maps a `map` tag. Anything else is `unsupported`.
 * [decode] returns `Long` for integers, `Double` for doubles, `ByteArray`,
 * the primitive arrays, `ArrayList` and `LinkedHashMap`.
 */
object TypedValue {
    const val TAG = "\$t"
    private const val MAX_SAFE_INT = 9007199254740991L

    /** Encodes [value] as typed-value JSON; values it cannot represent become an `unsupported` tag. */
    @JvmStatic
    fun encode(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Long -> int(value)
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is BigInteger -> if (value.bitLength() < 64) int(value.toLong()) else tag("int", "v" to value.toString())
        is Double -> double(value)
        is Float -> double(value.toDouble())
        is ByteArray -> tag("bytes", "b64" to Base64.encode(value))
        is ByteBuffer -> {
            val copy = value.duplicate()
            val bytes = ByteArray(copy.remaining()).also { copy.get(it) }
            tag("bytes", "b64" to Base64.encode(bytes))
        }
        is IntArray -> tag("i32", "v" to value.map { it.toLong() })
        is LongArray -> tag("i64", "v" to value.map { it.toString() })
        is FloatArray -> tag("f32", "v" to value.map { floatItem(it.toDouble()) })
        is DoubleArray -> tag("f64", "v" to value.map { floatItem(it) })
        is List<*> -> value.map { encode(it) }
        is Array<*> -> value.map { encode(it) }
        is Map<*, *> -> {
            val plain = value.keys.all { it is String && it != TAG }
            if (plain) {
                LinkedHashMap<String, Any?>().also { out -> for ((k, v) in value) out[k as String] = encode(v) }
            } else {
                tag("map", "e" to value.entries.map { (k, v) -> listOf(encode(k), encode(v)) })
            }
        }
        else -> tag("unsupported", "type" to value.javaClass.name)
    }

    private fun tag(t: String, entry: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(TAG to t, entry)

    private fun int(v: Long): Any = if (v >= -MAX_SAFE_INT && v <= MAX_SAFE_INT) v else tag("int", "v" to v.toString())

    private fun double(v: Double): Any {
        val plain = v.isFinite() && v != Math.floor(v) && !(v == 0.0 && 1.0 / v < 0)
        return if (plain) v else tag("double", "v" to dartToString(v))
    }

    private fun floatItem(v: Double): Any = if (v.isFinite() && !(v == 0.0 && 1.0 / v < 0)) v else dartToString(v)

    /** Dart's `double.toString()`, including `NaN`, `Infinity`, `-Infinity` and `-0.0`. */
    @JvmStatic
    fun dartToString(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "Infinity"
        v == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> Json.formatDouble(v)
    }

    /** Decodes typed-value JSON back into Kotlin values (the inverse of [encode]); `unsupported` decodes to null. */
    @JvmStatic
    fun decode(json: Any?): Any? {
        if (json is List<*>) return json.mapTo(ArrayList()) { decode(it) }
        if (json !is Map<*, *>) return json
        val t = json[TAG] ?: return LinkedHashMap<String, Any?>().also { out ->
            for ((k, v) in json) out[k.toString()] = decode(v)
        }
        val v = json["v"]
        return when (t) {
            "int" -> (v as String).let { it.toLongOrNull() ?: BigInteger(it) }
            "double" -> parseDouble(v as String)
            "bytes" -> Base64.decode(json["b64"] as String)
            "i32" -> (v as List<*>).map { (it as Number).toInt() }.toIntArray()
            "i64" -> (v as List<*>).map { it.toString().toLong() }.toLongArray()
            "f32" -> (v as List<*>).map { float(it).toFloat() }.toFloatArray()
            "f64" -> (v as List<*>).map { float(it) }.toDoubleArray()
            "map" -> LinkedHashMap<Any?, Any?>().also { out ->
                for (e in json["e"] as List<*>) {
                    val pair = e as List<*>
                    out[decode(pair[0])] = decode(pair[1])
                }
            }
            else -> null
        }
    }

    private fun float(x: Any?): Double = if (x is Number) x.toDouble() else parseDouble(x.toString())

    private fun parseDouble(s: String): Double = when (s) {
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> s.toDouble()
    }

    /** Whether [json] holds no `unsupported` tag. */
    @JvmStatic
    fun isReplayable(json: Any?): Boolean {
        if (json is List<*>) return json.all { isReplayable(it) }
        if (json !is Map<*, *>) return true
        val t = json[TAG]
        if (t == "unsupported") return false
        if (t == "map") return (json["e"] as List<*>).all { e -> (e as List<*>).let { isReplayable(it[0]) && isReplayable(it[1]) } }
        if (t != null) return true
        return json.values.all { isReplayable(it) }
    }

    /**
     * Redacts typed-value JSON like [Redactor.redactJson] does plain JSON:
     * values under sensitive keys (string keys of plain or tagged maps) are
     * replaced, strings are scrubbed; byte and numeric arrays are left alone.
     * Returns the value and whether anything changed.
     */
    @JvmStatic
    fun redact(json: Any?, redactor: Redactor): Pair<Any?, Boolean> {
        if (json is String) {
            val out = redactor.redactJson(json)
            return out to (out != json)
        }
        if (json is List<*>) {
            var changed = false
            val out = ArrayList<Any?>(json.size)
            for (v in json) {
                val (r, c) = redact(v, redactor)
                out.add(r)
                changed = changed || c
            }
            return out to changed
        }
        if (json !is Map<*, *>) return json to false
        val t = json[TAG]
        if (t == null) {
            var changed = false
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in json) {
                val key = k.toString()
                if (v != null && redactor.isSensitiveKey(key)) {
                    out[key] = Redactor.PLACEHOLDER
                    changed = true
                } else {
                    val (r, c) = redact(v, redactor)
                    out[key] = r
                    changed = changed || c
                }
            }
            return out to changed
        }
        if (t == "map") {
            var changed = false
            val entries = ArrayList<Any?>()
            for (e in json["e"] as List<*>) {
                val pair = e as List<*>
                val key = pair[0]
                if (key is String && pair[1] != null && redactor.isSensitiveKey(key)) {
                    entries.add(listOf(key, Redactor.PLACEHOLDER))
                    changed = true
                } else {
                    val (k, kc) = redact(key, redactor)
                    val (v, vc) = redact(pair[1], redactor)
                    entries.add(listOf(k, v))
                    changed = changed || kc || vc
                }
            }
            return linkedMapOf(TAG to "map", "e" to entries) to changed
        }
        return json to false
    }

    /** Whether [json] contains binary data (`bytes` or numeric arrays). */
    @JvmStatic
    fun hasBinary(json: Any?): Boolean {
        if (json is List<*>) return json.any { hasBinary(it) }
        if (json !is Map<*, *>) return false
        val t = json[TAG]
        if (t == "bytes" || t == "i32" || t == "i64" || t == "f32" || t == "f64") return true
        if (t == "map") return (json["e"] as List<*>).any { e -> (e as List<*>).let { hasBinary(it[0]) || hasBinary(it[1]) } }
        return json.values.any { hasBinary(it) }
    }
}

/** RFC 4648 base64 with padding (Dart's `base64Encode`); [decode] accepts missing padding. */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    @JvmStatic
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[n shr 18 and 63]).append(ALPHABET[n shr 12 and 63])
                .append(ALPHABET[n shr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        val left = bytes.size - i
        if (left == 1) {
            val n = bytes[i].toInt() and 0xff shl 16
            out.append(ALPHABET[n shr 18 and 63]).append(ALPHABET[n shr 12 and 63]).append("==")
        } else if (left == 2) {
            val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
            out.append(ALPHABET[n shr 18 and 63]).append(ALPHABET[n shr 12 and 63]).append(ALPHABET[n shr 6 and 63]).append('=')
        }
        return out.toString()
    }

    /** Throws [IllegalArgumentException] for characters outside the alphabet. */
    @JvmStatic
    fun decode(text: String): ByteArray {
        val s = text.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            require(v >= 0) { "invalid base64 character '$c'" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(acc shr bits and 0xff)
            }
        }
        return out.toByteArray()
    }
}
