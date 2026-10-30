package dev.bugcapsule.android.emergency

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The pure part of emergency-client-v1: feature keys, version order and
 * targeting, rollout buckets and the reference evaluation (§6). No clock,
 * no I/O; every client reproduces these results exactly (shared vectors in
 * `spec-fixtures/emergency-v1`).
 */
internal object EmergencyContract {
    const val WIRE_FORMAT = "bugcapsule.emergency.v1"
    const val SIGNATURE_ALG = "ES256"
    const val SCHEMA_VERSION = 1L
    const val PLATFORM = "NATIVE_ANDROID"

    /** A document stays usable this long after its `expires_at` (device clock skew). */
    const val GRACE_MS = 300_000L

    const val POLICY_UNAVAILABLE = "POLICY_UNAVAILABLE"

    val ENVIRONMENT = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")
    private val FEATURE_KEY = Regex("^[a-z0-9_-]{1,64}(\\.[a-z0-9_-]{1,64}){0,7}$")
    private val POLICY_KEY = Regex("^[a-z0-9_-]{1,64}(\\.[a-z0-9_-]{1,64}){0,7}(\\.\\*)?$")

    fun isFeatureKey(v: String): Boolean = v.length <= 200 && FEATURE_KEY.matches(v)

    /** A feature key, or `prefix.*`. */
    fun isPolicyKey(v: String): Boolean = v.length <= 200 && POLICY_KEY.matches(v)

    /** Exact, or `prefix.*` covering every key below `prefix` (not `prefix` itself). */
    fun keyMatches(pattern: String, featureKey: String): Boolean =
        if (pattern.endsWith(".*")) featureKey.startsWith(pattern.substring(0, pattern.length - 1)) else pattern == featureKey

    // ---------------------------------------------------------------- versions

    class Version(val nums: List<Long>, val pre: List<String>?)

    private val VERSION = Regex(
        "^[vV]?(\\d{1,9}(?:\\.\\d{1,9}){0,5})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
    private val NUMERIC = Regex("^\\d+$")

    /** `v?N(.N){0,5}(-pre)?(+build)?`, surrounding whitespace ignored; null for anything else. */
    fun parseVersion(raw: String): Version? {
        val m = VERSION.matchEntire(raw.trim()) ?: return null
        val pre = m.groups[2]?.value
        return Version(m.groupValues[1].split('.').map { it.toLong() }, pre?.split('.'))
    }

    /** -1, 0 or 1; null when either side is not a version. Pre-releases compare as in SemVer §11. */
    fun compareVersions(a: String, b: String): Int? {
        val x = parseVersion(a) ?: return null
        val y = parseVersion(b) ?: return null
        for (i in 0 until maxOf(x.nums.size, y.nums.size)) {
            val d = (x.nums.getOrNull(i) ?: 0L).compareTo(y.nums.getOrNull(i) ?: 0L)
            if (d != 0) return Integer.signum(d)
        }
        val xp = x.pre
        val yp = y.pre
        if (xp == null && yp == null) return 0
        if (xp == null) return 1
        if (yp == null) return -1
        for (i in 0 until maxOf(xp.size, yp.size)) {
            val p = xp.getOrNull(i) ?: return -1
            val q = yp.getOrNull(i) ?: return 1
            val pn = NUMERIC.matches(p)
            val qn = NUMERIC.matches(q)
            val d = when {
                pn && qn -> BigInteger(p).compareTo(BigInteger(q))
                pn != qn -> if (pn) -1 else 1
                else -> p.compareTo(q)
            }
            if (d != 0) return Integer.signum(d)
        }
        return 0
    }

    /**
     * Whether [version] falls in any of [ranges] (inclusive bounds, null =
     * unbounded). No ranges, a null version or one that is not a version: targeted.
     * A bound that is not a version does not exclude anything, as in the reference.
     */
    fun versionTargeted(version: String?, ranges: List<EmergencyVersionRange>): Boolean {
        if (ranges.isEmpty()) return true
        if (version == null || parseVersion(version) == null) return true
        return ranges.any { r ->
            (r.min == null || (compareVersions(version, r.min) ?: 0) >= 0) &&
                (r.max == null || (compareVersions(version, r.max) ?: 0) <= 0)
        }
    }

    // ----------------------------------------------------------------- rollout

    /** 0..9999: the first 4 bytes of SHA-256(UTF-8 "seed:subject") as a big-endian uint32, mod 10000. */
    fun rolloutBucket(seed: String, subject: String): Int {
        val h = MessageDigest.getInstance("SHA-256").digest("$seed:$subject".toByteArray(Charsets.UTF_8))
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (v % 10_000).toInt()
    }

    /** A partial rollout without a subject includes it (fails safe). */
    fun inRollout(rolloutBp: Long, seed: String, subject: String?): Boolean {
        if (rolloutBp >= 10_000) return true
        if (rolloutBp <= 0) return false
        if (subject.isNullOrEmpty()) return true
        return rolloutBucket(seed, subject) < rolloutBp
    }

    // -------------------------------------------------------------- evaluation

    class Input(val platform: String, val appVersion: String?, val subject: String?, val nowMs: Long)

    /** The policy that decides [featureKey], or null (ALLOW). */
    fun winner(policies: List<EmergencyPolicy>, featureKey: String, input: Input): EmergencyPolicy? {
        var best: EmergencyPolicy? = null
        for (p in policies) {
            if (!keyMatches(p.key, featureKey)) continue
            val expires = p.expiresAtMs
            if (expires != null && expires <= input.nowMs) continue
            if (p.targetPlatforms.isNotEmpty() && input.platform !in p.targetPlatforms) continue
            if (!versionTargeted(input.appVersion, p.versionRanges)) continue
            if (!inRollout(p.rolloutBp, p.rolloutSeed, input.subject)) continue
            if (best == null || precedes(p, best, featureKey)) best = p
        }
        return best
    }

    /** Highest priority, then most restrictive action, then exact key over wildcard, then lowest policy_id. */
    private fun precedes(a: EmergencyPolicy, b: EmergencyPolicy, featureKey: String): Boolean {
        if (a.priority != b.priority) return a.priority > b.priority
        if (a.action != b.action) return a.action.restrictiveness > b.action.restrictiveness
        val ae = a.key == featureKey
        val be = b.key == featureKey
        if (ae != be) return ae
        return a.policyId < b.policyId
    }

    fun decide(
        policies: List<EmergencyPolicy>,
        documentRevision: Long,
        featureKey: String,
        input: Input,
        source: EmergencyDecisionSource,
    ): EmergencyDecision {
        val w = winner(policies, featureKey, input)
            ?: return EmergencyDecision(
                featureKey, true, EmergencyAction.ALLOW, null, null, null, documentRevision,
                null, null, null, false, source,
            )
        return EmergencyDecision(
            featureKey = featureKey,
            allowed = w.action == EmergencyAction.ALLOW,
            action = w.action,
            reasonCode = w.reasonCode,
            policyId = w.policyId,
            policyRevision = w.revision,
            documentRevision = documentRevision,
            publicUserMessage = w.publicUserMessage,
            requiredVersion = w.requiredVersion,
            fallback = w.fallback,
            fallbackAvailable = w.action == EmergencyAction.USE_SAFE_FALLBACK && w.fallback != null,
            source = source,
            severity = w.severity,
        )
    }

    /** A decision without a document: failure mode or replay. */
    fun fixed(featureKey: String, action: EmergencyAction, reasonCode: String?, source: EmergencyDecisionSource) =
        EmergencyDecision(
            featureKey, action == EmergencyAction.ALLOW, action, reasonCode, null, null, null,
            null, null, null, false, source,
        )

    /** While a capsule replays, a feature it has no decision for is allowed (§7.8). */
    fun replayAllowed(featureKey: String) = fixed(featureKey, EmergencyAction.ALLOW, null, EmergencyDecisionSource.REPLAY)

    /** Exact key first, then the longest matching `prefix.*`; [EmergencyFailureMode.FAIL_OPEN] otherwise. */
    fun failureModeFor(modes: Map<String, EmergencyFailureMode>, featureKey: String): EmergencyFailureMode {
        modes[featureKey]?.let { return it }
        var best: String? = null
        for (pattern in modes.keys) {
            if (!pattern.endsWith(".*") || !keyMatches(pattern, featureKey)) continue
            if (best == null || pattern.length > best.length) best = pattern
        }
        return best?.let { modes[it] } ?: EmergencyFailureMode.FAIL_OPEN
    }

    // -------------------------------------------------------------------- time

    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|([+-])(\\d{2}):(\\d{2}))$")

    /** ISO-8601 instant (`2026-10-29T08:00:00.000Z`, or with an offset) to epoch ms; null when invalid. Works on API 23. */
    fun parseInstant(s: String): Long? {
        val m = ISO.matchEntire(s) ?: return null
        val g = m.groupValues
        val year = g[1].toLong()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').substring(0, 3).toInt()
        var offsetMin = 0
        if (g[8] != "Z") {
            val oh = g[10].toInt()
            val om = g[11].toInt()
            if (oh > 23 || om > 59) return null
            offsetMin = (oh * 60 + om) * (if (g[9] == "-") -1 else 1)
        }
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute - offsetMin) * 60_000 + second * 1000L + millis
    }

    private fun daysInMonth(y: Long, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0L && y % 100 != 0L) || y % 400 == 0L) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
