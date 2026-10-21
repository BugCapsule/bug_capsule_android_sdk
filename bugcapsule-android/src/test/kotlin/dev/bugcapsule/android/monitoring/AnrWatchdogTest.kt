package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import org.junit.Assert.assertEquals
import org.junit.Test

class AnrWatchdogTest {
    private var now = 0L
    private val posted = ArrayList<Runnable>()
    private val anrs = ArrayList<Long>()
    private var foreground = true

    private fun watchdog() = AnrWatchdog(
        timeoutMs = 5_000,
        postToMain = { posted.add(it) },
        mainThread = Thread.currentThread(),
        isForeground = { foreground },
        onAnr = { _, blocked -> anrs.add(blocked) },
        clock = { now },
        debuggerAttached = { false },
    )

    @Test
    fun aBlockedMainThreadIsReportedOncePerHang() {
        val w = watchdog()
        w.step() // posts a tick that never runs
        for (i in 1..20) {
            now += 500
            w.step()
        }
        assertEquals(1, anrs.size)
        assertEquals(5_000L, anrs[0])
        posted.forEach { it.run() } // the main thread recovers
        w.step()
        now += 6_000
        w.step()
        assertEquals("re-armed after recovery", 2, anrs.size)
    }

    @Test
    fun aResponsiveMainThreadOrABackgroundAppIsNotReported() {
        val w = watchdog()
        repeat(40) {
            w.step()
            posted.forEach { it.run() }
            posted.clear()
            now += 500
        }
        foreground = false
        w.step()
        now += 10_000
        w.step()
        assertEquals(0, anrs.size)
    }

    @Test
    fun theEventCarriesTheMainThreadStack() {
        val ev = LinkedHashMap<String, Any?>()
        val stack = arrayOf(StackTraceElement("com.shop.Db", "load", "Db.kt", 3), StackTraceElement("android.os.Looper", "loop", "Looper.java", 1))
        AnrWatchdog.fill(ev, stack, 5_100, 5_000, Thread.currentThread(), InAppRules(listOf("com.shop"), emptyList()))
        val value = J.obj(J.list(J.obj(ev["exception"])!!["values"])!![0])!!
        assertEquals("ApplicationNotResponding", value["type"])
        assertEquals("anr", J.obj(value["mechanism"])!!["type"])
        val thread = J.obj(J.list(ev["threads"])!![0])!!
        assertEquals(true, thread["crashed"])
        val frames = J.list(J.obj(thread["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals(listOf("loop", "load"), frames.map { it["function"] })
    }
}
