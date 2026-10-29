package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.BugCapsuleBackendConfig
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal sealed class EmergencyFetchResult {
    /** `200`: [body] is the raw response, [revisionHint] the unauthenticated `X-BugCapsule-Emergency-Revision` (receipts only). */
    class Fetched(val body: ByteArray, val etag: String?, val revisionHint: Long?) : EmergencyFetchResult()

    object NotModified : EmergencyFetchResult()

    /** [status] is null without an HTTP answer. [reason] comes from the problem body (`emergency_not_configured`). */
    class Failed(val message: String, val status: Int? = null, val retryAfterMs: Long? = null, val reason: String? = null) :
        EmergencyFetchResult() {
        /** 401/403, and 404 (the application id does not belong to the credential). */
        val credentialRejected: Boolean get() = status == 401 || status == 403 || status == 404
    }
}

/** How the emergency module talks to the backend; tests substitute a fake. */
internal interface EmergencyTransport {
    fun fetchPolicies(applicationId: String, environment: String, etag: String?, appVersion: String?): EmergencyFetchResult

    /** Returns the HTTP status; throws on I/O errors. */
    fun sendReceipt(applicationId: String, body: Map<String, Any?>): Int
}

/**
 * `GET /v1/apps/{app}/emergency/policies` and `POST …/receipts` over
 * [HttpURLConnection] (emergency-client-v1 §3, §7.3): 5 s to connect, 10 s
 * for the whole request, bodies up to 1 MiB.
 */
internal class HttpEmergencyTransport(
    private val backend: BugCapsuleBackendConfig,
    private val sdkVersion: String,
) : EmergencyTransport {
    private fun base(applicationId: String) =
        backend.url.trimEnd('/') + "/v1/apps/" + enc(applicationId) + "/emergency"

    override fun fetchPolicies(applicationId: String, environment: String, etag: String?, appVersion: String?): EmergencyFetchResult {
        val query = StringBuilder("?environment=").append(enc(environment))
            .append("&platform=").append(EmergencyContract.PLATFORM)
        appVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { query.append("&app_version=").append(enc(it.take(64))) }
        query.append("&sdk_version=").append(enc(sdkVersion.take(64)))
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000
        var conn: HttpURLConnection? = null
        var watchdog: java.util.TimerTask? = null
        return try {
            conn = (URL(base(applicationId) + "/policies" + query).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = TOTAL_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer ${backend.ingestKey}")
                setRequestProperty("Accept", "application/json")
                if (etag != null) setRequestProperty("If-None-Match", etag)
            }
            // Connect + headers + body share one budget: disconnecting aborts a stalled read.
            val c = conn
            watchdog = object : java.util.TimerTask() {
                override fun run() = c.disconnect()
            }.also { timer.schedule(it, TOTAL_TIMEOUT_MS.toLong()) }
            val status = conn.responseCode
            when (status) {
                200 -> {
                    val body = readBody(conn.inputStream, deadline)
                        ?: return EmergencyFetchResult.Failed("response larger than 1 MiB", status)
                    EmergencyFetchResult.Fetched(
                        body,
                        conn.getHeaderField("ETag"),
                        conn.getHeaderField("X-BugCapsule-Emergency-Revision")?.trim()?.toLongOrNull(),
                    )
                }
                304 -> EmergencyFetchResult.NotModified
                else -> {
                    val problem = try {
                        conn.errorStream?.let { readBody(it, deadline) }?.let { J.obj(Json.parse(String(it, Charsets.UTF_8))) }
                    } catch (_: Exception) {
                        null
                    }
                    val reason = J.str(problem?.get("reason"))?.take(100)
                    EmergencyFetchResult.Failed(
                        if (reason != null) "HTTP $status ($reason)" else "HTTP $status",
                        status,
                        retryAfter(conn),
                        reason,
                    )
                }
            }
        } catch (e: Exception) {
            EmergencyFetchResult.Failed(e.toString())
        } finally {
            watchdog?.cancel()
            conn?.disconnect()
        }
    }

    override fun sendReceipt(applicationId: String, body: Map<String, Any?>): Int {
        val bytes = Json.stringify(body).toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(base(applicationId) + "/receipts").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = TOTAL_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Authorization", "Bearer ${backend.ingestKey}")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(bytes) }
            return conn.responseCode
        } finally {
            conn?.disconnect()
        }
    }

    /** The body, or null when it is larger than [MAX_BODY_BYTES]. Throws when [deadline] passes. */
    private fun readBody(stream: InputStream, deadline: Long): ByteArray? = stream.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            if (System.nanoTime() > deadline) throw IOException("request took longer than ${TOTAL_TIMEOUT_MS / 1000} s")
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BODY_BYTES) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun retryAfter(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1000)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val TOTAL_TIMEOUT_MS = 10_000
        const val MAX_BODY_BYTES = 1024 * 1024

        private val timer by lazy { java.util.Timer("BugCapsule-Emergency-Timeout", true) }
    }
}
