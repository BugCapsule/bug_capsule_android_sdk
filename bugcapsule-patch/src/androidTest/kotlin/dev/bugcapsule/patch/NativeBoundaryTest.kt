package dev.bugcapsule.patch

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real JNI array boundaries: rejects before copying oversized untrusted inputs. */
@RunWith(AndroidJUnit4::class)
class NativeBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun b64(text: String) = Base64.decode(text, Base64.DEFAULT)
    private val wire by lazy { JSONObject(fixture("wire.json")) }

    private fun success(reply: ByteArray): ByteArray {
        assertTrue("JNI returned an empty response", reply.isNotEmpty())
        assertEquals("unexpected native error: ${String(reply.drop(1).toByteArray())}", 0, reply[0].toInt())
        return reply.copyOfRange(1, reply.size)
    }

    private fun healthy(handle: Long) {
        val status = JSONObject(String(success(NativeBridge.call(handle, NativeBridge.OP_STATUS, null, null, 0))))
        assertEquals(wire.getJSONObject("target").getString("baseBuildId"), status.getString("baseBuildId"))
        // Exercise parsing, verification and execution after every rejection, not only status.
        val cases = JSONArray(fixture("eval.json"))
        val c = (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.isNull("code") }
        val request = """{"input":${JSONObject.quote(c.getString("input"))},"output":${JSONObject.quote(c.getString("output"))},"value":${c.getString("value")}}"""
        val result = JSONObject(String(success(NativeBridge.call(0, NativeBridge.OP_LOCAL_EVAL, b64(c.getString("module")), request.toByteArray(), 0))))
        assertEquals(JSONObject().put("v", c.opt("expected")).toString(), JSONObject().put("v", result.opt("output")).toString())
    }

    private fun rejects(handle: Long, code: String, op: Int, a: ByteArray?, b: ByteArray? = null) {
        val reply = NativeBridge.call(handle, op, a, b, 0)
        assertTrue("JNI returned an empty response", reply.isNotEmpty())
        assertTrue("oversized/invalid input unexpectedly accepted", reply[0].toInt() != 0)
        assertEquals(code, JSONObject(String(reply.copyOfRange(1, reply.size))).getString("code"))
        healthy(handle)
    }

    private fun withEngine(body: (Long) -> Unit) {
        assertTrue("test APK must contain the current Rust JNI library", NativeBridge.loaded)
        val directory = File(instrumentation.targetContext.cacheDir, "boundary-${System.nanoTime()}")
        val config = JSONObject(wire.getJSONObject("target").toString())
            .put("storeDir", directory.path).put("installationId", "boundary-install")
            .put("trustRoots", JSONArray().put(wire.getString("trustRoot")))
        val handle = ByteBuffer.wrap(success(NativeBridge.open(config.toString().toByteArray())))
            .order(ByteOrder.LITTLE_ENDIAN).long
        try { body(handle) } finally {
            NativeBridge.close(handle)
            directory.deleteRecursively()
        }
    }

    @Test fun oversizedBundleIsTypedAndEngineStillWorks() = withEngine { h ->
        rejects(h, "malformed", NativeBridge.OP_STAGE, ByteArray((10 shl 20) + 1))
        val artifacts = wire.getJSONArray("artifacts")
        val valid = (0 until artifacts.length()).map { artifacts.getJSONObject(it) }.first { it.getString("name") == "valid" }
        success(NativeBridge.call(h, NativeBridge.OP_STAGE, b64(valid.getString("bundle")), null, 0))
        healthy(h)
    }

    @Test fun oversizedSignedDocumentsAreTypedAndEngineStillWorks() = withEngine { h ->
        for (op in listOf(NativeBridge.OP_CHANNEL, NativeBridge.OP_KEYRING)) {
            rejects(h, "malformed", op, ByteArray((16 shl 10) + 1), ByteArray(64))
        }
    }

    @Test fun nonRawSignatureLengthsAreRejectedWithoutPoisoningJni() = withEngine { h ->
        val channels = wire.getJSONArray("channels")
        val valid = (0 until channels.length()).map { channels.getJSONObject(it) }.first { it.getString("name") == "valid" }
        val channel = b64(valid.getString("document"))
        val rootId = wire.getString("trustRoot").removePrefix("bcpk.v1.").substringBeforeLast('.')
        val project = wire.getJSONObject("target").getString("projectId")
        // Canonical keyring reaches signature verification for short arrays, too.
        val ring = """{"issuedAt":0,"keyId":${JSONObject.quote(rootId)},"keys":[],"projectId":${JSONObject.quote(project)},"purpose":"bcpatch.keyring","revoked":[],"schemaVersion":1,"version":1}""".toByteArray()
        for (length in listOf(0, 63, 65)) {
            rejects(h, "bad_signature", NativeBridge.OP_CHANNEL, channel, ByteArray(length))
            rejects(h, "bad_signature", NativeBridge.OP_KEYRING, ring, ByteArray(length))
        }
        success(NativeBridge.call(h, NativeBridge.OP_CHANNEL, channel, b64(valid.getString("signature")), 0))
        healthy(h)
    }

    @Test fun oversizedEvaluationInputsAndIdsAreTypedAndEngineStillWorks() = withEngine { h ->
        rejects(h, "bad_input", NativeBridge.OP_EVAL, "module".toByteArray(), ByteArray((256 shl 10) + 1))
        rejects(h, "bad_input", NativeBridge.OP_EVAL, ByteArray(129) { 'm'.code.toByte() }, "1".toByteArray())
    }
}
