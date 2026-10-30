package dev.bugcapsule.android.replay

import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.WorkToken
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.FailureKind
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.IdleCompressor
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.emergency.EmergencyDecision
import dev.bugcapsule.android.emergency.EmergencyDecisionScript
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

    /** Restores the recorded soft keyboard state before a new pointer gesture. */
    fun preparePointer(imeVisible: Boolean) {}

    fun runOnMainSync(block: Runnable)

    /** Drives the app to a recorded process lifecycle state; false when it cannot. */
    fun moveLifecycle(state: String): Boolean

    /** Injects a recorded system key (`key` event); false when unsupported. */
    fun dispatchKey(phase: String, keyCode: Int): Boolean = false

    /** Posts [task] to the app's main thread without waiting (the ANR watchdog of crash capsules). */
    fun postToMain(task: Runnable) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(task)
    }

    /** The app's main thread, whose stack names the in-app frame of an ANR. */
    fun mainThread(): Thread? = android.os.Looper.getMainLooper().thread
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
    /** Crash capsules: where an attempt that may die leaves what the post-mortem needs ([CrashReplay.markerFile]). */
    private val attemptMarker: java.io.File? = null,
    /** Crash capsules: how long the main thread may block before it is an ANR. */
    private val anrThresholdMs: Long = CrashReplay.ANR_THRESHOLD_MS,
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

    /** A crash capsule (crash-capsules §4): the attempt is judged by how the app dies. */
    val expectsCrash: Boolean = capsule.manifest.failure?.kind == FailureKind.CRASH
    private val death = AtomicReference<CrashReplay.Death?>()

    /**
     * Interaction dependencies (capsule-v2 §5) on the replay clock
     * [interactionClock]; due deliveries keep idle compression from skipping
     * past their deadline.
     */
    override val interactions: InteractionReplay = InteractionReplay(capsule, ::interactionClock, ::diverge, ::activity)

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

    private val emergencyDecisions by lazy { EmergencyDecisionScript(capsule.events) }

    /** The decision the app saw at this point of the capture (emergency-client-v1 §7.8); null when the capsule has none for [key]. */
    internal fun emergencyDecision(key: String, subjectTag: String?): EmergencyDecision? =
        emergencyDecisions.lookup(key, subjectTag, base + elapsed())

    /** Clock of interaction deliveries: ms since the engine was created, skipped idle time included. */
    fun interactionClock(): Long = msSince(createdNs) + skippedMs.get()

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

    /** The expected failure happened, or (crash capsules) the app is dying: the run stops. */
    val isReproduced: Boolean get() = reproduced.count == 0L

    /**
     * Called by the runtime for every failure seen while replaying. [fatal]:
     * an uncaught exception, the process dies next; for a crash capsule that
     * is a death of another type.
     */
    @JvmOverloads
    fun onFailure(failure: FailureRecord, fatal: Boolean = false) {
        if (expectsCrash && fatal) {
            onDeath(CrashReplay.Death(failure.type, "uncaught", "uncaught", failure.message))
            return
        }
        val expected = capsule.manifest.failure
        if (expected != null && failure.signature == expected.signature) {
            if (observed.compareAndSet(null, failure)) reproduced.countDown()
        } else {
            synchronized(otherFailures) { if (otherFailures.size < 50) otherFailures.add(failure) }
            diverge("failure.other", failure.signature)
        }
    }

    /** Crash capsules: the app under test is dying (or hung) this way; the run stops and the report judges it. */
    fun onDeath(d: CrashReplay.Death) {
        if (death.compareAndSet(null, d)) reproduced.countDown()
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
        interactions.runStarted()
        val screen = try {
            driver.screen()
        } catch (_: Exception) {
            null
        }
        strategy = chooseStrategy(screen)
        val expected = capsule.manifest.failure
        val toDispatch = inputs()
        expectedInputs = toDispatch.size
        var watchdog: Thread? = null
        if (expectsCrash && expected != null) {
            attemptMarker?.let { CrashReplay.writeMarker(it, capsule.manifest.capsuleId, settings, expected, strategy, toDispatch.size) }
            watchdog = startAnrWatchdog(driver)
        }

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
                // An interaction delivery due before the input keeps the gap from being skipped.
                val deliveryDue = interactions.dueIn()?.let { it < wait } ?: false
                if (idleSince != null && !deliveryDue &&
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
        watchdog?.interrupt()
        val report = buildReport()
        reportOnce(report)
        return report
    }

    /**
     * Crash capsules: the main thread blocked for [anrThresholdMs] is an
     * ANR. Reported at once, while the process still lives (the system may
     * kill it next).
     */
    private fun startAnrWatchdog(driver: ReplayDriver): Thread = Thread({
        try {
            while (!isReproduced) {
                val ran = CountDownLatch(1)
                driver.postToMain { ran.countDown() }
                if (!ran.await(anrThresholdMs, TimeUnit.MILLISECONDS)) {
                    val stack = try {
                        driver.mainThread()?.stackTrace ?: emptyArray()
                    } catch (_: Throwable) {
                        emptyArray()
                    }
                    onDeath(
                        CrashReplay.Death(
                            "ApplicationNotResponding", "anr", "watchdog",
                            "the main thread was blocked for ${anrThresholdMs} ms",
                            pid = android.os.Process.myPid(),
                            inAppFrame = CrashReplay.inAppFrame(stack, capsule.manifest.app.id),
                        ),
                    )
                    finishNow("the main thread was blocked for ${anrThresholdMs} ms (ANR)")
                    return@Thread
                }
                Thread.sleep(ANR_POLL_MS)
            }
        } catch (_: InterruptedException) {
        }
    }, "BugCapsule-ReplayAnr").apply {
        isDaemon = true
        start()
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
        // Reported from this process: nothing for a post-mortem to judge.
        attemptMarker?.let { dev.bugcapsule.android.internal.Files.deleteQuietly(it) }
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
            try {
                interactions.finish(capsule.manifest.failure?.t)
            } catch (e: Exception) {
                diverge("dispatch.error", "interactions: $e")
            }
        }
        val expected = capsule.manifest.failure
        val died = if (expectsCrash) death.get() else null
        val judgment = if (expected != null && died != null) CrashReplay.judge(expected, died) else null
        val seen = if (expectsCrash) died?.toFailure(elapsed()) else observed.get()
        val outcome = when {
            expected == null -> ReplayOutcome.NO_ASSERTION
            judgment != null -> judgment.outcome
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
            reasonCode = judgment?.reasonCode,
            death = died?.toJson(),
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

    private companion object {
        const val ANR_POLL_MS = 500L
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
        if (phase == "down") (d["imeVisible"] as? Boolean)?.let(driver::preparePointer)
        driver.dispatchPointer(phase, pointer, x.toFloat(), y.toFloat(), kind, buttons)
    }
}
