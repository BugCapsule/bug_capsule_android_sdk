package dev.bugcapsule.android.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import dev.bugcapsule.android.core.EventTypes
import java.lang.ref.WeakReference

/**
 * Process-level capture through public APIs only:
 * * lifecycle via [Application.ActivityLifecycleCallbacks] (first activity
 *   resumed → `resumed`, last paused → `inactive`, last stopped → `paused`;
 *   configuration changes are ignored);
 * * pointers by wrapping each Activity's [Window.Callback] (no accessibility
 *   service, no screen recording, no reflection);
 * * the session anchor: the first drawn frame (`ViewTreeObserver.OnDrawListener`).
 */
internal class AndroidCapture(private val runtime: Runtime, private val host: Host) :
    Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var resumed = 0
    private var anchored = false

    // A configuration change pauses/stops one activity and resumes its
    // replacement; the process never left the foreground.
    private var configChangePause = false
    private var configChangeStop = false
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        host.application.registerActivityLifecycleCallbacks(this)
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        host.application.unregisterActivityLifecycleCallbacks(this)
    }

    private fun wrap(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is RecordingWindowCallback) return
        window.callback = RecordingWindowCallback(current, runtime, activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard { wrap(activity) }

    override fun onActivityStarted(activity: Activity) = guard {
        started++
        if (started == 1) configChangeStop = false
    }

    override fun onActivityResumed(activity: Activity) = guard {
        wrap(activity) // a callback replaced after onCreate is wrapped again
        host.currentWindow = WeakReference(activity.window)
        runtime.onActivityResumed(activity)
        if (!anchored) anchorOnFirstDraw(activity)
        resumed++
        if (resumed == 1) {
            if (configChangePause) configChangePause = false else runtime.onLifecycle("resumed")
        }
    }

    override fun onActivityPaused(activity: Activity) = guard {
        if (resumed == 0) return@guard
        resumed--
        if (resumed == 0) {
            if (activity.isChangingConfigurations) configChangePause = true else runtime.onLifecycle("inactive")
        }
    }

    override fun onActivityStopped(activity: Activity) = guard {
        if (started == 0) return@guard
        started--
        if (started == 0) {
            if (activity.isChangingConfigurations) configChangeStop = true else runtime.onLifecycle("paused")
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    private fun anchorOnFirstDraw(activity: Activity) {
        anchored = true
        val decor = activity.window?.decorView ?: return runtime.onFirstDraw()
        val listener = object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                runtime.onFirstDraw()
                // Listeners cannot be removed while drawing.
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            SdkLog.once("capture callback failed: $e")
        }
    }
}

/**
 * Records touches (screen coordinates divided by density, runtimes-v1 §4)
 * and forwards everything to the wrapped callback unchanged.
 */
internal class RecordingWindowCallback(
    val delegate: Window.Callback,
    private val runtime: Runtime,
    activity: Activity,
) : Window.Callback {
    private val density = activity.resources.displayMetrics.density.takeIf { it > 0 } ?: 1f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (runtime.isRecording) {
            try {
                record(event)
            } catch (e: Throwable) {
                SdkLog.once("pointer capture failed: $e")
            }
        }
        return delegate.dispatchTouchEvent(event)
    }

    private fun record(event: MotionEvent) {
        val offsetX = event.rawX - event.x
        val offsetY = event.rawY - event.y
        fun emit(phase: String, index: Int) {
            val data = linkedMapOf<String, Any?>(
                "phase" to phase,
                "pointer" to event.getPointerId(index),
                "x" to Host.round2(((event.getX(index) + offsetX) / density).toDouble()),
                "y" to Host.round2(((event.getY(index) + offsetY) / density).toDouble()),
                "kind" to when (event.getToolType(index)) {
                    MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
                    MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
                    MotionEvent.TOOL_TYPE_ERASER -> "invertedStylus"
                    else -> "touch"
                },
            )
            if (event.buttonState != 0 && event.buttonState != MotionEvent.BUTTON_PRIMARY) data["buttons"] = event.buttonState
            runtime.record(EventTypes.POINTER, data)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> emit("down", event.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> emit("up", event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) emit("move", i)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) emit("cancel", i)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (runtime.isObserving && event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        ) {
            try {
                runtime.record(
                    EventTypes.KEY,
                    linkedMapOf(
                        "phase" to if (event.action == KeyEvent.ACTION_DOWN) "down" else "up",
                        "keyCode" to event.keyCode,
                        "key" to "back",
                    ),
                )
            } catch (e: Throwable) {
                SdkLog.once("key capture failed: $e")
            }
        }
        return delegate.dispatchKeyEvent(event)
    }
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = delegate.dispatchKeyShortcutEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = delegate.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = delegate.dispatchGenericMotionEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = delegate.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = delegate.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = delegate.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = delegate.onAttachedToWindow()
    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()
    @SuppressLint("NewApi")
    override fun onSearchRequested(searchEvent: SearchEvent): Boolean = delegate.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? =
        delegate.onWindowStartingActionMode(callback)
    @SuppressLint("NewApi")
    override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        delegate.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode) = delegate.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode) = delegate.onActionModeFinished(mode)

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
}
