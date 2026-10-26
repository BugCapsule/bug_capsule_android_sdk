package dev.bugcapsule.android.core

import java.util.concurrent.atomic.AtomicInteger

/** What an interaction adapter records under one `manifest.dependencies` entry (capsule-v2 §2). */
data class DependencyDescriptor @JvmOverloads constructor(
    /** Dependency id in the capsule (`data.dep`), unique per capsule. */
    val id: String,
    /** `websocket`, `platform.channel` or `native`. */
    val kind: String,
    /** Adapter identity, e.g. `bugcapsule-okhttp.websocket`. */
    val adapter: String,
    val adapterVersion: String,
    /** Version of this adapter's event payloads. */
    val schema: Int,
    /** False: diagnostic capture only, never served in replay. */
    val required: Boolean = true,
    val operations: List<String> = emptyList(),
    /** Package -> version the adapter was qualified against. */
    val tested: Map<String, String> = emptyMap(),
    /** Keep binary payloads (default: metadata only, `payload.binaryMetadata` gap). */
    val captureBinary: Boolean = false,
    /** Redacted payloads stay replayable (the app does not branch on the redacted fields). */
    val redactedReplayable: Boolean = false,
)

/** An installed adapter as replay preflight sees it (capsule-v2 §2 *Preflight*). */
data class InteractionAdapterInfo @JvmOverloads constructor(
    val adapter: String,
    /** Schemas this adapter can replay. */
    val schemas: IntRange,
    /** True when open connections/subscriptions of a periodic checkpoint can be restored. */
    val restorable: Boolean = false,
)

/**
 * Capture side of interaction dependencies for one session: the payload
 * budget every adapter shares, the active connection/subscription limit, and
 * per-dependency stats and gaps for the manifest (capsule-v2 §7). Thread-safe.
 *
 * Adapters [register] once and record through the returned
 * [DependencyRecorder]. The runtime turns the registrations into
 * `manifest.dependencies` ([dependencies]) and periodic checkpoints into
 * `checkpoint.data.interactions` ([checkpoint]).
 */
class InteractionRecorder @JvmOverloads constructor(
    private val sink: (type: String, data: Map<String, Any?>) -> Unit,
    private val now: () -> Long,
    private val redactor: () -> Redactor,
    /** Whether the session records a capsule at all. */
    private val recording: () -> Boolean,
    /** Body capture (`captureNetworkBodies`): off records payloads as `omitted`. */
    private val payloadsEnabled: () -> Boolean = { true },
    /** Adapter ids turned off by remote config (`disabledAdapters`). */
    private val disabledAdapters: () -> Collection<String> = { emptyList() },
    limits: InteractionLimits = InteractionLimits(),
) {
    val budget = PayloadBudget(limits)
    private val registered = LinkedHashMap<String, DependencyRecorder>()
    private val active = AtomicInteger()

    var limits: InteractionLimits
        get() = budget.limits
        set(value) {
            budget.limits = value
        }

    /** Registers (or returns the registered) recorder of [descriptor]'s dependency id. */
    @Synchronized
    fun register(descriptor: DependencyDescriptor): DependencyRecorder =
        registered.getOrPut(descriptor.id) { DependencyRecorder(descriptor, this) }

    @Synchronized
    fun recorders(): List<DependencyRecorder> = registered.values.toList()

    internal fun record(type: String, data: Map<String, Any?>) = sink(type, data)
    internal fun clock(): Long = now()
    internal fun currentRedactor(): Redactor = redactor()
    internal val isRecording: Boolean get() = recording()
    internal val payloadsOn: Boolean get() = payloadsEnabled()
    internal fun disabled(adapter: String): Boolean = adapter in disabledAdapters()
    internal fun acquire(): Boolean {
        while (true) {
            val n = active.get()
            if (n >= budget.limits.maxActive) return false
            if (active.compareAndSet(n, n + 1)) return true
        }
    }
    internal fun release() {
        active.decrementAndGet()
    }

    /**
     * `manifest.dependencies` for a capsule whose window holds [events]:
     * every registered dependency with events in the window or with gaps.
     */
    fun dependencies(events: List<CapsuleEvent>): List<CapsuleDependency> {
        val counts = HashMap<String, Int>()
        for (e in events) {
            if (!InteractionTypes.isInteraction(e.type)) continue
            val dep = e.data["dep"] as? String ?: continue
            counts[dep] = (counts[dep] ?: 0) + 1
        }
        return recorders().mapNotNull { r ->
            val d = r.descriptor
            if ((counts[d.id] ?: 0) == 0 && !r.stats.hasGaps) return@mapNotNull null
            CapsuleDependency(
                id = d.id,
                kind = d.kind,
                adapter = d.adapter,
                adapterVersion = d.adapterVersion,
                schema = d.schema,
                required = d.required,
                coverage = when {
                    !d.required -> DependencyCoverage.DIAGNOSTIC
                    r.stats.hasGaps -> DependencyCoverage.PARTIAL
                    else -> DependencyCoverage.COMPLETE
                },
                operations = d.operations.take(CapsuleLimits.MAX_DEPENDENCY_ITEMS),
                tested = d.tested.entries.take(CapsuleLimits.MAX_TESTED).associate { it.key to it.value },
                gaps = r.stats.gaps.take(CapsuleLimits.MAX_DEPENDENCY_ITEMS).map {
                    it.copy(detail = it.detail?.take(2000))
                },
                stats = r.stats.toJson(),
            )
        }.take(CapsuleLimits.MAX_DEPENDENCIES)
    }

    /**
     * `checkpoint.data.interactions` of a periodic checkpoint: each captured
     * connection or subscription open now (capsule-v2 §6). Null when no
     * adapter is registered.
     */
    fun checkpoint(): Map<String, Any?>? {
        val all = recorders()
        if (all.isEmpty()) return null
        val open = ArrayList<Map<String, Any?>>()
        for (r in all) {
            for ((id, type) in r.openEntries()) {
                open.add(linkedMapOf("dep" to r.descriptor.id, "type" to type, "id" to id, "restorable" to r.restorable()))
            }
        }
        return linkedMapOf("open" to open)
    }
}

/** One dependency's capture handle (see [InteractionRecorder.register]). Thread-safe. */
class DependencyRecorder internal constructor(
    val descriptor: DependencyDescriptor,
    private val owner: InteractionRecorder,
) {
    val stats = DependencyStats()
    private val occurrences = OccurrenceCounter()
    private val ids = AtomicInteger()
    private val open = LinkedHashMap<String, String>()

    /** Set by an adapter that can snapshot and restore its open interactions (capsule-v2 §6). */
    @Volatile
    var restorable: () -> Boolean = { false }

    val encoder = PayloadEncoder(
        redactor = owner::currentRedactor,
        budget = owner.budget,
        stats = stats,
        now = owner::clock,
        captureBinary = descriptor.captureBinary,
        redactedReplayable = descriptor.redactedReplayable,
        payloadsEnabled = { owner.payloadsOn },
    )

    /** Recording a capsule and this adapter not turned off by remote config (`disabledAdapters`). */
    val isCapturing: Boolean get() = owner.isRecording && !owner.disabled(descriptor.adapter)

    /** The session redactor (URL keys, error messages). */
    val redactor: Redactor get() = owner.currentRedactor()

    fun now(): Long = owner.clock()

    /** 0-based count of earlier outbound events with [key]. */
    fun occurrence(key: String): Int = occurrences.next(key)

    /** A new id unique in this session, e.g. `ws1`, `ws2`. */
    fun nextId(prefix: String): String = "$prefix${ids.incrementAndGet()}"

    /** Records an interaction event (`data.dep` is added). Never throws: a failure becomes an `adapter.captureFailed` gap. */
    fun record(type: String, data: Map<String, Any?>) {
        try {
            val out = LinkedHashMap<String, Any?>(data.size + 1)
            out["dep"] = descriptor.id
            out.putAll(data)
            stats.event()
            owner.record(type, out)
        } catch (e: Throwable) {
            stats.gap(DependencyGaps.CAPTURE_FAILED, "$type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Claims one of the session's active slots for connection/subscription
     * [id] of [type]. False when the limit is reached: the interaction runs
     * uncaptured and a `connections.limit` gap is recorded.
     */
    fun open(type: String, id: String): Boolean {
        if (!owner.acquire()) {
            stats.gap(DependencyGaps.CONNECTIONS_LIMIT, "more than ${owner.limits.maxActive} active connections and subscriptions")
            return false
        }
        synchronized(open) { open[id] = type }
        return true
    }

    /** Releases the slot of [id] (idempotent). */
    fun closed(id: String) {
        val had = synchronized(open) { open.remove(id) != null }
        if (had) owner.release()
    }

    internal fun openEntries(): List<Pair<String, String>> = synchronized(open) { open.map { it.key to it.value } }

    @JvmOverloads
    fun gap(code: String, detail: String? = null) = stats.gap(code, detail)
}
