package dev.bugcapsule.android.replay

import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.WorkToken
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.IdleCompressor
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.internal.ActionRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Logical size of the replay window (dp) and its density. */
data class ScreenInfo(val widthDp: Double, val heightDp: Double, val density: Double)

/**
 * Injects replayed input into the app. Implemented by the instrumentation
 * runner (`bugcapsule-android-replay`): pointers through
 * `Instrumentation.sendPointerSync`, lifecycle by moving the `ActivityScenario`.
 * Called from the engine thread (never the main thread).
 */
interface ReplayDriver {
    fun screen(): ScreenInfo?

    /** [phase] is `down`, `move`, `up` or `cancel`; coordinates are screen pixels. */
    fun dispatchPointer(phase: String, pointer: Int, xPx: Float, yPx: Float, kind: String, buttons: Int)

    fun runOnMainSync(block: Runnable)

    /** Drives the app to a recorded process lifecycle state; false when it cannot. */
    fun moveLifecycle(state: String): Boolean

    /** Injects a recorded system key (`key` event); false when unsupported. */
    fun dispatchKey(phase: String, keyCode: Int): Boolean = false
}

/**
 * Drives a replay (port of `replay_engine.dart`): dispatches recorded input at
 * recorded times relative to the checkpoint, compresses idle stretches,
 * watches for the original failure and builds the [ReplayReport].
 */
class ReplayEngine(
    override val capsule: Capsule,
    override val settings: ReplaySettings,
    private val actions: ActionRegistry,
    val buildGitSha: String?,
    private val reporter: ReplayReporter?,
    private val nanoTime: () -> Long = System::nanoTime,
) : ReplayContext {
    override val fixtures = FixtureStore(capsule.events)
    private val idle = IdleCompressor(capsule.events)
    private val divergences = ArrayList<Divergence>()
    private val otherFailures = ArrayList<FailureRecord>()
    private val reproduced = CountDownLatch(1)
    private val observed = AtomicReference<FailureRecord?>()
    private val pressed = HashSet<Int>()
    private val fixturesServed = AtomicInteger()
    private val dispatched = AtomicInteger()
    private val work = AtomicInteger()
    private val skippedMs = AtomicLong()
    private var gapsCompressed = 0
    private val lastActivityNs = AtomicLong(0)
    private val reported = AtomicBoolean(false)
    private val createdNs = nanoTime()
    private var finalized = false

    @Volatile
    private var startNs: Long? = null

    @Volatile
    var strategy: ReplayStrategy = settings.strategy
        private set

    @Volatile
    private var expectedInputs: Int? = null

    @Volatile
    override var inputsStarted = false
        private set

    /** Runs right before the report is built (late divergences such as `restore.missing`). */
    @Volatile
    var beforeReport: (() -> Unit)? = null

    /** Messages for the report (e.g. why the replay stopped early). */
    @Volatile
    var message: String? = null

    private fun msSince(ns: Long) = (nanoTime() - ns) / 1_000_000

    /** Replay clock in recorded ms since [run] started (skipped idle time included). */
    fun elapsed(): Long = (startNs?.let { msSince(it) } ?: 0) + skippedMs.get()

    /** Virtual wall clock: checkpoint wall time + time since restore + skipped idle time. */
    fun currentTimeMillis(): Long = capsule.checkpoint.wallClockMs + msSince(createdNs) + skippedMs.get()

    override fun diverge(type: String, detail: String) {
        val t = if (startNs != null) elapsed() else null
        synchronized(divergences) {
            if (divergences.size < 200) divergences.add(Divergence(type, detail, t))
        }
    }

    override fun fixtureServed() {
        fixturesServed.incrementAndGet()
    }

    override fun beginWork(): WorkToken {
        work.incrementAndGet()
        activity()
        val ended = AtomicBoolean(false)
        return WorkToken {
            if (ended.compareAndSet(false, true)) {
                work.decrementAndGet()
                activity()
            }
        }
    }

    override fun activity() {
        lastActivityNs.set(nanoTime())
    }

    private val quietMs: Long get() = msSince(lastActivityNs.get())
    private val busy: Int get() = work.get() + synchronized(pressed) { pressed.size }

    val isReproduced: Boolean get() = reproduced.count == 0L

    /** Called by the runtime for every failure seen while replaying. */
    fun onFailure(failure: FailureRecord) {
        val expected = capsule.manifest.failure
        if (expected != null && failure.signature == expected.signature) {
            if (observed.compareAndSet(null, failure)) reproduced.countDown()
        } else {
            synchronized(otherFailures) { if (otherFailures.size < 50) otherFailures.add(failure) }
            diverge("failure.other", failure.signature)
        }
    }

    /** Checks that apply before the app starts. */
    fun precheck() {
        val m = capsule.manifest
        if (m.gitSha != null && buildGitSha != null && m.gitSha != buildGitSha) {
            diverge("build.gitSha", "capsule ${m.gitSha} vs build $buildGitSha")
        }
    }

    private fun chooseStrategy(screen: ScreenInfo?): ReplayStrategy {
        val hasPointers = capsule.events.any { it.type == EventTypes.POINTER }
        val hasActions = capsule.events.any { it.type == EventTypes.ACTION }
        if (settings.strategy != ReplayStrategy.AUTO) return settings.strategy
        val p = capsule.manifest.platform
        val sameScreen = screen != null &&
            Math.abs(screen.widthDp - p.screenWidth) < 1 && Math.abs(screen.heightDp - p.screenHeight) < 1
        if (hasPointers && (sameScreen || !hasActions)) {
            if (!sameScreen) {
                diverge(
                    "screen.size",
                    "recorded ${p.screenWidth}x${p.screenHeight}, replay ${screen?.widthDp}x${screen?.heightDp}; pointers are scaled",
                )
            }
            return ReplayStrategy.POINTER
        }
        return ReplayStrategy.SEMANTIC
    }

    private fun shouldDispatch(type: String) = when (type) {
        EventTypes.LIFECYCLE, EventTypes.TEXT, EventTypes.KEY -> true
        EventTypes.POINTER -> strategy == ReplayStrategy.POINTER
        EventTypes.ACTION -> strategy == ReplayStrategy.SEMANTIC
        else -> false
    }

    private val base: Long
        get() = if (capsule.checkpoint.kind == CheckpointKind.LAUNCH) 0 else capsule.checkpoint.t

    private fun inputs(): List<CapsuleEvent> = capsule.events.filter { shouldDispatch(it.type) && it.t >= base }

    /**
     * Runs the replay on the calling thread (the instrumentation test thread),
     * reports the result through the reporter and returns it.
     */
    fun run(driver: ReplayDriver): ReplayReport {
        startNs = nanoTime()
        activity()
        val screen = try {
            driver.screen()
        } catch (_: Exception) {
            null
        }
        strategy = chooseStrategy(screen)
        val expected = capsule.manifest.failure
        val toDispatch = inputs()
        expectedInputs = toDispatch.size

        var lastT = 0L
        var previous = base
        for (event in toDispatch) {
            if (isReproduced) break
            val due = event.t - base
            lastT = due
            val idleSince = if (settings.idleCompression) idle.idleSince(previous, event.t) else null
            previous = event.t
            while (!isReproduced) {
                val wait = due - elapsed()
                if (wait <= 0) break
                if (idleSince != null &&
                    idle.maySkip(now = elapsed() + base, idleSince = idleSince, busy = busy, quietMs = quietMs)
                ) {
                    skippedMs.addAndGet(wait)
                    gapsCompressed++
                    break
                }
                val step = if (idleSince == null || wait < 50) wait else 50
                reproduced.await(step, TimeUnit.MILLISECONDS)
            }
            if (isReproduced) break
            dispatch(event, driver, screen)
        }

        if (!isReproduced) {
            val failureDue = if (expected == null) 0 else expected.t - base
            val deadline = maxOf(failureDue, lastT) + (if (expected == null) 1500 else settings.settleTimeoutMs)
            val remaining = deadline - elapsed()
            if (remaining > 0) reproduced.await(remaining, TimeUnit.MILLISECONDS)
        }
        val report = buildReport()
        reportOnce(report)
        return report
    }

    /**
     * Builds and sends the report now, synchronously (the uncaught-exception
     * handler calls it before the process dies). Returns false when a report
     * was already sent.
     */
    fun finishNow(reason: String?): Boolean {
        if (reason != null && message == null) message = reason
        if (expectedInputs == null) {
            // The app crashed before the run started: count with the strategy auto would pick.
            if (strategy == ReplayStrategy.AUTO) {
                val hasPointers = capsule.events.any { it.type == EventTypes.POINTER }
                val hasActions = capsule.events.any { it.type == EventTypes.ACTION }
                strategy = if (hasPointers && !hasActions) ReplayStrategy.POINTER else if (hasActions) ReplayStrategy.SEMANTIC else ReplayStrategy.POINTER
            }
            expectedInputs = inputs().size
        }
        return reportOnce(buildReport())
    }

    private fun reportOnce(report: ReplayReport): Boolean {
        if (!reported.compareAndSet(false, true)) return false
        try {
            reporter?.report(report)
        } catch (_: Exception) {
        }
        return true
    }

    val hasReported: Boolean get() = reported.get()

    @Synchronized
    fun buildReport(): ReplayReport {
        if (!finalized) {
            finalized = true
            try {
                beforeReport?.invoke()
            } catch (_: Exception) {
            }
            for (key in fixtures.unusedKeys()) diverge("network.unused", key)
        }
        val expected = capsule.manifest.failure
        val seen = observed.get()
        val outcome = when {
            expected == null -> ReplayOutcome.NO_ASSERTION
            seen != null -> ReplayOutcome.REPRODUCED
            else -> ReplayOutcome.NOT_REPRODUCED
        }
        return ReplayReport(
            outcome = outcome,
            attempt = settings.attempt,
            durationMs = startNs?.let { msSince(it) } ?: 0,
            strategy = strategy,
            expectedSignature = expected?.signature,
            observed = seen,
            otherFailures = synchronized(otherFailures) { otherFailures.toList() },
            divergences = synchronized(divergences) { divergences.toList() },
            eventsDispatched = dispatched.get(),
            eventsExpected = expectedInputs ?: 0,
            fixturesServed = fixturesServed.get(),
            compressedMs = skippedMs.get(),
            gapsCompressed = gapsCompressed,
            buildGitSha = buildGitSha,
            message = message,
            attemptId = settings.attemptId,
            runtime = "android",
        )
    }

    private fun dispatch(event: CapsuleEvent, driver: ReplayDriver, screen: ScreenInfo?) {
        try {
            when (event.type) {
                EventTypes.POINTER -> dispatchPointer(event.data, driver, screen)
                EventTypes.ACTION -> {
                    val id = event.data["id"] as String
                    val payload = J.obj(event.data["payload"])
                    var found = false
                    inputsStarted = true
                    driver.runOnMainSync { found = actions.invoke(id, payload) }
                    if (!found) {
                        diverge("action.unregistered", id)
                        return
                    }
                }
                EventTypes.TEXT -> {
                    val id = event.data["id"] as String
                    val value = J.str(event.data["value"]) ?: ""
                    if (value.contains(Redactor.PLACEHOLDER)) diverge("text.redacted", id)
                    var found = false
                    inputsStarted = true
                    driver.runOnMainSync { found = actions.setText(id, value) }
                    if (!found) {
                        diverge("text.untracked", id)
                        return
                    }
                }
                EventTypes.KEY -> {
                    val phase = J.str(event.data["phase"]) ?: "down"
                    val code = J.int(event.data["keyCode"]) ?: 0
                    inputsStarted = true
                    if (!driver.dispatchKey(phase, code)) {
                        diverge("key.unsupported", "${event.data["key"] ?: code}")
                        return
                    }
                }
                EventTypes.LIFECYCLE -> {
                    val state = event.data["state"] as String
                    inputsStarted = true
                    if (!driver.moveLifecycle(state)) {
                        diverge("lifecycle.unsupported", state)
                        return
                    }
                }
            }
            dispatched.incrementAndGet()
            activity()
        } catch (e: Exception) {
            diverge("dispatch.error", "${event.type}: $e")
        }
    }

    private fun dispatchPointer(d: Map<String, Any?>, driver: ReplayDriver, screen: ScreenInfo?) {
        val p = capsule.manifest.platform
        val density = screen?.density ?: p.pixelRatio
        val sx = if (p.screenWidth > 0 && screen != null) screen.widthDp / p.screenWidth else 1.0
        val sy = if (p.screenHeight > 0 && screen != null) screen.heightDp / p.screenHeight else 1.0
        val x = (J.num(d["x"]) ?: 0.0) * sx * density
        val y = (J.num(d["y"]) ?: 0.0) * sy * density
        val pointer = J.int(d["pointer"]) ?: 0
        val phase = d["phase"] as String
        val kind = J.str(d["kind"]) ?: "touch"
        val buttons = J.int(d["buttons"]) ?: 1
        synchronized(pressed) {
            if (phase == "up" || phase == "cancel") pressed.remove(pointer) else pressed.add(pointer)
        }
        inputsStarted = true
        driver.dispatchPointer(phase, pointer, x.toFloat(), y.toFloat(), kind, buttons)
    }
}
