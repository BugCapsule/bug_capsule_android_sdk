package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.AppInfo
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CapsuleManifest
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Eligibility
import dev.bugcapsule.android.core.EligibilityStatus
import dev.bugcapsule.android.core.FailureKind
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.FailureSignature
import dev.bugcapsule.android.core.PlatformInfo
import dev.bugcapsule.android.core.SessionInfo

object TestCapsules {
    fun failure(t: Long = 1000, id: String = "boom") =
        FailureRecord(FailureKind.ASSERTION, FailureSignature.compute(FailureKind.ASSERTION, id, ""), id, "", t, assertionId = id)

    fun capsule(
        events: List<CapsuleEvent>,
        failure: FailureRecord? = failure(),
        checkpoint: Checkpoint = Checkpoint(CheckpointKind.LAUNCH, -100, 1_760_000_000_000, 0),
        gitSha: String? = null,
        width: Double = 400.0,
        height: Double = 800.0,
    ) = Capsule(
        manifest = CapsuleManifest(
            capsuleId = "c0ffee00-0000-4000-8000-000000000001",
            createdAtMs = 1_760_000_000_000,
            mode = CaptureMode.QA,
            app = AppInfo("1.0", "1"),
            sdkName = "bugcapsule-android",
            sdkVersion = "0.3.0",
            stateSchemaVersion = 1,
            platform = PlatformInfo("android", "15", "en", width, height, 2.0),
            session = SessionInfo("s", 1_760_000_000_000, 1_760_000_000_000, 42, 5000),
            eligibility = Eligibility(EligibilityStatus.ELIGIBLE, emptyList()),
            integrations = emptyList(),
            eventCount = events.size,
            droppedEvents = 0,
            checkpointKind = checkpoint.kind,
            gitSha = gitSha,
            failure = failure,
        ),
        checkpoint = checkpoint,
        events = events,
    )
}

/** Records what the engine injects; actions run inline. */
class FakeDriver(var screen: ScreenInfo? = ScreenInfo(400.0, 800.0, 2.0)) : ReplayDriver {
    val pointers = ArrayList<String>()
    val lifecycle = ArrayList<String>()
    val keys = ArrayList<String>()
    var onPointer: ((String) -> Unit)? = null

    override fun screen() = screen
    override fun dispatchPointer(phase: String, pointer: Int, xPx: Float, yPx: Float, kind: String, buttons: Int) {
        pointers.add("$phase:$pointer:${xPx.toInt()},${yPx.toInt()}")
        onPointer?.invoke(phase)
    }
    override fun runOnMainSync(block: Runnable) = block.run()
    override fun moveLifecycle(state: String): Boolean {
        lifecycle.add(state)
        return state != "detached"
    }
    override fun dispatchKey(phase: String, keyCode: Int): Boolean {
        keys.add("$phase:$keyCode")
        return true
    }
}
