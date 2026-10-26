package dev.bugcapsule.android.core

/**
 * Interaction dependencies (capsule-v2): WebSockets, platform channels and
 * native SDK adapters. A port of `interactions.dart`; the shared conformance
 * fixtures (`spec-fixtures/conformance/interactions.json`) hold both to the
 * same keys, payload encoding and delivery schedule.
 */
object InteractionTypes {
    const val WS_CONNECT = "ws.connect"
    const val WS_OPEN = "ws.open"
    const val WS_SEND = "ws.send"
    const val WS_MESSAGE = "ws.message"
    const val WS_ERROR = "ws.error"
    const val WS_CLOSE = "ws.close"

    const val PLATFORM_CALL = "platform.call"
    const val PLATFORM_RESULT = "platform.result"
    const val PLATFORM_ERROR = "platform.error"
    const val PLATFORM_LISTEN = "platform.listen"
    const val PLATFORM_EVENT = "platform.event"
    const val PLATFORM_CANCEL = "platform.cancel"
    const val PLATFORM_DONE = "platform.done"

    const val NATIVE_CALL = "native.call"
    const val NATIVE_RESULT = "native.result"
    const val NATIVE_ERROR = "native.error"
    const val NATIVE_SUBSCRIBE = "native.subscribe"
    const val NATIVE_EVENT = "native.event"
    const val NATIVE_CANCEL = "native.cancel"
    const val NATIVE_DONE = "native.done"

    /** Data fields that hold payload objects. */
    @JvmField
    val PAYLOAD_FIELDS: Set<String> = setOf("payload", "args", "value", "details")

    /** `ws`, `platform` or `native` for an interaction event type, else null. */
    @JvmStatic
    fun familyOf(type: String): String? = when {
        type.startsWith("ws.") -> "ws"
        type.startsWith("platform.") -> "platform"
        type.startsWith("native.") -> "native"
        else -> null
    }

    @JvmStatic
    fun isInteraction(type: String): Boolean = familyOf(type) != null
}

/** `manifest.dependencies[].kind`. */
object DependencyKinds {
    const val WEBSOCKET = "websocket"
    const val PLATFORM_CHANNEL = "platform.channel"
    const val NATIVE = "native"
}

/** Gap codes of capsule-v2 §7 (plus `operation.unsupported`, see [OPERATION_UNSUPPORTED]). */
object DependencyGaps {
    const val PAYLOAD_TRUNCATED = "payload.truncated"
    const val PAYLOAD_DROPPED = "payload.dropped"
    const val PAYLOAD_BINARY_METADATA = "payload.binaryMetadata"
    const val PAYLOAD_REDACTED = "payload.redacted"
    const val CONNECTIONS_LIMIT = "connections.limit"
    const val CODEC_UNSUPPORTED = "codec.unsupported"
    const val CHANNEL_LATE = "channel.late"
    const val CAPTURE_FAILED = "adapter.captureFailed"
    const val EVENTS_DROPPED = "events.dropped"

    /**
     * Android adapter extension: the app used an operation the native adapter
     * does not support, so it ran uncaptured (replay fails it as unrecorded).
     */
    const val OPERATION_UNSUPPORTED = "operation.unsupported"
}

data class DependencyGap(val code: String, val count: Int? = null, val detail: String? = null) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("code" to code).also {
        if (count != null) it["count"] = count
        if (detail != null) it["detail"] = detail
    }

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = DependencyGap(j["code"] as String, J.int(j["count"]), J.str(j["detail"]))
    }
}

enum class DependencyCoverage(val wire: String) {
    COMPLETE("complete"), PARTIAL("partial"), DIAGNOSTIC("diagnostic");

    companion object {
        @JvmStatic
        fun parse(wire: String): DependencyCoverage = entries.first { it.wire == wire }
    }
}

/** One entry of `manifest.dependencies` (capsule-v2 §2). */
data class CapsuleDependency @JvmOverloads constructor(
    val id: String,
    val kind: String,
    val adapter: String,
    val adapterVersion: String,
    val schema: Int,
    val required: Boolean,
    val coverage: DependencyCoverage,
    val operations: List<String> = emptyList(),
    /** Package -> version the adapter was qualified against. */
    val tested: Map<String, String> = emptyMap(),
    val gaps: List<DependencyGap> = emptyList(),
    val stats: Map<String, Long> = emptyMap(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id,
        "kind" to kind,
        "adapter" to adapter,
        "adapterVersion" to adapterVersion,
        "schema" to schema,
        "required" to required,
        "coverage" to coverage.wire,
    ).also { out ->
        if (operations.isNotEmpty()) out["operations"] = operations
        if (tested.isNotEmpty()) out["tested"] = tested.map { (p, v) -> linkedMapOf("package" to p, "version" to v) }
        if (gaps.isNotEmpty()) out["gaps"] = gaps.map { it.toJson() }
        if (stats.isNotEmpty()) out["stats"] = stats
    }

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = CapsuleDependency(
            id = j["id"] as String,
            kind = j["kind"] as String,
            adapter = j["adapter"] as String,
            adapterVersion = j["adapterVersion"] as String,
            schema = J.int(j["schema"])!!,
            required = j["required"] as Boolean,
            coverage = DependencyCoverage.parse(j["coverage"] as String),
            operations = (J.list(j["operations"]) ?: emptyList()).map { it.toString() },
            tested = LinkedHashMap<String, String>().also { out ->
                for (t in J.list(j["tested"]) ?: emptyList()) {
                    val m = J.obj(t) ?: continue
                    out[m["package"].toString()] = m["version"].toString()
                }
            },
            gaps = (J.list(j["gaps"]) ?: emptyList()).map { DependencyGap.fromJson(J.obj(it)!!) },
            stats = LinkedHashMap<String, Long>().also { out ->
                for ((k, v) in J.obj(j["stats"]) ?: emptyMap()) J.long(v)?.let { out[k] = it }
            },
        )
    }
}

enum class PayloadMode(val wire: String) {
    FULL("full"), REDACTED("redacted"), METADATA("metadata"), OMITTED("omitted"), TRUNCATED("truncated");

    companion object {
        @JvmStatic
        fun parse(wire: String): PayloadMode = entries.first { it.wire == wire }
    }
}

/** A payload object (capsule-v2 §3). [value] is typed-value JSON (see [TypedValue]). */
data class InteractionPayload @JvmOverloads constructor(
    val mode: PayloadMode,
    val replayable: Boolean,
    val value: Any? = null,
    val bytes: Int? = null,
    /** `text`, `binary` or null (`value`). */
    val kind: String? = null,
) {
    /** The Kotlin value replay hands the app. */
    val decoded: Any? get() = TypedValue.decode(value)

    /**
     * Whether this recording and [other] carry the same value (canonical
     * JSON), for mismatch checks. Non-replayable payloads compare by size.
     */
    fun sameAs(other: InteractionPayload): Boolean {
        if (!replayable || !other.replayable) return bytes == other.bytes && kind == other.kind
        return Json.canonical(value) == Json.canonical(other.value)
    }

    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("mode" to mode.wire, "replayable" to replayable).also {
        if (replayable || value != null) it["value"] = value
        if (bytes != null) it["bytes"] = bytes.toLong()
        if (kind != null) it["kind"] = kind
    }

    companion object {
        @JvmStatic
        fun fromJson(j: Map<String, Any?>) = InteractionPayload(
            mode = PayloadMode.parse(j["mode"] as String),
            replayable = j["replayable"] as Boolean,
            value = j["value"],
            bytes = J.int(j["bytes"]),
            kind = J.str(j["kind"]),
        )

        /** Null when [json] is not a payload object. */
        @JvmStatic
        fun tryParse(json: Any?): InteractionPayload? {
            val m = J.obj(json) ?: return null
            return try {
                fromJson(m)
            } catch (_: Exception) {
                null
            }
        }

        @JvmField
        val NONE = InteractionPayload(PayloadMode.FULL, true)
    }
}

/** Bounds of capsule-v2 §7. */
data class InteractionLimits @JvmOverloads constructor(
    /** Per payload (encoded JSON). */
    val maxPayloadBytes: Int = 64 * 1024,
    /** Interaction payloads retained in the recorder window, all adapters. */
    val maxWindowPayloadBytes: Int = 1024 * 1024,
    /** Captured connections and subscriptions open at the same time. */
    val maxActive: Int = 32,
    /** Matches the flight recorder window: older payloads no longer count. */
    val windowMs: Long = 5 * 60 * 1000L,
) {
    fun lowered(maxPayloadBytes: Int? = null, maxWindowBytes: Int? = null) = InteractionLimits(
        maxPayloadBytes = min(this.maxPayloadBytes, maxPayloadBytes),
        maxWindowPayloadBytes = min(maxWindowPayloadBytes, maxWindowBytes),
        maxActive = maxActive,
        windowMs = windowMs,
    )

    private fun min(a: Int, b: Int?) = if (b == null || b > a) a else b
}

/**
 * Shared payload byte budget of every adapter in a session: a rolling window
 * so a long session keeps recording recent traffic. Thread-safe.
 */
class PayloadBudget(@Volatile var limits: InteractionLimits) {
    private val spent = ArrayDeque<Pair<Long, Int>>()
    private var total = 0L

    @Synchronized
    fun spend(now: Long, bytes: Int): Boolean {
        while (spent.isNotEmpty() && spent.first().first < now - limits.windowMs) total -= spent.removeFirst().second
        if (total + bytes > limits.maxWindowPayloadBytes) return false
        spent.addLast(now to bytes)
        total += bytes
        return true
    }
}

/** Per-dependency capture bookkeeping: gaps and stats for the manifest. Thread-safe. */
class DependencyStats {
    private val gapCounts = LinkedHashMap<String, Pair<Int, String?>>()
    var events = 0L
        private set
    var payloadBytes = 0L
        private set
    var dropped = 0L
        private set
    var truncated = 0L
        private set

    @Synchronized
    fun event() {
        events++
    }

    @Synchronized
    fun payload(bytes: Int) {
        payloadBytes += bytes
    }

    @Synchronized
    fun dropped() {
        dropped++
    }

    @Synchronized
    fun truncated() {
        truncated++
    }

    @JvmOverloads
    @Synchronized
    fun gap(code: String, detail: String? = null) {
        val (n, d) = gapCounts[code] ?: (0 to detail)
        gapCounts[code] = (n + 1) to (d ?: detail)
    }

    val hasGaps: Boolean @Synchronized get() = gapCounts.isNotEmpty()

    val gaps: List<DependencyGap>
        @Synchronized get() = gapCounts.map { (code, v) -> DependencyGap(code, v.first, v.second) }

    @Synchronized
    fun toJson(): Map<String, Long> =
        linkedMapOf("events" to events, "payloadBytes" to payloadBytes, "dropped" to dropped, "truncated" to truncated)
}

/**
 * Turns live values into payload objects: typed encoding, redaction, binary
 * policy, size limits and the shared budget (capsule-v2 §3, §7).
 */
class PayloadEncoder @JvmOverloads constructor(
    private val redactor: () -> Redactor,
    val budget: PayloadBudget,
    val stats: DependencyStats,
    private val now: () -> Long,
    /** Binary values (byte frames, typed arrays) are kept only when true; otherwise recorded as metadata. */
    @Volatile var captureBinary: Boolean = false,
    /** Whether a value the redactor changed can still be replayed (the app does not branch on the redacted fields). */
    @Volatile var redactedReplayable: Boolean = false,
    /** False while payload capture is off (`captureNetworkBodies`): payloads are `omitted`. */
    private val payloadsEnabled: () -> Boolean = { true },
) {
    /** Encodes [value]. [kind] is `text`/`binary` for WebSocket frames. */
    @JvmOverloads
    fun encode(value: Any?, kind: String? = null): InteractionPayload {
        if (!payloadsEnabled()) {
            stats.gap(DependencyGaps.PAYLOAD_DROPPED, "payload capture is off")
            return InteractionPayload(PayloadMode.OMITTED, false, kind = kind)
        }
        val typed = try {
            if (value is String && kind == "text") value else TypedValue.encode(value)
        } catch (_: Exception) {
            stats.gap(DependencyGaps.CAPTURE_FAILED, "value could not be encoded")
            return InteractionPayload(PayloadMode.OMITTED, false, kind = kind)
        }
        val size = size(value, typed)
        if (!captureBinary && (kind == "binary" || TypedValue.hasBinary(typed))) {
            stats.gap(
                DependencyGaps.PAYLOAD_BINARY_METADATA,
                "binary data recorded as metadata; register a serializer to keep it",
            )
            return InteractionPayload(PayloadMode.METADATA, false, bytes = size, kind = kind)
        }
        val (redacted, changed) = TypedValue.redact(typed, redactor())
        val encoded = Json.stringify(redacted)
        if (encoded.length > budget.limits.maxPayloadBytes) {
            stats.truncated()
            stats.gap(DependencyGaps.PAYLOAD_TRUNCATED)
            return InteractionPayload(PayloadMode.TRUNCATED, false, bytes = size, kind = kind)
        }
        if (!budget.spend(now(), encoded.length)) {
            stats.dropped()
            stats.gap(DependencyGaps.PAYLOAD_DROPPED, "capsule payload budget spent")
            return InteractionPayload(PayloadMode.METADATA, false, bytes = size, kind = kind)
        }
        stats.payload(encoded.length)
        if (changed) stats.gap(DependencyGaps.PAYLOAD_REDACTED)
        val replayable = TypedValue.isReplayable(redacted) && (!changed || redactedReplayable)
        return InteractionPayload(
            mode = if (changed) PayloadMode.REDACTED else PayloadMode.FULL,
            replayable = replayable,
            value = redacted,
            bytes = size,
            kind = kind,
        )
    }

    /**
     * The match-key input for [value]: encoded and redacted like a payload,
     * whatever the capture limits (so capture and replay agree).
     */
    fun keyInput(value: Any?): Any? = try {
        TypedValue.redact(TypedValue.encode(value), redactor()).first
    } catch (_: Exception) {
        value?.javaClass?.name ?: "null"
    }

    private fun size(value: Any?, typed: Any?): Int = when (value) {
        is String -> value.toByteArray(Charsets.UTF_8).size
        is ByteArray -> value.size
        is IntArray -> value.size
        is LongArray -> value.size
        is java.nio.ByteBuffer -> value.remaining()
        else -> try {
            Json.stringify(typed).length
        } catch (_: Exception) {
            0
        }
    }
}

/** `<scope>|<method>|<hash of canonical args>` (capsule-v2 §5). */
fun interactionKey(scope: String, method: String?, argsJson: Any?): String =
    "$scope|${method ?: ""}|${RequestKeyBuilder.hash(Json.canonical(argsJson))}"

/** Counts occurrences per key on the capture side. Thread-safe. */
class OccurrenceCounter {
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun next(key: String): Int {
        val n = counts[key] ?: 0
        counts[key] = n + 1
        return n
    }
}

/** A recorded interaction event plus its scheduling metadata. */
class RecordedInteraction(val event: CapsuleEvent, val scope: String, val outbound: Boolean) {
    /** For inbound events: the outbound event that releases it. */
    var anchor: RecordedInteraction? = null
    var consumed = false

    /** Replay-clock time this event was matched (outbound) or delivered. */
    var at: Long? = null
    var dueAt: Long? = null

    val type: String get() = event.type
    val family: String get() = InteractionTypes.familyOf(event.type)!!
    val data: Map<String, Any?> get() = event.data
    val dep: String get() = data["dep"] as String

    fun payload(field: String): InteractionPayload? = InteractionPayload.tryParse(data[field])

    override fun toString() = "${event.type}#${event.seq}"
}

/**
 * The scheduling scope and direction (true: outbound) of an interaction
 * event (§5): the scope orders deliveries and is what an adapter binds a
 * handler to.
 */
fun interactionScope(e: CapsuleEvent, callDirections: Map<String, String>): Pair<String, Boolean>? {
    val d = e.data
    return when (e.type) {
        InteractionTypes.WS_CONNECT, InteractionTypes.WS_SEND -> "ws:${d["conn"]}" to true
        InteractionTypes.WS_CLOSE -> "ws:${d["conn"]}" to (d["initiator"] == "client")
        InteractionTypes.WS_OPEN, InteractionTypes.WS_MESSAGE, InteractionTypes.WS_ERROR -> "ws:${d["conn"]}" to false
        InteractionTypes.PLATFORM_CALL ->
            if (d["dir"] == "platform") "platform.in:${d["channel"]}" to false else "platform.call:${d["call"]}" to true
        // The reply to a platform-to-Dart call is the app's own (outbound).
        InteractionTypes.PLATFORM_RESULT, InteractionTypes.PLATFORM_ERROR ->
            "platform.call:${d["call"]}" to (callDirections["${d["call"]}"] == "platform")
        InteractionTypes.PLATFORM_LISTEN, InteractionTypes.PLATFORM_CANCEL -> "platform.sub:${d["sub"]}" to true
        InteractionTypes.PLATFORM_EVENT, InteractionTypes.PLATFORM_DONE -> "platform.sub:${d["sub"]}" to false
        InteractionTypes.NATIVE_CALL -> "native.op:${d["op"]}" to true
        InteractionTypes.NATIVE_RESULT -> "native.op:${d["op"]}" to false
        InteractionTypes.NATIVE_ERROR -> if (d["sub"] != null) "native.sub:${d["sub"]}" to false else "native.op:${d["op"]}" to false
        InteractionTypes.NATIVE_SUBSCRIBE, InteractionTypes.NATIVE_CANCEL -> "native.sub:${d["sub"]}" to true
        InteractionTypes.NATIVE_EVENT, InteractionTypes.NATIVE_DONE -> "native.sub:${d["sub"]}" to false
        else -> null
    }
}

/** Receives inbound events of a bound scope. */
fun interface InteractionHandler {
    fun deliver(event: RecordedInteraction)
}

/**
 * Replay of interaction dependencies (capsule-v2 §5) without timers: the
 * runtime calls [pump] when [nextDueAt] arrives. A port of the Dart
 * `InteractionScript` with identical semantics. Not thread-safe (see
 * `InteractionReplay` for the locked, timed wrapper).
 *
 * Adapters consume outbound expectations with [take] (keyed operations:
 * connects, calls, listens) or [takeNext] (ordered operations within a
 * scope: sends, cancels, replies), and [bind] a handler to a scope to receive
 * its inbound events. Each inbound event is released by its anchor (the
 * latest outbound event of its scope before it) and delivered
 * `t(inbound) - t(anchor)` after the anchor was matched; events without an
 * anchor follow the replay clock once [runStarted].
 */
class InteractionScript @JvmOverloads constructor(
    events: Iterable<CapsuleEvent>,
    dependencies: Set<String>,
    /** Replay clock in milliseconds (monotonic, includes skipped idle time). */
    private val clock: () -> Long,
    /** Recorded time replay starts from: 0 for a launch checkpoint, the checkpoint time for a periodic one. */
    val base: Long = 0,
    /** How long a due event may wait for its scope's handler. */
    val maxHandlerWaitMs: Long = 5000,
) {
    private val all = ArrayList<RecordedInteraction>()
    private val keyed = HashMap<String, ArrayDeque<RecordedInteraction>>()
    private val ordered = HashMap<String, MutableList<RecordedInteraction>>()
    private val released = HashMap<RecordedInteraction, MutableList<RecordedInteraction>>()
    private val unanchored = ArrayList<RecordedInteraction>()
    private val pending = ArrayList<RecordedInteraction>()
    private val handlers = HashMap<String, InteractionHandler>()
    private val lastDue = HashMap<String, Long>()
    private val divergences = ArrayList<Pair<String, String>>()
    private var runStart: Long? = null

    init {
        val calls = HashMap<String, String>()
        for (e in events) if (e.type == InteractionTypes.PLATFORM_CALL) calls["${e.data["call"]}"] = "${e.data["dir"]}"
        val lastOutbound = HashMap<String, RecordedInteraction>()
        for (e in events) {
            if (!InteractionTypes.isInteraction(e.type)) continue
            if (e.data["dep"] !in dependencies) continue
            if (e.data["diagnostic"] == true) continue
            val (scope, outbound) = interactionScope(e, calls) ?: continue
            val r = RecordedInteraction(e, scope, outbound)
            all.add(r)
            val channelScope = if (e.type.startsWith("platform.")) "channel:${e.data["channel"]}" else null
            if (outbound) {
                lastOutbound[scope] = r
                if (channelScope != null) lastOutbound[channelScope] = r
                val key = e.data["key"]
                if (key is String) {
                    keyed.getOrPut("${r.dep}|${e.type}|$key") { ArrayDeque() }.addLast(r)
                } else {
                    ordered.getOrPut(scope) { ArrayList() }.add(r)
                }
            } else {
                r.anchor = if (e.type == InteractionTypes.PLATFORM_CALL) lastOutbound[channelScope] else lastOutbound[scope]
                val a = r.anchor
                if (a == null) unanchored.add(r) else released.getOrPut(a) { ArrayList() }.add(r)
            }
        }
    }

    /** Recorded interactions of the replayed dependencies (for preflight and inspection). */
    val recorded: List<RecordedInteraction> get() = all

    val isEmpty: Boolean get() = all.isEmpty()

    /** Divergences found while scheduling (undelivered events), drained by the runtime. */
    fun takeDivergences(): List<Pair<String, String>> {
        val out = divergences.toList()
        divergences.clear()
        return out
    }

    /**
     * Matches a keyed outbound operation (`ws.connect`, `platform.call`,
     * `platform.listen`, `native.call`, `native.subscribe`) by key and
     * invocation order. Null: nothing recorded (the caller reports
     * `<family>.unrecorded` and fails the operation).
     */
    fun take(dep: String, type: String, key: String): RecordedInteraction? {
        val queue = keyed["$dep|$type|$key"] ?: return null
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            if (r.consumed) continue
            consume(r)
            return r
        }
        return null
    }

    /**
     * Unmatched keyed operations of [type] in [dep] whose key starts with
     * [prefix]: lets an adapter tell a payload mismatch from an unrecorded
     * operation.
     */
    fun hasUnconsumed(dep: String, type: String, prefix: String): Boolean =
        keyed.any { (k, v) -> k.startsWith("$dep|$type|$prefix") && v.any { !it.consumed } }

    /**
     * Matches the next unconsumed outbound event of [types] in [scope], in
     * recorded order (sends on a connection, a client close, a cancel, the
     * app's reply to a platform call).
     */
    fun takeNext(scope: String, types: Set<String>): RecordedInteraction? {
        val list = ordered[scope] ?: return null
        for (r in list) {
            if (r.consumed || r.type !in types) continue
            consume(r)
            return r
        }
        return null
    }

    private fun consume(r: RecordedInteraction) {
        val now = clock()
        r.consumed = true
        r.at = now
        for (inbound in released[r] ?: emptyList<RecordedInteraction>()) schedule(inbound, now + (inbound.event.t - r.event.t))
    }

    private fun schedule(r: RecordedInteraction, due: Long) {
        val last = lastDue[r.scope]
        val at = if (last != null && last > due) last else due
        lastDue[r.scope] = at
        r.dueAt = at
        pending.add(r)
    }

    /**
     * Replay of recorded input started at replay time [at], which
     * corresponds to recorded time [base]. Events without an anchor become
     * due on this clock.
     */
    fun runStarted(at: Long) {
        runStart = at
        for (r in unanchored) if (r.dueAt == null && !r.consumed) schedule(r, at + (r.event.t - base))
        pending.sortWith(BY_DUE)
    }

    /**
     * Events without an anchor recorded before the replayed window started
     * (e.g. platform calls before the first frame) are due as soon as their
     * handler exists.
     */
    private fun scheduleEarly(scope: String) {
        if (runStart != null) return
        for (r in unanchored) {
            if (r.scope != scope || r.dueAt != null || r.consumed) continue
            if (r.event.t < base) schedule(r, clock())
        }
    }

    /** Delivers inbound events of [scope] to [handler] from now on (and any already due). */
    fun bind(scope: String, handler: InteractionHandler) {
        handlers[scope] = handler
        scheduleEarly(scope)
    }

    fun unbind(scope: String) {
        handlers.remove(scope)
    }

    fun isBound(scope: String): Boolean = scope in handlers

    /**
     * Earliest time an event is due whose handler is bound; null when nothing
     * is scheduled. Events waiting for a handler do not count (an open but
     * idle subscription must not hold replay busy).
     */
    val nextDueAt: Long?
        get() {
            var next: Long? = null
            for (r in pending) {
                if (r.scope !in handlers) continue
                val due = r.dueAt!!
                if (next == null || due < next) next = due
            }
            return next
        }

    /** Whether any scheduled event waits for a handler. */
    val hasWaiting: Boolean get() = pending.any { it.scope !in handlers }

    /** Delivers every event due at or before now, in (due, seq) order. Returns how many were delivered. */
    fun pump(): Int {
        val now = clock()
        pending.sortWith(BY_DUE)
        var delivered = 0
        val keep = ArrayList<RecordedInteraction>()
        val due = ArrayList<RecordedInteraction>()
        for (r in pending) {
            if (r.dueAt!! > now) {
                keep.add(r)
                continue
            }
            if (r.scope !in handlers) {
                if (now - r.dueAt!! > maxHandlerWaitMs) {
                    r.consumed = true
                    divergences.add("${r.family}.undelivered" to "${describe(r)}: no handler for ${now - r.dueAt!!} ms")
                } else {
                    keep.add(r)
                }
                continue
            }
            due.add(r)
        }
        pending.clear()
        pending.addAll(keep)
        for (r in due) {
            r.consumed = true
            r.at = now
            val handler = handlers[r.scope]
            if (handler == null) {
                // Unbound by an earlier delivery in this batch (a close).
                pending.add(r)
                continue
            }
            handler.deliver(r)
            delivered++
        }
        return delivered
    }

    /**
     * Unmatched and undelivered recorded interactions, grouped by family and
     * scope: `<family>.unused` when recorded at or before [failureT] (or with
     * no failure), `<family>.unusedAfterFailure` after it.
     */
    fun unused(failureT: Long?): List<Pair<String, String>> {
        val groups = LinkedHashMap<Triple<String, Boolean, String>, MutableList<RecordedInteraction>>()
        for (r in all) {
            if (r.consumed) continue
            val after = failureT != null && r.event.t > failureT
            groups.getOrPut(Triple(r.family, after, r.scope)) { ArrayList() }.add(r)
        }
        return groups.map { (k, list) ->
            "${k.first}.${if (k.second) "unusedAfterFailure" else "unused"}" to "${k.third}: ${summary(list)}"
        }
    }

    private companion object {
        val BY_DUE = Comparator<RecordedInteraction> { a, b ->
            val c = a.dueAt!!.compareTo(b.dueAt!!)
            if (c != 0) c else a.event.seq.compareTo(b.event.seq)
        }

        fun summary(list: List<RecordedInteraction>): String {
            val counts = LinkedHashMap<String, Int>()
            for (r in list) counts[r.type] = (counts[r.type] ?: 0) + 1
            val first = list.first().event.t
            return "${counts.entries.joinToString(", ") { if (it.value > 1) "${it.key}×${it.value}" else it.key }} from ${first}ms"
        }

        fun describe(r: RecordedInteraction) = "${r.scope} ${r.type} @${r.event.t}ms"
    }
}
