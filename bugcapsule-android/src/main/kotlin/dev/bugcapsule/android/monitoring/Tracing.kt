package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.android.internal.Cancellable
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

/** Span / transaction status (monitoring-v1 §8); HTTP codes map as in OpenTelemetry. */
enum class SpanStatus(val wire: String) {
    OK("ok"), CANCELLED("cancelled"), UNKNOWN("unknown"), INVALID_ARGUMENT("invalid_argument"),
    DEADLINE_EXCEEDED("deadline_exceeded"), NOT_FOUND("not_found"), ALREADY_EXISTS("already_exists"),
    PERMISSION_DENIED("permission_denied"), RESOURCE_EXHAUSTED("resource_exhausted"),
    FAILED_PRECONDITION("failed_precondition"), ABORTED("aborted"), OUT_OF_RANGE("out_of_range"),
    UNIMPLEMENTED("unimplemented"), INTERNAL_ERROR("internal_error"), UNAVAILABLE("unavailable"),
    DATA_LOSS("data_loss"), UNAUTHENTICATED("unauthenticated");

    companion object {
        @JvmStatic
        fun fromHttpStatus(code: Int): SpanStatus = when {
            code in 100..399 -> OK
            code == 401 -> UNAUTHENTICATED
            code == 403 -> PERMISSION_DENIED
            code == 404 -> NOT_FOUND
            code == 409 -> ALREADY_EXISTS
            code == 412 -> FAILED_PRECONDITION
            code == 416 -> OUT_OF_RANGE
            code == 429 -> RESOURCE_EXHAUSTED
            code == 499 -> CANCELLED
            code in 400..499 -> INVALID_ARGUMENT
            code == 501 -> UNIMPLEMENTED
            code == 503 -> UNAVAILABLE
            code == 504 -> DEADLINE_EXCEEDED
            code in 500..599 -> INTERNAL_ERROR
            else -> UNKNOWN
        }
    }
}

/** What spans need from the SDK (clock, scheduling, delivery). */
internal interface TracingHub {
    fun nowMs(): Long
    fun nanoTime(): Long
    fun schedule(delayMs: Long, task: Runnable): Cancellable
    fun onTransactionFinished(transaction: Transaction)
    fun baggage(span: Span): String
}

/**
 * A timed operation inside a [Transaction]. Create children with
 * [startChild]; always [finish] (a transaction is sent once it finishes,
 * with every finished child). Thread-safe.
 */
open class Span internal constructor(
    internal val hub: TracingHub,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    op: String,
    description: String?,
    /** The sampling decision of the whole trace; unsampled spans are timed but never sent. */
    val sampled: Boolean,
    private val owner: Transaction?,
    startMs: Long?,
) {
    protected val lock = Any()

    @Volatile
    var op: String = op
        private set

    @Volatile
    var description: String? = description
        private set

    @Volatile
    var status: SpanStatus? = null
        private set
    val startTimestampMs: Long = startMs ?: hub.nowMs()
    private val startNanos: Long = hub.nanoTime() - (hub.nowMs() - startTimestampMs) * 1_000_000

    @Volatile
    var endTimestampMs: Long? = null
        private set
    private val data = LinkedHashMap<String, Any?>()
    private val tags = LinkedHashMap<String, String>()

    /** Not part of its transaction (started after it finished, or past [Transaction.MAX_SPANS]). */
    @Volatile
    internal var detached = false

    val isFinished: Boolean get() = endTimestampMs != null

    /** The transaction this span belongs to (itself for a transaction). */
    open val transaction: Transaction get() = owner!!

    /** Starts a child span (finished children are sent with the transaction). */
    @JvmOverloads
    fun startChild(op: String, description: String? = null): Span = transaction.newChild(this, op, description, null)

    internal fun startChildAt(op: String, description: String?, startMs: Long): Span =
        transaction.newChild(this, op, description, startMs)

    fun setData(key: String, value: Any?) = synchronized(lock) { data[key] = value }

    fun setTag(key: String, value: String) = synchronized(lock) { tags[key] = value }

    fun setStatus(status: SpanStatus) {
        this.status = status
    }

    fun setDescription(description: String?) {
        this.description = description
    }

    fun setOperation(op: String) {
        this.op = op
    }

    /** Finishes with the status set before (default `ok`). Later calls are ignored. */
    open fun finish() = finish(status ?: SpanStatus.OK)

    open fun finish(status: SpanStatus) = finishAt(null, status)

    /** Finishes at the wall-clock time [endMs] (now when null). */
    internal open fun finishAt(endMs: Long?, status: SpanStatus): Boolean {
        synchronized(lock) {
            if (endTimestampMs != null) return false
            this.status = status
            endTimestampMs = endMs ?: (startTimestampMs + (hub.nanoTime() - startNanos) / 1_000_000)
        }
        owner?.childFinished(this)
        return true
    }

    /** Elapsed time so far (or the duration once finished), in ms. */
    val durationMs: Long get() = (endTimestampMs ?: (startTimestampMs + (hub.nanoTime() - startNanos) / 1_000_000)) - startTimestampMs

    /** `traceparent` for an outgoing request made inside this span (W3C trace context). */
    fun toTraceparent(): String = TraceHeaders.traceparent(traceId, spanId, sampled)

    /** `baggage` entries of this trace (`bugcapsule-trace_id`, release, environment, sample rate, sampled). */
    fun toBaggage(): String = hub.baggage(this)

    internal open fun toJson(): Map<String, Any?> = synchronized(lock) {
        linkedMapOf<String, Any?>(
            "spanId" to spanId,
            "parentSpanId" to parentSpanId,
            "op" to op,
            "description" to description,
            "startTimestamp" to Iso8601.format(startTimestampMs),
            "timestamp" to Iso8601.format(endTimestampMs ?: startTimestampMs),
            "status" to (status ?: SpanStatus.OK).wire,
        ).also {
            if (data.isNotEmpty()) it["data"] = LinkedHashMap(data)
            if (tags.isNotEmpty()) it["tags"] = LinkedHashMap(tags)
        }
    }

    internal fun dataSnapshot(): Map<String, Any?> = synchronized(lock) { LinkedHashMap(data) }

    internal fun tagsSnapshot(): Map<String, String> = synchronized(lock) { LinkedHashMap(tags) }
}

/**
 * The root span of a trace on this device: a screen load, an app start, a
 * user action or a custom operation. Sent when finished, if sampled.
 */
class Transaction internal constructor(
    hub: TracingHub,
    name: String,
    op: String,
    traceId: String,
    spanId: String,
    parentSpanId: String?,
    sampled: Boolean,
    /** The rate the decision was made with (propagated in `baggage`). */
    val sampleRate: Double?,
    startMs: Long?,
) : Span(hub, traceId, spanId, parentSpanId, op, null, sampled, null, startMs) {
    @Volatile
    var name: String = name
        private set
    private val children = ArrayList<Span>()
    private val measurements = LinkedHashMap<String, Pair<Number, String>>()
    private var active = 0
    private var idleMs: Long? = null
    private var idleTimer: Cancellable? = null
    private var deadlineTimer: Cancellable? = null
    private val finishListeners = ArrayList<(Transaction) -> Unit>()

    override val transaction: Transaction get() = this

    fun setName(name: String) {
        this.name = name
    }

    /** Adds a measurement (`app_start_cold`, `time_to_initial_display`, `frames_slow`, custom ...). */
    fun setMeasurement(name: String, value: Number, unit: String) = synchronized(lock) { measurements[name] = value to unit }

    internal fun measurement(name: String): Number? = synchronized(lock) { measurements[name]?.first }

    internal fun newChild(parent: Span, op: String, description: String?, startMs: Long?): Span {
        val child = Span(hub, traceId, Ids.spanId(), parent.spanId, op, description, sampled, this, startMs)
        synchronized(lock) {
            if (isFinished || children.size >= MAX_SPANS) return child.also { it.detached = true }
            children.add(child)
            active++
            idleTimer?.cancel()
            idleTimer = null
        }
        return child
    }

    internal fun childFinished(child: Span) {
        if (child.detached) return
        synchronized(lock) {
            active--
            if (active == 0 && idleMs != null && !isFinished) scheduleIdle()
        }
    }

    /**
     * Finishes automatically once no child span has been running for
     * [idleTimeoutMs] (end = the last child's end), and at the latest after
     * [deadlineMs] (unfinished children become `deadline_exceeded`).
     */
    internal fun finishWhenIdle(idleTimeoutMs: Long, deadlineMs: Long) = synchronized(lock) {
        idleMs = idleTimeoutMs
        deadlineTimer?.cancel()
        deadlineTimer = hub.schedule(deadlineMs) { finish(SpanStatus.DEADLINE_EXCEEDED) }
        if (active == 0) scheduleIdle()
    }

    private fun scheduleIdle() {
        val delay = idleMs ?: return
        idleTimer?.cancel()
        idleTimer = hub.schedule(delay) {
            val end = synchronized(lock) { children.mapNotNull { it.endTimestampMs }.maxOrNull() }
            finishAt(end ?: hub.nowMs(), status ?: SpanStatus.OK)
        }
    }

    internal fun doOnFinish(listener: (Transaction) -> Unit) = synchronized(lock) { finishListeners.add(listener) }

    override fun finishAt(endMs: Long?, status: SpanStatus): Boolean {
        if (isFinished) return false
        val listeners = synchronized(lock) {
            idleTimer?.cancel()
            deadlineTimer?.cancel()
            finishListeners.toList()
        }
        // Listeners add measurements (frames) and may still finish children.
        for (l in listeners) {
            try {
                l(this)
            } catch (_: Throwable) {
            }
        }
        if (!super.finishAt(endMs, status)) return false
        val end = endTimestampMs!!
        val open = synchronized(lock) { children.filter { !it.isFinished } }
        val childStatus = if (status == SpanStatus.DEADLINE_EXCEEDED) SpanStatus.DEADLINE_EXCEEDED else SpanStatus.CANCELLED
        for (c in open) c.finishAt(maxOf(end, c.startTimestampMs), childStatus)
        hub.onTransactionFinished(this)
        return true
    }

    internal fun childrenSnapshot(): List<Span> = synchronized(lock) { children.toList() }

    internal fun measurementsJson(): Map<String, Any?> = synchronized(lock) {
        measurements.mapValues { (_, v) -> linkedMapOf("value" to v.first, "unit" to v.second) }
    }

    companion object {
        const val MAX_SPANS = 1000
    }
}

/** An incoming trace to continue ([dev.bugcapsule.android.BugCapsule.continueTrace]). */
class TraceContext internal constructor(
    val traceId: String,
    val parentSpanId: String?,
    val parentSampled: Boolean?,
    val sampleRate: Double?,
)

internal object Ids {
    fun traceId(): String = hex(16)
    fun spanId(): String = hex(8)

    private fun hex(bytes: Int): String {
        val r = ThreadLocalRandom.current()
        while (true) {
            val sb = StringBuilder(bytes * 2)
            var zero = true
            repeat(bytes) {
                val b = r.nextInt(256)
                if (b != 0) zero = false
                sb.append(HEX[b ushr 4]).append(HEX[b and 15])
            }
            if (!zero) return sb.toString()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** W3C `traceparent` and BugCapsule `baggage` entries (monitoring-v1 §8, distributed tracing). */
object TraceHeaders {
    const val TRACEPARENT = "traceparent"
    const val BAGGAGE = "baggage"
    private const val PREFIX = "bugcapsule-"
    private val TRACEPARENT_RE = Regex("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$")

    class Traceparent(val traceId: String, val spanId: String, val sampled: Boolean)

    @JvmStatic
    fun traceparent(traceId: String, spanId: String, sampled: Boolean?): String =
        "00-$traceId-$spanId-${if (sampled == true) "01" else "00"}"

    @JvmStatic
    fun parseTraceparent(value: String?): Traceparent? {
        val m = TRACEPARENT_RE.matchEntire(value?.trim()?.lowercase() ?: return null) ?: return null
        val (version, traceId, spanId, flags) = m.destructured
        if (version == "ff" || traceId.all { it == '0' } || spanId.all { it == '0' }) return null
        return Traceparent(traceId, spanId, (flags.toInt(16) and 1) == 1)
    }

    @JvmStatic
    fun baggage(traceId: String, release: String?, environment: String?, sampleRate: Double?, sampled: Boolean?): String {
        val entries = LinkedHashMap<String, String>()
        entries["trace_id"] = traceId
        if (release != null) entries["release"] = release
        if (environment != null) entries["environment"] = environment
        if (sampleRate != null) entries["sample_rate"] = formatRate(sampleRate)
        if (sampled != null) entries["sampled"] = sampled.toString()
        return entries.entries.joinToString(",") { "$PREFIX${it.key}=${encode(it.value)}" }
    }

    /** Keeps the app's own entries of [existing], replacing any `bugcapsule-*` entries with [ours]. */
    @JvmStatic
    fun mergeBaggage(existing: String?, ours: String): String {
        val kept = (existing ?: "").split(',').map { it.trim() }.filter {
            it.isNotEmpty() && !it.substringBefore('=').trim().startsWith(PREFIX)
        }
        return (kept + ours).joinToString(",")
    }

    /** The `bugcapsule-*` entries of a `baggage` header, keyed without the prefix. */
    @JvmStatic
    fun parseBaggage(value: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in (value ?: "").split(',')) {
            val kv = entry.substringBefore(';').trim()
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val key = kv.substring(0, i).trim()
            if (!key.startsWith(PREFIX)) continue
            out[key.removePrefix(PREFIX)] = try {
                java.net.URLDecoder.decode(kv.substring(i + 1).trim().replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                kv.substring(i + 1).trim()
            }
        }
        return out
    }

    private fun formatRate(rate: Double): String {
        val s = rate.toBigDecimal().stripTrailingZeros().toPlainString()
        return if (s.contains('.') || s.contains('E')) s else "$s.0"
    }

    /** Percent-encodes what a W3C baggage value may not contain. */
    private fun encode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ok = c == 0x21 || c in 0x23..0x2B || c in 0x2D..0x3A || c in 0x3C..0x5B || c in 0x5D..0x7E
            if (ok && c != '%'.code) sb.append(c.toChar()) else sb.append('%').append(String.format("%02X", c))
        }
        return sb.toString()
    }
}

/** `tracePropagationTargets` matching: plain strings are substrings, `/.../` strings are regular expressions. */
object PropagationTargets {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pattern>()

    @JvmStatic
    fun matches(url: String, targets: List<String>): Boolean = targets.any { target ->
        if (target.length > 2 && target.startsWith("/") && target.endsWith("/")) {
            val p = try {
                cache.getOrPut(target) { Pattern.compile(target.substring(1, target.length - 1)) }
            } catch (_: Exception) {
                null
            }
            p != null && p.matcher(url).find()
        } else {
            target.isNotEmpty() && url.contains(target)
        }
    }
}

/** Spans created while monitoring is off: timed, never sent. */
internal object NoopHub : TracingHub {
    override fun nowMs() = System.currentTimeMillis()
    override fun nanoTime() = System.nanoTime()
    override fun schedule(delayMs: Long, task: Runnable) = Cancellable {}
    override fun onTransactionFinished(transaction: Transaction) {}
    override fun baggage(span: Span) = TraceHeaders.baggage(span.traceId, null, null, null, span.sampled)
}

/** Spans made current on a thread with [dev.bugcapsule.android.BugCapsule.startSpan]. */
internal object CurrentSpan {
    private val local = ThreadLocal<Span?>()
    fun get(): Span? = local.get()?.takeIf { !it.isFinished }
    fun set(span: Span?) = local.set(span)
}
