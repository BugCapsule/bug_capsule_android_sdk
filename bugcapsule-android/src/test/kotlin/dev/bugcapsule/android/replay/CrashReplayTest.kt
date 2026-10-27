package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.RunKind
import dev.bugcapsule.android.core.Verdict
import dev.bugcapsule.android.core.Verdicts
import dev.bugcapsule.android.internal.ActionRegistry
import dev.bugcapsule.android.monitoring.ExitInfoSource
import dev.bugcapsule.android.monitoring.ExitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Replay of crash capsules (docs/crash-capsules.md §4). */
@RunWith(RobolectricTestRunner::class)
class CrashReplayTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val reports = ArrayList<ReplayReport>()

    private val segv = FailureRecord.crash("SIGSEGV", "Segmentation violation", 1000, "signal", 11, "checkout_native")
    private val anr = FailureRecord.crash("ApplicationNotResponding", "Input dispatching timed out", 1000, "anr")

    private fun engine(failure: FailureRecord, marker: File? = null, anrMs: Long = 60_000) = ReplayEngine(
        TestCapsules.capsule(listOf(CapsuleEvent(1, 10, "lifecycle", mapOf("state" to "resumed"))), failure),
        ReplaySettings(settleTimeoutMs = 100, attemptId = "att-7", attempt = 2),
        ActionRegistry(), null, { reports.add(it) },
        attemptMarker = marker, anrThresholdMs = anrMs,
    )

    /** The main thread answers at once ([blocked] = false) or never. */
    private fun driver(blocked: Boolean = false) = object : ReplayDriver by FakeDriver() {
        override fun postToMain(task: Runnable) {
            if (!blocked) task.run()
        }

        override fun mainThread(): Thread? = null
    }

    @Test
    fun theSameCrashTypeReproducesAndAnotherFrameIsNoted() {
        val same = CrashReplay.judge(segv, CrashReplay.Death("SIGSEGV", "signal", "tombstone", signal = 11, inAppFrame = "checkout_native"))
        assertEquals(ReplayOutcome.REPRODUCED, same.outcome)
        assertNull(same.reasonCode)
        val frame = CrashReplay.judge(segv, CrashReplay.Death("SIGSEGV", "signal", "tombstone", inAppFrame = "cart_native"))
        assertEquals(ReplayOutcome.REPRODUCED, frame.outcome)
        assertEquals("failure.different_frame", frame.reasonCode)
        assertNull("frames unknown on one side: no claim", CrashReplay.judge(segv, CrashReplay.Death("SIGSEGV", "signal", "exit_info")).reasonCode)
        val other = CrashReplay.judge(segv, CrashReplay.Death("SIGABRT", "signal", "tombstone"))
        assertEquals(ReplayOutcome.INCONCLUSIVE, other.outcome)
        assertEquals("failure.different", other.reasonCode)
        val unknownSignal = FailureRecord.crash("NativeCrash", "", 0, "signal")
        assertEquals(ReplayOutcome.REPRODUCED, CrashReplay.judge(unknownSignal, CrashReplay.Death("SIGBUS", "signal", "tombstone")).outcome)
        assertEquals(ReplayOutcome.INCONCLUSIVE, CrashReplay.judge(unknownSignal, CrashReplay.Death("OutOfMemory", "oom", "exit_info")).outcome)
    }

    @Test
    fun theWindowEndingWithTheProcessAliveIsNotReproduced() {
        val marker = File(tmp.root, "marker.json")
        val r = engine(segv, marker).run(driver())
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertNull(r.death)
        assertFalse("reported from the process: nothing for a post-mortem", marker.exists())
        assertEquals(Verdict.NOT_REPRODUCED, Verdicts.evaluate(RunKind.REPLAY, 1, listOf(r.copy(attempt = 1))).verdict)
    }

    @Test
    fun anUncaughtExceptionDuringACrashReplayIsADifferentDeath() {
        val e = engine(segv)
        e.onFailure(FailureRecord.exception(IllegalStateException("boom"), 50), fatal = true)
        e.finishNow("the app crashed")
        val r = reports.single()
        assertEquals(ReplayOutcome.INCONCLUSIVE, r.outcome)
        assertEquals("failure.different", r.reasonCode)
        assertEquals("uncaught", r.death!!["mechanism"])
        assertEquals("IllegalStateException", r.observed!!.type)
        val run = Verdicts.evaluate(RunKind.VERIFY, 1, listOf(r.copy(attempt = 1)))
        assertEquals(Verdict.INCONCLUSIVE, run.verdict)
        assertEquals(listOf("failure.different"), run.reasonCodes)
    }

    @Test
    fun aHandledFailureDoesNotEndACrashReplay() {
        val e = engine(segv)
        e.onFailure(FailureRecord.assertion("cart_empty", "", 20))
        assertFalse(e.isReproduced)
        val r = e.run(driver())
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertEquals("assertion:cart_empty:", r.otherFailures.single().signature)
    }

    @Test
    fun aBlockedMainThreadIsAnAnrReportedWhileTheProcessLives() {
        val r = engine(anr, anrMs = 150).run(driver(blocked = true))
        assertEquals(ReplayOutcome.REPRODUCED, r.outcome)
        assertEquals("crash:ApplicationNotResponding", r.observed!!.signature)
        assertEquals("watchdog", r.death!!["source"])
        assertEquals(1, reports.size)

        reports.clear()
        val other = engine(segv, anrMs = 150).run(driver(blocked = true))
        assertEquals(ReplayOutcome.INCONCLUSIVE, other.outcome)
        assertEquals("failure.different", other.reasonCode)
    }

    @Test
    fun theAnrFrameIsTheTopInAppFrameOfTheMainThread() {
        val stack = arrayOf(
            StackTraceElement("java.lang.Thread", "sleep", null, 0),
            StackTraceElement("com.shop.Cart", "load", null, 0),
            StackTraceElement("com.shop.MainActivity", "onCreate", null, 0),
        )
        assertEquals("com.shop.Cart.load", CrashReplay.inAppFrame(stack, "com.shop"))
        assertNull(CrashReplay.inAppFrame(stack, null))
    }

    @Test
    fun aRunLeavesAMarkerThatAPostMortemJudgesFromTheExitRecord() {
        val marker = File(tmp.root, "marker.json")
        var markedDuringRun = false
        engine(segv, marker).run(object : ReplayDriver by driver() {
            override fun moveLifecycle(state: String): Boolean {
                markedDuringRun = marker.exists()
                return true
            }
        })
        assertTrue("written before the first input", markedDuringRun)
        assertFalse(marker.exists())
        // A process that dies mid-run leaves it.
        CrashReplay.writeMarker(marker, "c0ffee00-0000-4000-8000-000000000001", ReplaySettings(attempt = 2, attemptId = "att-7"), segv, dev.bugcapsule.android.core.ReplayStrategy.POINTER, 3)
        val pid = android.os.Process.myPid()
        val exits = ExitInfoSource {
            listOf(ExitRecord(pid, ExitRecord.REASON_CRASH_NATIVE, System.currentTimeMillis() + 10, 100, "crash", "com.shop", status = 11) { null })
        }
        val r = CrashReplay.postMortem(marker, "att-7", "com.shop", exits, 1_000, 34)
        assertEquals(ReplayOutcome.REPRODUCED, r.outcome)
        assertEquals("crash:SIGSEGV", r.expectedSignature)
        assertEquals("crash:SIGSEGV", r.observed!!.signature)
        assertEquals(mapOf("mechanism" to "signal", "type" to "SIGSEGV", "signal" to 11, "pid" to pid, "source" to "exit_info"), r.death)
        assertEquals(2, r.attempt)
        assertEquals("att-7", r.attemptId)
        assertEquals(3, r.eventsExpected)
        assertFalse(marker.exists())
        assertEquals(Verdict.REPRODUCED, Verdicts.evaluate(RunKind.REPLAY, 1, listOf(r.copy(attempt = 1))).verdict)
    }

    private fun postMortem(reason: Int, status: Int = 0, expected: FailureRecord = segv, attemptId: String? = "att-7", api: Int = 34): ReplayReport {
        val marker = File(tmp.root, "m-${System.nanoTime()}.json")
        CrashReplay.writeMarker(marker, "c", ReplaySettings(attemptId = "att-7"), expected, dev.bugcapsule.android.core.ReplayStrategy.AUTO, 1)
        val pid = android.os.Process.myPid()
        return CrashReplay.postMortem(
            marker, attemptId, "com.shop",
            ExitInfoSource { listOf(ExitRecord(pid, reason, System.currentTimeMillis(), 100, "why", "com.shop", status) { null }) },
            300, api,
        )
    }

    @Test
    fun postMortemDeathTypes() {
        val abort = postMortem(ExitRecord.REASON_CRASH_NATIVE, status = 6)
        assertEquals(ReplayOutcome.INCONCLUSIVE, abort.outcome)
        assertEquals("failure.different", abort.reasonCode)
        assertEquals("SIGABRT", abort.death!!["type"])

        val lowMemory = postMortem(ExitRecord.REASON_LOW_MEMORY, expected = FailureRecord.crash("OutOfMemory", "", 0, "oom"))
        assertEquals(ReplayOutcome.REPRODUCED, lowMemory.outcome)
        assertEquals(ReplayOutcome.REPRODUCED, postMortem(ExitRecord.REASON_ANR, expected = anr).outcome)
        assertEquals("kill -11 from outside is a SIGSEGV too", ReplayOutcome.REPRODUCED, postMortem(ExitRecord.REASON_SIGNALED, status = 11).outcome)

        val stopped = postMortem(10 /* USER_REQUESTED */)
        assertEquals(ReplayOutcome.ERROR, stopped.outcome)
        assertTrue(stopped.message!!.contains("without crashing"))
        assertEquals(ReplayOutcome.ERROR, postMortem(ExitRecord.REASON_SIGNALED, status = 9).outcome)
        assertEquals("another attempt's marker", ReplayOutcome.ERROR, postMortem(ExitRecord.REASON_CRASH_NATIVE, 11, attemptId = "att-8").outcome)
        assertTrue(postMortem(ExitRecord.REASON_CRASH_NATIVE, 11, api = 29).message!!.contains("logcat"))
    }

    @Test
    fun withoutAMarkerOrExitRecordThePostMortemIsAnError() {
        val none = CrashReplay.postMortem(File(tmp.root, "absent.json"), "att-7", "com.shop", ExitInfoSource { emptyList() }, 100, 34)
        assertEquals(ReplayOutcome.ERROR, none.outcome)
        val marker = File(tmp.root, "m.json")
        CrashReplay.writeMarker(marker, "c", ReplaySettings(attemptId = "att-7"), segv, dev.bugcapsule.android.core.ReplayStrategy.AUTO, 1)
        val noExit = CrashReplay.postMortem(marker, "att-7", "com.shop", ExitInfoSource { emptyList() }, 100, 34)
        assertEquals(ReplayOutcome.ERROR, noExit.outcome)
        assertTrue(noExit.message!!.contains("no exit record"))
    }

    @Test
    fun reportsRoundTripTheNewFields() {
        val r = ReplayReport(
            ReplayOutcome.INCONCLUSIVE, 1, 5, dev.bugcapsule.android.core.ReplayStrategy.POINTER,
            reasonCode = "failure.different", death = mapOf("mechanism" to "signal", "type" to "SIGABRT"),
        )
        val back = ReplayReport.fromJson(dev.bugcapsule.android.core.J.obj(dev.bugcapsule.android.core.Json.parse(dev.bugcapsule.android.core.Json.stringify(r.toJson())))!!)
        assertEquals(ReplayOutcome.INCONCLUSIVE, back.outcome)
        assertEquals("failure.different", back.reasonCode)
        assertEquals("SIGABRT", back.death!!["type"])
        assertEquals("inconclusive", r.toJson()["outcome"])
    }
}
