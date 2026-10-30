package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.core.SpecFixtures
import dev.bugcapsule.android.internal.ActionRegistry
import dev.bugcapsule.android.replay.FakeDriver
import dev.bugcapsule.android.replay.ReplayEngine
import dev.bugcapsule.android.replay.TestCapsules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** §7.8: the shared vectors, the tracker and the replay clock. */
class EmergencyDecisionsTest {
    private val key = "checkout.confirm_order"

    private fun blocked(documentRevision: Long = 42, reason: String = "INCIDENT", message: String? = null) = EmergencyDecision(
        featureKey = key, allowed = false, action = EmergencyAction.BLOCK, reasonCode = reason, policyId = "p-1",
        policyRevision = 3, documentRevision = documentRevision, publicUserMessage = message, requiredVersion = null,
        fallback = null, fallbackAvailable = false, source = EmergencyDecisionSource.POLICY,
    )

    private class Written : (Map<String, Any?>) -> Unit {
        val events: MutableList<Map<String, Any?>> = Collections.synchronizedList(ArrayList<Map<String, Any?>>())

        override fun invoke(data: Map<String, Any?>) {
            events.add(data)
        }
    }

    // --------------------------------------------------------- shared vectors

    private val vectors get() = SpecFixtures.json("emergency-v1/replay-decisions.json")

    @Test
    fun subjectTagVectors() {
        val cases = J.list(vectors["subjectTags"])!!.map { J.obj(it)!! }
        assertTrue(cases.isNotEmpty())
        for (c in cases) assertEquals(c["tag"], emergencySubjectTag(c["subject"] as String))
    }

    @Test
    fun lookupVectors() {
        val v = vectors
        val script = EmergencyDecisionScript(J.list(v["events"])!!.map { CapsuleEvent.fromJson(J.obj(it)!!) })
        val fields = mapOf(
            "allowed" to "allowed", "action" to "action", "reasonCode" to "reason_code", "policyId" to "policy_id",
            "policyRevision" to "policy_revision", "documentRevision" to "document_revision",
            "message" to "public_user_message", "requiredVersion" to "required_version", "fallback" to "fallback",
            "fallbackAvailable" to "fallback_available",
        )
        val cases = J.list(v["cases"])!!.map { J.obj(it)!! }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"] as String
            val tag = (c["subject"] as String?)?.let(::emergencySubjectTag)
            val got = script.lookup(c["key"] as String, tag, J.long(c["t"])!!)
            val expected = J.obj(c["expect"])
            if (expected == null) {
                assertNull(name, got)
                continue
            }
            assertNotNull(name, got)
            assertEquals(name, EmergencyDecisionSource.REPLAY, got!!.source)
            val wire = got.toWire()
            for ((field, value) in expected) assertEquals("$name: $field", value, wire[fields.getValue(field)])
        }
    }

    // ---------------------------------------------------------------- tracker

    @Test
    fun aDecisionIsWrittenOnlyWhenItChanges() {
        val t = EmergencyDecisionTracker()
        val w = Written()
        t.track(blocked(), null, w)
        t.track(blocked(), null, w)
        assertEquals(1, w.events.size)
        t.track(blocked(documentRevision = 43), null, w)
        t.track(blocked(documentRevision = 43, message = "Paused."), null, w)
        assertEquals(listOf(42L, 43L, 43L), w.events.map { it["documentRevision"] })
        assertEquals("Paused.", w.events.last()["message"])
    }

    @Test
    fun theEventHoldsTheDecisionsFieldsAndNoNulls() {
        val w = Written()
        EmergencyDecisionTracker().track(blocked(message = "Paused."), "user-1", w)
        assertEquals(
            mapOf(
                "key" to key, "subject" to "748982dd71b97baf", "action" to "BLOCK", "source" to "policy",
                "reasonCode" to "INCIDENT", "policyId" to "p-1", "policyRevision" to 3L, "documentRevision" to 42L,
                "message" to "Paused.",
            ),
            w.events.single(),
        )
    }

    @Test
    fun subjectsAreTrackedApartAndNeverWritten() {
        val t = EmergencyDecisionTracker()
        val w = Written()
        t.track(blocked(), "user-1", w)
        t.track(blocked(), "user-2", w)
        t.track(blocked(), "user-1", w)
        t.track(blocked(), null, w)
        assertEquals(listOf("748982dd71b97baf", "5baa2f291d4eb976", null), w.events.map { it["subject"] })
        assertFalse(Json.stringify(w.events).contains("user-"))
    }

    @Test
    fun pastTheLimitNothingIsWrittenAndTheTrackerIsIncompleteOnce() {
        var told = 0
        val t = EmergencyDecisionTracker(maxPairs = 1, onIncomplete = { told++ })
        val w = Written()
        t.track(blocked(), "user-1", w)
        assertTrue(t.complete)
        t.track(blocked(), "user-2", w)
        t.track(blocked(), "user-3", w)
        assertFalse(t.complete)
        assertEquals(1, told)
        assertEquals(1, w.events.size)
        // A pair that fits still changes.
        t.track(blocked(documentRevision = 43), "user-1", w)
        assertEquals(2, w.events.size)
    }

    @Test
    fun aWriteThatFailsMarksTheTrackerIncompleteAndDoesNotThrow() {
        val t = EmergencyDecisionTracker()
        t.track(blocked(), null) { throw IllegalStateException("no") }
        assertFalse(t.complete)
    }

    @Test
    fun everyTrackedDecisionCanBeWrittenAgain() {
        val t = EmergencyDecisionTracker()
        t.track(blocked(), "user-1") {}
        t.track(blocked(), "user-2") {}
        val w = Written()
        t.writeAll(w)
        assertEquals(setOf("748982dd71b97baf", "5baa2f291d4eb976"), w.events.map { it["subject"] }.toSet())
    }

    @Test
    fun tagsAreStableAcrossTheCache() {
        val t = EmergencyDecisionTracker()
        for (round in 1..2) {
            for (i in 0 until EmergencyDecisionTracker.MAX_PAIRS + 50) assertEquals(emergencySubjectTag("u$i"), t.tagOf("u$i"))
        }
    }

    // -------------------------------------------------------------- threading

    @Test
    fun manyThreadsEvaluatingTheSameDecisionWriteItOnce() {
        val t = EmergencyDecisionTracker()
        val w = Written()
        val threads = (1..8).map {
            thread {
                repeat(2_000) { t.track(blocked(), "user-1", w) }
            }
        }
        threads.forEach { it.join() }
        assertEquals(1, w.events.size)
    }

    @Test
    fun anEvaluationNeverWaitsForAnotherThreadsHookAndOrderHolds() {
        val t = EmergencyDecisionTracker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<Any?>())
        val write: (Map<String, Any?>) -> Unit = { data ->
            order.add(data["documentRevision"])
            if (data["documentRevision"] == 1L) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val slow = thread { t.track(blocked(documentRevision = 1), null, write) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        thread {
            t.track(blocked(documentRevision = 2), null, write)
            done.countDown()
        }
        assertTrue("the second evaluation waited for the first one's hook", done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf<Any?>(1L), order.toList())

        release.countDown()
        slow.join()
        assertEquals(listOf<Any?>(1L, 2L), order.toList())
    }

    @Test
    fun racingChangesEndWithTheTrackedDecisionLast() {
        repeat(20) {
            val t = EmergencyDecisionTracker()
            val w = Written()
            val threads = (1..4).map { n ->
                thread {
                    for (i in 0 until 200) t.track(blocked(documentRevision = (n * 1000 + i).toLong()), null, w)
                }
            }
            threads.forEach { it.join() }
            val current = Written()
            t.writeAll(current)
            assertEquals(current.events.single(), w.events.last())
        }
    }

    // ----------------------------------------------------------------- script

    private fun decision(seq: Long, t: Long, action: String) =
        CapsuleEvent(seq, t, EventTypes.EMERGENCY_DECISION, mapOf("key" to key, "action" to action, "source" to "policy"))

    @Test
    fun anUnknownActionIsSkippedNotBlocked() {
        val script = EmergencyDecisionScript(listOf(decision(1, 0, "ALLOW"), decision(2, 10, "SOMETHING_NEW")))
        assertEquals(EmergencyAction.ALLOW, script.lookup(key, null, 20)!!.action)
    }

    @Test
    fun onEqualTimeTheLaterEventWins() {
        val script = EmergencyDecisionScript(listOf(decision(1, 10, "ALLOW"), decision(2, 10, "BLOCK")))
        assertEquals(EmergencyAction.BLOCK, script.lookup(key, null, 10)!!.action)
    }

    @Test
    fun otherEventTypesAndUnknownFieldsDoNotMatter() {
        val script = EmergencyDecisionScript(
            listOf(
                CapsuleEvent(1, 0, "emergency.other", mapOf("key" to key, "action" to "BLOCK")),
                decision(2, 0, "ALLOW").let { it.copy(data = it.data + ("future" to 1L)) },
            ),
        )
        assertEquals(EmergencyAction.ALLOW, script.lookup(key, null, 0)!!.action)
    }

    /** The replay clock runs in the milliseconds of event `t`, from the checkpoint of the window. */
    private fun decisionsSeen(checkpoint: Checkpoint, start: Long): List<EmergencyAction?> {
        val actions = ActionRegistry()
        val events = listOf(
            decision(1, start + 1, "ALLOW"),
            CapsuleEvent(2, start + 50, "action", mapOf("id" to "first")),
            decision(3, start + 500, "BLOCK"),
            CapsuleEvent(4, start + 800, "action", mapOf("id" to "second")),
        )
        val settings = ReplaySettings(strategy = ReplayStrategy.SEMANTIC, settleTimeoutMs = 50, idleCompression = false)
        val engine = ReplayEngine(TestCapsules.capsule(events, checkpoint = checkpoint), settings, actions, null, null)
        val seen = Collections.synchronizedList(ArrayList<EmergencyAction?>())
        actions.register("first") { seen.add(engine.emergencyDecision(key, null)?.action) }
        actions.register("second") { seen.add(engine.emergencyDecision(key, null)?.action) }
        engine.run(FakeDriver())
        return seen.toList()
    }

    @Test
    fun theReplayClockFollowsALaunchWindow() {
        val seen = decisionsSeen(Checkpoint(CheckpointKind.LAUNCH, -100, 1_760_000_000_000, 0), 0)
        assertEquals(listOf(EmergencyAction.ALLOW, EmergencyAction.BLOCK), seen)
    }

    @Test
    fun theReplayClockStartsAtAPeriodicCheckpoint() {
        val seen = decisionsSeen(Checkpoint(CheckpointKind.PERIODIC, 60_000, 1_760_000_000_000, 0), 60_000)
        assertEquals(listOf(EmergencyAction.ALLOW, EmergencyAction.BLOCK), seen)
    }

    @Test
    fun aDecisionInACompressedIdleStretchIsSeenAfterIt() {
        val actions = ActionRegistry()
        val events = listOf(decision(1, 200, "BLOCK"), CapsuleEvent(2, 4_000, "action", mapOf("id" to "pay")))
        val engine = ReplayEngine(
            TestCapsules.capsule(events),
            ReplaySettings(strategy = ReplayStrategy.SEMANTIC, settleTimeoutMs = 50),
            actions, null, null,
        )
        var seen: EmergencyAction? = null
        actions.register("pay") { seen = engine.emergencyDecision(key, null)?.action }
        val report = engine.run(FakeDriver())
        assertTrue("the gap was skipped", report.compressedMs > 0)
        assertEquals(EmergencyAction.BLOCK, seen)
    }
}
