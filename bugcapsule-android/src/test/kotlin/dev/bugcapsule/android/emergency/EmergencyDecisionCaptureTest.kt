package dev.bugcapsule.android.emergency

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BeforeRecord
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import dev.bugcapsule.android.internal.CrashCapsules
import dev.bugcapsule.android.internal.Runtime
import dev.bugcapsule.android.replay.BugCapsuleReplay
import dev.bugcapsule.android.replay.CrashReplay
import dev.bugcapsule.android.replay.FakeDriver
import dev.bugcapsule.android.replay.ReplayDriver
import dev.bugcapsule.android.replay.TestCapsules
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * §7.8 through the real runtime: a signed document, the flight recorder, the
 * capture rules and the crash journal on one side; the capsule that comes out,
 * armed and replayed through the real engine, on the other.
 */
@RunWith(RobolectricTestRunner::class)
class EmergencyDecisionCaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "checkout.confirm_order"
    private val signer = TestSigner("ek_decisions")
    private val transport = FakeTransport()
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private val results = CopyOnWriteArrayList<String>()

    @Volatile
    private var capsuleBytes = ByteArray(0)

    private val instrumentation = object : Instrumentation() {
        override fun getTargetContext(): Context = app
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        BugCapsule.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/a/t/settings" -> MockResponse().setBody(Json.stringify(ReplaySettings(attemptId = "a1", settleTimeoutMs = 100).toJson()))
                "/a/t/capsule" -> MockResponse().setBody(Buffer().write(capsuleBytes))
                "/a/t/result" -> {
                    results.add(request.body.readUtf8())
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
        server.shutdown()
    }

    // ----------------------------------------------------------------- setup

    /** A real policy document blocking [key], fetched and verified. */
    private fun guard(provider: EmergencyUpdateProvider? = null) {
        transport.ok(signer.envelope(document(42)))
        initializeGuard(provider)
        assertEquals(42L, BugCapsule.emergency.refreshPolicies().get(10, TimeUnit.SECONDS).revision)
    }

    private fun initializeGuard(provider: EmergencyUpdateProvider? = null) {
        BugCapsule.emergency.testHooks = EmergencyTestHooks(
            storageDir = File(tmp.root, "guard"),
            transport = { transport },
            nowMs = { T0 + 60_000 },
            deliver = { it.run() },
            foreground = false,
        )
        BugCapsule.emergency.initialize(
            app,
            EmergencyConfig.builder().trustAnchor(signer.anchor()).backend("http://backend.test", "bct_test_key_0123456789")
                .updateProvider(provider).build(),
        )
    }

    private fun start(hook: BeforeRecord? = null, mode: CaptureMode = CaptureMode.QA, configure: BugCapsuleConfig.Builder.() -> Unit = {}) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, null)
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().mode(mode).gitSha("abc123").autoCaptureOnFailure(false).beforeRecord(hook).apply(configure).build(),
        )
    }

    private fun runtime(): Runtime = BugCapsule.runtimeForTest()!!

    private fun capturedFile(): File = BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file

    private fun captured(): Capsule = CapsuleCodec.decode(capturedFile().readBytes())

    private fun decisions(c: Capsule): List<CapsuleEvent> = c.events.filter { it.type == EventTypes.EMERGENCY_DECISION }

    private fun capability(c: Capsule) = c.manifest.capabilities[EMERGENCY_DECISIONS_CAPABILITY]

    private fun journalCapability(): Any? {
        val header = J.obj(Json.parse(File(runtime().syncJournal()!!, "header.json").readText()))!!
        return J.obj(header["capabilities"])!![EMERGENCY_DECISIONS_CAPABILITY]
    }

    // ------------------------------------------------------------- recording

    @Test
    fun aBlockedFeatureIsInTheCapsuleOnce() {
        guard()
        start()
        repeat(50) { assertFalse(BugCapsule.emergency.evaluate(key).allowed) }
        val c = captured()
        val d = decisions(c).single().data
        assertEquals(key, d["key"])
        assertEquals("BLOCK", d["action"])
        assertEquals("policy", d["source"])
        assertEquals("PRICING_SAFETY", d["reasonCode"])
        assertEquals("p-1", d["policyId"])
        assertEquals(42L, d["documentRevision"])
        assertEquals("Checkout is temporarily unavailable.", d["message"])
        assertEquals("supported", capability(c))
    }

    @Test
    fun aNewRevisionIsANewDecision() {
        guard()
        start()
        BugCapsule.emergency.evaluate(key)
        transport.ok(signer.envelope(document(43, issuedAt = T0 + 1_000)))
        assertEquals(43L, BugCapsule.emergency.refreshPolicies().get(10, TimeUnit.SECONDS).revision)
        BugCapsule.emergency.evaluate(key)
        BugCapsule.emergency.evaluate(key)
        assertEquals(listOf<Any?>(42L, 43L), decisions(captured()).map { it.data["documentRevision"] })
    }

    @Test
    fun aDecisionMadeBeforeRecordingStartedIsWritten() {
        guard()
        BugCapsule.emergency.evaluate(key)
        start()
        BugCapsule.emergency.evaluate(key)
        assertEquals("BLOCK", decisions(captured()).single().data["action"])
    }

    @Test
    fun aDecisionBeforeTheGuardIsInitializedIsWrittenToo() {
        BugCapsule.emergency.evaluate(key)
        start()
        val d = decisions(captured()).single().data
        assertEquals("ALLOW", d["action"])
        assertEquals("failure_mode", d["source"])
    }

    @Test
    fun aWindowThatStartsAtAPeriodicCheckpointKnowsTheDecision() {
        guard()
        start { maxEvents(50) }
        BugCapsule.emergency.evaluate(key)
        runtime().periodicCheckpoint()
        repeat(200) { BugCapsule.log("filler $it") }
        runtime().periodicCheckpoint()
        val c = captured()
        assertEquals(CheckpointKind.PERIODIC, c.checkpoint.kind)
        val d = decisions(c).first()
        assertTrue("written after the checkpoint", d.seq > c.checkpoint.afterSeq)
        assertEquals("BLOCK", d.data["action"])
        assertEquals("supported", capability(c))
    }

    @Test
    fun theSubjectIsNeverWrittenOnlyItsTag() {
        guard()
        start()
        BugCapsule.emergency.evaluate(key, EmergencyContext("user-1"))
        BugCapsule.emergency.evaluate(key, EmergencyContext("user-1"))
        val file = capturedFile()
        assertEquals("748982dd71b97baf", decisions(CapsuleCodec.decode(file.readBytes())).single().data["subject"])
        assertFalse(String(GZIPInputStream(file.inputStream()).readBytes()).contains("user-1"))
    }

    @Test
    fun pastThePairLimitTheCapsuleIsPartial() {
        guard()
        start()
        for (i in 0..EmergencyDecisionTracker.MAX_PAIRS) BugCapsule.emergency.evaluate(key, EmergencyContext("user-$i"))
        val c = captured()
        assertEquals(EmergencyDecisionTracker.MAX_PAIRS, decisions(c).size)
        assertEquals("partial", capability(c))
    }

    @Test
    fun aDecisionTheHookDropsMakesTheCapsulePartial() {
        guard()
        start(hook = { type, data -> if (type == EventTypes.EMERGENCY_DECISION) null else data })
        assertFalse(BugCapsule.emergency.evaluate(key).allowed)
        val c = captured()
        assertTrue(decisions(c).isEmpty())
        assertEquals("partial", capability(c))
    }

    @Test
    fun theHookSeesTheEventAndCanChangeIt() {
        guard()
        start(hook = { type, data -> if (type == EventTypes.EMERGENCY_DECISION) data + ("message" to "edited") else data })
        BugCapsule.emergency.evaluate(key)
        assertEquals("edited", decisions(captured()).single().data["message"])
    }

    @Test
    fun aHookThatThrowsNeverReachesTheApp() {
        guard()
        start(hook = { type, data -> if (type == EventTypes.EMERGENCY_DECISION) throw IllegalStateException("hook") else data })
        assertEquals(EmergencyAction.BLOCK, BugCapsule.emergency.evaluate(key).action)
        assertEquals("partial", capability(captured()))
    }

    @Test
    fun appRedactionRulesApply() {
        guard()
        start { redactKeys("reasonCode") }
        BugCapsule.emergency.evaluate(key)
        val d = decisions(captured()).single().data
        assertEquals("[REDACTED]", d["reasonCode"])
        assertEquals(key, d["key"])
    }

    @Test
    fun anAppWithoutGuardsSaysThereAreNone() {
        start()
        val c = captured()
        assertTrue(decisions(c).isEmpty())
        assertEquals("supported", capability(c))
    }

    @Test
    fun aSessionSampledOutWritesNothingAndStaysComplete() {
        guard()
        var hookCalls = 0
        start(hook = { _, data ->
            hookCalls++
            data
        }, mode = CaptureMode.PRODUCTION) { productionSampleRate(0.0) }
        assertFalse(BugCapsule.isRecording)
        assertFalse(BugCapsule.emergency.evaluate(key).allowed)
        assertEquals(0, hookCalls)
        assertTrue(BugCapsule.emergency.decisionsComplete)
    }

    // --------------------------------------------------------- crash journal

    @Test
    fun theJournalHeaderTurnsPartialWhenADecisionIsDropped() {
        guard()
        start(hook = { type, data -> if (type == EventTypes.EMERGENCY_DECISION) null else data })
        assertEquals("supported", journalCapability())
        BugCapsule.emergency.evaluate(key)
        assertEquals("partial", journalCapability())
    }

    private fun pay() {
        if (!BugCapsule.emergency.evaluate(key).allowed) BugCapsule.session!!.reportError(IllegalStateException("blocked path crashes"))
    }

    @Test
    fun aCrashCapsuleHasTheDecisionsAndTheCapabilityAndReplaysOnlyWithThem() {
        guard()
        start()
        val rt = runtime()
        rt.onFirstDraw()
        BugCapsule.action("pay", Runnable { BugCapsule.emergency.evaluate(key) }).run()
        val journal = CrashCapsules.read(rt.syncJournal()!!)!!
        val failure = FailureRecord.crash("SIGSEGV", "", 0, "signal", 11, null)
        val built = CrashCapsules.build(
            journal,
            CrashCapsules.Crash("c0ffee00-0000-4000-8000-000000000002", failure, "signal", System.currentTimeMillis(), mapOf("eventId" to "e1")),
            5_000_000,
            { _, _, _ -> },
        )!!
        val capsule = CapsuleCodec.decode(built.bytes)
        assertEquals("BLOCK", decisions(capsule).single().data["action"])
        assertEquals("supported", capability(capsule))
        BugCapsule.resetForTest()

        fun crashReplay(c: Capsule): ReplayReport {
            capsuleBytes = CapsuleCodec.encode(c)
            assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
            BugCapsule.init(app, BugCapsuleConfig.builder().build())
            val engine = BugCapsuleReplay.awaitEngine(1000)!!
            // The blocked path is the one that dies: a real signal would end this test's process.
            BugCapsule.registerAction("pay") {
                if (!BugCapsule.emergency.evaluate(key).allowed) engine.onDeath(CrashReplay.Death("SIGSEGV", "signal", "tombstone", signal = 11))
            }
            val driver = object : ReplayDriver by FakeDriver() {
                override fun postToMain(task: Runnable) = task.run()
                override fun mainThread(): Thread? = null
            }
            return engine.run(driver).also { BugCapsule.resetForTest() }
        }
        assertEquals(ReplayOutcome.REPRODUCED, crashReplay(capsule).outcome)
        assertEquals(ReplayOutcome.NOT_REPRODUCED, crashReplay(withoutDecisions(capsule)).outcome)
    }

    // ---------------------------------------------------------------- replay

    private val url get() = "http://127.0.0.1:${server.port}/a/t"

    private fun withoutDecisions(c: Capsule): Capsule {
        val events = c.events.filter { it.type != EventTypes.EMERGENCY_DECISION }
        assertNotEquals(c.events.size, events.size)
        return c.copy(manifest = c.manifest.copy(eventCount = events.size), events = events)
    }

    /** A capsule (without a capability) saying [key] is blocked for `user-1`. */
    private fun blockedCapsule() = CapsuleCodec.encode(
        TestCapsules.capsule(
            listOf(
                CapsuleEvent(
                    1, 10, EventTypes.EMERGENCY_DECISION,
                    mapOf(
                        "key" to key, "subject" to "748982dd71b97baf", "action" to "BLOCK", "source" to "policy",
                        "reasonCode" to "INCIDENT", "policyId" to "p-9", "policyRevision" to 3L, "documentRevision" to 42L,
                        "message" to "Payments are paused.",
                    ),
                ),
                CapsuleEvent(2, 20, EventTypes.EMERGENCY_DECISION, mapOf("key" to "broken.future", "action" to "SOME_FUTURE_ACTION")),
            ),
        ),
    )

    private fun assertRecorded(d: EmergencyDecision) {
        assertEquals(EmergencyAction.BLOCK, d.action)
        assertFalse(d.allowed)
        assertEquals(EmergencyDecisionSource.REPLAY, d.source)
        assertEquals("INCIDENT", d.reasonCode)
        assertEquals("p-9", d.policyId)
        assertEquals(3L, d.policyRevision)
        assertEquals(42L, d.documentRevision)
        assertEquals("Payments are paused.", d.publicUserMessage)
    }

    @Test
    fun everyReplayPathReturnsTheRecordedDecision() {
        capsuleBytes = blockedCapsule()
        val subject = EmergencyContext("user-1")
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))

        // Armed, nothing started yet.
        assertRecorded(BugCapsule.emergency.evaluate(key, subject))
        // BugCapsule running, the guard not initialized.
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        assertTrue(BugCapsule.isReplaying)
        assertRecorded(BugCapsule.emergency.evaluate(key, subject))
        // The guard initialized: its engine answers.
        initializeGuard()
        assertRecorded(BugCapsule.emergency.evaluate(key, subject))
        assertFalse(BugCapsule.emergency.isFeatureAllowed(key, subject))
        assertNull(BugCapsule.emergency.getPolicy(key, subject))

        // A subject the capsule doesn't know uses the key's entry; a key it doesn't know is allowed.
        assertRecorded(BugCapsule.emergency.evaluate(key, EmergencyContext("user-2")))
        val other = BugCapsule.emergency.evaluate("other.feature")
        assertTrue(other.allowed)
        assertEquals(EmergencyDecisionSource.REPLAY, other.source)
        // An unknown action is not a block.
        val future = BugCapsule.emergency.evaluate("broken.future")
        assertTrue(future.allowed)
        assertEquals(EmergencyDecisionSource.REPLAY, future.source)

        assertEquals(0, transport.fetches)
        assertTrue(transport.receipts.isEmpty())
    }

    @Test
    fun theGuardInitializedBeforeBugCapsuleStartsAlsoReplays() {
        capsuleBytes = blockedCapsule()
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        initializeGuard()
        assertRecorded(BugCapsule.emergency.evaluate(key))
        assertEquals(0, transport.fetches)
    }

    @Test
    fun replayIsNotRecordedAndDoesNotChangeTheTracker() {
        capsuleBytes = blockedCapsule()
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        var hookCalls = 0
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().beforeRecord { _, data ->
                hookCalls++
                data
            }.build(),
        )
        repeat(5) { BugCapsule.emergency.evaluate(key) }
        assertEquals(0, hookCalls)
        assertTrue(BugCapsule.emergency.decisionsComplete)
    }

    @Test
    fun aPostMortemProcessAllowsEverything() {
        assertTrue(BugCapsuleReplay.armPostMortem(instrumentation, url, null))
        val d = BugCapsule.emergency.evaluate(key)
        assertTrue(d.allowed)
        assertEquals(EmergencyDecisionSource.REPLAY, d.source)
    }

    @Test
    fun aReplayedRequireUpdateNeverReachesTheStore() {
        capsuleBytes = blockedCapsule()
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        val provider = CountingProvider()
        initializeGuard(provider)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val answers = ArrayList<EmergencyUpdateAvailability>()
        val updates = BugCapsule.emergency.updates
        updates.checkForUpdate(EmergencyUpdateRequest(requiredVersion = "2.4.1")) { answers += it }
        updates.startUpdate(activity, EmergencyUpdateRequest(requiredVersion = "2.4.1"))
        updates.resumeUpdate(activity)
        assertFalse(updates.completeUpdate())
        assertEquals(0, provider.calls)
        assertFalse(answers.single().available)
    }

    @Test
    fun outsideReplayTheStoreIsReached() {
        val provider = CountingProvider()
        guard(provider)
        BugCapsule.emergency.updates.checkForUpdate(EmergencyUpdateRequest(requiredVersion = "2.4.1")) {}
        assertEquals(1, provider.calls)
    }

    private class CountingProvider : EmergencyUpdateProvider {
        var calls = 0
        override val provider = "counting"

        override fun checkAvailability(request: EmergencyUpdateRequest, callback: EmergencyUpdateCallback) {
            calls++
        }

        override fun startUpdate(activity: Activity, request: EmergencyUpdateRequest) {
            calls++
        }

        override fun resumeUpdate(activity: Activity) {
            calls++
        }

        override fun completeUpdate(): Boolean {
            calls++
            return true
        }

        override fun setListener(listener: EmergencyUpdateListener?) {}
    }

    /** A real capture of the blocked path, armed and replayed through the runtime. */
    private fun replay(capsule: Capsule): ReplayReport {
        capsuleBytes = CapsuleCodec.encode(capsule)
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        BugCapsule.registerAction("pay") { pay() }
        val report = BugCapsuleReplay.awaitEngine(1000)!!.run(FakeDriver())
        BugCapsule.resetForTest()
        return report
    }

    @Test
    fun theBlockedPathReproducesOnlyWithItsDecision() {
        guard()
        start()
        runtime().onFirstDraw()
        BugCapsule.action("pay", Runnable { pay() }).run()
        val capsule = captured()
        assertNotNull(capsule.manifest.failure)
        assertEquals("BLOCK", decisions(capsule).single().data["action"])
        BugCapsule.resetForTest()

        val faithful = replay(capsule)
        assertEquals(faithful.toJson().toString(), ReplayOutcome.REPRODUCED, faithful.outcome)
        // What every replay did before: all features allowed, bug not reached.
        val allowed = replay(withoutDecisions(capsule))
        assertEquals(allowed.toJson().toString(), ReplayOutcome.NOT_REPRODUCED, allowed.outcome)
    }
}
