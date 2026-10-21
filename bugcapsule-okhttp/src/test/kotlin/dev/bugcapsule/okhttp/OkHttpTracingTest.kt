package dev.bugcapsule.okhttp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import dev.bugcapsule.android.monitoring.TraceHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.TimeUnit

/** monitoring-v1 §8: http.client spans and trace propagation through the OkHttp integration. */
@RunWith(RobolectricTestRunner::class)
class OkHttpTracingTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val target = MockWebServer()
    private val other = MockWebServer()
    private val okHttp = BugCapsuleOkHttp()
    private val client = OkHttpClient.Builder().addInterceptor(okHttp.interceptor).build()
    private val transactions = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
    private val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())

    @Before
    fun setUp() {
        BugCapsule.resetForTest()
        target.start()
        other.start()
        repeat(3) {
            target.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"))
            other.enqueue(MockResponse().setBody("{}"))
        }
    }

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
        target.shutdown()
        other.shutdown()
    }

    private fun start(mode: CaptureMode = CaptureMode.QA, sampleRate: Double = 1.0) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root)
        val config = BugCapsuleConfig.builder()
            .mode(mode)
            .productionSampleRate(0.0)
            .tracesSampleRate(sampleRate)
            .tracePropagationTargets(target.url("/").toString())
            .beforeSendTransaction { tx -> tx.also { transactions.add(it.payload) } }
            .beforeSend { e, _ -> e.also { events.add(it.payload) } }
            .build()
        BugCapsule.init(ApplicationProvider.getApplicationContext<Application>(), config, listOf(okHttp))
    }

    private fun get(server: MockWebServer, path: String, baggage: String? = null): Int {
        val b = Request.Builder().url(server.url(path))
        if (baggage != null) b.header("baggage", baggage)
        return client.newCall(b.build()).execute().use { it.body!!.string(); it.code }
    }

    @Test
    fun targetsGetTraceHeadersAndCallsBecomeHttpClientSpans() {
        start()
        val tx = BugCapsule.startTransaction("/cart", "navigation", bindToScope = true)
        assertEquals(200, get(target, "/api/cart?page=2", baggage = "userId=7"))
        assertEquals(200, get(other, "/cdn/logo.png"))
        tx.finish()

        val sent = target.takeRequest(5, TimeUnit.SECONDS)!!
        val traceparent = TraceHeaders.parseTraceparent(sent.getHeader("traceparent"))!!
        assertEquals(tx.traceId, traceparent.traceId)
        assertTrue(traceparent.sampled)
        val baggage = sent.getHeader("baggage")!!
        assertTrue(baggage, baggage.startsWith("userId=7,bugcapsule-trace_id=${tx.traceId}"))
        assertTrue(baggage.contains("bugcapsule-environment=qa"))
        val notTarget = other.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(notTarget.getHeader("traceparent"))
        assertNull(notTarget.getHeader("baggage"))

        val spans = J.list(transactions.single()["spans"])!!.map { J.obj(it)!! }.filter { it["op"] == "http.client" }
        assertEquals(2, spans.size)
        assertEquals(traceparent.spanId, spans[0]["spanId"])
        assertEquals("GET ${target.url("/api/cart")}", spans[0]["description"])
        assertEquals(200, J.int(J.obj(spans[0]["data"])!!["http.response.status_code"]))
        assertEquals("ok", spans[0]["status"])
    }

    @Test
    fun traceHeadersNeverEnterTheCapsule() {
        start()
        val tx = BugCapsule.startTransaction("/cart", "navigation", bindToScope = true)
        get(target, "/api/cart")
        tx.finish()
        assertNotNull(target.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("traceparent"))
        val capsule = CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
        val http = capsule.events.single { it.type == "http" }.data
        val headers = J.obj(http["requestHeaders"])!!
        assertNull(headers["traceparent"])
        assertNull(headers["baggage"])
        // The same key as the request without trace headers: replay (which adds none) still matches.
        val plain = Request.Builder().url(target.url("/api/cart")).build()
        assertEquals(okHttp.keyFor(BugCapsule.session!!, plain, null), http["key"])
    }

    @Test
    fun withoutACapsuleRecordingCallsStillBecomeBreadcrumbs() {
        start(mode = CaptureMode.PRODUCTION, sampleRate = 0.0)
        assertTrue(!BugCapsule.isRecording)
        get(target, "/api/profile?token=abc")
        BugCapsule.captureMessage("after the call")
        val crumbs = J.list(events.single()["breadcrumbs"])!!.map { J.obj(it)!! }.filter { it["type"] == "http" }
        val data = J.obj(crumbs.single()["data"])!!
        assertEquals("GET", data["method"])
        assertEquals(200, J.int(data["status_code"]))
        assertTrue("redacted", !(data["url"] as String).contains("abc"))
        // Unsampled trace: headers still carry the trace, flagged not sampled.
        val tp = TraceHeaders.parseTraceparent(target.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("traceparent"))
        assertTrue(tp != null && !tp.sampled)
    }
}
