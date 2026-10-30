package dev.bugcapsule.patch

import dev.bugcapsule.android.core.PatchBinding
import dev.bugcapsule.android.core.PatchIdentity
import java.io.File
import java.util.UUID

/**
 * Opens the runtime for BugCapsule: normally from the device store, and in a
 * replay attempt pinned to one exact artifact. Pass it to
 * `BugCapsuleConfig.Builder.patchBinding`, then use [patch].
 */
class BugCapsulePatchBinding(
    private val config: PatchConfig,
    private val modules: List<PatchModule<*, *>>,
    private val surfaces: List<PatchSurfaceContract> = emptyList(),
) : PatchBinding {
    private var opened: BugCapsulePatch? = null

    /** The runtime of this process. */
    @get:Synchronized
    val patch: BugCapsulePatch
        get() = opened ?: BugCapsulePatch.open(config, modules, surfaces).also { opened = it }

    override val identity: PatchIdentity? get() = identityOf(patch)

    private fun identityOf(p: BugCapsulePatch): PatchIdentity? {
        if (!p.available) return null
        val s = p.status()
        val running = s.optJSONObject("running")
        return PatchIdentity(
            baseBuildId = s.getString("baseBuildId"),
            patchSetDigest = running?.getString("patchSetDigest"),
            engineAbi = s.getInt("engineAbi"),
            hostAbiHash = s.getString("hostAbiHash"),
            artifactDigest = running?.getString("artifactDigest"),
        )
    }

    @Synchronized
    override fun pinForReplay(bundle: ByteArray?, keyring: ByteArray?, keyringSignature: ByteArray?): PatchIdentity? {
        require((keyring == null) == (keyringSignature == null)) { "a keyring needs its signature" }
        val dir = File("${config.storeDir}-replay-${UUID.randomUUID()}").apply { mkdirs() }
        fun write(name: String, bytes: ByteArray?) = bytes?.let { File(dir, name).apply { writeBytes(it) }.path }
        val replay = PatchReplay(write("artifact.bcpatch", bundle), write("keyring.json", keyring), write("keyring.sig", keyringSignature))
        val p = BugCapsulePatch.open(config, modules, surfaces, replay)
        check(p.available) { "the pinned artifact was refused: ${p.unavailableReason}" }
        val handle = opened
        if (handle == null) opened = p else handle.adoptReplay(p)
        return identityOf(opened!!)
    }
}
