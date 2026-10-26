package dev.bugcapsule.android.embedding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.monitoring.ExitInfoSource
import dev.bugcapsule.android.monitoring.ExitReasonsTest
import dev.bugcapsule.android.monitoring.ExitRecord
import dev.bugcapsule.android.monitoring.PreviousProcess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashBridgeTest {
    private lateinit var app: Application
    private lateinit var dir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        dir = File(app.noBackupFilesDir, "bugcapsule-crash-bridge")
        dir.deleteRecursively()
        CrashBridge.resetForTest()
    }

    @After
    fun tearDown() {
        CrashBridge.resetForTest()
        PreviousProcess.exitInfoOverride = null
    }

    private fun events(): List<Map<String, Any?>> = CrashBridge.takePendingEvents().map { J.obj(Json.parse(it))!! }

    @Test
    fun aNativeCrashOfThePreviousRunComesBackOnceWithItsScope() {
        File(dir, "native").mkdirs()
        File(dir, "native/context.json").writeText("{\"release\":\"com.shop@1.0.0+1\",\"environment\":\"production\"}")
        File(dir, "native/321-1761000000000.crash").writeText(
            "bugcapsule-native-crash 1\nsignal 6\ncode -6\naddress 0x0\ntid 9\nthread main\ntimestamp 1761000000500\nabi arm64-v8a\n" +
                "scope {\"pid\":321,\"release\":\"com.shop@1.0.0+1\",\"environment\":\"production\",\"user\":{\"id\":\"u_1\"},\"tags\":{\"route\":\"/cart\"}," +
                "\"breadcrumbs\":[{\"timestamp\":\"2025-10-20T22:39:59.000Z\",\"type\":\"navigation\",\"message\":\"/cart\",\"level\":\"info\",\"data\":{}}]}\n" +
                "frame 0x7a00001000 0x7a00000000 0x7a00000f00 /data/app/x/lib/arm64/libapp.so dart_crash\nend\n",
        )
        assertTrue(CrashBridge.install(app, "com.shop@2.0.0+2", "production", CrashBridge.Options(nativeCrashes = false, anr = false, exitInfo = false)))
        assertTrue(CrashBridge.lastRunCrashed)
        val event = events().single()
        assertEquals("fatal", event["level"])
        assertEquals("android", event["runtime"])
        assertEquals("com.shop@1.0.0+1", event["release"])
        assertEquals("u_1", J.obj(event["user"])!!["id"])
        assertEquals("/cart", J.obj(event["tags"])!!["route"])
        assertEquals(1, J.list(event["breadcrumbs"])!!.size)
        val value = J.obj(J.list(J.obj(event["exception"])!!["values"])!!.single())!!
        assertEquals("SIGABRT", value["type"])
        assertTrue("taken once", CrashBridge.takePendingEvents().isEmpty())
        assertFalse(File(dir, "native/321-1761000000000.crash").exists())
    }

    @Test
    fun anAnrKillOfThePreviousRunUsesTheScopeItPersisted() {
        dir.mkdirs()
        File(dir, "exit_info.json").writeText("{\"lastTimestamp\":1}")
        File(dir, "scope.json").writeText("{\"pid\":55,\"release\":\"com.shop@1.0.0+1\",\"environment\":\"qa\",\"user\":{\"id\":\"u_anr\"}}")
        val trace = ExitReasonsTest.ANR_TRACE.toByteArray()
        PreviousProcess.exitInfoOverride = ExitInfoSource {
            listOf(ExitRecord(55, ExitRecord.REASON_ANR, 1761000000000, 100, "Input dispatching timed out", app.packageName) { trace.inputStream() })
        }
        assertTrue(CrashBridge.install(app, "com.shop@2.0.0+2", "qa", CrashBridge.Options(nativeCrashes = false, anr = false)))
        assertFalse("an ANR kill is abnormal, not a crash", CrashBridge.lastRunCrashed)
        val event = events().single()
        assertEquals("com.shop@1.0.0+1", event["release"])
        assertEquals("qa", event["environment"])
        assertEquals("u_anr", J.obj(event["user"])!!["id"])
        assertEquals("ApplicationNotResponding", J.obj(J.list(J.obj(event["exception"])!!["values"])!!.single())!!["type"])
        assertEquals(4, J.list(event["threads"])!!.size)
    }

    @Test
    fun theScopeIsPersistedWithThisProcessAndRelease() {
        assertTrue(CrashBridge.install(app, "com.shop@2.0.0+2", "production", CrashBridge.Options(nativeCrashes = false, anr = false, exitInfo = false)))
        assertFalse(CrashBridge.lastRunCrashed)
        CrashBridge.updateScope("{\"user\":{\"id\":\"u_9\"},\"tags\":{\"screen\":\"home\"}}")
        CrashBridge.updateScope("not json") // ignored
        val file = File(dir, "scope.json")
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val saved = J.obj(Json.parse(file.readText()))!!
        assertEquals(android.os.Process.myPid(), (saved["pid"] as Number).toInt())
        assertEquals("com.shop@2.0.0+2", saved["release"])
        assertEquals("u_9", J.obj(saved["user"])!!["id"])
        assertTrue(CrashBridge.takePendingEvents().isEmpty())
    }

    @Test
    fun aTakeThatTimesOutNeverLosesEvents() {
        assertTrue(CrashBridge.install(app, "a@1+1", "production", CrashBridge.Options(nativeCrashes = false, anr = false, exitInfo = false)))
        val events = File(dir, "events").apply { mkdirs() }
        repeat(20) { i ->
            File(events, "${1000 + i}-e$i.json").writeText("{\"eventId\":\"e$i\"}")
            val got = CrashBridge.takePendingEvents(timeoutMs = 0)
            // Either handed out, or still on disk for the next take: never both gone.
            val onDisk = File(events, "${1000 + i}-e$i.json").exists()
            assertTrue("e$i lost", got.any { it.contains("e$i") } || onDisk)
            if (onDisk) assertTrue(CrashBridge.takePendingEvents().any { it.contains("e$i") })
        }
    }

    @Test
    fun installIsIdempotent() {
        assertTrue(CrashBridge.install(app, "a@1+1", "production", CrashBridge.Options(nativeCrashes = false, anr = false, exitInfo = false)))
        assertTrue(CrashBridge.install(app, "b@2+2", "qa"))
        assertFalse(CrashBridge.nativeCrashHandlerInstalled)
    }
}
