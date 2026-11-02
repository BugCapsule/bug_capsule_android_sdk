package dev.bugcapsule.android.core

/** The checkpoint plus the events that follow it: what a capsule replays. */
class RecordedWindow(
    val checkpoint: Checkpoint,
    val events: List<CapsuleEvent>,
    /** Events evicted from the buffer over the whole session. */
    val droppedEvents: Int,
    /** No retained checkpoint covers the oldest retained event. */
    val hasGap: Boolean,
    /** Events of [events] whose bodies or payloads were dropped to stay within the retained-byte budget. */
    val truncatedBodies: Int = 0,
    /** Events evicted over the session to stay within the retained-byte budget. */
    val budgetEvicted: Int = 0,
)

/**
 * Bounded event buffer with checkpoints (port of `flight_recorder.dart`).
 * While nothing was evicted the window is the launch checkpoint plus every
 * event; afterwards it starts at the earliest periodic checkpoint taken after
 * the last evicted event. Not thread-safe: the runtime serializes access.
 *
 * Retained events also share a byte budget ([maxRetainedBytes], estimated by
 * [RetainedBytes]): over it, the oldest HTTP bodies and interaction payloads
 * are dropped first, then the oldest events.
 */
class FlightRecorder @JvmOverloads constructor(
    launch: Checkpoint,
    val maxEvents: Int = 20_000,
    val windowMs: Long = 5 * 60 * 1000L,
    val maxCheckpoints: Int = 4,
    val maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
) {
    init {
        require(launch.kind == CheckpointKind.LAUNCH)
    }

    private var launch: Checkpoint = launch
    private val events = ArrayDeque<CapsuleEvent>()
    private val sizes = ArrayDeque<Long>() // RetainedBytes of each event, same order
    private val periodic = ArrayList<Checkpoint>()
    private var seq = 0L
    private var dropped = 0
    private var lastEvictedSeq = 0L
    private var bytes = 0L

    // Index of the oldest event whose body was not looked at yet.
    private var stripFrom = 0
    private val stripped = HashSet<Long>()

    /** Events evicted to stay within [maxRetainedBytes] over the session. */
    var budgetEvicted = 0
        private set

    /** Estimated bytes of the retained events. */
    val retainedBytes: Long get() = bytes

    /** Events removed by [rewrite] over the session. */
    var rewriteDropped = 0
        private set

    val lastSeq: Long get() = seq
    val eventCount: Int get() = events.size
    val droppedEvents: Int get() = dropped
    val launchCoversWindow: Boolean get() = dropped == 0

    fun add(t: Long, type: String, data: Map<String, Any?> = emptyMap()): CapsuleEvent {
        val event = CapsuleEvent(++seq, t, type, data)
        events.addLast(event)
        val size = RetainedBytes.of(event)
        sizes.addLast(size)
        bytes += size
        evict(t)
        return event
    }

    fun addCheckpoint(checkpoint: Checkpoint) {
        require(checkpoint.kind == CheckpointKind.PERIODIC)
        periodic.add(checkpoint)
        prune()
    }

    /** Rewrites retained events/checkpoints in place; returning null drops an event. */
    fun rewrite(event: ((CapsuleEvent) -> CapsuleEvent?)? = null, checkpoint: ((Checkpoint) -> Checkpoint)? = null) {
        if (event != null) {
            val updated = ArrayList<CapsuleEvent>(events.size)
            for (e in events) {
                val r = event(e)
                if (r == null) rewriteDropped++ else updated.add(r)
            }
            events.clear()
            events.addAll(updated)
            sizes.clear()
            bytes = 0
            for (e in events) {
                val size = RetainedBytes.of(e)
                sizes.addLast(size)
                bytes += size
            }
            stripped.retainAll(events.mapTo(HashSet()) { it.seq })
            stripFrom = 0
        }
        if (checkpoint != null) {
            launch = checkpoint(launch)
            for (i in periodic.indices) periodic[i] = checkpoint(periodic[i])
        }
    }

    fun window(): RecordedWindow {
        if (launchCoversWindow) return RecordedWindow(launch, events.toList(), 0, false, stripped.size, budgetEvicted)
        val chosen = windowCheckpoint()
        val from = chosen.afterSeq
        val kept = events.filter { it.seq > from }
        return RecordedWindow(
            chosen, kept, dropped, chosen.afterSeq < lastEvictedSeq,
            kept.count { it.seq in stripped }, budgetEvicted,
        )
    }

    /** The checkpoint [window] starts at (cheap: no copy). */
    fun windowCheckpoint(): Checkpoint {
        if (launchCoversWindow) return launch
        return periodic.firstOrNull { it.afterSeq >= lastEvictedSeq } ?: periodic.lastOrNull() ?: launch
    }

    /** `afterSeq` of [windowCheckpoint]: what the crash journal must keep events after. */
    val windowStartSeq: Long get() = windowCheckpoint().afterSeq

    /** Every retained checkpoint (launch first while it covers the window) and event: the crash journal's content after a [rewrite]. */
    fun retained(): Pair<List<Checkpoint>, List<CapsuleEvent>> =
        ((if (launchCoversWindow) listOf(launch) else emptyList()) + periodic) to events.toList()

    private fun evict(now: Long) {
        while (events.isNotEmpty() && (events.size > maxEvents || events.first().t < now - windowMs)) removeOldest()
        // Over the byte budget: the oldest bodies go first, then the oldest events.
        while (bytes > maxRetainedBytes && stripOldestBody()) Unit
        while (bytes > maxRetainedBytes && events.isNotEmpty()) {
            removeOldest()
            budgetEvicted++
        }
        if (dropped > 0) prune()
    }

    private fun removeOldest() {
        val e = events.removeFirst()
        bytes -= sizes.removeFirst()
        lastEvictedSeq = e.seq
        stripped.remove(e.seq)
        if (stripFrom > 0) stripFrom--
        dropped++
    }

    /** Drops the bodies of the oldest event that has some; false when none is left. */
    private fun stripOldestBody(): Boolean {
        while (stripFrom < events.size) {
            val i = stripFrom++
            val e = events[i]
            val lighter = RetainedBytes.withoutBodies(e) ?: continue
            val size = RetainedBytes.of(lighter)
            events[i] = lighter
            bytes += size - sizes[i]
            sizes[i] = size
            stripped.add(e.seq)
            return true
        }
        return false
    }

    private fun prune() {
        val firstValid = periodic.indexOfFirst { it.afterSeq >= lastEvictedSeq }
        val keepFrom = if (firstValid == -1) periodic.size - 1 else firstValid
        if (keepFrom > 0) periodic.subList(0, keepFrom).clear()
        while (periodic.size > maxCheckpoints) periodic.removeAt(0)
    }
}

/** Default of [FlightRecorder.maxRetainedBytes]: 8 MiB of retained event payload. */
const val DEFAULT_MAX_RETAINED_BYTES: Long = 8L * 1024 * 1024

/**
 * Estimated in-memory payload of recorded events (strings count their
 * length, binary payloads their size), and the lighter copy of an event
 * once its HTTP bodies or interaction payloads are dropped.
 */
object RetainedBytes {
    private const val EVENT_OVERHEAD = 48L
    private const val ENTRY_OVERHEAD = 16L

    fun of(event: CapsuleEvent): Long = EVENT_OVERHEAD + event.type.length + of(event.data)

    fun of(value: Any?): Long = when (value) {
        null -> 4
        is String -> value.length.toLong()
        is Map<*, *> -> value.entries.sumOf { (k, v) -> ENTRY_OVERHEAD + of(k) + of(v) }
        is Iterable<*> -> value.sumOf { 8 + of(it) }
        is Array<*> -> value.sumOf { 8 + of(it) }
        is ByteArray -> value.size.toLong()
        else -> 8
    }

    /** [event] without its HTTP bodies or interaction payloads; null when it has none to drop. */
    fun withoutBodies(event: CapsuleEvent): CapsuleEvent? = when {
        event.type == EventTypes.HTTP ->
            if (event.data["requestBody"] == null && event.data["responseBody"] == null) null else event.copy(data = withoutHttpBodies(event.data))
        InteractionTypes.isInteraction(event.type) ->
            if (InteractionTypes.PAYLOAD_FIELDS.none { J.obj(event.data[it])?.get("mode").let { m -> m != null && m != "omitted" } }) {
                null
            } else {
                event.copy(data = withoutPayloads(event.data))
            }
        else -> null
    }

    /** HTTP event data with both bodies omitted (the request key is kept: replay still matches the request). */
    fun withoutHttpBodies(data: Map<String, Any?>): Map<String, Any?> {
        val d = LinkedHashMap(data)
        d["requestBody"] = null
        d["responseBody"] = null
        d["bodyEncoding"] = "omitted"
        if (d["requestEncoding"] != null && d["requestEncoding"] != "json") d["requestEncoding"] = "omitted"
        return d
    }

    /** Payload objects of an interaction event become `omitted`; their kind is kept. */
    fun withoutPayloads(data: Map<String, Any?>): Map<String, Any?> {
        val d = LinkedHashMap(data)
        for (field in InteractionTypes.PAYLOAD_FIELDS) {
            val p = J.obj(d[field]) ?: continue
            d[field] = linkedMapOf<String, Any?>("mode" to "omitted", "replayable" to false).also { out ->
                p["kind"]?.let { out["kind"] = it }
            }
        }
        return d
    }
}

/** When replay may jump over an idle stretch before an input (port of `idle_compression.dart`). */
class IdleCompressor @JvmOverloads constructor(
    val events: List<CapsuleEvent>,
    val minGapMs: Long = 1500,
    val collapseToMs: Long = 300,
) {
    /** Recorded time from which the recording is idle until the input at [to]; null when not longer than [minGapMs]. */
    fun idleSince(from: Long, to: Long): Long? {
        if (to - from <= minGapMs) return null
        var since = from
        for (e in events) {
            // Written after each checkpoint, so they say nothing about the app (emergency-client-v1 §7.8).
            if (e.type == EventTypes.EMERGENCY_DECISION) continue
            val start = if (e.type == EventTypes.HTTP) e.t - (J.long(e.data["durationMs"]) ?: 0) else e.t
            if (start >= to) continue
            val end = if (e.t < to) e.t else to
            if (end > since) since = end
        }
        return if (to - since > minGapMs) since else null
    }

    fun maySkip(now: Long, idleSince: Long, busy: Int, quietMs: Long): Boolean =
        busy == 0 && quietMs >= collapseToMs && now >= idleSince + collapseToMs
}
