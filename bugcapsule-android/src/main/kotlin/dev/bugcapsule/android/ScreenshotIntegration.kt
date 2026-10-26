package dev.bugcapsule.android

import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Optional, diagnostic PNG frames. Call capture on the UI thread at application-chosen safe points.
 * The provider MUST mask sensitive content before returning bytes; return null to skip a screen.
 * No timers, crash-handler UI access, or replay inputs. Limits are per session, including dropped frames.
 */
class ScreenshotIntegration(
    private val enabled: Boolean = false,
    minIntervalMs: Long = 1000,
    maxFrames: Int = 20,
    maxFrameBytes: Int = 262144,
    maxTotalBytes: Int = 2097152,
    private val captureRedactedPng: () -> ByteArray?,
) : BugCapsuleIntegration {
    override val name = "screenshots"
    private val interval = minIntervalMs.coerceAtLeast(1000)
    private val frames = maxFrames.coerceIn(1, 20)
    private val frameBytes = maxFrameBytes.coerceIn(1, 262144)
    private val totalBytes = maxTotalBytes.coerceIn(1, 2097152)
    private var session: BugCapsuleSession? = null
    private var last: Long? = null
    private var count = 0
    private var bytes = 0
    private var busy = false

    @Synchronized override fun setUp(session: BugCapsuleSession) {
        this.session = session; last = null; count = 0; bytes = 0
    }

    @Synchronized fun capture(): Boolean {
        val s = session ?: return false
        if (!enabled || !s.isRecording || s.isReplaying || busy || count >= frames || bytes >= totalBytes ||
            Looper.myLooper() != Looper.getMainLooper()) return false
        val t = s.now()
        if (last?.let { t - it < interval } == true) return false
        last = t; busy = true
        return try {
            val png = captureRedactedPng() ?: return false
            if (!s.isRecording || s.isReplaying || session !== s || png.size !in 24..frameBytes || bytes + png.size > totalBytes) return false
            if (!png.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) return false
            fun intAt(i: Int): Int = (0..3).fold(0) { n, j -> (n shl 8) or (png[i + j].toInt() and 255) }
            val width = intAt(16); val height = intAt(20)
            if (width !in 1..2048 || height !in 1..2048) return false
            fun kindAt(i: Int) = String(png, i + 4, 4, Charsets.US_ASCII)
            if (intAt(8) != 13 || kindAt(8) != "IHDR") return false
            var pos = 8; var hasData = false; var ended = false
            val allowed = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "pHYs", "sBIT")
            while (pos + 12 <= png.size) {
                val size = intAt(pos); val kind = kindAt(pos)
                if (size < 0 || size > png.size - pos - 12 || kind !in allowed) return false
                if (kind == "IDAT") hasData = true
                pos += size + 12
                if (kind == "IEND") { if (size != 0) return false; ended = true; break }
            }
            if (!hasData || !ended || pos != png.size) return false
            s.record("screenshot", mapOf("schema" to 1, "mimeType" to "image/png", "privacy" to "reviewed",
                "capturedAtT" to t, "width" to width, "height" to height,
                "base64" to Base64.encodeToString(png, Base64.NO_WRAP)))
            count++; bytes += png.size
            true
        } catch (_: Exception) { false } finally { busy = false }
    }

    companion object {
        /** Encode only an application-owned bitmap whose sensitive regions have already been masked.
         * Does not read a Window, bypass FLAG_SECURE, or copy other apps. Caller owns bitmap lifetime.
         */
        @JvmStatic fun pngFromRedactedBitmap(bitmap: Bitmap): ByteArray? {
            if (bitmap.isRecycled || bitmap.width !in 1..2048 || bitmap.height !in 1..2048) return null
            return try {
                val out = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) || out.size() > 262144) null else out.toByteArray()
            } catch (_: Exception) { null }
        }
    }
}
