package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.SpecFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every case of `spec-fixtures/emergency-v1` (emergency-client-v1 §9), on the
 * oldest and a current API level: the crypto path (KeyFactory EC,
 * SHA256withECDSA, DER signatures) must work from API 23.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class EmergencyVectorsTest {
    private fun fixture(name: String) = SpecFixtures.json("emergency-v1/$name.json")

    @Test
    fun versionVectors() {
        val v = fixture("versions")
        val compare = J.list(v["compare"])!!
        assertTrue(compare.isNotEmpty())
        for (c in compare.map { J.obj(it)!! }) {
            val a = c["a"] as String
            val b = c["b"] as String
            assertEquals("compare($a, $b)", J.int(c["result"]), EmergencyContract.compareVersions(a, b))
            assertEquals("compare($b, $a)", J.int(c["result"])?.let { -it }, EmergencyContract.compareVersions(b, a))
        }
        val invalid = J.list(v["invalid"])!!
        assertTrue(invalid.isNotEmpty())
        for (c in invalid.map { J.obj(it)!! }) {
            val s = c["version"] as String
            assertNull("\"$s\" is not a version", EmergencyContract.parseVersion(s))
            assertNull(EmergencyContract.compareVersions(s, "1.0.0"))
        }
        val targeted = J.list(v["targeted"])!!
        assertTrue(targeted.isNotEmpty())
        for (c in targeted.map { J.obj(it)!! }) {
            val ranges = J.list(c["ranges"])!!.map { r -> J.obj(r)!!.let { EmergencyVersionRange(it["min"] as String?, it["max"] as String?) } }
            assertEquals("targeted(${c["version"]}, $ranges)", c["expected"], EmergencyContract.versionTargeted(c["version"] as String?, ranges))
        }
    }

    @Test
    fun rolloutVectors() {
        val cases = J.list(fixture("rollout")["cases"])!!
        assertTrue(cases.isNotEmpty())
        for (c in cases.map { J.obj(it)!! }) {
            assertEquals("bucket(${c["seed"]}, ${c["subject"]})", J.int(c["bucket"]), EmergencyContract.rolloutBucket(c["seed"] as String, c["subject"] as String))
        }
    }

    @Test
    fun evaluationVectors() {
        val f = fixture("evaluation")
        val documents = J.obj(f["documents"])!!.mapValues { (_, d) -> parse(J.obj(d)!!) }
        val cases = J.list(f["cases"])!!
        assertTrue(cases.isNotEmpty())
        for ((i, c) in cases.map { J.obj(it)!! }.withIndex()) {
            val doc = documents.getValue(c["document"] as String)
            val ctx = J.obj(c["context"])!!
            val input = EmergencyContract.Input(
                platform = ctx["platform"] as String,
                appVersion = ctx["app_version"] as String?,
                subject = ctx["subject"] as String?,
                nowMs = EmergencyContract.parseInstant(ctx["now"] as String)!!,
            )
            val decision = EmergencyContract.decide(doc.policies, doc.revision, c["feature_key"] as String, input, EmergencyDecisionSource.POLICY)
            assertEquals("case $i (${c["document"]} / ${c["feature_key"]})", J.obj(c["expected"]), decision.toWire())
        }
    }

    /** The evaluation documents carry only `revision` and `policies`; the rest is filled in to reuse the document parser. */
    private fun parse(d: Map<String, Any?>): PolicyDocument {
        val full = LinkedHashMap(d)
        full["application_id"] = APP_ID
        full["environment"] = "production"
        full["issued_at"] = "2026-10-29T08:00:00.000Z"
        full["expires_at"] = "2026-10-29T09:00:00.000Z"
        return EnvelopeVerifier.parseDocument(full) ?: throw AssertionError("fixture document does not parse: $d")
    }

    @Test
    fun envelopeVectors() {
        val f = fixture("envelopes")
        val environment = f["environment"] as String
        val anchor = (TrustAnchor.parse(f["anchor"] as String) as TrustAnchor.Parsed.Valid).anchor
        val rotated = (TrustAnchor.parse(f["anchor_rotated"] as String) as TrustAnchor.Parsed.Valid).anchor
        assertEquals(f["application_id"], anchor.app)
        assertEquals(1, anchor.keys.size)
        assertEquals(2, rotated.keys.size)
        val cases = J.list(f["cases"])!!.map { J.obj(it)!! }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"] as String
            val outcome = outcome(EnvelopeVerifier.verify(c["envelope"], anchor, environment), c)
            assertEquals(name, c["expected"], outcome)
            if (name == "valid_second_key") {
                assertEquals("valid_second_key with anchor_rotated", "ok", outcome(EnvelopeVerifier.verify(c["envelope"], rotated, environment), c))
            } else if (c["expected"] == "ok") {
                assertEquals("$name with anchor_rotated", "ok", outcome(EnvelopeVerifier.verify(c["envelope"], rotated, environment), c))
            }
        }
    }

    private fun outcome(v: Verification, c: Map<String, Any?>): String = when (v) {
        is Verification.Failed -> v.reason
        is Verification.Ok -> {
            J.long(c["revision"])?.let { assertEquals(it, v.document.revision) }
            assertEquals(APP_ID, v.document.applicationId)
            "ok"
        }
    }

    @Test
    fun envelopeVectorsVerifyInWrongEnvironment() {
        val f = fixture("envelopes")
        val anchor = (TrustAnchor.parse(f["anchor"] as String) as TrustAnchor.Parsed.Valid).anchor
        val valid = J.list(f["cases"])!!.map { J.obj(it)!! }.first { it["name"] == "valid" }
        val v = EnvelopeVerifier.verify(valid["envelope"], anchor, "staging")
        assertEquals(Reasons.WRONG_ENVIRONMENT, (v as Verification.Failed).reason)
        assertEquals(42L, v.revision)
    }
}
