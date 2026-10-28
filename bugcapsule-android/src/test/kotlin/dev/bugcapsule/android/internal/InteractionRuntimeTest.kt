package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.adapters.NativeAdapter
import dev.bugcapsule.android.adapters.NativeCallback
import dev.bugcapsule.android.adapters.NativeError
import dev.bugcapsule.android.adapters.NativeInteractions
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.PayloadMode
import dev.bugcapsule.android.core.InteractionPayload
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Interaction dependencies through the real runtime: v2 capsules, sanitizing, the remote kill switch. */
@RunWith(RobolectricTestRunner::class)
class InteractionRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var app: Application
    private val transport = FakeTransport()

    private class ProfileAdapter : NativeAdapter {
        override val id = "acme.profile"
        override val version = "1.0.0"
        override val schema = 1
        override val operations = setOf("profile.get")
    }

    private val bridge = NativeInteractions(ProfileAdapter())
    private val results = ArrayList<Any?>()
    private val callback = object : NativeCallback {
        override fun onResult(value: Any?) {
            results.add(value)
        }

        override fun onError(error: NativeError) {
            results.add(error)
        }
    }

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

    private fun start(withBackend: Boolean) {
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root, if (withBackend) transport else null)
        val b = BugCapsuleConfig.builder().mode(CaptureMode.QA).gitSha("abc123")
        if (withBackend) b.backend("http://backend.test", "bci_test_key_000000000000000")
        BugCapsule.init(app, b.build(), listOf(bridge))
    }

    private fun awaitCapture(): File {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            BugCapsule.savedCaptures().firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no capsule was written")
    }

    private fun getProfile(args: Any?, answer: Any?) = bridge.call("profile.get", args, callback) { it.onResult(answer) }

    @Test
    fun nativeInteractionsMakeAValidV2Capsule() {
        start(false)
        getProfile(mapOf("uid" to "u1"), mapOf("name" to "Sam", "password" to "hunter2", "visits" to 3))
        bridge.call("profile.delete", null, callback) { it.onResult(true) } // unsupported: runs, uncaptured
        assertEquals(listOf<Any?>(mapOf("name" to "Sam", "password" to "hunter2", "visits" to 3), true), results)
        BugCapsule.fail("boom")
        val file = awaitCapture()
        assertTrue(String(GZIPInputStream(file.inputStream()).readBytes()).contains("\"specVersion\":2,"))
        val capsule = CapsuleCodec.decode(file.readBytes())
        val dep = capsule.dependency("acme.profile")!!
        assertEquals("acme.profile", dep.adapter)
        assertEquals(DependencyCoverage.PARTIAL, dep.coverage)
        assertEquals(setOf(DependencyGaps.OPERATION_UNSUPPORTED, DependencyGaps.PAYLOAD_REDACTED), dep.gaps.map { it.code }.toSet())
        assertTrue(capsule.manifest.eligibility.missing.any { it.dependency == "dependency.acme.profile" })
        val call = capsule.events.single { it.type == "native.call" }
        assertEquals(bridge.key("profile.get", mapOf("uid" to "u1")), call.data["key"])
        val result = InteractionPayload.fromJson(capsule.events.single { it.type == "native.result" }.data["value"] as Map<String, Any?>)
        assertEquals(PayloadMode.REDACTED, result.mode)
        assertFalse(result.replayable)
        assertEquals("[REDACTED]", (result.value as Map<*, *>)["password"])
    }

    @Test
    fun remoteMaxInteractionPayloadBytesLowersThePerPayloadLimit() {
        start(true)
        transport.configs.add(ConfigFetchResult.Fetched(FakeTransport.config().copy(maxInteractionPayloadBytes = 8)))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        getProfile(mapOf("uid" to "u1"), mapOf("name" to "Sam Example", "visits" to 3))
        BugCapsule.fail("boom")
        val capsule = CapsuleCodec.decode(awaitCapture().readBytes())
        val result = InteractionPayload.fromJson(capsule.events.single { it.type == "native.result" }.data["value"] as Map<String, Any?>)
        assertEquals(PayloadMode.TRUNCATED, result.mode)
        assertTrue(capsule.dependency("acme.profile")!!.gaps.any { it.code == DependencyGaps.PAYLOAD_TRUNCATED })

        val parsed = RemoteCaptureConfig.fromJson(mapOf("maxInteractionPayloadBytes" to 1_000_000L))
        assertEquals(64 * 1024, parsed.maxInteractionPayloadBytes)
        assertEquals(parsed, RemoteCaptureConfig.fromJson(parsed.toJson()))
        assertEquals(null, RemoteCaptureConfig.fromJson(emptyMap()).maxInteractionPayloadBytes)
    }

    @Test
    fun disabledAdaptersStopOneAdapterWithoutTouchingHttp() {
        start(true)
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        val session = BugCapsule.session!!
        getProfile(mapOf("uid" to "u1"), "first")
        session.record(EventTypes.HTTP, mapOf("key" to "GET a/", "status" to 200))
        transport.configs.add(ConfigFetchResult.Fetched(FakeTransport.config().copy(disabledAdapters = listOf("acme.profile"))))
        BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        getProfile(mapOf("uid" to "u2"), "second")
        session.record(EventTypes.HTTP, mapOf("key" to "GET b/", "status" to 200))
        assertEquals(listOf<Any?>("first", "second"), results)
        BugCapsule.fail("boom")
        val capsule = CapsuleCodec.decode(awaitCapture().readBytes())
        assertTrue("adapter events are gone", capsule.events.none { it.type.startsWith("native.") })
        assertEquals(listOf("GET a/", "GET b/"), capsule.eventsOfType("http").map { it.data["key"] })
        val dep = capsule.dependency("acme.profile")!!
        assertEquals(DependencyGaps.EVENTS_DROPPED, dep.gaps.single().code)
    }
}
