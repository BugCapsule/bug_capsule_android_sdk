package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The client runtime of emergency-client-v1 §5 and §7, against a scripted backend and a manual clock. */
class EmergencyEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signer = TestSigner("ek_one")
    private val anchor = signer.anchor()
    private var now = T0 + 60_000
    private val transport = FakeTransport()
    private val scheduler = ManualScheduler()
    private val executor = Executors.newSingleThreadExecutor()
    private var replaying = false
    private var hasBackend = true
    private val snapshots = CopyOnWriteArrayList<EmergencySnapshot>()
    private val engines = ArrayList<EmergencyEngine>()

    private val store get() = EmergencyStore(tmp.root.resolve("emergency"))

    @After
    fun tearDown() {
        engines.forEach { it.dispose() }
        executor.shutdownNow()
    }

    private fun engine(
        anchorString: String = anchor,
        modes: Map<String, EmergencyFailureMode> = emptyMap(),
        environment: String = "production",
        foreground: Boolean = true,
        intervalMs: Long = 60_000,
    ): EmergencyEngine {
        val parsed = TrustAnchor.parse(anchorString)
        val e = EmergencyEngine(
            environment = environment,
            refreshIntervalMs = intervalMs,
            failureModes = modes,
            anchor = (parsed as? TrustAnchor.Parsed.Valid)?.anchor,
            anchorError = (parsed as? TrustAnchor.Parsed.Invalid)?.message,
            store = store,
            transport = { if (hasBackend) transport else null },
            executor = executor,
            scheduler = scheduler,
            nowMs = { now },
            random = Random(7),
            replaying = { replaying },
            appVersion = { "2.4.1" },
            sdkVersion = "0.5.0-test",
            onSnapshot = { snapshots.add(it) },
            foreground = foreground,
        )
        engines += e
        return e
    }

    /** Starts [e] and waits for the startup fetch (and its receipts). */
    private fun started(e: EmergencyEngine): EmergencyEngine {
        e.start()
        drain()
        return e
    }

    private fun drain() {
        repeat(3) { executor.submit {}.get(10, TimeUnit.SECONDS) }
    }

    private fun refresh(e: EmergencyEngine): EmergencyStatus = e.refresh().get(10, TimeUnit.SECONDS).also { drain() }

    private fun decision(e: EmergencyEngine, key: String = "checkout.confirm_order") = e.evaluate(key, null)

    // ------------------------------------------------------------- accepting

    @Test
    fun acceptsAVerifiedDocumentAndEvaluatesIt() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        val d = decision(e)
        assertEquals(EmergencyAction.BLOCK, d.action)
        assertFalse(d.allowed)
        assertEquals(EmergencyDecisionSource.POLICY, d.source)
        assertEquals(42L, d.documentRevision)
        assertEquals("PRICING_SAFETY", d.reasonCode)
        assertEquals("Checkout is temporarily unavailable.", d.publicUserMessage)
        assertEquals(EmergencyState.USABLE, e.status().state)
        assertEquals(42L, e.status().revision)
        assertNull(e.status().lastError)
        assertEquals(now, e.status().lastFetchAtMs)
        assertTrue(decision(e, "search.query").allowed)
        assertEquals("p-1", e.getPolicy("checkout.confirm_order", null)?.policyId)
        assertNull(e.getPolicy("search.query", null))
        assertEquals("next refresh after the interval", 60_000L, e.scheduledDelayMs)
    }

    @Test
    fun rejectsAnOlderRevisionThanTheHighestAccepted() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        transport.ok(signer.envelope(document(41, listOf(policy(action = "ALLOW")), issuedAt = T0 + 1000)))
        refresh(e)
        assertEquals(42L, e.status().revision)
        assertEquals("document rejected: stale_revision", e.status().lastError)
        assertEquals(EmergencyAction.BLOCK, decision(e).action)
    }

    @Test
    fun sameRevisionWithAnOlderOrEqualIssuedAtChangesNothing() {
        transport.ok(signer.envelope(document(42)), etag = "\"a\"")
        val e = started(engine())
        val before = e.current
        transport.ok(signer.envelope(document(42, listOf(policy(action = "ALLOW")), issuedAt = T0 - 1000)), etag = "\"b\"")
        refresh(e)
        assertSame(before, e.current)
        assertNull("ignoring is not an error", e.status().lastError)
        transport.ok(signer.envelope(document(42, issuedAt = T0)), etag = "\"c\"")
        refresh(e)
        assertSame(before, e.current)
        assertEquals("\"a\"", e.current.etag)

        // A later issued_at of the same revision is the normal refresh: it extends the expiry.
        transport.ok(signer.envelope(document(42, issuedAt = T0 + 1800_000)), etag = "\"d\"")
        refresh(e)
        assertEquals(T0 + 1800_000 + 3_600_000, e.status().expiresAtMs)
        assertEquals("\"d\"", e.current.etag)
    }

    @Test
    fun rejectsADocumentAlreadyExpiredOnArrival() {
        now = T0 + 3_600_000 + 300_001
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        assertEquals(EmergencyState.NONE, e.status().state)
        assertEquals("document rejected: expired_on_arrival", e.status().lastError)

        // Within the 300 s grace it is accepted.
        now = T0 + 3_600_000 + 300_000
        transport.ok(signer.envelope(document(42)))
        refresh(e)
        assertEquals(EmergencyState.USABLE, e.status().state)
    }

    @Test
    fun everyVerificationFailureKeepsTheCurrentDocument() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        val other = TestSigner("ek_other")
        val payload = Json.canonical(document(43, listOf(policy(action = "ALLOW")))).toByteArray()
        val bodies = linkedMapOf(
            "not json" to Reasons.MALFORMED,
            "[]" to Reasons.MALFORMED,
            Json.stringify(mapOf("format" to "bugcapsule.emergency.v1", "alg" to "ES256", "kid" to signer.kid, "payload" to b64(payload))) to Reasons.MALFORMED,
            signer.envelope(payload, format = "bugcapsule.emergency.v2") to Reasons.UNSUPPORTED_FORMAT,
            other.envelope(payload) to Reasons.UNKNOWN_KEY,
            other.envelope(payload, kid = signer.kid) to Reasons.BAD_SIGNATURE,
            signer.envelope("[1,2]".toByteArray()) to Reasons.MALFORMED,
            signer.envelope("{not json".toByteArray()) to Reasons.MALFORMED,
            signer.envelope(document(43, schema = 2)) to Reasons.UNSUPPORTED_SCHEMA,
            signer.envelope(document(43, app = "16fd2706-8baf-433b-82eb-8c7fada847da")) to Reasons.WRONG_APPLICATION,
            signer.envelope(document(43, environment = "staging")) to Reasons.WRONG_ENVIRONMENT,
            signer.envelope(document(43, listOf(policy(key = "Checkout.Pay")))) to Reasons.MALFORMED,
            signer.envelope(document(43, listOf(policy() + ("rollout_bp" to "all")))) to Reasons.MALFORMED,
            signer.envelope(document(43, listOf(policy(expiresAt = "tomorrow")))) to Reasons.MALFORMED,
            signer.envelope(document(43) + ("issued_at" to "2026-13-01T00:00:00Z")) to Reasons.MALFORMED,
        )
        for ((body, reason) in bodies) {
            transport.ok(body)
            refresh(e)
            assertEquals(body.take(60), "document rejected: $reason", e.status().lastError)
            assertEquals(42L, e.status().revision)
            assertEquals(EmergencyAction.BLOCK, decision(e).action)
        }
        // Non-UTF-8 bytes are malformed too.
        transport.answer(EmergencyFetchResult.Fetched(byteArrayOf(0xff.toByte(), 0xfe.toByte()), null, null))
        refresh(e)
        assertEquals("document rejected: malformed", e.status().lastError)
    }

    @Test
    fun unknownActionsAreTreatedAsBlock() {
        transport.ok(signer.envelope(document(42, listOf(policy(action = "SELF_DESTRUCT")))))
        val e = started(engine())
        val d = decision(e)
        assertEquals(EmergencyAction.BLOCK, d.action)
        assertFalse(d.allowed)
    }

    // ------------------------------------------------------------- the cache

    @Test
    fun cacheIsPersistedAndReverifiedOnRestart() {
        transport.ok(signer.envelope(document(42)), etag = "\"rev42\"")
        started(engine()).dispose()
        val cached = store.read()!!
        assertEquals(APP_ID, cached.application)
        assertEquals(42L, cached.highestRevision)
        assertEquals("\"rev42\"", cached.etag)
        assertEquals(now, cached.receivedAtMs)

        // Offline restart: the cached document is used without the network.
        hasBackend = false
        val e = engine()
        e.start()
        assertEquals("usable before any fetch finished", EmergencyState.USABLE, e.status().state)
        assertEquals(EmergencyAction.BLOCK, decision(e).action)
        drain()
        assertEquals(EmergencyAction.BLOCK, decision(e).action)
        assertTrue(e.status().lastError!!.startsWith("no backend connection"))
        assertEquals("listeners hear about the restored document", 42L, snapshots.last().revision)
    }

    @Test
    fun tamperedCacheIsDiscarded() {
        transport.ok(signer.envelope(document(42)))
        started(engine()).dispose()
        val c = store.read()!!
        val env = J.obj(Json.parse(c.envelope!!))!!.toMutableMap()
        env["payload"] = b64(Json.canonical(document(42, listOf(policy(action = "ALLOW")))).toByteArray())
        store.write(EmergencyStore.Cached(c.application, c.environment, Json.stringify(env), c.etag, c.receivedAtMs, c.highestRevision))
        hasBackend = false
        val e = started(engine())
        assertEquals(EmergencyState.NONE, e.status().state)
        assertTrue(decision(e).allowed)
        assertNull("discarded on disk", store.read()!!.envelope)
        assertEquals("anti-replay state survives", 42L, store.read()!!.highestRevision)
    }

    @Test
    fun cacheIsDiscardedWhenTheAnchorChanges() {
        transport.ok(signer.envelope(document(42)))
        started(engine()).dispose()

        // Same application, rotated to a new key only: the old document no longer verifies.
        val next = TestSigner("ek_two")
        hasBackend = false
        val rotated = started(engine(next.anchor()))
        assertEquals(EmergencyState.NONE, rotated.status().state)
        assertNull(store.read()!!.envelope)
        assertEquals(42L, store.read()!!.highestRevision)
        rotated.dispose()

        // A replay of revision 41 signed by the new key is still refused (highest revision persisted).
        hasBackend = true
        transport.ok(next.envelope(document(41)))
        val again = started(engine(next.anchor()))
        assertEquals("document rejected: stale_revision", again.status().lastError)
        again.dispose()

        // Another application: nothing of the old one carries over.
        val otherApp = "16fd2706-8baf-433b-82eb-8c7fada847da"
        transport.ok(next.envelope(document(3, app = otherApp)))
        val other = started(engine(next.anchor(otherApp)))
        assertEquals(EmergencyState.USABLE, other.status().state)
        assertEquals(3L, store.read()!!.highestRevision)
        assertEquals(otherApp, store.read()!!.application)
    }

    @Test
    fun cacheOfAnotherEnvironmentIsNotUsed() {
        transport.ok(signer.envelope(document(42)))
        started(engine()).dispose()
        hasBackend = false
        val e = started(engine(environment = "staging"))
        assertEquals(EmergencyState.NONE, e.status().state)
        assertEquals("staging", store.read()!!.environment)
        assertEquals(0L, store.read()!!.highestRevision)
    }

    @Test
    fun keyRotationKeepsVerifyingWithTheNextKey() {
        val next = TestSigner("ek_two")
        transport.ok(next.envelope(document(43)))
        val e = started(engine(signer.anchor(APP_ID, next)))
        assertEquals(43L, e.status().revision)
    }

    // ---------------------------------------------------------- failure modes

    @Test
    fun expiryAppliesEachFailureMode() {
        transport.ok(signer.envelope(document(42, listOf(
            policy(key = "checkout.confirm_order", id = "p-1"),
            policy(key = "wallet.transfer", id = "p-2", reason = "LEDGER"),
            policy(key = "search.query", id = "p-3", reason = "SEARCH"),
        ))))
        val e = started(engine(modes = mapOf(
            "checkout.*" to EmergencyFailureMode.FAIL_CLOSED,
            "wallet.transfer" to EmergencyFailureMode.USE_LAST_VALID_POLICY,
            "profile.*" to EmergencyFailureMode.FAIL_CLOSED,
        )))
        // Within expires_at + 300 s the document is still usable.
        now = T0 + 3_600_000 + 300_000
        assertEquals(EmergencyDecisionSource.POLICY, decision(e).source)
        now += 1
        assertEquals(EmergencyState.EXPIRED, e.status().state)

        val closed = decision(e, "checkout.confirm_order")
        assertEquals(EmergencyAction.BLOCK, closed.action)
        assertEquals("POLICY_UNAVAILABLE", closed.reasonCode)
        assertEquals(EmergencyDecisionSource.FAILURE_MODE, closed.source)
        assertNull(closed.documentRevision)

        val open = decision(e, "search.query")
        assertTrue(open.allowed)
        assertEquals("POLICY_UNAVAILABLE", open.reasonCode)
        assertEquals(EmergencyDecisionSource.FAILURE_MODE, open.source)
        assertNull(e.getPolicy("search.query", null))

        val stale = decision(e, "wallet.transfer")
        assertEquals(EmergencyAction.BLOCK, stale.action)
        assertEquals("LEDGER", stale.reasonCode)
        assertEquals(EmergencyDecisionSource.STALE_POLICY, stale.source)
        assertEquals(42L, stale.documentRevision)
        assertEquals("p-2", e.getPolicy("wallet.transfer", null)?.policyId)

        assertEquals(EmergencyAction.BLOCK, decision(e, "profile.delete").action)
    }

    @Test
    fun failureModesWithoutADocument() {
        hasBackend = false
        val e = started(engine(modes = mapOf(
            "checkout.*" to EmergencyFailureMode.FAIL_CLOSED,
            "wallet.transfer" to EmergencyFailureMode.USE_LAST_VALID_POLICY,
        )))
        assertEquals(EmergencyState.NONE, e.status().state)
        assertEquals(EmergencyAction.BLOCK, decision(e, "checkout.pay").action)
        val last = decision(e, "wallet.transfer")
        assertTrue("USE_LAST_VALID_POLICY without a document allows", last.allowed)
        assertEquals("POLICY_UNAVAILABLE", last.reasonCode)
        assertEquals(EmergencyDecisionSource.FAILURE_MODE, last.source)
        assertTrue(decision(e, "search.query").allowed)
        assertTrue("the wildcard covers keys below it, not the prefix itself", decision(e, "checkout").allowed)
    }

    @Test
    fun failureModeMatchingPrefersExactThenLongestPrefix() {
        val modes = mapOf(
            "checkout.*" to EmergencyFailureMode.FAIL_CLOSED,
            "checkout.cart.*" to EmergencyFailureMode.FAIL_OPEN,
            "checkout.cart.pay" to EmergencyFailureMode.USE_LAST_VALID_POLICY,
            "a.*" to EmergencyFailureMode.FAIL_CLOSED,
        )
        fun mode(k: String) = EmergencyContract.failureModeFor(modes, k)
        assertEquals(EmergencyFailureMode.USE_LAST_VALID_POLICY, mode("checkout.cart.pay"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, mode("checkout.cart.view"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, mode("checkout.cart.pay.extra"))
        assertEquals(EmergencyFailureMode.FAIL_CLOSED, mode("checkout.confirm_order"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, mode("checkout"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, mode("checkoutx.pay"))
        assertEquals(EmergencyFailureMode.FAIL_CLOSED, mode("a.b"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, mode("ab.c"))
        assertEquals(EmergencyFailureMode.FAIL_OPEN, EmergencyContract.failureModeFor(emptyMap(), "x"))
    }

    @Test
    fun invalidAnchorTurnsPoliciesOff() {
        for (bad in listOf("", "bcek.v2.abc", "bcek.v1.%%%", "bcek.v1." + b64("[]".toByteArray()),
            "bcek.v1." + b64("""{"app":"a","keys":[{"kid":"k","alg":"EdDSA","spki":"${signer.spki}"}]}""".toByteArray()),
            "bcek.v1." + b64("""{"app":"a","keys":[{"kid":"k","alg":"ES256","spki":"AAAA"}]}""".toByteArray()),
            "bcek.v1." + b64("""{"app":"a","keys":[]}""".toByteArray()))) {
            assertTrue(bad, TrustAnchor.parse(bad) is TrustAnchor.Parsed.Invalid)
        }
        val e = started(engine(anchorString = "bcek.v1.garbage", modes = mapOf("checkout.*" to EmergencyFailureMode.FAIL_CLOSED)))
        assertEquals("no fetch without a valid anchor", 0, transport.fetches)
        assertFalse(e.status().trustAnchorValid)
        assertTrue(e.status().lastError!!.isNotEmpty())
        assertEquals(EmergencyAction.BLOCK, decision(e, "checkout.pay").action)
        assertTrue(decision(e, "search.query").allowed)
        assertNull(e.scheduledDelayMs)
    }

    // ---------------------------------------------------------------- refresh

    @Test
    fun notModifiedKeepsTheDocumentAndSendsTheEtag() {
        transport.ok(signer.envelope(document(42)), etag = "W/\"abc\"")
        val e = started(engine())
        assertNull("no etag without a cached document", transport.etags[0])
        transport.answer(EmergencyFetchResult.NotModified)
        refresh(e)
        assertEquals("W/\"abc\"", transport.etags[1])
        assertEquals(42L, e.status().revision)
        assertNull(e.status().lastError)
        assertEquals(60_000L, e.scheduledDelayMs)
    }

    @Test
    fun credentialRejectionStopsScheduledFetchesButKeepsTheCache() {
        for (status in listOf(401, 403, 404)) {
            transport.answers.clear()
            transport.etags.clear()
            scheduler.entries.clear()
            tmp.root.resolve("emergency").deleteRecursively()
            transport.ok(signer.envelope(document(42)))
            val e = started(engine())
            transport.answer(EmergencyFetchResult.Failed("HTTP $status", status))
            refresh(e)
            assertTrue(e.status().credentialRejected)
            assertNull("HTTP $status: nothing scheduled", e.scheduledDelayMs)
            assertTrue(scheduler.pending.isEmpty())
            assertEquals(EmergencyAction.BLOCK, decision(e).action)
            assertEquals(EmergencyState.USABLE, e.status().state)
            refresh(e)
            e.onForeground()
            drain()
            assertEquals("no more fetches in this process", 2, transport.fetches)
            e.dispose()
        }
    }

    @Test
    fun failuresBackOffWithJitterAndHonourRetryAfter() {
        hasBackend = true
        val e = engine()
        e.start()
        drain() // "no scripted answer": failure 1
        assertTrue(e.scheduledDelayMs!! in 48_000..72_000)
        refresh(e)
        assertTrue(e.scheduledDelayMs!! in 96_000..144_000)
        repeat(8) { refresh(e) }
        assertTrue("capped at 15 min ± 20 %", e.scheduledDelayMs!! in 720_000..1_080_000)
        transport.answer(EmergencyFetchResult.Failed("HTTP 429", 429, retryAfterMs = 3_600_000))
        refresh(e)
        assertTrue("Retry-After raises the delay", e.scheduledDelayMs!! >= 2_880_000)
        transport.answer(EmergencyFetchResult.Failed("HTTP 409 (emergency_not_configured)", 409, reason = "emergency_not_configured"))
        refresh(e)
        assertEquals("HTTP 409 (emergency_not_configured)", e.status().lastError)
        assertFalse(e.status().credentialRejected)
        transport.ok(signer.envelope(document(42)))
        refresh(e)
        assertEquals("a success resets the backoff", 60_000L, e.scheduledDelayMs)
        transport.answer(EmergencyFetchResult.Failed("offline"))
        refresh(e)
        assertTrue(e.scheduledDelayMs!! in 48_000..72_000)
    }

    @Test
    fun scheduledTimerFetchesAgain() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        assertEquals(1, scheduler.pending.size)
        transport.ok(signer.envelope(document(43, issuedAt = T0 + 1000)))
        scheduler.pending.single().task.run()
        drain()
        assertEquals(43L, e.status().revision)
        assertEquals(2, transport.fetches)
    }

    @Test
    fun concurrentRefreshesJoinTheFetchInFlight() {
        val gate = CountDownLatch(1)
        transport.gate = gate
        transport.ok(signer.envelope(document(42)))
        val e = engine()
        e.start()
        val a = e.refresh()
        val b = e.refresh()
        assertSame(a, b)
        gate.countDown()
        a.get(10, TimeUnit.SECONDS)
        drain()
        assertEquals(1, transport.fetches)
        assertEquals(42L, a.get().revision)
        transport.gate = null
        transport.answer(EmergencyFetchResult.NotModified)
        val c = e.refresh()
        c.get(10, TimeUnit.SECONDS)
        assertEquals("a finished fetch is not joined", 2, transport.fetches)
    }

    @Test
    fun noTimersInTheBackgroundAndAFetchOnForeground() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        assertEquals(1, scheduler.pending.size)
        e.onBackground()
        drain()
        assertTrue(scheduler.pending.isEmpty())
        transport.answer(EmergencyFetchResult.Failed("offline"))
        refresh(e)
        assertTrue("a fetch in the background schedules nothing", scheduler.pending.isEmpty())

        // Back in the foreground: fetches now.
        transport.answer(EmergencyFetchResult.NotModified)
        e.onForeground()
        drain()
        assertEquals(3, transport.fetches)
        assertEquals(60_000L, e.scheduledDelayMs)

        // A foreground transition right after a successful fetch only re-arms the timer.
        e.onBackground()
        now += 5_000
        e.onForeground()
        drain()
        assertEquals(3, transport.fetches)
        assertEquals(55_000L, e.scheduledDelayMs)
    }

    @Test
    fun startedInTheBackgroundFetchesOnceAndWaits() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine(foreground = false))
        assertEquals(1, transport.fetches)
        assertNull(e.scheduledDelayMs)
        assertTrue(scheduler.pending.isEmpty())
    }

    // --------------------------------------------------------------- receipts

    @Test
    fun receiptsAreSentOncePerRevisionStatusAndCode() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        // Re-issued same revision: accepted, no second APPLIED receipt.
        transport.ok(signer.envelope(document(42, issuedAt = T0 + 1000)))
        refresh(e)
        // The same stale document twice: one FAILED receipt.
        repeat(2) {
            transport.ok(signer.envelope(document(40)))
            refresh(e)
        }
        // A verification failure without a verified revision uses the revision hint.
        transport.answer(EmergencyFetchResult.Fetched("garbage".toByteArray(), null, 44))
        refresh(e)
        transport.answer(EmergencyFetchResult.Fetched("garbage".toByteArray(), null, null))
        refresh(e)
        drain()
        val r = transport.receipts
        assertEquals(r.toString(), 4, r.size)
        assertEquals(
            linkedMapOf<String, Any?>(
                "environment" to "production", "platform" to "NATIVE_ANDROID", "installationId" to e.installationId,
                "appVersion" to "2.4.1", "sdkVersion" to "0.5.0-test", "revision" to 42L, "status" to "APPLIED", "errorCode" to null,
            ),
            r[0],
        )
        assertEquals(listOf(40L, "FAILED", "stale_revision"), listOf(r[1]["revision"], r[1]["status"], r[1]["errorCode"]))
        assertEquals(listOf(44L, "FAILED", "malformed"), listOf(r[2]["revision"], r[2]["status"], r[2]["errorCode"]))
        assertEquals(listOf(0L, "FAILED", "malformed"), listOf(r[3]["revision"], r[3]["status"], r[3]["errorCode"]))
        for (body in r) assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(body["errorCode"] as String? ?: "ok"))
    }

    @Test
    fun noReceiptForFailedFetches() {
        transport.answer(EmergencyFetchResult.Failed("HTTP 503", 503))
        started(engine())
        assertTrue(transport.receipts.isEmpty())
    }

    @Test
    fun installationIdIsPersistedAndStable() {
        hasBackend = false
        val a = started(engine())
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(a.installationId))
        assertEquals(a.installationId, store.readInstallationId())
        a.dispose()
        val b = started(engine())
        assertEquals(a.installationId, b.installationId)
        assertTrue("apart from the policy cache", store.installationFile != store.cacheFile)
    }

    @Test
    fun subjectDefaultsToTheInstallationIdAndCanBeOverridden() {
        transport.ok(signer.envelope(document(42, listOf(policy(rolloutBp = 5000)))))
        val e = started(engine())
        val seedSubject = { s: String -> EmergencyContract.rolloutBucket("seed", s) < 5000 }
        assertEquals(seedSubject(e.installationId), !decision(e).allowed)
        val inside = (0 until 200).map { "user:$it" }.first { seedSubject(it) }
        val outside = (0 until 200).map { "user:$it" }.first { !seedSubject(it) }
        assertFalse(e.evaluate("checkout.confirm_order", EmergencyContext(inside)).allowed)
        assertTrue(e.evaluate("checkout.confirm_order", EmergencyContext(outside)).allowed)
    }

    // -------------------------------------------------------- runtime reports

    private fun update(state: EmergencyUpdateState, flow: EmergencyUpdateFlow = EmergencyUpdateFlow.FLEXIBLE) =
        RuntimeReport.of(EmergencyUpdateStatus("google_play", flow, state, "2.4.2+57", "2.4.2+57"))!!

    private fun runtimeReports() = transport.receipts.filter { it["status"] == "RUNTIME" }

    private fun reportRuntime(e: EmergencyEngine, runtime: Map<String, Any?>) {
        e.reportRuntime(runtime)
        drain()
    }

    /** Fires the pending runtime timer (the only timer while no refresh is scheduled). */
    private fun fireRuntimeTimer() {
        val entry = scheduler.pending.single { it.delayMs <= EmergencyEngine.RUNTIME_INTERVAL_MS && it.task !in refreshTasks }
        entry.cancelled = true
        entry.task.run()
        drain()
    }

    private val refreshTasks = HashSet<Runnable>()

    @Test
    fun runtimeReportIsAReceiptWithTheHighestRevision() {
        transport.answer(EmergencyFetchResult.Failed("offline"))
        val e = started(engine())
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        val r = runtimeReports()
        assertEquals(1, r.size)
        assertEquals(
            linkedMapOf<String, Any?>(
                "environment" to "production", "platform" to "NATIVE_ANDROID", "installationId" to e.installationId,
                "appVersion" to "2.4.1", "sdkVersion" to "0.5.0-test", "revision" to 0L, "status" to "RUNTIME", "errorCode" to null,
                "runtime" to linkedMapOf(
                    "update" to linkedMapOf(
                        "provider" to "google_play", "flow" to "flexible", "state" to "available",
                        "availableVersion" to "2.4.2+57", "requiredVersion" to "2.4.2+57",
                    ),
                ),
            ),
            r[0],
        )
        assertEquals("no hotfix on Android", setOf("update"), (r[0]["runtime"] as Map<*, *>).keys)

        now += 61_000
        transport.ok(signer.envelope(document(42)))
        refresh(e)
        reportRuntime(e, update(EmergencyUpdateState.IN_PROGRESS))
        assertEquals("after a revision was accepted", 42L, runtimeReports().last()["revision"])
    }

    @Test
    fun appliedAndFailedReceiptsCarryTheLatestRuntime() {
        transport.answer(EmergencyFetchResult.Failed("offline"))
        val e = started(engine())
        reportRuntime(e, update(EmergencyUpdateState.DOWNLOADED))
        transport.ok(signer.envelope(document(42)))
        refresh(e)
        transport.ok(signer.envelope(document(40)))
        refresh(e)
        val applied = transport.receipts.single { it["status"] == "APPLIED" }
        val failed = transport.receipts.single { it["status"] == "FAILED" }
        assertEquals(update(EmergencyUpdateState.DOWNLOADED), applied["runtime"])
        assertEquals(update(EmergencyUpdateState.DOWNLOADED), failed["runtime"])
    }

    @Test
    fun receiptsWithoutARuntimeHaveNoRuntimeField() {
        transport.ok(signer.envelope(document(42)))
        started(engine())
        assertFalse(transport.receipts.single().containsKey("runtime"))
    }

    @Test
    fun runtimeReportsAreThrottledAndCoalescedToTheLatest() {
        transport.answer(EmergencyFetchResult.Failed("offline"))
        val e = started(engine())
        scheduler.pending.forEach { refreshTasks += it.task }
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        assertEquals(1, runtimeReports().size)

        now += 10_000
        reportRuntime(e, update(EmergencyUpdateState.IN_PROGRESS))
        now += 10_000
        reportRuntime(e, update(EmergencyUpdateState.DOWNLOADED))
        assertEquals("within 60 s nothing more is sent", 1, runtimeReports().size)
        val timer = scheduler.pending.single { it.task !in refreshTasks }
        assertEquals("armed by the first change, for the end of the interval", 50_000L, timer.delayMs)

        now += 40_000
        fireRuntimeTimer()
        val r = runtimeReports()
        assertEquals(2, r.size)
        assertEquals("only the latest", update(EmergencyUpdateState.DOWNLOADED), r[1]["runtime"])
    }

    @Test
    fun theSameRuntimeIsNeverSentTwiceInARow() {
        transport.answer(EmergencyFetchResult.Failed("offline"))
        val e = started(engine())
        scheduler.pending.forEach { refreshTasks += it.task }
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        assertEquals(1, runtimeReports().size)

        // Changed and changed back within the interval: the latest equals the last sent one.
        now += 10_000
        reportRuntime(e, update(EmergencyUpdateState.DECLINED))
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        now += 50_000
        fireRuntimeTimer()
        assertEquals("nothing new at the end of the interval", 1, runtimeReports().size)

        // A → B → A over separate intervals: each change is sent, also a repeated object.
        now += 61_000
        reportRuntime(e, update(EmergencyUpdateState.DECLINED))
        now += 61_000
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        assertTrue(scheduler.pending.none { it.task !in refreshTasks })
        assertEquals(
            listOf("available", "declined", "available"),
            runtimeReports().map { ((it["runtime"] as Map<*, *>)["update"] as Map<*, *>)["state"] },
        )
        assertEquals("RUNTIME receipts are exempt from the per-revision rule", setOf(0L), runtimeReports().map { it["revision"] }.toSet())
    }

    @Test
    fun noRuntimeReportsInReplayModeOrWithoutAnAnchor() {
        replaying = true
        val e = started(engine())
        reportRuntime(e, update(EmergencyUpdateState.AVAILABLE))
        assertTrue(transport.receipts.isEmpty())

        replaying = false
        val off = started(engine(anchorString = "bcek.v1.invalid"))
        reportRuntime(off, update(EmergencyUpdateState.FAILED))
        assertTrue(transport.receipts.isEmpty())
    }

    @Test
    fun runtimeObjectsAreValidatedBeforeTheyAreSent() {
        assertNull(RuntimeReport.of(EmergencyUpdateStatus("Google Play", EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateState.AVAILABLE)))
        assertNull(RuntimeReport.of(EmergencyUpdateStatus("a".repeat(33), EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateState.AVAILABLE)))
        assertNull(RuntimeReport.of(EmergencyUpdateStatus("_play", EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateState.AVAILABLE)))
        val long = "1." + "0".repeat(63)
        assertNull(RuntimeReport.of(EmergencyUpdateStatus("google_play", EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateState.AVAILABLE, long)))
        assertNull(RuntimeReport.of(EmergencyUpdateStatus("google_play", EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateState.AVAILABLE, null, long)))
        val ok = RuntimeReport.of(EmergencyUpdateStatus("app_store", EmergencyUpdateFlow.STORE_PAGE, EmergencyUpdateState.OPENED_STORE, "1".repeat(64)))
        assertEquals(
            mapOf(
                "update" to mapOf(
                    "provider" to "app_store", "flow" to "store_page", "state" to "opened_store",
                    "availableVersion" to "1".repeat(64), "requiredVersion" to null,
                ),
            ),
            ok,
        )
        assertEquals(
            listOf("not_available", "available", "in_progress", "downloaded", "declined", "failed", "opened_store"),
            EmergencyUpdateState.entries.map { it.wire },
        )
        assertEquals(listOf("immediate", "flexible", "store_page", "custom"), EmergencyUpdateFlow.entries.map { it.wire })
    }

    // ------------------------------------------------------------------ replay

    @Test
    fun replayModeAllowsEverythingWithoutNetworkOrReceipts() {
        transport.ok(signer.envelope(document(42)))
        started(engine()).dispose()
        transport.etags.clear()
        transport.receipts.clear()
        replaying = true
        transport.ok(signer.envelope(document(43)))
        val e = started(engine(modes = mapOf("checkout.*" to EmergencyFailureMode.FAIL_CLOSED)))
        refresh(e)
        e.onForeground()
        drain()
        assertEquals(0, transport.fetches)
        assertTrue(transport.receipts.isEmpty())
        assertNull(e.scheduledDelayMs)
        val d = decision(e)
        assertTrue(d.allowed)
        assertEquals(EmergencyDecisionSource.REPLAY, d.source)
        assertNull(d.reasonCode)
        assertNull(e.getPolicy("checkout.confirm_order", null))
        assertTrue(e.status().replaying)
    }

    // -------------------------------------------------------------- observers

    @Test
    fun listenersHearAboutNewRevisionsAndStateChangesOnly() {
        transport.ok(signer.envelope(document(42)))
        val e = started(engine())
        assertEquals(1, snapshots.size)
        assertEquals(42L, snapshots[0].revision)
        assertEquals(EmergencyState.USABLE, snapshots[0].state)
        assertEquals(1, snapshots[0].policies.size)

        transport.ok(signer.envelope(document(42, issuedAt = T0 + 1000)))
        refresh(e)
        assertEquals("same revision, same decisions", 1, snapshots.size)

        transport.ok(signer.envelope(document(43, emptyList(), issuedAt = T0 + 2000)))
        refresh(e)
        assertEquals(2, snapshots.size)
        assertEquals(43L, snapshots[1].revision)

        now = T0 + 2000 + 3_600_000 + 300_001
        transport.answer(EmergencyFetchResult.Failed("offline"))
        refresh(e)
        assertEquals(3, snapshots.size)
        assertEquals(EmergencyState.EXPIRED, snapshots[2].state)
    }

    // ---------------------------------------------------------- thread safety

    @Test
    fun concurrentEvaluationsNeverSeeAHalfAppliedRevision() {
        // Even revisions block with reason R<rev>, odd ones allow: a decision
        // mixing two snapshots would pair a revision with the wrong outcome.
        fun doc(rev: Long) = document(
            rev,
            if (rev % 2 == 0L) listOf(policy(reason = "R$rev", id = "p$rev")) else listOf(policy(action = "ALLOW", reason = "R$rev", id = "p$rev")),
            issuedAt = T0 + rev,
        )
        transport.ok(signer.envelope(doc(2)))
        val e = started(engine())
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val readers = (0 until 4).map {
            Thread {
                var seen = 0L
                while (!stop.get()) {
                    val d = e.evaluate("checkout.confirm_order", null)
                    val rev = d.documentRevision
                    val ok = rev != null && d.reasonCode == "R$rev" && d.policyId == "p$rev" &&
                        d.allowed == (rev % 2 == 1L) && rev >= seen
                    if (!ok) failure.compareAndSet(null, d.toString())
                    if (rev != null) seen = rev
                }
            }.apply { start() }
        }
        for (rev in 3L..60L) {
            transport.ok(signer.envelope(doc(rev)))
            e.refresh().get(10, TimeUnit.SECONDS)
        }
        stop.set(true)
        readers.forEach { it.join(10_000) }
        assertNull(failure.get())
        assertEquals(60L, e.status().revision)
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun noBackendIsAFailedFetch() {
        hasBackend = false
        val e = started(engine())
        assertNotNull(e.status().lastError)
        assertTrue(e.scheduledDelayMs!! in 48_000..72_000)
        assertEquals(0, transport.fetches)
    }

    @Test
    fun isoInstantsParseLikeTheBackend() {
        assertEquals(T0, EmergencyContract.parseInstant("2026-10-29T08:00:00.000Z"))
        assertEquals(T0, EmergencyContract.parseInstant("2026-10-29T08:00:00Z"))
        assertEquals(T0 + 123, EmergencyContract.parseInstant("2026-10-29T08:00:00.123456Z"))
        assertEquals(T0, EmergencyContract.parseInstant("2026-10-29T10:00:00.000+02:00"))
        assertEquals(951_782_400_000L, EmergencyContract.parseInstant("2000-02-29T00:00:00Z"))
        assertEquals(0L, EmergencyContract.parseInstant("1970-01-01T00:00:00.000Z"))
        for (bad in listOf("2026-02-29T00:00:00Z", "2026-10-29 08:00:00Z", "2026-10-29T24:00:00Z", "2026-10-29T08:00:00", "x")) {
            assertNull(bad, EmergencyContract.parseInstant(bad))
        }
    }
}
