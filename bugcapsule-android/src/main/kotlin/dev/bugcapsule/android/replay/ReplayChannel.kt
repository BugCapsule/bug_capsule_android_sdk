package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.DartUri
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplayProtocol
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Where a finished replay report goes. */
fun interface ReplayReporter {
    fun report(report: ReplayReport)
}

/**
 * Talks to the CLI's loopback replay server (protocol v1.1): `GET
 * <base>/settings`, `GET <base>/capsule`, `POST <base>/result`.
 *
 * Uses a minimal HTTP/1.1 client over a plain socket on purpose: the replay
 * server is on the device's loopback (via `adb reverse`), and the app's
 * network security config (cleartext policy) and its OkHttp client must not
 * be involved. Only `http://` loopback/LAN base URLs are accepted.
 */
class ReplayChannel(val baseUrl: String) {
    private val uri: DartUri = DartUri.parse(baseUrl.trimEnd('/'))

    init {
        require(uri.scheme == "http" && uri.host.isNotEmpty()) { "replay URL must be http://host:port/a/<token>" }
    }

    class Response(val status: Int, val body: ByteArray)

    fun fetchSettings(): ReplaySettings {
        val res = request("GET", ReplayProtocol.SETTINGS_PATH, null)
        if (res.status != 200) throw IOException("settings request failed: HTTP ${res.status}")
        return ReplaySettings.fromJson(J.obj(Json.parseUtf8(res.body)) ?: throw IOException("settings are not an object"))
    }

    fun fetchCapsuleBytes(): ByteArray {
        val res = request("GET", ReplayProtocol.CAPSULE_PATH, null)
        if (res.status != 200) throw IOException("capsule download failed: HTTP ${res.status}")
        return res.body
    }

    /** A file of this attempt's patch archive. Throws when it can't be loaded. */
    fun fetchPatchFile(path: String): ByteArray {
        val res = request("GET", path, null)
        if (res.status != 200) throw IOException("$path download failed: HTTP ${res.status}")
        return res.body
    }

    fun fetch(): Pair<Capsule, ReplaySettings> {
        val settings = fetchSettings()
        return CapsuleCodec.decode(fetchCapsuleBytes()) to settings
    }

    /** Posts [report]; returns the HTTP status (204 accepted, 409/410 stale attempt) or null on I/O failure. */
    fun postResult(report: ReplayReport): Int? = try {
        request("POST", ReplayProtocol.RESULT_PATH, Json.stringify(report.toJson()).toByteArray(Charsets.UTF_8)).status
    } catch (_: IOException) {
        null
    }

    fun request(method: String, path: String, body: ByteArray?): Response {
        val port = uri.port ?: 80
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host.removePrefix("[").removeSuffix("]"), port), 5_000)
            socket.soTimeout = 30_000
            val target = uri.path + path
            val head = StringBuilder()
                .append("$method $target HTTP/1.1\r\n")
                .append("Host: ${uri.host}:$port\r\n")
                .append("Connection: close\r\n")
                .append("Accept: */*\r\n")
            if (body != null) {
                head.append("Content-Type: application/json\r\n")
                head.append("Content-Length: ${body.size}\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(socket.getInputStream())
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) {
                if (sb.isEmpty()) throw IOException("connection closed")
                break
            }
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 64 * 1024) throw IOException("header line too long")
        }
        return sb.toString()
    }

    private fun readResponse(input0: InputStream): Response {
        val input = input0.buffered()
        val statusLine = readLine(input)
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val out = ByteArrayOutputStream()
        when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> {
                while (true) {
                    val size = readLine(input).substringBefore(';').trim().toInt(16)
                    if (size == 0) break
                    copy(input, out, size.toLong())
                    readLine(input)
                }
            }
            headers["content-length"] != null -> copy(input, out, headers["content-length"]!!.toLong())
            else -> copy(input, out, Long.MAX_VALUE)
        }
        return Response(status, out.toByteArray())
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, max: Long) {
        val buf = ByteArray(16 * 1024)
        var remaining = max
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) {
                if (max != Long.MAX_VALUE) throw IOException("unexpected end of body")
                break
            }
            out.write(buf, 0, n)
            remaining -= n
            if (out.size() > 256 * 1024 * 1024) throw IOException("response too large")
        }
    }
}
