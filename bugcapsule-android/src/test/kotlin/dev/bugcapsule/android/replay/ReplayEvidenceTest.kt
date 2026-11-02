package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.EvidenceLevel
import dev.bugcapsule.android.core.FailureKind
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.internal.ActionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verdict v2 evidence: a clean replay says whether the capsule's assertion ran
 * and held, what the postconditions returned, and whether replay got past the
 * recorded failure point (mirrors the Flutter `replay_evidence_test.dart`).
 */
class ReplayEvidenceTest {
    // The capsule's failure is the assertion `cart.total`, at t = 200 ms.
    private val capsule = TestCapsules.capsule(emptyList(), TestCapsules.failure(t = 200, id = "cart.total"))

    private fun engine() = ReplayEngine(capsule, ReplaySettings(settleTimeoutMs = 100), ActionRegistry(), null, null)

    @Test
    fun theCapsuleFailsOnTheCartTotalAssertion() {
        assertEquals(FailureKind.ASSERTION, capsule.manifest.failure!!.kind)
        assertEquals("cart.total", capsule.manifest.failure!!.assertionId)
    }

    @Test
    fun aFixedAppAssertionHeldAndPostconditionsReported() {
        val e = engine().apply { postconditions = { mapOf("cart.total_positive" to true) } }
        e.checkPassed("cart.total")
        val r = e.run(FakeDriver())
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertEquals(true, r.evidence!!.assertionPassed)
        assertEquals(mapOf("cart.total_positive" to true), r.evidence!!.postconditions)
        assertEquals(EvidenceLevel.STRONG, r.evidence!!.level)
        @Suppress("UNCHECKED_CAST")
        val back = ReplayReport.fromJson(dev.bugcapsule.android.core.Json.parse(dev.bugcapsule.android.core.Json.stringify(r.toJson())) as Map<String, Any?>)
        assertEquals(EvidenceLevel.STRONG, back.evidence!!.level)
    }

    @Test
    fun anAppThatNeverEvaluatesTheAssertionGivesAtMostWeakEvidence() {
        val r = engine().run(FakeDriver())
        assertEquals(ReplayOutcome.NOT_REPRODUCED, r.outcome)
        assertNull(r.evidence!!.assertionPassed)
        assertTrue(r.evidence!!.failurePointPassed)
        assertEquals(EvidenceLevel.WEAK, r.evidence!!.level)
    }

    @Test
    fun aCheckOfAnotherAssertionIsNotEvidenceForThisOne() {
        val e = engine()
        e.checkPassed("profile.loaded")
        assertNull(e.run(FakeDriver()).evidence!!.assertionPassed)
    }

    @Test
    fun aPostconditionThatDidNotHoldLeavesNoEvidence() {
        val e = engine().apply { postconditions = { mapOf("cart.total_positive" to false) } }
        e.checkPassed("cart.total")
        val r = e.run(FakeDriver())
        assertEquals(listOf("cart.total_positive"), r.evidence!!.failedPostconditions)
        assertEquals(EvidenceLevel.NONE, r.evidence!!.level)
    }

    @Test
    fun aReproductionCarriesNoFixEvidence() {
        val e = engine().apply { postconditions = { mapOf("cart.total_positive" to true) } }
        e.checkPassed("cart.total")
        e.onFailure(capsule.manifest.failure!!)
        val r = e.run(FakeDriver())
        assertEquals(ReplayOutcome.REPRODUCED, r.outcome)
        assertNull(r.evidence!!.assertionPassed)
        assertTrue(r.evidence!!.postconditions.isEmpty())
        assertFalse(r.evidence!!.failurePointPassed)
        assertEquals(EvidenceLevel.NONE, r.evidence!!.level)
    }

    @Test
    fun aRunThatEndsBeforeTheFailurePointHasNoEvidence() {
        // Built before run(): nothing was dispatched or settled.
        assertEquals(EvidenceLevel.NONE, engine().buildReport().evidence!!.level)
    }
}
