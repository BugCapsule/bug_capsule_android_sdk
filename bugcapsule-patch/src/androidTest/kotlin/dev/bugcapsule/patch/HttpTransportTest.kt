package dev.bugcapsule.patch

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A real socket on the device, so headers, limits and redirects are exercised. */
@RunWith(AndroidJUnit4::class)
class HttpTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var t: HttpPatchTransport
    private val digest = "sha256:" + "a".repeat(64)
    private fun b64(vararg b: Byte) = Base64.encodeToString(b, Base64.NO_WRAP)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        t = HttpPatchTransport(server.url("/").toString().trimEnd('/').replace("localhost", "127.0.0.1"), "bcpd_secret", "com.shop", "android", "internal", "build-1", "install-1", 2_000, 2_000)
    }

    @After fun tearDown() = server.shutdown()

    private fun reason(block: () -> Unit) = assertThrows(PatchHttpException::class.java) { block() }.reason

    @Test fun fetchesTheChannelWithScopeTokenAndExactBytes() {
        val body = JSONObject()
            .put("channel", JSONObject().put("document", b64(1, 2)).put("signature", b64(3)))
            .put("keyring", JSONObject().put("version", 2).put("document", b64(4)).put("signature", b64(5)))
        server.enqueue(MockResponse().setBody(body.toString()).setHeader("ETag", "\"abc\""))
        val c = t.fetchChannel(null)!!
        assertEquals(listOf<Byte>(1, 2), c.document.toList())
        assertEquals(listOf<Byte>(4), c.keyringDocument!!.toList())
        assertEquals("\"abc\"", c.etag)
        val r = server.takeRequest()
        assertEquals("/v1/apps/com.shop/patches/channel?platform=android&distributionProfile=internal&baseBuildId=build-1", r.path)
        assertEquals("Bearer bcpd_secret", r.getHeader("Authorization"))
    }

    @Test fun notModifiedAndNoContentMeanNothingToDo() {
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(t.fetchChannel("\"abc\""))
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(t.fetchChannel(null))
    }

    @Test fun declaredOversizeIsRefusedBeforeReading() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(HttpPatchTransport.MAX_CHANNEL_BYTES + 1))))
        assertEquals("response_too_large", reason { t.fetchChannel(null) })
    }

    @Test fun undeclaredOversizeStopsAtTheLimit() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(HttpPatchTransport.MAX_ARTIFACT_BYTES + 1)), 1 shl 20))
        assertEquals("response_too_large", reason { t.fetchArtifact(digest) })
    }

    @Test fun aTrickledBodyCannotOutliveTheAbsoluteDeadline() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1 shl 20))).throttleBody(1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
        val start = System.nanoTime()
        assertEquals("timed_out", reason { t.fetchArtifact(digest) })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000)
    }

    @Test fun anErrorBodyIsNeverRead() {
        server.enqueue(MockResponse().setResponseCode(500).setBody(Buffer().write(ByteArray(1 shl 20))).throttleBody(1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
        val start = System.nanoTime()
        assertEquals("http_error", reason { t.fetchArtifact(digest) })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
    }

    @Test fun redirectsAreNotFollowed() {
        val other = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal").toString()))
        assertEquals("redirect_refused", reason { t.fetchArtifact(digest) })
        assertEquals("the token must not follow a redirect", 0, other.requestCount)
        other.shutdown()
    }

    @Test fun errorsAreTyped() {
        for ((status, want) in listOf(401 to "unauthorized", 404 to "not_found", 410 to "gone", 429 to "rate_limited", 500 to "http_error")) {
            server.enqueue(MockResponse().setResponseCode(status))
            assertEquals(want, reason { t.fetchArtifact(digest) })
        }
    }

    @Test fun malformedDigestNeverReachesAPath() {
        assertEquals("bad_digest", reason { t.fetchArtifact("../secrets") })
        assertEquals(0, server.requestCount)
    }

    @Test fun receiptsMapToTheApiAndDropUnknownEvents() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val receipts = JSONArray()
            .put(JSONObject().put("seq", 1).put("event", "staged").put("digest", digest).put("reason", JSONObject.NULL).put("at", 5))
            .put(JSONObject().put("seq", 2).put("event", "something_new").put("digest", JSONObject.NULL).put("reason", JSONObject.NULL).put("at", 6))
        t.postReceipts(JSONObject().put("receipts", receipts).put("status", JSONObject().put("activeDigest", JSONObject.NULL).put("pending", digest).put("channelRevision", 4)))
        val b = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("install-1", b.getString("installationId"))
        assertEquals(digest, b.getString("stagedDigest"))
        assertEquals(1, b.getJSONArray("receipts").length())
    }

    @Test fun plainHttpIsRefusedOutsideLoopback() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpPatchTransport("http://example.com", "x", "a", "p", "d", "b", "i")
        }
        assertTrue(true)
    }
}
