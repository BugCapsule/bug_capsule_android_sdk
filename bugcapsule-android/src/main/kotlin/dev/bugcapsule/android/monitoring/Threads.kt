package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json

/**
 * `threads` of an event (monitoring-v1 §2): every live JVM thread's stack
 * when the app crashes or hangs, so deadlocks and work blocking the main
 * thread are visible. Bounded to fit the server's 512 KiB `threads` limit.
 */
internal object ThreadDump {
    const val MAX_THREADS = 100
    const val MAX_FRAMES_PER_THREAD = 40
    const val MAX_TOTAL_FRAMES = 1_500

    /** The server rejects the whole event above 512 KiB of `threads`: stay well below. */
    const val MAX_THREADS_BYTES = 448 * 1024

    /**
     * Keeps any thread list (ours, a tombstone's, an ANR dump's) within the
     * server's limits: [MAX_THREADS], frames closest to the top of each stack
     * ([ExceptionMapper.MAX_FRAMES] for the crashed thread,
     * [MAX_FRAMES_PER_THREAD] for the others, [MAX_TOTAL_FRAMES] in all), then
     * the last threads go until the JSON fits [MAX_THREADS_BYTES].
     */
    fun fit(threads: List<Map<String, Any?>>): List<Map<String, Any?>> {
        var budget = MAX_TOTAL_FRAMES
        val out = ArrayList<Map<String, Any?>>()
        for (t in threads.take(MAX_THREADS)) {
            val frames = J.list(J.obj(t["stacktrace"])?.get("frames"))
            if (frames == null) {
                out.add(t)
                continue
            }
            val cap = if (t["crashed"] == true) ExceptionMapper.MAX_FRAMES else MAX_FRAMES_PER_THREAD
            val keep = minOf(frames.size, cap, budget.coerceAtLeast(0))
            budget -= keep
            // Frames are oldest first: keep the newest.
            out.add(LinkedHashMap(t).also { it["stacktrace"] = linkedMapOf("frames" to frames.takeLast(keep)) })
        }
        while (out.size > 1 && Json.stringify(out).toByteArray(Charsets.UTF_8).size > MAX_THREADS_BYTES) out.removeAt(out.size - 1)
        return out
    }

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
