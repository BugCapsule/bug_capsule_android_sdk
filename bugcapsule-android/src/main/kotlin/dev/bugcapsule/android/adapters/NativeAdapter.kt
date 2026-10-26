package dev.bugcapsule.android.adapters

import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.DependencyDescriptor
import dev.bugcapsule.android.core.DependencyGaps
import dev.bugcapsule.android.core.DependencyKinds
import dev.bugcapsule.android.core.DependencyRecorder
import dev.bugcapsule.android.core.InteractionAdapterInfo
import dev.bugcapsule.android.core.InteractionHandler
import dev.bugcapsule.android.core.InteractionTypes
import dev.bugcapsule.android.core.RecordedInteraction
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.TypedValue
import dev.bugcapsule.android.core.interactionKey
import dev.bugcapsule.android.replay.InteractionReplay
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A native SDK adapter (capsule-v2 §4.3): describes how a third-party SDK
 * (location, maps, auth, payments, a database) is recorded and replayed.
 * Values that cross the adapter are typed values (capsule-v2 §3.1): `null`,
 * booleans, numbers, strings, `ByteArray` and the primitive arrays, lists and
 * maps. The adapter's wrapper converts SDK objects to and from them.
 *
 * Install it through [NativeInteractions] (a [BugCapsuleIntegration]):
 *
 * ```kotlin
 * val location = NativeInteractions(LocationAdapter())
 * BugCapsule.init(app, config, listOf(okHttp, location))
 * val provider: LocationProvider = BugCapsuleLocationProvider(realProvider, location)
 * ```
 *
 * Replay never calls the SDK: recorded results and events are handed to the
 * app's callbacks at their recorded latency, operations without a recording
 * fail with [NativeError.UNRECORDED]. Run [NativeAdapterConformance]
 * (artifact `bugcapsule-android-replay`) against your adapter in a unit test.
 */
interface NativeAdapter {
    /** Adapter identity in `manifest.dependencies[].adapter`, e.g. `acme.location`. */
    val id: String

    /** Version of the adapter (not of the SDK). */
    val version: String

    /** Version of the event payloads this adapter records. */
    val schema: Int

    /** Schemas this adapter can replay (preflight: others end `incompatible`). */
    val supportedSchemas: IntRange get() = schema..schema

    /** Dependency id in the capsule (`data.dep`); unique per capsule. */
    val dependencyId: String get() = id

    /** Operations (calls and subscriptions) this adapter records; others run uncaptured with an explicit gap. */
    val operations: Set<String>

    /** SDK package -> version this adapter was qualified against (`tested`). */
    val tested: Map<String, String> get() = emptyMap()

    /** False: diagnostic capture only; replay passes calls through to the SDK and never serves them. */
    val required: Boolean get() = true

    /** Keep binary values (default: metadata only, which replay cannot serve). */
    val captureBinary: Boolean get() = false

    /** Values the redactor changed are still replayable (the app does not branch on those fields). */
    val redactedReplayable: Boolean get() = false

    /** Top-level argument fields that differ between runs (timestamps, nonces): left out of match keys. */
    fun nondeterministicFields(operation: String): Set<String> = emptySet()

    /** Where replayed callbacks run; null: a BugCapsule background thread (one per adapter, in recorded order). */
    val callbackExecutor: Executor? get() = null

    /** True when [snapshot]/[restore] can bring back subscriptions open at a periodic checkpoint (capsule-v2 §6). */
    val restorable: Boolean get() = false

    /** JSON-safe state of open subscriptions for a periodic checkpoint (only called when [restorable]). */
    fun snapshot(): Any? = null

    /** Restores [snapshot] state before the app under replay starts. */
    fun restore(state: Any?) {}

    /** Releases SDK resources; called by [NativeInteractions.dispose]. */
    fun dispose() {}
}

/** An SDK error (`native.error`). [details] is a typed value. */
data class NativeError @JvmOverloads constructor(val code: String, val message: String? = null, val details: Any? = null) {
    companion object {
        /** Replay had no recording of the operation (`native.unrecorded`). */
        const val UNRECORDED = "bugcapsule.unrecorded"

        /** The recorded value cannot be replayed (`native.noPayload`). */
        const val NO_PAYLOAD = "bugcapsule.noPayload"
    }
}

/** Result of one call. Exactly one method is called once. */
interface NativeCallback {
    fun onResult(value: Any?)
    fun onError(error: NativeError)
}

/** Events of one subscription until [onError], [onDone] or cancellation. */
interface NativeSubscriber {
    fun onEvent(value: Any?)
    fun onError(error: NativeError)
    fun onDone() {}
}

/** Cancels a subscription. */
fun interface NativeSubscription {
    fun cancel()
}

/** Starts the real SDK call (capture only); report the outcome to [callback]. */
fun interface NativeCall {
    fun start(callback: NativeCallback)
}

/** Starts the real SDK subscription (capture only); returns how to cancel it. */
fun interface NativeSubscribe {
    fun start(subscriber: NativeSubscriber): NativeSubscription
}

/**
 * The bridge an adapter's SDK wrapper calls (capture and replay dispatch of
 * `native.call/result/error/subscribe/event/cancel/done`), and the
 * [BugCapsuleIntegration] that installs [adapter].
 *
 * * **Capture**: [call]/[subscribe] record the operation (arguments through
 *   the session's redactor and payload limits), run the real SDK through
 *   `live`, record what it answers and forward it to the app. Operations not
 *   in [NativeAdapter.operations] run uncaptured with an
 *   `operation.unsupported` gap; so do subscriptions beyond the session's
 *   active limit (`connections.limit`). Capture problems never change what
 *   the app receives.
 * * **Replay**: `live` is never called. Operations match the recording by
 *   key (operation + redacted arguments) and invocation order; results and
 *   events arrive at their recorded latency on [NativeAdapter.callbackExecutor]
 *   (default: one background thread per adapter, recorded order). Misses
 *   fail with [NativeError.UNRECORDED] and a `native.unrecorded` (or
 *   `native.mismatch` when only the arguments differ) divergence.
 *
 * App callbacks receive the recorded value in both modes (the wrapper decodes
 * it), so capture and replay hand the app the same thing.
 */
class NativeInteractions(val adapter: NativeAdapter) : BugCapsuleIntegration {
    override val name: String get() = adapter.id

    @Volatile
    private var redactor: () -> Redactor = { Redactor() }

    @Volatile
    private var recorder: DependencyRecorder? = null

    @Volatile
    private var replay: InteractionReplay? = null
    private var diverge: (String, String) -> Unit = { _, _ -> }
    private val openSubs = AtomicInteger()
    private val ownExecutor = lazy<ExecutorService> {
        Executors.newSingleThreadExecutor { r -> Thread(r, "BugCapsule-native-${adapter.id}").apply { isDaemon = true } }
    }
    private val disposed = AtomicBoolean(false)

    val descriptor: DependencyDescriptor
        get() = DependencyDescriptor(
            id = adapter.dependencyId,
            kind = DependencyKinds.NATIVE,
            adapter = adapter.id,
            adapterVersion = adapter.version,
            schema = adapter.schema,
            required = adapter.required,
            operations = adapter.operations.sorted(),
            tested = adapter.tested,
            captureBinary = adapter.captureBinary,
            redactedReplayable = adapter.redactedReplayable,
        )

    override fun setUp(session: BugCapsuleSession) {
        redactor = { session.redactor }
        val r = session.replay
        if (r != null) {
            replay = r.interactions
            diverge = r::diverge
            return
        }
        recorder = session.interactions?.register(descriptor)?.also { rec ->
            rec.restorable = { adapter.restorable }
        }
    }

    override fun interactionAdapters() = listOf(InteractionAdapterInfo(adapter.id, adapter.supportedSchemas, adapter.restorable))

    override fun capabilities() = mapOf("native.${adapter.id}" to "supported")

    override fun snapshot(kind: CheckpointKind): Any? =
        if (kind == CheckpointKind.PERIODIC && adapter.restorable && openSubs.get() > 0) adapter.snapshot() else null

    override fun restore(data: Any?, session: BugCapsuleSession) {
        if (data != null) adapter.restore(data)
    }

    /** True in replay: wrappers must not touch the SDK (and never need to: `live` is not called). */
    val isReplaying: Boolean get() = replay != null

    /** Records a gap of this dependency (capsule-v2 §7), e.g. a value the wrapper could not convert. */
    @JvmOverloads
    fun gap(code: String, detail: String? = null) {
        recorder?.gap(code, detail)
    }

    /** Replay: the SDK was reached for real (escaped the adapter): `dependency.live`. */
    fun reportLive(detail: String) {
        if (replay != null) diverge("dependency.live", "${adapter.id}: $detail")
    }

    /** Replay: the adapter was installed after the SDK was initialised: `dependency.late`. */
    fun reportLate(detail: String) {
        if (replay != null) diverge("dependency.late", "${adapter.id}: $detail")
    }

    /** Releases the adapter and stops replay deliveries. */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try {
            adapter.dispose()
        } finally {
            if (ownExecutor.isInitialized()) ownExecutor.value.shutdown()
        }
    }

    // --------------------------------------------------------------- calls

    /**
     * One request/response operation, e.g. `doc.get` or `location.last`.
     * [args] and the value given to [callback] are typed values.
     */
    @JvmOverloads
    fun call(
        operation: String,
        args: Any?,
        callback: NativeCallback,
        instance: String? = null,
        parent: String? = null,
        live: NativeCall,
    ) {
        val r = replay
        if (r != null) {
            if (!adapter.required) return live.start(callback)
            return replayCall(r, operation, args, callback)
        }
        val rec = recorder
        if (rec == null || !rec.isCapturing) return live.start(callback)
        if (operation !in adapter.operations) {
            rec.gap(DependencyGaps.OPERATION_UNSUPPORTED, "$operation is not recorded by ${adapter.id}")
            return live.start(callback)
        }
        val op = rec.nextId("o")
        val key = key(operation, args)
        rec.record(
            InteractionTypes.NATIVE_CALL,
            linkedMapOf<String, Any?>("op" to op, "operation" to operation).also {
                if (instance != null) it["instance"] = instance
                if (parent != null) it["parent"] = parent
                it["args"] = rec.encoder.encode(args).toJson()
                it["key"] = key
                it["occurrence"] = rec.occurrence(key)
            },
        )
        val done = AtomicBoolean(false)
        val recording = object : NativeCallback {
            override fun onResult(value: Any?) {
                if (!done.compareAndSet(false, true)) return
                rec.record(InteractionTypes.NATIVE_RESULT, linkedMapOf("op" to op, "value" to rec.encoder.encode(value).toJson()))
                callback.onResult(value)
            }

            override fun onError(error: NativeError) {
                if (!done.compareAndSet(false, true)) return
                rec.record(InteractionTypes.NATIVE_ERROR, errorData("op", op, error, rec))
                callback.onError(error)
            }
        }
        try {
            live.start(recording)
        } catch (e: RuntimeException) {
            rec.gap(DependencyGaps.CAPTURE_FAILED, "$operation threw ${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun replayCall(r: InteractionReplay, operation: String, args: Any?, callback: NativeCallback) {
        val dep = r.dependencyFor(adapter.id)
        val key = key(operation, args)
        val recorded = dep?.let { r.take(it.id, InteractionTypes.NATIVE_CALL, key) }
        if (recorded == null) {
            unrecorded(r, InteractionTypes.NATIVE_CALL, operation, key)
            deliver { callback.onError(NativeError(NativeError.UNRECORDED, "BugCapsule replay: no recorded $operation call")) }
            return
        }
        val scope = "native.op:${recorded.data["op"]}"
        r.bind(scope, InteractionHandler { ev ->
            r.unbind(scope)
            deliver {
                when (ev.type) {
                    InteractionTypes.NATIVE_RESULT -> {
                        val p = ev.payload("value")
                        if (p == null || !p.replayable) {
                            diverge("native.noPayload", "$scope $operation result (${p?.mode?.wire ?: "missing"})")
                            callback.onError(NativeError(NativeError.NO_PAYLOAD, "BugCapsule replay: $operation result was not recorded"))
                        } else {
                            callback.onResult(p.decoded)
                        }
                    }
                    else -> callback.onError(recordedError(ev))
                }
            }
        })
    }

    // ------------------------------------------------------- subscriptions

    /**
     * A stream of events, e.g. `location.updates` or `authStateChanges`.
     * Returns the handle the app cancels; [subscriber] gets typed values.
     */
    @JvmOverloads
    fun subscribe(
        operation: String,
        args: Any?,
        subscriber: NativeSubscriber,
        instance: String? = null,
        parent: String? = null,
        live: NativeSubscribe,
    ): NativeSubscription {
        val r = replay
        if (r != null) {
            if (!adapter.required) return live.start(subscriber)
            return replaySubscribe(r, operation, args, subscriber)
        }
        val rec = recorder
        if (rec == null || !rec.isCapturing) return live.start(subscriber)
        if (operation !in adapter.operations) {
            rec.gap(DependencyGaps.OPERATION_UNSUPPORTED, "$operation is not recorded by ${adapter.id}")
            return live.start(subscriber)
        }
        val sub = rec.nextId("n")
        if (!rec.open("native.sub", sub)) return live.start(subscriber)
        openSubs.incrementAndGet()
        val key = key(operation, args)
        rec.record(
            InteractionTypes.NATIVE_SUBSCRIBE,
            linkedMapOf<String, Any?>("sub" to sub, "operation" to operation).also {
                if (instance != null) it["instance"] = instance
                if (parent != null) it["parent"] = parent
                it["args"] = rec.encoder.encode(args).toJson()
                it["key"] = key
                it["occurrence"] = rec.occurrence(key)
            },
        )
        val ended = AtomicBoolean(false)
        val index = AtomicInteger()
        fun end() {
            if (ended.compareAndSet(false, true)) {
                rec.closed(sub)
                openSubs.decrementAndGet()
            }
        }
        val recording = object : NativeSubscriber {
            override fun onEvent(value: Any?) {
                if (ended.get()) return
                rec.record(
                    InteractionTypes.NATIVE_EVENT,
                    linkedMapOf("sub" to sub, "index" to index.getAndIncrement(), "value" to rec.encoder.encode(value).toJson()),
                )
                subscriber.onEvent(value)
            }

            override fun onError(error: NativeError) {
                if (ended.get()) return
                rec.record(InteractionTypes.NATIVE_ERROR, errorData("sub", sub, error, rec))
                end()
                subscriber.onError(error)
            }

            override fun onDone() {
                if (ended.get()) return
                rec.record(InteractionTypes.NATIVE_DONE, linkedMapOf("sub" to sub))
                end()
                subscriber.onDone()
            }
        }
        val handle = try {
            live.start(recording)
        } catch (e: RuntimeException) {
            rec.gap(DependencyGaps.CAPTURE_FAILED, "$operation threw ${e.javaClass.simpleName}")
            end()
            throw e
        }
        return NativeSubscription {
            if (!ended.get()) {
                rec.record(InteractionTypes.NATIVE_CANCEL, linkedMapOf("sub" to sub))
                end()
            }
            handle.cancel()
        }
    }

    private fun replaySubscribe(r: InteractionReplay, operation: String, args: Any?, subscriber: NativeSubscriber): NativeSubscription {
        val dep = r.dependencyFor(adapter.id)
        val key = key(operation, args)
        val recorded = dep?.let { r.take(it.id, InteractionTypes.NATIVE_SUBSCRIBE, key) }
        if (recorded == null) {
            unrecorded(r, InteractionTypes.NATIVE_SUBSCRIBE, operation, key)
            deliver { subscriber.onError(NativeError(NativeError.UNRECORDED, "BugCapsule replay: no recorded $operation subscription")) }
            return NativeSubscription {}
        }
        val sub = recorded.data["sub"]
        val scope = "native.sub:$sub"
        val ended = AtomicBoolean(false)
        r.bind(scope, InteractionHandler { ev ->
            if (ev.type != InteractionTypes.NATIVE_EVENT) {
                ended.set(true)
                r.unbind(scope)
            }
            deliver {
                when (ev.type) {
                    InteractionTypes.NATIVE_EVENT -> {
                        val p = ev.payload("value")
                        if (p == null || !p.replayable) {
                            diverge("native.noPayload", "$scope $operation event #${ev.data["index"]} (${p?.mode?.wire ?: "missing"})")
                        } else {
                            subscriber.onEvent(p.decoded)
                        }
                    }
                    InteractionTypes.NATIVE_DONE -> subscriber.onDone()
                    else -> subscriber.onError(recordedError(ev))
                }
            }
        })
        return NativeSubscription {
            if (!ended.compareAndSet(false, true)) return@NativeSubscription
            r.unbind(scope)
            if (r.takeNext(scope, CANCEL) == null) diverge("native.unrecorded", "$scope: $operation cancelled, not in the recording")
        }
    }

    // -------------------------------------------------------------- shared

    /** `<operation>||<hash of redacted arguments without nondeterministic fields>`, identical in capture and replay. */
    fun key(operation: String, args: Any?): String {
        val ignored = adapter.nondeterministicFields(operation)
        val input = if (ignored.isNotEmpty() && args is Map<*, *>) args.filterKeys { it !in ignored } else args
        val json = try {
            TypedValue.redact(TypedValue.encode(input), redactor()).first
        } catch (_: Exception) {
            input?.javaClass?.name ?: "null"
        }
        return interactionKey(operation, null, json)
    }

    private fun unrecorded(r: InteractionReplay, type: String, operation: String, key: String) {
        val dep = r.dependencyFor(adapter.id)
        val mismatch = dep != null && r.hasUnconsumed(dep.id, type, "$operation|")
        diverge(
            if (mismatch) "native.mismatch" else "native.unrecorded",
            if (mismatch) "$operation: arguments differ from the recording ($key)" else "$operation: no recording ($key)",
        )
    }

    private fun errorData(field: String, id: String, error: NativeError, rec: DependencyRecorder): Map<String, Any?> =
        linkedMapOf<String, Any?>(field to id, "code" to error.code).also {
            if (error.message != null) it["message"] = rec.redactor.scrubText(error.message)
            if (error.details != null) it["details"] = rec.encoder.encode(error.details).toJson()
        }

    private fun recordedError(ev: RecordedInteraction): NativeError {
        val details = ev.payload("details")
        if (details != null && !details.replayable) diverge("native.noPayload", "${ev.scope} error details (${details.mode.wire})")
        return NativeError(
            code = ev.data["code"]?.toString() ?: "unknown",
            message = ev.data["message"] as? String,
            details = if (details != null && details.replayable) details.decoded else null,
        )
    }

    private fun deliver(block: () -> Unit) {
        // An app callback that throws fails on that thread, as it would with the SDK.
        val executor = replay?.deliveryExecutor ?: adapter.callbackExecutor ?: ownExecutor.value
        executor.execute { block() }
    }

    private companion object {
        val CANCEL = setOf(InteractionTypes.NATIVE_CANCEL)
    }
}
