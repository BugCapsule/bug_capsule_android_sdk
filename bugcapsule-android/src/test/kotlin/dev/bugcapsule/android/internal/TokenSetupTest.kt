package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.ConnectionSource
import dev.bugcapsule.android.ConnectionState
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.SpecFixtures
import dev.bugcapsule.android.storage.BugCapsuleSharedPreferences
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Token-only setup (connection token v1) end to end against a stubbed
 * backend: the token becomes the backend config, refusals and network
 * failures never reach the app, and [BugCapsule.status] reports both
 * connection confirmations.
 */
@RunWith(RobolectricTestRunner::class)
class TokenSetupTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private var server = MockWebServer()
    private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = ::ok

    private val key = "bct_Q2xpZW50VmlzaWJsZUluZ2VzdE9ubHlLZXlfMDEyMzQ"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root)
        server.dispatcher = dispatcher()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
        server.shutdown()
    }

    private fun dispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            seen.add(request)
            return answer(request)
        }
    }

    private fun ok(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')?.substringAfterLast("/v1/")) {
        "sdk/config" -> MockResponse().setBody("""{"enabled":true,"sampleRate":1,"configTtlSeconds":300}""")
        "envelope" -> MockResponse().setBody("""{"accepted":1,"rejected":[]}""")
        "capsules" -> MockResponse().setResponseCode(201).setBody("""{"id":"srv-1"}""")
        else -> MockResponse().setResponseCode(404)
    }

    private val api get() = "http://127.0.0.1:${server.port}"

    /** A v1 token for [api] (the fixtures' tokens point at real hosts). */
    private fun token(api: String = this.api, key: String = this.key) = "bcsdk.v1." +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Json.stringify(mapOf("api" to api, "key" to key)).toByteArray())

    private fun logs(): String = ShadowLog.getLogsForTag(SdkLog.TAG).joinToString("\n") { it.msg }

    private fun until(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun state() = BugCapsule.status().connection.state

    private fun qa(token: String) = BugCapsuleConfig.builder().token(token).mode(CaptureMode.QA).build()

    @Test
    fun beforeInitTheStatusIsNotConfigured() {
        val s = BugCapsule.status()
        assertEquals(ConnectionState.NOT_CONFIGURED, s.connection.state)
        assertEquals("not_configured", s.connection.state.code)
        assertNull(s.connection.source)
    }

    @Test
    fun theTokenAloneConnectsAndBothConfirmationsAreSeparate() {
        val started = System.nanoTime()
        BugCapsule.init(app, token("$api/"))
        assertTrue("init does not wait for the network", (System.nanoTime() - started) / 1_000_000 < 2_000)
        until(what = "connected") { state() == ConnectionState.CONNECTED }

        val config = seen.first()
        assertEquals("GET", config.method)
        assertEquals("/v1/sdk/config", config.path)
        assertEquals("Bearer $key", config.getHeader("Authorization"))
        val s = BugCapsule.status().connection
        assertEquals(ConnectionSource.TOKEN, s.source)
        assertEquals(api, s.apiUrl)
        assertNotNull(s.configFetchedAt)
        assertNull(s.firstCapsuleAcceptedAt)
        assertNull(s.firstEventAcceptedAt)
        assertNull(s.reason)
        // Basic connectivity is not full instrumentation.
        val inst = BugCapsule.status().instrumentation
        assertEquals(listOf("network", "navigation", "app_state"), inst.missing)
        assertTrue("views" in inst.frameworks)
        assertFalse("no token problems logged: ${logs()}", Regex("can't be used|refused|ignoring token").containsMatchIn(logs()))
    }

    @Test
    fun capsulesAndEventsGoToTheTokensApiAndAreConfirmed() {
        BugCapsule.init(app, qa(token()), listOf(BugCapsuleSharedPreferences.builder().allowFile("prefs").build()))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        assertEquals(ConnectionState.CONNECTED, state())
        BugCapsule.reportError(IllegalStateException("price is null"))
        until(what = "capsule accepted") { BugCapsule.status().connection.firstCapsuleAcceptedAt != null }
        assertTrue(BugCapsule.flush(10_000))
        until(what = "event accepted") { BugCapsule.status().connection.firstEventAcceptedAt != null }
        val capsule = seen.first { it.path == "/v1/capsules" }
        assertEquals("POST", capsule.method)
        assertEquals("Bearer $key", capsule.getHeader("Authorization"))
        assertEquals("Bearer $key", seen.first { it.path == "/v1/envelope" }.getHeader("Authorization"))
        val inst = BugCapsule.status().instrumentation
        assertTrue(inst.appState)
        assertEquals(listOf("network", "navigation"), inst.missing)
        assertEquals(listOf("shared_preferences"), inst.integrations)
    }

    @Test
    fun anExplicitBackendKeepsWorkingAndWinsOverAToken() {
        val legacy = "bci_" + "k".repeat(43)
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().mode(CaptureMode.QA).backend("$api/bc/", legacy)
                .token(token("https://elsewhere.invalid")).build(),
        )
        until(what = "connected") { state() == ConnectionState.CONNECTED }
        assertEquals("/bc/v1/sdk/config", seen.first().path)
        assertEquals("Bearer $legacy", seen.first().getHeader("Authorization"))
        assertEquals(ConnectionSource.BACKEND, BugCapsule.status().connection.source)
        assertEquals("$api/bc", BugCapsule.status().connection.apiUrl)
        assertTrue(logs(), logs().contains("both token and backend are set; using backend and ignoring token"))
    }

    @Test
    fun withoutTokenOrBackendTheStatusIsNotConfigured() {
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).build())
        val s = BugCapsule.status()
        assertEquals(ConnectionState.NOT_CONFIGURED, s.connection.state)
        assertNull(s.connection.source)
        assertTrue(BugCapsule.isRecording)
    }

    @Test
    fun invalidAndUnsupportedTokensNeverThrowNorTouchTheNetwork() {
        val unsupported = J.list(SpecFixtures.json("connection-token-v1/vectors.json")["invalid"])!!
            .map { J.obj(it)!! }.first { it["error"] == "unsupported_token_version" }["token"] as String
        val cases = listOf(
            "bcsdk.v1.not-json" to ConnectionState.INVALID_TOKEN,
            "bci_" + "x".repeat(43) to ConnectionState.INVALID_TOKEN,
            "" to ConnectionState.INVALID_TOKEN,
            unsupported to ConnectionState.UNSUPPORTED_TOKEN_VERSION,
        )
        for ((token, expected) in cases) {
            ShadowLog.clear()
            BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.newFolder())
            BugCapsule.init(app, qa(token))
            val s = BugCapsule.status().connection
            assertEquals(token, expected, s.state)
            assertEquals(expected.code, s.reason)
            assertEquals(ConnectionSource.TOKEN, s.source)
            assertNull(s.apiUrl)
            assertTrue(s.message!!.isNotBlank())
            BugCapsule.reportError(IllegalStateException("still fine"))
            BugCapsule.flushUploads().get(5, TimeUnit.SECONDS)
            BugCapsule.flush(500)
            val lines = ShadowLog.getLogsForTag(SdkLog.TAG).filter { it.msg.contains("the SDK token can't be used") }
            assertEquals(logs(), 1, lines.size)
            assertEquals(android.util.Log.ERROR, lines.single().type)
            assertTrue(lines.single().msg.contains("(${expected.code})"))
            BugCapsule.resetForTest()
        }
        // The one-line convenience overload behaves the same.
        BugCapsule.init(app, "bcs_" + "s".repeat(43))
        assertEquals(ConnectionState.INVALID_TOKEN, state())
        assertTrue(BugCapsule.status().connection.message!!.contains("secret key"))
        assertEquals(0, seen.size)
    }

    @Test
    fun aRevokedTokenStopsCaptureWithTheBackendsReason() {
        answer = { r ->
            if (r.path == "/v1/sdk/config") {
                MockResponse().setResponseCode(401).setHeader("Content-Type", "application/problem+json").setBody(
                    """{"code":"unauthorized","reason":"token_revoked","detail":"this SDK token was revoked; generate a new token in the BugCapsule dashboard"}""",
                )
            } else {
                MockResponse().setResponseCode(401)
            }
        }
        BugCapsule.init(app, qa(token()))
        assertTrue(BugCapsule.isRecording)
        until(what = "rejected") { state() == ConnectionState.REJECTED }
        val s = BugCapsule.status().connection
        assertEquals("token_revoked", s.reason)
        assertTrue(s.message!!, s.message!!.contains("generate a new token"))
        assertNull(s.configFetchedAt)
        assertFalse(BugCapsule.isRecording)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        val refused = ShadowLog.getLogsForTag(SdkLog.TAG).filter { it.msg.contains("refused this app's SDK token") }
        assertEquals("logged once: ${logs()}", 1, refused.size)
        assertTrue(refused.single().msg.contains("(token_revoked): this SDK token was revoked"))
        // Nothing is sent with a refused credential.
        BugCapsule.reportError(IllegalStateException("after revocation"))
        BugCapsule.flushUploads().get(5, TimeUnit.SECONDS)
        assertTrue(seen.none { it.path == "/v1/capsules" || it.path == "/v1/envelope" })
    }

    @Test
    fun aLegacyKeyRefusalIsRejectedToo() {
        answer = { MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","reason":"key_unknown_or_revoked"}""") }
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).backend(api, "bci_" + "k".repeat(43)).build())
        until(what = "rejected") { state() == ConnectionState.REJECTED }
        assertEquals("key_unknown_or_revoked", BugCapsule.status().connection.reason)
        assertNotNull(BugCapsule.status().connection.message)
        assertTrue(logs(), logs().contains("refused this app's ingest key (key_unknown_or_revoked)"))
    }

    @Test
    fun aHangingBackendNeverBlocksStartUp() {
        answer = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val started = System.nanoTime()
        BugCapsule.init(app, qa(token()))
        assertTrue("init does not wait for the network", (System.nanoTime() - started) / 1_000_000 < 2_000)
        until(what = "config request") { seen.isNotEmpty() }
        assertEquals(ConnectionState.CONNECTING, state())
        BugCapsule.reportError(IllegalStateException("still fine"))
        BugCapsule.log("the app keeps running")
    }

    @Test
    fun serverErrorsAreOfflineNotFatal() {
        answer = { MockResponse().setResponseCode(503).setBody("""{"code":"unavailable"}""") }
        BugCapsule.init(app, qa(token()))
        until(what = "offline") { state() == ConnectionState.OFFLINE }
        assertEquals("HTTP 503", BugCapsule.status().connection.reason)
        assertTrue(BugCapsule.isRecording)
    }

    @Test
    fun offlineDataWaitsInTheOutboxAndGoesOutLater() {
        val port = server.port
        server.shutdown() // nothing listens: connection refused
        BugCapsule.init(app, qa(token("http://127.0.0.1:$port")))
        until(what = "offline") { state() == ConnectionState.OFFLINE }
        val s = BugCapsule.status().connection
        assertTrue(s.message!!, s.message!!.contains("could not be reached"))
        assertNotNull(s.reason)
        BugCapsule.reportError(IllegalStateException("while offline"))
        val rt = BugCapsule.runtimeForTest()!!
        until(what = "queued capsule") { rt.outbox!!.entries().isNotEmpty() }
        BugCapsule.flushUploads().get(5, TimeUnit.SECONDS)
        assertEquals("the capsule is kept", 1, rt.outbox!!.entries().size)
        assertTrue(BugCapsule.isRecording)

        server = MockWebServer().apply {
            dispatcher = dispatcher()
            start(InetAddress.getByName("127.0.0.1"), port)
        }
        BugCapsule.refreshRemoteConfig().get(10, TimeUnit.SECONDS)
        BugCapsule.flushUploads().get(10, TimeUnit.SECONDS)
        assertEquals(ConnectionState.CONNECTED, state())
        assertEquals(0, rt.outbox!!.entries().size)
        assertNotNull(BugCapsule.status().connection.firstCapsuleAcceptedAt)
        assertTrue(seen.any { it.path == "/v1/capsules" && it.getHeader("Authorization") == "Bearer $key" })
    }
}
