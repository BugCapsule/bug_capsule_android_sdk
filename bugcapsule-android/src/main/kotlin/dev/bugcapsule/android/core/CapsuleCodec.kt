package dev.bugcapsule.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CapsuleFormatException(message: String) : Exception(message)

/**
 * Reads and writes `.bugcapsule` files (gzip-compressed UTF-8 JSON). Reads
 * spec v1 and v2 and rejects newer versions; writes v2 only when the capsule
 * has interaction dependencies (capsule-v2 §1), otherwise v1.
 */
object CapsuleCodec {
    const val FORMAT = "bugcapsule.capsule"

    /** The newest spec version this reader implements. */
    const val SPEC_VERSION = 2

    /** Same bound as the Dart reader: what a hostile file can make us allocate. */
    const val MAX_DECOMPRESSED_BYTES = 200 * 1024 * 1024

    @JvmStatic
    fun toJson(capsule: Capsule): Map<String, Any?> = linkedMapOf(
        "format" to FORMAT,
        "specVersion" to capsule.specVersion,
        // v2 requires `dependencies`, empty for a crash capsule without interactions.
        "manifest" to capsule.manifest.toJson().let {
            if (capsule.specVersion >= 2 && "dependencies" !in it) it + ("dependencies" to emptyList<Any?>()) else it
        },
        "checkpoint" to capsule.checkpoint.toJson(),
        "events" to capsule.events.map { it.toJson() },
    )

    @JvmStatic
    fun encode(capsule: Capsule): ByteArray = gzip(Json.stringify(toJson(capsule)).toByteArray(Charsets.UTF_8))

    @JvmStatic
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @JvmStatic
    @JvmOverloads
    fun decode(bytes: ByteArray, maxDecompressedBytes: Int = MAX_DECOMPRESSED_BYTES): Capsule {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
            throw CapsuleFormatException("not a gzip file")
        }
        val raw = gunzip(bytes, maxDecompressedBytes)
        if (countJsonValues(raw, CapsuleLimits.MAX_JSON_VALUES) > CapsuleLimits.MAX_JSON_VALUES) {
            throw CapsuleFormatException("$ has more than ${CapsuleLimits.MAX_JSON_VALUES} JSON values")
        }
        val json = try {
            Json.parseUtf8(raw)
        } catch (e: JsonFormatException) {
            throw CapsuleFormatException("invalid JSON: ${e.message}")
        } catch (e: JsonDepthException) {
            throw CapsuleFormatException("malformed capsule: $ nests deeper than ${CapsuleLimits.MAX_DEPTH} levels")
        }
        @Suppress("UNCHECKED_CAST")
        if (json !is Map<*, *>) throw CapsuleFormatException("top level is not an object")
        @Suppress("UNCHECKED_CAST")
        return fromJson(json as Map<String, Any?>)
    }

    /** Inflates while counting: input larger than [max] is rejected before it is all in memory. */
    private fun gunzip(bytes: ByteArray, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(bytes), 8192).use { input ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw CapsuleFormatException("decompressed capsule exceeds $max bytes")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: CapsuleFormatException) {
            throw e
        } catch (e: IOException) {
            throw CapsuleFormatException("not a valid gzip file")
        }
        return out.toByteArray()
    }

    /** Validates the whole document before building the model, naming the offending JSON path. */
    @JvmStatic
    fun fromJson(json: Map<String, Any?>): Capsule {
        if (json["format"] != FORMAT) {
            throw CapsuleFormatException("\$.format must be \"$FORMAT\" (got \"${json["format"]}\")")
        }
        val version = json["specVersion"]
        if (version !is Long && version !is Int) throw CapsuleFormatException("\$.specVersion must be an integer")
        val v = (version as Number).toLong()
        if (v > SPEC_VERSION) {
            throw CapsuleFormatException(
                "\$.specVersion $v is newer than this reader (v$SPEC_VERSION); update the BugCapsule SDK",
            )
        }
        val problem = CapsuleValidator.validate(json)
        if (problem != null) throw CapsuleFormatException("malformed capsule: $problem")
        return try {
            Capsule(
                manifest = CapsuleManifest.fromJson(J.obj(json["manifest"])!!, v.toInt()),
                checkpoint = Checkpoint.fromJson(J.obj(json["checkpoint"])!!),
                events = J.list(json["events"])!!.map { CapsuleEvent.fromJson(J.obj(it)!!) },
            )
        } catch (e: CapsuleFormatException) {
            throw e
        } catch (e: Exception) {
            throw CapsuleFormatException("malformed capsule: $e")
        }
    }

    /** Values in UTF-8 JSON without parsing (`{`, `[` and `,` outside strings, plus one); stops past [limit]. */
    @JvmStatic
    fun countJsonValues(buf: ByteArray, limit: Int): Int {
        var n = 1
        var inString = false
        var i = 0
        while (i < buf.size) {
            val c = buf[i].toInt()
            if (inString) {
                if (c == 0x5c) i++ else if (c == 0x22) inString = false
            } else if (c == 0x22) {
                inString = true
            } else if (c == 0x7b || c == 0x5b || c == 0x2c) {
                if (++n > limit) return n
            }
            i++
        }
        return n
    }
}
