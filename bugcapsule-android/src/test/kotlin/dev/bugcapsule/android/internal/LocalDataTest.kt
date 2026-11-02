package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.ConnectionState
import dev.bugcapsule.android.core.CaptureMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/** BC-ENT-038: clearing local data, the persisted consent switch and the saved-capture TTL. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class LocalDataTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var root: File
    private val transport = FakeTransport().apply {
        uploadResult = UploadResult(UploadStatus.RETRY_LATER, message = "offline") // capsules stay queued
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        root = tmp.newFolder("bugcapsule")
        BugCapsule.resetForTest()
    }

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    private fun start(configure: BugCapsuleConfig.Builder.() -> Unit = {}) {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(root, transport)
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().mode(CaptureMode.QA).gitSha("abc123")
                .backend("https://backend.test", "bci_test_key_000000000000000").apply(configure).build(),
        )
        awaitPrepared()
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
    }

    private fun queued(): List<File> = File(root, "outbox").listFiles { f -> f.name.endsWith(".bugcapsule") }?.toList() ?: emptyList()

    private fun file(path: String, text: String = "x") = File(root, path).also {
        it.parentFile!!.mkdirs()
        it.writeText(text)
    }

    @Test
    fun clearLocalDataDeletesWhatWasCollectedButKeepsTheEmergencyCache() {
        start()
        BugCapsule.capture("to clear").get(10, TimeUnit.SECONDS)!!
        assertEquals(1, BugCapsule.savedCaptures().size)
        assertEquals(1, queued().size)
        val emergency = file("emergency/policy_cache.json", "{}")
        val otherJournal = file("${CrashCapsules.DIR}/99999/events.jsonl")
        val nativeRecord = file("native/1.crash")
        val scope = file("monitoring/scope.json", "{}")
        val installId = file("monitoring/install_id", "0123456789abcdef")
        val exits = file("monitoring/exit_info.json", "{}")
        assertTrue(File(root, "remote_config.json").exists())

        BugCapsule.clearLocalData().get(10, TimeUnit.SECONDS)

        assertEquals(emptyList<File>(), BugCapsule.savedCaptures())
        assertEquals(emptyList<File>(), queued())
        assertFalse(otherJournal.parentFile!!.exists())
        assertFalse(nativeRecord.exists())
        assertFalse(scope.exists())
        assertFalse(installId.exists())
        assertFalse("cached project settings", File(root, "remote_config.json").exists())
        assertTrue("guards keep working offline", emergency.exists())
        assertTrue("already reported exits stay reported", exits.exists())
        // The session goes on recording.
        assertTrue(BugCapsule.isRecording)
        assertTrue(BugCapsule.capture().get(10, TimeUnit.SECONDS) != null)
    }

    @Test
    fun clearLocalDataBeforeInitDoesNothing() {
        assertNull(BugCapsule.clearLocalData().get(1, TimeUnit.SECONDS))
    }

    @Test
    fun turningCollectionOffStopsCaptureAndPurgesQueuedData() {
        start()
        BugCapsule.capture("queued").get(10, TimeUnit.SECONDS)!!
        assertEquals(1, queued().size)
        BugCapsule.setCollectionEnabled(false)
        assertFalse("off right away", BugCapsule.isRecording)
        BugCapsule.log("not recorded")
        BugCapsule.flushUploads().get(10, TimeUnit.SECONDS)
        assertEquals(emptyList<File>(), queued())
        assertEquals(emptyList<File>(), BugCapsule.savedCaptures())
        assertNull(BugCapsule.capture().get(10, TimeUnit.SECONDS))
        assertTrue(transport.uploads.size <= 1) // at most the attempt made before
        assertEquals(ConnectionState.DISABLED, BugCapsule.status().connection.state)
        assertEquals("off", File(root, Runtime.COLLECTION_FILE).readText())

        // Persisted: the next launch does not collect either, whatever the config default.
        val uploadsBefore = transport.uploads.size
        start { collectionEnabled(true) }
        assertFalse(BugCapsule.isRecording)
        assertNull(BugCapsule.capture().get(10, TimeUnit.SECONDS))
        BugCapsule.reportError(IllegalStateException("after consent was withdrawn"))
        BugCapsule.flushUploads().get(10, TimeUnit.SECONDS)
        assertEquals(uploadsBefore, transport.uploads.size)
        assertEquals(emptyList<File>(), queued())
    }

    @Test
    fun theConfigSetsTheDefaultUntilTheAppChooses() {
        start { collectionEnabled(false) }
        assertFalse(BugCapsule.isRecording)
        assertEquals(ConnectionState.DISABLED, BugCapsule.status().connection.state)
        assertFalse("nothing persisted until the app chooses", File(root, Runtime.COLLECTION_FILE).exists())

        BugCapsule.setCollectionEnabled(true)
        BugCapsule.flushUploads().get(10, TimeUnit.SECONDS)
        assertFalse("starts at the next launch", BugCapsule.isRecording)
        assertEquals("on", File(root, Runtime.COLLECTION_FILE).readText())

        start { collectionEnabled(false) }
        assertTrue("the persisted choice wins over the default", BugCapsule.isRecording)
    }

    @Test
    fun aChoiceMadeBeforeInitApplies() {
        BugCapsule.setCollectionEnabled(false)
        BugCapsule.testOverrides = BugCapsuleTestOverrides(root, transport)
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).build())
        assertFalse("nothing is kept for later while off", BugCapsule.session!!.isObserving)
        awaitPrepared()
        assertFalse(BugCapsule.isRecording)
        assertEquals("off", File(root, Runtime.COLLECTION_FILE).readText())
    }

    @Test
    fun clearLocalDataKeepsTheConsentChoice() {
        start()
        BugCapsule.setCollectionEnabled(false)
        BugCapsule.clearLocalData().get(10, TimeUnit.SECONDS)
        assertEquals("off", File(root, Runtime.COLLECTION_FILE).readText())
    }

    @Test
    fun savedCapturesExpireAfterSevenDays() {
        val old = file("captures/old.bugcapsule").apply { setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000) }
        val recent = file("captures/recent.bugcapsule").apply { setLastModified(System.currentTimeMillis() - 6L * 24 * 60 * 60 * 1000) }
        start()
        assertFalse("deleted when the SDK starts", old.exists())
        assertEquals(listOf(recent), BugCapsule.savedCaptures())
        assertEquals(7L * 24 * 60 * 60 * 1000, BugCapsuleConfig.builder().build().savedCaptureMaxAgeMs)
    }

    @Test
    fun theStoreHidesAndPrunesExpiredCaptures() {
        var now = 1_000_000_000L
        val store = CapsuleStore(root, maxSaved = 20, maxAgeMs = 1_000, nowMs = { now })
        val f = store.save("a", byteArrayOf(1)).apply { setLastModified(now) }
        assertEquals(listOf(f), store.savedCaptures())
        now += 1_001
        assertEquals(emptyList<File>(), store.savedCaptures())
        assertTrue(f.exists())
        store.prune()
        assertFalse(f.exists())
    }
}
