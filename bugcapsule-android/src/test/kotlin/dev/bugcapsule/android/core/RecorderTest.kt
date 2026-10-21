package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    private val launch = Checkpoint(CheckpointKind.LAUNCH, 0, 1000, 0)

    @Test
    fun launchCoversTheWindowUntilEviction() {
        val r = FlightRecorder(launch, maxEvents = 5)
        repeat(4) { r.add(it.toLong(), "log") }
        val w = r.window()
        assertEquals(CheckpointKind.LAUNCH, w.checkpoint.kind)
        assertEquals(4, w.events.size)
        assertFalse(w.hasGap)
    }

    @Test
    fun evictionMovesTheWindowToAPeriodicCheckpoint() {
        val r = FlightRecorder(launch, maxEvents = 5)
        repeat(3) { r.add(it.toLong(), "log") }
        r.addCheckpoint(Checkpoint(CheckpointKind.PERIODIC, 3, 1003, r.lastSeq))
        repeat(4) { r.add(10L + it, "log") } // evicts 2
        val w = r.window()
        assertEquals(CheckpointKind.PERIODIC, w.checkpoint.kind)
        assertEquals(listOf(4L, 5L, 6L, 7L), w.events.map { it.seq })
        assertEquals(2, w.droppedEvents)
        assertFalse(w.hasGap)
    }

    @Test
    fun timeWindowEvictsAndReportsGapWithoutCheckpoint() {
        val r = FlightRecorder(launch, windowMs = 100)
        r.add(0, "log")
        r.add(500, "log")
        val w = r.window()
        assertTrue(w.hasGap)
        assertEquals(1, w.droppedEvents)
    }

    @Test
    fun rewriteDropsAndCounts() {
        val r = FlightRecorder(launch)
        r.add(0, "http"); r.add(1, "log")
        r.rewrite(event = { if (it.type == "http") null else it })
        assertEquals(1, r.rewriteDropped)
        assertEquals(listOf("log"), r.window().events.map { it.type })
    }

    @Test
    fun idleCompression() {
        val events = listOf(
            CapsuleEvent(1, 100, "pointer"),
            CapsuleEvent(2, 3000, "http", mapOf("durationMs" to 1500L)),
            CapsuleEvent(3, 6000, "pointer"),
        )
        val idle = IdleCompressor(events)
        assertNull(idle.idleSince(0, 1000))
        // The http exchange runs 1500..3000, so idle starts at 3000.
        assertEquals(3000L, idle.idleSince(100, 6000))
        assertTrue(idle.maySkip(now = 3400, idleSince = 3000, busy = 0, quietMs = 400))
        assertFalse(idle.maySkip(now = 3400, idleSince = 3000, busy = 1, quietMs = 400))
        assertFalse(idle.maySkip(now = 3100, idleSince = 3000, busy = 0, quietMs = 400))
    }
}
