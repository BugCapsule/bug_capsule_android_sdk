package dev.bugcapsule.android.replay

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.core.DependencyKinds
import dev.bugcapsule.android.core.InteractionAdapterInfo
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.RunKind
import dev.bugcapsule.android.core.Verdicts
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.storage.DataStoreFiles
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Production protection, early storage restoration and state restorers. */
@RunWith(RobolectricTestRunner::class)
class ArmingTest {
    private lateinit var app: Application
    private val server = MockWebServer()
    private val results = LinkedBlockingDeque<String>()
    private val statusLines = ArrayList<String>()
    private var capsuleBytes = ByteArray(0)

    private val instrumentation = object : Instrumentation() {
        override fun getTargetContext(): Context = app
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BugCapsule.resetForTest()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/a/t/settings" -> MockResponse().setBody(Json.stringify(ReplaySettings(attemptId = "a1", settleTimeoutMs = 100).toJson()))
                "/a/t/capsule" -> MockResponse().setBody(Buffer().write(capsuleBytes))
                "/a/t/result" -> {
                    results.add(request.body.readUtf8())
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        BugCapsule.resetForTest()
    }

    private val url get() = "http://127.0.0.1:${server.port}/a/t"

    private fun debuggable(on: Boolean) {
        app.applicationInfo.flags = if (on) {
            app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    private fun capsuleWith(data: Map<String, Any?>, kind: CheckpointKind = CheckpointKind.LAUNCH) =
        CapsuleCodec.encode(TestCapsules.capsule(emptyList(), checkpoint = Checkpoint(kind, 0, 1_760_000_000_000, 0, data)))

    @Test
    fun replayIsRefusedForNonDebuggableApps() {
        debuggable(false)
        capsuleBytes = capsuleWith(emptyMap())
        assertFalse(BugCapsule.isReplaying)
        assertFalse(BugCapsuleReplay.arm(instrumentation, url) { statusLines.add(it) })
        assertFalse(BugCapsuleReplay.isArmed())
        val report = results.poll(5, TimeUnit.SECONDS)!!
        assertTrue(report.contains("\"outcome\":\"error\""))
        assertTrue(report.contains("not debuggable"))
        assertTrue(statusLines.single().startsWith("BUGCAPSULE_RESULT::{"))
        // The app starts in capture mode.
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        assertFalse(BugCapsule.isReplaying)
    }

    @Test
    fun storageIsRestoredBeforeTheAppStarts() {
        debuggable(true)
        capsuleBytes = capsuleWith(
            mapOf(
                "shared_preferences" to mapOf(
                    "shop_prefs" to mapOf(
                        "cart_cache" to mapOf("type" to "string", "value" to "[\"mug\"]"),
                        "visits" to mapOf("type" to "int", "value" to 3L),
                        "since" to mapOf("type" to "long", "value" to 1_760_000_000_000L),
                        "ratio" to mapOf("type" to "float", "value" to 0.5),
                        "seen" to mapOf("type" to "bool", "value" to true),
                        "tags" to mapOf("type" to "stringSet", "value" to listOf("a", "b")),
                        "auth_token" to mapOf("type" to "string", "value" to "[REDACTED]"),
                    ),
                ),
                "datastore" to mapOf("settings" to mapOf("currency" to mapOf("type" to "string", "value" to "USD"), "n" to mapOf("type" to "int", "value" to 2L))),
            ),
        )
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        val prefs = app.getSharedPreferences("shop_prefs", Context.MODE_PRIVATE)
        assertEquals("[\"mug\"]", prefs.getString("cart_cache", null))
        assertEquals(3, prefs.getInt("visits", 0))
        assertEquals(1_760_000_000_000L, prefs.getLong("since", 0))
        assertEquals(0.5f, prefs.getFloat("ratio", 0f))
        assertTrue(prefs.getBoolean("seen", false))
        assertEquals(setOf("a", "b"), prefs.getStringSet("tags", null))
        val ds = DataStoreFiles.read(DataStoreFiles.file(app, "settings"))!!
        assertEquals("USD", ds["currency"])
        assertEquals(2, ds["n"])
        assertTrue(BugCapsuleReplay.armed()!!.earlyDivergences.any { it.type == "storage.redacted" && it.detail == "shop_prefs/auth_token" })

        // BugCapsule.init then starts in replay mode with an engine.
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        assertTrue(BugCapsule.isReplaying)
        assertFalse(BugCapsule.isRecording)
        assertNotNull(BugCapsuleReplay.awaitEngine(1000))
    }

    @Test
    fun stateRestorersRunAtRegistrationAndMissingOnesAreReported() {
        debuggable(true)
        capsuleBytes = capsuleWith(mapOf("state" to mapOf("cart" to mapOf("items" to 2L), "other" to 1L)), CheckpointKind.PERIODIC)
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        var restored: Any? = null
        BugCapsule.registerState("cart", { null }) { restored = it }
        assertEquals(mapOf("items" to 2L), restored)
        val engine = BugCapsuleReplay.awaitEngine(1000)!!
        val report = engine.buildReport()
        assertTrue(report.divergences.any { it.type == "restore.missing" && it.detail.contains("other") })
        assertTrue(report.divergences.none { it.detail.contains("cart") })
        assertEquals("a1", report.attemptId)
        assertNull(report.observed)
    }

    @Test
    fun seededRandomAndVirtualClockComeFromTheCapsule() {
        debuggable(true)
        capsuleBytes = capsuleWith(emptyMap())
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        assertEquals(java.util.Random(42).nextInt(), BugCapsule.random().nextInt())
        val now = BugCapsule.currentTimeMillis()
        assertTrue(now in 1_760_000_000_000..1_760_000_010_000)
    }

    private fun wsCapsule() = CapsuleCodec.encode(
        TestCapsules.capsule(emptyList()).let { c ->
            c.copy(
                manifest = c.manifest.copy(
                    dependencies = listOf(
                        CapsuleDependency("ws", DependencyKinds.WEBSOCKET, "bugcapsule-okhttp.websocket", "0.5.0", 1, true, DependencyCoverage.COMPLETE),
                    ),
                ),
            )
        },
    )

    @Test
    fun aRequiredDependencyWithoutAnAdapterIsIncompatible() {
        debuggable(true)
        capsuleBytes = wsCapsule()
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        BugCapsule.init(app, BugCapsuleConfig.builder().build())
        assertNull(BugCapsuleReplay.awaitEngine(1000))
        val report = ReplayReport.fromJson(Json.parse(results.poll(5, TimeUnit.SECONDS)!!) as Map<String, Any?>)
        assertEquals(ReplayOutcome.INCOMPATIBLE, report.outcome)
        assertTrue(report.message!!, report.message!!.startsWith("dependency.unsupported: bugcapsule-okhttp.websocket schema 1"))
        assertEquals(listOf("dependency.unsupported"), Verdicts.evaluate(RunKind.VERIFY, 1, listOf(report)).reasonCodes)
    }

    @Test
    fun anInstalledAdapterPassesPreflight() {
        debuggable(true)
        capsuleBytes = wsCapsule()
        assertTrue(BugCapsuleReplay.arm(instrumentation, url, null))
        val adapter = object : BugCapsuleIntegration {
            override val name = "okhttp.websocket"
            override fun interactionAdapters() = listOf(InteractionAdapterInfo("bugcapsule-okhttp.websocket", 1..1))
        }
        BugCapsule.init(app, BugCapsuleConfig.builder().build(), listOf(adapter))
        val engine = BugCapsuleReplay.awaitEngine(1000)!!
        assertEquals("ws", engine.interactions.dependencyFor("bugcapsule-okhttp.websocket")!!.id)
    }
}
