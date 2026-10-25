package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.internal.ActionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEngineTest {
    private val actions = ActionRegistry()
    private val reports = ArrayList<ReplayReport>()

    private fun engine(
        events: List<CapsuleEvent>,
        settings: ReplaySettings = ReplaySettings(settleTimeoutMs = 300, attemptId = "att-1"),
        failure: dev.bugcapsule.android.core.FailureRecord? = TestCapsules.failure(),
        checkpoint: Checkpoint = Checkpoint(CheckpointKind.LAUNCH, -100, 1_760_000_000_000, 0),
        gitSha: String? = null,
        build: String? = null,
    ) = ReplayEngine(TestCapsules.capsule(events, failure, checkpoint, gitSha), settings, actions, build, { reports.add(it) })

    private fun pointer(seq: Long, t: Long, phase: String, x: Double = 10.0, y: Double = 20.0) =
        CapsuleEvent(seq, t, "pointer", mapOf("phase" to phase, "pointer" to 0L, "x" to x, "y" to y, "kind" to "touch"))

    @Test
    fun pointerStrategyWhenScreenMatchesAndDpBecomesPixels() {
        val e = engine(listOf(pointer(1, 10, "down"), pointer(2, 20, "up"), CapsuleEvent(3, 25, "action", mapOf("id" to "a"))))
        val driver = FakeDriver()
        val r = e.run(driver)
        assertEquals(ReplayStrategy.POINTER, r.strategy)
        assertEquals(listOf("down:0:20,40", "up:0:20,40"), driver.pointers)
        assertEquals(2, r.eventsExpected)
        assertEquals(2, r.eventsDispatched)
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertEquals("att-1", r.attemptId)
        assertEquals("android", r.runtime)
        assertEquals(1, reports.size)
    }

    @Test
    fun restoresRecordedKeyboardBeforeGestureButNotBeforeRelease() {
        val calls = mutableListOf<String>()
        val delegate = FakeDriver()
        val driver = object : ReplayDriver by delegate {
            override fun preparePointer(imeVisible: Boolean) { calls.add("ime:$imeVisible") }
            override fun dispatchPointer(phase: String, pointer: Int, xPx: Float, yPx: Float, kind: String, buttons: Int) {
                calls.add(phase)
            }
        }
        fun input(seq: Long, phase: String, visible: Boolean) = pointer(seq, seq, phase).let {
            it.copy(data = it.data + ("imeVisible" to visible))
        }
        val report = engine(listOf(input(1, "down", true), input(2, "up", false), input(3, "down", false), input(4, "up", false))).run(driver)
        assertEquals(listOf("ime:true", "down", "up", "ime:false", "down", "up"), calls)
        assertEquals(4, report.eventsDispatched)
    }

    @Test
    fun olderCapsulesDoNotGuessKeyboardVisibility() {
        val driver = object : ReplayDriver by FakeDriver() {
            override fun preparePointer(imeVisible: Boolean) { error("Unexpected keyboard change") }
        }
        val report = engine(listOf(pointer(1, 1, "down"), pointer(2, 2, "up"))).run(driver)
        assertEquals(2, report.eventsDispatched)
        assertTrue(report.divergences.none { it.type == "dispatch.error" })
    }

    @Test
    fun keyboardSynchronizationFailureIsNotCountedAsInjectedInput() {
        val driver = object : ReplayDriver by FakeDriver() {
            override fun preparePointer(imeVisible: Boolean) { error("Keyboard still covers application") }
        }
        val event = pointer(1, 1, "down").let { it.copy(data = it.data + ("imeVisible" to false)) }
        val report = engine(listOf(event)).run(driver)
        assertEquals(0, report.eventsDispatched)
        assertTrue(report.divergences.any { it.type == "dispatch.error" })
    }

    @Test
    fun semanticStrategyWhenScreenDiffersAndActionsExist() {
        var invoked = 0
        actions.register("cart.checkout") { invoked++ }
        val e = engine(listOf(pointer(1, 10, "down"), CapsuleEvent(2, 12, "action", mapOf("id" to "cart.checkout"))))
        val r = e.run(FakeDriver(ScreenInfo(300.0, 600.0, 3.0)))
        assertEquals(ReplayStrategy.SEMANTIC, r.strategy)
        assertEquals(1, invoked)
    }

    @Test
    fun pointersScaleWithScreenSizeDivergenceWhenNoActions() {
        val e = engine(listOf(pointer(1, 10, "down", 100.0, 100.0), pointer(2, 11, "up", 100.0, 100.0)))
        val driver = FakeDriver(ScreenInfo(200.0, 400.0, 1.0))
        val r = e.run(driver)
        assertEquals(ReplayStrategy.POINTER, r.strategy)
        assertEquals("down:0:50,50", driver.pointers.first())
        assertTrue(r.divergences.any { it.type == "screen.size" })
    }

    @Test
    fun reproducedStopsEarlyAndMatchesBySignature() {
        val e = engine(listOf(pointer(1, 10, "down"), pointer(2, 5000, "up")))
        val driver = FakeDriver()
        driver.onPointer = { if (it == "down") e.onFailure(TestCapsules.failure(t = 50)) }
        val started = System.nanoTime()
        val r = e.run(driver)
        assertEquals(ReplayOutcome.REPRODUCED, r.outcome)
        assertTrue("stopped before the 5 s input", (System.nanoTime() - started) / 1_000_000 < 3000)
        assertEquals(TestCapsules.failure().signature, r.observed!!.signature)
    }

    @Test
    fun otherFailuresAreMaterialDivergences() {
        val e = engine(listOf(pointer(1, 10, "down")))
        e.onFailure(TestCapsules.failure(id = "other"))
        val r = e.run(FakeDriver())
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertEquals(1, r.otherFailures.size)
        assertTrue(r.divergences.any { it.type == "failure.other" && it.detail == "assertion:other:" })
    }

    @Test
    fun textAndActionDivergences() {
        actions.trackText("known") {}
        val e = engine(
            listOf(
                CapsuleEvent(1, 10, "text", mapOf("id" to "missing", "value" to "x")),
                CapsuleEvent(2, 11, "text", mapOf("id" to "known", "value" to "[REDACTED]")),
                CapsuleEvent(3, 12, "action", mapOf("id" to "nope")),
            ),
            settings = ReplaySettings(strategy = ReplayStrategy.SEMANTIC, settleTimeoutMs = 100),
        )
        val r = e.run(FakeDriver())
        val types = r.divergences.map { it.type }
        assertTrue(types.containsAll(listOf("text.untracked", "text.redacted", "action.unregistered")))
        assertEquals(1, r.eventsDispatched)
        assertEquals(3, r.eventsExpected)
    }

    @Test
    fun lifecycleAndKeysAreAlwaysDispatched() {
        val e = engine(
            listOf(
                CapsuleEvent(1, -5, "lifecycle", mapOf("state" to "resumed")),
                CapsuleEvent(2, 10, "lifecycle", mapOf("state" to "paused")),
                CapsuleEvent(3, 11, "key", mapOf("phase" to "down", "keyCode" to 4L, "key" to "back")),
                CapsuleEvent(4, 12, "lifecycle", mapOf("state" to "detached")),
            ),
            settings = ReplaySettings(strategy = ReplayStrategy.SEMANTIC, settleTimeoutMs = 100),
        )
        val driver = FakeDriver()
        val r = e.run(driver)
        assertEquals("before the anchor: not dispatched", listOf("paused", "detached"), driver.lifecycle)
        assertEquals(listOf("down:4"), driver.keys)
        assertTrue(r.divergences.any { it.type == "lifecycle.unsupported" })
    }

    @Test
    fun periodicCheckpointTimesAreRelativeToTheCheckpoint() {
        val e = engine(
            listOf(pointer(1, 10_000, "down"), pointer(2, 10_100, "up")),
            checkpoint = Checkpoint(CheckpointKind.PERIODIC, 10_000, 1_760_000_010_000, 0),
            settings = ReplaySettings(settleTimeoutMs = 100, idleCompression = false),
        )
        val started = System.nanoTime()
        e.run(FakeDriver())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
    }

    @Test
    fun idleGapsAreCompressed() {
        val e = engine(listOf(pointer(1, 100, "down"), pointer(2, 120, "up"), pointer(3, 6000, "down"), pointer(4, 6020, "up")))
        val started = System.nanoTime()
        val r = e.run(FakeDriver())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
        assertTrue(r.compressedMs > 4000)
        assertEquals(1, r.gapsCompressed)
    }

    @Test
    fun pendingWorkBlocksCompression() {
        val e = engine(listOf(pointer(1, 100, "down"), pointer(2, 120, "up"), pointer(3, 2500, "down"), pointer(4, 2510, "up")),
            settings = ReplaySettings(settleTimeoutMs = 50))
        val token = e.beginWork()
        val r = e.run(FakeDriver())
        token.end()
        assertEquals(0, r.gapsCompressed)
    }

    @Test
    fun unusedFixturesBuildShaAndNoAssertion() {
        val http = CapsuleEvent(1, 5, "http", mapOf("key" to "GET x/", "occurrence" to 0L, "status" to 200L))
        val e = engine(listOf(http), failure = null, gitSha = "aaa", build = "bbb")
        e.precheck()
        val r = e.run(FakeDriver())
        assertEquals(ReplayOutcome.NO_ASSERTION, r.outcome)
        assertTrue(r.divergences.any { it.type == "network.unused" && it.detail == "GET x/" })
        assertTrue(r.divergences.any { it.type == "build.gitSha" })
        assertEquals("bbb", r.buildGitSha)
    }

    @Test
    fun finishNowReportsOnceSynchronously() {
        val e = engine(listOf(pointer(1, 10, "down")))
        e.onFailure(TestCapsules.failure())
        assertTrue(e.finishNow("the app crashed"))
        assertFalse(e.finishNow("again"))
        assertEquals(1, reports.size)
        assertEquals(ReplayOutcome.REPRODUCED, reports[0].outcome)
        assertEquals("the app crashed", reports[0].message)
        assertEquals(1, reports[0].eventsExpected)
    }

    @Test
    fun virtualClockStartsAtTheCheckpointWallClock() {
        val e = engine(emptyList())
        val now = e.currentTimeMillis()
        assertTrue(now >= 1_760_000_000_000 && now < 1_760_000_000_000 + 5000)
        assertNull(e.buildReport().observed)
    }
}
