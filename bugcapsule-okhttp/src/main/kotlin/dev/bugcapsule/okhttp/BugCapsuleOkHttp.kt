package dev.bugcapsule.okhttp

import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.core.DartUri
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.EndpointRule
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.JsonDepthException
import dev.bugcapsule.android.core.JsonFormatException
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.RequestKeyBuilder
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Requests recorded without headers or bodies (e.g. login or payment endpoints). */
fun interface RequestFilter {
    fun exclude(request: Request): Boolean
}

/**
 * OkHttp integration (`network.okhttp`): captures exchanges and serves them
 * deterministically in replay. Retrofit works through it unchanged.
 *
 * Add [interceptor] as the **last application interceptor**, after the app's
 * own interceptors (auth, headers, logging), so they keep running in replay:
 *
 * ```kotlin
 * val okHttp = BugCapsuleOkHttp(rules = listOf(EndpointRule("POST", "/api/checkout", ignoreBodyFields = setOf("nonce"))))
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(AuthInterceptor())
 *     .addInterceptor(okHttp.interceptor)   // innermost application interceptor
 *     .build()
 * BugCapsule.init(app, config, listOf(okHttp))
 * ```
 *
 * In replay the interceptor answers from the capsule without calling
 * `chain.proceed`: interceptors added after it, network interceptors and the
 * network itself are not used (strict by default; unrecorded requests fail
 * with [ConnectException]).
 *
 * Capture never buffers whole bodies: the response source is tapped while the
 * app reads it (same bytes, same timing) and only a bounded prefix is kept
 * (`maxBodyBytes`, binary [maxBinaryBytes]); one-shot and duplex request
 * bodies are never read (keyed without a hash, recorded as `omitted`).
 */
class BugCapsuleOkHttp @JvmOverloads constructor(
    rules: List<EndpointRule> = emptyList(),
    ignoreQuery: Set<String> = RequestKeyBuilder.DEFAULT_IGNORE_QUERY,
    ignoreBodyFields: Set<String> = emptySet(),
    val maxBinaryBytes: Int = 64 * 1024,
    /** Request bodies up to this size take part in the key (fixed, so capture and replay agree). */
    val maxKeyBodyBytes: Int = 64 * 1024,
    /** A response still streaming after this long is recorded as `truncated`. */
    val maxBodyWaitMs: Long = 30_000,
    val exclude: RequestFilter? = null,
) : BugCapsuleIntegration {
    val keys = RequestKeyBuilder(rules, ignoreQuery, ignoreBodyFields)

    @Volatile
    private var session: BugCapsuleSession? = null
    private val occurrences = ConcurrentHashMap<String, AtomicInteger>()
    private val nextId = AtomicInteger()

    /** Exchanges started but not yet recorded, by id (see [beforeCapsule]). */
    private val inFlight = ConcurrentHashMap<Any, () -> Unit>()

    override val name: String = "okhttp"

    override fun setUp(session: BugCapsuleSession) {
        this.session = session
    }

    override fun capabilities() = mapOf("network.okhttp" to "supported")

    /** The interceptor to add last among the client's application interceptors. */
    val interceptor: Interceptor = Interceptor { chain -> intercept(chain) }

    /** Appends [interceptor] to [builder] (call after adding your own interceptors). */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.addInterceptor(interceptor)

    /** Records unfinished exchanges as `pending` so replay holds them (they never answered before the capsule). */
    override fun beforeCapsule(crash: Boolean) {
        for ((token, recordPending) in inFlight.entries.toList()) {
            if (inFlight.remove(token) != null) recordPending()
        }
    }

    override fun describeEligibility(eligibility: EligibilityBuilder, window: RecordedWindow) {
        val missing = window.events.count {
            it.type == EventTypes.HTTP && it.data["error"] == null && it.data["pending"] != true &&
                (it.data["bodyEncoding"] == "omitted" || it.data["bodyEncoding"] == "truncated")
        }
        if (missing > 0) {
            eligibility.missing(
                "network.bodies",
                "$missing recorded responses have no body (capture disabled, excluded or too large)",
            )
        }
    }

    private fun intercept(chain: Interceptor.Chain): Response {
        val s = session ?: return chain.proceed(chain.request())
        val replay = s.replay
        if (replay != null) return replay(chain, s, replay)
        if (!s.isRecording) return chain.proceed(chain.request())
        return capture(chain, s)
    }

    // ------------------------------------------------------------- bodies

    /** The request body as read for keying/recording; [complete] when read whole within the limit. */
    internal class RequestBytes(val bytes: ByteArray, val complete: Boolean, val unreadable: Boolean)

    private class LimitReached : IOException()

    /**
     * Writes a re-writable request body into memory, up to [limit] bytes.
     * One-shot and duplex bodies are not touched (they would be consumed).
     */
    private fun readRequest(request: Request, limit: Int): RequestBytes? {
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex()) return RequestBytes(ByteArray(0), complete = false, unreadable = true)
        val buffer = Buffer()
        return try {
            val sink = object : ForwardingSink(buffer) {
                override fun write(source: Buffer, byteCount: Long) {
                    if (buffer.size + byteCount > limit) throw LimitReached()
                    super.write(source, byteCount)
                }
            }.buffer()
            body.writeTo(sink)
            sink.flush()
            RequestBytes(buffer.readByteArray(), complete = true, unreadable = false)
        } catch (_: LimitReached) {
            RequestBytes(buffer.readByteArray(), complete = false, unreadable = false)
        } catch (_: IOException) {
            RequestBytes(ByteArray(0), complete = false, unreadable = true)
        }
    }

    private fun requestContentType(request: Request): String? =
        request.body?.contentType()?.toString() ?: request.header("Content-Type")

    /** Decodes a complete body: redacted JSON, form fields, scrubbed text, or small binary as base64. */
    internal fun decode(s: BugCapsuleSession, bytes: ByteArray, contentType: String?): Pair<Any?, String> {
        if (bytes.isEmpty()) return null to "json"
        val redactor = s.redactor
        val type = (contentType ?: "").lowercase()
        if (isText(type)) {
            val text = try {
                Json.decodeUtf8(bytes)
            } catch (_: JsonFormatException) {
                return binary(bytes)
            }
            if (type.contains("json") || type.isEmpty()) {
                return try {
                    redactor.redactJson(Json.parse(text)) to "json"
                } catch (_: JsonFormatException) {
                    redactor.scrubText(text) to "text"
                } catch (_: JsonDepthException) {
                    redactor.scrubText(text) to "text"
                }
            }
            if (type.contains("x-www-form-urlencoded")) {
                return try {
                    redactor.redactJson(splitQueryString(text)) to "form"
                } catch (_: IllegalArgumentException) {
                    redactor.scrubText(text) to "text"
                }
            }
            return redactor.scrubText(text) to "text"
        }
        return binary(bytes)
    }

    private fun binary(bytes: ByteArray): Pair<Any?, String> =
        if (bytes.size > maxBinaryBytes) null to "truncated" else bytes.toByteString().base64() to "base64"

    internal fun encodeBody(body: Any?, encoding: String): ByteArray = when (encoding) {
        "json" -> if (body == null) ByteArray(0) else Json.stringify(body).toByteArray(Charsets.UTF_8)
        "text" -> (body as? String ?: "").toByteArray(Charsets.UTF_8)
        "form" -> {
            val map = body as? Map<*, *> ?: emptyMap<Any, Any>()
            map.entries.joinToString("&") { (k, v) ->
                val key = DartUri.encodeQueryComponent(k.toString())
                val value = v?.toString() ?: ""
                if (value.isEmpty()) key else "$key=${DartUri.encodeQueryComponent(value)}"
            }.toByteArray(Charsets.UTF_8)
        }
        "base64" -> ((body as String).decodeBase64() ?: throw IOException("invalid base64 body")).toByteArray()
        else -> ByteArray(0)
    }

    /**
     * The request key: same inputs in capture and replay (redacted URL, and
     * the body when complete within [maxKeyBodyBytes] and not multipart).
     */
    internal fun keyFor(s: BugCapsuleSession, request: Request, body: RequestBytes?): String {
        val uri = s.redactor.redactUri(DartUri.parse(request.url.toString()))
        val contentType = requestContentType(request)
        var keyBody: Any? = null
        if (body != null && body.complete && body.bytes.size <= maxKeyBodyBytes && !isMultipart(contentType)) {
            keyBody = decode(s, body.bytes, contentType).first
        }
        return keys.keyFor(request.method, uri, keyBody)
    }

    // ------------------------------------------------------------ capture

    private fun capture(chain: Interceptor.Chain, s: BugCapsuleSession): Response {
        val request = chain.request()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val excluded = try {
            exclude?.exclude(request) ?: false
        } catch (_: Exception) {
            false
        }
        val captureBodies = s.captureNetworkBodies && !excluded
        val requestType = requestContentType(request)
        val readLimit = maxOf(s.maxBodyBytes, maxKeyBodyBytes)
        val requestBytes = try {
            readRequest(request, readLimit)
        } catch (_: Exception) {
            null
        }
        // Key and occurrence are fixed when the request starts, as replay looks them up.
        var key: String? = null
        var occurrence = 0
        try {
            key = keyFor(s, request, requestBytes)
            occurrence = occurrences.getOrPut(key) { AtomicInteger() }.getAndIncrement()
        } catch (_: Exception) {
            // Keying failed: the exchange is not recorded; the request still runs.
        }

        fun base(): MutableMap<String, Any?> {
            var requestBody: Any? = null
            var requestEncoding = "json"
            if (requestBytes != null && (requestBytes.bytes.isNotEmpty() || requestBytes.unreadable || !requestBytes.complete)) {
                when {
                    !captureBodies || isMultipart(requestType) || requestBytes.unreadable -> requestEncoding = "omitted"
                    requestBytes.complete && requestBytes.bytes.size <= s.maxBodyBytes ->
                        decode(s, requestBytes.bytes, requestType).let { (b, e) ->
                            requestBody = b
                            requestEncoding = e
                        }
                    else -> requestEncoding = "truncated"
                }
            }
            val out = linkedMapOf<String, Any?>(
                "id" to nextId.getAndIncrement(),
                "key" to key,
                "occurrence" to occurrence,
                "method" to request.method.uppercase(),
                "url" to s.redactor.redactUrl(request.url.toString()),
            )
            if (!excluded) {
                val headers = LinkedHashMap<String, Any?>()
                requestType?.let { headers["content-type"] = it }
                for ((name, values) in request.headers.toMultimap()) headers[name] = values
                out["requestHeaders"] = s.redactor.redactHeaders(headers)
            }
            out["requestBody"] = requestBody
            out["requestEncoding"] = requestEncoding
            if (excluded) out["excluded"] = true
            return out
        }

        val token = Any()
        val baseData by lazy { base() }
        if (key != null) {
            inFlight[token] = {
                record(s) {
                    LinkedHashMap(baseData).apply {
                        put("status", 0)
                        put("durationMs", elapsed())
                        put("bodyEncoding", "omitted")
                        put("pending", true)
                    }
                }
            }
        }
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (key != null && inFlight.remove(token) != null) {
                record(s) {
                    LinkedHashMap(baseData).apply {
                        put("status", 0)
                        put("durationMs", elapsed())
                        put("bodyEncoding", "omitted")
                        put("error", errorJson(s, e, chain.call().isCanceled()))
                    }
                }
            }
            throw e
        }
        if (key == null) return response

        fun head(): MutableMap<String, Any?> = LinkedHashMap(baseData).apply {
            put("status", response.code)
            put("statusMessage", response.message)
            if (!excluded) put("responseHeaders", s.redactor.redactHeaders(response.headers.toMultimap()))
        }

        val body = response.body
        val contentType = body?.contentType()?.toString() ?: response.header("Content-Type")
        val sse = contentType?.lowercase()?.contains("event-stream") == true
        if (body == null || sse || !captureBodies) {
            // Nothing to keep: record now and hand the response over untouched.
            if (inFlight.remove(token) == null) return response
            record(s) { head().apply { put("durationMs", elapsed()); put("bodyEncoding", "omitted") } }
            return response
        }
        val limit = if (isText((contentType ?: "").lowercase())) s.maxBodyBytes else maxBinaryBytes
        val tapped = TappedBody(body, limit)
        tapped.onFinish = { extra ->
            if (inFlight.remove(token) != null) record(s) { head().apply { put("durationMs", elapsed()); putAll(extra) } }
        }
        tapped.decode = { bytes -> decode(s, bytes, contentType) }
        tapped.errorJson = { e -> errorJson(s, e, chain.call().isCanceled()) }
        s.schedule(maxBodyWaitMs) { tapped.finish(mapOf("bodyEncoding" to "truncated")) }
        return response.newBuilder().body(tapped).build()
    }

    /** Recorded when the exchange completes: `t` is that time, the request was sent at `t - durationMs`. */
    private inline fun record(s: BugCapsuleSession, data: () -> Map<String, Any?>) {
        try {
            s.record(EventTypes.HTTP, data())
        } catch (_: Exception) {
            // Recording must never break the app's request.
        }
    }

    private fun errorJson(s: BugCapsuleSession, e: IOException, canceled: Boolean): Map<String, Any?> = linkedMapOf(
        "type" to errorType(e, canceled),
        "message" to s.redactor.scrubText(e.message ?: e.javaClass.simpleName),
    )

    /**
     * Passes the response through unchanged (same source object for every
     * `source()` call) while keeping the first [limit] bytes.
     */
    private class TappedBody(private val original: ResponseBody, private val limit: Int) : ResponseBody() {
        var onFinish: (Map<String, Any?>) -> Unit = {}
        var decode: (ByteArray) -> Pair<Any?, String> = { null to "truncated" }
        var errorJson: (IOException) -> Map<String, Any?> = { emptyMap() }
        private val done = AtomicBoolean(false)
        private var retained: Buffer? = Buffer()

        @Volatile
        private var complete = false

        fun finish(extra: Map<String, Any?>) {
            if (!done.compareAndSet(false, true)) return
            try {
                onFinish(extra)
            } catch (_: Throwable) {
            }
            synchronized(this) { retained = null }
        }

        override fun contentType(): MediaType? = original.contentType()
        override fun contentLength(): Long = original.contentLength()

        private val tapped: BufferedSource by lazy {
            object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        guard { finish(mapOf("bodyEncoding" to "truncated", "error" to errorJson(e))) }
                        throw e
                    }
                    guard {
                        if (n == -1L) {
                            complete = true
                            if (!done.get()) {
                                val bytes = synchronized(this@TappedBody) { retained?.readByteArray() }
                                if (bytes != null) {
                                    val (body, encoding) = try {
                                        decode(bytes)
                                    } catch (_: Exception) {
                                        null to "truncated"
                                    }
                                    finish(mapOf("responseBody" to body, "bodyEncoding" to encoding))
                                }
                            }
                        } else if (n > 0) {
                            synchronized(this@TappedBody) {
                                val r = retained
                                if (r != null) {
                                    if (r.size + n <= limit) {
                                        sink.copyTo(r, sink.size - n, n)
                                    } else {
                                        retained = null
                                    }
                                }
                            }
                            if (synchronized(this@TappedBody) { retained == null } && !done.get()) {
                                finish(mapOf("bodyEncoding" to "truncated"))
                            }
                        }
                    }
                    return n
                }

                override fun close() {
                    if (!complete) {
                        guard {
                            finish(
                                mapOf(
                                    "bodyEncoding" to "truncated",
                                    "error" to mapOf("type" to "cancel", "message" to "response body closed before the end"),
                                ),
                            )
                        }
                    }
                    super.close()
                }
            }.buffer()
        }

        override fun source(): BufferedSource = tapped

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------- replay

    private fun replay(chain: Interceptor.Chain, s: BugCapsuleSession, replay: ReplayContext): Response {
        val request = chain.request()
        val requestBytes = readRequest(request, maxOf(s.maxBodyBytes, maxKeyBodyBytes))
        val key = keyFor(s, request, requestBytes)
        val lookup = replay.fixtures.take(key)
        if (lookup == null) {
            replay.diverge("network.unrecorded", key)
            if (!replay.settings.strictNetwork) {
                val token = replay.beginWork()
                try {
                    return chain.proceed(request)
                } finally {
                    token.end()
                }
            }
            throw ConnectException("BugCapsule replay: no recorded response for $key")
        }
        if (lookup.reused) replay.diverge("network.reused", key)
        val fixture = lookup.fixture
        replay.fixtureServed()
        if (fixture.data["pending"] == true) {
            // It never answered before the capsule was written: hold it as the
            // recording did, until the app cancels the call (or the attempt ends).
            while (true) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("interrupted")
                }
            }
        }

        // Keep the recorded latency so response ordering (and races) replay.
        val token = replay.beginWork()
        try {
            val deadline = System.nanoTime() + fixture.durationMs * 1_000_000
            while (true) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                try {
                    Thread.sleep(minOf(left, 10))
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("interrupted")
                }
            }
        } finally {
            token.end()
        }

        val error = fixture.error
        if (error != null && fixture.status == 0) {
            val message = error["message"]?.toString() ?: "recorded network error"
            throw when (error["type"]) {
                "connectionTimeout" -> SocketTimeoutException("connect timed out ($message)")
                "receiveTimeout" -> SocketTimeoutException("timeout ($message)")
                "cancel" -> IOException("Canceled")
                else -> ConnectException(message)
            }
        }
        if (fixture.status == 0) throw ConnectException("BugCapsule replay: recorded exchange for $key has no response")
        if (!fixture.hasBody && fixture.status != 204) replay.diverge("network.noBody", key)
        val headers = Headers.Builder()
        for ((name, value) in fixture.responseHeaders) {
            if (name == "content-length" || name == "content-encoding" || name == "transfer-encoding") continue
            try {
                headers.add(name, value)
            } catch (_: IllegalArgumentException) {
                headers.addUnsafeNonAscii(name, value)
            }
        }
        val mediaType = fixture.responseHeaders["content-type"]?.toMediaTypeOrNull()
        val bytes = encodeBody(fixture.responseBody, fixture.bodyEncoding)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(fixture.status)
            .message(fixture.statusMessage ?: "")
            .headers(headers.build())
            .body(bytes.toResponseBody(mediaType))
            .sentRequestAtMillis(System.currentTimeMillis() - fixture.durationMs)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
    }

    internal companion object {
        fun isMultipart(contentType: String?) = (contentType ?: "").lowercase().startsWith("multipart/")

        fun isText(type: String) = type.contains("json") || type.startsWith("text/") || type.contains("xml") ||
            type.contains("x-www-form-urlencoded") || type.isEmpty()

        /** runtimes-v1 §4 `error.type` vocabulary for OkHttp. */
        fun errorType(e: IOException, canceled: Boolean): String = when {
            canceled -> "cancel"
            e is SocketTimeoutException -> if (e.message?.contains("connect", ignoreCase = true) == true) "connectionTimeout" else "receiveTimeout"
            e is InterruptedIOException && e.message == "timeout" -> "receiveTimeout"
            else -> "connectionError"
        }

        /** Dart's `Uri.splitQueryString`: last value wins, `=x` pairs skipped. */
        fun splitQueryString(text: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (element in text.split("&")) {
                val index = element.indexOf('=')
                if (index == -1) {
                    if (element.isNotEmpty()) map[DartUri.decodeQueryComponent(element)] = ""
                } else if (index != 0) {
                    map[DartUri.decodeQueryComponent(element.substring(0, index))] =
                        DartUri.decodeQueryComponent(element.substring(index + 1))
                }
            }
            return map
        }
    }
}
