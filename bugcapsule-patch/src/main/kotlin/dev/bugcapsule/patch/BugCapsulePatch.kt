package dev.bugcapsule.patch

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A module the app ships a fallback for, and that patches may replace. The
 * schemas are canonical type text (`int`, `dec(2)`, `rec(a:int,b:str)`); a
 * patch must implement exactly these to activate.
 */
class PatchModule<I, O>(
    val id: String,
    val inputSchema: String,
    val outputSchema: String,
    val encode: (I) -> Any?,
    val decode: (Any?) -> O,
    /** The bundled implementation. Runs whenever no patch applies or one fails. */
    val fallback: (I) -> O,
)

/** What the installed binary can render for a surface; part of the base capability hash. */
data class PatchSurfaceContract(
    val id: String,
    val components: List<String>,
    val actions: List<String>,
    /** Actions a layout must keep. A layout dropping one is refused. */
    val requiredActions: List<String> = emptyList(),
    val assets: List<String> = emptyList(),
)

enum class PatchSource { PATCHED, BUNDLED }

data class PatchResult<O>(
    val value: O,
    val source: PatchSource,
    /** Why the bundled implementation ran: `not_patched` or an error code. */
    val reason: String? = null,
)

/** Identity of the installed binary and where its patches come from. */
data class PatchConfig(
    val storeDir: String,
    val projectId: String,
    val applicationId: String,
    val environment: String,
    val distributionProfile: String,
    /** Generated at build time; never the marketing version. */
    val baseBuildId: String,
    /** Stable pseudonymous id for cohort assignment. */
    val installationId: String,
    /** Pinned `bcpk.v1.…` keys. */
    val trustRoots: List<String>,
    val platform: String = "android",
)

class PatchException(val code: String, message: String) : Exception(message)

/** Replay pinning: the archived files an attempt loads, read-only and offline. A null [bundlePath] is the bare base. */
data class PatchReplay(val bundlePath: String? = null, val keyringPath: String? = null, val keyringSignaturePath: String? = null)

/**
 * The runtime as the app sees it. One generation is pinned at [open] for the
 * life of the process: staging never changes running behavior. This replaces
 * [PatchModule]s only; compiled Kotlin and Java are never replaced.
 */
class BugCapsulePatch private constructor(
    private var handle: Long,
    /** Why this build runs fallbacks only; null when the runtime is up. */
    val unavailableReason: String?,
) {
    val available: Boolean get() = handle != 0L

    /** Runs the patched module when there is one, else the bundled fallback. Never throws for a patch failure. */
    fun <I, O> call(module: PatchModule<I, O>, input: I, timeoutMs: Long = 50): PatchResult<O> {
        if (!available) return PatchResult(module.fallback(input), PatchSource.BUNDLED, unavailableReason)
        return try {
            val body = invoke(NativeBridge.OP_EVAL, module.id.toByteArray(), JSONObject.wrap(module.encode(input)).toString().toByteArray(), timeoutMs)
            PatchResult(module.decode(JSONObject(String(body)).get("output")), PatchSource.PATCHED)
        } catch (e: PatchException) {
            PatchResult(module.fallback(input), PatchSource.BUNDLED, e.code)
        } catch (_: org.json.JSONException) {
            PatchResult(module.fallback(input), PatchSource.BUNDLED, "bad_output")
        }
    }

    fun status(): JSONObject = json(NativeBridge.OP_STATUS)

    /** Verifies a `.bcpatch` and makes it the next launch's candidate. */
    fun stage(bundle: ByteArray): JSONObject = json(NativeBridge.OP_STAGE, bundle)

    /** Applies signed channel metadata; the answer says what to do next. */
    fun applyChannel(document: ByteArray, signature: ByteArray): JSONObject = json(NativeBridge.OP_CHANNEL, document, signature)

    fun applyKeyring(document: ByteArray, signature: ByteArray): JSONObject = json(NativeBridge.OP_KEYRING, document, signature)

    /** Call after the first usable screen and module smoke checks. Downloading is not health. */
    fun markHealthy(checkpoint: String): JSONObject = json(NativeBridge.OP_HEALTHY, checkpoint.toByteArray())

    /** Reports a fault attributed to the running patch, such as a crash. */
    fun reportFault(kind: String): JSONObject = json(NativeBridge.OP_FAULT, kind.toByteArray())

    fun receipts(): JSONObject = json(NativeBridge.OP_RECEIPTS)

    fun ackReceipts(upTo: Long): JSONObject = json(NativeBridge.OP_ACK, n = upTo)

    /** A patched asset, or null when this generation does not override [id]. */
    fun asset(id: String): ByteArray? = try {
        invoke(NativeBridge.OP_ASSET, id.toByteArray())
    } catch (_: PatchException) {
        null
    }

    /** The patched document for a registered surface, or null to use the bundled layout. */
    fun surface(id: String): JSONObject? = try {
        JSONObject(String(invoke(NativeBridge.OP_SURFACE, id.toByteArray())))
    } catch (_: PatchException) {
        null
    }

    fun close() {
        if (available) NativeBridge.close(handle)
        handle = 0
    }

    private fun json(op: Int, a: ByteArray? = null, b: ByteArray? = null, n: Long = 0) = JSONObject(String(invoke(op, a, b, n)))

    private fun invoke(op: Int, a: ByteArray? = null, b: ByteArray? = null, n: Long = 0): ByteArray {
        if (!available) throw PatchException("unavailable", unavailableReason ?: "closed")
        return unpack(NativeBridge.call(handle, op, a, b, n))
    }

    companion object {
        /** Opens the store and selects this process's generation. Never throws: without a runtime, every call uses the fallback. */
        fun open(config: PatchConfig, modules: List<PatchModule<*, *>>, surfaces: List<PatchSurfaceContract> = emptyList(), replay: PatchReplay? = null): BugCapsulePatch {
            if (!NativeBridge.loaded) return BugCapsulePatch(0, "runtime_missing")
            if (NativeBridge.abiVersion() != NativeBridge.ABI_VERSION) return BugCapsulePatch(0, "abi_mismatch")
            val json = JSONObject()
                .put("storeDir", config.storeDir)
                .put("projectId", config.projectId)
                .put("applicationId", config.applicationId)
                .put("environment", config.environment)
                .put("platform", config.platform)
                .put("distributionProfile", config.distributionProfile)
                .put("baseBuildId", config.baseBuildId)
                .put("installationId", config.installationId)
                .put("trustRoots", JSONArray(config.trustRoots))
                .put("modules", JSONArray(modules.map { JSONObject().put("id", it.id).put("input", it.inputSchema).put("output", it.outputSchema) }))
                .put(
                    "surfaces",
                    JSONArray(
                        surfaces.map {
                            JSONObject().put("id", it.id).put("components", JSONArray(it.components)).put("actions", JSONArray(it.actions))
                                .put("requiredActions", JSONArray(it.requiredActions)).put("assets", JSONArray(it.assets))
                        },
                    ),
                )
            if (replay != null) {
                json.put(
                    "replay",
                    JSONObject().put("bundlePath", replay.bundlePath ?: JSONObject.NULL).apply {
                        if (replay.keyringPath != null) put("keyringPath", replay.keyringPath).put("keyringSignaturePath", replay.keyringSignaturePath)
                    },
                )
            }
            return try {
                val payload = unpack(NativeBridge.open(json.toString().toByteArray()))
                BugCapsulePatch(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).long, null)
            } catch (e: PatchException) {
                BugCapsulePatch(0, e.code)
            }
        }

        private fun unpack(r: ByteArray): ByteArray {
            val payload = r.copyOfRange(1, r.size)
            if (r[0].toInt() != 0) {
                val e = JSONObject(String(payload))
                throw PatchException(e.getString("code"), e.getString("message"))
            }
            return payload
        }
    }
}
