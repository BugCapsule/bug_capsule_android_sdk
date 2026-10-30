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
import java.io.IOException

/** The real runtime on a device, with a fake network. */
@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wire = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("wire.json").bufferedReader().readText())

    private fun named(list: String, name: String): JSONObject {
        val a = wire.getJSONArray(list)
        return (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("name") == name }
    }

    private fun bundle(name: String) = Base64.decode(named("artifacts", name).getString("bundle"), Base64.DEFAULT)
    private fun channel(name: String) = named("channels", name).let {
        SignedChannel(Base64.decode(it.getString("document"), Base64.DEFAULT), Base64.decode(it.getString("signature"), Base64.DEFAULT), name)
    }

    private inner class Fake(var ch: SignedChannel, var artifact: ByteArray) : PatchTransport {
        var downloads = 0
        var offline = false
        override fun fetchChannel(etag: String?): SignedChannel? {
            if (offline) throw IOException("offline")
            return if (etag == ch.etag) null else ch
        }
        override fun fetchArtifact(digest: String): ByteArray {
            downloads++
            return artifact
        }
        override fun postReceipts(batch: JSONObject) {
            if (offline) throw IOException("offline")
        }
    }

    private fun open(dir: String): BugCapsulePatch {
        val t = wire.getJSONObject("target")
        val m = t.getJSONArray("modules").getJSONObject(0)
        return BugCapsulePatch.open(
            PatchConfig(dir, t.getString("projectId"), t.getString("applicationId"), t.getString("environment"), t.getString("distributionProfile"), t.getString("baseBuildId"), "i", listOf(wire.getString("trustRoot")), t.getString("platform")),
            listOf(PatchModule<Int, Int>(m.getString("id"), m.getString("input"), m.getString("output"), { it }, { it as Int }, { it })),
        )
    }

    private fun dir() = File(context.cacheDir, "u${System.nanoTime()}").path

    @Test
    fun downloadsStagesAndKeepsTheRunningGeneration() {
        val d = dir()
        val patch = open(d)
        val r = PatchUpdater(patch, Fake(channel("valid"), bundle("valid"))).checkForUpdate()
        assertEquals(PatchUpdateState.RESTART_REQUIRED, r.state)
        assertEquals(wire.getString("artifactDigest"), r.artifactDigest)
        assertEquals("BASE", patch.status().getString("state"))
        patch.close()
        val next = open(d)
        assertEquals("PROBATION", next.status().getString("state"))
        next.close()
    }

    @Test
    fun failedDownloadIsRetriedAndLeavesTheNextLaunchAlone() {
        val d = dir()
        val patch = open(d)
        val net = Fake(channel("valid"), bundle("payload bit flip"))
        val updater = PatchUpdater(patch, net)
        assertEquals("verify_failed", updater.checkForUpdate().reason)
        assertTrue(patch.status().isNull("pending"))
        net.artifact = bundle("valid")
        assertEquals(PatchUpdateState.RESTART_REQUIRED, updater.checkForUpdate().state)
        assertEquals(2, net.downloads)
        patch.close()
    }

    @Test
    fun forgedMetadataAndOutagesAreResults() {
        val patch = open(dir())
        assertEquals("bad_signature", PatchUpdater(patch, Fake(channel("bad signer"), bundle("valid"))).checkForUpdate().reason)
        val net = Fake(channel("valid"), bundle("valid")).apply { offline = true }
        assertEquals(PatchUpdateState.FAILED, PatchUpdater(patch, net).checkForUpdate().state)
        patch.close()
    }

    @Test
    fun receiptsAreOnlyAcknowledgedOnceUploaded() {
        val patch = open(dir())
        val net = Fake(channel("valid"), bundle("valid"))
        val updater = PatchUpdater(patch, net)
        updater.checkForUpdate()
        net.offline = true
        assertTrue(runCatching { updater.flushReceipts() }.isFailure)
        assertTrue(patch.receipts().getJSONArray("receipts").length() > 0)
        net.offline = false
        assertTrue(updater.flushReceipts() > 0)
        assertEquals(0, JSONArray(patch.receipts().getJSONArray("receipts").toString()).length())
        patch.close()
    }
}
