package dev.bugcapsule.android.core

/** The dependency a capsule captured with a patch loaded must require (capsule-v2 §10). */
const val PATCH_RUNTIME_DEPENDENCY = "patch.runtime"
const val PATCH_RUNTIME_ADAPTER = "bugcapsule.patch_runtime"

/** `manifest.patch` (capsule-v2 §10): what actually ran. A null [patchSetDigest] is the bare base build. */
data class PatchIdentity(
    val baseBuildId: String,
    val patchSetDigest: String?,
    val engineAbi: Int,
    val hostAbiHash: String,
    val artifactDigest: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "baseBuildId" to baseBuildId,
        "patchSetDigest" to patchSetDigest,
        "engineAbi" to engineAbi,
        "hostAbiHash" to hostAbiHash,
    ).also { if (artifactDigest != null) it["artifactDigest"] = artifactDigest }

    /** The required dependency of a capsule captured with a patch loaded. */
    val dependency: CapsuleDependency
        get() = CapsuleDependency.fromJson(
            mapOf(
                "id" to PATCH_RUNTIME_DEPENDENCY,
                "kind" to PATCH_RUNTIME_DEPENDENCY,
                "adapter" to PATCH_RUNTIME_ADAPTER,
                "adapterVersion" to "1",
                "schema" to 1,
                "required" to true,
                "coverage" to DependencyCoverage.COMPLETE.wire,
            ),
        )

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = PatchIdentity(
            baseBuildId = j["baseBuildId"] as String,
            patchSetDigest = J.str(j["patchSetDigest"]),
            engineAbi = J.int(j["engineAbi"]) ?: 0,
            hostAbiHash = j["hostAbiHash"] as String,
            artifactDigest = J.str(j["artifactDigest"]),
        )
    }
}

/**
 * Connects the patch runtime to capture and replay. `bugcapsule-patch`
 * implements this; pass it to `BugCapsuleConfig.Builder.patchBinding`.
 */
interface PatchBinding {
    /** What this process runs. Capsules record it. */
    val identity: PatchIdentity?

    /**
     * Replay: load exactly [bundle], or the bare base when null, offline and
     * without the device store or live channel. A bundle signed by a delegated
     * key comes with the archived [keyring] and [keyringSignature]. Throws when
     * that is impossible: running anything else would make the attempt meaningless.
     */
    fun pinForReplay(bundle: ByteArray?, keyring: ByteArray?, keyringSignature: ByteArray?): PatchIdentity?
}

/** Why a capsule can't replay with this patch setup, or null. A wrong or missing patch is never a run of something else. */
fun patchPreflight(manifest: CapsuleManifest, settings: ReplaySettings, hasBinding: Boolean, running: PatchIdentity?): String? {
    val captured = manifest.patch
    val needsRuntime = captured?.patchSetDigest != null || manifest.dependencies.any { it.required && it.kind == PATCH_RUNTIME_DEPENDENCY }
    if (!hasBinding) {
        return if (needsRuntime || settings.patchArtifact) {
            "dependency.unsupported: $PATCH_RUNTIME_ADAPTER is not set up in this build; set patchBinding on BugCapsuleConfig"
        } else {
            null
        }
    }
    if (running == null) return "patch.unavailable: the patch runtime reported no identity"
    if (captured != null) {
        if (captured.baseBuildId != running.baseBuildId) {
            return "patch.base: captured on base ${captured.baseBuildId}, this build is ${running.baseBuildId}"
        }
        if (captured.engineAbi != running.engineAbi || captured.hostAbiHash != running.hostAbiHash) {
            return "patch.abi: captured on engine ABI ${captured.engineAbi}, this build has ${running.engineAbi}"
        }
        if (!settings.patchArtifact && captured.patchSetDigest != null) {
            return "patch.artifact_missing: captured with ${captured.patchSetDigest}; pass its archived artifact with --patch-artifact"
        }
    }
    if (settings.patchArtifact && running.patchSetDigest != settings.expectedPatchSetDigest) {
        return "patch.mismatch: loaded ${running.patchSetDigest}, expected ${settings.expectedPatchSetDigest}"
    }
    return null
}
