package dev.bugcapsule.android

import android.content.Context
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplaySettings

/** What the runtime exposes to integrations (OkHttp, Navigation, storage...). */
interface BugCapsuleSession {
    val config: BugCapsuleConfig
    val mode: CaptureMode

    /** True while events are recorded (capture enabled and sampled). */
    val isRecording: Boolean
    val isReplaying: Boolean
    val redactor: Redactor
    val captureNetworkBodies: Boolean
    val maxBodyBytes: Int

    /** The application context. */
    val applicationContext: Context

    /** Milliseconds on the session clock (same clock as event `t`). */
    fun now(): Long

    /** Records an event; a no-op when not recording. Safe from any thread. */
    fun record(type: String, data: Map<String, Any?>)

    /** Non-null in replay mode. */
    val replay: ReplayContext?

    /** Reports an error as a failure (deduplicated by signature). */
    fun reportError(error: Throwable)

    /** Runs [task] on the SDK's background executor after [delayMs]. */
    fun schedule(delayMs: Long, task: Runnable)
}

/** Replay services for integrations. */
interface ReplayContext {
    val capsule: Capsule
    val settings: ReplaySettings
    val fixtures: FixtureStore

    /** Records a difference between the recording and this replay. */
    fun diverge(type: String, detail: String)

    /** Counts a network response served from the capsule. */
    fun fixtureServed()

    /**
     * Marks work in progress that idle compression must not skip over (a
     * response waiting out its recorded latency). Call [WorkToken.end] once.
     */
    fun beginWork(): WorkToken

    /** Notes app activity so idle compression waits for it to settle. */
    fun activity()

    /** True once the first recorded input was dispatched (late restoration check). */
    val inputsStarted: Boolean
}

fun interface WorkToken {
    fun end()
}

/**
 * Extension point for libraries the app uses.
 *
 * Capture: [setUp] → [snapshot] (launch, then periodic) → [describeEligibility]
 * when a capsule is written. Replay: [setUp] (with `session.replay` set) →
 * [restore] with this integration's checkpoint data.
 */
interface BugCapsuleIntegration {
    /** Key of this integration's data in checkpoints and `manifest.integrations`. */
    val name: String

    fun setUp(session: BugCapsuleSession) {}

    /** JSON-safe snapshot (maps, lists, strings, numbers, booleans) or null. */
    fun snapshot(kind: CheckpointKind): Any? = null

    fun restore(data: Any?, session: BugCapsuleSession) {}

    fun describeEligibility(eligibility: EligibilityBuilder, window: RecordedWindow) {}

    /**
     * Called right before a capsule is written ([crash]: from the crash
     * handler, on the crashing thread). Integrations record work still in
     * flight here, e.g. OkHttp records unfinished exchanges as `pending`, so
     * that replay holds them instead of failing them as unrecorded.
     */
    fun beforeCapsule(crash: Boolean) {}

    /** Capability keys this integration adds to `manifest.capabilities`. */
    fun capabilities(): Map<String, String> = emptyMap()
}

/** Serializes registered state to JSON-safe values for periodic checkpoints. */
fun interface StateSerializer {
    fun serialize(): Any?
}

/** Restores registered state from a periodic checkpoint during replay. */
fun interface StateRestorer {
    fun restore(value: Any?)
}

/** Handler invoked by semantic replay for an action id; [payload] is the recorded payload or null. */
fun interface ActionHandler {
    fun invoke(payload: Map<String, Any?>?)
}

/** Sets a tracked text field during replay (called on the main thread). */
fun interface TextSetter {
    fun setText(value: String)
}
