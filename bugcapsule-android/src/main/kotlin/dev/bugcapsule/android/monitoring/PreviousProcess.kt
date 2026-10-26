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
    private val exits: List<ExitRecord>,
    private val scope: Map<String, Any?>?,
    /** Release / environment the records were written with (read before the handler is reinstalled). */
    private val ctx: Map<String, Any?>,
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

    val isEmpty: Boolean get() = records.isEmpty() && exits.isEmpty()

    /**
     * One fatal event to send, built by [fill]. [scope] is the crashed
     * process's (empty when unknown: never the current process's).
     */
    class Crash(
        val mechanism: String,
        val timestampMs: Long?,
        val release: String?,
        val environment: String?,
        val scope: Map<String, Any?>,
        val fill: (MutableMap<String, Any?>) -> Unit,
    )

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
                            "signal", record.timestampMs,
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
            if (exit in used) continue
            try {
                val s = scopeOf(exit.pid) ?: emptyMap()
                val mechanism = when (exit.reason) {
                    ExitRecord.REASON_ANR -> "anr"
                    ExitRecord.REASON_LOW_MEMORY -> "oom"
                    else -> "signal"
                }
                emit(
                    Crash(mechanism, exit.timestampMs, J.str(s["release"]), J.str(s["environment"]), s) { ev ->
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
        }
    }

    companion object {
        /**
         * Call before [NativeCrashes.install] (it rewrites the records' context).
         * [exits]: null when exit records are not read (turned off).
         */
        fun collect(native: NativeCrashes, exits: ExitReasons?, scopeFile: File): PreviousProcess {
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
            return PreviousProcess(native, records, exits?.takeNew()?.filter(ExitReasons::reportable) ?: emptyList(), scope, ctx)
        }

        /** Tests: replaces the system's exit records everywhere they are read. */
        @Volatile
        var exitInfoOverride: ExitInfoSource? = null

        /** This app's recent exit records from the system (API 30+), or [exitInfoOverride]. */
        fun systemExits(context: Context): ExitInfoSource = exitInfoOverride ?: ExitInfoSource {
            if (android.os.Build.VERSION.SDK_INT < 30) return@ExitInfoSource emptyList()
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return@ExitInfoSource emptyList()
            am.getHistoricalProcessExitReasons(null, 0, 16).map { info ->
                ExitRecord(info.pid, info.reason, info.timestamp, info.importance, info.description, info.processName) { info.traceInputStream }
            }
        }
    }
}
