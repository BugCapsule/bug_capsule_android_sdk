package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Files
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * What survives a restart (emergency-client-v1 §5, §7.3), under
 * `<storage root>/emergency/`:
 *
 * * `policy_cache.json`: the envelope exactly as received, its ETag, when it
 *   was received and the highest revision ever accepted, for one application
 *   and environment. Written atomically (temp file, then rename).
 * * `installation_id`: 16 random bytes in base64url, apart from the cache.
 */
internal class EmergencyStore(val dir: File) {
    val cacheFile = File(dir, "policy_cache.json")
    val installationFile = File(dir, "installation_id")

    class Cached(
        val application: String,
        val environment: String,
        /** The response body of the accepted envelope; null after it was discarded. */
        val envelope: String?,
        val etag: String?,
        val receivedAtMs: Long?,
        val highestRevision: Long,
    )

    /** Null when there is no cache or it is unreadable. */
    fun read(): Cached? {
        try {
            if (!cacheFile.isFile) return null
            val j = J.obj(Json.parse(cacheFile.readText(Charsets.UTF_8))) ?: return null
            if (J.long(j["version"]) != 1L) return null
            return Cached(
                application = J.str(j["application"]) ?: return null,
                environment = J.str(j["environment"]) ?: return null,
                envelope = J.str(j["envelope"]),
                etag = J.str(j["etag"]),
                receivedAtMs = J.long(j["receivedAt"]),
                highestRevision = J.long(j["highestRevision"]) ?: 0L,
            )
        } catch (_: Exception) {
            return null
        }
    }

    @Throws(IOException::class)
    fun write(c: Cached) {
        val json = linkedMapOf<String, Any?>(
            "version" to 1L,
            "application" to c.application,
            "environment" to c.environment,
            "envelope" to c.envelope,
            "etag" to c.etag,
            "receivedAt" to c.receivedAtMs,
            "highestRevision" to c.highestRevision,
        )
        Files.writeAtomically(cacheFile, Json.stringify(json).toByteArray(Charsets.UTF_8))
    }

    fun readInstallationId(): String? = try {
        installationFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { INSTALLATION_ID.matches(it) }
    } catch (_: Exception) {
        null
    }

    @Throws(IOException::class)
    fun writeInstallationId(id: String) {
        Files.writeAtomically(installationFile, id.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private val INSTALLATION_ID = Regex("^[A-Za-z0-9_-]{16,100}$")
        private val random by lazy { SecureRandom() }

        fun newInstallationId(): String = base64Url(ByteArray(16).also { random.nextBytes(it) })

        /** Unpadded base64url (RFC 4648 §5); `java.util.Base64` needs API 26. */
        fun base64Url(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xff
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
                val n = (b0 shl 16) or (b1 shl 8) or b2
                out.append(alphabet[(n shr 18) and 63]).append(alphabet[(n shr 12) and 63])
                if (i + 1 < bytes.size) out.append(alphabet[(n shr 6) and 63])
                if (i + 2 < bytes.size) out.append(alphabet[n and 63])
                i += 3
            }
            return out.toString()
        }
    }
}
