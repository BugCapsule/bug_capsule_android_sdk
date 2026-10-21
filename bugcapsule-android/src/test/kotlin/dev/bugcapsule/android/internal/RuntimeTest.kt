package dev.bugcapsule.android.internal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.storage.BugCapsuleSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class FakeTransport : Transport {
    val configs = LinkedBlockingDeque<ConfigFetchResult>()
    var lastConfig: ConfigFetchResult = ConfigFetchResult.Fetched(config())
    val uploads = LinkedBlockingDeque<ByteArray>()
    var uploadResult = UploadResult(UploadStatus.UPLOADED, serverId = "srv")

    override fun fetchConfig(): ConfigFetchResult {
        configs.poll()?.let { lastConfig = it }
        return lastConfig
    }

    override fun upload(bytes: ByteArray): UploadResult {
        uploads.add(bytes)
        return uploadResult
    }

    companion object {
        fun config(enabled: Boolean = true, bodies: Boolean = true, keys: List<String> = emptyList()) =
            RemoteCaptureConfig(enabled, 1.0, 5_000_000, bodies, keys, 300, System.currentTimeMillis())
    }
}

@RunWith(RobolectricTestRunner::class)
class RuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private val transport = FakeTransport()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BugCapsule.resetForTest()
    }

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    private fun start(withBackend: Boolean, vararg integrations: dev.bugcapsule.android.BugCapsuleIntegration) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, if (withBackend) transport else null)
        val b = BugCapsuleConfig.builder().mode(CaptureMode.QA).redactKeys("loyaltyId").gitSha("abc123")
        if (withBackend) b.backend("http://backend.test", "bci_test_key_000000000000000")
        BugCapsule.init(app, b.build(), integrations.toList())
    }

    private fun awaitCapture(): File {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            BugCapsule.savedCaptures().firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no capsule was written")
    }

    private fun rawJson(file: File) = String(GZIPInputStream(file.inputStream()).readBytes())

    @Test
    fun secretsAreRedactedBeforeAnythingIsPersisted() {
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("api_token", "tok-secret-1")
            .putString("profile", "{\"password\":\"pw-secret-2\",\"name\":\"Sam\"}")
            .putInt("visits", 3)
            .commit()
        start(false, BugCapsuleSharedPreferences.builder().allowFile("prefs").build())
        val session = BugCapsule.session!!
        assertTrue(session.isRecording)
        session.record(
            EventTypes.HTTP,
            mapOf(
                "key" to "GET api.test/x", "occurrence" to 0, "method" to "GET",
                "url" to "https://api.test/x?code=oauth-secret-3",
                "requestHeaders" to mapOf("authorization" to "Bearer hdr-secret-4abcdef"),
                "responseBody" to mapOf("token" to "body-secret-5", "loyaltyId" to "L-secret-6", "ok" to 1),
                "status" to 200, "durationMs" to 5,
            ),
        )
        BugCapsule.log("retry with Bearer log-secret-7abcdef")
        BugCapsule.trackText("login.password", {}, false).onChanged("pw-secret-8")
        BugCapsule.trackText("notes", {}, false).onChanged("call me, token note-secret-9 ok")
        BugCapsule.trackText("card.holder", {}, true).onChanged("Jane-secret-10")
        BugCapsule.fail("checkout_stuck", "password=fail-secret-11")
        val text = rawJson(awaitCapture())
        for (i in 1..11) assertFalse("secret $i leaked: $text", Regex("secret-$i(?![0-9])").containsMatchIn(text))
        assertTrue(text.contains("[REDACTED]"))
        val capsule = CapsuleCodec.decode(awaitCapture().readBytes())
        assertEquals("assertion:checkout_stuck:", capsule.manifest.failure!!.signature)
        assertEquals("android", capsule.manifest.runtimeName)
        assertEquals("abc123", capsule.manifest.gitSha)
        assertEquals(listOf("loyaltyId"), capsule.manifest.redactionKeys)
        assertTrue(capsule.checkpoint.data.containsKey("shared_preferences"))
        val prefs = (capsule.checkpoint.data["shared_preferences"] as Map<*, *>)["prefs"] as Map<*, *>
        assertEquals(mapOf("type" to "int", "value" to 3L), prefs["visits"])
        assertTrue(capsule.manifest.eligibility.missing.any { it.dependency == "storage.prefs/api_token" })
    }

    @Test
    fun capsulesUploadAfterASuccessfulConfigFetch() {
        start(true)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        BugCapsule.fail("x")
        awaitCapture()
        val bytes = transport.uploads.poll(10, TimeUnit.SECONDS)
        assertTrue(bytes != null && CapsuleCodec.decode(bytes).manifest.failure != null)
        assertTrue(BugCapsule.session.let { (it as Runtime).outbox!!.entries().isEmpty() })
    }

    @Test
    fun killSwitchStopsRecordingAndPurgesQueuedAndSavedCaptures() {
        transport.uploadResult = UploadResult(UploadStatus.RETRY_LATER, message = "offline")
        start(true)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        BugCapsule.fail("x")
        awaitCapture()
        val rt = BugCapsule.session as Runtime
        val deadline = System.currentTimeMillis() + 5000
        while (rt.outbox!!.entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, rt.outbox!!.entries().size)
        transport.configs.add(ConfigFetchResult.Fetched(FakeTransport.config(enabled = false)))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        assertFalse(BugCapsule.isRecording)
        assertTrue(rt.outbox!!.entries().isEmpty())
        assertTrue(BugCapsule.savedCaptures().isEmpty())
    }

    @Test
    fun rejectedKeyStopsCaptureButKeepsTheQueue() {
        transport.uploadResult = UploadResult(UploadStatus.RETRY_LATER, message = "offline")
        start(true)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        BugCapsule.fail("x")
        awaitCapture()
        val rt = BugCapsule.session as Runtime
        val deadline = System.currentTimeMillis() + 5000
        while (rt.outbox!!.entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        transport.configs.add(ConfigFetchResult.Failed("HTTP 401", 401))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        assertFalse(BugCapsule.isRecording)
        assertEquals(1, rt.outbox!!.entries().size)
    }

    @Test
    fun remoteBodiesOffAndNewRedactKeysRewriteTheBuffer() {
        start(true)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        val session = BugCapsule.session!!
        session.record(EventTypes.HTTP, mapOf("key" to "GET a/", "responseBody" to mapOf("x" to 1), "bodyEncoding" to "json"))
        BugCapsule.log("member M-1234")
        transport.configs.add(ConfigFetchResult.Fetched(FakeTransport.config(bodies = false, keys = listOf("member"))))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        assertFalse(session.captureNetworkBodies)
        assertTrue(session.redactor.isSensitiveKey("member"))
        transport.uploadResult = UploadResult(UploadStatus.UPLOADED)
        BugCapsule.fail("y")
        val capsule = CapsuleCodec.decode(awaitCapture().readBytes())
        assertTrue("http events dropped on re-redaction", capsule.events.none { it.type == "http" })
        assertTrue(capsule.manifest.eligibility.missing.any { it.dependency == "events.redaction" })
    }
}
