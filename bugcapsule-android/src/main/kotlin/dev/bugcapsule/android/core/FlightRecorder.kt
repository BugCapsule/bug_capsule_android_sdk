package dev.bugcapsule.android.core

/** The checkpoint plus the events that follow it: what a capsule replays. */
class RecordedWindow(
    val checkpoint: Checkpoint,
    val events: List<CapsuleEvent>,
    /** Events evicted from the buffer over the whole session. */
    val droppedEvents: Int,
    /** No retained checkpoint covers the oldest retained event. */
    val hasGap: Boolean,
)

/**
 * Bounded event buffer with checkpoints (port of `flight_recorder.dart`).
 * While nothing was evicted the window is the launch checkpoint plus every
 * event; afterwards it starts at the earliest periodic checkpoint taken after
 * the last evicted event. Not thread-safe: the runtime serializes access.
 */
class FlightRecorder @JvmOverloads constructor(
    launch: Checkpoint,
    val maxEvents: Int = 20_000,
    val windowMs: Long = 5 * 60 * 1000L,
    val maxCheckpoints: Int = 4,
) {
    init {
        require(launch.kind == CheckpointKind.LAUNCH)
    }

    private var launch: Checkpoint = launch
    private val events = ArrayDeque<CapsuleEvent>()
    private val periodic = ArrayList<Checkpoint>()
    private var seq = 0L
    private var dropped = 0
    private var lastEvictedSeq = 0L

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
        }
        if (checkpoint != null) {
            launch = checkpoint(launch)
            for (i in periodic.indices) periodic[i] = checkpoint(periodic[i])
        }
    }

    fun window(): RecordedWindow {
        if (launchCoversWindow) return RecordedWindow(launch, events.toList(), 0, false)
        val chosen = windowCheckpoint()
        val from = chosen.afterSeq
        return RecordedWindow(chosen, events.filter { it.seq > from }, dropped, chosen.afterSeq < lastEvictedSeq)
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
        while (events.isNotEmpty() && (events.size > maxEvents || events.first().t < now - windowMs)) {
            lastEvictedSeq = events.removeFirst().seq
            dropped++
        }
        if (dropped > 0) prune()
    }

    private fun prune() {
        val firstValid = periodic.indexOfFirst { it.afterSeq >= lastEvictedSeq }
        val keepFrom = if (firstValid == -1) periodic.size - 1 else firstValid
        if (keepFrom > 0) periodic.subList(0, keepFrom).clear()
        while (periodic.size > maxCheckpoints) periodic.removeAt(0)
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
