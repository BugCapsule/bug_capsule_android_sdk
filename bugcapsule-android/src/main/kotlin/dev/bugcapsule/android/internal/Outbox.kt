package dev.bugcapsule.android.internal

import dev.bugcapsule.android.OutboxLimits
import java.io.File
import java.io.IOException
import java.util.Random

class OutboxEntry(val file: File, val capsuleId: String, val enqueuedAtMs: Long, val size: Long)

/**
 * Capsules waiting for upload, as `<enqueuedAtMs>_<capsuleId>.bugcapsule`
 * files (port of the Dart `UploadOutbox`):
 *
 * * **Bounds**: [OutboxLimits]. A capsule larger than `maxBytes` is never
 *   queued; otherwise the oldest are evicted first.
 * * **Writes** are atomic; temp files left by a crash are removed on start.
 *   Disk-full and other I/O errors drop the capsule.
 * * **Upload**: oldest first, one at a time. A retryable failure stops the
 *   pass and backs off from 30 s to 1 h (or `Retry-After`) with ±20 % jitter;
 *   permanent rejections and corrupt files are deleted.
 * * File operations share one lock; uploads run outside it.
 */
class UploadOutbox(
    val dir: File,
    val limits: OutboxLimits = OutboxLimits(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
    /** File extension of the entries: `bugcapsule` for capsules, `envelope` for monitoring envelopes (gzip too). */
    private val extension: String = "bugcapsule",
) {
    private val name = Regex("^(?:(\\d+)_)?([A-Za-z0-9-]{1,100})\\.${Regex.escape(extension)}$")
    private val noun = if (extension == "bugcapsule") "capsule" else extension

    private val lock = Any()
    private val flushLock = Any()

    @Volatile
    private var closed = false
    private var failures = 0

    /** Earliest time the next upload attempt may run after failures. */
    @Volatile
    var retryAtMs: Long? = null
        private set
    private val attempts = HashMap<String, Int>()

    init {
        dir.mkdirs()
        Files.removeTemporaryFiles(dir)
        synchronized(lock) { enforceLimits() }
    }

    fun entries(): List<OutboxEntry> = synchronized(lock) { listEntries() }

    private fun listEntries(): List<OutboxEntry> {
        val out = ArrayList<OutboxEntry>()
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile) continue
            val m = name.matchEntire(f.name) ?: continue
            val ms = m.groupValues[1].toLongOrNull()
            out.add(OutboxEntry(f, m.groupValues[2], ms ?: f.lastModified(), f.length()))
        }
        out.sortBy { it.enqueuedAtMs }
        return out
    }

    /** Queues a capsule; false when not queued (closed, too large, I/O error). Safe from any thread. */
    fun enqueue(capsuleId: String, bytes: ByteArray): Boolean {
        if (closed || bytes.size > limits.maxBytes) return false
        synchronized(lock) {
            val file = File(dir, "${nowMs()}_$capsuleId.$extension")
            try {
                Files.writeAtomically(file, bytes)
            } catch (e: IOException) {
                SdkLog.w("could not queue $noun $capsuleId: ${e.message}")
                return false
            }
            enforceLimits()
            return file.exists()
        }
    }

    private fun enforceLimits() {
        val all = listEntries()
        val cutoff = nowMs() - limits.maxAgeMs
        var count = all.size
        var bytes = all.sumOf { it.size }
        for (e in all) {
            val expired = e.enqueuedAtMs < cutoff
            if (!expired && count <= limits.maxCount && bytes <= limits.maxBytes) continue
            Files.deleteQuietly(e.file)
            count--
            bytes -= e.size
        }
    }

    /** Deletes every queued capsule (kill switch). Returns how many. */
    fun purge(): Int = synchronized(lock) {
        val all = listEntries()
        all.forEach { Files.deleteQuietly(it.file) }
        all.size
    }

    /**
     * Uploads queued capsules, oldest first, while [allowed] holds. Returns the
     * result for [onlyId] when given ("queued" when it was not attempted).
     */
    fun flush(upload: (ByteArray) -> UploadResult, allowed: () -> Boolean, onlyId: String? = null): UploadResult? =
        synchronized(flushLock) { flushOnce(upload, allowed, onlyId) }

    private fun flushOnce(upload: (ByteArray) -> UploadResult, allowed: () -> Boolean, onlyId: String?): UploadResult? {
        var mine: UploadResult? = null
        val retryAt = retryAtMs
        if (retryAt != null && nowMs() < retryAt) {
            return if (onlyId == null) null else UploadResult(UploadStatus.RETRY_LATER, message = "queued")
        }
        val queue = entries().sortedBy { attempts[it.capsuleId] ?: 0 } // stable: oldest first within a tier
        for (entry in queue) {
            if (closed || !allowed()) break
            val bytes = try {
                entry.file.readBytes()
            } catch (_: IOException) {
                continue
            }
            if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
                SdkLog.w("dropping corrupt queued $noun ${entry.capsuleId}")
                synchronized(lock) { Files.deleteQuietly(entry.file) }
                continue
            }
            val result = upload(bytes)
            if (entry.capsuleId == onlyId) mine = result
            if (result.status == UploadStatus.RETRY_LATER) {
                attempts[entry.capsuleId] = (attempts[entry.capsuleId] ?: 0) + 1
                failures++
                val base = 30_000L * (1L shl minOf(failures - 1, 7))
                var delay = minOf(base, 3_600_000L)
                val after = result.retryAfterMs
                if (after != null && after > delay) delay = after
                retryAtMs = nowMs() + Math.round(delay * (0.8 + random.nextDouble() * 0.4))
                break
            }
            failures = 0
            retryAtMs = null
            attempts.remove(entry.capsuleId)
            if (result.status == UploadStatus.REJECTED) {
                SdkLog.w("upload of ${entry.capsuleId} rejected: ${result.message}")
            }
            synchronized(lock) { Files.deleteQuietly(entry.file) }
        }
        if (onlyId != null && mine == null) mine = UploadResult(UploadStatus.RETRY_LATER, message = "queued")
        return mine
    }

    /** Stops uploading after the current file; later enqueues are refused. */
    fun close() {
        closed = true
    }
}
