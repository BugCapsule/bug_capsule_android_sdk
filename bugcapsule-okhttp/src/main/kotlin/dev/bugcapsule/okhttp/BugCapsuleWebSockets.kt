package dev.bugcapsule.okhttp

import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.core.DependencyDescriptor
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.DependencyKinds
import dev.bugcapsule.android.core.DependencyRecorder
import dev.bugcapsule.android.core.DependencyStats
import dev.bugcapsule.android.core.InteractionAdapterInfo
import dev.bugcapsule.android.core.InteractionHandler
import dev.bugcapsule.android.core.InteractionLimits
import dev.bugcapsule.android.core.InteractionPayload
import dev.bugcapsule.android.core.InteractionTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.PayloadBudget
import dev.bugcapsule.android.core.PayloadEncoder
import dev.bugcapsule.android.core.RecordedInteraction
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.RequestKeyBuilder
import dev.bugcapsule.android.replay.InteractionReplay
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp WebSocket adapter (`bugcapsule-okhttp.websocket`, schema 1;
 * capsule-v2 §4.1): records connections opened through [factory] /
 * [newWebSocket] and replays them offline.
 *
 * ```kotlin
 * val webSockets = BugCapsuleWebSockets()
 * BugCapsule.init(app, config, listOf(okHttp, webSockets))
 * val sockets: WebSocket.Factory = webSockets.factory(okHttpClient)
 * val ws = sockets.newWebSocket(Request.Builder().url("wss://quotes.example.com/v1/stream").build(), listener)
 * ```
 *
 * Only sockets created through this factory are captured; `client.newWebSocket`
 * called directly is not (and in replay would reach the network).
 *
 * **Capture** records `ws.connect` (connection id, redacted URL, match key =
 * redacted URL without [ignoreQuery] parameters, occurrence, reconnect
 * attempt, requested protocols), `ws.open`, `ws.send` (text or bytes, with
 * the `Boolean` OkHttp returned), `ws.message`, `ws.error` (`phase`
 * `connect`/`stream`, HTTP status of a failed handshake), the app's
 * `close`/`cancel` (`ws.close`, `initiator: client`) and the listener's
 * `onClosing` (`initiator: server`, `callback: closing`) and `onClosed`
 * (`initiator: unknown`, `callback: closed`). Text frames go through the
 * session redactor; binary frames are metadata only unless [captureBinary].
 * Payload limits and the active-connection limit (32 per session) are the
 * session's; connections beyond it run uncaptured (`connections.limit` gap).
 * Remote config `disabledAdapters: ["bugcapsule-okhttp.websocket"]` turns
 * capture off without affecting HTTP.
 *
 * **Replay** never reaches the network: [newWebSocket] returns a fake
 * [WebSocket] matched to a recorded connection by key and occurrence. Sends
 * and closes are compared with the recording in order (`ws.mismatch`) and
 * return the recorded result; recorded callbacks are delivered at their
 * recorded latency. A connect without a recording fails with
 * [ConnectException] through `onFailure` (`ws.unrecorded`).
 *
 * **Listener threads in replay**: callbacks run on one background thread per
 * adapter (`BugCapsule-WebSocket`), never the main thread, one at a time in
 * recorded order (due time, then recorded sequence). Like OkHttp's reader
 * thread a listener sees its events serially; unlike OkHttp, events of
 * different connections are serialized too, so races between sockets replay
 * in their recorded order.
 */
class BugCapsuleWebSockets @JvmOverloads constructor(
    /** Query parameters left out of the match key (cache busters, timestamps). */
    val ignoreQuery: Set<String> = RequestKeyBuilder.DEFAULT_IGNORE_QUERY,
    /** Record binary frames (default: size only, which replay cannot serve: `ws.noPayload`). */
    val captureBinary: Boolean = false,
    /** Text frames the redactor changed stay replayable (the app does not branch on the redacted fields). */
    val redactedReplayable: Boolean = false,
    /** Dependency id in capsules (`data.dep`). */
    val dependencyId: String = "ws",
) : BugCapsuleIntegration {
    override val name: String = "okhttp.websocket"

    @Volatile
    private var session: BugCapsuleSession? = null

    @Volatile
    private var recorder: DependencyRecorder? = null

    /** Consecutive failed connects per key (reset when one opens): `ws.connect.attempt`. */
    private val attempts = ConcurrentHashMap<String, Int>()
    private val replayExecutor = lazy<ExecutorService> {
        Executors.newSingleThreadExecutor { r -> Thread(r, "BugCapsule-WebSocket").apply { isDaemon = true } }
    }

    val descriptor: DependencyDescriptor
        get() = DependencyDescriptor(
            id = dependencyId,
            kind = DependencyKinds.WEBSOCKET,
            adapter = ADAPTER_ID,
            adapterVersion = BuildConfig.SDK_VERSION,
            schema = SCHEMA,
            operations = listOf("connect", "send", "close"),
            tested = mapOf(OKHTTP_PACKAGE to TESTED_OKHTTP),
            captureBinary = captureBinary,
            redactedReplayable = redactedReplayable,
        )

    override fun setUp(session: BugCapsuleSession) {
        this.session = session
        if (session.replay == null) recorder = session.interactions?.register(descriptor)
    }

    override fun capabilities() = mapOf("network.websocket.okhttp" to "supported")

    override fun interactionAdapters() = listOf(InteractionAdapterInfo(ADAPTER_ID, SCHEMA..SCHEMA))

    /** A [WebSocket.Factory] whose sockets are captured and replayed (use it instead of [client] directly). */
    fun factory(client: OkHttpClient): WebSocket.Factory = WebSocket.Factory { request, listener -> newWebSocket(client, request, listener) }

    /** [OkHttpClient.newWebSocket] with capture and replay. */
    fun newWebSocket(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket {
        val s = session ?: return client.newWebSocket(request, listener)
        val replay = s.replay
        if (replay != null) return replayConnect(request, listener, replay.interactions, s.redactor, replay::diverge)
        val rec = recorder
        if (rec == null || !rec.isCapturing) return client.newWebSocket(request, listener)
        return try {
            capture(client, request, listener, rec)
        } catch (e: CaptureFailed) {
            rec.gap(DependencyGaps.CAPTURE_FAILED, "connect: ${e.cause}")
            client.newWebSocket(request, listener)
        }
    }

    /** Match key: the redacted `ws(s)://` URL without [ignoreQuery] parameters (capsule-v2 §4.1). */
    internal fun key(url: HttpUrl, redactor: Redactor): String {
        val b = url.newBuilder()
        for (name in url.queryParameterNames) if (name in ignoreQuery) b.removeAllQueryParameters(name)
        return redactor.redactUrl(wsUrl(b.build()))
    }

    // ------------------------------------------------------------ capture

    private class CaptureFailed(cause: Throwable) : RuntimeException(cause)

    private fun capture(client: OkHttpClient, request: Request, listener: WebSocketListener, rec: DependencyRecorder): WebSocket {
        val conn: String
        val data: Map<String, Any?>
        val key: String
        try {
            conn = rec.nextId("ws")
            key = key(request.url, rec.redactor)
            data = linkedMapOf<String, Any?>(
                "conn" to conn,
                "url" to rec.redactor.redactUrl(wsUrl(request.url)),
                "key" to key,
                "occurrence" to rec.occurrence(key),
                "attempt" to (attempts[key] ?: 0),
            ).also { d ->
                val protocols = request.headers("Sec-WebSocket-Protocol").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                if (protocols.isNotEmpty()) d["protocols"] = protocols
            }
        } catch (e: Exception) {
            throw CaptureFailed(e)
        }
        if (!rec.open("ws", conn)) return client.newWebSocket(request, listener)
        val socket = CapturedSocket(conn, key, rec, listener)
        // Held until the socket exists, so the listener's events are recorded after the connect.
        synchronized(socket.lock) {
            rec.record(InteractionTypes.WS_CONNECT, data)
            socket.delegate = client.newWebSocket(request, socket.listener)
        }
        return socket
    }

    /**
     * The app's socket while capturing. Recording and the OkHttp call happen
     * under [lock], and the listener records under it too, so a response to
     * a send is never recorded before the send.
     */
    private inner class CapturedSocket(
        val conn: String,
        val key: String,
        val rec: DependencyRecorder,
        val app: WebSocketListener,
    ) : WebSocket {
        val lock = Any()
        lateinit var delegate: WebSocket
        private val sends = AtomicInteger()
        private val messages = AtomicInteger()
        private val opened = AtomicBoolean(false)

        private fun record(type: String, data: Map<String, Any?>) {
            rec.record(type, linkedMapOf<String, Any?>("conn" to conn).apply { putAll(data) })
        }

        private fun payload(value: Any?, kind: String): Map<String, Any?> = try {
            rec.encoder.encode(value, kind).toJson()
        } catch (e: Exception) {
            rec.gap(DependencyGaps.CAPTURE_FAILED, "payload: ${e.javaClass.simpleName}")
            InteractionPayload(dev.bugcapsule.android.core.PayloadMode.OMITTED, false, kind = kind).toJson()
        }

        override fun request(): Request = delegate.request()
        override fun queueSize(): Long = delegate.queueSize()

        override fun send(text: String): Boolean = synchronized(lock) {
            val result = delegate.send(text)
            record(InteractionTypes.WS_SEND, linkedMapOf("index" to sends.getAndIncrement(), "payload" to payload(text, "text"), "result" to result))
            result
        }

        override fun send(bytes: ByteString): Boolean = synchronized(lock) {
            val result = delegate.send(bytes)
            record(
                InteractionTypes.WS_SEND,
                linkedMapOf("index" to sends.getAndIncrement(), "payload" to payload(bytes.toByteArray(), "binary"), "result" to result),
            )
            result
        }

        override fun close(code: Int, reason: String?): Boolean = synchronized(lock) {
            val result = delegate.close(code, reason)
            record(
                InteractionTypes.WS_CLOSE,
                linkedMapOf<String, Any?>("initiator" to "client", "code" to code).also {
                    if (reason != null) it["reason"] = rec.redactor.scrubText(reason)
                    it["result"] = result
                },
            )
            result
        }

        override fun cancel() {
            synchronized(lock) {
                record(InteractionTypes.WS_CLOSE, linkedMapOf("initiator" to "client", "cancel" to true))
                delegate.cancel()
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    opened.set(true)
                    attempts.remove(key)
                    record(
                        InteractionTypes.WS_OPEN,
                        linkedMapOf<String, Any?>().also { d ->
                            response.header("Sec-WebSocket-Protocol")?.let { d["protocol"] = it }
                            response.header("Sec-WebSocket-Extensions")?.let { d["extensions"] = it }
                        },
                    )
                }
                app.onOpen(this@CapturedSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    record(InteractionTypes.WS_MESSAGE, linkedMapOf("index" to messages.getAndIncrement(), "payload" to payload(text, "text")))
                }
                app.onMessage(this@CapturedSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(lock) {
                    record(
                        InteractionTypes.WS_MESSAGE,
                        linkedMapOf("index" to messages.getAndIncrement(), "payload" to payload(bytes.toByteArray(), "binary")),
                    )
                }
                app.onMessage(this@CapturedSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) { record(InteractionTypes.WS_CLOSE, closeData("server", "closing", code, reason)) }
                app.onClosing(this@CapturedSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) { record(InteractionTypes.WS_CLOSE, closeData("unknown", "closed", code, reason)) }
                rec.closed(conn)
                app.onClosed(this@CapturedSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    val wasOpen = opened.get()
                    if (!wasOpen) synchronized(attempts) { attempts[key] = (attempts[key] ?: 0) + 1 }
                    record(
                        InteractionTypes.WS_ERROR,
                        linkedMapOf<String, Any?>(
                            "phase" to if (wasOpen) "stream" else "connect",
                            "error" to linkedMapOf(
                                "type" to t.javaClass.simpleName,
                                "message" to rec.redactor.scrubText(t.message ?: t.javaClass.simpleName),
                            ),
                        ).also { d -> response?.let { d["status"] = it.code } },
                    )
                }
                rec.closed(conn)
                app.onFailure(this@CapturedSocket, t, response)
            }
        }

        private fun closeData(initiator: String, callback: String, code: Int, reason: String) =
            linkedMapOf<String, Any?>("initiator" to initiator, "callback" to callback, "code" to code).also {
                if (reason.isNotEmpty()) it["reason"] = rec.redactor.scrubText(reason)
            }
    }

    // ------------------------------------------------------------- replay

    private fun replayConnect(
        request: Request,
        listener: WebSocketListener,
        replay: InteractionReplay?,
        redactor: Redactor,
        diverge: (String, String) -> Unit,
    ): WebSocket {
        val key = key(request.url, redactor)
        val dep = replay?.dependencyFor(ADAPTER_ID)
        val recorded = if (replay != null && dep != null) replay.take(dep.id, InteractionTypes.WS_CONNECT, key) else null
        val executor: Executor = replay?.deliveryExecutor ?: replayExecutor.value
        val encoder = PayloadEncoder(
            redactor = { redactor },
            budget = PayloadBudget(InteractionLimits(maxWindowPayloadBytes = Int.MAX_VALUE)),
            stats = DependencyStats(),
            now = { 0L },
            captureBinary = captureBinary,
            redactedReplayable = redactedReplayable,
        )
        val socket = ReplayedSocket(request, listener, replay, executor, encoder, redactor, diverge)
        if (replay == null || dep == null || recorded == null) {
            val path = key.substringBefore('?')
            val mismatch = replay != null && dep != null && replay.hasUnconsumed(dep.id, InteractionTypes.WS_CONNECT, path)
            diverge(if (mismatch) "ws.mismatch" else "ws.unrecorded", if (mismatch) "connect $key: URL differs from the recording" else "connect $key")
            socket.failUnrecorded(key)
        } else {
            socket.bind(recorded.data["conn"].toString())
        }
        return socket
    }

    /** The app's socket in replay: never connected; answers from the recording. */
    private class ReplayedSocket(
        private val request: Request,
        private val app: WebSocketListener,
        private val replay: InteractionReplay?,
        private val executor: Executor,
        private val encoder: PayloadEncoder,
        private val redactor: Redactor,
        private val diverge: (String, String) -> Unit,
    ) : WebSocket {
        @Volatile
        private var scope: String? = null
        private val sends = AtomicInteger()

        fun bind(conn: String) {
            val s = "ws:$conn"
            scope = s
            replay!!.bind(s, InteractionHandler { ev -> onRecorded(s, ev) })
        }

        fun failUnrecorded(key: String) {
            executor.execute {
                app.onFailure(this, ConnectException("BugCapsule replay: no recorded WebSocket connection for $key"), null)
            }
        }

        /** Runs inside the script's delivery (lock held): hand over to the delivery thread. */
        private fun onRecorded(scope: String, ev: RecordedInteraction) {
            val terminal = ev.type == InteractionTypes.WS_ERROR ||
                (ev.type == InteractionTypes.WS_CLOSE && callbackOf(ev) == "closed")
            if (terminal) replay!!.unbind(scope)
            executor.execute { deliver(scope, ev) }
        }

        private fun callbackOf(ev: RecordedInteraction): String =
            ev.data["callback"] as? String ?: if (ev.data["initiator"] == "server") "closing" else "closed"

        private fun deliver(scope: String, ev: RecordedInteraction) {
            val d = ev.data
            when (ev.type) {
                InteractionTypes.WS_OPEN -> app.onOpen(this, handshake(101, "Switching Protocols", d))
                InteractionTypes.WS_MESSAGE -> {
                    val p = ev.payload("payload")
                    if (p == null || !p.replayable) {
                        diverge("ws.noPayload", "$scope message #${d["index"]} (${p?.mode?.wire ?: "missing"}, ${p?.kind ?: "text"})")
                        return
                    }
                    when (val value = p.decoded) {
                        is ByteArray -> app.onMessage(this, value.toByteString())
                        else -> if (p.kind == "binary") {
                            diverge("ws.noPayload", "$scope message #${d["index"]}: binary frame without bytes")
                        } else {
                            app.onMessage(this, value?.toString() ?: "")
                        }
                    }
                }
                InteractionTypes.WS_CLOSE -> {
                    val code = J.int(d["code"]) ?: 1005
                    val reason = d["reason"] as? String ?: ""
                    if (callbackOf(ev) == "closing") app.onClosing(this, code, reason) else app.onClosed(this, code, reason)
                }
                InteractionTypes.WS_ERROR -> {
                    val error = J.obj(d["error"]) ?: emptyMap()
                    val status = J.int(d["status"])
                    app.onFailure(
                        this,
                        exception(error["type"] as? String, error["message"] as? String ?: "recorded WebSocket failure"),
                        status?.let { handshake(it, "", d) },
                    )
                }
            }
        }

        private fun handshake(code: Int, message: String, d: Map<String, Any?>): Response {
            val headers = Headers.Builder()
            (d["protocol"] as? String)?.let { headers.add("Sec-WebSocket-Protocol", it) }
            (d["extensions"] as? String)?.let { headers.add("Sec-WebSocket-Extensions", it) }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .headers(headers.build())
                .build()
        }

        override fun request(): Request = request
        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = send(text, "text")

        override fun send(bytes: ByteString): Boolean = send(bytes.toByteArray(), "binary")

        private fun send(value: Any, kind: String): Boolean {
            val index = sends.getAndIncrement()
            val s = scope ?: return false
            val recorded = replay!!.takeNext(s, SEND)
            if (recorded == null) {
                diverge("ws.unrecorded", "$s send #$index ($kind): not in the recording")
                return false
            }
            val expected = recorded.payload("payload")
            val actual = encoder.encode(value, kind)
            if (expected != null && !expected.sameAs(actual)) {
                diverge("ws.mismatch", "$s send #$index differs from the recording (${actual.bytes} vs ${expected.bytes} bytes)")
            }
            return recorded.data["result"] as? Boolean ?: true
        }

        override fun close(code: Int, reason: String?): Boolean {
            val s = scope ?: return false
            val recorded = replay!!.takeNext(s, CLOSE)
            if (recorded == null) {
                diverge("ws.unrecorded", "$s close($code): not in the recording")
                return false
            }
            val d = recorded.data
            val sameReason = (d["reason"] as? String ?: "") == redactor.scrubText(reason ?: "")
            if (d["cancel"] == true || J.int(d["code"]) != code || !sameReason) {
                diverge("ws.mismatch", "$s close($code) differs from the recording (${if (d["cancel"] == true) "cancel" else "close(${d["code"]})"})")
            }
            return d["result"] as? Boolean ?: true
        }

        override fun cancel() {
            val s = scope ?: return
            val recorded = replay!!.takeNext(s, CLOSE)
            if (recorded == null) {
                diverge("ws.unrecorded", "$s cancel(): not in the recording")
            } else if (recorded.data["cancel"] != true) {
                diverge("ws.mismatch", "$s cancel() differs from the recording (close(${recorded.data["code"]}))")
            }
        }

        private companion object {
            val SEND = setOf(InteractionTypes.WS_SEND)
            val CLOSE = setOf(InteractionTypes.WS_CLOSE)

            fun exception(type: String?, message: String): Throwable = when (type) {
                "SocketTimeoutException" -> SocketTimeoutException(message)
                "ConnectException" -> ConnectException(message)
                "UnknownHostException" -> UnknownHostException(message)
                "EOFException" -> EOFException(message)
                "ProtocolException" -> ProtocolException(message)
                "SocketException" -> SocketException(message)
                else -> IOException(if (type == null) message else "$type: $message")
            }
        }
    }

    companion object {
        const val ADAPTER_ID = "bugcapsule-okhttp.websocket"
        const val SCHEMA = 1
        const val OKHTTP_PACKAGE = "com.squareup.okhttp3:okhttp"

        /** OkHttp version this adapter is tested against (`tested` in the capsule). */
        const val TESTED_OKHTTP = "4.12.0"

        /** `https://` → `wss://`, `http://` → `ws://` (OkHttp stores WebSocket URLs as HTTP). */
        internal fun wsUrl(url: HttpUrl): String {
            val s = url.toString()
            return when {
                s.startsWith("https:") -> "wss:" + s.substring(6)
                s.startsWith("http:") -> "ws:" + s.substring(5)
                else -> s
            }
        }
    }
}
