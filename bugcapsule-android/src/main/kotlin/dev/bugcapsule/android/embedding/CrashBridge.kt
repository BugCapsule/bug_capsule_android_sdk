package dev.bugcapsule.android.embedding

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.core.Ids
import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Files
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.monitoring.AnrWatchdog
import dev.bugcapsule.android.monitoring.ExitReasons
import dev.bugcapsule.android.monitoring.InAppRules
import dev.bugcapsule.android.monitoring.Monitoring
import dev.bugcapsule.android.monitoring.NativeCrashes
import dev.bugcapsule.android.monitoring.PreviousProcess
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Native crash capture for SDKs that embed this one and send events
 * themselves: the BugCapsule Flutter SDK's Android plugin. Not for apps:
 * an app uses [BugCapsule.start], which does all of this and more.
 *
 * It runs the crash handlers without this SDK's own sessions, outbox or
 * transport, so the embedding SDK stays the only sender:
 *
 * - NDK signal handler (with the `bugcapsule-ndk` artifact), with the
 *   embedding SDK's scope written into each crash record;
 * - main-thread ANR watchdog;
 * - Android 11+ exit records: ANRs the system killed the app for, native
 *   crashes (every thread from the tombstone, Android 12+), foreground
 *   low-memory kills.
 *
 * Events are monitoring-v1 `event` JSON (docs/monitoring-v1.md §2) with
 * `runtime: "android"`; the embedding SDK adds its device contexts, may
 * change `runtime`, redacts and sends them. They are kept on disk until
 * [takePendingEvents] returns them. The iOS SDK's `BugCapsuleCrashBridge`
 * has the same shape.
 */
object CrashBridge {
    /** What [install] turns on. [inAppIncludes] default to the app's package. */
    class Options @JvmOverloads constructor(
        val nativeCrashes: Boolean = true,
        val anr: Boolean = true,
        val anrTimeoutMs: Long = 5_000,
        val exitInfo: Boolean = true,
        val inAppIncludes: List<String> = emptyList(),
    )

    /** Told about events captured while the app runs (ANRs); crashes of earlier runs are only in [takePendingEvents]. */
    fun interface Listener {
        fun onEvent(eventJson: String)
    }

    private class State(
        val dir: File,
        val release: String,
        val environment: String,
        val appId: String,
        val inApp: InAppRules,
        val native: NativeCrashes,
        val nativeInstalled: Boolean,
        val executor: ScheduledExecutorService,
        val lastRunCrashed: Boolean,
    ) {
        @Volatile
        var scope: Map<String, Any?> = emptyMap()

        @Volatile
        var scopeFileScheduled = false

        var anr: AnrWatchdog? = null

        val events = File(dir, "events")
        val scopeFile = File(dir, "scope.json")
    }

    private val lock = Any()

    @Volatile
    private var state: State? = null

    @Volatile
    private var listener: Listener? = null

    /**
     * Installs the handlers and reads what earlier runs left behind.
     * Returns false (and does nothing) when this SDK's own monitoring is
     * running in the process, or [context] has no files dir. Idempotent:
     * a second call returns true and keeps the first settings.
     */
    @JvmStatic
    @JvmOverloads
    fun install(context: Context, release: String, environment: String, options: Options = Options()): Boolean = synchronized(lock) {
        if (state != null) return true
        if (BugCapsule.session != null) {
            SdkLog.w("CrashBridge not installed: BugCapsule.start() already reports crashes in this process")
            return false
        }
        try {
            val app = context.applicationContext ?: context
            val dir = File(app.noBackupFilesDir, "bugcapsule-crash-bridge").apply { mkdirs() }
            val native = NativeCrashes(File(dir, "native"))
            val exits = if (options.exitInfo) {
                ExitReasons(File(dir, "exit_info.json"), app.packageName, PreviousProcess.systemExits(app))
            } else {
                null
            }
            val previous = PreviousProcess.collect(native, exits, File(dir, "scope.json"))
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "BugCapsule-CrashBridge").apply { isDaemon = true } }
            val installed = options.nativeCrashes && native.install(release, environment)
            val s = State(
                dir, release, environment, app.packageName,
                InAppRules(options.inAppIncludes.ifEmpty { listOf(app.packageName) }, emptyList()),
                native, installed, executor, lastRunCrashed = previous.crashedAtMs != null,
            )
            state = s
            if (!previous.isEmpty) {
                executor.execute {
                    previous.report(s.appId, s.inApp) { c ->
                        val ev = event(s, "fatal", c.timestampMs, c.release ?: release, c.environment ?: environment, c.scope, c.fill)
                        store(s, ev)
                    }
                }
            }
            if (options.anr) startAnrWatchdog(s, app, options.anrTimeoutMs)
            true
        } catch (e: Throwable) {
            SdkLog.w("CrashBridge not installed: $e")
            false
        }
    }

    /** True when the previous run ended in a native crash (its session is `crashed`, not `abnormal`). */
    @JvmStatic
    val lastRunCrashed: Boolean
        get() = state?.lastRunCrashed ?: false

    /** True when the NDK handler is installed (the `bugcapsule-ndk` artifact is present). */
    @JvmStatic
    val nativeCrashHandlerInstalled: Boolean
        get() = state?.nativeInstalled ?: false

    /**
     * Sets the scope crash events get: one JSON object with any of `level`,
     * `fingerprint`, `tags`, `user`, `extra`, `contexts`, `breadcrumbs` in
     * event form, already redacted. Copied into the NDK handler at once (at
     * most 64 KiB; larger is ignored there) and to disk at most every second
     * (for ANR and low-memory exits reported on the next launch).
     */
    @JvmStatic
    fun updateScope(json: String) {
        val s = state ?: return
        val parsed = try {
            J.obj(Json.parse(json))
        } catch (_: Exception) {
            null
        } ?: return
        val withProcess = LinkedHashMap<String, Any?>().apply {
            put("pid", android.os.Process.myPid())
            put("release", s.release)
            put("environment", s.environment)
            putAll(parsed)
        }
        s.scope = withProcess
        if (s.nativeInstalled) s.native.setScope(Json.stringify(withProcess).toByteArray(Charsets.UTF_8))
        if (!s.scopeFileScheduled) {
            s.scopeFileScheduled = true
            s.executor.schedule({
                s.scopeFileScheduled = false
                try {
                    Files.writeAtomically(s.scopeFile, Json.stringify(s.scope).toByteArray(Charsets.UTF_8))
                } catch (e: Throwable) {
                    SdkLog.once("CrashBridge could not persist the scope: $e")
                }
            }, SCOPE_FILE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Events not taken yet, oldest first, each removed once returned. Waits
     * up to [timeoutMs] for the previous run's crashes to be read (they come
     * from tombstones and ANR dumps, read in the background).
     */
    @JvmStatic
    @JvmOverloads
    fun takePendingEvents(timeoutMs: Long = 5_000): List<String> {
        val s = state ?: return emptyList()
        return try {
            s.executor.submit<List<String>> {
                val files = (s.events.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }
                files.mapNotNull { f ->
                    val text = try {
                        f.readText()
                    } catch (_: Exception) {
                        null
                    }
                    Files.deleteQuietly(f)
                    text
                }
            }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            SdkLog.w("CrashBridge could not read pending events: $e")
            emptyList()
        }
    }

    @JvmStatic
    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    private fun startAnrWatchdog(s: State, context: Context, timeoutMs: Long) {
        val main = Looper.getMainLooper()
        val handler = Handler(main)
        s.anr = AnrWatchdog(
            timeoutMs = timeoutMs.coerceAtLeast(1_000),
            postToMain = { handler.post(it) },
            mainThread = main.thread,
            isForeground = { isForeground(context) },
            onAnr = { stack, blocked ->
                val ev = event(s, "error", null, s.release, s.environment, s.scope) { e ->
                    AnrWatchdog.fill(e, stack, blocked, timeoutMs, main.thread, s.inApp)
                }
                s.executor.execute {
                    val json = store(s, ev)
                    if (json != null) {
                        try {
                            listener?.onEvent(json)
                        } catch (e: Throwable) {
                            SdkLog.once("CrashBridge listener failed: $e")
                        }
                    }
                }
            },
        ).also { it.start() }
    }

    private fun isForeground(context: Context): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Throwable) {
        true
    }

    private fun event(
        s: State,
        level: String,
        timestampMs: Long?,
        release: String,
        environment: String,
        scope: Map<String, Any?>,
        fill: (MutableMap<String, Any?>) -> Unit,
    ): MutableMap<String, Any?> {
        val ev = linkedMapOf<String, Any?>(
            "eventId" to Ids.uuidV4(),
            "timestamp" to Iso8601.format(timestampMs ?: System.currentTimeMillis()),
            "level" to level,
            "runtime" to "android",
            "release" to release,
            "environment" to environment,
        )
        fill(ev)
        Monitoring.applyPreviousScope(ev, scope)
        ev["level"] = level
        return ev
    }

    /** Writes [ev] to the pending events (names sort by time); returns its JSON. */
    private fun store(s: State, ev: Map<String, Any?>): String? = try {
        val json = Json.stringify(ev)
        s.events.mkdirs()
        Files.writeAtomically(File(s.events, "${System.currentTimeMillis()}-${ev["eventId"]}.json"), json.toByteArray(Charsets.UTF_8))
        json
    } catch (e: Throwable) {
        SdkLog.w("CrashBridge could not store an event: $e")
        null
    }

    /** Tests: uninstalls everything (the NDK handler stays installed: it cannot be told apart from another user). */
    @JvmStatic
    internal fun resetForTest() = synchronized(lock) {
        state?.anr?.shutdown()
        state?.executor?.shutdownNow()
        state = null
        listener = null
    }

    private const val SCOPE_FILE_INTERVAL_MS = 1_000L
}
