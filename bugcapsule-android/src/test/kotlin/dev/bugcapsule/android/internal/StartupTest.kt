package dev.bugcapsule.android.internal

import android.app.Application
import android.content.Context
import android.os.StrictMode
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.Permission
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** BC-ENT-036: `BugCapsule.init` does no disk I/O on the calling thread, and loses nothing recorded meanwhile. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28, 35])
class StartupTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private val transport = FakeTransport()
    private val gate = CountDownLatch(1)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BugCapsule.resetForTest()
    }

    @After
    fun tearDown() {
        gate.countDown()
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    private fun config(mode: CaptureMode = CaptureMode.QA) = BugCapsuleConfig.builder()
        .mode(mode)
        .gitSha("abc123")
        .productionSampleRate(1.0)
        .backend("https://backend.test", "bci_test_key_000000000000000")
        .build()

    private val prefs = BugCapsuleSharedPreferences.builder().allowFile("prefs").build()

    /**
     * Every file the calling thread reads, writes or deletes under [roots]
     * while [block] runs. StrictMode's disk detection works on devices (the
     * instrumented `StartupStrictModeTest` covers it); on the JVM, file I/O
     * never reaches it, so a SecurityManager sees the accesses instead.
     */
    private fun fileAccessesOnThisThread(roots: List<File>, block: () -> Unit): List<String> {
        val caller = Thread.currentThread()
        val paths = roots.flatMap { listOf(it.absolutePath, it.canonicalPath) }.distinct()
        val touched = Collections.synchronizedList(ArrayList<String>())
        val violations = Collections.synchronizedList(ArrayList<String>())
        val sm = object : SecurityManager() {
            override fun checkPermission(perm: Permission) {}
            override fun checkPermission(perm: Permission, context: Any?) {}
            override fun checkRead(file: String) = note(file)
            override fun checkWrite(file: String) = note(file)
            override fun checkDelete(file: String) = note(file)
            private fun note(file: String) {
                if (Thread.currentThread() !== caller) return
                val path = File(file).absolutePath
                if (paths.any { path.startsWith(it) }) touched.add(path)
            }
        }
        val policy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                .penaltyListener({ it.run() }) { v -> violations.add(v.toString()) }
                .build(),
        )
        @Suppress("DEPRECATION")
        val previous = System.getSecurityManager()
        try {
            try {
                @Suppress("DEPRECATION")
                System.setSecurityManager(sm)
            } catch (_: UnsupportedOperationException) {
                StrictMode.setThreadPolicy(policy)
                assumeTrue("this JVM cannot install a SecurityManager (needs -Djava.security.manager=allow)", false)
            }
            block()
        } finally {
            @Suppress("DEPRECATION")
            System.setSecurityManager(previous)
            StrictMode.setThreadPolicy(policy)
        }
        assertEquals("StrictMode violations", emptyList<String>(), violations)
        return touched.toList()
    }

    @Test
    fun initReadsAndWritesNoFileOnTheCallingThread() {
        app.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("visits", 3).commit()
        // Everything preparation reads: a cached remote config, a crash journal of a dead process.
        val root = tmp.newFolder("bugcapsule")
        FileConfigCache(File(root, "remote_config.json")).write("x", FakeTransport.config())
        File(root, "${CrashCapsules.DIR}/999999").mkdirs()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(root, transport)
        val roots = listOf(root, app.filesDir.parentFile!!)

        val touched = fileAccessesOnThisThread(roots) {
            BugCapsule.init(app, config(), listOf(prefs))
        }

        assertEquals("files touched by BugCapsule.init on the calling thread", emptyList<String>(), touched)
        awaitPrepared()
        assertTrue(BugCapsule.isRecording)
        assertTrue("preparation did its disk work on the executor", File(root, "captures").isDirectory)
        val capsule = CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
        assertTrue("the launch snapshot was taken off the calling thread", capsule.checkpoint.data.containsKey("shared_preferences"))
    }

    @Test
    fun theProbeSeesWhatPreparationUsedToDoOnTheCallingThread() {
        val root = tmp.newFolder("probe")
        val touched = fileAccessesOnThisThread(listOf(root)) {
            CapsuleStore(root)
            FileConfigCache(File(root, "remote_config.json")).read("x")
        }
        assertTrue(touched.any { it.endsWith("captures") })
        assertTrue(touched.any { it.endsWith("remote_config.json") })
    }

    @Test
    fun initWithDefaultStorageTouchesNoFileOnTheCallingThread() {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(transport = transport)
        val roots = listOf(app.filesDir.parentFile!!)
        val touched = fileAccessesOnThisThread(roots) {
            BugCapsule.init(app, config(), listOf(prefs))
        }
        assertEquals(emptyList<String>(), touched)
        awaitPrepared()
        assertTrue(File(app.noBackupFilesDir, "bugcapsule/captures").isDirectory)
    }

    private fun startHeld(mode: CaptureMode = CaptureMode.QA) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, transport, beforePrepare = Runnable { gate.await(10, TimeUnit.SECONDS) })
        BugCapsule.init(app, config(mode))
    }

    @Test
    fun eventsRecordedWhilePreparingAreKept() {
        startHeld()
        assertFalse("not recording until prepared", BugCapsule.isRecording)
        assertTrue("integrations still capture", BugCapsule.session!!.isRecording)
        BugCapsule.log("early 1")
        BugCapsule.recordAction("cart.add")
        BugCapsule.log("early 2")
        val early = BugCapsule.capture("held")
        Thread.sleep(100)
        assertFalse("no capture before preparation", early.isDone)
        assertTrue("nothing saved before preparation", BugCapsule.savedCaptures().isEmpty())
        assertTrue("no upload before preparation", transport.uploads.isEmpty())

        gate.countDown()
        awaitPrepared()
        BugCapsule.log("late")
        early.get(10, TimeUnit.SECONDS)
        val capsule = CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
        val kept = capsule.events.filter { it.type == EventTypes.LOG || it.type == EventTypes.ACTION }
            .map { it.data["message"] ?: it.data["id"] }
        assertEquals(listOf("early 1", "cart.add", "early 2", "late"), kept)
        assertEquals("events keep their order", capsule.events.map { it.seq }.sorted(), capsule.events.map { it.seq })
        assertTrue(capsule.events.all { it.t >= capsule.checkpoint.t })
    }

    @Test
    fun monitoringCallsMadeWhilePreparingApplyOnceReady() {
        val sent = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, beforePrepare = Runnable { gate.await(10, TimeUnit.SECONDS) })
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).beforeSend { e, _ -> e.also { sent.add(it.payload) } }.build())
        BugCapsule.setTag("tier", "gold")
        BugCapsule.log("before the message")
        assertEquals("no event id before monitoring started", null, BugCapsule.captureMessage("early message"))
        assertTrue(sent.isEmpty())
        gate.countDown()
        awaitPrepared()
        val event = sent.single()
        assertEquals("gold", dev.bugcapsule.android.core.J.obj(event["tags"])!!["tier"])
    }

    @Test
    fun theStartupBufferKeepsTheNewestEvents() {
        startHeld()
        repeat(Runtime.EARLY_EVENTS + 44) { BugCapsule.log("e$it") }
        gate.countDown()
        awaitPrepared()
        val capsule = CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
        val logs = capsule.events.filter { it.type == EventTypes.LOG }.map { it.data["message"] }
        assertEquals(Runtime.EARLY_EVENTS, logs.size)
        assertEquals("e44", logs.first())
        assertEquals("e${Runtime.EARLY_EVENTS + 43}", logs.last())
    }

    @Test
    fun aFailureWhilePreparingIsCapturedOnceReady() {
        startHeld()
        BugCapsule.fail("startup_broken", "during preparation")
        Thread.sleep(100)
        assertTrue(BugCapsule.savedCaptures().isEmpty())
        gate.countDown()
        awaitPrepared()
        val deadline = System.currentTimeMillis() + 10_000
        while (BugCapsule.savedCaptures().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val capsule = CapsuleCodec.decode(BugCapsule.savedCaptures().first().readBytes())
        assertEquals("assertion:startup_broken:", capsule.manifest.failure!!.signature)
    }

    @Test
    fun aSampledOutSessionDropsWhatItKeptWhilePreparing() {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(
            tmp.root, transport,
            random = java.util.Random(1).also { it.nextDouble() }, // any roll: the rate is 0
            beforePrepare = Runnable { gate.await(10, TimeUnit.SECONDS) },
        )
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.PRODUCTION).productionSampleRate(0.0).build())
        assertFalse("known to be sampled out before preparation", BugCapsule.session!!.isRecording)
        BugCapsule.log("early")
        gate.countDown()
        awaitPrepared()
        assertFalse(BugCapsule.isRecording)
        assertEquals(null, BugCapsule.capture().get(10, TimeUnit.SECONDS))
    }

    @Test
    fun aFailedPreparationFailsOpen() {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, transport, beforePrepare = Runnable { throw IllegalStateException("disk gone") })
        BugCapsule.init(app, config())
        BugCapsule.log("early")
        awaitPrepared()
        assertFalse(BugCapsule.isRecording)
        assertFalse(BugCapsule.session!!.isObserving)
        assertEquals(null, BugCapsule.capture().get(10, TimeUnit.SECONDS))
        BugCapsule.log("after") // never throws into the app
    }

    @Test
    fun capsulesListTruncationOverTheRetainedBudget() {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, transport)
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().mode(CaptureMode.QA).gitSha("abc123").maxRetainedBytes(64L * 1024).build(),
        )
        awaitPrepared()
        repeat(10) {
            BugCapsule.session!!.record(
                EventTypes.HTTP,
                linkedMapOf(
                    "key" to "GET shop.test/x", "occurrence" to it, "method" to "GET", "url" to "https://shop.test/x",
                    "status" to 200, "durationMs" to 1, "bodyEncoding" to "utf8", "responseBody" to "b".repeat(20_000),
                ),
            )
        }
        val capsule = CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
        val truncated = capsule.manifest.eligibility.missing.single { it.dependency == "capture.truncated" }
        assertFalse(truncated.blocking)
        assertTrue(capsule.events.any { it.type == EventTypes.HTTP && it.data["responseBody"] == null })
    }
}
