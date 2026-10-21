package dev.bugcapsule.android.monitoring

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.metrics.performance.JankStats
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.internal.SdkLog
import java.util.WeakHashMap

/**
 * Screen transactions (monitoring-v1 §8, Android column):
 *
 * * **Activities**: `ui.load` transaction named after the class, bound to the
 *   scope, with `ui.load.initial_display` ending at the first drawn frame
 *   (`time_to_initial_display`) and, with TTFD enabled,
 *   `ui.load.full_display` ending at [reportFullyDrawn]
 *   (`time_to_full_display`).
 * * **App start**: the first activity of a process started less than a
 *   minute ago is a cold start (`app.start.cold` span and `app_start_cold`
 *   from process start to its first frame); an activity created after every
 *   activity was destroyed is a warm start.
 * * **Fragments** (AndroidX): `ui.load` child spans from created to resumed.
 * * **Navigation / Compose screens** ([startScreen]): `navigation` or
 *   `ui.load` transactions whose initial display is the next frame.
 * * **Frames** (JankStats): `frames_total`, `frames_slow`, `frames_frozen`,
 *   `frames_delay` of the window while the transaction ran.
 *
 * Transactions finish when idle ([BugCapsuleConfig.idleTransactionTimeoutMs]
 * without running spans), at the latest after [DEADLINE_MS], or when their
 * activity pauses. Main thread only.
 */
internal class ScreenTracer(
    private val monitoring: Monitoring,
    private val config: BugCapsuleConfig,
    /** SDK init time, used as the process start before API 24. */
    private val initUptimeMs: Long,
) : Application.ActivityLifecycleCallbacks {
    private class Screen(val transaction: Transaction, val initialDisplay: Span?, var fullDisplay: Span?)

    private val main = Handler(Looper.getMainLooper())
    private val screens = WeakHashMap<Activity, Screen>()
    private val frames = WeakHashMap<Window, FrameCounter>()
    private var activityCount = 0
    private var everCreated = false
    private var current: Screen? = null

    /** Wall-clock start of the pending app start and its op, consumed by the next activity. */
    private var pendingStart: Pair<Long, String>? = null

    private fun enabled() = monitoring.active && monitoring.tracingEnabled

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard {
        val first = !everCreated
        everCreated = true
        val wasEmpty = activityCount == 0
        activityCount++
        if (config.enableAppStartTracking) {
            pendingStart = when {
                first && sinceProcessStartMs() < 60_000 -> processStartWallMs() to "app.start.cold"
                !first && wasEmpty -> monitoring.nowMs() to "app.start.warm"
                else -> null
            }
        }
        if (!config.enableActivityTracing || !enabled()) {
            pendingStart = null
            return@guard
        }
        val name = activity.javaClass.simpleName.ifEmpty { activity.javaClass.name }
        monitoring.currentScope().setTag("screen", name)
        val start = pendingStart
        val tx = monitoring.startTransaction(name, "ui.load", bindToScope = true, startMs = start?.first)
        tx.setData("activity", activity.javaClass.name)
        val appStart = start?.let { (ms, op) -> tx.startChildAt(op, if (op == "app.start.cold") "Cold start" else "Warm start", ms) }
        val ttid = tx.startChild("ui.load.initial_display", "$name initial display")
        val ttfd = if (config.enableTimeToFullDisplayTracing) tx.startChild("ui.load.full_display", "$name full display") else null
        val screen = Screen(tx, ttid, ttfd)
        screens[activity] = screen
        current = screen
        trackFrames(activity.window, tx)
        onFirstFrame(activity) {
            val end = monitoring.nowMs()
            ttid.finishAt(end, SpanStatus.OK)
            tx.setMeasurement("time_to_initial_display", end - ttid.startTimestampMs, "millisecond")
            if (appStart != null && start != null) {
                appStart.finishAt(end, SpanStatus.OK)
                tx.setMeasurement(if (start.second == "app.start.cold") "app_start_cold" else "app_start_warm", end - start.first, "millisecond")
                pendingStart = null
            }
        }
        tx.finishWhenIdle(config.idleTransactionTimeoutMs, DEADLINE_MS)
        trackFragments(activity)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) = guard {
        monitoring.currentScope().setTag("screen", activity.javaClass.simpleName.ifEmpty { activity.javaClass.name })
    }

    override fun onActivityPaused(activity: Activity) = guard {
        val screen = screens[activity] ?: return@guard
        if (!screen.transaction.isFinished) screen.transaction.finish()
    }

    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) = guard {
        activityCount = (activityCount - 1).coerceAtLeast(0)
        screens.remove(activity)?.let { if (!it.transaction.isFinished) it.transaction.finish(SpanStatus.CANCELLED) }
        activity.window?.let { w -> frames.remove(w)?.stop() }
    }

    /**
     * A screen of a single-activity app (Navigation destination, Compose
     * screen). While the activity's own load is still running, the screen
     * only names it.
     */
    fun startScreen(name: String, op: String, view: View?): Transaction? {
        if (!enabled()) return null
        monitoring.currentScope().setTag("screen", name)
        val running = current
        if (running != null && !running.transaction.isFinished && running.initialDisplay?.isFinished == false) {
            running.transaction.setTag("screen", name)
            return running.transaction
        }
        val tx = monitoring.startTransaction(name, op, bindToScope = true)
        val ttid = tx.startChild("ui.load.initial_display", "$name initial display")
        val ttfd = if (config.enableTimeToFullDisplayTracing) tx.startChild("ui.load.full_display", "$name full display") else null
        val screen = Screen(tx, ttid, ttfd)
        current = screen
        val window = (view?.context as? Activity)?.window
        if (window != null) trackFrames(window, tx) else frames.values.lastOrNull()?.attach(tx)
        afterNextFrame {
            val end = monitoring.nowMs()
            ttid.finishAt(end, SpanStatus.OK)
            tx.setMeasurement("time_to_initial_display", end - ttid.startTimestampMs, "millisecond")
        }
        tx.finishWhenIdle(config.idleTransactionTimeoutMs, DEADLINE_MS)
        return tx
    }

    /** Ends `ui.load.full_display` of the current screen (`time_to_full_display`). */
    fun reportFullyDrawn() {
        val screen = current ?: return
        val span = screen.fullDisplay ?: return
        if (span.isFinished) return
        val end = monitoring.nowMs()
        span.finishAt(end, SpanStatus.OK)
        screen.transaction.setMeasurement("time_to_full_display", end - span.startTimestampMs, "millisecond")
    }

    /** `ui.action.click` transaction for a semantic action (when no screen load is running). */
    fun onAction(id: String) {
        if (!config.enableUserInteractionTracing || !enabled()) return
        val active = monitoring.currentScope().activeSpan
        if (active is Transaction && !active.isFinished && active.op != "ui.action.click") return
        val tx = monitoring.startTransaction(id, "ui.action.click", bindToScope = true)
        frames.values.lastOrNull()?.attach(tx)
        tx.finishWhenIdle(config.idleTransactionTimeoutMs, DEADLINE_MS)
    }

    // ----------------------------------------------------------- fragments

    private fun trackFragments(activity: Activity) {
        if (!fragmentsAvailable) return
        try {
            FragmentSpans.register(activity, monitoring)
        } catch (_: Throwable) {
        }
    }

    // -------------------------------------------------------------- frames

    private fun trackFrames(window: Window?, tx: Transaction) {
        if (!config.enableFramesTracking || window == null) return
        try {
            val counter = frames.getOrPut(window) { FrameCounter(window) }
            counter.attach(tx)
        } catch (e: Throwable) {
            SdkLog.once("frame tracking unavailable: $e")
        }
    }

    // --------------------------------------------------------------- timing

    private fun onFirstFrame(activity: Activity, block: () -> Unit) {
        val decor = activity.window?.decorView ?: return afterNextFrame(block)
        val listener = object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                // The frame is on screen once the draw pass is over.
                main.postAtFrontOfQueue { guard(block) }
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun afterNextFrame(block: () -> Unit) {
        val run = Runnable {
            try {
                Choreographer.getInstance().postFrameCallback {
                    main.postAtFrontOfQueue { guard(block) }
                }
            } catch (_: Throwable) {
                guard(block)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private fun sinceProcessStartMs(): Long = SystemClock.uptimeMillis() - processStartUptimeMs()

    private fun processStartUptimeMs(): Long =
        if (Build.VERSION.SDK_INT >= 24) android.os.Process.getStartUptimeMillis() else initUptimeMs

    private fun processStartWallMs(): Long = monitoring.nowMs() - sinceProcessStartMs()

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            SdkLog.once("screen tracing failed: $e")
        }
    }

    companion object {
        const val DEADLINE_MS = 30_000L

        private val fragmentsAvailable: Boolean by lazy {
            try {
                Class.forName("androidx.fragment.app.FragmentActivity")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * Counts frames of one window with JankStats; each transaction attached
 * gets the counts between its start and finish as measurements. Slow: over
 * the display's frame budget; frozen: over 700 ms.
 */
internal class FrameCounter(window: Window) {
    private var total = 0L
    private var slow = 0L
    private var frozen = 0L
    private var delayNanos = 0L
    private val budgetNanos: Long
    private val jank: JankStats

    init {
        @Suppress("DEPRECATION")
        val rate = (window.windowManager?.defaultDisplay?.refreshRate ?: 60f).takeIf { it >= 30f } ?: 60f
        budgetNanos = (1_000_000_000L / rate).toLong()
        jank = JankStats.createAndTrack(window) { frame ->
            val d = frame.frameDurationUiNanos
            total++
            if (d > budgetNanos) {
                slow++
                delayNanos += d - budgetNanos
            }
            if (d > 700_000_000L) frozen++
        }
    }

    fun attach(tx: Transaction) {
        val t0 = total
        val s0 = slow
        val f0 = frozen
        val d0 = delayNanos
        tx.doOnFinish {
            val frames = total - t0
            if (frames <= 0) return@doOnFinish
            // A frozen frame is also slow: report them apart, like Sentry.
            val frozenN = frozen - f0
            it.setMeasurement("frames_total", frames, "none")
            it.setMeasurement("frames_slow", (slow - s0) - frozenN, "none")
            it.setMeasurement("frames_frozen", frozenN, "none")
            it.setMeasurement("frames_delay", (delayNanos - d0) / 1e9, "second")
        }
    }

    fun stop() {
        try {
            jank.isTrackingEnabled = false
        } catch (_: Throwable) {
        }
    }
}

/** AndroidX fragment `ui.load` spans (only touched when androidx.fragment is on the classpath). */
internal object FragmentSpans {
    fun register(activity: Activity, monitoring: Monitoring) {
        val fa = activity as? androidx.fragment.app.FragmentActivity ?: return
        val spans = WeakHashMap<androidx.fragment.app.Fragment, Span>()
        fa.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentCreated(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment, savedInstanceState: Bundle?) {
                    val parent = monitoring.currentScope().activeSpan ?: return
                    if (parent.isFinished) return
                    spans[f] = parent.startChild("ui.load", f.javaClass.simpleName.ifEmpty { f.javaClass.name })
                }

                override fun onFragmentResumed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                    spans.remove(f)?.finish()
                }

                override fun onFragmentDestroyed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                    spans.remove(f)?.finish(SpanStatus.CANCELLED)
                }
            },
            true,
        )
    }
}
