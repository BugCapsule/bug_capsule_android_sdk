package dev.bugcapsule.android.internal

import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleLimits
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.core.PatchIdentity
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EligibilityStatus
import dev.bugcapsule.android.core.FailureKind
import dev.bugcapsule.android.core.Json
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

/** Building a crash capsule from a journal on the next launch (docs/crash-capsules.md §2-3). */
@RunWith(RobolectricTestRunner::class)
class CrashCapsulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wall = 1_761_000_000_000L

    private fun header(anchorT: Long? = 100) = linkedMapOf<String, Any?>(
        "journal" to 1,
        "mode" to "production",
        "app" to mapOf("id" to "com.shop", "version" to "2.1.0", "build" to "210"),
        "source" to mapOf("gitSha" to "abc123", "dirty" to false),
        "sdk" to mapOf("name" to "bugcapsule-android", "version" to "0.9.0"),
        "stateSchemaVersion" to 1,
        "platform" to mapOf("os" to "android", "osVersion" to "14", "locale" to "en_US", "screen" to mapOf("width" to 411.0, "height" to 914.0, "pixelRatio" to 2.625)),
        "integrations" to listOf("okhttp"),
        "labels" to mapOf("team" to "checkout"),
        "runtime" to mapOf("name" to "android", "version" to "34", "language" to "kotlin"),
        "capabilities" to mapOf("crash.uncaught" to "partial"),
        "session" to linkedMapOf<String, Any?>("id" to "sess-1", "startedAt" to "2025-10-20T22:40:00.000Z", "seed" to 42).also {
            if (anchorT != null) it["wallClockAtAnchorMs"] = wall + anchorT
        },
        "anchorT" to anchorT,
        "pid" to 123,
        "release" to "com.shop@2.1.0+210",
        "environment" to "production",
    )

    private fun event(seq: Long, t: Long = 100 + seq * 10, data: Map<String, Any?> = mapOf("id" to "tap$seq")) = CapsuleEvent(seq, t, "action", data)

    private fun journalDir(
        checkpoints: List<Checkpoint>,
        events: List<CapsuleEvent>,
        header: Map<String, Any?> = header(),
        name: String = "123",
    ): File {
        val dir = File(tmp.root, "journal/$name")
        File(dir, "checkpoints").mkdirs()
        File(dir, "events").mkdirs()
        File(dir, "header.json").writeText(Json.stringify(header))
        for (c in checkpoints) File(dir, "checkpoints/${c.afterSeq}.json").writeText(Json.stringify(c.toJson()))
        // One file per checkpoint segment, as the writer does.
        val starts = checkpoints.map { it.afterSeq + 1 }
        for ((first, segment) in events.groupBy { e -> starts.filter { it <= e.seq }.maxOrNull() ?: e.seq }) {
            File(dir, "events/$first.jsonl").writeText(segment.joinToString("") { Json.stringify(it.toJson()) + "\n" })
        }
        return dir
    }

    private val launch = Checkpoint(CheckpointKind.LAUNCH, 0, wall, 0, mapOf("prefs" to mapOf("visits" to 3)))

    private fun periodic(afterSeq: Long) = Checkpoint(CheckpointKind.PERIODIC, 100 + afterSeq * 10 + 1, wall + 100 + afterSeq * 10 + 1, afterSeq, mapOf("state" to mapOf("cart" to afterSeq)))

    private val nativeEvent = mapOf(
        "exception" to mapOf(
            "values" to listOf(
                mapOf(
                    "type" to "SIGSEGV",
                    "value" to "Segmentation violation (code 1) at 0x0",
                    "mechanism" to mapOf("type" to "signal", "handled" to false, "data" to mapOf("signal" to 11, "code" to 1)),
                    "stacktrace" to mapOf(
                        "frames" to listOf(
                            mapOf("function" to "__libc_init", "package" to "/apex/libc.so", "inApp" to false),
                            mapOf("function" to "checkout_native", "package" to "/data/app/x/lib/arm64/libshop.so", "inApp" to true),
                            mapOf("function" to "memcpy", "package" to "/apex/libc.so", "inApp" to false),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun crash(atMs: Long? = wall + 1_150, mechanism: String = "signal") = CrashCapsules.Crash(
        capsuleId = "c4a5c0de-0000-4000-8000-000000000001",
        failure = CrashCapsules.failureOf(nativeEvent, mechanism),
        mechanism = mechanism,
        atMs = atMs,
        monitoring = mapOf("eventId" to "e1e1e1e1-0000-4000-8000-000000000002", "release" to "com.shop@2.1.0+210", "environment" to "production"),
    )

    private fun build(dir: File, crash: CrashCapsules.Crash = crash(), max: Long = 5_000_000) =
        CrashCapsules.build(CrashCapsules.read(dir)!!, crash, max, { e, _, _ -> e.missing("integration.okhttp", "checked") })

    @Test
    fun buildsAVersion2CrashCapsuleFromTheLaunchCheckpoint() {
        val dir = journalDir(listOf(launch, periodic(2)), (1L..5L).map { event(it) })
        val built = build(dir)!!
        val capsule = CapsuleCodec.decode(built.bytes)
        assertEquals(2, capsule.specVersion)
        assertEquals(2L, built.json["specVersion"])
        val m = capsule.manifest
        assertEquals(CheckpointKind.LAUNCH, capsule.checkpoint.kind)
        assertEquals("checkpoint shifted to the anchor", -100L, capsule.checkpoint.t)
        assertEquals((1L..5L).toList(), capsule.events.map { it.seq })
        assertEquals(10L, capsule.events.first().t)
        val failure = m.failure!!
        assertEquals(FailureKind.CRASH, failure.kind)
        assertEquals("crash:SIGSEGV", failure.signature)
        assertEquals("SIGSEGV", failure.type)
        assertEquals("Segmentation violation (code 1) at 0x0", failure.message)
        assertEquals(mapOf("mechanism" to "signal", "signal" to 11L, "inAppFrame" to "checkout_native"), failure.crash)
        assertEquals("#0 memcpy (/apex/libc.so)", failure.stack!!.lines().first())
        assertEquals("crash time relative to the anchor", 1_050L, failure.t)
        assertEquals(1_050L, m.session.durationMs)
        assertEquals(wall + 100, m.session.wallClockAtAnchorMs)
        assertEquals("crash minus the last journaled event", 1_150L - 150L, m.journalGapMs)
        assertEquals("crash_journal", m.labels["source"])
        assertEquals("signal", m.labels["crash.mechanism"])
        assertEquals("checkout", m.labels["team"])
        assertEquals("e1e1e1e1-0000-4000-8000-000000000002", m.monitoring!!["eventId"])
        assertEquals(EligibilityStatus.PARTIAL, m.eligibility.status)
        val missing = m.eligibility.missing.associate { it.dependency to it.reason }
        assertEquals(CrashCapsules.GAP_REASON, missing["crash.native"])
        assertTrue(missing.containsKey("integration.okhttp"))
        assertFalse(missing.containsKey("events.window"))
        assertEquals("abc123", m.gitSha)
        assertEquals(listOf("okhttp"), m.integrations)
        assertEquals("android", m.runtimeName)
    }

    @Test
    fun recoveredPatchedCrashKeepsIdentityAndMandatoryRuntimeWithoutInteractions() {
        val identity = PatchIdentity("android+42", "sha256:" + "a".repeat(64), 1, "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64))
        val h = header().also {
            it["patch"] = identity.toJson()
            it["dependencies"] = listOf(identity.dependency.toJson())
        }
        val journal = CrashCapsules.read(journalDir(listOf(launch), emptyList(), h))!!
        var eligibilityDependencies = emptyList<CapsuleDependency>()
        val built = CrashCapsules.build(journal, crash(), 5_000_000, { _, _, dependencies -> eligibilityDependencies = dependencies })!!
        val capsule = CapsuleCodec.decode(built.bytes)
        assertTrue(capsule.events.isEmpty())
        assertEquals(identity, capsule.manifest.patch)
        assertEquals(listOf(identity.dependency), capsule.manifest.dependencies)
        assertEquals(listOf(identity.dependency), eligibilityDependencies)
        assertTrue(capsule.manifest.dependencies.single().required)
        assertEquals(DependencyCoverage.COMPLETE, capsule.manifest.dependencies.single().coverage)
    }

    @Test
    fun ordinaryUnusedCompleteDependenciesAreStillDroppedWhilePartialOnesRemain() {
        val complete = CapsuleDependency("unused.native", "native", "shop.native", "1", 1, true, DependencyCoverage.COMPLETE)
        val partial = complete.copy(id = "partial.native", coverage = DependencyCoverage.PARTIAL)
        val optionalPatch = PatchIdentity("base", null, 1, "sha256:" + "b".repeat(64)).dependency.copy(required = false)
        val h = header().also { it["dependencies"] = listOf(complete, partial, optionalPatch).map { d -> d.toJson() } }
        val capsule = CapsuleCodec.decode(build(journalDir(listOf(launch), emptyList(), h))!!.bytes)
        assertNull(capsule.manifest.patch)
        assertEquals(listOf(partial), capsule.manifest.dependencies)
    }

    @Test
    fun aFullSetOfAppLabelsLeavesRoomForTheCrashLabels() {
        val full = header().also { h -> h["labels"] = (0 until CapsuleLimits.MAX_LABELS).associate { "k$it" to "v$it" } }
        val built = build(journalDir(listOf(launch), (1L..2L).map { event(it) }, header = full))
        assertTrue("the crash capsule is kept", built != null)
        val labels = CapsuleCodec.decode(built!!.bytes).manifest.labels
        assertEquals(CapsuleLimits.MAX_LABELS, labels.size)
        assertEquals("crash_journal", labels["source"])
        assertEquals("signal", labels["crash.mechanism"])
        assertEquals("v97", labels["k97"])
        assertFalse(labels.containsKey("k98"))
    }

    @Test
    fun theNewestPeriodicCheckpointIsUsedWhenEventsAfterLaunchAreGone() {
        // The recorder evicted 1..2: the journal deleted the launch segment.
        val dir = journalDir(listOf(launch, periodic(2)), (3L..6L).map { event(it) })
        val capsule = CapsuleCodec.decode(build(dir)!!.bytes)
        assertEquals(CheckpointKind.PERIODIC, capsule.checkpoint.kind)
        assertEquals(2L, capsule.checkpoint.afterSeq)
        assertEquals(mapOf("cart" to 2L), capsule.checkpoint.data["state"])
        assertEquals((3L..6L).toList(), capsule.events.map { it.seq })
        assertEquals(2, capsule.manifest.droppedEvents)
        assertTrue(capsule.manifest.eligibility.missing.none { it.dependency == "events.window" })
    }

    @Test
    fun aJournalWithAHoleAfterEveryCheckpointIsFlaggedAsAGap() {
        val dir = journalDir(listOf(periodic(2)), (5L..6L).map { event(it) })
        val capsule = CapsuleCodec.decode(build(dir)!!.bytes)
        assertTrue(capsule.manifest.eligibility.missing.any { it.dependency == "events.window" })
    }

    @Test
    fun anOversizeCapsuleFallsBackToTheNewestPeriodicCheckpointThenToNothing() {
        val random = java.util.Random(42)
        val big = (1..20_000).map { (33 + random.nextInt(90)).toChar() }.joinToString("")
        val events = (1L..6L).map { event(it, data = mapOf("id" to "tap$it", "note" to if (it <= 4) big + it else "small")) }
        val dir = journalDir(listOf(launch, periodic(4)), events)
        val full = build(dir)!!
        // Leave room for random capsule IDs to change the compressed size.
        val smaller = build(dir, max = full.bytes.size / 2L)!!
        val capsule = CapsuleCodec.decode(smaller.bytes)
        assertEquals(CheckpointKind.PERIODIC, capsule.checkpoint.kind)
        assertEquals(listOf(5L, 6L), capsule.events.map { it.seq })
        assertNull("still too big: no capsule, the event says nothing", build(dir, max = 200))
    }

    @Test
    fun beforeTheFirstFrameTheCrashIsTheAnchor() {
        val dir = journalDir(listOf(launch), (1L..2L).map { event(it) }, header(anchorT = null))
        val m = CapsuleCodec.decode(build(dir)!!.bytes).manifest
        assertEquals(0L, m.failure!!.t)
        assertEquals(wall + 1_150, m.session.wallClockAtAnchorMs)
    }

    @Test
    fun withoutACrashTimeTheLastEventStandsInAndThereIsNoGap() {
        val dir = journalDir(listOf(launch), (1L..3L).map { event(it) })
        val m = CapsuleCodec.decode(build(dir, crash(atMs = null))!!.bytes).manifest
        assertEquals(130L - 100L, m.failure!!.t)
        assertNull(m.journalGapMs)
    }

    @Test
    fun anrAndLowMemoryFailures() {
        val anr = CrashCapsules.failureOf(
            mapOf(
                "exception" to mapOf(
                    "values" to listOf(
                        mapOf(
                            "type" to "ApplicationNotResponding",
                            "value" to "Input dispatching timed out",
                            "mechanism" to mapOf("type" to "anr", "handled" to false),
                            "stacktrace" to mapOf(
                                "frames" to listOf(
                                    mapOf("function" to "loop", "module" to "android.os.Looper", "inApp" to false),
                                    mapOf("function" to "load", "module" to "com.shop.Cart", "inApp" to true),
                                    mapOf("function" to "sleep", "module" to "java.lang.Thread", "inApp" to false),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "anr",
        )
        assertEquals("crash:ApplicationNotResponding", anr.signature)
        assertEquals("com.shop.Cart.load", anr.crash!!["inAppFrame"])
        val oom = CrashCapsules.failureOf(emptyMap(), "oom")
        assertEquals("crash:OutOfMemory", oom.signature)
        assertEquals(mapOf("mechanism" to "oom"), oom.crash)
    }

    @Test
    fun findPrepareAndCleanup() {
        val root = File(tmp.root, "journal")
        val dead = journalDir(listOf(launch), emptyList(), name = "123")
        val older = journalDir(listOf(launch), emptyList(), name = "123-1000").apply { walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 60_000) } }
        val live = journalDir(listOf(launch), emptyList(), name = "456")
        val stale = journalDir(listOf(launch), emptyList(), name = "457").apply {
            walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - CrashCapsules.MAX_AGE_MS - 60_000) }
        }
        assertEquals("the newest journal of a pid", dead, CrashCapsules.find(root, 123))
        assertNull("missing journal", CrashCapsules.find(root, 999))
        assertNull("too old to build from", CrashCapsules.find(root, 457))

        // This process got pid 456 again: the earlier journal moves aside and stays findable.
        val mine = CrashCapsules.prepare(root, 456)
        assertFalse(mine.exists())
        assertNotNull(CrashCapsules.find(root, 456))
        mine.mkdirs()
        assertEquals("never this process's own live journal", root.listFiles()!!.single { it.name.startsWith("456-") }, CrashCapsules.find(root, 456, myPid = 456))

        CrashCapsules.cleanup(root, { it == 457 || it == 456 })
        assertFalse(dead.exists())
        assertFalse(older.exists())
        assertFalse("older than 7 days, even if that pid lives", stale.exists())
        assertTrue("a live process's journal stays", live.exists())
        assertEquals("moved-aside journals are never live", listOf("456"), root.list()!!.toList())
    }
}
