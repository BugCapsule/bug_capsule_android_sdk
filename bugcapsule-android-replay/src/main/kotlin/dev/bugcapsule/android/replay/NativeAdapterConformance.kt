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
import dev.bugcapsule.android.core.AppInfo
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CapsuleFormatException
import dev.bugcapsule.android.core.CapsuleManifest
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.DependencyGap
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.Eligibility
import dev.bugcapsule.android.core.EligibilityStatus
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.InteractionRecorder
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.PlatformInfo
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.SessionInfo
import dev.bugcapsule.android.core.TypedValue
import java.util.concurrent.Executor

/**
 * A reusable conformance check for a [NativeAdapter], for the app team's own
 * unit tests (plain JVM, no device): it records a scripted session through
 * the adapter, writes and re-reads the capsule (a valid v2 document), replays
 * it offline on a manual clock and asserts that the app observed the same
 * values, that the real SDK was never used in replay and that replay found
 * no divergence. [assertUnsupported] checks that operations the adapter does
 * not support produce an explicit gap and fail in replay.
 *
 * ```kotlin
 * @Test fun locationAdapterConforms() {
 *     NativeAdapterConformance({ LocationAdapter() }).assertConforms { ctx ->
 *         val sdk = FakeLocationSdk(ctx)              // calls ctx.sdkUsed(...) when used
 *         val provider = BugCapsuleLocationProvider(sdk, ctx.bridge)
 *         provider.lastLocation { ctx.observe("last", it) }
 *         if (!ctx.replaying) sdk.answerLast(Location(52.37, 4.89))
 *         ctx.advance(100)
 *     }
 * }
 * ```
 *
 * A scenario runs twice: capturing ([Ctx.replaying] false: drive your fake
 * SDK, it answers) and replaying (true: do not drive the fake SDK; recorded
 * answers arrive while you [Ctx.advance] the clock). Callbacks run on the
 * calling thread in both phases.
 */
class NativeAdapterConformance @JvmOverloads constructor(
    private val adapter: () -> NativeAdapter,
    private val redactor: Redactor = Redactor(),
) {
    /** One scripted session; see the class documentation. */
    fun interface Scenario {
        fun run(ctx: Ctx)
    }

    /** What a scenario uses: the [bridge] to build the adapter's SDK wrapper with, and the clock. */
    class Ctx internal constructor(
        val bridge: NativeInteractions,
        /** True in the replay phase: the real (fake) SDK must not be driven. */
        val replaying: Boolean,
        private val advanceBy: (Long) -> Unit,
        private val observations: MutableList<Pair<String, Any?>>,
        private val contacts: MutableList<String>,
    ) {
        /** Records what the app saw (a typed value or a data class); both phases must observe the same. */
        fun observe(label: String, value: Any?) {
            observations.add(label to value)
        }

        /** Moves the session clock; in replay, delivers recorded answers due by then. 0 flushes what is due now. */
        @JvmOverloads
        fun advance(ms: Long = 0) = advanceBy(ms)

        /** Call from the fake SDK whenever it is used: in replay that is a `dependency.live` failure. */
        fun sdkUsed(detail: String) {
            if (replaying) contacts.add(detail)
        }
    }

    /** Outcome of [run]; [problems] is empty when the adapter conforms. */
    class Report internal constructor(
        val captured: List<Pair<String, Any?>>,
        val replayed: List<Pair<String, Any?>>,
        val divergences: List<Divergence>,
        /** The `manifest.dependencies` entry the capture produced (null when nothing was recorded). */
        val dependency: CapsuleDependency?,
        val capsule: Capsule,
        /** Uses of the SDK during replay. */
        val liveContacts: List<String>,
        val problems: List<String>,
    ) {
        fun gaps(code: String): List<DependencyGap> = dependency?.gaps?.filter { it.code == code } ?: emptyList()

        fun assertConforms() {
            if (problems.isNotEmpty()) throw AssertionError("adapter does not conform:\n- " + problems.joinToString("\n- "))
        }
    }

    /** Runs [scenario] in capture then replay and reports. */
    fun run(scenario: Scenario): Report {
        // ---- capture
        var t = 0L
        var seq = 0L
        val events = ArrayList<CapsuleEvent>()
        val recorder = InteractionRecorder(
            sink = { type, data -> synchronized(events) { events.add(CapsuleEvent(++seq, t, type, data)) } },
            now = { t },
            redactor = { redactor },
            recording = { true },
        )
        val captureBridge = NativeInteractions(adapter())
        captureBridge.setUp(Session(redactor, recorder, null))
        val captured = ArrayList<Pair<String, Any?>>()
        scenario.run(Ctx(captureBridge, false, { ms -> t += ms }, captured, ArrayList()))
        captureBridge.dispose()
        val recorded = synchronized(events) { events.toList() }
        val dependencies = recorder.dependencies(recorded)
        val problems = ArrayList<String>()
        val written = capsule(dependencies, recorded, t)
        val capsule = try {
            CapsuleCodec.decode(CapsuleCodec.encode(written))
        } catch (e: CapsuleFormatException) {
            problems.add("the recorded capsule is invalid: ${e.message}")
            written
        }

        // ---- replay
        var now = 0L
        val divergences = ArrayList<Divergence>()
        val diverge: (String, String) -> Unit = { type, detail -> synchronized(divergences) { divergences.add(Divergence(type, detail)) } }
        val adapterUnderTest = adapter()
        val replayBridge = NativeInteractions(adapterUnderTest)
        val incompatible = InteractionReplay.preflight(capsule, replayBridge.interactionAdapters())
        if (incompatible != null) problems.add("preflight: $incompatible")
        val interactions = InteractionReplay(capsule, { now }, diverge, autoPump = false, deliveryExecutor = Executor { it.run() })
        interactions.runStarted()
        replayBridge.setUp(Session(redactor, null, Replay(capsule, interactions, diverge)))
        val replayed = ArrayList<Pair<String, Any?>>()
        val contacts = ArrayList<String>()
        fun advanceTo(target: Long) {
            while (true) {
                val next = interactions.nextDueAt ?: break
                if (next > target) break
                if (next > now) now = next
                interactions.pump()
            }
            now = maxOf(now, target)
            interactions.pump()
        }
        scenario.run(Ctx(replayBridge, true, { ms -> advanceTo(now + ms) }, replayed, contacts))
        while (true) advanceTo(interactions.nextDueAt ?: break)
        interactions.finish(null)
        replayBridge.dispose()

        val a = captured.map { it.first to normalize(it.second) }
        val b = replayed.map { it.first to normalize(it.second) }
        if (a != b) problems.add("the app observed different values in replay:\n  capture $a\n  replay  $b")
        for (d in divergences) problems.add("divergence ${d.type}: ${d.detail}")
        for (c in contacts) problems.add("dependency.live: the SDK was used during replay ($c)")
        if (recorded.isEmpty()) problems.add("the scenario recorded nothing through the adapter")
        return Report(captured, replayed, divergences.toList(), dependencies.firstOrNull(), capsule, contacts, problems)
    }

    /** Runs [scenario] and throws [AssertionError] unless the adapter conforms. */
    fun assertConforms(scenario: Scenario): Report = run(scenario).also { it.assertConforms() }

    /**
     * Calls [operation], which the adapter must not support, once: capture
     * must run it on the SDK with an `operation.unsupported` gap, replay must
     * fail it with [NativeError.UNRECORDED] (a `native.unrecorded` divergence)
     * without using the SDK.
     */
    @JvmOverloads
    fun assertUnsupported(operation: String, args: Any? = null): Report {
        val report = run { ctx ->
            ctx.bridge.call(operation, args, object : NativeCallback {
                override fun onResult(value: Any?) = ctx.observe("result", value)
                override fun onError(error: NativeError) = ctx.observe("error", error.code)
            }) { cb ->
                ctx.sdkUsed(operation)
                cb.onResult("live")
            }
            ctx.advance()
        }
        val problems = ArrayList<String>()
        if (operation in adapter().operations) problems.add("$operation is listed in the adapter's operations")
        if (report.gaps(DependencyGaps.OPERATION_UNSUPPORTED).none { it.detail?.contains(operation) == true }) {
            problems.add("capture recorded no ${DependencyGaps.OPERATION_UNSUPPORTED} gap for $operation")
        }
        if (report.captured != listOf("result" to "live")) problems.add("capture did not hand the app the SDK's answer: ${report.captured}")
        if (report.replayed != listOf("error" to NativeError.UNRECORDED)) problems.add("replay did not fail $operation as unrecorded: ${report.replayed}")
        if (report.divergences.none { it.type == "native.unrecorded" }) problems.add("replay reported no native.unrecorded divergence")
        if (report.liveContacts.isNotEmpty()) problems.add("the SDK was used during replay")
        if (problems.isNotEmpty()) throw AssertionError("unsupported operation $operation:\n- " + problems.joinToString("\n- "))
        return report
    }

    private fun normalize(value: Any?): String {
        val typed = TypedValue.encode(value)
        return if (TypedValue.isReplayable(typed)) Json.canonical(typed) else value.toString()
    }

    private fun capsule(dependencies: List<CapsuleDependency>, events: List<CapsuleEvent>, durationMs: Long) = Capsule(
        manifest = CapsuleManifest(
            capsuleId = "00000000-0000-4000-8000-00000000c0f0",
            createdAtMs = 1_760_000_000_000,
            mode = CaptureMode.DEBUG,
            app = AppInfo("conformance", "1"),
            sdkName = "bugcapsule-android",
            sdkVersion = "conformance",
            stateSchemaVersion = 1,
            platform = PlatformInfo("android", "", "en", 0.0, 0.0, 1.0),
            session = SessionInfo("conformance", 1_760_000_000_000, 1_760_000_000_000, 1, durationMs),
            eligibility = Eligibility(EligibilityStatus.ELIGIBLE, emptyList()),
            integrations = listOf(adapter().id),
            eventCount = events.size,
            droppedEvents = 0,
            checkpointKind = CheckpointKind.LAUNCH,
            dependencies = dependencies,
        ),
        checkpoint = Checkpoint(CheckpointKind.LAUNCH, 0, 1_760_000_000_000, 0),
        events = events,
    )

    private class Session(
        override val redactor: Redactor,
        override val interactions: InteractionRecorder?,
        override val replay: ReplayContext?,
    ) : BugCapsuleSession {
        override val config: BugCapsuleConfig = BugCapsuleConfig.builder().build()
        override val mode = CaptureMode.DEBUG
        override val isRecording get() = interactions != null
        override val isReplaying get() = replay != null
        override val captureNetworkBodies = true
        override val maxBodyBytes = 256 * 1024
        override val applicationContext: Context get() = throw UnsupportedOperationException("no Android context in the conformance helper")
        override fun now() = 0L
        override fun record(type: String, data: Map<String, Any?>) {}
        override fun reportError(error: Throwable) {
            throw AssertionError("the adapter reported an error", error)
        }
        override fun schedule(delayMs: Long, task: Runnable) {}
    }

    private class Replay(
        override val capsule: Capsule,
        override val interactions: InteractionReplay,
        private val divergeFn: (String, String) -> Unit,
    ) : ReplayContext {
        override val settings = ReplaySettings()
        override val fixtures = FixtureStore(emptyList())
        override fun diverge(type: String, detail: String) = divergeFn(type, detail)
        override fun fixtureServed() {}
        override fun beginWork(): WorkToken = WorkToken {}
        override fun activity() {}
        override val inputsStarted = true
    }
}
