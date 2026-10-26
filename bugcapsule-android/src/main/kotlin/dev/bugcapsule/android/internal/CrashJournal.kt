package dev.bugcapsule.android.internal

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Json
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The crash journal of this process (docs/crash-capsules.md §1): what the
 * flight recorder holds, mirrored to `journal/<pid>/` so that a crash that
 * leaves no time to write a capsule (native signal, ANR or low-memory kill)
 * still gets one on the next launch ([CrashCapsules]).
 *
 * - `header.json`: the capsule manifest fields known while recording.
 * - `checkpoints/<afterSeq>.json`: the recorder's window checkpoint and the
 *   newest periodic one.
 * - `events/<firstSeq>.jsonl`: one event per line; a new file starts at each
 *   checkpoint, so eviction deletes whole files.
 *
 * Callers only enqueue (no I/O, no JSON on their thread). One writer thread
 * batches events and appends them with one `write` at least every
 * [flushIntervalMs], and at once on a checkpoint or [flush]. On disk at most
 * [maxBytes]: past it the journal keeps only the newest checkpoint, and if
 * that alone is too big it drops everything until the next checkpoint.
 *
 * Times are the recorder's (ms since the SDK started); `header.anchorT` says
 * where the session anchor is, and [CrashCapsules] shifts them.
 */
internal class CrashJournal(
    val dir: File,
    private val maxBytes: () -> Long,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    queueCapacity: Int = 20_000,
) {
    private sealed class Op
    private class Event(val event: CapsuleEvent, val keepFrom: Long) : Op()
    private class Point(val checkpoint: Checkpoint, val keepFrom: Long) : Op()
    private class Header(val json: Map<String, Any?>) : Op()
    private class Reset(val checkpoints: List<Checkpoint>, val events: List<CapsuleEvent>, val keepFrom: Long) : Op()
    private object Flush : Op()
    private class Barrier(val latch: CountDownLatch) : Op()

    private val queue = LinkedBlockingQueue<Op>(queueCapacity)

    /** An event did not fit in the queue: the journal has a hole until the next checkpoint. */
    private val lost = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /** Held around every file change, so [close] never races a write. */
    private val fileLock = Any()

    private val checkpointsDir = File(dir, "checkpoints")
    private val eventsDir = File(dir, "events")
    private val headerFile = File(dir, "header.json")

    // Writer thread state.
    private val pending = StringBuilder()
    private var pendingSinceNs = 0L
    private var currentFile: File? = null
    private var newFileAtNextEvent = true
    private var discarding = false
    private var keepFrom = 0L
    private var lastHeader: String? = null
    private var headerBytes = 0L

    /** afterSeq → size of each checkpoint file; firstSeq → size of each events file. */
    private val checkpointFiles = TreeMap<Long, Long>()
    private val eventFiles = TreeMap<Long, Long>()

    private val writer = Thread({ loop() }, "BugCapsule-Journal").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
    }

    init {
        writer.start()
    }

    /** Bytes on disk (writer's count; tests). */
    @Volatile
    var sizeOnDisk = 0L
        private set

    /** [keepFrom]: the recorder's `windowStartSeq` after adding [event]. */
    fun event(event: CapsuleEvent, keepFrom: Long) = enqueue(Event(event, keepFrom))

    fun checkpoint(checkpoint: Checkpoint, keepFrom: Long) = enqueue(Point(checkpoint, keepFrom))

    /** Rewrites `header.json` when [json] differs from the last one written. */
    fun header(json: Map<String, Any?>) = enqueue(Header(json))

    /** After the recorder rewrote its buffer (redaction rules, adapters turned off): the journal becomes its new content. */
    fun reset(checkpoints: List<Checkpoint>, events: List<CapsuleEvent>, keepFrom: Long) = enqueue(Reset(checkpoints, events, keepFrom))

    /** Writes buffered events now (a recorded failure, the app going to the background). */
    fun flush() = enqueue(Flush)

    /** Waits until everything enqueued so far is on disk; false on timeout or when closed. */
    fun sync(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        if (!enqueue(Barrier(latch))) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Stops the writer; [delete] removes the journal (recording stopped, or the crash capsule was written). */
    fun close(delete: Boolean) {
        closed = true
        writer.interrupt()
        synchronized(fileLock) {
            if (delete) dir.deleteRecursively()
        }
    }

    private fun enqueue(op: Op): Boolean {
        if (closed) return false
        if (queue.offer(op)) return true
        if (op is Event) lost.set(true)
        return false
    }

    // ------------------------------------------------------------ writer

    private fun loop() {
        val barriers = ArrayList<Barrier>()
        while (!closed) {
            try {
                val op = if (pending.isEmpty()) {
                    queue.take()
                } else {
                    val waitMs = flushIntervalMs - (System.nanoTime() - pendingSinceNs) / 1_000_000
                    if (waitMs > 0) queue.poll(waitMs, TimeUnit.MILLISECONDS) else queue.poll()
                }
                var flushNow = false
                var next = op
                while (next != null) {
                    if (next is Barrier) barriers.add(next) else if (handle(next)) flushNow = true
                    next = queue.poll()
                }
                if (pending.isNotEmpty() &&
                    (flushNow || barriers.isNotEmpty() || (System.nanoTime() - pendingSinceNs) / 1_000_000 >= flushIntervalMs)
                ) {
                    writePending()
                }
                barriers.forEach { it.latch.countDown() }
                barriers.clear()
            } catch (_: InterruptedException) {
                // close()
            } catch (e: Throwable) {
                SdkLog.once("crash journal write failed; dropping it until the next checkpoint: $e")
                pending.setLength(0)
                loseAll()
            }
        }
        barriers.forEach { it.latch.countDown() }
    }

    /** Returns true when the op asks for an immediate flush. */
    private fun handle(op: Op): Boolean {
        when (op) {
            is Event -> {
                if (lost.getAndSet(false)) {
                    pending.setLength(0)
                    loseAll()
                }
                if (discarding) return false
                if (newFileAtNextEvent) {
                    if (pending.isNotEmpty()) writePending()
                    currentFile = File(eventsDir, "${op.event.seq}.jsonl")
                    eventFiles[op.event.seq] = 0L
                    newFileAtNextEvent = false
                }
                if (pending.isEmpty()) pendingSinceNs = System.nanoTime()
                pending.append(Json.stringify(op.event.toJson())).append('\n')
                evict(op.keepFrom)
            }
            is Point -> {
                if (pending.isNotEmpty()) writePending()
                // A periodic checkpoint before any event adds nothing to the launch checkpoint.
                if (op.checkpoint.kind == CheckpointKind.PERIODIC && op.checkpoint.afterSeq == 0L && checkpointFiles.containsKey(0L)) {
                    return false
                }
                writeCheckpoint(op.checkpoint)
                discarding = false
                newFileAtNextEvent = true
                evict(op.keepFrom)
                enforceSize()
            }
            is Header -> {
                val text = Json.stringify(op.json)
                if (text != lastHeader) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    locked { Files.writeAtomically(headerFile, bytes) }
                    lastHeader = text
                    headerBytes = bytes.size.toLong()
                    updateSize()
                }
            }
            is Reset -> {
                pending.setLength(0)
                loseAll()
                val kept = keptCheckpoints(op.checkpoints.map { it.afterSeq }.toSortedSet(), op.keepFrom)
                val checkpoints = op.checkpoints.filter { it.afterSeq in kept }.distinctBy { it.afterSeq }
                for (c in checkpoints) writeCheckpoint(c)
                keepFrom = op.keepFrom
                if (checkpoints.isNotEmpty()) {
                    discarding = false
                    newFileAtNextEvent = true
                    val from = checkpoints.minOf { it.afterSeq }
                    val starts = checkpoints.map { it.afterSeq }
                    var previous = from
                    for (e in op.events.filter { it.seq > from }.sortedBy { it.seq }) {
                        // A new file at each checkpoint, as when written live.
                        if (previous != from && starts.any { it >= previous && it < e.seq }) {
                            if (pending.isNotEmpty()) writePending()
                            newFileAtNextEvent = true
                        }
                        handle(Event(e, op.keepFrom))
                        previous = e.seq
                    }
                    if (pending.isNotEmpty()) writePending()
                    newFileAtNextEvent = true
                    enforceSize()
                }
                return true
            }
            Flush -> return true
            is Barrier -> return true
        }
        return false
    }

    private inline fun locked(block: () -> Unit) {
        synchronized(fileLock) {
            if (closed) return
            block()
        }
    }

    private fun writeCheckpoint(c: Checkpoint) {
        val bytes = Json.stringify(c.toJson()).toByteArray(Charsets.UTF_8)
        locked { Files.writeAtomically(File(checkpointsDir, "${c.afterSeq}.json"), bytes) }
        checkpointFiles[c.afterSeq] = bytes.size.toLong()
        updateSize()
    }

    /** One append per flush. */
    private fun writePending() {
        val file = currentFile
        val bytes = pending.toString().toByteArray(Charsets.UTF_8)
        pending.setLength(0)
        if (file == null || discarding) return
        locked {
            eventsDir.mkdirs()
            FileOutputStream(file, true).use { it.write(bytes) }
        }
        val first = file.name.substringBefore('.').toLong()
        eventFiles[first] = (eventFiles[first] ?: 0L) + bytes.size
        updateSize()
        enforceSize()
    }

    /** The checkpoint covering [keepFrom] (the newest at or before it) and the newest of all. */
    private fun keptCheckpoints(all: Set<Long>, keepFrom: Long): Set<Long> {
        val sorted = all.sorted()
        val covering = sorted.lastOrNull { it <= keepFrom }
        return setOfNotNull(covering, sorted.lastOrNull())
    }

    /** Deletes what the recorder no longer needs once its window starts after [from]. */
    private fun evict(from: Long) {
        if (from == keepFrom && checkpointFiles.size <= 2) return
        keepFrom = from
        val kept = keptCheckpoints(checkpointFiles.keys, from)
        for (afterSeq in checkpointFiles.keys.toList()) if (afterSeq !in kept) deleteCheckpoint(afterSeq)
        kept.minOrNull()?.let { deleteEventsBefore(it) }
    }

    private fun enforceSize() {
        val max = maxBytes()
        if (sizeOnDisk <= max) return
        val newest = checkpointFiles.lastKeyOrNull()
        if (newest != null && checkpointFiles.size > 1) {
            for (afterSeq in checkpointFiles.keys.toList()) if (afterSeq != newest) deleteCheckpoint(afterSeq)
            deleteEventsBefore(newest)
        }
        if (sizeOnDisk > max) {
            SdkLog.once("crash journal is over $max bytes even from the newest checkpoint; dropped until the next checkpoint")
            pending.setLength(0)
            loseAll()
        }
    }

    /** Deletes the events files whose every event has `seq <= afterSeq` (whole files: the next file starts after them). */
    private fun deleteEventsBefore(afterSeq: Long) {
        val starts = eventFiles.keys.toList()
        for (i in 0 until starts.size - 1) {
            if (starts[i + 1] <= afterSeq + 1) deleteEvents(starts[i])
        }
    }

    private fun deleteCheckpoint(afterSeq: Long) {
        locked { Files.deleteQuietly(File(checkpointsDir, "$afterSeq.json")) }
        checkpointFiles.remove(afterSeq)
        updateSize()
    }

    private fun deleteEvents(firstSeq: Long) {
        locked { Files.deleteQuietly(File(eventsDir, "$firstSeq.jsonl")) }
        eventFiles.remove(firstSeq)
        if (currentFile?.name == "$firstSeq.jsonl") currentFile = null
        updateSize()
    }

    /** Drops every checkpoint and event: nothing replayable until the next checkpoint. */
    private fun loseAll() {
        for (k in checkpointFiles.keys.toList()) deleteCheckpoint(k)
        for (k in eventFiles.keys.toList()) deleteEvents(k)
        currentFile = null
        newFileAtNextEvent = true
        discarding = true
    }

    private fun updateSize() {
        sizeOnDisk = headerBytes + checkpointFiles.values.sum() + eventFiles.values.sum()
    }

    private fun TreeMap<Long, Long>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()

    companion object {
        const val FLUSH_INTERVAL_MS = 250L
    }
}
