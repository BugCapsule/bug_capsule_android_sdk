package dev.bugcapsule.okhttp

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.ReplaySettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OkHttpReplayTest {
    private val session = FakeSession()
    private val bugCapsule = BugCapsuleOkHttp().also { it.setUp(session) }
    private var appInterceptorRuns = 0
    private val client = OkHttpClient.Builder()
        .addInterceptor { appInterceptorRuns++; it.proceed(it.request()) }
        .addInterceptor(bugCapsule.interceptor)
        .addInterceptor { throw AssertionError("the transport below BugCapsule must not run in replay") }
        .build()

    private fun fixture(seq: Long, key: String, occurrence: Int, body: Any?, durationMs: Long = 0, status: Int = 200, error: Map<String, Any?>? = null) =
        CapsuleEvent(seq, seq * 10, "http", linkedMapOf(
            "key" to key, "occurrence" to occurrence.toLong(), "status" to status.toLong(), "durationMs" to durationMs,
            "responseHeaders" to mapOf("content-type" to "application/json", "content-length" to "999"),
            "responseBody" to body, "bodyEncoding" to if (body == null) "omitted" else "json", "error" to error,
        ))

    private fun replay(vararg events: CapsuleEvent, strict: Boolean = true): FakeReplay {
        val r = FakeReplay.of(events.toList())
        session.replay = FakeReplay(r.capsule, ReplaySettings(strictNetwork = strict))
        return session.replay as FakeReplay
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }

    @Test
    fun servesByKeyAndOccurrenceThenReusesTheLast() {
        val r = replay(fixture(1, "GET api.test/a", 0, mapOf("n" to 1L)), fixture(2, "GET api.test/a", 1, mapOf("n" to 2L)))
        assertEquals(200 to "{\"n\":1}", get("https://api.test/a"))
        assertEquals(200 to "{\"n\":2}", get("https://api.test/a"))
        assertEquals(200 to "{\"n\":2}", get("https://api.test/a"))
        assertTrue(r.divergences.any { it.type == "network.reused" })
        assertEquals(3, r.served.get())
        assertEquals("app interceptors still run", 3, appInterceptorRuns)
    }

    @Test
    fun recordedLatencyIsKeptSoRacesReplay() {
        replay(fixture(1, "GET api.test/slow", 0, mapOf("s" to 1L), durationMs = 400), fixture(2, "GET api.test/fast", 0, mapOf("f" to 1L), durationMs = 50))
        val pool = Executors.newFixedThreadPool(2)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val a = pool.submit { get("https://api.test/slow"); order.add("slow") }
        val b = pool.submit { get("https://api.test/fast"); order.add("fast") }
        a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("fast", "slow"), order)
        val started = System.nanoTime()
        get("https://api.test/slow") // reused; still delayed
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 380)
    }

    @Test
    fun requestBodiesTakePartInTheKey() {
        val keyed = BugCapsuleOkHttp().also { it.setUp(FakeSession()) }
        val key = keyed.keyFor(session, Request.Builder().url("https://api.test/q").post("{\"b\":1}".toRequestBody("application/json".toMediaType())).build(),
            BugCapsuleOkHttp.RequestBytes("{\"b\":1}".toByteArray(), complete = true, unreadable = false))
        replay(fixture(1, key, 0, mapOf("ok" to true)))
        val res = client.newCall(Request.Builder().url("https://api.test/q").post("{\"b\":1}".toRequestBody("application/json".toMediaType())).build()).execute()
        assertEquals("{\"ok\":true}", res.body!!.string())
    }

    @Test
    fun unrecordedRequestsFailStrictly() {
        val r = replay()
        try {
            get("https://api.test/never")
            fail("expected ConnectException")
        } catch (e: ConnectException) {
            assertTrue(e.message!!.contains("no recorded response"))
        }
        assertTrue(r.divergences.any { it.type == "network.unrecorded" && it.detail == "GET api.test/never" })
    }

    @Test
    fun lenientNetworkReachesTheRealServer() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("real")); start() }
        val r = replay(strict = false)
        val lenient = OkHttpClient.Builder().addInterceptor(bugCapsule.interceptor).build()
        assertEquals("real", lenient.newCall(Request.Builder().url(server.url("/x")).build()).execute().body!!.string())
        assertTrue(r.divergences.any { it.type == "network.unrecorded" })
        server.shutdown()
    }

    @Test
    fun recordedErrorsAreRaisedNatively() {
        replay(
            fixture(1, "GET api.test/t", 0, null, status = 0, error = mapOf("type" to "receiveTimeout", "message" to "timeout")),
            fixture(2, "GET api.test/c", 0, null, status = 0, error = mapOf("type" to "connectionError", "message" to "refused")),
        )
        try { get("https://api.test/t"); fail() } catch (_: SocketTimeoutException) {}
        try { get("https://api.test/c"); fail() } catch (_: ConnectException) {}
    }

    @Test
    fun missingBodiesAreReported() {
        val r = replay(fixture(1, "GET api.test/nb", 0, null))
        get("https://api.test/nb")
        assertTrue(r.divergences.any { it.type == "network.noBody" })
    }

    @Test
    fun cancellationStopsTheDelay() {
        replay(fixture(1, "GET api.test/long", 0, mapOf("x" to 1L), durationMs = 5000))
        val call = client.newCall(Request.Builder().url("https://api.test/long").build())
        Executors.newSingleThreadScheduledExecutor().schedule({ call.cancel() }, 200, TimeUnit.MILLISECONDS)
        val started = System.nanoTime()
        try {
            call.execute()
            fail("expected cancellation")
        } catch (_: IOException) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
    }

    @Test
    fun pendingExchangesAreHeldUntilCancelled() {
        val pending = CapsuleEvent(1, 10, "http", linkedMapOf("key" to "GET api.test/p", "occurrence" to 0L, "status" to 0L,
            "durationMs" to 100L, "bodyEncoding" to "omitted", "pending" to true))
        val r = replay(pending)
        val call = client.newCall(Request.Builder().url("https://api.test/p").build())
        Executors.newSingleThreadScheduledExecutor().schedule({ call.cancel() }, 700, TimeUnit.MILLISECONDS)
        val started = System.nanoTime()
        try {
            call.execute()
            fail("a pending exchange never answers")
        } catch (_: IOException) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 600)
        assertTrue(r.divergences.none { it.type == "network.unrecorded" })
    }

    @Test
    fun clientTimeoutsStillApply() {
        replay(fixture(1, "GET api.test/slow", 0, mapOf("x" to 1L), durationMs = 5000))
        val timed = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val started = System.nanoTime()
        try {
            timed.newCall(Request.Builder().url("https://api.test/slow").build()).execute()
            fail("expected timeout")
        } catch (_: IOException) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
    }
}
