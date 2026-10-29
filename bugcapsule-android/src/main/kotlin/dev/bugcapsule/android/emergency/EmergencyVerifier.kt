package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.ConnectionToken
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** Verification and acceptance reason codes (emergency-client-v1 §4, §5), as sent in FAILED receipts. */
internal object Reasons {
    const val MALFORMED = "malformed"
    const val UNSUPPORTED_FORMAT = "unsupported_format"
    const val UNKNOWN_KEY = "unknown_key"
    const val BAD_SIGNATURE = "bad_signature"
    const val UNSUPPORTED_SCHEMA = "unsupported_schema"
    const val WRONG_APPLICATION = "wrong_application"
    const val WRONG_ENVIRONMENT = "wrong_environment"
    const val STALE_REVISION = "stale_revision"
    const val EXPIRED_ON_ARRIVAL = "expired_on_arrival"
}

/** The application id and the ES256 public keys an app trusts (emergency-client-v1 §2). */
internal class TrustAnchor(val app: String, val keys: Map<String, PublicKey>) {
    sealed class Parsed {
        class Valid(val anchor: TrustAnchor) : Parsed()
        class Invalid(val message: String) : Parsed()
    }

    companion object {
        const val PREFIX = "bcek.v1."

        /** Order of the P-256 group (secp256r1 `n`). */
        private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

        /** Offline, never throws. */
        fun parse(raw: String?): Parsed {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return Parsed.Invalid("no trust anchor configured; copy the bcek.v1.… anchor from the dashboard")
            if (!s.startsWith(PREFIX)) return Parsed.Invalid("not an emergency trust anchor (expected bcek.v1.…)")
            val bytes = ConnectionToken.decodeBase64Url(s.substring(PREFIX.length))
                ?: return Parsed.Invalid("the trust anchor is damaged (not base64url)")
            val json = try {
                J.obj(Json.parseUtf8(bytes))
            } catch (_: Exception) {
                null
            } ?: return Parsed.Invalid("the trust anchor is damaged (not a JSON object)")
            val app = J.str(json["app"])?.takeIf { it.isNotEmpty() } ?: return Parsed.Invalid("the trust anchor has no application id")
            val list = J.list(json["keys"])?.takeIf { it.isNotEmpty() } ?: return Parsed.Invalid("the trust anchor has no keys")
            val keys = LinkedHashMap<String, PublicKey>()
            for (item in list) {
                val k = J.obj(item) ?: return Parsed.Invalid("the trust anchor has a malformed key")
                val kid = J.str(k["kid"])?.takeIf { it.isNotEmpty() } ?: return Parsed.Invalid("a trust anchor key has no kid")
                if (k["alg"] != SIGNATURE_ALG) return Parsed.Invalid("trust anchor key $kid is not ES256")
                val spki = J.str(k["spki"])?.let { ConnectionToken.decodeBase64Url(it) }
                    ?: return Parsed.Invalid("trust anchor key $kid has no public key")
                keys[kid] = p256Key(spki) ?: return Parsed.Invalid("trust anchor key $kid is not an ECDSA P-256 public key")
            }
            return Parsed.Valid(TrustAnchor(app, keys))
        }

        private const val SIGNATURE_ALG = EmergencyContract.SIGNATURE_ALG

        private fun p256Key(spki: ByteArray): PublicKey? = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val params = (key as? ECPublicKey)?.params
            if (params != null && params.curve.field.fieldSize == 256 && params.order == P256_ORDER) key else null
        } catch (_: Exception) {
            null
        }
    }
}

/** A verified, well-formed policy document (emergency-client-v1 §4). */
internal class PolicyDocument(
    val applicationId: String,
    val environment: String,
    val revision: Long,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val policies: List<EmergencyPolicy>,
)

internal sealed class Verification {
    class Ok(val document: PolicyDocument) : Verification()

    /** [revision] is known only when the payload was verified and carries one. */
    class Failed(val reason: String, val revision: Long? = null) : Verification()
}

/**
 * Envelope verification in the order of emergency-client-v1 §4. The key
 * comes from the embedded anchor only; the signature is checked over the
 * exact decoded payload bytes, which are parsed only afterwards.
 */
internal object EnvelopeVerifier {
    fun verify(raw: Any?, anchor: TrustAnchor, environment: String): Verification {
        val e = J.obj(raw) ?: return Verification.Failed(Reasons.MALFORMED)
        val kid = e["kid"] as? String
        val payload = e["payload"] as? String
        val signature = e["signature"] as? String
        if (kid == null || payload == null || signature == null) return Verification.Failed(Reasons.MALFORMED)
        if (e["format"] != EmergencyContract.WIRE_FORMAT || e["alg"] != EmergencyContract.SIGNATURE_ALG) {
            return Verification.Failed(Reasons.UNSUPPORTED_FORMAT)
        }
        val key = anchor.keys[kid] ?: return Verification.Failed(Reasons.UNKNOWN_KEY)
        val bytes = ConnectionToken.decodeBase64Url(payload) ?: return Verification.Failed(Reasons.BAD_SIGNATURE)
        val sig = ConnectionToken.decodeBase64Url(signature) ?: return Verification.Failed(Reasons.BAD_SIGNATURE)
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(bytes)
                verify(sig)
            }
        } catch (_: Exception) {
            false
        }
        if (!valid) return Verification.Failed(Reasons.BAD_SIGNATURE)
        val doc = try {
            J.obj(Json.parseUtf8(bytes))
        } catch (_: Exception) {
            null
        } ?: return Verification.Failed(Reasons.MALFORMED)
        val revision = integer(doc["revision"])
        if (integer(doc["schema_version"]) != EmergencyContract.SCHEMA_VERSION) return Verification.Failed(Reasons.UNSUPPORTED_SCHEMA, revision)
        if (doc["application_id"] != anchor.app) return Verification.Failed(Reasons.WRONG_APPLICATION, revision)
        if (doc["environment"] != environment) return Verification.Failed(Reasons.WRONG_ENVIRONMENT, revision)
        val parsed = parseDocument(doc) ?: return Verification.Failed(Reasons.MALFORMED, revision)
        return Verification.Ok(parsed)
    }

    /** Field types of the document and its policies; null when any field is malformed. Unknown fields are ignored. */
    fun parseDocument(doc: Map<String, Any?>): PolicyDocument? {
        val revision = integer(doc["revision"])?.takeIf { it >= 0 } ?: return null
        val issued = (doc["issued_at"] as? String)?.let { EmergencyContract.parseInstant(it) } ?: return null
        val expires = (doc["expires_at"] as? String)?.let { EmergencyContract.parseInstant(it) } ?: return null
        val list = J.list(doc["policies"]) ?: return null
        val policies = ArrayList<EmergencyPolicy>(list.size)
        for (item in list) policies += parsePolicy(J.obj(item) ?: return null) ?: return null
        return PolicyDocument(doc["application_id"] as String, doc["environment"] as String, revision, issued, expires, policies)
    }

    private fun parsePolicy(p: Map<String, Any?>): EmergencyPolicy? {
        val policyId = p["policy_id"] as? String ?: return null
        val key = (p["key"] as? String)?.takeIf { EmergencyContract.isPolicyKey(it) } ?: return null
        val revision = integer(p["revision"]) ?: return null
        val action = p["action"] as? String ?: return null
        val priority = integer(p["priority"]) ?: return null
        val platforms = J.list(p["target_platforms"]) ?: return null
        if (platforms.any { it !is String }) return null
        val rangesRaw = J.list(p["version_ranges"]) ?: return null
        val ranges = ArrayList<EmergencyVersionRange>(rangesRaw.size)
        for (r in rangesRaw) {
            val o = J.obj(r) ?: return null
            if (!nullableString(o, "min") || !nullableString(o, "max")) return null
            ranges += EmergencyVersionRange(o["min"] as String?, o["max"] as String?)
        }
        val rolloutBp = integer(p["rollout_bp"]) ?: return null
        val seed = p["rollout_seed"] as? String ?: return null
        val reason = p["reason_code"] as? String ?: return null
        for (f in listOf("severity", "incident_id", "public_user_message", "required_version", "fallback", "expires_at")) {
            if (!nullableString(p, f)) return null
        }
        val expiresAt = (p["expires_at"] as String?)?.let { EmergencyContract.parseInstant(it) ?: return null }
        @Suppress("UNCHECKED_CAST")
        return EmergencyPolicy(
            policyId = policyId,
            key = key,
            revision = revision,
            action = EmergencyAction.fromWire(action),
            priority = priority,
            severity = p["severity"] as String?,
            incidentId = p["incident_id"] as String?,
            targetPlatforms = java.util.Collections.unmodifiableList(ArrayList(platforms as List<String>)),
            versionRanges = java.util.Collections.unmodifiableList(ranges),
            rolloutBp = rolloutBp,
            rolloutSeed = seed,
            reasonCode = reason,
            publicUserMessage = p["public_user_message"] as String?,
            requiredVersion = p["required_version"] as String?,
            fallback = p["fallback"] as String?,
            expiresAtMs = expiresAt,
        )
    }

    /** Absent, null or a string. */
    private fun nullableString(o: Map<String, Any?>, field: String): Boolean = o[field].let { it == null || it is String }

    /** A JSON integer (also `1.0`, as JavaScript reads it); null otherwise. */
    fun integer(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v == Math.rint(v) && Math.abs(v) <= 9_007_199_254_740_991.0) v.toLong() else null
        else -> null
    }
}
