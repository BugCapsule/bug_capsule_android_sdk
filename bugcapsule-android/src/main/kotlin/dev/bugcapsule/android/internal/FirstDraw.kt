package dev.bugcapsule.android.internal

import android.view.View
import android.view.ViewTreeObserver

/** Old Android versions do not transfer OnDrawListeners from a floating observer. */
internal fun onFirstDraw(view: View, block: () -> Unit) {
    fun listen() {
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var done = false
            override fun onDraw() {
                if (done) return
                done = true
                block()
                view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }
    if (view.isAttachedToWindow) listen() else view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            listen()
        }
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}
