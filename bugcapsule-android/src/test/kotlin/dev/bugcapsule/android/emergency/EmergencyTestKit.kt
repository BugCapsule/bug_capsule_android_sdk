package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Cancellable
import dev.bugcapsule.android.internal.Scheduler
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal const val APP_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
internal const val T0 = 1_793_260_800_000L // 2026-10-29T08:00:00.000Z

internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun iso(ms: Long): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(ms))
}

/** A throwaway ES256 signing key and the documents it signs. */
internal class TestSigner(val kid: String = "ek_test_" + System.nanoTime()) {
    val keys: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    val spki: String get() = b64(keys.public.encoded)

    fun anchor(app: String = APP_ID, vararg extra: TestSigner): String {
        val list = (listOf(this) + extra).map { mapOf("kid" to it.kid, "alg" to "ES256", "spki" to it.spki) }
        return TrustAnchor.PREFIX + b64(Json.stringify(mapOf("app" to app, "keys" to list)).toByteArray())
    }

    fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(payload)
        sign()
    }

    /** An envelope (response body) over [payload]'s exact bytes. */
    fun envelope(payload: ByteArray, kid: String = this.kid, format: String = "bugcapsule.emergency.v1"): String =
        Json.stringify(linkedMapOf("format" to format, "alg" to "ES256", "kid" to kid, "payload" to b64(payload), "signature" to b64(sign(payload))))

    fun envelope(doc: Map<String, Any?>): String = envelope(Json.canonical(doc).toByteArray())
}

internal fun policy(
    key: String = "checkout.confirm_order",
    action: String = "BLOCK",
    id: String = "p-1",
    reason: String = "PRICING_SAFETY",
    priority: Long = 0,
    rolloutBp: Long = 10_000,
    expiresAt: String? = null,
    message: String? = "Checkout is temporarily unavailable.",
): Map<String, Any?> = linkedMapOf(
    "policy_id" to id, "key" to key, "revision" to 1L, "action" to action, "priority" to priority,
    "severity" to "P0", "incident_id" to "inc_1", "target_platforms" to emptyList<String>(),
    "version_ranges" to emptyList<Any>(), "rollout_bp" to rolloutBp, "rollout_seed" to "seed",
    "reason_code" to reason, "public_user_message" to message, "required_version" to null,
    "fallback" to null, "expires_at" to expiresAt,
)

internal fun document(
    revision: Long,
    policies: List<Map<String, Any?>> = listOf(policy()),
    issuedAt: Long = T0,
    expiresAt: Long = issuedAt + 3_600_000,
    app: String = APP_ID,
    environment: String = "production",
    schema: Long = 1,
): Map<String, Any?> = linkedMapOf(
    "type" to "bugcapsule.emergency.policy-document", "schema_version" to schema, "application_id" to app,
    "environment" to environment, "revision" to revision, "issued_at" to iso(issuedAt), "expires_at" to iso(expiresAt),
    "policies" to policies,
)

/** Scripted backend: answers fetches from a queue and records requests and receipts. */
internal class FakeTransport : EmergencyTransport {
    val answers = ArrayDeque<EmergencyFetchResult>()
    val etags = CopyOnWriteArrayList<String?>()
    val receipts = CopyOnWriteArrayList<Map<String, Any?>>()

    /** When set, fetches wait on it (in-flight tests). */
    @Volatile
    var gate: CountDownLatch? = null

    val fetches: Int get() = etags.size

    fun ok(body: String, etag: String? = "\"e-${body.hashCode()}\"") = apply {
        synchronized(answers) { answers.addLast(EmergencyFetchResult.Fetched(body.toByteArray(), etag, null)) }
    }

    fun answer(r: EmergencyFetchResult) = apply { synchronized(answers) { answers.addLast(r) } }

    override fun fetchPolicies(applicationId: String, environment: String, etag: String?, appVersion: String?): EmergencyFetchResult {
        etags.add(etag)
        gate?.await(10, TimeUnit.SECONDS)
        return synchronized(answers) { answers.removeFirstOrNull() } ?: EmergencyFetchResult.Failed("no scripted answer")
    }

    override fun sendReceipt(applicationId: String, body: Map<String, Any?>): Int {
        receipts.add(body)
        return 204
    }
}

/** Timers that only run when a test fires them. */
internal class ManualScheduler : Scheduler {
    class Entry(val delayMs: Long, val task: Runnable) {
        @Volatile
        var cancelled = false
    }

    val entries = CopyOnWriteArrayList<Entry>()

    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val e = Entry(delayMs, task)
        entries.add(e)
        return Cancellable { e.cancelled = true }
    }

    val pending: List<Entry> get() = entries.filter { !it.cancelled }
}
