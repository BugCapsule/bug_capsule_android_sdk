package dev.bugcapsule.android.monitoring

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects the main thread being blocked for [timeoutMs] (Android's ANR
 * threshold is 5 s) while the app is in the foreground: a background thread
 * posts a tick to the main thread and checks it ran. Reports once per hang
 * (with the main thread's stack at detection time) and re-arms when the main
 * thread responds again. Not reported while a debugger is attached.
 */
internal class AnrWatchdog(
    private val timeoutMs: Long,
    private val postToMain: (Runnable) -> Unit,
    private val mainThread: Thread,
    private val isForeground: () -> Boolean,
    private val onAnr: (stack: Array<StackTraceElement>, blockedMs: Long) -> Unit,
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
    private val debuggerAttached: () -> Boolean = { android.os.Debug.isDebuggerConnected() },
    private val pollMs: Long = 500,
) : Thread("BugCapsule-ANR") {
    private val ticked = AtomicBoolean(true)

    @Volatile
    private var postedAt = 0L

    @Volatile
    private var reported = false

    @Volatile
    private var stopped = false

    init {
        isDaemon = true
    }

    fun shutdown() {
        stopped = true
        interrupt()
    }

    override fun run() {
        while (!stopped) {
            try {
                step()
                sleep(pollMs)
            } catch (_: InterruptedException) {
                if (stopped) return
            } catch (_: Throwable) {
            }
        }
    }

    /** One poll (tests drive it directly). */
    internal fun step() {
        val now = clock()
        if (ticked.get()) {
            reported = false
            ticked.set(false)
            postedAt = now
            postToMain(Runnable { ticked.set(true) })
            return
        }
        val blocked = now - postedAt
        if (blocked < timeoutMs || reported) return
        if (!isForeground() || debuggerAttached()) return
        reported = true
        onAnr(mainThread.stackTrace, blocked)
    }

    companion object {
        /** The event body of an ANR: the main thread's stack as the exception and in `threads`. */
        fun fill(ev: MutableMap<String, Any?>, stack: Array<StackTraceElement>, blockedMs: Long, timeoutMs: Long, mainThread: Thread, inApp: InAppRules) {
            val frames = ExceptionMapper.frames(stack, inApp)
            ev["exception"] = linkedMapOf(
                "values" to listOf(
                    linkedMapOf(
                        "type" to "ApplicationNotResponding",
                        "value" to "Application Not Responding for at least $timeoutMs ms (main thread blocked ${blockedMs} ms).",
                        "module" to "dev.bugcapsule.android",
                        "mechanism" to ExceptionMapper.Mechanism("anr", false).toJson(),
                        "stacktrace" to linkedMapOf("frames" to frames),
                    ),
                ),
            )
            ev["threads"] = listOf(
                linkedMapOf(
                    "id" to mainThread.id,
                    "name" to mainThread.name,
                    "crashed" to true,
                    "current" to false,
                    "stacktrace" to linkedMapOf("frames" to frames),
                ),
            )
        }
    }
}
