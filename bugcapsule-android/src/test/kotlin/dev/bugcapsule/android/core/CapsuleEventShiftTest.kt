package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CapsuleEventShiftTest {
    @Test
    fun aScreenshotKeepsCapturedAtTAtOrBeforeTAfterTheAnchorShift() {
        val event = CapsuleEvent(1, 6998, "screenshot", mapOf<String, Any?>("schema" to 1, "capturedAtT" to 6998L))

        val shifted = event.shift(1181)

        assertEquals(5817L, shifted.t)
        assertEquals(5817L, shifted.data["capturedAtT"])
        assertEquals(1, shifted.data["schema"])
        assertEquals(6998L, event.data["capturedAtT"])
    }

    @Test
    fun otherEventsKeepTheirData() {
        val data = mapOf("id" to "cart.checkout")

        assertSame(data, CapsuleEvent(2, 100, "action", data).shift(40).data)
    }
}
