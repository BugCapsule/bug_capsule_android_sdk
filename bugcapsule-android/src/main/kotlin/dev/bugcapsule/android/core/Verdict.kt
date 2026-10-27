package dev.bugcapsule.android.core

/**
 * Attempt classification and batch verdicts, a port of the Dart reference
 * (`verdict.dart`), held to it by `spec-fixtures/conformance/verdicts.json`.
 * The CLI decides verdicts; the SDK uses the same rules to explain a replay
 * report (and in the adapter conformance helper).
 */
enum class RunKind { REPLAY, VERIFY }

enum class Verdict(val wire: String) {
    REPRODUCED("reproduced"),
    NOT_REPRODUCED("not_reproduced"),
    FIXED("not_reproduced"),
    STILL_FAILING("reproduced"),
    FLAKY("flaky"),
    INCOMPATIBLE("incompatible"),
    INCONCLUSIVE("inconclusive");

    /** The Dart enum name (`notReproduced`, `stillFailing`, ...). */
    val dartName: String
        get() = name.lowercase().split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")
}

enum class AttemptStatus { REPRODUCED, CLEAN, INVALID }

/**
 * Separates divergences that are expected between a recording and a replay
 * from those that make a "the failure did not happen" result meaningless.
 * Every type not in [EXPECTED], including unknown ones, is material.
 */
object DivergencePolicy {
    @JvmField
    val EXPECTED: Set<String> = setOf(
        "build.gitSha",
        "screen.size",
        "network.unused",
        "ws.unusedAfterFailure",
        "platform.unusedAfterFailure",
        "native.unusedAfterFailure",
    )

    @JvmStatic
    fun isMaterial(type: String): Boolean = type !in EXPECTED

    /** The capsule-v2 §8 reason code of a material interaction divergence, or null for other types. */
    @JvmStatic
    fun reasonCode(type: String): String? {
        if (type == "dependency.live" || type == "dependency.late") return "interaction.live"
        val family = type.substringBefore('.')
        if (family != "ws" && family != "platform" && family != "native") return null
        return when (type.substring(family.length + 1)) {
            "unused", "undelivered" -> "interaction.missing"
            "unrecorded" -> "interaction.unexpected"
            "mismatch" -> "interaction.mismatch"
            "noPayload" -> "interaction.incomplete"
            "unrestored" -> "interaction.unrestored"
            else -> null
        }
    }
}

data class AttemptAssessment(
    val report: ReplayReport,
    val status: AttemptStatus,
    /** Why an attempt is invalid (empty otherwise). */
    val reasons: List<String>,
    /** Interaction reason codes (capsule-v2 §8), sorted. */
    val reasonCodes: List<String> = emptyList(),
)

data class RunEvaluation(
    val kind: RunKind,
    val requestedAttempts: Int,
    val verdict: Verdict,
    val attempts: List<AttemptAssessment>,
    val reasons: List<String>,
    val reasonCodes: List<String>,
) {
    val reproductions: Int get() = attempts.count { it.status == AttemptStatus.REPRODUCED }
    val cleanAttempts: Int get() = attempts.count { it.status == AttemptStatus.CLEAN }
    val validAttempts: Int get() = reproductions + cleanAttempts

    /** 0 success, 1 the opposite, 2 flaky, 3 inconclusive / incompatible. */
    val exitCode: Int
        get() = when (verdict) {
            Verdict.REPRODUCED, Verdict.FIXED -> 0
            Verdict.NOT_REPRODUCED, Verdict.STILL_FAILING -> 1
            Verdict.FLAKY -> 2
            Verdict.INCOMPATIBLE, Verdict.INCONCLUSIVE -> 3
        }
}

object Verdicts {
    /** Classifies one attempt (see the Dart `assessAttempt`). */
    @JvmStatic
    fun assess(r: ReplayReport): AttemptAssessment {
        val reasons = ArrayList<String>()
        val codes = sortedSetOf<String>()
        when (r.outcome) {
            ReplayOutcome.REPRODUCED -> {
                if (r.expectedSignature == null) {
                    reasons.add("reported a reproduction without an expected failure")
                    return AttemptAssessment(r, AttemptStatus.INVALID, reasons)
                }
                // `failure.different_frame` (crash capsules): the same crash type in another in-app frame.
                return AttemptAssessment(r, AttemptStatus.REPRODUCED, emptyList(), listOfNotNull(r.reasonCode))
            }
            ReplayOutcome.INCONCLUSIVE -> {
                reasons.add("inconclusive: ${r.message ?: r.reasonCode ?: "no reason given"}")
                r.reasonCode?.let { codes.add(it) }
            }
            ReplayOutcome.INCOMPATIBLE -> {
                reasons.add("incompatible: ${r.message ?: "capsule cannot run against this build"}")
                if (r.message?.startsWith("dependency.") == true) codes.add("dependency.unsupported")
            }
            ReplayOutcome.NO_ASSERTION -> reasons.add("capsule has no failure assertion")
            ReplayOutcome.ERROR -> reasons.add("replay error: ${r.message ?: "unknown"}")
            ReplayOutcome.NOT_REPRODUCED -> {
                if (r.expectedSignature == null) reasons.add("no expected failure to check")
                val expected = r.eventsExpected
                if (expected != null && r.eventsDispatched < expected) {
                    reasons.add("only ${r.eventsDispatched} of $expected recorded inputs were replayed")
                }
                for (f in r.otherFailures) reasons.add("unexpected failure: ${f.signature}")
                val material = LinkedHashSet<String>()
                for (d in r.divergences) if (DivergencePolicy.isMaterial(d.type)) material.add(d.type)
                for (type in material) {
                    val n = r.divergences.count { it.type == type }
                    reasons.add("divergence $type${if (n > 1) " ×$n" else ""}")
                    DivergencePolicy.reasonCode(type)?.let { codes.add(it) }
                }
            }
        }
        return AttemptAssessment(
            r,
            if (reasons.isEmpty()) AttemptStatus.CLEAN else AttemptStatus.INVALID,
            reasons,
            codes.toList(),
        )
    }

    /** The verdict rules shared by `bugcapsule run`, `verify` and `regress run` (Dart `evaluateRun`). */
    @JvmStatic
    fun evaluate(kind: RunKind, requestedAttempts: Int, reports: List<ReplayReport>): RunEvaluation {
        val attempts = reports.map { assess(it) }
        val reasons = LinkedHashSet<String>()
        for (a in attempts) reasons.addAll(a.reasons)
        if (reports.size > requestedAttempts) reasons.add("${reports.size} reports for $requestedAttempts requested attempts")
        val missing = requestedAttempts - attempts.size
        if (missing > 0) reasons.add("$missing of $requestedAttempts requested attempts did not run")
        if (requestedAttempts <= 0) reasons.add("no attempts were requested")

        val n = requestedAttempts
        val r = attempts.count { it.status == AttemptStatus.REPRODUCED }
        val c = attempts.count { it.status == AttemptStatus.CLEAN }
        val verdict = when {
            reports.any { it.outcome == ReplayOutcome.INCOMPATIBLE } -> Verdict.INCOMPATIBLE
            n <= 0 || reports.size > n -> Verdict.INCONCLUSIVE
            kind == RunKind.REPLAY -> when {
                r == n -> Verdict.REPRODUCED
                c == n -> Verdict.NOT_REPRODUCED
                r > 0 && c > 0 -> Verdict.FLAKY
                else -> Verdict.INCONCLUSIVE
            }
            else -> when {
                c == n -> Verdict.FIXED
                r > 0 && c > 0 -> Verdict.FLAKY
                r > 0 -> Verdict.STILL_FAILING
                else -> Verdict.INCONCLUSIVE
            }
        }
        return RunEvaluation(
            kind,
            requestedAttempts,
            verdict,
            attempts,
            reasons.toList(),
            attempts.flatMap { it.reasonCodes }.toSortedSet().toList(),
        )
    }
}
