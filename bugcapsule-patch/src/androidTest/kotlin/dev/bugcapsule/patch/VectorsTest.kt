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

/** The shared vectors, through the real JNI entries on a device. */
@RunWith(AndroidJUnit4::class)
class VectorsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun asset(name: String) = testContext.assets.open(name).bufferedReader().readText()
    private fun b64(s: String) = Base64.decode(s, Base64.DEFAULT)

    /** Status byte, then payload. Null code means success. */
    private fun outcome(r: ByteArray): Pair<String?, ByteArray> {
        val payload = r.copyOfRange(1, r.size)
        return if (r[0].toInt() == 0) null to payload else JSONObject(String(payload)).getString("code") to payload
    }

    @Test
    fun evalVectorsAgreeOnOutputFuelAndMemory() {
        val cases = JSONArray(asset("eval.json"))
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val request = """{"input":${JSONObject.quote(c.getString("input"))},"output":${JSONObject.quote(c.getString("output"))},"value":${c.getString("value")}}"""
            val (code, payload) = outcome(NativeBridge.call(0, NativeBridge.OP_LOCAL_EVAL, b64(c.getString("module")), request.toByteArray(), 0))
            assertEquals(name, c.optString("code").ifEmpty { null }, code)
            if (code == null) {
                val r = JSONObject(String(payload))
                assertEquals(name, c.getLong("steps"), r.getLong("steps"))
                assertEquals(name, c.getLong("memory"), r.getLong("memory"))
                assertEquals(name, JSONObject().put("v", c.opt("expected")).toString(), JSONObject().put("v", r.opt("output")).toString())
            }
        }
        assertTrue(cases.length() >= 40)
    }

    private fun engine(w: JSONObject): Long {
        val t = JSONObject(w.getJSONObject("target").toString())
        t.put("storeDir", File(context.cacheDir, "v${System.nanoTime()}").path).put("installationId", "i").put("trustRoots", JSONArray().put(w.getString("trustRoot")))
        val (code, payload) = outcome(NativeBridge.open(t.toString().toByteArray()))
        assertEquals(null, code)
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).long
    }

    @Test
    fun artifactAndChannelVectorsAgree() {
        val w = JSONObject(asset("wire.json"))
        val artifacts = w.getJSONArray("artifacts")
        for (i in 0 until artifacts.length()) {
            val a = artifacts.getJSONObject(i)
            val h = engine(w)
            val (code, _) = outcome(NativeBridge.call(h, NativeBridge.OP_STAGE, b64(a.getString("bundle")), null, 0))
            assertEquals(a.getString("name"), if (a.isNull("code")) null else a.getString("code"), code)
            NativeBridge.close(h)
        }
        val channels = w.getJSONArray("channels")
        for (i in 0 until channels.length()) {
            val c = channels.getJSONObject(i)
            val h = engine(w)
            val (code, _) = outcome(NativeBridge.call(h, NativeBridge.OP_CHANNEL, b64(c.getString("document")), b64(c.getString("signature")), 0))
            assertEquals(c.getString("name"), if (c.isNull("code")) null else c.getString("code"), code)
            NativeBridge.close(h)
        }
    }
}
