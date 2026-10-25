package dev.bugcapsule.android.replay

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.atomic.AtomicReference

/**
 * Injects replayed input with public instrumentation APIs:
 * pointers via [Instrumentation.sendPointerSync] (screen pixels; multi-touch
 * supported), lifecycle by moving the [ActivityScenario] (`resumed` →
 * RESUMED, `inactive` → STARTED, `paused` → CREATED), actions and text on the
 * main thread via [Instrumentation.runOnMainSync].
 */
class InstrumentationReplayDriver(private val instrumentation: Instrumentation) : ReplayDriver {
    @Volatile
    var scenario: ActivityScenario<out Activity>? = null

    private var downTime = 0L
    private val active = LinkedHashMap<Int, Pair<Float, Float>>()
    private var toolType = MotionEvent.TOOL_TYPE_FINGER

    override fun screen(): ScreenInfo? {
        val result = AtomicReference<ScreenInfo?>()
        try {
            scenario?.onActivity { activity ->
                val density = activity.resources.displayMetrics.density.toDouble()
                val decor = activity.window.decorView
                if (decor.width > 0 && density > 0) {
                    result.set(ScreenInfo(round2(decor.width / density), round2(decor.height / density), density))
                }
            }
        } catch (_: Throwable) {
        }
        return result.get()
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    override fun preparePointer(imeVisible: Boolean) {
        val activeScenario = checkNotNull(scenario) { "No activity for keyboard synchronization" }
        var matched = false
        var requested = false
        val deadline = SystemClock.uptimeMillis() + 2_000
        do {
            activeScenario.onActivity { activity ->
                val decor = activity.window.decorView
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)) { "Window insets unavailable" }
                matched = insets.isVisible(WindowInsetsCompat.Type.ime()) == imeVisible
                if (!matched && !requested) {
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (imeVisible) {
                        val focus = checkNotNull(activity.currentFocus) { "No focused view for recorded keyboard" }
                        imm.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
                    } else {
                        imm.hideSoftInputFromWindow(decor.windowToken, 0)
                    }
                    requested = true
                }
            }
            if (matched) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Recorded keyboard visibility ($imeVisible) was not restored before pointer injection")
    }

    override fun dispatchPointer(phase: String, pointer: Int, xPx: Float, yPx: Float, kind: String, buttons: Int) {
        toolType = when (kind) {
            "mouse" -> MotionEvent.TOOL_TYPE_MOUSE
            "stylus" -> MotionEvent.TOOL_TYPE_STYLUS
            "invertedStylus" -> MotionEvent.TOOL_TYPE_ERASER
            else -> MotionEvent.TOOL_TYPE_FINGER
        }
        when (phase) {
            "down" -> {
                if (active.isEmpty()) downTime = SystemClock.uptimeMillis()
                active[pointer] = xPx to yPx
                val index = active.keys.indexOf(pointer)
                send(if (active.size == 1) MotionEvent.ACTION_DOWN else pointerAction(MotionEvent.ACTION_POINTER_DOWN, index))
            }
            "move" -> {
                if (pointer !in active) return
                active[pointer] = xPx to yPx
                send(MotionEvent.ACTION_MOVE)
            }
            "up" -> {
                if (pointer !in active) return
                active[pointer] = xPx to yPx
                val index = active.keys.indexOf(pointer)
                send(if (active.size == 1) MotionEvent.ACTION_UP else pointerAction(MotionEvent.ACTION_POINTER_UP, index))
                active.remove(pointer)
            }
            else -> {
                if (active.isEmpty()) return
                send(MotionEvent.ACTION_CANCEL)
                active.clear()
            }
        }
    }

    private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun send(action: Int) {
        val ids = active.keys.toList()
        val properties = Array(ids.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = this@InstrumentationReplayDriver.toolType
            }
        }
        val coords = Array(ids.size) { i ->
            MotionEvent.PointerCoords().apply {
                val (x, y) = active.getValue(ids[i])
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, ids.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private var keyDownTime = 0L

    override fun dispatchKey(phase: String, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (phase == "down") keyDownTime = now
        val action = if (phase == "down") KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        instrumentation.sendKeySync(KeyEvent(keyDownTime, now, action, keyCode, 0))
        return true
    }

    override fun runOnMainSync(block: Runnable) = instrumentation.runOnMainSync(block)

    override fun moveLifecycle(state: String): Boolean {
        val s = scenario ?: return false
        val target = when (state) {
            "resumed" -> Lifecycle.State.RESUMED
            "inactive" -> Lifecycle.State.STARTED
            "paused", "hidden" -> Lifecycle.State.CREATED
            else -> return false
        }
        s.moveToState(target)
        instrumentation.waitForIdleSync()
        return true
    }
}
