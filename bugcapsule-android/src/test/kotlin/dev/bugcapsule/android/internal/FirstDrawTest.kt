package dev.bugcapsule.android.internal

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class FirstDrawTest {
    @Test fun firstDrawSurvivesWindowAttachmentAndRunsOnlyOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val decor = controller.get().window.decorView
        assertFalse(decor.isAttachedToWindow)
        var draws = 0
        onFirstDraw(decor) { draws++ }
        controller.start().resume().visible()
        assertTrue(decor.isAttachedToWindow)
        decor.viewTreeObserver.dispatchOnDraw()
        decor.viewTreeObserver.dispatchOnDraw()
        assertEquals(1, draws)
        controller.pause().stop().destroy()
    }

    @Test fun observesAlreadyAttachedWindows() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var draws = 0
        val decor = controller.get().window.decorView
        onFirstDraw(decor) { draws++ }
        decor.viewTreeObserver.dispatchOnDraw()
        assertEquals(1, draws)
        controller.pause().stop().destroy()
    }
}
