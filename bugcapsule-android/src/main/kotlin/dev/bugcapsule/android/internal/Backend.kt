package dev.bugcapsule.android.internal

import dev.bugcapsule.android.BugCapsuleBackendConfig
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class UploadStatus { UPLOADED, REJECTED, RETRY_LATER }

/** Result of one upload attempt. [retryAfterMs] comes from a `Retry-After` header. */
data class UploadResult(
    val status: UploadStatus,
    val serverId: String? = null,
    val message: String? = null,
    val retryAfterMs: Long? = null,
)

sealed class ConfigFetchResult {
    data class Fetched(val config: RemoteCaptureConfig) : ConfigFetchResult()
    data class Failed(val message: String, val status: Int? = null, val retryAfterMs: Long? = null) :
        ConfigFetchResult() {
        /** The ingest key was rejected (revoked or rotated). */
        val unauthorized: Boolean get() = status == 401 || status == 403
    }
}

/** How the SDK talks to the backend; tests substitute a fake. */
interface Transport {
    fun fetchConfig(): ConfigFetchResult
    fun upload(bytes: ByteArray): UploadResult
}

/**
 * `GET /v1/sdk/config` and `POST /v1/capsules` over [HttpURLConnection] (the
 * SDK's own traffic never goes through the app's OkHttp client, so it is not
 * captured or replayed).
 */
internal class BackendClient(
    private val config: BugCapsuleBackendConfig,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Transport {
    private fun url(path: String) = URL(config.url.trimEnd('/') + path)

    override fun fetchConfig(): ConfigFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url("/v1/sdk/config").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer ${config.ingestKey}")
                setRequestProperty("Accept", "application/json")
            }
            val status = conn.responseCode
            val body = readBody(conn)
            if (status != 200) {
                return ConfigFetchResult.Failed("HTTP $status", status, retryAfter(conn))
            }
            val json = J.obj(Json.parse(body)) ?: return ConfigFetchResult.Failed("config is not a JSON object")
            ConfigFetchResult.Fetched(RemoteCaptureConfig.fromJson(json, fetchedAtMs = nowMs()))
        } catch (e: Exception) {
            ConfigFetchResult.Failed(e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    override fun upload(bytes: ByteArray): UploadResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url("/v1/capsules").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                // Room for a slow uplink: 20 s plus 1 s per 16 KB.
                readTimeout = 20_000 + (bytes.size / (16 * 1024)) * 1000
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Authorization", "Bearer ${config.ingestKey}")
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val json = try {
                J.obj(Json.parse(readBody(conn)))
            } catch (_: Exception) {
                null
            }
            if (status == 200 || status == 201) {
                return UploadResult(UploadStatus.UPLOADED, serverId = J.str(json?.get("id")))
            }
            val code = J.str(json?.get("code")) ?: "HTTP $status"
            // 4xx other than 401/408/429 never succeed: drop instead of retrying.
            val retry = status >= 500 || status == 401 || status == 408 || status == 429
            UploadResult(if (retry) UploadStatus.RETRY_LATER else UploadStatus.REJECTED, message = code, retryAfterMs = retryAfter(conn))
        } catch (e: Exception) {
            UploadResult(UploadStatus.RETRY_LATER, message = e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream: InputStream? = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        }
        stream ?: return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 1024 * 1024) break
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun retryAfter(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1000)
}
