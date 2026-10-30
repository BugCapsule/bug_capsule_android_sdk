package dev.bugcapsule.patch

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Refusal from the delivery endpoint, or a response outside its bounds. */
class PatchHttpException(val reason: String, val status: Int? = null) : IOException("$reason${status?.let { ", HTTP $it" } ?: ""}")

/**
 * [PatchTransport] over the BugCapsule delivery API (`/v1/apps/:id/patches`).
 *
 * Sizes are bounded while reading, so an oversized response never becomes a
 * byte array for JNI. Redirects are refused: the bearer token must not follow
 * one. TLS is required except for loopback development servers.
 */
class HttpPatchTransport(
    private val baseUrl: String,
    /** `bcpd_…`: identifies one application in one environment. */
    private val deliveryToken: String,
    private val applicationId: String,
    private val platform: String,
    private val distributionProfile: String,
    private val baseBuildId: String,
    private val installationId: String,
    /** Absolute limit of a channel or receipt request, reading included. */
    private val timeoutMs: Int = 30_000,
    /** Absolute limit of an artifact download, reading included. */
    private val artifactTimeoutMs: Int = 120_000,
) : PatchTransport {
    init {
        val u = URL(baseUrl)
        require(u.protocol == "https" || u.host in LOOPBACK) { "patch delivery needs https" }
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): URL {
        val q = query.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return URL("${baseUrl.trimEnd('/')}/v1/apps/${URLEncoder.encode(applicationId, "UTF-8")}/patches$path${if (q.isEmpty()) "" else "?$q"}")
    }

    /**
     * Runs one request under an absolute deadline. A watchdog disconnects at
     * the deadline, so a trickled body can't outlive it, and nothing reads a
     * body it won't use: disconnecting closes the socket either way.
     */
    private fun <T> bounded(u: URL, method: String, limitMs: Int, run: (HttpURLConnection) -> T): T {
        val c = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = limitMs
            readTimeout = limitMs
            setRequestProperty("Authorization", "Bearer $deliveryToken")
        }
        val expired = AtomicBoolean(false)
        val watchdog = Timer("bcpatch-http", true)
        watchdog.schedule(
            object : TimerTask() {
                override fun run() {
                    expired.set(true)
                    c.disconnect()
                }
            },
            limitMs.toLong(),
        )
        try {
            return run(c)
        } catch (e: PatchHttpException) {
            throw e
        } catch (e: IOException) {
            throw if (expired.get()) PatchHttpException("timed_out") else e
        } finally {
            watchdog.cancel()
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection, max: Int): ByteArray {
        // contentLengthLong needs API 24; a length past Int also goes through here.
        val declared = c.getHeaderField("Content-Length")?.toLongOrNull()
        if (declared != null && declared > max) throw PatchHttpException("response_too_large", c.responseCode)
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        c.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw PatchHttpException("response_too_large", c.responseCode)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun refused(status: Int): PatchHttpException = PatchHttpException(
        when (status) {
            301, 302, 303, 307, 308 -> "redirect_refused"
            401, 403 -> "unauthorized"
            404 -> "not_found"
            410 -> "gone"
            429 -> "rate_limited"
            else -> "http_error"
        },
        status,
    )

    override fun fetchChannel(etag: String?): SignedChannel? {
        val u = url("/channel", mapOf("platform" to platform, "distributionProfile" to distributionProfile, "baseBuildId" to baseBuildId))
        return bounded(u, "GET", timeoutMs) { c ->
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            when (val status = c.responseCode) {
                204, 304 -> null
                200 -> {
                    val j = JSONObject(String(read(c, MAX_CHANNEL_BYTES), Charsets.UTF_8))
                    val channel = j.getJSONObject("channel")
                    val keyring = j.optJSONObject("keyring")
                    SignedChannel(
                        decode(channel.getString("document")),
                        decode(channel.getString("signature")),
                        c.getHeaderField("ETag"),
                        keyring?.let { decode(it.getString("document")) },
                        keyring?.let { decode(it.getString("signature")) },
                    )
                }
                else -> throw refused(status)
            }
        }
    }

    private fun decode(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        throw PatchHttpException("malformed_response")
    }

    override fun fetchArtifact(digest: String): ByteArray {
        if (!DIGEST.matches(digest)) throw PatchHttpException("bad_digest")
        return bounded(url("/artifacts/$digest"), "GET", artifactTimeoutMs) { c ->
            val status = c.responseCode
            if (status != 200) throw refused(status)
            read(c, MAX_ARTIFACT_BYTES)
        }
    }

    override fun postReceipts(batch: JSONObject) {
        val status = batch.getJSONObject("status")
        val pending = status.optString("pending").takeIf { it.startsWith("sha256:") }
        val receipts = JSONArray()
        val all = batch.getJSONArray("receipts")
        for (i in 0 until minOf(all.length(), MAX_RECEIPTS)) {
            val r = all.getJSONObject(i)
            if (r.getString("event") !in RECEIPT_EVENTS) continue
            receipts.put(JSONObject().put("seq", r.getLong("seq")).put("event", r.getString("event")).put("digest", r.opt("digest")).put("reason", r.opt("reason")).put("at", r.getLong("at")))
        }
        val body = JSONObject()
            .put("platform", platform)
            .put("baseBuildId", baseBuildId)
            .put("installationId", installationId)
            .put("activeDigest", status.opt("activeDigest"))
            .put("stagedDigest", pending ?: JSONObject.NULL)
            .put("channelRevision", status.opt("channelRevision"))
            .put("receipts", receipts)
            .toString()
            .toByteArray(Charsets.UTF_8)
        bounded(url("/receipts"), "POST", timeoutMs) { c ->
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code != 202) throw refused(code)
        }
    }

    companion object {
        const val MAX_ARTIFACT_BYTES = 10 shl 20

        /** Two 16 KiB documents with signatures, in base64, inside JSON. */
        const val MAX_CHANNEL_BYTES = 64 shl 10
        const val MAX_RECEIPTS = 100
        private val DIGEST = Regex("^sha256:[0-9a-f]{64}$")
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

        /** An event outside this list stays local: one unknown name would refuse the whole batch. */
        private val RECEIPT_EVENTS = setOf("staged", "activated", "healthy", "fault", "quarantined", "rolled_back", "rollback_requested", "invalidated")
    }
}
