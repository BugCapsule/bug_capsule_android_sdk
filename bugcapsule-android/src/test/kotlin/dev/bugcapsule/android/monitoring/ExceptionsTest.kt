package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionsTest {
    private val inApp = InAppRules(listOf("com.shop"), listOf("com.shop.vendor"))

    private fun boom(): Nothing = throw IllegalStateException("Cart total is negative (-3), token=abc123secret")

    @Test
    fun chainIsInnermostFirstAndFramesOldestFirst() {
        val root = IllegalArgumentException("bad price")
        root.stackTrace = arrayOf(
            StackTraceElement("com.shop.Price", "parse", "Price.kt", 12),
            StackTraceElement("com.shop.Cart", "total", "Cart.kt", 40),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
        )
        val outer = RuntimeException("checkout failed", root)
        val values = ExceptionMapper.values(outer, ExceptionMapper.Mechanism("uncaught", false), inApp) { it }
        assertEquals(listOf("IllegalArgumentException", "RuntimeException"), values.map { it["type"] })
        assertEquals("java.lang", values[1]["module"])
        assertEquals(mapOf("type" to "uncaught", "handled" to false), values[1]["mechanism"])
        assertEquals("chained", J.obj(values[0]["mechanism"])!!["type"])
        val frames = J.list(J.obj(values[0]["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals(listOf("dispatchMessage", "total", "parse"), frames.map { it["function"] })
        assertEquals(listOf(false, true, true), frames.map { it["inApp"] })
        assertEquals(12, frames.last()["lineno"])
        assertEquals("Price.kt", frames.last()["filename"])
    }

    @Test
    fun excludesWinOverIncludes() {
        assertTrue(inApp.isInApp("com.shop.CheckoutFragment"))
        assertFalse(inApp.isInApp("com.shop.vendor.Lib"))
        assertFalse(inApp.isInApp("okhttp3.Call"))
        assertFalse(inApp.isInApp(null))
    }

    @Test
    fun cyclesAndLongChainsAreBounded() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(2, ExceptionMapper.values(b, ExceptionMapper.Mechanism("manual", true), inApp) { it }.size)
        var e: Throwable = RuntimeException("0")
        repeat(20) { e = RuntimeException("${it + 1}", e) }
        assertEquals(ExceptionMapper.MAX_VALUES, ExceptionMapper.values(e, ExceptionMapper.Mechanism("manual", true), inApp) { it }.size)
    }

    @Test
    fun messagesAreScrubbedAndFramesCapped() {
        val e = try {
            boom()
        } catch (t: IllegalStateException) {
            t
        }
        e.stackTrace = Array(300) { StackTraceElement("com.shop.Deep", "f$it", "Deep.kt", it + 1) }
        val v = ExceptionMapper.value(e, ExceptionMapper.Mechanism("manual", true), inApp) { it.replace("abc123secret", "[REDACTED]") }
        assertEquals("Cart total is negative (-3), token=[REDACTED]", v["value"])
        val frames = J.list(J.obj(v["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals(ExceptionMapper.MAX_FRAMES, frames.size)
        assertEquals("f0", frames.last()["function"]) // the crashing frame is kept, last
    }
}
