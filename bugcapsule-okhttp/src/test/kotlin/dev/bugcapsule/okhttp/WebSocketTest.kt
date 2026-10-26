package dev.bugcapsule.okhttp

import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleValidator
import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.InteractionPayload
import dev.bugcapsule.android.core.PayloadMode
import dev.bugcapsule.android.core.Redactor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    /** What the app saw on one socket, plus a log shared by every socket. */
    private class AppListener(
        val name: String,
        val global: MutableList<String>,
        val sendOnOpen: String? = "sub",
        val closeAfter: Int = 2,
    ) : WebSocketListener() {
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var failure: Throwable? = null
        var sendResult: Boolean? = null
        private var messages = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            seen.add("open ${response.code}")
            if (sendOnOpen != null) sendResult = webSocket.send(sendOnOpen)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            seen.add("text $text")
            global.add("$name:$text")
            if (++messages == closeAfter) webSocket.close(1000, "bye")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            seen.add("bytes ${bytes.hex()}")
            global.add("$name:${bytes.hex()}")
            if (++messages == closeAfter) webSocket.close(1000, "bye")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            seen.add("closing $code")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            seen.add("closed $code")
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            seen.add("failure ${t.javaClass.simpleName}")
            failure = t
            failed.countDown()
            closed.countDown()
        }
    }

    /** Server side: greets with its own number, echoes text, completes closes. */
    private fun serverSocket(n: Int, binary: Boolean = false) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (binary) webSocket.send(byteArrayOf(1, 2, 3, n.toByte()).toByteString()) else webSocket.send("hello-$n")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send("echo-$text-$n")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    })

    private fun request(path: String = "/stream?ts=123&token=abc") = Request.Builder().url(server.url(path)).build()

    private fun replayOf(capsule: Capsule, captureBinary: Boolean = false, manual: Boolean = false): Triple<FakeSession, FakeReplay, BugCapsuleWebSockets> {
        val session = FakeSession()
        val replay = if (manual) {
            FakeReplay(capsule, clock = { 0L }, autoPump = false, deliveryExecutor = { it.run() })
        } else {
            FakeReplay(capsule)
        }
        session.replay = replay
        val ws = BugCapsuleWebSockets(captureBinary = captureBinary).also { it.setUp(session) }
        return Triple(session, replay, ws)
    }

    @Test
    fun raceWithEqualTimestampsAndConcurrentIdenticalConnectionsRoundTrips() {
        // ---- capture: every event at the same session time, two identical connections at once
        server.enqueue(serverSocket(1))
        server.enqueue(serverSocket(2))
        server.start()
        val session = FakeSession(clock = { 1000L })
        val ws = BugCapsuleWebSockets().also { it.setUp(session) }
        val sockets = ws.factory(client)
        val capturedLog = Collections.synchronizedList(ArrayList<String>())
        val a = AppListener("a", capturedLog)
        val b = AppListener("b", capturedLog)
        sockets.newWebSocket(request(), a)
        sockets.newWebSocket(request(), b)
        assertTrue(a.closed.await(10, TimeUnit.SECONDS) && b.closed.await(10, TimeUnit.SECONDS))
        server.shutdown()

        val capsule = session.capsule()
        assertEquals(2, capsule.specVersion)
        assertNull(CapsuleValidator.validate(CapsuleCodec.toJson(capsule)))
        val connects = capsule.eventsOfType("ws.connect")
        val key = "ws://${server.hostName}:${server.port}/stream?token=%5BREDACTED%5D"
        assertEquals(key, ws.key(request().url, session.redactor))
        assertEquals(listOf(key, key), connects.map { it.data["key"] })
        assertEquals(listOf(0L, 1L), connects.map { it.data["occurrence"] })
        assertEquals(listOf(0L, 0L), connects.map { it.data["attempt"] })
        assertTrue(capsule.events.all { it.t == 1000L })
        val dep = capsule.dependency("ws")!!
        assertEquals(BugCapsuleWebSockets.ADAPTER_ID, dep.adapter)
        assertEquals(mapOf("com.squareup.okhttp3:okhttp" to "4.12.0"), dep.tested)
        assertEquals(DependencyCoverage.COMPLETE, dep.coverage)
        val sends = capsule.eventsOfType("ws.send")
        assertEquals(listOf(true, true), sends.map { it.data["result"] })
        val clientCloses = capsule.eventsOfType("ws.close").filter { it.data["initiator"] == "client" }
        assertEquals(listOf("bye", "bye"), clientCloses.map { it.data["reason"] })

        // ---- replay: no server; the same app code. A frozen clock and explicit pumps make every
        // delivery due at once, so events of both sockets interleave by recorded seq (§5).
        val (_, replay, replayWs) = replayOf(capsule, manual = true)
        val replayLog = Collections.synchronizedList(ArrayList<String>())
        val ra = AppListener("a", replayLog)
        val rb = AppListener("b", replayLog)
        val replaySockets = replayWs.factory(client)
        replaySockets.newWebSocket(request(), ra)
        replaySockets.newWebSocket(request(), rb)
        var pumps = 0
        while ((ra.closed.count > 0 || rb.closed.count > 0) && pumps++ < 50) replay.interactions.pump()
        replay.interactions.finish(null)

        assertEquals(a.seen, ra.seen)
        assertEquals(b.seen, rb.seen)
        // Across sockets, messages arrive in recorded order (equal times: by seq).
        val connOf = mapOf(connects[0].data["conn"] to "a", connects[1].data["conn"] to "b")
        val recordedOrder = capsule.eventsOfType("ws.message").map {
            "${connOf[it.data["conn"]]}:${InteractionPayload.fromJson(it.data["payload"] as Map<String, Any?>).value}"
        }
        assertEquals(recordedOrder, replayLog)
        assertEquals(emptyList<Any>(), replay.divergences)
    }

    @Test
    fun anUnrecordedConnectFailsWithoutReachingTheNetwork() {
        server.start()
        val wsDep = FakeSession().let { s ->
            BugCapsuleWebSockets().setUp(s)
            s.recorder.recorders().single().record("ws.connect", mapOf("conn" to "ws1", "url" to "ws://other/", "key" to "ws://other/", "occurrence" to 0, "attempt" to 0))
            s.capsule()
        }
        // A diagnostic-only recording of the same connection is never served either.
        val diagnostic = wsDep.copy(
            manifest = wsDep.manifest.copy(
                dependencies = wsDep.manifest.dependencies.map { it.copy(required = false, coverage = DependencyCoverage.DIAGNOSTIC) },
            ),
        )
        val diagnosticKey = BugCapsuleWebSockets().key(request().url, Redactor())
        val diagnosticCapsule = diagnostic.copy(events = diagnostic.events.map { it.copy(data = it.data + ("key" to diagnosticKey)) })
        // Recorded, but no ws dependency / no capsule recording at all / diagnostic only: always unrecorded.
        for (capsule in listOf(wsDep, FakeReplay.of(emptyList()).capsule, diagnosticCapsule)) {
            val (_, replay, ws) = replayOf(capsule)
            val listener = AppListener("a", ArrayList())
            val socket = ws.newWebSocket(client, request(), listener)
            assertTrue(listener.failed.await(5, TimeUnit.SECONDS))
            assertTrue(listener.failure is ConnectException)
            assertFalse(socket.send("x"))
            assertEquals("ws.unrecorded", replay.divergences.first().type)
        }
        assertEquals(0, server.requestCount)
    }

    private fun connectEvents(vararg extra: Pair<String, Map<String, Any?>>): Capsule {
        val s = FakeSession(clock = { 0L })
        BugCapsuleWebSockets().setUp(s)
        val rec = s.recorder.recorders().single()
        val key = BugCapsuleWebSockets().key(request().url, s.redactor)
        rec.record("ws.connect", mapOf("conn" to "ws1", "url" to key, "key" to key, "occurrence" to 0, "attempt" to 0))
        rec.record("ws.open", mapOf("conn" to "ws1"))
        for ((type, data) in extra) rec.record(type, mapOf("conn" to "ws1") + data)
        return s.capsule()
    }

    private fun text(value: String) = InteractionPayload(PayloadMode.FULL, true, value, value.length, "text").toJson()

    @Test
    fun aSendThatDiffersFromTheRecordingIsAMismatch() {
        server.start()
        val capsule = connectEvents(
            "ws.send" to mapOf("index" to 0, "payload" to text("{\"op\":\"subscribe\",\"symbol\":\"ACME\"}"), "result" to true),
            "ws.message" to mapOf("index" to 0, "payload" to text("{\"price\":12.5}")),
        )
        val (_, replay, ws) = replayOf(capsule)
        val listener = AppListener("a", ArrayList(), sendOnOpen = "{\"op\":\"subscribe\",\"symbol\":\"OTHER\"}", closeAfter = 99)
        ws.newWebSocket(client, request(), listener)
        val deadline = System.currentTimeMillis() + 5000
        while (listener.seen.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("open 101", "text {\"price\":12.5}"), listener.seen)
        assertEquals(true, listener.sendResult)
        assertEquals(listOf("ws.mismatch"), replay.divergences.map { it.type })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun binaryFramesAreMetadataOnlyByDefault() {
        server.enqueue(serverSocket(7, binary = true))
        server.start()
        val session = FakeSession()
        val ws = BugCapsuleWebSockets().also { it.setUp(session) }
        val listener = AppListener("a", ArrayList(), sendOnOpen = null, closeAfter = 1)
        ws.newWebSocket(client, request(), listener)
        assertTrue(listener.closed.await(10, TimeUnit.SECONDS))
        val capsule = session.capsule()
        val payload = InteractionPayload.fromJson(capsule.eventsOfType("ws.message").single().data["payload"] as Map<String, Any?>)
        assertEquals(InteractionPayload(PayloadMode.METADATA, false, bytes = 4, kind = "binary"), payload)
        val dep = capsule.dependency("ws")!!
        assertEquals(DependencyCoverage.PARTIAL, dep.coverage)
        assertEquals(listOf(DependencyGaps.PAYLOAD_BINARY_METADATA), dep.gaps.map { it.code })

        // Replay cannot serve it: ws.noPayload, and the app never gets made-up bytes.
        val (_, replay, replayWs) = replayOf(capsule)
        val replayed = AppListener("a", ArrayList(), sendOnOpen = null, closeAfter = 1)
        replayWs.newWebSocket(client, request(), replayed)
        val deadline = System.currentTimeMillis() + 5000
        while (replay.divergences.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("ws.noPayload", replay.divergences.first().type)
        assertTrue(replayed.seen.none { it.startsWith("bytes") })
    }

    @Test
    fun binaryFramesReplayWhenCaptured() {
        server.enqueue(serverSocket(7, binary = true))
        server.start()
        val session = FakeSession()
        val ws = BugCapsuleWebSockets(captureBinary = true).also { it.setUp(session) }
        val listener = AppListener("a", ArrayList(), sendOnOpen = null, closeAfter = 1)
        ws.newWebSocket(client, request(), listener)
        assertTrue(listener.closed.await(10, TimeUnit.SECONDS))
        server.shutdown()
        val capsule = session.capsule()
        assertEquals(DependencyCoverage.COMPLETE, capsule.dependency("ws")!!.coverage)
        val (_, replay, replayWs) = replayOf(capsule, captureBinary = true)
        val replayed = AppListener("a", ArrayList(), sendOnOpen = null, closeAfter = 1)
        replayWs.newWebSocket(client, request(), replayed)
        assertTrue(replayed.closed.await(5, TimeUnit.SECONDS))
        assertEquals(listener.seen, replayed.seen)
        assertTrue(replayed.seen.contains("bytes 01020307"))
        replay.interactions.finish(null)
        assertEquals(emptyList<Any>(), replay.divergences)
    }

    @Test
    fun remoteKillSwitchStopsWebSocketCaptureButNotHttp() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"))
        server.enqueue(serverSocket(1))
        server.start()
        val session = FakeSession()
        session.disabledAdapters = listOf(BugCapsuleWebSockets.ADAPTER_ID)
        val okHttp = BugCapsuleOkHttp().also { it.setUp(session) }
        val ws = BugCapsuleWebSockets().also { it.setUp(session) }
        val http = client.newBuilder().addInterceptor(okHttp.interceptor).build()
        http.newCall(Request.Builder().url(server.url("/api")).build()).execute().use { it.body!!.string() }
        val listener = AppListener("a", ArrayList())
        ws.newWebSocket(http, request(), listener)
        assertTrue(listener.closed.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("open 101", "text hello-1", "text echo-sub-1", "closing 1000", "closed 1000"), listener.seen)
        assertEquals(1, session.awaitHttp(1).size)
        assertTrue(session.events.none { it.first.startsWith("ws.") })
        assertTrue(session.capsule().manifest.dependencies.isEmpty())
    }

    @Test
    fun reconnectAttemptsCountFailedConnects() {
        server.start()
        val port = server.port
        server.shutdown() // connects fail
        val session = FakeSession()
        val ws = BugCapsuleWebSockets().also { it.setUp(session) }
        val req = Request.Builder().url("http://127.0.0.1:$port/stream").build()
        repeat(2) {
            val l = AppListener("a", ArrayList())
            ws.newWebSocket(client, req, l)
            assertTrue(l.failed.await(10, TimeUnit.SECONDS))
        }
        val capsule = session.capsule()
        assertEquals(listOf(0L, 1L), capsule.eventsOfType("ws.connect").map { it.data["attempt"] })
        assertEquals(listOf("connect", "connect"), capsule.eventsOfType("ws.error").map { it.data["phase"] })

        val (_, replay, replayWs) = replayOf(capsule)
        repeat(2) {
            val l = AppListener("a", ArrayList())
            replayWs.newWebSocket(client, req, l)
            assertTrue(l.failed.await(5, TimeUnit.SECONDS))
            assertTrue(l.failure is ConnectException)
        }
        replay.interactions.finish(null)
        assertEquals(emptyList<Any>(), replay.divergences)
    }
}
