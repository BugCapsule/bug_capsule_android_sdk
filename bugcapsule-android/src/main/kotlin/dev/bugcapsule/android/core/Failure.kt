package dev.bugcapsule.android.core

enum class FailureKind(val wire: String) {
    EXCEPTION("exception"), ASSERTION("assertion"), MANUAL("manual"),

    /** The process died: native signal, ANR kill, low-memory kill (crash-capsules §3; capsule format v2 only). */
    CRASH("crash");

    companion object {
        @JvmStatic
        fun parse(wire: String): FailureKind = entries.first { it.wire == wire }
    }
}

/** A machine-checkable failure: what replay and verify compare against. */
data class FailureRecord(
    val kind: FailureKind,
    val signature: String,
    val type: String,
    val message: String,
    val t: Long,
    val stack: String? = null,
    val assertionId: String? = null,
    /** Kind `crash`: `{mechanism, signal?, inAppFrame?}` (crash-capsules §3). */
    val crash: Map<String, Any?>? = null,
) {
    fun shift(offsetMs: Long) = copy(t = t - offsetMs)

    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind.wire,
        "signature" to signature,
        "type" to type,
        "message" to message,
        "stack" to stack,
        "assertionId" to assertionId,
    ).also {
        if (crash != null) it["crash"] = crash
        it["t"] = t
    }

    companion object {
        /**
         * runtimes-v1 §5: `type` is `Throwable.javaClass.simpleName`, `message`
         * is `message ?: ""`. [scrub] removes credentials before anything is
         * derived from the text; capture and replay pass the same function.
         */
        @JvmStatic
        @JvmOverloads
        fun exception(error: Throwable, t: Long, scrub: ((String) -> String)? = null): FailureRecord {
            val type = typeName(error)
            val clean: (String) -> String = { s -> scrub?.invoke(s) ?: s }
            val message = clean(safeMessage(error))
            val stack = try {
                error.stackTraceToString()
            } catch (_: Throwable) {
                null
            }
            return FailureRecord(
                kind = FailureKind.EXCEPTION,
                signature = FailureSignature.compute(FailureKind.EXCEPTION, type, message),
                type = type,
                message = truncate(message, 2000),
                stack = stack?.let { clean(truncate(it, 8000)) },
                t = t,
            )
        }

        /** A failure raised through a named assertion (`BugCapsule.fail("id")`). */
        @JvmStatic
        @JvmOverloads
        fun assertion(assertionId: String, message: String, t: Long, scrub: ((String) -> String)? = null) =
            FailureRecord(
                kind = FailureKind.ASSERTION,
                signature = FailureSignature.compute(FailureKind.ASSERTION, assertionId, ""),
                type = assertionId,
                message = truncate(scrub?.invoke(message) ?: message, 2000),
                assertionId = assertionId,
                t = t,
            )

        /**
         * A crash that ended the process (crash-capsules §3): [type] is the
         * signal name, `ApplicationNotResponding` or `OutOfMemory`; the
         * signature is `crash:<type>` (no addresses or lines: replay runs
         * another build).
         */
        @JvmStatic
        @JvmOverloads
        fun crash(
            type: String,
            message: String,
            t: Long,
            mechanism: String,
            signal: Int? = null,
            inAppFrame: String? = null,
            stack: String? = null,
        ) = FailureRecord(
            kind = FailureKind.CRASH,
            signature = "crash:$type",
            type = type,
            message = truncate(message, 2000),
            stack = stack?.let { truncate(it, 8000) },
            t = t,
            crash = linkedMapOf<String, Any?>("mechanism" to mechanism).also {
                if (signal != null) it["signal"] = signal
                if (inAppFrame != null) it["inAppFrame"] = truncate(inAppFrame, 500)
            },
        )

        @JvmStatic
        fun typeName(error: Throwable): String {
            val simple = error.javaClass.simpleName
            if (simple.isNotEmpty()) return simple
            return error.javaClass.name.substringAfterLast('.')
        }

        private fun safeMessage(error: Throwable): String = try {
            error.message ?: ""
        } catch (_: Throwable) {
            ""
        }

        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = FailureRecord(
            kind = FailureKind.parse(j["kind"] as String),
            signature = j["signature"] as String,
            type = j["type"] as String,
            message = J.str(j["message"]) ?: "",
            stack = J.str(j["stack"]),
            assertionId = J.str(j["assertionId"]),
            t = J.long(j["t"]) ?: 0,
            crash = J.obj(j["crash"]),
        )

        private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max) + "…"
    }
}

/** `<kind>:<type>:<normalized first line of the message>` (capsule-v1 *Failure*). */
object FailureSignature {
    private val hex = Regex("(?<![A-Za-z0-9_])(0x)?[0-9a-fA-F]{8,}(?![A-Za-z0-9_])")
    private val digits = Regex("[0-9]+")
    private val space = Regex("[${DartText.WS_CLASS}]+")

    @JvmStatic
    fun compute(kind: FailureKind, type: String, message: String): String {
        val firstLine = message.split('\n').first()
        var normalized = DartText.trim(
            firstLine.replace(hex, "#").replace(digits, "#").replace(space, " "),
        )
        if (normalized.length > 200) normalized = normalized.substring(0, 200)
        return "${kind.wire}:$type:$normalized"
    }
}

/** String helpers with Dart (JavaScript) whitespace semantics. */
object DartText {
    /** Characters JavaScript's (and Dart's) `\s` matches, as a regex class body. */
    const val WS_CLASS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    private fun isTrimmable(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', ' ', ' ',
        ' ', ' ', ' ', ' ', '　', '\uFEFF',
        -> true
        else -> c in ' '..' '
    }

    @JvmStatic
    fun trim(s: String): String = trimRight(trimLeft(s))

    @JvmStatic
    fun trimLeft(s: String): String {
        var i = 0
        while (i < s.length && isTrimmable(s[i])) i++
        return if (i == 0) s else s.substring(i)
    }

    @JvmStatic
    fun trimRight(s: String): String {
        var i = s.length
        while (i > 0 && isTrimmable(s[i - 1])) i--
        return if (i == s.length) s else s.substring(0, i)
    }
}
