package dev.bugcapsule.android.monitoring

/**
 * `threads` of an event (monitoring-v1 §2): every live JVM thread's stack
 * when the app crashes or hangs, so deadlocks and work blocking the main
 * thread are visible. Bounded to fit the server's 512 KiB `threads` limit.
 */
internal object ThreadDump {
    const val MAX_THREADS = 100
    const val MAX_FRAMES_PER_THREAD = 40
    const val MAX_TOTAL_FRAMES = 1_500

    /**
     * [crashed]: the thread the failure happened on, listed first (with
     * [crashedFrames] as its stack, or none when the exception already holds
     * it). The main thread comes next, then the others by id.
     */
    fun threads(
        crashed: Thread,
        current: Thread,
        inApp: InAppRules,
        crashedFrames: List<Map<String, Any?>>? = null,
        stacks: Map<Thread, Array<StackTraceElement>> = allStacks(),
        mainThread: Thread? = mainThread(),
    ): List<Map<String, Any?>> {
        val others = stacks.keys.filter { it !== crashed }
            .sortedWith(compareBy<Thread>({ it !== mainThread }, { it.id }))
        val out = ArrayList<Map<String, Any?>>()
        out.add(entry(crashed, crashed = true, current = crashed === current, frames = crashedFrames))
        var budget = MAX_TOTAL_FRAMES - (crashedFrames?.size ?: 0)
        for (t in others) {
            if (out.size >= MAX_THREADS) break
            val stack = stacks[t] ?: continue
            val take = minOf(stack.size, MAX_FRAMES_PER_THREAD, budget.coerceAtLeast(0))
            val frames = ExceptionMapper.frames(stack.copyOfRange(0, take), inApp)
            budget -= take
            out.add(entry(t, crashed = false, current = t === current, frames = frames))
        }
        return out
    }

    private fun entry(t: Thread, crashed: Boolean, current: Boolean, frames: List<Map<String, Any?>>?): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to t.id, "name" to t.name, "crashed" to crashed, "current" to current).also {
            if (frames != null) it["stacktrace"] = linkedMapOf("frames" to frames)
        }

    private fun allStacks(): Map<Thread, Array<StackTraceElement>> = try {
        Thread.getAllStackTraces()
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun mainThread(): Thread? = try {
        android.os.Looper.getMainLooper()?.thread
    } catch (_: Throwable) {
        null
    }
}
