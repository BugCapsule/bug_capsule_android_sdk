package dev.bugcapsule.android.replay

import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.internal.CrashFrames
import dev.bugcapsule.android.internal.Files
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.monitoring.ExitInfoSource
import dev.bugcapsule.android.monitoring.ExitRecord
import dev.bugcapsule.android.monitoring.NativeCrashRecord
import dev.bugcapsule.android.monitoring.Tombstone
import java.io.File

/**
 * How replay judges a crash capsule (docs/crash-capsules.md §4): the app
 * under test must die the way it died on the device.
 *
 * - In the replayed process: an uncaught JVM exception (the crash handler
 *   reports before the process dies) and the main thread blocked for
 *   [ANR_THRESHOLD_MS] (an ANR, reported while the process still lives).
 * - After the process died of a native signal or was killed (it cannot
 *   report): the engine leaves an [attempt marker][markerFile]; the CLI then
 *   runs `BugCapsuleReplayTest#judgeProcessDeath`, which reads the system's
 *   exit record (and tombstone) of the marked pid and reports for it.
 */
object CrashReplay {
    /** The main thread blocked this long during a crash capsule's replay is an ANR (Android's input dispatch timeout). */
    const val ANR_THRESHOLD_MS = 5_000L

    /** How the app under test died. [source]: `uncaught`, `watchdog`, `exit_info`, `tombstone`. */
    class Death(
        val type: String,
        val mechanism: String,
        val source: String,
        val message: String = "",
        val signal: Int? = null,
        val pid: Int? = null,
        val inAppFrame: String? = null,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>("mechanism" to mechanism, "type" to type).also {
            if (signal != null) it["signal"] = signal
            if (pid != null) it["pid"] = pid
            it["source"] = source
            if (inAppFrame != null) it["inAppFrame"] = inAppFrame
        }

        /** The crash as the report's `observed` failure, [t] on the replay clock. */
        fun toFailure(t: Long): FailureRecord = FailureRecord.crash(type, message, t, mechanism, signal, inAppFrame)
    }

    class Judgment(val outcome: ReplayOutcome, val reasonCode: String?)

    /**
     * Same `type` (signal, `ApplicationNotResponding`, `OutOfMemory`) is
     * `reproduced`, with `failure.different_frame` when both sides know the
     * in-app frame and it differs; another type is `inconclusive`,
     * `failure.different`. A capture that only knew "a native crash"
     * (`NativeCrash`, no tombstone before Android 12) matches any signal.
     */
    @JvmStatic
    fun judge(expected: FailureRecord, death: Death): Judgment {
        val expectedMechanism = J.str(expected.crash?.get("mechanism"))
        val same = death.type == expected.type ||
            (expected.type == NATIVE_CRASH && expectedMechanism == "signal" && death.mechanism == "signal")
        if (!same) return Judgment(ReplayOutcome.INCONCLUSIVE, FAILURE_DIFFERENT)
        val frame = J.str(expected.crash?.get("inAppFrame"))
        if (frame != null && death.inAppFrame != null && frame != death.inAppFrame) {
            return Judgment(ReplayOutcome.REPRODUCED, FAILURE_DIFFERENT_FRAME)
        }
        return Judgment(ReplayOutcome.REPRODUCED, null)
    }

    /** The main thread's in-app frame closest to the top of [stack] (`Class.method`), for ANRs. */
    @JvmStatic
    fun inAppFrame(stack: Array<StackTraceElement>, appPackage: String?): String? {
        if (appPackage.isNullOrEmpty()) return null
        return stack.firstOrNull { it.className.startsWith("$appPackage.") }?.let { "${it.className}.${it.methodName}" }
    }

    /** The death an exit record describes; null when the process did not crash (it exited or was stopped). */
    internal fun deathOf(exit: ExitRecord, appId: String): Death? = when (exit.reason) {
        ExitRecord.REASON_CRASH_NATIVE, ExitRecord.REASON_SIGNALED -> {
            val stone = if (exit.reason == ExitRecord.REASON_CRASH_NATIVE) exit.openTrace()?.use { Tombstone.parse(it.readBytes()) } else null
            if (stone != null) {
                val crashing = stone.threads.firstOrNull { it.id == stone.tid } ?: stone.threads.firstOrNull()
                Death(
                    type = stone.signalName ?: NativeCrashRecord.signalName(stone.signal),
                    mechanism = "signal",
                    source = "tombstone",
                    message = stone.abortMessage ?: NativeCrashRecord.signalDescription(stone.signal),
                    signal = stone.signal,
                    pid = exit.pid,
                    inAppFrame = crashing?.let { CrashFrames.inAppFrame(it.frames(appId)) },
                )
            } else if (exit.status > 0 && exit.status != SIGKILL) {
                Death(NativeCrashRecord.signalName(exit.status), "signal", "exit_info", exit.description ?: "", exit.status, exit.pid)
            } else if (exit.reason == ExitRecord.REASON_CRASH_NATIVE) {
                Death(NATIVE_CRASH, "signal", "exit_info", exit.description ?: "", null, exit.pid)
            } else {
                null
            }
        }
        ExitRecord.REASON_ANR -> Death("ApplicationNotResponding", "anr", "exit_info", exit.description ?: "", pid = exit.pid)
        ExitRecord.REASON_LOW_MEMORY -> Death("OutOfMemory", "oom", "exit_info", exit.description ?: "", pid = exit.pid)
        ExitRecord.REASON_CRASH -> Death("UncaughtException", "uncaught", "exit_info", exit.description ?: "", pid = exit.pid)
        else -> null
    }

    // ------------------------------------------------------------ marker

    /** Where an attempt that may die leaves what the post-mortem needs. */
    @JvmStatic
    fun markerFile(noBackupFilesDir: File): File = File(noBackupFilesDir, "bugcapsule-replay/crash-attempt.json")

    internal fun writeMarker(file: File, capsuleId: String, settings: ReplaySettings, expected: FailureRecord, strategy: ReplayStrategy, eventsExpected: Int) {
        try {
            val json = linkedMapOf<String, Any?>(
                "capsuleId" to capsuleId,
                "attemptId" to settings.attemptId,
                "attempt" to settings.attempt,
                "pid" to android.os.Process.myPid(),
                "startedAtMs" to System.currentTimeMillis(),
                "strategy" to strategy.wire,
                "eventsExpected" to eventsExpected,
                "expected" to expected.toJson(),
            )
            Files.writeAtomically(file, Json.stringify(json).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            SdkLog.w("could not write the crash attempt marker: $e")
        }
    }

    /**
     * The report of the attempt [marker] describes, from the system's exit
     * record of its pid (waits up to [timeoutMs] for it to appear). Deletes
     * the marker. [attemptId]: the CLI's current attempt; a marker of
     * another attempt is not reported as this one.
     */
    internal fun postMortem(marker: File, attemptId: String?, appId: String, exits: ExitInfoSource, timeoutMs: Long, apiLevel: Int): ReplayReport {
        val m = try {
            if (marker.exists()) J.obj(Json.parse(marker.readText())) else null
        } catch (_: Exception) {
            null
        }
        fun error(message: String, settingsAttempt: Int = 1) = ReplayReport(
            outcome = ReplayOutcome.ERROR, attempt = settingsAttempt, durationMs = 0, strategy = ReplayStrategy.AUTO,
            message = message, attemptId = attemptId, runtime = "android",
        )
        if (m == null) return error("no crashed replay attempt to judge on this device")
        val markedAttempt = J.str(m["attemptId"])
        if (attemptId != null && markedAttempt != null && markedAttempt != attemptId) {
            return error("the crashed attempt on this device is $markedAttempt, not $attemptId")
        }
        Files.deleteQuietly(marker)
        val expected = try {
            FailureRecord.fromJson(J.obj(m["expected"])!!)
        } catch (_: Exception) {
            return error("the attempt marker has no expected crash")
        }
        val pid = J.int(m["pid"]) ?: return error("the attempt marker has no pid")
        val attempt = J.int(m["attempt"]) ?: 1
        val startedAt = J.long(m["startedAtMs"]) ?: 0L
        if (apiLevel < 30) {
            return error("process $pid died; Android ${apiLevel} has no exit records: judge it from logcat (see docs/how-it-works.md)", attempt)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var exit: ExitRecord? = null
        while (true) {
            exit = try {
                exits.exits().firstOrNull { it.pid == pid && it.timestampMs >= startedAt - 1_000 }
            } catch (_: Throwable) {
                null
            }
            if (exit != null || System.currentTimeMillis() >= deadline) break
            Thread.sleep(200)
        }
        if (exit == null) return error("process $pid has no exit record yet; it may still be running", attempt)
        val death = deathOf(exit, appId)
            ?: return error("process $pid ended without crashing (exit reason ${exit.reason}: ${exit.description ?: "no description"})", attempt)
        val judgment = judge(expected, death)
        return ReplayReport(
            outcome = judgment.outcome,
            attempt = attempt,
            durationMs = (exit.timestampMs - startedAt).coerceAtLeast(0),
            strategy = ReplayStrategy.parse(J.str(m["strategy"])),
            expectedSignature = expected.signature,
            observed = death.toFailure((exit.timestampMs - startedAt).coerceAtLeast(0)),
            eventsExpected = J.int(m["eventsExpected"]),
            message = "the app process died: ${death.type} (${death.source})",
            attemptId = markedAttempt ?: attemptId,
            runtime = "android",
            reasonCode = judgment.reasonCode,
            death = death.toJson(),
        )
    }

    const val FAILURE_DIFFERENT = "failure.different"
    const val FAILURE_DIFFERENT_FRAME = "failure.different_frame"
    private const val NATIVE_CRASH = "NativeCrash"
    private const val SIGKILL = 9
}
