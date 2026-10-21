package dev.bugcapsule.gradle

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.GZIPInputStream

class MappingUploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String?>,
        val body: ByteArray,
    )

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Recorded>()
    private var responseStatus = 201
    private var responseBody = """{"kind":"proguard"}"""

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    private val debugId = "0a1b2c3d-4e5f-3a6b-8c7d-8e9fa0b1c2d3"
    private val secret = "bcs_test_secret_123"
    private val mappingText = "com.example.Foo -> a.a:\n    void bar() -> a\n".repeat(2000)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val headers = listOf("Authorization", "Content-Type", "Content-Encoding", "Content-Length")
                .associateWith { exchange.requestHeaders.getFirst(it) }
            val body = exchange.requestBody.readBytes()
            synchronized(requests) {
                requests += Recorded(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery, headers, body)
            }
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun mappingFile(): File = tmp.newFile("mapping.txt").apply { writeText(mappingText) }

    @Test
    fun `posts the gzipped mapping with the expected request`() {
        val result = MappingUploader("$baseUrl/", secret).uploadProguardMapping(mappingFile(), debugId)

        assertEquals(201, result.status)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/debug-files", request.path)
        assertEquals("kind=proguard&debugId=$debugId", request.query)
        assertEquals("Bearer $secret", request.headers["Authorization"])
        assertEquals("application/octet-stream", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals(request.body.size.toString(), request.headers["Content-Length"])
        assertTrue("body should be compressed", request.body.size < mappingText.length)
        val unzipped = GZIPInputStream(request.body.inputStream()).use { it.readBytes() }
        assertEquals(mappingText, String(unzipped))
    }

    @Test
    fun `keeps a base path in the url`() {
        MappingUploader("$baseUrl/api", secret).uploadProguardMapping(mappingFile(), debugId)
        assertEquals("/api/v1/debug-files", requests.single().path)
    }

    @Test
    fun `4xx surfaces the problem code and message without the key`() {
        responseStatus = 401
        responseBody = """{"code":"invalid_key","message":"The secret key is not valid for this project."}"""
        try {
            MappingUploader(baseUrl, secret).uploadProguardMapping(mappingFile(), debugId)
            fail("expected UploadException")
        } catch (e: UploadException) {
            assertEquals(401, e.status)
            assertEquals("invalid_key", e.code)
            assertEquals("The secret key is not valid for this project.", e.serverMessage)
            assertEquals(
                "BugCapsule upload failed: HTTP 401 invalid_key: The secret key is not valid for this project.",
                e.message,
            )
            assertFalse(e.message!!.contains(secret))
        }
    }

    @Test
    fun `5xx surfaces too`() {
        responseStatus = 503
        responseBody = """{"message":"Storage \"unavailable\"","code":"storage_unavailable"}"""
        try {
            MappingUploader(baseUrl, secret).uploadProguardMapping(mappingFile(), debugId)
            fail("expected UploadException")
        } catch (e: UploadException) {
            assertEquals(503, e.status)
            assertEquals("storage_unavailable", e.code)
            assertEquals("Storage \"unavailable\"", e.serverMessage)
        }
    }

    @Test
    fun `non-json error body is reported as text`() {
        responseStatus = 502
        responseBody = "Bad Gateway"
        try {
            MappingUploader(baseUrl, secret).uploadProguardMapping(mappingFile(), debugId)
            fail("expected UploadException")
        } catch (e: UploadException) {
            assertEquals(502, e.status)
            assertEquals(null, e.code)
            assertEquals("Bad Gateway", e.serverMessage)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a url without scheme`() {
        MappingUploader("api.bugcapsule.dev", secret).uploadProguardMapping(mappingFile(), debugId)
    }
}
