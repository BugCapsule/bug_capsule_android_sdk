package dev.bugcapsule.android.monitoring

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.feedback.BugCapsuleFeedbackDialog
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import dev.bugcapsule.android.internal.Runtime
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Monitoring end to end against a stubbed backend speaking the
 * monitoring-v1 contract (`/v1/sdk/config`, `/v1/envelope`, `/v1/capsules`).
 */
@RunWith(RobolectricTestRunner::class)
class MonitoringIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private val server = MockWebServer()
    private val envelopes: MutableList<Map<String, Any?>> = Collections.synchronizedList(ArrayList())
    private val envelopeRequests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    private val capsules: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var remoteConfig: Map<String, Any?> = mapOf("enabled" to true, "sampleRate" to 1.0, "configTtlSeconds" to 300)

    @Volatile
    private var envelopeResponse: () -> MockResponse = { MockResponse().setBody("{\"accepted\":1,\"rejected\":[]}") }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowLog.stream = System.out
        BugCapsule.resetForTest()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/v1/sdk/config" -> MockResponse().setBody(Json.stringify(remoteConfig))
                "/v1/envelope" -> {
                    val r = envelopeResponse()
                    if (r.status.contains("200")) {
                        envelopeRequests.add(request)
                        envelopes.add(J.obj(Json.parse(String(GZIPInputStream(request.body.inputStream()).readBytes())))!!)
                    }
                    r
                }
                "/v1/capsules" -> {
                    capsules.add(request.body.readByteArray())
                    MockResponse().setResponseCode(201).setBody("{\"id\":\"srv\"}")
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

    private fun start(configure: BugCapsuleConfig.Builder.() -> Unit = {}) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root)
        val b = BugCapsuleConfig.builder()
            .mode(CaptureMode.QA)
            .gitSha("abc123")
            .backend(server.url("/").toString().trimEnd('/'), "bci_test_key_000000000000000")
            .redactKeys("loyaltyId")
        b.configure()
        BugCapsule.init(app, b.build())
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
    }

    private fun runtime() = BugCapsule.session as Runtime

    private fun items(type: String): List<Map<String, Any?>> = synchronized(envelopes) {
        envelopes.flatMap { e -> J.list(e["items"])!!.map { J.obj(it)!! } }
            .filter { it["type"] == type }
            .map { J.obj(it["payload"])!! }
    }

    private fun flushAll() {
        // Queued writes run on the SDK executor; flush after them.
        assertTrue("outbox drained", BugCapsule.flush(10_000))
    }

    @Test
    fun capturedExceptionsAreSentAsGzippedEnvelopes() {
        start()
        BugCapsule.setUser(User(id = "u_123", segment = "pro"))
        BugCapsule.setTag("screen", "checkout")
        BugCapsule.log("opening checkout")
        val error = IllegalStateException("Cart total is negative (-3) for Bearer abcdef123456", IllegalArgumentException("price"))
        val id = BugCapsule.captureException(error, CaptureOptions(tags = mapOf("flow" to "checkout"), fingerprint = listOf("cart-total")))
        assertNotNull(id)
        assertEquals(id, BugCapsule.lastEventId())
        flushAll()

        val request = envelopeRequests.first()
        assertEquals("POST", request.method)
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("Bearer bci_test_key_000000000000000", request.getHeader("Authorization"))
        val envelope = envelopes.first()
        assertTrue(dev.bugcapsule.android.core.Iso8601.isValid(envelope["sentAt"] as String))
        assertEquals("bugcapsule-android", J.obj(envelope["sdk"])!!["name"])

        val event = items("event").single()
        assertEquals(id, event["eventId"])
        assertEquals("error", event["level"])
        assertEquals("android", event["runtime"])
        assertEquals("qa", event["environment"])
        assertTrue((event["release"] as String).startsWith("${app.packageName}@"))
        assertEquals(listOf("cart-total"), event["fingerprint"])
        assertEquals(mapOf("screen" to "checkout", "flow" to "checkout"), event["tags"])
        assertEquals("u_123", J.obj(event["user"])!!["id"])
        val values = J.list(J.obj(event["exception"])!!["values"])!!.map { J.obj(it)!! }
        assertEquals(listOf("IllegalArgumentException", "IllegalStateException"), values.map { it["type"] })
        assertFalse("redacted on device", (values[1]["value"] as String).contains("abcdef123456"))
        assertEquals(mapOf("type" to "manual", "handled" to true), values[1]["mechanism"])
        val contexts = J.obj(event["contexts"])!!
        for (k in listOf("device", "os", "app", "trace")) assertTrue("contexts.$k", contexts.containsKey(k))
        assertEquals("Android", J.obj(contexts["os"])!!["name"])
        val breadcrumbs = J.list(event["breadcrumbs"])!!.map { J.obj(it)!! }
        assertTrue(breadcrumbs.any { it["category"] == "log" && it["message"] == "opening checkout" })
        assertNull(event["capsuleId"])
    }

    @Test
    fun aFailureCapsuleAndItsEventPointAtEachOther() {
        start()
        BugCapsule.reportError(IllegalStateException("Item legacy-pin has no price"))
        val deadline = System.currentTimeMillis() + 10_000
        while (capsules.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        flushAll()
        val event = items("event").single()
        val capsule = CapsuleCodec.decode(capsules.single())
        assertEquals(capsule.manifest.capsuleId, event["capsuleId"])
        val link = capsule.manifest.monitoring!!
        assertEquals(event["eventId"], link["eventId"])
        assertEquals(event["release"], link["release"])
        assertEquals("qa", link["environment"])
        assertEquals(J.obj(J.obj(event["contexts"])!!["trace"])!!["traceId"], link["traceId"])
        assertTrue(CapsuleCodec.toJson(capsule).let { dev.bugcapsule.android.core.CapsuleValidator.validate(it) } == null)
    }

    @Test
    fun monitoringRunsInProductionSessionsThatDoNotRecordACapsule() {
        start { mode(CaptureMode.PRODUCTION).productionSampleRate(0.0) }
        assertFalse(BugCapsule.isRecording)
        assertTrue(BugCapsule.session!!.isObserving)
        BugCapsule.session!!.record(EventTypes.ROUTE, mapOf("location" to "/cart", "source" to "navigation"))
        BugCapsule.fail("cart_total_non_negative", "total -3")
        flushAll()
        val event = items("event").single()
        assertEquals("production", event["environment"])
        val value = J.obj(J.list(J.obj(event["exception"])!!["values"])!![0])!!
        assertEquals("cart_total_non_negative", value["type"])
        assertEquals("assertion", J.obj(value["mechanism"])!!["type"])
        assertTrue(J.list(event["breadcrumbs"])!!.any { J.obj(it)!!["type"] == "navigation" })
        assertTrue(BugCapsule.savedCaptures().isEmpty())
    }

    @Test
    fun beforeSendAndRemoteErrorSamplingDropEvents() {
        remoteConfig = remoteConfig + mapOf("errorSampleRate" to 0.0)
        start { beforeSend { event, hint -> if (hint.throwable is IllegalArgumentException) null else event.also { it.tags["hooked"] = "yes" } } }
        assertNull("sampled out by the remote rate", BugCapsule.captureException(IllegalStateException("x")))
        flushAll()
        assertTrue(items("event").isEmpty())

        BugCapsule.resetForTest()
        remoteConfig = remoteConfig - "errorSampleRate"
        start { beforeSend { event, hint -> if (hint.throwable is IllegalArgumentException) null else event.also { it.tags["hooked"] = "yes" } } }
        assertNull(BugCapsule.captureException(IllegalArgumentException("dropped")))
        assertNotNull(BugCapsule.captureMessage("hello", Level.WARNING))
        flushAll()
        val event = items("event").single()
        assertEquals("warning", event["level"])
        assertEquals("hello", J.obj(event["message"])!!["formatted"])
        assertEquals("yes", J.obj(event["tags"])!!["hooked"])
        assertTrue("stack attached", J.list(event["threads"])!!.isNotEmpty())
    }

    @Test
    fun remoteMonitoringOffSendsNothingAndPurgesTheQueue() {
        envelopeResponse = { MockResponse().setResponseCode(503).setHeader("Retry-After", "120") }
        start()
        BugCapsule.captureMessage("queued")
        assertFalse("503 keeps it queued", BugCapsule.flush(5_000))
        val mon = runtime().monitoring!!
        assertEquals(1, mon.queuedEnvelopes().size)
        remoteConfig = remoteConfig + mapOf("monitoringEnabled" to false)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        assertFalse(mon.active)
        assertTrue(mon.queuedEnvelopes().isEmpty())
        assertNull(BugCapsule.captureMessage("after"))
        assertTrue("capsules unaffected", BugCapsule.isRecording)
    }

    @Test
    fun retryAfterBacksOffAndTheEnvelopeIsSentLater() {
        var calls = 0
        envelopeResponse = {
            calls++
            if (calls == 1) MockResponse().setResponseCode(429).setHeader("Retry-After", "1") else MockResponse().setBody("{\"accepted\":1}")
        }
        start()
        BugCapsule.captureMessage("rate limited")
        assertFalse(BugCapsule.flush(5_000))
        assertEquals(1, runtime().monitoring!!.queuedEnvelopes().size)
        assertFalse("backing off: not retried yet", BugCapsule.flush(5_000))
        assertEquals(1, calls)
    }

    @Test
    fun sessionsFollowTheForegroundAndCountErrors() {
        start()
        val rt = runtime()
        rt.onLifecycle("resumed")
        BugCapsule.captureException(IllegalStateException("one"))
        BugCapsule.captureException(IllegalStateException("two"))
        rt.onLifecycle("paused")
        flushAll()
        val sessions = items("session")
        assertEquals(true, sessions.first()["init"])
        assertEquals("ok", sessions.first()["status"])
        assertTrue("first error updates the session", sessions.any { J.int(it["errors"]) == 1 })
        assertEquals(1, sessions.map { it["sid"] }.distinct().size)
        assertTrue(sessions.all { it["release"] == items("event").first()["release"] })
    }

    @Test
    fun transactionsCarrySpansMeasurementsAndTheTraceOfErrorsInside() {
        start { tracesSampleRate(1.0) }
        val tx = BugCapsule.startTransaction("/checkout", "navigation", bindToScope = true)
        val child = tx.startChild("db.sql.query", "SELECT * FROM cart WHERE token = 'secret-token-1'")
        child.setData("rows", 3)
        child.finish()
        val eventId = BugCapsule.captureException(IllegalStateException("inside"))
        tx.setMeasurement("time_to_initial_display", 420, "millisecond")
        BugCapsule.startSpan("serialize cart", "serialize") { span -> span.setData("bytes", 10) }
        tx.finish()
        flushAll()
        val payload = items("transaction").single()
        assertEquals("/checkout", payload["name"])
        assertEquals("navigation", payload["op"])
        assertEquals(tx.traceId, payload["traceId"])
        assertEquals(mapOf("value" to 420L, "unit" to "millisecond"), J.obj(payload["measurements"])!!["time_to_initial_display"])
        val spans = J.list(payload["spans"])!!.map { J.obj(it)!! }
        assertEquals(listOf("db.sql.query", "serialize"), spans.map { it["op"] })
        assertEquals(3L, J.obj(spans[0]["data"])!!["rows"])
        assertEquals(tx.spanId, spans[0]["parentSpanId"])
        val event = items("event").single { it["eventId"] == eventId }
        assertEquals(tx.traceId, J.obj(J.obj(event["contexts"])!!["trace"])!!["traceId"])
    }

    @Test
    fun unsampledTracesAreNotSentAndContinueTraceKeepsTheDecision() {
        start { tracesSampleRate(0.0) }
        BugCapsule.startTransaction("a", "task").finish()
        val ctx = BugCapsule.continueTrace("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "bugcapsule-sample_rate=0.5")!!
        val continued = BugCapsule.startTransaction("b", "task", context = ctx)
        assertTrue(continued.sampled)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", continued.traceId)
        continued.finish()
        flushAll()
        val tx = items("transaction").single()
        assertEquals("b", tx["name"])
        assertEquals("00f067aa0ba902b7", tx["parentSpanId"])
        assertNull(BugCapsule.continueTrace("nonsense"))
    }

    @Test
    fun feedbackFromTheDialogIsSentWithItsCapsule() {
        start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = BugCapsuleFeedbackDialog.show(activity, eventId = "8f0e0000-0000-4000-8000-000000000001")!!
        dialog.field("bugcapsule.feedback.message").setText("Checkout button did nothing, my password=hunter2")
        dialog.field("bugcapsule.feedback.email").setText("ana@example.com")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        val deadline = System.currentTimeMillis() + 10_000
        while (capsules.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        flushAll()
        val feedback = items("feedback").single()
        assertEquals("8f0e0000-0000-4000-8000-000000000001", feedback["eventId"])
        assertEquals("ana@example.com", feedback["email"])
        assertFalse((feedback["message"] as String).contains("hunter2"))
        val capsule = CapsuleCodec.decode(capsules.single())
        assertEquals(capsule.manifest.capsuleId, feedback["capsuleId"])
        assertEquals(feedback["feedbackId"], capsule.manifest.monitoring!!["feedbackId"])
    }

    private fun android.app.AlertDialog.field(tag: String): android.widget.EditText = window!!.decorView.findViewWithTag(tag)

    @Test
    fun monitoringOffLeavesCapsulesAlone() {
        start { monitoring(false) }
        assertNull(runtime().monitoring)
        assertNull(BugCapsule.captureException(IllegalStateException("x")))
        assertFalse(BugCapsule.startTransaction("x", "task").sampled)
        assertTrue(BugCapsule.isRecording)
        assertTrue(BugCapsule.flush(1_000))
        assertTrue(envelopes.isEmpty())
    }

    @Test
    fun aNativeCrashRecordIsSentOnTheNextLaunchAndCrashesThePreviousSession() {
        // What the previous process left: its context, a crash record and its running session.
        val root = java.io.File(tmp.root, "native").apply { mkdirs() }
        java.io.File(root, "context.json").writeText("{\"release\":\"com.shop@0.9.0+8\",\"environment\":\"qa\"}")
        java.io.File(root, "123-1761000000000.crash").writeText(
            "bugcapsule-native-crash 1\nsignal 6\ncode -6\naddress 0x0\ntid 77\nthread main\ntimestamp 1761000000500\nabi arm64-v8a\n" +
                "scope {\"tags\":{\"screen\":\"checkout\"},\"user\":{\"id\":\"u_crashed\"},\"extra\":{},\"contexts\":{\"app\":{\"appVersion\":\"0.9.0\"}}," +
                "\"breadcrumbs\":[{\"timestamp\":\"2025-10-20T22:39:59.000Z\",\"type\":\"navigation\",\"category\":\"navigation\",\"message\":null,\"level\":\"info\",\"data\":{\"to\":\"/pay\"}}]}\n" +
                "frame 0x7a00001000 0x7a00000000 0x7a00000f00 /data/app/x/lib/arm64/libshop.so checkout_native\nend\n",
        )
        java.io.File(tmp.root, "monitoring").mkdirs()
        java.io.File(tmp.root, "monitoring/session.json").writeText(
            "{\"sid\":\"b3a1c0de-0000-4000-8000-000000000000\",\"did\":\"i\",\"started\":1761000000000,\"seq\":0," +
                "\"status\":\"ok\",\"errors\":0,\"timestamp\":1761000000100,\"backgroundSince\":null," +
                "\"release\":\"com.shop@0.9.0+8\",\"environment\":\"qa\"}",
        )
        start()
        flushAll()
        val event = items("event").single()
        assertEquals("fatal", event["level"])
        assertEquals("com.shop@0.9.0+8", event["release"])
        assertEquals("2025-10-20T22:40:00.500Z", event["timestamp"])
        val value = J.obj(J.list(J.obj(event["exception"])!!["values"])!![0])!!
        assertEquals("SIGABRT", value["type"])
        assertEquals("signal", J.obj(value["mechanism"])!!["type"])
        // The crashed process's scope, not this launch's.
        assertEquals("u_crashed", J.obj(event["user"])!!["id"])
        assertEquals("checkout", J.obj(event["tags"])!!["screen"])
        assertEquals("/pay", J.obj(J.obj(J.list(event["breadcrumbs"])!!.single())!!["data"])!!["to"])
        val contexts = J.obj(event["contexts"])!!
        assertEquals("0.9.0", J.obj(contexts["app"])!!["appVersion"])
        assertNotNull("device contexts still come from this launch", contexts["os"])
        val session = items("session").single()
        assertEquals("b3a1c0de-0000-4000-8000-000000000000", session["sid"])
        assertEquals("crashed", session["status"])
        assertEquals("com.shop@0.9.0+8", session["release"])
        assertFalse("records are consumed", java.io.File(root, "123-1761000000000.crash").exists())
    }

    @Test
    fun theScopeIsMirroredAsOneLineOfRedactedJsonWithinTheNativeBuffer() {
        start()
        val mon = runtime().monitoring!!
        val mirrored = Collections.synchronizedList(ArrayList<ByteArray>())
        mon.mirrorScopeTo { mirrored.add(it) }
        BugCapsule.setUser(User(id = "u_7"))
        BugCapsule.setTag("screen", "checkout")
        BugCapsule.addBreadcrumb(Breadcrumb("tapped pay", data = mapOf("password" to "hunter2")))
        mon.mirrorScopeNow()
        val json = J.obj(Json.parse(String(mirrored.last(), Charsets.UTF_8)))!!
        assertFalse(String(mirrored.last()).contains('\n'))
        assertEquals("u_7", J.obj(json["user"])!!["id"])
        assertEquals("checkout", J.obj(json["tags"])!!["screen"])
        val crumb = J.obj(J.list(json["breadcrumbs"])!!.last())!!
        assertEquals("tapped pay", crumb["message"])
        assertFalse("redacted before it reaches disk", String(mirrored.last()).contains("hunter2"))
        assertNotNull(J.obj(J.obj(json["contexts"])!!["app"]))

        // Over 64 KiB: the oldest breadcrumbs go, the newest stay.
        repeat(100) { i -> BugCapsule.addBreadcrumb(Breadcrumb("crumb $i", data = mapOf("blob" to "x".repeat(2_000)))) }
        mon.mirrorScopeNow()
        val big = mirrored.last()
        assertTrue(big.size <= Monitoring.CRASH_SCOPE_MAX_BYTES)
        val crumbs = J.list(J.obj(Json.parse(String(big, Charsets.UTF_8)))!!["breadcrumbs"])!!
        assertEquals("crumb 99", J.obj(crumbs.last())!!["message"])
        assertTrue(crumbs.size < 100)
    }

    @Test
    fun activitiesAreUiLoadTransactionsWithInitialDisplay() {
        val sent = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        start {
            tracesSampleRate(1.0)
            beforeSendTransaction { tx -> tx.also { sent.add(it.payload) } }
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Activity", BugCapsule.scope()!!.snapshot().tags["screen"])
        val active = BugCapsule.activeSpan() as Transaction
        assertEquals("ui.load", active.op)
        controller.pause()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val tx = sent.single()
        assertEquals("Activity", tx["name"])
        assertEquals("ui.load", tx["op"])
        val ops = J.list(tx["spans"])!!.map { J.obj(it)!!["op"] }
        assertTrue(ops.toString(), "ui.load.initial_display" in ops)
        assertNull("unbound from the scope once finished", BugCapsule.scope()!!.activeSpan)
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [28])
    fun activityFramesReachTheTransactionEnvelopeAfterWindowCreation() {
        start { tracesSampleRate(1.0) }
        val controller = Robolectric.buildActivity(Activity::class.java)
        assertNull("regression: the window has no decor before activity creation", controller.get().window.peekDecorView())
        controller.setup().visible()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val window = controller.get().window
        val now = System.nanoTime()
        repeat(3) { i ->
            val metrics = org.robolectric.shadows.FrameMetricsBuilder()
                .setMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP, now + i * 30_000_000L)
                .setMetric(android.view.FrameMetrics.VSYNC_TIMESTAMP, now + i * 30_000_000L)
                .setMetric(android.view.FrameMetrics.DRAW_DURATION, 20_000_000L)
                .build()
            shadowOf(window).reportOnFrameMetricsAvailable(metrics)
        }
        controller.pause()
        flushAll()
        val tx = items("transaction").single { it["op"] == "ui.load" }
        assertNotNull("frame measurements must reach the uploaded transaction", tx["measurements"])
        val measurements = J.obj(tx["measurements"])!!
        assertEquals(3L, J.long(J.obj(measurements["frames_total"])!!["value"]))
        assertTrue(measurements.keys.containsAll(listOf("frames_slow", "frames_frozen", "frames_delay")))
        controller.stop().destroy()
    }

    @Test
    fun anUncaughtCrashIsPersistedSynchronouslyWithItsCapsuleAndCrashesTheSession() {
        start()
        val rt = runtime()
        rt.onLifecycle("resumed")
        BugCapsule.flush(5_000)
        val before = rt.monitoring!!.queuedEnvelopes().size
        rt.onUncaught(RuntimeException("Unable to start activity", IllegalStateException("Recommendations arrived before the profile")))
        assertEquals("written before the handler returns", before + 1, rt.monitoring!!.queuedEnvelopes().size)
        assertEquals(1, BugCapsule.savedCaptures().size)
        flushAll()
        val event = items("event").single()
        assertEquals("fatal", event["level"])
        val values = J.list(J.obj(event["exception"])!!["values"])!!.map { J.obj(it)!! }
        assertEquals("framework wrapper unwrapped", "IllegalStateException", values.last()["type"])
        assertEquals(mapOf("type" to "uncaught", "handled" to false), values.last()["mechanism"])
        val capsule = CapsuleCodec.decode(BugCapsule.savedCaptures().single().readBytes())
        assertEquals(capsule.manifest.capsuleId, event["capsuleId"])
        assertEquals(event["eventId"], capsule.manifest.monitoring!!["eventId"])
        assertTrue(items("session").any { it["status"] == "crashed" && J.int(it["errors"]) == 1 })
    }

    @Test
    fun withScopeIsTemporaryAndThreadLocal() {
        start()
        BugCapsule.setTag("global", "1")
        BugCapsule.withScope { scope ->
            scope.setTag("temp", "1")
            scope.setLevel(Level.WARNING)
            BugCapsule.captureMessage("inside")
        }
        BugCapsule.captureMessage("outside")
        flushAll()
        val inside = items("event").single { J.obj(it["message"])!!["formatted"] == "inside" }
        val outside = items("event").single { J.obj(it["message"])!!["formatted"] == "outside" }
        assertEquals(mapOf("global" to "1", "temp" to "1"), inside["tags"])
        assertEquals("warning", inside["level"])
        assertEquals(mapOf("global" to "1"), outside["tags"])
        assertEquals("info", outside["level"])
    }
}
