package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchIdentityTest {
    private val hash = "sha256:" + "a".repeat(64)
    private val digest = "sha256:" + "b".repeat(64)
    private fun id(base: String = "build-1", patch: String? = null, abi: Int = 1) = PatchIdentity(base, patch, abi, hash)

    private fun manifest(patch: PatchIdentity?) = CapsuleManifest(
        capsuleId = "c",
        createdAtMs = 1,
        mode = CaptureMode.QA,
        app = AppInfo("1.0", "1"),
        sdkName = "s",
        sdkVersion = "1",
        stateSchemaVersion = 1,
        platform = PlatformInfo("android", "15", "en", 400.0, 800.0, 2.0),
        session = SessionInfo("s", 1, 1, 1, 0),
        eligibility = Eligibility(EligibilityStatus.ELIGIBLE, emptyList()),
        integrations = emptyList(),
        eventCount = 0,
        droppedEvents = 0,
        checkpointKind = CheckpointKind.LAUNCH,
        patch = patch,
        dependencies = listOfNotNull(patch?.takeIf { it.patchSetDigest != null }?.dependency),
    )

    private fun check(captured: PatchIdentity?, running: PatchIdentity?, artifact: Boolean = false, expected: String? = null, binding: Boolean = true, kind: String = "replay", deps: List<CapsuleDependency>? = null): String? {
        val m = manifest(captured).let { if (deps != null) it.copy(dependencies = deps) else it }
        return patchPreflight(m, ReplaySettings(patchArtifact = artifact, expectedPatchSetDigest = expected, kind = kind), binding, running)
    }

    @Test fun identityRoundTrips() {
        val i = id(patch = digest).copy(artifactDigest = digest)
        assertEquals(i, PatchIdentity.fromJson(i.toJson()))
    }

    @Test fun preexistingCapsulesStillReplay() {
        assertNull(check(null, null, binding = false))
        assertNull(check(null, id()))
    }

    @Test fun aReplayerWithoutARuntimeRefusesAPatchedCapsule() {
        assertTrue(check(id(patch = digest), null, binding = false)!!.startsWith("dependency.unsupported"))
    }

    @Test fun aMissingArchivedArtifactIsNeverABaseRun() {
        assertTrue(check(id(patch = digest), id())!!.startsWith("patch.artifact_missing"))
    }

    @Test fun wrongBaseAbiOrDigestIsIncompatible() {
        assertTrue(check(id(), id(base = "x"))!!.startsWith("patch.base"))
        assertTrue(check(id(), id(abi = 2))!!.startsWith("patch.abi"))
        assertTrue(check(id(), id(patch = hash), artifact = true, expected = digest, kind = "verify")!!.startsWith("patch.mismatch"))
    }

    @Test fun aFixRunOfTheExactDigestPasses() {
        assertNull(check(id(), id(patch = digest), artifact = true, expected = digest, kind = "verify"))
        assertNull(check(id(patch = digest), id(patch = digest), artifact = true, expected = digest))
    }

    @Test fun aBaselineMustRunTheCapturedDigest() {
        assertTrue(check(id(patch = digest), id(patch = hash), artifact = true, expected = hash)!!.startsWith("patch.mismatch"))
        assertTrue(check(id(), id(patch = digest), artifact = true, expected = digest)!!.startsWith("patch.mismatch"))
        assertTrue(check(null, id(patch = digest), artifact = true, expected = digest)!!.startsWith("patch.mismatch"))
        assertNull(check(id(patch = digest), id(patch = hash), artifact = true, expected = hash, kind = "verify"))
    }

    @Test fun aRequiredRuntimeIsValidatedNotSkipped() {
        val good = id(patch = digest).dependency
        assertTrue(check(id(patch = digest), id(patch = digest), artifact = true, expected = digest, deps = listOf(good.copy(adapter = "x.y")))!!.startsWith("dependency.unsupported"))
        assertTrue(check(id(patch = digest), id(patch = digest), artifact = true, expected = digest, deps = listOf(good.copy(schema = 2)))!!.startsWith("dependency.schema"))
        assertTrue(check(null, id(), deps = listOf(good))!!.startsWith("patch.identity_missing"))
    }
}
