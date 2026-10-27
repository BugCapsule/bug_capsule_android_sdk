package dev.bugcapsule.android.internal

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.FlightRecorder
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The crash journal mirrors the flight recorder on disk (docs/crash-capsules.md §1). */
@RunWith(RobolectricTestRunner::class)
class CrashJournalTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val journals = ArrayList<CrashJournal>()

    @After
    fun tearDown() {
        journals.forEach { it.close(delete = false) }
    }

    private fun journal(maxBytes: Long = 10_000_000, flushMs: Long = CrashJournal.FLUSH_INTERVAL_MS) =
        CrashJournal(File(tmp.root, "journal/4242"), { maxBytes }, flushMs).also { journals.add(it) }

    private fun launch(t: Long = 0) = Checkpoint(CheckpointKind.LAUNCH, t, 1_760_000_000_000 + t, 0, mapOf("prefs" to mapOf("visits" to 3)))

    /** Records like the runtime: the recorder first, then the journal with the recorder's window start. */
    private class Recording(val recorder: FlightRecorder, val journal: CrashJournal) {
        var t = 0L

        fun event(type: String = "action", data: Map<String, Any?> = mapOf("id" to "tap")): CapsuleEvent {
            t += 10
            val e = recorder.add(t, type, data)
            journal.event(e, recorder.windowStartSeq)
            return e
        }

        fun checkpoint(): Checkpoint {
            t += 1
            val c = Checkpoint(CheckpointKind.PERIODIC, t, 1_760_000_000_000 + t, recorder.lastSeq, mapOf("state" to mapOf("cart" to recorder.lastSeq)))
            recorder.addCheckpoint(c)
            journal.checkpoint(c, recorder.windowStartSeq)
            return c
        }
    }

    private fun recording(maxEvents: Int = 20_000, maxBytes: Long = 10_000_000): Recording {
        val j = journal(maxBytes)
        val first = launch()
        j.header(mapOf("mode" to "qa"))
        j.checkpoint(first, 0)
        return Recording(FlightRecorder(first, maxEvents), j)
    }

    private fun files(dir: String) = (File(tmp.root, "journal/4242/$dir").list() ?: emptyArray()).sortedBy { it.substringBefore('.').toLong() }

    @Test
    fun writesTheHeaderCheckpointsAndOneEventPerLine() {
        val r = recording()
        r.journal.header(mapOf("mode" to "qa", "pid" to 4242))
        r.event()
        r.event("lifecycle", mapOf("state" to "paused"))
        assertTrue(r.journal.sync(5_000))
        val dir = File(tmp.root, "journal/4242")
        assertEquals(mapOf("mode" to "qa", "pid" to 4242L), Json.parse(File(dir, "header.json").readText()))
        assertEquals(listOf("0.json"), files("checkpoints"))
        assertEquals(launch().toJson().toString(), Checkpoint.fromJson(J.obj(Json.parse(File(dir, "checkpoints/0.json").readText()))!!).toJson().toString())
        val lines = File(dir, "events/1.jsonl").readLines()
        assertEquals(2, lines.size)
        assertEquals(CapsuleEvent(2, 20, "lifecycle", mapOf("state" to "paused")), CapsuleEvent.fromJson(J.obj(Json.parse(lines[1]))!!))
    }

    @Test
    fun batchesEventsUntilTheFlushIntervalOrAFlush() {
        val j = journal(flushMs = 60_000)
        j.checkpoint(launch(), 0)
        j.event(CapsuleEvent(1, 5, "action", mapOf("id" to "a")), 0)
        Thread.sleep(300)
        val file = File(tmp.root, "journal/4242/events/1.jsonl")
        assertFalse("not written before the interval", file.exists() && file.length() > 0)
        j.flush()
        val deadline = System.currentTimeMillis() + 5_000
        while (!(file.exists() && file.length() > 0) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, file.readLines().size)
    }

    @Test
    fun flushesWithinTheIntervalWithoutBeingAsked() {
        val j = journal(flushMs = 50)
        j.checkpoint(launch(), 0)
        j.event(CapsuleEvent(1, 5, "action", mapOf("id" to "a")), 0)
        val file = File(tmp.root, "journal/4242/events/1.jsonl")
        val deadline = System.currentTimeMillis() + 5_000
        while (!(file.exists() && file.length() > 0) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(file.length() > 0)
    }

    @Test
    fun aNewEventsFileStartsAtEachCheckpointAndOnlyTheNewestPeriodicIsKeptBesideLaunch() {
        val r = recording()
        repeat(3) { r.event() }
        r.checkpoint() // after 3
        repeat(2) { r.event() }
        r.checkpoint() // after 5
        r.event()
        assertTrue(r.journal.sync(5_000))
        assertEquals("launch still covers the window, plus the newest periodic", listOf("0.json", "5.json"), files("checkpoints"))
        assertEquals(listOf("1.jsonl", "4.jsonl", "6.jsonl"), files("events"))
        val contents = CrashCapsules.read(File(tmp.root, "journal/4242"))!!
        assertEquals((1L..6L).toList(), contents.events.map { it.seq })
    }

    @Test
    fun evictionFollowsTheRecorderWindowWholeFilesAtATime() {
        val r = recording(maxEvents = 5)
        repeat(4) { r.event() }
        val c4 = r.checkpoint()
        repeat(3) { r.event() } // seq 5..7: evicts 1 and 2; the window starts at the checkpoint after 4
        assertTrue(r.journal.sync(5_000))
        assertEquals(c4.afterSeq, r.recorder.windowStartSeq)
        assertEquals("launch dropped once the recorder evicted", listOf("4.json"), files("checkpoints"))
        assertEquals(listOf("5.jsonl"), files("events"))

        repeat(2) { r.event() }
        val c9 = r.checkpoint()
        repeat(4) { r.event() } // seq 10..13: the window moves to the checkpoint after 9
        assertTrue(r.journal.sync(5_000))
        assertEquals(c9.afterSeq, r.recorder.windowStartSeq)
        assertEquals(listOf("9.json"), files("checkpoints"))
        assertEquals(listOf("10.jsonl"), files("events"))
        val contents = CrashCapsules.read(File(tmp.root, "journal/4242"))!!
        assertEquals(r.recorder.window().events, contents.events.filter { it.seq > c9.afterSeq })
    }

    @Test
    fun eventsRecordedWhileACheckpointIsTakenStayInThePreviousFile() {
        val r = recording()
        repeat(2) { r.event() }
        // The runtime reads afterSeq, snapshots on the main thread, then adds: an event can land in between.
        val afterSeq = r.recorder.lastSeq
        r.event()
        val c = Checkpoint(CheckpointKind.PERIODIC, 99, 1_760_000_000_099, afterSeq, emptyMap())
        r.recorder.addCheckpoint(c)
        r.journal.checkpoint(c, r.recorder.windowStartSeq)
        r.event()
        assertTrue(r.journal.sync(5_000))
        assertEquals(listOf("1.jsonl", "4.jsonl"), files("events"))
        val contents = CrashCapsules.read(File(tmp.root, "journal/4242"))!!
        assertEquals(listOf(3L, 4L), contents.events.filter { it.seq > afterSeq }.map { it.seq })
    }

    @Test
    fun overTheSizeCapOnlyTheNewestCheckpointAndItsEventsRemain() {
        val big = "x".repeat(400)
        val r = recording(maxBytes = 3_000)
        repeat(5) { r.event(data = mapOf("id" to "a", "note" to big)) }
        val newest = r.checkpoint()
        assertTrue(r.journal.sync(5_000))
        assertEquals("under the cap: launch and its events stay", listOf("0.json", "5.json"), files("checkpoints"))
        repeat(2) { r.event(data = mapOf("id" to "b", "note" to big)) }
        assertTrue(r.journal.sync(5_000))
        assertEquals(listOf("${newest.afterSeq}.json"), files("checkpoints"))
        assertEquals(listOf("6.jsonl"), files("events"))
        assertTrue(r.journal.sizeOnDisk <= 3_000)
    }

    @Test
    fun whenEvenTheNewestCheckpointIsTooBigTheJournalWaitsForTheNext() {
        val big = "x".repeat(1_000)
        val r = recording(maxBytes = 2_500)
        repeat(4) { r.event(data = mapOf("id" to "a", "note" to big)) }
        assertTrue(r.journal.sync(5_000))
        assertTrue("nothing replayable is kept", files("checkpoints").isEmpty() && files("events").isEmpty())
        r.event(data = mapOf("id" to "dropped"))
        val c = r.checkpoint()
        r.event(data = mapOf("id" to "kept"))
        assertTrue(r.journal.sync(5_000))
        assertEquals(listOf("${c.afterSeq}.json"), files("checkpoints"))
        assertEquals(listOf("kept"), CrashCapsules.read(File(tmp.root, "journal/4242"))!!.events.map { it.data["id"] })
        assertTrue(r.journal.sizeOnDisk <= 2_500)
    }

    @Test
    fun resetReplacesTheJournalWithTheRecorderContent() {
        val r = recording()
        r.event(data = mapOf("id" to "a", "member" to "M-1234"))
        r.checkpoint()
        r.event(data = mapOf("id" to "b", "member" to "M-5678"))
        assertTrue(r.journal.sync(5_000))
        // New redaction rules: the recorder rewrites its buffer, the journal follows.
        r.recorder.rewrite(event = { e -> e.copy(data = e.data + ("member" to "[REDACTED]")) })
        val (checkpoints, events) = r.recorder.retained()
        r.journal.reset(checkpoints, events, r.recorder.windowStartSeq, r.recorder.lastSeq)
        assertTrue(r.journal.sync(5_000))
        val text = File(tmp.root, "journal/4242").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(text.contains("M-1234") || text.contains("M-5678"))
        assertEquals(listOf("0.json", "1.json"), files("checkpoints"))
        assertEquals(listOf("1.jsonl", "2.jsonl"), files("events"))
        assertEquals(listOf(1L, 2L), CrashCapsules.read(File(tmp.root, "journal/4242"))!!.events.map { it.seq })
    }

    @Test
    fun closeDeletesTheJournalAndIgnoresLaterWrites() {
        val r = recording()
        r.event()
        assertTrue(r.journal.sync(5_000))
        r.journal.close(delete = true)
        r.event()
        assertFalse(File(tmp.root, "journal/4242").exists())
        assertFalse(r.journal.sync(100))
    }

    @Test
    fun aJournalWithoutCheckpointOrHeaderIsNotReadable() {
        val dir = File(tmp.root, "journal/7").apply { mkdirs() }
        assertNull(CrashCapsules.read(dir))
        File(dir, "header.json").writeText("{}")
        assertNull("no checkpoint", CrashCapsules.read(dir))
        File(dir, "checkpoints").mkdirs()
        File(dir, "checkpoints/0.json").writeText(Json.stringify(launch().toJson()))
        File(dir, "events").mkdirs()
        File(dir, "events/1.jsonl").writeText(Json.stringify(CapsuleEvent(1, 5, "action", mapOf("id" to "a")).toJson()) + "\n{\"seq\":2,\"t\":")
        val contents = CrashCapsules.read(dir)
        assertNotNull(contents)
        assertEquals("the torn last line is skipped", listOf(1L), contents!!.events.map { it.seq })
    }

    @Test
    fun eventsARewriteDroppedAreNotAHoleInTheJournal() {
        val r = recording()
        r.event()
        r.event("http", mapOf("key" to "GET a/"))
        r.event()
        r.event("http", mapOf("key" to "GET b/"))
        assertTrue(r.journal.sync(5_000))
        // New redaction keys: the recorder drops buffered http events.
        r.recorder.rewrite(event = { e -> if (e.type == "http") null else e })
        val (checkpoints, events) = r.recorder.retained()
        r.journal.reset(checkpoints, events, r.recorder.windowStartSeq, r.recorder.lastSeq)
        r.event()
        assertTrue(r.journal.sync(5_000))
        val contents = CrashCapsules.read(File(tmp.root, "journal/4242"))!!
        assertEquals(listOf(1L, 3L, 5L), contents.events.map { it.seq })
        assertEquals(listOf(2L..2L, 4L..4L), contents.dropped)
        val header = linkedMapOf<String, Any?>(
            "mode" to "qa", "app" to mapOf("version" to "1", "build" to "1"), "sdk" to mapOf("name" to "bugcapsule-android", "version" to "1"),
            "stateSchemaVersion" to 1, "platform" to mapOf("os" to "android"),
            "session" to mapOf("id" to "s", "startedAt" to "2025-10-20T22:40:00.000Z", "seed" to 1), "anchorT" to 0,
        )
        val built = CrashCapsules.build(
            CrashCapsules.Contents(header, contents.checkpoints, contents.events, contents.dropped),
            CrashCapsules.Crash("c4a5c0de-0000-4000-8000-000000000009", dev.bugcapsule.android.core.FailureRecord.crash("SIGSEGV", "", 0, "signal"), "signal", null, mapOf("eventId" to "e")),
            5_000_000, { _, _, _ -> },
        )!!
        val capsule = dev.bugcapsule.android.core.CapsuleCodec.decode(built.bytes)
        assertEquals("launch still covers the window", CheckpointKind.LAUNCH, capsule.checkpoint.kind)
        val missing = capsule.manifest.eligibility.missing.map { it.dependency }
        assertFalse(missing.contains("events.window"))
        assertTrue(missing.contains("events.redaction"))
    }
}
