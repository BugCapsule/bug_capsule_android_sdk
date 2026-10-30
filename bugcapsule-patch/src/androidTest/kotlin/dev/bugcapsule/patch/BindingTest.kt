package dev.bugcapsule.patch

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The real runtime on a device: a replay pin must change what callers run. */
@RunWith(AndroidJUnit4::class)
class BindingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wire = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("wire.json").bufferedReader().readText())
    private val t = wire.getJSONObject("target")
    private val m = t.getJSONArray("modules").getJSONObject(0)

    // Vector module: x + 1. The fallback is x, so the two are distinguishable.
    private val module = PatchModule<Int, Int>(m.getString("id"), m.getString("input"), m.getString("output"), { it }, { it as Int }, { it })

    private fun artifact(name: String): ByteArray {
        val a = wire.getJSONArray("artifacts")
        return Base64.decode((0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("name") == name }.getString("bundle"), Base64.DEFAULT)
    }

    private fun dir() = File(context.cacheDir, "b${System.nanoTime()}").path

    private fun binding(d: String) = BugCapsulePatchBinding(
        PatchConfig(d, t.getString("projectId"), t.getString("applicationId"), t.getString("environment"), t.getString("distributionProfile"), t.getString("baseBuildId"), "install-1", listOf(wire.getString("trustRoot")), t.getString("platform")),
        listOf(module),
    )

    @Test fun holdersOfTheHandleFollowAReplayPin() {
        val b = binding(dir())
        val held = b.patch
        assertEquals("live store is on base", 1, held.call(module, 1).value)
        assertNotNull(b.pinForReplay(artifact("valid"), null, null)?.patchSetDigest)
        assertSame(held, b.patch)
        assertEquals("the handle now runs the pinned artifact", 2, held.call(module, 1).value)
        assertTrue(held.status().getBoolean("replay"))
        assertThrows(PatchException::class.java) { held.stage(artifact("valid")) }
        held.close()
    }

    @Test fun aBareBasePinIgnoresAPatchActiveInTheLiveStore() {
        val d = dir()
        val live = binding(d)
        live.patch.stage(artifact("valid"))
        live.patch.close()
        val next = binding(d)
        assertEquals(2, next.patch.call(module, 1).value)
        val held = next.patch
        assertNull(next.pinForReplay(null, null, null)?.patchSetDigest)
        assertEquals(1, held.call(module, 1).value)
        held.close()
    }

    @Test fun aRefusedArtifactThrowsLeavesTheHandleAloneAndKeepsNoFiles() {
        val d = dir()
        val b = binding(d)
        val held = b.patch
        assertThrows(IllegalStateException::class.java) { b.pinForReplay(artifact("payload bit flip"), null, null) }
        assertTrue(held.available)
        val leftovers = File(d).parentFile!!.listFiles { f -> f.name.startsWith(File(d).name + "-replay-") }.orEmpty()
        assertEquals("archived files must not stay on disk", 0, leftovers.size)
        held.close()
    }

    @Test fun aSuccessfulPinKeepsNoFiles() {
        val d = dir()
        val b = binding(d)
        b.pinForReplay(artifact("valid"), null, null)
        val leftovers = File(d).parentFile!!.listFiles { f -> f.name.startsWith(File(d).name + "-replay-") }.orEmpty()
        assertEquals(0, leftovers.size)
        b.patch.close()
    }
}
