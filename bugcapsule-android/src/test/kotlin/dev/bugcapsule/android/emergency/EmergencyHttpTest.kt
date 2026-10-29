package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.BugCapsuleBackendConfig
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `HttpEmergencyTransport` against a local server (emergency-client-v1 §3, §7.3). */
class EmergencyHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpEmergencyTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = HttpEmergencyTransport(BugCapsuleBackendConfig(server.url("/api/").toString(), "bct_test_key_0123456789"), "0.5.0")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun fetchSendsTheContractRequest() {
        server.enqueue(MockResponse().setBody("{\"x\":1}").setHeader("ETag", "\"v1\"").setHeader("X-BugCapsule-Emergency-Revision", "42"))
        val r = transport.fetchPolicies(APP_ID, "production", null, "2.4.1 beta") as EmergencyFetchResult.Fetched
        assertEquals("{\"x\":1}", String(r.body))
        assertEquals("\"v1\"", r.etag)
        assertEquals(42L, r.revisionHint)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals(
            "/api/v1/apps/$APP_ID/emergency/policies?environment=production&platform=NATIVE_ANDROID&app_version=2.4.1%20beta&sdk_version=0.5.0",
            req.path,
        )
        assertEquals("Bearer bct_test_key_0123456789", req.getHeader("Authorization"))
        assertNull(req.getHeader("If-None-Match"))
    }

    @Test
    fun conditionalFetchAndNotModified() {
        server.enqueue(MockResponse().setResponseCode(304))
        assertTrue(transport.fetchPolicies(APP_ID, "staging", "W/\"v1\"", null) is EmergencyFetchResult.NotModified)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("W/\"v1\"", req.getHeader("If-None-Match"))
        assertFalse("no app_version without one", req.path!!.contains("app_version"))
    }

    @Test
    fun failuresCarryStatusRetryAfterAndReason() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val busy = transport.fetchPolicies(APP_ID, "production", null, null) as EmergencyFetchResult.Failed
        assertEquals(429, busy.status)
        assertEquals(120_000L, busy.retryAfterMs)
        assertFalse(busy.credentialRejected)

        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "999999"))
        assertEquals(86_400_000L, (transport.fetchPolicies(APP_ID, "production", null, null) as EmergencyFetchResult.Failed).retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"integration_not_configured","reason":"emergency_not_configured"}"""))
        val nc = transport.fetchPolicies(APP_ID, "production", null, null) as EmergencyFetchResult.Failed
        assertEquals("emergency_not_configured", nc.reason)
        assertFalse(nc.credentialRejected)

        for (code in listOf(401, 403, 404)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue((transport.fetchPolicies(APP_ID, "production", null, null) as EmergencyFetchResult.Failed).credentialRejected)
        }
    }

    @Test
    fun bodiesOverOneMebibyteAreAFailedFetch() {
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        val r = transport.fetchPolicies(APP_ID, "production", null, null)
        assertTrue(r is EmergencyFetchResult.Failed)
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024)))
        assertTrue(transport.fetchPolicies(APP_ID, "production", null, null) is EmergencyFetchResult.Fetched)
    }

    @Test
    fun networkErrorsAreFailedFetches() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val r = transport.fetchPolicies(APP_ID, "production", null, null) as EmergencyFetchResult.Failed
        assertNull(r.status)
    }

    @Test
    fun receiptIsPostedAsJson() {
        server.enqueue(MockResponse().setResponseCode(204))
        val body = linkedMapOf<String, Any?>("environment" to "production", "revision" to 42L, "status" to "APPLIED", "errorCode" to null)
        assertEquals(204, transport.sendReceipt(APP_ID, body))
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/v1/apps/$APP_ID/emergency/receipts", req.path)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals(body, J.obj(Json.parse(req.body.readUtf8())))
    }
}
