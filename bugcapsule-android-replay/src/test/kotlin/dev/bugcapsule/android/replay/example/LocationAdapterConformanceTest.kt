package dev.bugcapsule.android.replay.example

import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.replay.NativeAdapterConformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What an app team runs against its own adapter (here: the example location adapter). */
class LocationAdapterConformanceTest {
    /** A scriptable stand-in for the location SDK; reports every use to the helper. */
    private class FakeLocationSdk(private val ctx: NativeAdapterConformance.Ctx) : LocationProvider {
        private val pending = ArrayList<LocationCallback>()
        private val listeners = ArrayList<LocationListener>()

        override fun lastLocation(callback: LocationCallback) {
            ctx.sdkUsed("lastLocation")
            pending.add(callback)
        }

        override fun requestUpdates(intervalMs: Long, listener: LocationListener): Cancellable {
            ctx.sdkUsed("requestUpdates")
            listeners.add(listener)
            return Cancellable { listeners.remove(listener) }
        }

        fun answer(location: Location) = pending.removeAt(0).onLocation(location)
        fun fail(code: String) = pending.removeAt(0).onError(code, "denied by the user")
        fun emit(location: Location) = listeners.toList().forEach { it.onLocation(location) }
    }

    private val conformance = NativeAdapterConformance({ LocationAdapter() })

    private fun observer(ctx: NativeAdapterConformance.Ctx, label: String) = object : LocationCallback {
        override fun onLocation(location: Location?) = ctx.observe(label, location)
        override fun onError(code: String, message: String?) = ctx.observe("$label.error", code)
    }

    private val session = NativeAdapterConformance.Scenario { ctx ->
        val sdk = FakeLocationSdk(ctx)
        val provider = BugCapsuleLocationProvider(sdk, ctx.bridge)

        provider.lastLocation(observer(ctx, "last"))
        ctx.advance(40)
        if (!ctx.replaying) sdk.answer(Location(52.37, 4.89, 12f, 1_000))
        ctx.advance(10)

        val updates = provider.requestUpdates(1000) { ctx.observe("update", it) }
        for (i in 1..3) {
            ctx.advance(1000)
            if (!ctx.replaying) sdk.emit(Location(52.37 + i / 1000.0, 4.89, 8.5f, 1_000L + i * 1000))
        }
        ctx.advance(5)
        updates.cancel()

        // Two identical calls in flight answer in reverse order: matched by invocation order.
        provider.lastLocation(observer(ctx, "second"))
        provider.lastLocation(observer(ctx, "third"))
        ctx.advance(20)
        if (!ctx.replaying) {
            sdk.fail("permission-denied")
            sdk.answer(Location(1.0, 2.0, 3f, 4))
        }
        ctx.advance(100)
    }

    @Test
    fun theExampleAdapterConforms() {
        val report = conformance.assertConforms(session)
        assertEquals(
            listOf("last", "update", "update", "update", "second.error", "third"),
            report.replayed.map { it.first },
        )
        assertEquals(2, report.capsule.specVersion)
        val dep = report.dependency!!
        assertEquals("example.location", dep.adapter)
        assertEquals(DependencyCoverage.COMPLETE, dep.coverage)
        assertEquals(listOf("location.last", "location.updates"), dep.operations)
    }

    @Test
    fun unsupportedOperationsProduceExplicitGaps() {
        val report = conformance.assertUnsupported("location.geofence", mapOf("radiusM" to 100))
        assertEquals(DependencyCoverage.PARTIAL, report.dependency!!.coverage)
    }

    @Test
    fun aWrapperThatBypassesTheBridgeIsCaught() {
        val report = conformance.run { ctx ->
            val sdk = FakeLocationSdk(ctx)
            val provider = BugCapsuleLocationProvider(sdk, ctx.bridge)
            provider.lastLocation(observer(ctx, "last"))
            if (!ctx.replaying) sdk.answer(Location(1.0, 2.0, 3f, 4))
            sdk.requestUpdates(1000) {} // straight to the SDK: real in replay
            ctx.advance(10)
        }
        assertTrue(report.problems.toString(), report.problems.any { it.startsWith("dependency.live") })
        try {
            report.assertConforms()
            fail("expected an AssertionError")
        } catch (_: AssertionError) {
        }
    }

    @Test
    fun aRecordingThatDoesNotMatchTheAppIsCaught() {
        var calls = 0
        val report = conformance.run { ctx ->
            val sdk = FakeLocationSdk(ctx)
            val provider = BugCapsuleLocationProvider(sdk, ctx.bridge)
            // The app asks for a different interval in replay: a native.mismatch.
            provider.requestUpdates(if (ctx.replaying) 5000 else 1000) { ctx.observe("update", it) }
            calls++
            ctx.advance(10)
        }
        assertEquals(2, calls)
        assertTrue(report.problems.toString(), report.divergences.any { it.type == "native.mismatch" })
    }
}
