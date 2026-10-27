package dev.bugcapsule.android.core

import java.security.SecureRandom

/** Replay protocol v1.1 between the `bugcapsule` CLI and an app (runtimes-v1 §10). */
object ReplayProtocol {
    const val ENV_URL = "BUGCAPSULE_REPLAY_URL"

    /** Instrumentation argument carrying the armed base URL. */
    const val INSTRUMENTATION_ARG = "bugcapsuleReplayUrl"

    /** Instrumentation status key of the fallback channel. */
    const val STATUS_KEY = "bugcapsule_result"
    const val CAPSULE_PATH = "/capsule"
    const val SETTINGS_PATH = "/settings"
    const val RESULT_PATH = "/result"
    const val STDOUT_PREFIX = "BUGCAPSULE_RESULT::"
}

enum class ReplayStrategy(val wire: String) {
    AUTO("auto"), POINTER("pointer"), SEMANTIC("semantic");

    companion object {
        @JvmStatic
        fun parse(wire: String?): ReplayStrategy = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

data class ReplaySettings(
    val strategy: ReplayStrategy = ReplayStrategy.AUTO,
    val settleTimeoutMs: Long = 8000,
    val attempt: Int = 1,
    val kind: String = "replay",
    val strictNetwork: Boolean = true,
    val idleCompression: Boolean = true,
    val attemptId: String? = null,
    val runtime: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "strategy" to strategy.wire,
        "settleTimeoutMs" to settleTimeoutMs,
        "attempt" to attempt,
        "kind" to kind,
        "strictNetwork" to strictNetwork,
        "idleCompression" to idleCompression,
    ).also {
        if (attemptId != null) it["attemptId"] = attemptId
        if (runtime != null) it["runtime"] = runtime
    }

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = ReplaySettings(
            strategy = ReplayStrategy.parse(J.str(j["strategy"])),
            settleTimeoutMs = J.long(j["settleTimeoutMs"]) ?: 8000,
            attempt = J.int(j["attempt"]) ?: 1,
            kind = J.str(j["kind"]) ?: "replay",
            strictNetwork = J.bool(j["strictNetwork"]) ?: true,
            idleCompression = J.bool(j["idleCompression"]) ?: true,
            attemptId = J.str(j["attemptId"]),
            runtime = J.str(j["runtime"]),
        )
    }
}

enum class ReplayOutcome(val wire: String) {
    REPRODUCED("reproduced"),
    NOT_REPRODUCED("not_reproduced"),
    INCOMPATIBLE("incompatible"),
    NO_ASSERTION("no_assertion"),

    /**
     * The attempt proves nothing either way; [ReplayReport.reasonCode] says
     * why (crash capsules: `failure.different`, the app died of another
     * crash type). Older readers parse it as `error`, which is also invalid.
     */
    INCONCLUSIVE("inconclusive"),
    ERROR("error");

    companion object {
        @JvmStatic
        fun parse(wire: String?): ReplayOutcome = entries.firstOrNull { it.wire == wire } ?: ERROR
    }
}

data class Divergence(val type: String, val detail: String, val t: Long? = null) {
    fun toJson(): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type, "detail" to detail).also { if (t != null) it["t"] = t }

    override fun toString() = "$type: $detail"

    companion object {
        fun fromJson(j: Map<String, Any?>) = Divergence(j["type"] as String, j["detail"] as String, J.long(j["t"]))
    }
}

data class ReplayReport(
    val outcome: ReplayOutcome,
    val attempt: Int,
    val durationMs: Long,
    val strategy: ReplayStrategy,
    val expectedSignature: String? = null,
    val observed: FailureRecord? = null,
    val otherFailures: List<FailureRecord> = emptyList(),
    val divergences: List<Divergence> = emptyList(),
    val eventsDispatched: Int = 0,
    val eventsExpected: Int? = null,
    val fixturesServed: Int = 0,
    val compressedMs: Long = 0,
    val gapsCompressed: Int = 0,
    val buildGitSha: String? = null,
    val message: String? = null,
    val attemptId: String? = null,
    val runtime: String? = null,
    /** Machine-readable reason (`failure.different`, `failure.different_frame`); null when there is none. */
    val reasonCode: String? = null,
    /** Crash capsules: how the app under test died (`{mechanism, type, signal?, pid?, source, inAppFrame?}`), null while it lives. */
    val death: Map<String, Any?>? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "outcome" to outcome.wire,
        "attempt" to attempt,
        "durationMs" to durationMs,
        "strategy" to strategy.wire,
        "expectedSignature" to expectedSignature,
        "observed" to observed?.toJson(),
        "otherFailures" to otherFailures.map { it.toJson() },
        "divergences" to divergences.map { it.toJson() },
        "eventsDispatched" to eventsDispatched,
        "eventsExpected" to eventsExpected,
        "fixturesServed" to fixturesServed,
        "compressedMs" to compressedMs,
        "gapsCompressed" to gapsCompressed,
        "buildGitSha" to buildGitSha,
        "message" to message,
    ).also {
        if (attemptId != null) it["attemptId"] = attemptId
        if (runtime != null) it["runtime"] = runtime
        if (reasonCode != null) it["reasonCode"] = reasonCode
        if (death != null) it["death"] = death
    }

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = ReplayReport(
            outcome = ReplayOutcome.parse(J.str(j["outcome"])),
            attempt = J.int(j["attempt"]) ?: 1,
            durationMs = J.long(j["durationMs"]) ?: 0,
            strategy = ReplayStrategy.parse(J.str(j["strategy"])),
            expectedSignature = J.str(j["expectedSignature"]),
            observed = J.obj(j["observed"])?.let { FailureRecord.fromJson(it) },
            otherFailures = (J.list(j["otherFailures"]) ?: emptyList()).map { FailureRecord.fromJson(J.obj(it)!!) },
            divergences = (J.list(j["divergences"]) ?: emptyList()).map { Divergence.fromJson(J.obj(it)!!) },
            eventsDispatched = J.int(j["eventsDispatched"]) ?: 0,
            eventsExpected = J.int(j["eventsExpected"]),
            fixturesServed = J.int(j["fixturesServed"]) ?: 0,
            compressedMs = J.long(j["compressedMs"]) ?: 0,
            gapsCompressed = J.int(j["gapsCompressed"]) ?: 0,
            buildGitSha = J.str(j["buildGitSha"]),
            message = J.str(j["message"]),
            attemptId = J.str(j["attemptId"]),
            runtime = J.str(j["runtime"]),
            reasonCode = J.str(j["reasonCode"]),
            death = J.obj(j["death"]),
        )
    }
}

object Ids {
    private val secure = SecureRandom()

    /** RFC 4122 version 4 UUID. */
    @JvmStatic
    fun uuidV4(): String {
        val b = ByteArray(16).also { secure.nextBytes(it) }
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** Random unsigned 32-bit seed for the session's deterministic random. */
    @JvmStatic
    fun newSeed(): Long = secure.nextInt().toLong() and 0xFFFFFFFFL
}
