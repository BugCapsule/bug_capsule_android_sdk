package dev.bugcapsule.gradle

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

/** A non-2xx answer from the backend, carrying its problem `code`/`message` when present. */
class UploadException(
    val status: Int,
    val code: String?,
    val serverMessage: String?,
) : IOException(
    buildString {
        append("BugCapsule upload failed: HTTP ").append(status)
        if (code != null) append(" ").append(code)
        if (!serverMessage.isNullOrBlank()) append(": ").append(serverMessage)
    },
)

/**
 * Uploads debug files to `POST <baseUrl>/v1/debug-files?kind=<kind>&debugId=<id>`,
 * gzip-compressed, authenticated with a project secret key. Plain JDK, no Gradle
 * or AGP types, so it can be tested on its own. The secret key never appears in
 * exceptions or logs.
 */
class MappingUploader(
    private val baseUrl: String,
    private val secretKey: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
) {
    /** Status code of a successful upload. */
    data class Result(val status: Int)

    /**
     * Uploads [mapping] as the ProGuard/R8 mapping of [debugId].
     *
     * The request is not sent in streaming mode: the JDK discards the response body
     * of a 401 received in streaming mode, and that body carries the problem `code`.
     * The JDK therefore buffers the gzipped body (typically ~10-15% of the mapping).
     *
     * @throws UploadException on a non-2xx answer.
     * @throws IOException on network failures.
     */
    fun uploadProguardMapping(mapping: File, debugId: String): Result = upload("proguard", debugId, mapping)

    /** [params]: extra query parameters (`arch`, `name`, ...). */
    fun upload(kind: String, debugId: String, file: File, params: Map<String, String> = emptyMap()): Result {
        require(secretKey.isNotBlank()) { "BugCapsule secret key is empty" }
        val connection = URI(endpoint(kind, debugId, params)).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $secretKey")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.setRequestProperty("Accept", "application/json")
            file.inputStream().use { input ->
                GZIPOutputStream(connection.outputStream, BUFFER_SIZE).use { input.copyTo(it, BUFFER_SIZE) }
            }

            val status = connection.responseCode
            if (status in 200..299) {
                connection.inputStream.use { it.readBytes() }
                return Result(status)
            }
            val body = (connection.errorStream ?: safeInputStream(connection))?.use { readLimited(it) }.orEmpty()
            val code = jsonString(body, "code")
            val message = jsonString(body, "message")
                ?: body.trim().takeIf { it.isNotEmpty() && code == null }?.take(500)
                ?: connection.responseMessage
            throw UploadException(status, code, message)
        } finally {
            connection.disconnect()
        }
    }

    internal fun endpoint(kind: String, debugId: String, params: Map<String, String> = emptyMap()): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "BugCapsule url must start with http:// or https:// (got '$base')"
        }
        val extra = params.entries.joinToString("") { (k, v) -> "&${encode(k)}=${encode(v)}" }
        return "$base/v1/debug-files?kind=${encode(kind)}&debugId=${encode(debugId)}$extra"
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun safeInputStream(connection: HttpURLConnection): InputStream? =
            try {
                connection.inputStream
            } catch (_: IOException) {
                null
            }

        fun readLimited(input: InputStream, limit: Int = 64 * 1024): String {
            val bytes = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val read = input.read(bytes, total, limit - total)
                if (read < 0) break
                total += read
            }
            return String(bytes, 0, total, Charsets.UTF_8)
        }

        /** Extracts a top-level-ish string field from a small JSON problem body. */
        fun jsonString(json: String, field: String): String? {
            val match = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
                ?: return null
            return unescape(match.groupValues[1])
        }

        fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\' || i + 1 >= value.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val next = value[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'u' -> if (i + 5 < value.length) {
                        out.append(value.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            }
            return out.toString()
        }
    }
}
