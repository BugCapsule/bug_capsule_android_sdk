package dev.bugcapsule.android.monitoring

import android.content.Context
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.SdkLog
import java.io.File

/**
 * What earlier processes of the app left behind, read once at start: our
 * native crash records, the system's exit records not handled yet, and the
 * scope the last process mirrored to [scopeFile]. Used by the SDK's own
 * monitoring and by [dev.bugcapsule.android.embedding.CrashBridge].
 */
internal class PreviousProcess private constructor(
    private val native: NativeCrashes,
    private val records: List<Pair<File, NativeCrashRecord?>>,
    private val exitReasons: ExitReasons?,
    /** New exit records worth an event, oldest first. */
    private val exits: List<ExitRecord>,
    /** Newest of all new exit records (reportable or not): handled once [report] is done. */
    private val newestExitMs: Long?,
    private val scope: Map<String, Any?>?,
    /** Release / environment the records were written with (read before the handler is reinstalled). */
    private val ctx: Map<String, Any?>,
    /** The system's ANR kill of a process whose hang the watchdog reported while it ran, with that event's id. */
    private val watchdogAnr: Pair<ExitRecord, String?>? = null,
) {
    /** The previous run ended with a native crash at this time (its session is `crashed`, not `abnormal`). */
    val crashedAtMs: Long? = run {
        var at: Long? = null
        for ((file, record) in records) {
            if (record != null) at = maxOf(at ?: 0L, record.timestampMs ?: file.lastModified())
        }
        for (exit in exits) {
            if (exit.reason == ExitRecord.REASON_CRASH_NATIVE) at = maxOf(at ?: 0L, exit.timestampMs)
        }
        at
    }

    val isEmpty: Boolean get() = records.isEmpty() && exits.isEmpty() && watchdogAnr == null

    /**
     * One fatal event to send, built by [fill]. [scope] is the crashed
     * process's (empty when unknown: never the current process's).
     */
    class Crash(
        /** The crashed process (its crash journal is `journal/<pid>`), when known. */
        val pid: Int?,
        val mechanism: String,
        val timestampMs: Long?,
        val release: String?,
        val environment: String?,
        val scope: Map<String, Any?>,
        /**
         * Set when the event was already sent while the process ran (an ANR
         * the watchdog reported before the system killed the app): no new
         * event, but a crash capsule may still link to this one.
         */
        val reportedEventId: String? = null,
        val fill: (MutableMap<String, Any?>) -> Unit,
    ) {
        val alreadyReported: Boolean get() = reportedEventId != null
    }

    /**
     * Calls [emit] for each crash, oldest records first, and deletes the
     * records. Reads tombstones and ANR dumps: call off the main thread.
     */
    fun report(appId: String, inApp: InAppRules, emit: (Crash) -> Unit) {
        val used = HashSet<ExitRecord>()
        fun tombstone(exit: ExitRecord?): Tombstone? =
            if (exit == null) null else exit.openTrace()?.use { Tombstone.parse(it.readBytes()) }
        fun scopeOf(pid: Int?): Map<String, Any?>? = scope?.takeIf { pid != null && (it["pid"] as? Number)?.toInt() == pid }

        for ((file, record) in records) {
            try {
                if (record != null) {
                    val pid = file.name.substringBefore('-').toIntOrNull()
                    val exit = exits.firstOrNull { it.reason == ExitRecord.REASON_CRASH_NATIVE && it.pid == pid }
                    exit?.let { used.add(it) }
                    val stone = tombstone(exit)
                    val s = record.scope ?: scopeOf(pid) ?: emptyMap()
                    emit(
                        Crash(
                            pid, "signal", record.timestampMs,
                            J.str(s["release"]) ?: ctx["release"] as? String,
                            J.str(s["environment"]) ?: ctx["environment"] as? String,
                            s,
                        ) { ev ->
                            NativeCrashes.fill(ev, record, appId, ElfBuildId::forPath)
                            if (stone != null) ExitReasons.addTombstoneThreads(ev, stone, appId)
                        },
                    )
                }
            } catch (e: Throwable) {
                SdkLog.w("could not report native crash record ${file.name}: $e")
            }
            native.delete(file)
        }
        for (exit in exits) {
            if (exit in used) {
                exitReasons?.markHandled(exit.timestampMs)
                continue
            }
            try {
                val s = scopeOf(exit.pid) ?: emptyMap()
                val mechanism = when (exit.reason) {
                    ExitRecord.REASON_ANR -> "anr"
                    ExitRecord.REASON_LOW_MEMORY -> "oom"
                    else -> "signal"
                }
                emit(
                    Crash(exit.pid, mechanism, exit.timestampMs, J.str(s["release"]), J.str(s["environment"]), s) { ev ->
                        when (exit.reason) {
                            ExitRecord.REASON_ANR -> ExitReasons.fillAnr(ev, exit, inApp)
                            ExitRecord.REASON_LOW_MEMORY -> ExitReasons.fillLowMemory(ev, exit)
                            else -> ExitReasons.fillNativeFromTombstone(ev, exit, tombstone(exit), appId)
                        }
                    },
                )
            } catch (e: Throwable) {
                SdkLog.w("could not report exit record of pid ${exit.pid}: $e")
            }
            // Only now: a process that dies before this point reports the exit next time.
            exitReasons?.markHandled(exit.timestampMs)
        }
        watchdogAnr?.let { (exit, eventId) ->
            if (eventId != null) {
                try {
                    val s = scopeOf(exit.pid) ?: emptyMap()
                    emit(
                        Crash(
                            exit.pid, "anr", exit.timestampMs, J.str(s["release"]), J.str(s["environment"]), s,
                            fill = { ev -> ExitReasons.fillAnr(ev, exit, inApp) },
                            reportedEventId = eventId,
                        ),
                    )
                } catch (e: Throwable) {
                    SdkLog.w("could not report the ANR exit of pid ${exit.pid}: $e")
                }
            }
            exitReasons?.markHandled(exit.timestampMs)
        }
        newestExitMs?.let { exitReasons?.markHandled(it) }
    }

    companion object {
        /**
         * Call before [NativeCrashes.install] (it rewrites the records' context).
         * [exits]: null when exit records are not read (turned off).
         */
        fun collect(native: NativeCrashes, exits: ExitReasons?, scopeFile: File, watchdogAnrFile: File? = null): PreviousProcess {
            val records = native.pending().map { file ->
                file to try {
                    NativeCrashRecord.parse(file.readText())
                } catch (e: Throwable) {
                    SdkLog.w("could not read native crash record ${file.name}: $e")
                    null
                }
            }
            val scope = try {
                if (scopeFile.exists()) J.obj(Json.parse(scopeFile.readText())) else null
            } catch (_: Exception) {
                null
            }
            val ctx = if (records.isEmpty()) emptyMap() else native.previousContext()
            // The ANR watchdog already reported the hang of this process (an `error` event while it ran):
            // the system's ANR exit for it would count the same hang twice.
            val watchdog = try {
                if (watchdogAnrFile?.exists() == true) J.obj(Json.parse(watchdogAnrFile.readText())) else null
            } catch (_: Exception) {
                null
            }
            val watchdogPid = J.int(watchdog?.get("pid"))
            val fresh = exits?.takeNew() ?: emptyList()
            val reportable = fresh.filter { ExitReasons.reportable(it) && !(it.reason == ExitRecord.REASON_ANR && it.pid == watchdogPid) }
            val watchdogExit = fresh.lastOrNull { it.reason == ExitRecord.REASON_ANR && it.pid == watchdogPid }
            val newest = fresh.maxOfOrNull { it.timestampMs }
            if (reportable.isEmpty() && watchdogExit == null && newest != null) exits?.markHandled(newest)
            return PreviousProcess(
                native, records, exits, reportable, newest, scope, ctx,
                watchdogExit?.let { it to J.str(watchdog?.get("eventId")) },
            )
        }

        /**
         * Remembers that the watchdog reported an ANR of this process (see
         * [collect]), with the id of its event when there is one: a crash
         * capsule built after the system kills the app links to it.
         */
        fun noteWatchdogAnr(file: File, eventId: String? = null) {
            try {
                dev.bugcapsule.android.internal.Files.writeAtomically(
                    file,
                    Json.stringify(
                        linkedMapOf<String, Any?>("pid" to android.os.Process.myPid()).also { if (eventId != null) it["eventId"] = eventId },
                    ).toByteArray(),
                )
            } catch (_: Exception) {
            }
        }

        /**
         * True in the app's main process. Exit records and the persisted scope
         * belong to it: other processes of the app must not read or overwrite them.
         */
        fun isMainProcess(context: Context): Boolean {
            val name = try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    android.app.Application.getProcessName()
                } else {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
                }
            } catch (_: Throwable) {
                null
            }
            return name.isNullOrEmpty() || name == context.packageName
        }

        /** Tests: replaces the system's exit records everywhere they are read. */
        @Volatile
        var exitInfoOverride: ExitInfoSource? = null

        /** This app's recent exit records from the system (API 30+), or [exitInfoOverride]. */
        fun systemExits(context: Context): ExitInfoSource = exitInfoOverride ?: ExitInfoSource {
            if (android.os.Build.VERSION.SDK_INT < 30) return@ExitInfoSource emptyList()
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return@ExitInfoSource emptyList()
            am.getHistoricalProcessExitReasons(null, 0, 16).map { info ->
                ExitRecord(info.pid, info.reason, info.timestamp, info.importance, info.description, info.processName, info.status) { info.traceInputStream }
            }
        }
    }
}
