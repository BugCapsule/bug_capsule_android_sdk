package dev.bugcapsule.android.core

/** The dependency a capsule captured with a patch loaded must require (capsule-v2 §10). */
const val PATCH_RUNTIME_DEPENDENCY = "patch.runtime"
const val PATCH_RUNTIME_ADAPTER = "bugcapsule.patch_runtime"

/** The adapter name the Flutter SDK writes; capsules cross runtimes in tools. */
const val PATCH_RUNTIME_ADAPTER_FLUTTER = "bug_capsule.patch_runtime"
const val PATCH_RUNTIME_SCHEMA = 1

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
                "schema" to PATCH_RUNTIME_SCHEMA,
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
    val required = manifest.dependencies.filter { it.required && it.kind == PATCH_RUNTIME_DEPENDENCY }
    for (d in required) {
        if (d.adapter != PATCH_RUNTIME_ADAPTER && d.adapter != PATCH_RUNTIME_ADAPTER_FLUTTER) {
            return "dependency.unsupported: ${d.adapter} (dependency ${d.id}) is not a known patch runtime"
        }
        if (d.schema != PATCH_RUNTIME_SCHEMA) return "dependency.schema: ${d.adapter} cannot replay schema ${d.schema}; update the SDK"
        if (captured == null) return "patch.identity_missing: the capsule requires a patch runtime but records no identity"
    }
    val needsRuntime = captured?.patchSetDigest != null || required.isNotEmpty()
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
    // A baseline reproduces what was captured; only a verify run may try a candidate.
    if (settings.kind != "verify" && running.patchSetDigest != captured?.patchSetDigest) {
        return "patch.mismatch: a baseline must run the captured ${captured?.patchSetDigest ?: "base"}, this attempt loaded ${running.patchSetDigest ?: "base"}"
    }
    return null
}
