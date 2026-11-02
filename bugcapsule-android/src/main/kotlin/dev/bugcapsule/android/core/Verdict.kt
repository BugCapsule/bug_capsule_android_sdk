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

    /**
     * verify (v2): baseline reproduced, every candidate attempt clean, but the
     * evidence is weak or the capsule is partial. Sent to the backend as
     * inconclusive, so it never satisfies a gate.
     */
    FIXED_QUALIFIED("inconclusive"),

    /** Replay containment was not kept (live traffic allowed): no conclusion. */
    UNSAFE("inconclusive"),
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

    /** 0 success, 1 the opposite, 2 flaky, 3 inconclusive / incompatible, 4 fixed with qualifications, 5 unsafe. */
    val exitCode: Int
        get() = when (verdict) {
            Verdict.REPRODUCED, Verdict.FIXED -> 0
            Verdict.NOT_REPRODUCED, Verdict.STILL_FAILING -> 1
            Verdict.FLAKY -> 2
            Verdict.INCOMPATIBLE, Verdict.INCONCLUSIVE -> 3
            Verdict.FIXED_QUALIFIED -> 4
            Verdict.UNSAFE -> 5
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
                return AttemptAssessment(r, AttemptStatus.REPRODUCED, emptyList(), (listOfNotNull(r.reasonCode) + r.divergences.filter { it.type == "failure.different_frame" }.map { it.type }).distinct().sorted())
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
                if (r.otherFailures.any { it.kind == FailureKind.CRASH } || (r.otherFailures.isNotEmpty() && r.expectedSignature?.startsWith("crash:") == true)) codes.add("failure.different")
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
        val unsafe = reports.any { r -> r.divergences.any { it.type == UNSAFE_LIVE_DIVERGENCE } }
        if (unsafe) reasons.add("live traffic was allowed during replay")
        val verdict = when {
            unsafe -> Verdict.UNSAFE
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

    // ---------------------------------------------------------- verdict v2

    /** Divergence a runner adds when live traffic was let through (`--allow-live`): the attempt proves nothing. */
    const val UNSAFE_LIVE_DIVERGENCE = "replay.unsafe_live"

    /** Baseline attempts below this never support FIXED. */
    const val MIN_BASELINE_ATTEMPTS = 3

    /** Candidate attempts are never fewer than this, nor more than [MAX_CANDIDATE_ATTEMPTS]. */
    const val MIN_CANDIDATE_ATTEMPTS = 5
    const val MAX_CANDIDATE_ATTEMPTS = 30

    /** Lower bound of the Wilson 95% interval of [successes] / [n]. */
    @JvmStatic
    fun wilsonLower(successes: Int, n: Int): Double {
        if (n <= 0) return 0.0
        val z = 1.959963984540054
        val p = successes.toDouble() / n
        val z2 = z * z
        val centre = p + z2 / (2 * n)
        val margin = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        return ((centre - margin) / (1 + z2 / n)).coerceIn(0.0, 1.0)
    }

    /** Candidate attempts so that a build still failing at the baseline's lower rate passes every attempt with probability below 1%. */
    @JvmStatic
    fun requiredCandidateAttempts(baselineReproduced: Int, baselineAttempts: Int): Int {
        val low = wilsonLower(baselineReproduced, baselineAttempts)
        if (low <= 0) return MAX_CANDIDATE_ATTEMPTS
        if (low >= 1) return MIN_CANDIDATE_ATTEMPTS
        val n = Math.ceil(Math.log(0.01) / Math.log(1 - low)).toInt()
        return n.coerceIn(MIN_CANDIDATE_ATTEMPTS, MAX_CANDIDATE_ATTEMPTS)
    }

    /**
     * `verify` under verdict v2 (Dart `evaluateVerifyV2`, spec/verdict-v2.md):
     * [candidate] are the candidate build's reports, [baseline] the baseline
     * build's result (null when none could be built). FIXED needs a baseline
     * that reproduced in every one of at least [MIN_BASELINE_ATTEMPTS]
     * attempts, at least [requiredCandidateAttempts] candidate attempts all
     * clean, strong positive evidence in each, and an eligible capsule. Weak
     * evidence or a partial capsule gives [Verdict.FIXED_QUALIFIED]; no
     * evidence is inconclusive.
     */
    @JvmStatic
    fun evaluateVerifyV2(
        requestedAttempts: Int,
        candidate: List<ReplayReport>,
        baseline: BaselineResult?,
        eligibility: EligibilityStatus,
    ): RunEvaluation {
        val v1 = evaluate(RunKind.VERIFY, requestedAttempts, candidate)
        val reasons = ArrayList(v1.reasons)
        val codes = java.util.TreeSet(v1.reasonCodes)
        fun result(verdict: Verdict, reason: String? = null, code: String? = null): RunEvaluation {
            if (reason != null) reasons.add(reason)
            if (code != null) codes.add(code)
            return RunEvaluation(RunKind.VERIFY, requestedAttempts, verdict, v1.attempts, reasons, codes.toList())
        }

        if (candidate.any { r -> r.divergences.any { it.type == UNSAFE_LIVE_DIVERGENCE } }) {
            return result(Verdict.UNSAFE, "live traffic was allowed during replay", "replay.unsafe")
        }
        if (v1.verdict == Verdict.INCOMPATIBLE || v1.verdict == Verdict.STILL_FAILING) return result(v1.verdict)
        if (baseline == null) {
            return result(
                Verdict.INCONCLUSIVE,
                "no baseline: the capsule's build could not be replayed, so nothing shows this harness reproduces the failure",
                "baseline.unavailable",
            )
        }
        if (baseline.reproduced == 0) {
            return result(
                Verdict.INCONCLUSIVE,
                "the baseline build did not reproduce the failure in ${baseline.attempts} attempts",
                "baseline.not_reproduced",
            )
        }
        if (baseline.reproduced < baseline.attempts) {
            return result(
                Verdict.FLAKY,
                "the baseline build reproduced in ${baseline.reproduced} of ${baseline.attempts} attempts",
                "baseline.flaky",
            )
        }
        if (baseline.attempts < MIN_BASELINE_ATTEMPTS) {
            return result(
                Verdict.INCONCLUSIVE,
                "the baseline ran ${baseline.attempts} attempts; at least $MIN_BASELINE_ATTEMPTS are needed",
                "baseline.too_few",
            )
        }
        val needed = requiredCandidateAttempts(baseline.reproduced, baseline.attempts)
        if (requestedAttempts < needed) {
            return result(
                Verdict.INCONCLUSIVE,
                "$requestedAttempts candidate attempts; $needed are needed after a ${baseline.reproduced}/${baseline.attempts} baseline",
                "attempts.too_few",
            )
        }
        if (v1.verdict != Verdict.FIXED) return result(v1.verdict)
        if (eligibility == EligibilityStatus.INELIGIBLE) {
            return result(Verdict.INCONCLUSIVE, "the capsule is ineligible for replay", "capsule.ineligible")
        }
        val levels = v1.attempts.map { it.report.evidence?.level ?: EvidenceLevel.NONE }
        if (levels.any { it == EvidenceLevel.NONE }) {
            val failed = v1.attempts.flatMap { it.report.evidence?.failedPostconditions ?: emptyList() }.toCollection(LinkedHashSet())
            if (failed.isNotEmpty()) {
                return result(
                    Verdict.STILL_FAILING,
                    "postconditions did not hold: ${failed.joinToString(", ")}",
                    "postcondition.failed",
                )
            }
            return result(
                Verdict.INCONCLUSIVE,
                "no positive evidence: nothing shows the failing path ran (update the SDK, or register a postcondition with BugCapsule.expect)",
                "evidence.none",
            )
        }
        val weak = levels.any { it == EvidenceLevel.WEAK }
        val partial = eligibility == EligibilityStatus.PARTIAL
        if (weak || partial) {
            return result(
                Verdict.FIXED_QUALIFIED,
                listOfNotNull(
                    if (weak) "only shows the recorded failure point was passed (no assertion or postcondition held)" else null,
                    if (partial) "the capsule is partial: some inputs were not recorded" else null,
                ).joinToString("; "),
                if (weak) "evidence.weak" else "capsule.partial",
            )
        }
        return result(Verdict.FIXED)
    }
}

/** The baseline build's result for a capsule (verdict v2). */
data class BaselineResult(
    val revision: String,
    val attempts: Int,
    val reproduced: Int,
    val cached: Boolean = false,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf("revision" to revision, "attempts" to attempts, "reproduced" to reproduced, "cached" to cached)

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = BaselineResult(
            revision = j["revision"] as String,
            attempts = J.int(j["attempts"])!!,
            reproduced = J.int(j["reproduced"])!!,
            cached = j["cached"] == true,
        )
    }
}
