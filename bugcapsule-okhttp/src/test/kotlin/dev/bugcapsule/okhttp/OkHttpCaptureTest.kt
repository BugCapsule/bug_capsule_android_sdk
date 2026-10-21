package dev.bugcapsule.okhttp

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpCaptureTest {
    private val server = MockWebServer().apply { start() }
    private val session = FakeSession(maxBodyBytes = 1024)
    private val bugCapsule = BugCapsuleOkHttp(maxBinaryBytes = 64).also { it.setUp(session) }
    private val client = OkHttpClient.Builder()
        .addInterceptor { it.proceed(it.request().newBuilder().header("Authorization", "Bearer app-token-123456").build()) }
        .addInterceptor(bugCapsule.interceptor)
        .build()

    @After
    fun stop() = server.shutdown()

    private fun url(path: String) = server.url(path)

    @Test
    fun jsonExchangeIsRecordedRedactedAndKeyed() {
        server.enqueue(MockResponse().setBody("{\"token\":\"t-1\",\"ok\":true}").setHeader("Content-Type", "application/json").setHeader("Set-Cookie", "a=b"))
        val body = "{\"user\":\"qa\",\"password\":\"hunter2\"}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url("/api/login?code=xyz&lang=en")).post(body).build()).execute().use {
            assertEquals("{\"token\":\"t-1\",\"ok\":true}", it.body!!.string())
        }
        val e = session.awaitHttp(1).single()
        assertEquals("POST", e["method"])
        assertTrue(e["key"] as String, (e["key"] as String).startsWith("POST ${server.hostName}/api/login?code=[REDACTED]&lang=en#"))
        assertEquals(0, e["occurrence"])
        assertEquals(mapOf("user" to "qa", "password" to "[REDACTED]"), e["requestBody"])
        assertEquals(mapOf("token" to "[REDACTED]", "ok" to true), e["responseBody"])
        assertEquals("json", e["bodyEncoding"])
        assertEquals("[REDACTED]", (e["requestHeaders"] as Map<*, *>)["authorization"])
        assertEquals("[REDACTED]", (e["responseHeaders"] as Map<*, *>)["set-cookie"])
        assertTrue(e["url"] as String, (e["url"] as String).contains("code=%5BREDACTED%5D"))
    }

    @Test
    fun occurrencesCountPerKey() {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        repeat(3) { client.newCall(Request.Builder().url(url("/a")).build()).execute().close() }
        assertEquals(listOf(0, 1, 2), session.awaitHttp(3).map { it["occurrence"] })
    }

    @Test
    fun largeBodiesStreamUntouchedAndAreRecordedTruncated() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(big)).setHeader("Content-Type", "application/octet-stream"))
        val received = client.newCall(Request.Builder().url(url("/big")).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(big, received)
        val e = session.awaitHttp(1).single()
        assertEquals("truncated", e["bodyEncoding"])
        assertNull(e["responseBody"])
    }

    @Test
    fun smallBinaryIsBase64() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))).setHeader("Content-Type", "image/png"))
        client.newCall(Request.Builder().url(url("/img")).build()).execute().use { it.body!!.bytes() }
        val e = session.awaitHttp(1).single()
        assertEquals("base64", e["bodyEncoding"])
        assertEquals("AQID", e["responseBody"])
    }

    @Test
    fun oneShotRequestBodiesAreNeverRead() {
        server.enqueue(MockResponse().setBody("ok").setHeader("Content-Type", "text/plain"))
        var writes = 0
        val oneShot = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                writes++
                sink.writeUtf8("stream-once")
            }
        }
        client.newCall(Request.Builder().url(url("/upload")).post(oneShot).build()).execute().close()
        assertEquals("only OkHttp wrote it", 1, writes)
        assertEquals("stream-once", server.takeRequest().body.readUtf8())
        val e = session.awaitHttp(1).single()
        assertEquals("omitted", e["requestEncoding"])
        assertTrue("keyed without a body hash", !(e["key"] as String).contains("#"))
    }

    @Test
    fun responseClosedEarlyIsRecordedAsCancelled() {
        server.enqueue(MockResponse().setBody("{\"x\":\"" + "y".repeat(600) + "\"}").setHeader("Content-Type", "application/json"))
        client.newCall(Request.Builder().url(url("/partial")).build()).execute().use { it.body!!.source().readUtf8(10) }
        val e = session.awaitHttp(1).single()
        assertEquals("truncated", e["bodyEncoding"])
        assertEquals("cancel", (e["error"] as Map<*, *>)["type"])
    }

    @Test
    fun connectionErrorsAreRecordedAndRethrown() {
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/x")
        dead.shutdown()
        try {
            client.newCall(Request.Builder().url(deadUrl).build()).execute()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        val e = session.awaitHttp(1).single()
        assertEquals(0, e["status"])
        assertEquals("connectionError", (e["error"] as Map<*, *>)["type"])
    }

    @Test
    fun readTimeoutsAreReceiveTimeouts() {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(2, TimeUnit.SECONDS))
        val slow = client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        try {
            slow.newCall(Request.Builder().url(url("/slow")).build()).execute()
            fail("expected timeout")
        } catch (_: SocketTimeoutException) {
        }
        assertEquals("receiveTimeout", (session.awaitHttp(1).single()["error"] as Map<*, *>)["type"])
    }

    @Test
    fun cancelledCallsAreCancel() {
        server.enqueue(MockResponse().setBody("x").setHeadersDelay(2, TimeUnit.SECONDS))
        val call = client.newCall(Request.Builder().url(url("/cancel")).build())
        val done = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done.countDown()
            override fun onResponse(call: Call, response: Response) = done.countDown()
        })
        Thread.sleep(200)
        call.cancel()
        done.await(5, TimeUnit.SECONDS)
        assertEquals("cancel", (session.awaitHttp(1).single()["error"] as Map<*, *>)["type"])
    }

    @Test
    fun bodiesOffRecordsAtHeadersAsOmitted() {
        session.captureNetworkBodies = false
        server.enqueue(MockResponse().setBody("{\"a\":1}").setHeader("Content-Type", "application/json"))
        client.newCall(Request.Builder().url(url("/nobody")).post("{\"q\":1}".toRequestBody("application/json".toMediaType())).build())
            .execute().use { assertEquals("{\"a\":1}", it.body!!.string()) }
        val e = session.awaitHttp(1).single()
        assertEquals("omitted", e["bodyEncoding"])
        assertEquals("omitted", e["requestEncoding"])
        assertTrue("key still hashes the body", (e["key"] as String).contains("#"))
    }

    @Test
    fun excludedRequestsKeepNoHeadersOrBodies() {
        val excluding = BugCapsuleOkHttp(exclude = { it.url.encodedPath == "/pay" }).also { it.setUp(session) }
        server.enqueue(MockResponse().setBody("{}"))
        OkHttpClient.Builder().addInterceptor(excluding.interceptor).build()
            .newCall(Request.Builder().url(url("/pay")).build()).execute().close()
        val e = session.awaitHttp(1).single()
        assertEquals(true, e["excluded"])
        assertNull(e["requestHeaders"])
        assertEquals("omitted", e["bodyEncoding"])
    }

    @Test
    fun streamingSseIsOmitted() {
        server.enqueue(MockResponse().setBody("data: 1\n\n").setHeader("Content-Type", "text/event-stream"))
        client.newCall(Request.Builder().url(url("/events")).build()).execute().use { assertEquals("data: 1\n\n", it.body!!.string()) }
        assertEquals("omitted", session.awaitHttp(1).single()["bodyEncoding"])
    }

    @Test
    fun inFlightExchangesAreRecordedAsPendingBeforeACapsule() {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS))
        val call = client.newCall(Request.Builder().url(url("/slow-profile")).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
        Thread.sleep(300)
        bugCapsule.beforeCapsule(crash = true)
        val e = session.http().single()
        assertEquals(true, e["pending"])
        assertEquals(0, e["status"])
        assertTrue((e["key"] as String).endsWith("/slow-profile"))
        call.cancel()
        Thread.sleep(200)
        assertEquals("not recorded twice", 1, session.http().size)
    }

    interface Api {
        @POST("api/checkout")
        fun checkout(@Body body: Map<String, @JvmSuppressWildcards Any>): retrofit2.Call<Map<String, Any>>
    }

    @Test
    fun retrofitWorksThroughTheInterceptor() {
        server.enqueue(MockResponse().setBody("{\"orderId\":\"o1\"}").setHeader("Content-Type", "application/json"))
        val api = Retrofit.Builder().baseUrl(url("/")).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java)
        val res = api.checkout(mapOf("cart" to "c1")).execute()
        assertEquals("o1", res.body()!!["orderId"])
        val e = session.awaitHttp(1).single()
        assertEquals(mapOf("cart" to "c1"), e["requestBody"])
        assertEquals(mapOf("orderId" to "o1"), e["responseBody"])
    }
}
