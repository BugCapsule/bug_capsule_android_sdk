package dev.bugcapsule.android.emergency

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The public `BugCapsule.emergency` facade in an Android process. */
@RunWith(RobolectricTestRunner::class)
class BugCapsuleEmergencyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private val signer = TestSigner("ek_facade")
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    private var policiesBody: String? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BugCapsule.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return when {
                    path.contains("/emergency/policies") ->
                        policiesBody?.let { MockResponse().setBody(it).setHeader("ETag", "\"e1\"") } ?: MockResponse().setResponseCode(503)
                    path.contains("/emergency/receipts") -> MockResponse().setResponseCode(204)
                    path.startsWith("/v1/sdk/config") -> MockResponse().setBody("""{"enabled":false}""")
                    else -> MockResponse().setResponseCode(404)
                }
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

    private fun hooks(
        foreground: Boolean = false,
        transport: FakeTransport? = null,
        now: Long = T0 + 60_000,
        executor: java.util.concurrent.ScheduledThreadPoolExecutor? = null,
    ) = EmergencyTestHooks(
        storageDir = tmp.root,
        transport = transport?.let { t -> { t } },
        executor = executor,
        nowMs = { now },
        foreground = foreground,
    )

    private fun config(anchor: String = signer.anchor()) = EmergencyConfig.builder()
        .trustAnchor(anchor)
        .backend(server.url("/").toString(), "bct_test_key_0123456789")

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun callsBeforeInitializeUseTheFailureModeAndNeverThrow() {
        val e = BugCapsule.emergency
        assertFalse(e.isInitialized)
        val d = e.evaluate("checkout.confirm_order")
        assertTrue(d.allowed)
        assertEquals(EmergencyAction.ALLOW, d.action)
        assertEquals("POLICY_UNAVAILABLE", d.reasonCode)
        assertEquals(EmergencyDecisionSource.FAILURE_MODE, d.source)
        assertTrue(e.isFeatureAllowed("Not A Key!"))
        assertTrue(e.evaluate("", EmergencyContext("u1")).allowed)
        assertNull(e.getPolicy("checkout.confirm_order"))
        val status = e.refreshPolicies().get(1, TimeUnit.SECONDS)
        assertFalse(status.initialized)
        assertEquals(EmergencyState.NONE, status.state)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun fetchesVerifiesAndNotifiesListenersOnTheMainThread() {
        policiesBody = signer.envelope(document(42))
        val seen = CopyOnWriteArrayList<Pair<EmergencySnapshot, Boolean>>()
        BugCapsule.emergency.addListener { s -> seen += s to (Looper.myLooper() == Looper.getMainLooper()) }
        BugCapsule.emergency.addListener { throw IllegalStateException("a broken listener") }
        BugCapsule.emergency.testHooks = hooks()
        BugCapsule.emergency.initialize(app, config().failureMode("checkout.*", EmergencyFailureMode.FAIL_CLOSED).build())
        assertTrue(BugCapsule.emergency.isInitialized)
        val status = BugCapsule.emergency.refreshPolicies().get(10, TimeUnit.SECONDS)
        assertEquals(42L, status.revision)
        assertEquals(EmergencyState.USABLE, status.state)
        val d = BugCapsule.emergency.evaluate("checkout.confirm_order")
        assertEquals(EmergencyAction.BLOCK, d.action)
        assertEquals(EmergencyDecisionSource.POLICY, d.source)
        assertFalse(BugCapsule.emergency.isFeatureAllowed("checkout.confirm_order"))
        assertEquals("p-1", BugCapsule.emergency.getPolicy("checkout.confirm_order")?.policyId)

        idleMain()
        assertEquals(1, seen.size)
        assertEquals(42L, seen[0].first.revision)
        assertTrue("delivered on the main thread", seen[0].second)

        val fetch = requests.first { it.path!!.contains("/policies") }
        assertTrue(fetch.path!!.startsWith("/v1/apps/$APP_ID/emergency/policies?environment=production&platform=NATIVE_ANDROID"))
        assertEquals("Bearer bct_test_key_0123456789", fetch.getHeader("Authorization"))
        val deadline = System.currentTimeMillis() + 5_000
        while (requests.none { it.path!!.contains("/receipts") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val receipt = requests.first { it.path!!.contains("/receipts") }
        assertTrue(receipt.body.readUtf8().contains("\"status\":\"APPLIED\""))
        assertTrue(tmp.root.resolve("emergency/policy_cache.json").isFile)
        assertTrue(tmp.root.resolve("emergency/installation_id").isFile)
    }

    @Test
    fun reusesTheConnectionOfBugCapsuleInitEvenWithCaptureDisabled() {
        policiesBody = signer.envelope(document(7))
        BugCapsule.testOverrides = BugCapsuleTestOverrides(storageDir = tmp.root.resolve("capture"))
        BugCapsule.init(app, BugCapsuleConfig.builder().backend(server.url("/").toString(), "bct_shared_key_0123456789").enabled(false).build())
        BugCapsule.emergency.testHooks = hooks()
        BugCapsule.emergency.initialize(app, EmergencyConfig.builder().trustAnchor(signer.anchor()).build())
        val status = BugCapsule.emergency.refreshPolicies().get(10, TimeUnit.SECONDS)
        assertEquals(7L, status.revision)
        val fetch = requests.first { it.path!!.contains("/emergency/policies") }
        assertEquals("Bearer bct_shared_key_0123456789", fetch.getHeader("Authorization"))
    }

    @Test
    fun invalidAnchorUsesFailureModesWithoutNetwork() {
        BugCapsule.emergency.testHooks = hooks()
        BugCapsule.emergency.initialize(app, config("not-an-anchor").failureMode("payments.*", EmergencyFailureMode.FAIL_CLOSED).build())
        assertEquals(EmergencyAction.BLOCK, BugCapsule.emergency.evaluate("payments.send").action)
        assertTrue(BugCapsule.emergency.isFeatureAllowed("search.query"))
        val status = BugCapsule.emergency.refreshPolicies().get(10, TimeUnit.SECONDS)
        assertFalse(status.trustAnchorValid)
        assertTrue(status.lastError!!.contains("trust anchor"))
        assertTrue(requests.none { it.path!!.contains("/emergency/") })
    }

    @Test
    fun aSecondInitializeIsIgnored() {
        val t = FakeTransport()
        BugCapsule.emergency.testHooks = hooks(transport = t)
        BugCapsule.emergency.initialize(app, config().environment("staging").build())
        BugCapsule.emergency.initialize(app, config().environment("production").build())
        assertEquals("staging", BugCapsule.emergency.status().environment)
    }

    @Test
    fun activityLifecycleDrivesForegroundRefreshes() {
        val t = FakeTransport()
        val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        fun drain() = repeat(3) { executor.submit {}.get(10, TimeUnit.SECONDS) }
        try {
            BugCapsule.emergency.testHooks = hooks(foreground = false, transport = t, executor = executor)
            BugCapsule.emergency.initialize(app, config().build())
            drain()
            val engine = BugCapsule.emergency.engineForTest()!!
            assertEquals("the startup fetch", 1, t.fetches)
            assertNull("no timers while in the background", engine.scheduledDelayMs)

            t.answer(EmergencyFetchResult.Failed("offline"))
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            drain()
            assertEquals("the first started activity fetches", 2, t.fetches)
            assertTrue("foreground: the retry is scheduled", engine.scheduledDelayMs != null)

            controller.pause().stop()
            drain()
            assertNull("stopped: no timers", engine.scheduledDelayMs)
            controller.destroy()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun resetForgetsListenersAndState() {
        val l = EmergencyListener { fail("removed") }
        BugCapsule.emergency.addListener(l)
        BugCapsule.emergency.removeListener(l)
        BugCapsule.emergency.testHooks = hooks(transport = FakeTransport())
        BugCapsule.emergency.initialize(app, config().failureMode("a.*", EmergencyFailureMode.FAIL_CLOSED).build())
        assertSame(EmergencyAction.BLOCK, BugCapsule.emergency.evaluate("a.b").action)
        BugCapsule.emergency.resetForTest()
        assertFalse(BugCapsule.emergency.isInitialized)
        assertTrue(BugCapsule.emergency.evaluate("a.b").allowed)
    }

    @Test
    fun builderValidatesAndClamps() {
        val c = EmergencyConfig.builder().refreshIntervalMs(1_000).build()
        assertEquals(15_000L, c.refreshIntervalMs)
        assertEquals("production", c.environment)
        assertEquals(60_000L, EmergencyConfig.builder().build().refreshIntervalMs)
        for (bad in listOf("Production", "", "-x", "a".repeat(41))) {
            try {
                EmergencyConfig.builder().environment(bad)
                fail("environment \"$bad\" accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (bad in listOf("*", "checkout.", "Checkout.pay", "checkout.*.x", ".*")) {
            try {
                EmergencyConfig.builder().failureMode(bad, EmergencyFailureMode.FAIL_CLOSED)
                fail("pattern \"$bad\" accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        val modes = EmergencyConfig.builder().failureMode("checkout.*", EmergencyFailureMode.FAIL_CLOSED)
            .failureMode("checkout.view", EmergencyFailureMode.FAIL_OPEN).build().failureModes
        assertEquals(2, modes.size)
    }
}
