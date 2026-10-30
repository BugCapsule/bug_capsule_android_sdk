package dev.bugcapsule.patch

import org.json.JSONArray
import org.json.JSONObject

/** Signed channel metadata as delivered, kept as the exact received bytes. */
class SignedChannel(val document: ByteArray, val signature: ByteArray, val etag: String? = null)

/**
 * How an app reaches its delivery endpoint. TLS, tokens and retries live here;
 * the runtime treats everything that comes back as untrusted.
 */
interface PatchTransport {
    /** Null means nothing changed since [etag]. */
    fun fetchChannel(etag: String?): SignedChannel?
    fun fetchArtifact(digest: String): ByteArray
    fun postReceipts(batch: JSONObject)
}

enum class PatchUpdateState { UP_TO_DATE, RESTART_REQUIRED, NOT_IN_COHORT, QUARANTINED, FAILED }

data class PatchUpdateResult(val state: PatchUpdateState, val artifactDigest: String? = null, val reason: String? = null)

/**
 * Check, download, verify, stage. Blocking: call it off the main thread. Never
 * restarts the app and never changes the running generation. Failures are
 * returned, not thrown, and leave the next launch as it was.
 */
class PatchUpdater(private val patch: BugCapsulePatch, private val transport: PatchTransport) {
    private var etag: String? = null

    @Synchronized
    fun checkForUpdate(): PatchUpdateResult = try {
        val channel = transport.fetchChannel(etag)
        if (channel == null) {
            PatchUpdateResult(PatchUpdateState.UP_TO_DATE)
        } else {
            val result = act(patch.applyChannel(channel.document, channel.signature))
            // Only after the download and staging worked: a 304 must not end retries.
            etag = channel.etag
            result
        }
    } catch (e: PatchException) {
        PatchUpdateResult(PatchUpdateState.FAILED, reason = e.code)
    } catch (e: java.io.IOException) {
        PatchUpdateResult(PatchUpdateState.FAILED, reason = "transport: ${e.message}")
    }

    private fun act(d: JSONObject): PatchUpdateResult {
        val digest = d.optString("artifactDigest").ifEmpty { null }
        return when (d.getString("action")) {
            "download" -> {
                val staged = patch.stage(transport.fetchArtifact(digest!!))
                PatchUpdateResult(PatchUpdateState.RESTART_REQUIRED, staged.getString("artifactDigest"))
            }
            "restart_required" -> PatchUpdateResult(PatchUpdateState.RESTART_REQUIRED, d.getString("target").takeUnless { it == "base" })
            else -> when (d.optString("reason")) {
                "not_in_cohort" -> PatchUpdateResult(PatchUpdateState.NOT_IN_COHORT)
                "quarantined" -> PatchUpdateResult(PatchUpdateState.QUARANTINED, digest)
                else -> PatchUpdateResult(PatchUpdateState.UP_TO_DATE)
            }
        }
    }

    /** Uploads local receipts, acknowledging them only after the server took them. */
    @Synchronized
    fun flushReceipts(): Int {
        val batch = patch.receipts()
        val items: JSONArray = batch.getJSONArray("receipts")
        if (items.length() == 0) return 0
        transport.postReceipts(batch.put("status", patch.status()))
        patch.ackReceipts(items.getJSONObject(items.length() - 1).getLong("seq"))
        return items.length()
    }
}
