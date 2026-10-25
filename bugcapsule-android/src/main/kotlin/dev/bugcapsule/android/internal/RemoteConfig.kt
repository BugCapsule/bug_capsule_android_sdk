package dev.bugcapsule.android.internal

import android.util.Log
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import java.io.File
import java.util.Random

/** Capture config the backend can change at runtime (kill switch, sampling, bodies, redaction, monitoring). */
data class RemoteCaptureConfig(
    val enabled: Boolean,
    val sampleRate: Double,
    val maxCapsuleBytes: Long,
    val captureNetworkBodies: Boolean,
    val redactKeys: List<String>,
    val configTtlSeconds: Long,
    /** Wall clock (epoch ms) of the successful fetch this config came from. */
    val fetchedAtMs: Long,
    // monitoring-v1 §10. Null: the server did not send the field (older backend).
    /** False: no envelopes are sent (capsules unaffected). */
    val monitoringEnabled: Boolean = true,
    val errorSampleRate: Double? = null,
    val tracesSampleRate: Double? = null,
    val sessionTracking: Boolean = true,
    val maxBreadcrumbs: Int? = null,
    /** When set, replaces the app's `tracePropagationTargets`. */
    val tracePropagationTargets: List<String>? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "sampleRate" to sampleRate,
        "maxCapsuleBytes" to maxCapsuleBytes,
        "captureNetworkBodies" to captureNetworkBodies,
        "redactKeys" to redactKeys,
        "configTtlSeconds" to configTtlSeconds,
        "fetchedAtMs" to fetchedAtMs,
        "monitoringEnabled" to monitoringEnabled,
        "errorSampleRate" to errorSampleRate,
        "tracesSampleRate" to tracesSampleRate,
        "sessionTracking" to sessionTracking,
        "maxBreadcrumbs" to maxBreadcrumbs,
        "tracePropagationTargets" to tracePropagationTargets,
    )

    companion object {
        /** Parses `/v1/sdk/config` (or the cache). Throws [IllegalArgumentException] on wrong types; clamps values. */
        @JvmStatic
        fun fromJson(j: Map<String, Any?>, fetchedAtMs: Long? = null): RemoteCaptureConfig {
            fun <T> field(key: String, type: Class<T>): T? {
                val v = j[key] ?: return null
                if (type == Double::class.javaObjectType && v is Number) @Suppress("UNCHECKED_CAST") return v.toDouble() as T
                if (type == Long::class.javaObjectType && (v is Long || v is Int)) @Suppress("UNCHECKED_CAST") return (v as Number).toLong() as T
                if (type.isInstance(v)) return type.cast(v)
                throw IllegalArgumentException("remote config \"$key\" has type ${v.javaClass.simpleName}")
            }
            fun rate(key: String): Double? = field(key, Double::class.javaObjectType)?.let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) }
            val rate = field("sampleRate", Double::class.javaObjectType) ?: 1.0
            val ttl = field("configTtlSeconds", Long::class.javaObjectType) ?: 300L
            val maxBytes = field("maxCapsuleBytes", Long::class.javaObjectType) ?: (5L * 1024 * 1024)
            return RemoteCaptureConfig(
                enabled = field("enabled", Boolean::class.javaObjectType) ?: true,
                sampleRate = if (rate.isNaN()) 0.0 else rate.coerceIn(0.0, 1.0),
                maxCapsuleBytes = maxBytes.coerceAtLeast(1),
                captureNetworkBodies = field("captureNetworkBodies", Boolean::class.javaObjectType) ?: true,
                redactKeys = (field("redactKeys", List::class.java) ?: emptyList<Any?>()).map { it.toString() },
                configTtlSeconds = ttl.coerceIn(0, 86_400),
                fetchedAtMs = fetchedAtMs ?: field("fetchedAtMs", Long::class.javaObjectType) ?: 0L,
                monitoringEnabled = field("monitoringEnabled", Boolean::class.javaObjectType) ?: true,
                errorSampleRate = rate("errorSampleRate"),
                tracesSampleRate = rate("tracesSampleRate"),
                sessionTracking = field("sessionTracking", Boolean::class.javaObjectType) ?: true,
                maxBreadcrumbs = field("maxBreadcrumbs", Long::class.javaObjectType)?.coerceIn(0, 100)?.toInt(),
                tracePropagationTargets = field("tracePropagationTargets", List::class.java)?.map { it.toString() },
            )
        }
    }
}

/** Where the last good config is persisted between launches. */
interface RemoteConfigCache {
    fun read(scope: String): RemoteCaptureConfig?
    fun write(scope: String, config: RemoteCaptureConfig)
}

internal class FileConfigCache(private val file: File) : RemoteConfigCache {
    override fun read(scope: String): RemoteCaptureConfig? = try {
        if (!file.exists()) {
            null
        } else {
            val json = J.obj(Json.parse(file.readText()))
            if (json == null || json["scope"] != scope) null else J.obj(json["config"])?.let { RemoteCaptureConfig.fromJson(it) }
        }
    } catch (_: Exception) {
        null
    }

    override fun write(scope: String, config: RemoteCaptureConfig) {
        Files.writeAtomically(file, Json.stringify(linkedMapOf("scope" to scope, "config" to config.toJson())).toByteArray())
    }
}

fun interface Cancellable {
    fun cancel()
}

/** Delayed tasks (the SDK executor in the app, a manual clock in tests). */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): Cancellable
}

/**
 * Keeps the remote capture config current and decides what it allows (port
 * of `remote_config.dart`):
 *
 * * **Startup**: [loadCached] restores the last config for this backend and
 *   ingest key ([scope]); a cached kill switch applies however old it is.
 * * **Refresh**: after a success the next fetch follows the TTL (at least
 *   [minIntervalMs]); after a failure it backs off from [minIntervalMs] to
 *   [maxBackoffMs] with ±20 % jitter, honouring `Retry-After`. [onResumed]
 *   refreshes a stale config; [onPaused] stops the timer.
 * * **Uploads** need an enabled config fetched within [maxStaleMs].
 * * **Rejected key** (401/403): capture stops for the rest of the session.
 *
 * Not thread-safe for mutation: the runtime calls it from its single
 * executor; [current], [uploadsAllowed] and [captureAllowed] may be read anywhere.
 */
class RemoteConfigController(
    private val fetch: () -> ConfigFetchResult,
    private val cache: RemoteConfigCache,
    val scope: String,
    private val onChange: (previous: RemoteCaptureConfig?, next: RemoteCaptureConfig, keyRejected: Boolean) -> Unit,
    private val scheduler: Scheduler,
    val maxStaleMs: Long = 7L * 24 * 60 * 60 * 1000,
    val minIntervalMs: Long = 30_000,
    val maxBackoffMs: Long = 30 * 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
    /** The backend refused the credential (401/403); called once until a fetch succeeds again. */
    private val onRejected: ((ConfigFetchResult.Failed) -> Unit)? = null,
) {
    @Volatile
    var current: RemoteCaptureConfig? = null
        private set

    @Volatile
    var keyRejected = false
        private set

    /** The last failed fetch; null after a success. */
    @Volatile
    var lastFailure: ConfigFetchResult.Failed? = null
        private set

    /** Wall clock (epoch ms) of the last successful fetch in this process (not from the cache). */
    @Volatile
    var fetchedThisSession: Long? = null
        private set
    private var failures = 0
    private var timer: Cancellable? = null
    private var paused = false
    private var disposed = false

    /** Delay of the currently scheduled refresh (tests). */
    var scheduledDelayMs: Long? = null
        private set

    private val ttlMs: Long get() = maxOf((current?.configTtlSeconds ?: 0) * 1000, minIntervalMs)
    private val ageMs: Long? get() = current?.let { nowMs() - it.fetchedAtMs }
    val isFresh: Boolean get() = ageMs?.let { it < ttlMs } ?: false
    val captureAllowed: Boolean get() = !keyRejected && (current?.enabled ?: true)
    val uploadsAllowed: Boolean
        get() {
            val c = current ?: return false
            return !keyRejected && c.enabled && nowMs() - c.fetchedAtMs <= maxStaleMs
        }

    fun loadCached() {
        current = try {
            cache.read(scope)
        } catch (e: Exception) {
            log("cached config unreadable: $e")
            null
        }
    }

    /** Fetches now (synchronously). */
    fun refresh() {
        if (disposed) return
        timer?.cancel()
        val result = try {
            fetch()
        } catch (e: Exception) {
            ConfigFetchResult.Failed(e.toString())
        }
        if (disposed) return
        when (result) {
            is ConfigFetchResult.Fetched -> {
                failures = 0
                lastFailure = null
                fetchedThisSession = nowMs()
                val previous = current
                current = result.config
                keyRejected = false
                try {
                    cache.write(scope, result.config)
                } catch (e: Exception) {
                    log("could not cache remote config: $e")
                }
                notify(previous, result.config, false)
                schedule(ttlMs)
            }
            is ConfigFetchResult.Failed -> {
                failures++
                lastFailure = result
                if (result.unauthorized && !keyRejected) {
                    keyRejected = true
                    val report = onRejected
                    if (report == null) {
                        log("ingest key rejected (HTTP ${result.status}); capture stopped for this session")
                    } else {
                        try {
                            report(result)
                        } catch (_: Exception) {
                            // diagnostics never break the SDK
                        }
                    }
                    val previous = current
                    notify(previous, disabledLike(previous), true)
                }
                val exp = minIntervalMs * (1L shl minOf(failures - 1, 16))
                var delay = minOf(exp, maxBackoffMs)
                val retryAfter = result.retryAfterMs
                if (retryAfter != null && retryAfter > delay) delay = retryAfter
                schedule(jitter(delay))
            }
        }
    }

    /** Refreshes first when the config is missing or older than its TTL. */
    fun ensureFresh() {
        if (!isFresh) refresh()
    }

    private fun notify(previous: RemoteCaptureConfig?, next: RemoteCaptureConfig, rejected: Boolean) {
        try {
            onChange(previous, next, rejected)
        } catch (e: Exception) {
            log("applying remote config failed: $e")
        }
    }

    private fun disabledLike(c: RemoteCaptureConfig?) = RemoteCaptureConfig(
        enabled = false,
        sampleRate = c?.sampleRate ?: 0.0,
        maxCapsuleBytes = c?.maxCapsuleBytes ?: 0,
        captureNetworkBodies = false,
        redactKeys = c?.redactKeys ?: emptyList(),
        configTtlSeconds = c?.configTtlSeconds ?: 0,
        fetchedAtMs = c?.fetchedAtMs ?: 0,
        monitoringEnabled = false,
    )

    private fun jitter(ms: Long): Long = Math.round(ms * (0.8 + random.nextDouble() * 0.4))

    private fun schedule(delayMs: Long) {
        timer?.cancel()
        scheduledDelayMs = null
        if (disposed || paused) return
        scheduledDelayMs = delayMs
        timer = scheduler.schedule(delayMs) { refresh() }
    }

    fun onPaused() {
        paused = true
        timer?.cancel()
        scheduledDelayMs = null
    }

    fun onResumed() {
        if (!paused || disposed) return
        paused = false
        if (isFresh) schedule(ttlMs - (ageMs ?: 0)) else refresh()
    }

    fun dispose() {
        disposed = true
        timer?.cancel()
    }

    private fun log(message: String) = SdkLog.w(message)
}

internal object SdkLog {
    const val TAG = "BugCapsule"
    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    fun w(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
            System.err.println("[bugcapsule] $message")
        }
    }

    fun e(message: String) {
        try {
            Log.e(TAG, message)
        } catch (_: Throwable) {
            System.err.println("[bugcapsule] $message")
        }
    }

    fun i(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            println("[bugcapsule] $message")
        }
    }

    fun once(message: String) {
        if (warned.add(message)) w(message)
    }
}
