package dev.bugcapsule.android.emergency

/**
 * What a guarded feature should do (emergency-client-v1 §4). Only [ALLOW]
 * lets the feature run as usual; every other action tells the app what to
 * show or do instead.
 */
enum class EmergencyAction {
    ALLOW,
    BLOCK,
    READ_ONLY,
    REQUIRE_UPDATE,
    SHOW_MAINTENANCE,
    DISABLE_FEATURE,
    USE_SAFE_FALLBACK,
    ;

    internal val restrictiveness: Int
        get() = when (this) {
            ALLOW -> 0
            USE_SAFE_FALLBACK -> 1
            READ_ONLY -> 2
            DISABLE_FEATURE -> 3
            SHOW_MAINTENANCE -> 4
            REQUIRE_UPDATE -> 5
            BLOCK -> 6
        }

    internal companion object {
        /** An action this SDK does not know is treated as [BLOCK] (fails safe). */
        fun fromWire(name: String): EmergencyAction = entries.firstOrNull { it.name == name } ?: BLOCK
    }
}

/** Where a decision came from (emergency-client-v1 §7.4). */
enum class EmergencyDecisionSource(
    /** The lower-case name used in the contract (`policy`, `stale_policy`, `failure_mode`, `replay`). */
    val wire: String,
) {
    /** A verified document that has not expired. */
    POLICY("policy"),

    /** An expired document, used because the key's mode is [EmergencyFailureMode.USE_LAST_VALID_POLICY]. */
    STALE_POLICY("stale_policy"),

    /** No usable document: the key's [EmergencyFailureMode] decided. */
    FAILURE_MODE("failure_mode"),

    /** BugCapsule is replaying a capsule: every feature is allowed and nothing is fetched. */
    REPLAY("replay"),
}

/** What a feature does when there is no usable policy document (emergency-client-v1 §7.4). */
enum class EmergencyFailureMode {
    /** Allow the feature (the default for every key). */
    FAIL_OPEN,

    /** Block the feature. Use it for actions that move money. */
    FAIL_CLOSED,

    /** Keep evaluating the last verified document after it expired; allow when there is none. */
    USE_LAST_VALID_POLICY,
}

/** Whether a verified policy document is loaded and still usable. */
enum class EmergencyState(val wire: String) {
    /** No verified document (never fetched, discarded, or the trust anchor is invalid). */
    NONE("none"),

    /** A verified document that has not expired (plus the 300 s grace). */
    USABLE("usable"),

    /** The last verified document has expired. */
    EXPIRED("expired"),
}

/**
 * Optional input of [BugCapsuleEmergency.evaluate].
 *
 * [subject] is the rollout identity. It defaults to the installation id. Pass
 * your own stable, pseudonymous user id when a rollout should follow the
 * user across devices. Never pass an email address or anything else that
 * identifies a person.
 */
class EmergencyContext @JvmOverloads constructor(
    val subject: String? = null,
) {
    override fun toString() = "EmergencyContext(subject=${if (subject == null) "null" else "…"})"
}

/** An inclusive version range; a null bound is unbounded. */
class EmergencyVersionRange internal constructor(
    val min: String?,
    val max: String?,
) {
    override fun toString() = "[${min ?: "*"}, ${max ?: "*"}]"
}

/** One policy of a verified document, as signed by the backend. */
class EmergencyPolicy internal constructor(
    val policyId: String,
    /** The feature key, or a `prefix.*` wildcard that covers every key below it. */
    val key: String,
    val revision: Long,
    /** Unknown actions are reported as [EmergencyAction.BLOCK]. */
    val action: EmergencyAction,
    val priority: Long,
    val severity: String?,
    val incidentId: String?,
    /** Empty: every platform. */
    val targetPlatforms: List<String>,
    /** Empty: every version. */
    val versionRanges: List<EmergencyVersionRange>,
    /** Basis points of subjects included (10000 = everyone). */
    val rolloutBp: Long,
    val rolloutSeed: String,
    val reasonCode: String,
    val publicUserMessage: String?,
    val requiredVersion: String?,
    val fallback: String?,
    /** Epoch ms after which this policy no longer applies; null: until the document expires. */
    val expiresAtMs: Long?,
) {
    override fun toString() = "EmergencyPolicy($policyId, $key, $action, rev $revision)"
}

/**
 * The result of [BugCapsuleEmergency.evaluate] (emergency-client-v1 §6).
 *
 * Show [publicUserMessage] when the feature is not [allowed]. With
 * [EmergencyAction.REQUIRE_UPDATE], [requiredVersion] is the first version that
 * no longer needs the restriction. With [EmergencyAction.USE_SAFE_FALLBACK],
 * [fallback] names the app-defined fallback to use.
 */
class EmergencyDecision internal constructor(
    val featureKey: String,
    /** True only for [EmergencyAction.ALLOW]. */
    val allowed: Boolean,
    val action: EmergencyAction,
    val reasonCode: String?,
    val policyId: String?,
    val policyRevision: Long?,
    /** Revision of the document the decision came from; null for failure-mode and replay decisions. */
    val documentRevision: Long?,
    val publicUserMessage: String?,
    val requiredVersion: String?,
    val fallback: String?,
    /** True only for [EmergencyAction.USE_SAFE_FALLBACK] with a [fallback]. */
    val fallbackAvailable: Boolean,
    val source: EmergencyDecisionSource,
    /**
     * Severity of the deciding policy (`P0`…), or null. Not a decision field
     * of the contract: [EmergencyUpdateRequest.from] uses it to tell urgent
     * `REQUIRE_UPDATE` decisions apart.
     */
    val severity: String? = null,
) {
    /** The snake_case decision fields of the contract (without `source`), for logs and the shared vectors. */
    internal fun toWire(): Map<String, Any?> = linkedMapOf(
        "feature_key" to featureKey,
        "allowed" to allowed,
        "action" to action.name,
        "reason_code" to reasonCode,
        "policy_id" to policyId,
        "policy_revision" to policyRevision,
        "document_revision" to documentRevision,
        "public_user_message" to publicUserMessage,
        "required_version" to requiredVersion,
        "fallback" to fallback,
        "fallback_available" to fallbackAvailable,
    )

    override fun toString() =
        "EmergencyDecision($featureKey: $action, source=${source.wire}, reason=$reasonCode, policy=$policyId, document=$documentRevision)"
}

/** The verified document listeners are told about. Policies are empty when [state] is [EmergencyState.NONE]. */
class EmergencySnapshot internal constructor(
    val state: EmergencyState,
    val revision: Long?,
    val issuedAtMs: Long?,
    val expiresAtMs: Long?,
    val policies: List<EmergencyPolicy>,
) {
    override fun toString() = "EmergencySnapshot(${state.wire}, revision=$revision, ${policies.size} policies)"
}

/** Called on the main thread after the policy snapshot changed. Exceptions are caught and logged. */
fun interface EmergencyListener {
    fun onPoliciesChanged(snapshot: EmergencySnapshot)
}

/** Diagnostics of the emergency module (emergency-client-v1 §7.5). */
class EmergencyStatus internal constructor(
    /** False until [BugCapsuleEmergency.initialize] was called. */
    val initialized: Boolean,
    val state: EmergencyState,
    /** Revision of the current verified document. */
    val revision: Long?,
    val issuedAtMs: Long?,
    val expiresAtMs: Long?,
    /** When the current document was received (device clock). */
    val receivedAtMs: Long?,
    /** When the last fetch finished, successful or not. */
    val lastFetchAtMs: Long?,
    /** Why the last fetch or document failed; null after a success. */
    val lastError: String?,
    /** The backend refused the credential (401/403/404): no more scheduled fetches in this process. */
    val credentialRejected: Boolean,
    /** False when the trust anchor could not be parsed: every key uses its failure mode. */
    val trustAnchorValid: Boolean,
    val environment: String?,
    /** BugCapsule is replaying a capsule: evaluations return ALLOW and nothing is fetched. */
    val replaying: Boolean,
) {
    override fun toString() =
        "EmergencyStatus(initialized=$initialized, state=${state.wire}, revision=$revision, lastError=$lastError, " +
            "credentialRejected=$credentialRejected, trustAnchorValid=$trustAnchorValid, replaying=$replaying)"
}
