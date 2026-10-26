package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.InteractionAdapterInfo
import dev.bugcapsule.android.core.InteractionHandler
import dev.bugcapsule.android.core.InteractionScript
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.RecordedInteraction

/**
 * Replay of a capsule's interaction dependencies for one attempt
 * (capsule-v2 §5): a thread-safe, timed wrapper around [InteractionScript].
 *
 * The replay clock is [clock] (milliseconds, monotonic, skipped idle time
 * included). With [autoPump] a daemon thread (`BugCapsule-interactions`)
 * delivers due events to bound handlers; handlers run on that thread while
 * the script lock is held, so they must only hand the event to the adapter's
 * own delivery thread. Without it (tests, the adapter conformance helper)
 * the caller calls [pump].
 *
 * Only `required` dependencies are replayed; diagnostic ones are never served.
 */
class InteractionReplay @JvmOverloads constructor(
    val capsule: Capsule,
    private val clock: () -> Long,
    private val diverge: (type: String, detail: String) -> Unit,
    private val activity: () -> Unit = {},
    autoPump: Boolean = true,
    maxHandlerWaitMs: Long = 5000,
    /**
     * Where adapters run replayed callbacks instead of their own threads
     * (tests and the adapter conformance helper pass a direct executor).
     */
    val deliveryExecutor: java.util.concurrent.Executor? = null,
) {
    private val lock = Object()

    /** The capsule's required dependencies. */
    val dependencies: List<CapsuleDependency> = capsule.manifest.dependencies.filter { it.required }

    /** Recorded time replay starts from. */
    val base: Long = if (capsule.checkpoint.kind == CheckpointKind.LAUNCH) 0 else capsule.checkpoint.t

    private val script = InteractionScript(capsule.events, dependencies.map { it.id }.toSet(), clock, base, maxHandlerWaitMs)

    @Volatile
    private var closed = false
    private var finished = false
    private val thread: Thread? = if (autoPump && !script.isEmpty) {
        Thread({ loop() }, "BugCapsule-interactions").apply {
            isDaemon = true
            start()
        }
    } else {
        null
    }

    /** The first required dependency recorded by [adapter], or null (then every operation is unrecorded). */
    fun dependencyFor(adapter: String): CapsuleDependency? = dependencies.firstOrNull { it.adapter == adapter }

    fun now(): Long = clock()

    val isEmpty: Boolean get() = script.isEmpty

    /** See [InteractionScript.take]. */
    fun take(dep: String, type: String, key: String): RecordedInteraction? = locked { script.take(dep, type, key) }

    /** See [InteractionScript.hasUnconsumed]. */
    fun hasUnconsumed(dep: String, type: String, prefix: String): Boolean = locked { script.hasUnconsumed(dep, type, prefix) }

    /** See [InteractionScript.takeNext]. */
    fun takeNext(scope: String, types: Set<String>): RecordedInteraction? = locked { script.takeNext(scope, types) }

    /** See [InteractionScript.bind]. */
    fun bind(scope: String, handler: InteractionHandler) = locked { script.bind(scope, handler) }

    fun unbind(scope: String) = locked { script.unbind(scope) }

    fun isBound(scope: String): Boolean = locked { script.isBound(scope) }

    /** Recorded input replay started now (events without an anchor follow the clock from here). */
    fun runStarted() = locked { script.runStarted(clock()) }

    /** Earliest due time of a deliverable event (see [InteractionScript.nextDueAt]). */
    val nextDueAt: Long? get() = locked { script.nextDueAt }

    /** Milliseconds until the next deliverable event (≤ 0: overdue), or null. */
    fun dueIn(): Long? = locked { script.nextDueAt?.let { it - clock() } }

    /** Delivers every due event; returns how many. */
    fun pump(): Int {
        val n = locked {
            val delivered = script.pump()
            for ((type, detail) in script.takeDivergences()) diverge(type, detail)
            delivered
        }
        if (n > 0) activity()
        return n
    }

    /**
     * Ends the attempt: last deliveries, then `<family>.unused` /
     * `<family>.unusedAfterFailure` for everything never matched or
     * delivered ([failureT]: recorded failure time). Idempotent.
     */
    fun finish(failureT: Long?) {
        closed = true
        synchronized(lock) {
            if (finished) return
            finished = true
            lock.notifyAll()
        }
        pump()
        locked { for ((type, detail) in script.unused(failureT)) diverge(type, detail) }
    }

    /** Stops the delivery thread without reporting. */
    fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        thread?.interrupt()
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(lock) {
        val out = block()
        lock.notifyAll()
        out
    }

    private fun loop() {
        while (!closed) {
            try {
                synchronized(lock) {
                    val delivered = script.pump()
                    for ((type, detail) in script.takeDivergences()) diverge(type, detail)
                    if (delivered > 0) activity()
                    val next = script.nextDueAt
                    // The clock can jump (idle compression): never sleep long while something is due.
                    val wait = when {
                        next != null -> (next - clock()).coerceIn(1, 20)
                        script.hasWaiting -> 100
                        else -> 500
                    }
                    if (!closed) lock.wait(wait)
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                diverge("dispatch.error", "interaction delivery: $e")
            }
        }
    }

    companion object {
        /**
         * Preflight (capsule-v2 §2): every required dependency has an installed
         * adapter with the same id that supports its schema, and its open
         * interactions at a periodic checkpoint can be restored. Returns the
         * `incompatible` message (`dependency.unsupported: ...`,
         * `dependency.schema: ...`, `dependency.unrestorable: ...`) or null.
         */
        @JvmStatic
        fun preflight(capsule: Capsule, adapters: List<InteractionAdapterInfo>): String? {
            val open = (J.list(J.obj(capsule.checkpoint.data["interactions"])?.get("open")) ?: emptyList())
                .mapNotNull { J.obj(it) }
            for (d in capsule.manifest.dependencies) {
                if (!d.required) continue
                val installed = adapters.filter { it.adapter == d.adapter }
                if (installed.isEmpty()) {
                    return "dependency.unsupported: ${d.adapter} schema ${d.schema} (dependency ${d.id}) has no installed adapter"
                }
                val adapter = installed.firstOrNull { d.schema in it.schemas }
                    ?: return "dependency.schema: ${d.adapter} schema ${d.schema} (dependency ${d.id}); the installed adapter replays schemas " +
                        installed.joinToString { "${it.schemas.first}..${it.schemas.last}" }
                if (capsule.checkpoint.kind == CheckpointKind.PERIODIC) {
                    val blocked = open.filter { it["dep"] == d.id && (it["restorable"] != true || !adapter.restorable) }
                    if (blocked.isNotEmpty()) {
                        return "dependency.unrestorable: ${d.adapter} (dependency ${d.id}) had ${blocked.size} open interaction(s) at the " +
                            "periodic checkpoint that cannot be restored (${blocked.joinToString { "${it["type"]} ${it["id"]}" }})"
                    }
                }
            }
            return null
        }
    }
}
