package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol v1.1 against a local server, including stale-attempt answers (409/410). */
class ReplayChannelTest {
    private val server = MockWebServer()
    private val posted = ArrayList<String>()

    @Volatile
    private var resultStatus = 204
    private val capsuleBytes = CapsuleCodec.encode(TestCapsules.capsule(emptyList()))

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/a/tok/settings" -> MockResponse().setChunkedBody(
                    Json.stringify(ReplaySettings(attempt = 2, attemptId = "id-2", runtime = "android").toJson()), 7,
                )
                "/a/tok/capsule" -> MockResponse().setBody(Buffer().write(capsuleBytes))
                "/a/tok/result" -> {
                    posted.add(request.body.readUtf8())
                    MockResponse().setResponseCode(resultStatus)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val base get() = "http://127.0.0.1:${server.port}/a/tok"
    private val report = ReplayReport(ReplayOutcome.REPRODUCED, 2, 10, ReplayStrategy.POINTER, attemptId = "id-2")

    @Test
    fun fetchesSettingsAndCapsule() {
        val (capsule, settings) = ReplayChannel(base).fetch()
        assertEquals(2, settings.attempt)
        assertEquals("id-2", settings.attemptId)
        assertEquals("c0ffee00-0000-4000-8000-000000000001", capsule.manifest.capsuleId)
    }

    @Test
    fun postsTheReportAndSurfacesStaleAttempts() {
        val channel = ReplayChannel(base)
        assertEquals(204, channel.postResult(report))
        assertTrue(posted.last().contains("\"attemptId\":\"id-2\""))
        resultStatus = 410
        assertEquals(410, channel.postResult(report))
        resultStatus = 409
        assertEquals(409, channel.postResult(report))
    }

    @Test
    fun wrongTokenIs404() {
        val res = ReplayChannel("http://127.0.0.1:${server.port}/a/other").request("GET", "/settings", null)
        assertEquals(404, res.status)
    }

    @Test
    fun unreachableServerIsNotAnException() {
        assertNull(ReplayChannel("http://127.0.0.1:1/a/x").postResult(report))
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyHttpBaseUrls() {
        ReplayChannel("file:///etc/passwd")
    }
}
