package dev.bugcapsule.android.monitoring

import java.util.IdentityHashMap

/**
 * `inApp` of a frame: [excludes] win over [includes] (prefix matches on the
 * frame's class / module). Defaults: the app package is included.
 */
internal class InAppRules(includes: List<String>, excludes: List<String>) {
    private val includes = includes.filter { it.isNotBlank() }
    private val excludes = excludes.filter { it.isNotBlank() }

    fun isInApp(module: String?): Boolean {
        if (module == null) return false
        if (excludes.any { module.startsWith(it) }) return false
        return includes.any { module.startsWith(it) }
    }
}

/** A JVM [Throwable] as `exception.values` (monitoring-v1 §2). */
internal object ExceptionMapper {
    const val MAX_VALUES = 10
    const val MAX_FRAMES = 250

    class Mechanism(val type: String, val handled: Boolean, val data: Map<String, Any?> = emptyMap()) {
        fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("type" to type, "handled" to handled).also {
            if (data.isNotEmpty()) it["data"] = data
        }
    }

    /**
     * The cause chain, innermost cause first and the thrown exception last
     * (Sentry order). The mechanism goes on the thrown exception; causes get
     * `chained` mechanisms so the server knows they were not thrown alone.
     */
    fun values(error: Throwable, mechanism: Mechanism, inApp: InAppRules, scrub: (String) -> String): List<Map<String, Any?>> {
        val chain = ArrayList<Throwable>()
        val seen = IdentityHashMap<Throwable, Boolean>()
        var e: Throwable? = error
        while (e != null && seen.put(e, true) == null && chain.size < MAX_VALUES) {
            chain.add(e)
            e = e.cause
        }
        return chain.mapIndexed { i, t ->
            val m = if (i == 0) mechanism else Mechanism("chained", mechanism.handled, linkedMapOf("source" to "cause"))
            value(t, m, inApp, scrub)
        }.reversed()
    }

    fun value(t: Throwable, mechanism: Mechanism, inApp: InAppRules, scrub: (String) -> String): Map<String, Any?> {
        val cls = t.javaClass.name
        val message = try {
            t.message
        } catch (_: Throwable) {
            null
        }
        return linkedMapOf(
            "type" to cls.substringAfterLast('.'),
            "value" to message?.let { cut(scrub(it), 8192) },
            "module" to cls.substringBeforeLast('.', "").ifEmpty { null },
            "mechanism" to mechanism.toJson(),
            "stacktrace" to linkedMapOf("frames" to frames(safeStack(t), inApp)),
        )
    }

    private fun safeStack(t: Throwable): Array<StackTraceElement> = try {
        t.stackTrace
    } catch (_: Throwable) {
        emptyArray()
    }

    /** JVM stacks list the crashing frame first; the contract wants it last. */
    fun frames(stack: Array<StackTraceElement>, inApp: InAppRules): List<Map<String, Any?>> {
        val kept = if (stack.size > MAX_FRAMES) stack.copyOfRange(0, MAX_FRAMES) else stack
        return kept.reversed().map { frame(it, inApp) }
    }

    fun frame(el: StackTraceElement, inApp: InAppRules): Map<String, Any?> = linkedMapOf<String, Any?>(
        "function" to el.methodName,
        "module" to el.className,
    ).also {
        if (el.fileName != null) it["filename"] = el.fileName
        if (el.lineNumber > 0) it["lineno"] = el.lineNumber
        if (el.isNativeMethod) it["native"] = true
        it["inApp"] = inApp.isInApp(el.className)
    }

    /** The calling thread's stack without the SDK's own frames (for messages and assertions). */
    fun currentFrames(inApp: InAppRules): List<Map<String, Any?>> {
        val stack = Thread.currentThread().stackTrace.dropWhile {
            it.className.startsWith("java.lang.Thread") || it.className.startsWith("dalvik.system.VMStack") ||
                it.className.startsWith("dev.bugcapsule.android.")
        }
        return frames(stack.toTypedArray(), inApp)
    }

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)
}
