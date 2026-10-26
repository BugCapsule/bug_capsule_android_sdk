package dev.bugcapsule.android.replay

import android.content.Context
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.WorkToken
import dev.bugcapsule.android.adapters.NativeAdapter
import dev.bugcapsule.android.adapters.NativeCallback
import dev.bugcapsule.android.adapters.NativeError
import dev.bugcapsule.android.adapters.NativeInteractions
import dev.bugcapsule.android.adapters.NativeSubscriber
import dev.bugcapsule.android.adapters.NativeSubscription
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.interactionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replay never reaches the SDK, keys are scoped by adapter instance, and callbacks get decoded values. */
class NativeInteractionsReplayTest {
    private open class Adapter(
        override val required: Boolean = true,
        override val captureBinary: Boolean = false,
    ) : NativeAdapter {
        override val id = "acme.store"
        override val version = "1.0.0"
        override val schema = 1
        override val operations = setOf("doc.get", "doc.watch")
    }

    private fun get(ctx: NativeAdapterConformance.Ctx, label: String, args: Any?, instance: String? = null, answer: Any? = "ok") {
        ctx.bridge.call("doc.get", args, object : NativeCallback {
            override fun onResult(value: Any?) = ctx.observe(label, value)
            override fun onError(error: NativeError) = ctx.observe("$label.error", error.code)
        }, instance = instance) { cb ->
            ctx.sdkUsed("doc.get")
            cb.onResult(answer)
        }
    }

    private fun watch(ctx: NativeAdapterConformance.Ctx): NativeSubscription =
        ctx.bridge.subscribe("doc.watch", null, object : NativeSubscriber {
            override fun onEvent(value: Any?) = ctx.observe("event", value)
            override fun onError(error: NativeError) = ctx.observe("watch.error", error.code)
        }) { s ->
            ctx.sdkUsed("doc.watch")
            s.onEvent("snapshot")
            NativeSubscription {}
        }

    @Test
    fun diagnosticAdaptersFailInReplayInsteadOfReachingTheSdk() {
        val report = NativeAdapterConformance({ Adapter(required = false) }).run { ctx ->
            get(ctx, "get", mapOf("path" to "a"))
            watch(ctx)
            ctx.advance()
        }
        assertEquals(listOf("get" to "ok", "event" to "snapshot"), report.captured)
        assertEquals(listOf("get.error" to NativeError.UNRECORDED, "watch.error" to NativeError.UNRECORDED), report.replayed)
        assertEquals(listOf("native.unrecorded", "native.unrecorded"), report.divergences.map { it.type })
        assertTrue(report.liveContacts.isEmpty())
        assertEquals(false, report.dependency!!.required)
    }

    @Test
    fun anAdapterWithoutARecordingFailsEveryOperation() {
        val report = NativeAdapterConformance({ Adapter() }).run { ctx ->
            if (ctx.replaying) {
                get(ctx, "get", null)
                watch(ctx)
            }
            ctx.advance()
        }
        assertTrue(report.capsule.manifest.dependencies.isEmpty())
        assertEquals(listOf("get.error" to NativeError.UNRECORDED, "watch.error" to NativeError.UNRECORDED), report.replayed)
        assertTrue(report.divergences.all { it.type == "native.unrecorded" && it.detail.contains("no recording for this adapter") })
        assertTrue(report.liveContacts.isEmpty())
    }

    @Test
    fun replayWithoutInteractionReplayStillNeverCallsTheSdk() {
        val divergences = ArrayList<String>()
        val capsule = NativeAdapterConformance({ Adapter() }).run { ctx -> get(ctx, "get", null); ctx.advance() }.capsule
        val replay = object : ReplayContext {
            override val capsule = capsule
            override val settings = ReplaySettings()
            override val fixtures = FixtureStore(emptyList())
            override fun diverge(type: String, detail: String) {
                divergences.add(type)
            }
            override fun fixtureServed() {}
            override fun beginWork() = WorkToken {}
            override fun activity() {}
            override val inputsStarted = true
            // interactions: default null
        }
        val session = object : BugCapsuleSession {
            override val config = BugCapsuleConfig.builder().build()
            override val mode = CaptureMode.DEBUG
            override val isRecording = false
            override val isReplaying = true
            override val redactor = Redactor()
            override val captureNetworkBodies = true
            override val maxBodyBytes = 1024
            override val applicationContext: Context get() = throw UnsupportedOperationException()
            override fun now() = 0L
            override fun record(type: String, data: Map<String, Any?>) {}
            override val replay: ReplayContext = replay
            override fun reportError(error: Throwable) {}
            override fun schedule(delayMs: Long, task: Runnable) {}
        }
        val bridge = NativeInteractions(object : Adapter() {
            override val callbackExecutor = java.util.concurrent.Executor { it.run() }
        })
        bridge.setUp(session)
        val seen = ArrayList<Any?>()
        bridge.call("doc.get", null, object : NativeCallback {
            override fun onResult(value: Any?) {
                seen.add(value)
            }
            override fun onError(error: NativeError) {
                seen.add(error.code)
            }
        }) { throw AssertionError("the SDK was called in replay") }
        assertTrue(bridge.isReplaying)
        assertEquals(listOf<Any?>(NativeError.UNRECORDED), seen)
        assertEquals(listOf("native.unrecorded"), divergences)
    }

    @Test
    fun keysAreScopedByAdapterInstance() {
        val bridge = NativeInteractions(Adapter())
        val args = mapOf("path" to "carts/1")
        assertEquals(bridge.key("doc.get", args).replaceFirst("doc.get|", "doc.get@[DEFAULT]|"), bridge.key("doc.get", args, "[DEFAULT]"))
        assertEquals(interactionKey("doc.get@[DEFAULT]", null, args), bridge.key("doc.get", args, "[DEFAULT]"))

        val report = NativeAdapterConformance({ Adapter() }).run { ctx ->
            get(ctx, "default", args, instance = "[DEFAULT]")
            get(ctx, "secondary", args, instance = "secondary")
            get(ctx, "argsDiffer", mapOf("path" to if (ctx.replaying) "carts/2" else "carts/3"), instance = "secondary")
            get(ctx, "otherInstance", args, instance = if (ctx.replaying) "third" else "[DEFAULT]")
            ctx.advance()
        }
        val calls = report.capsule.eventsOfType("native.call")
        assertEquals(listOf("[DEFAULT]", "secondary", "secondary", "[DEFAULT]"), calls.map { it.data["instance"] })
        assertTrue(calls.all { (it.data["key"] as String).startsWith("doc.get@${it.data["instance"]}|") })
        // Unrecorded failures are immediate; recorded results arrive at their recorded latency.
        assertEquals(
            setOf("default" to "ok", "secondary" to "ok", "argsDiffer.error" to NativeError.UNRECORDED, "otherInstance.error" to NativeError.UNRECORDED),
            report.replayed.toSet(),
        )
        // Same instance, other arguments: a mismatch. Another instance: unrecorded, even though the default instance has an unused call.
        assertEquals(listOf("native.mismatch", "native.unrecorded"), report.divergences.map { it.type }.filter { it != "native.unused" })
        assertTrue(report.divergences.first { it.type == "native.mismatch" }.detail.startsWith("doc.get@secondary:"))
    }

    @Test
    fun callbacksReceiveDecodedValues() {
        val value = linkedMapOf<String, Any?>(
            "big" to 9007199254740993L,
            "whole" to 2.0,
            "bytes" to byteArrayOf(1, 2, 3),
            "ids" to longArrayOf(1, Long.MAX_VALUE),
            "byNumber" to mapOf(1 to "one"),
        )
        val report = NativeAdapterConformance({ Adapter(captureBinary = true) }).assertConforms { ctx ->
            get(ctx, "get", null, answer = value)
            ctx.advance()
        }
        val replayed = report.replayed.single().second as Map<*, *>
        assertEquals(9007199254740993L, replayed["big"])
        assertEquals(2.0, replayed["whole"])
        assertTrue((replayed["bytes"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue((replayed["ids"] as LongArray).contentEquals(longArrayOf(1, Long.MAX_VALUE)))
        assertEquals(mapOf(1L to "one"), replayed["byNumber"])
        // The capsule itself holds typed JSON, not these objects.
        val stored = CapsuleCodec.toJson(report.capsule).toString()
        assertTrue(stored, stored.contains("\$t=bytes"))
    }
}
