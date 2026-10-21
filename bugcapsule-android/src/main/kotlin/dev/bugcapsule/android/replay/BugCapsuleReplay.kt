package dev.bugcapsule.android.replay

import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleFormatException
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayProtocol
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplaySettings
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.internal.SdkLog
import dev.bugcapsule.android.storage.EarlyRestore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Emits one fallback line (`BUGCAPSULE_RESULT::{json}`) as instrumentation status. */
fun interface StatusSink {
    fun emit(line: String)
}

/**
 * Replay arming, used by `BugCapsuleReplayRunner` (artifact
 * `bugcapsule-android-replay`, an androidTest dependency). Not an app API.
 *
 * **Production protection** (runtimes-v1 §10): replay is armed only from an
 * [Instrumentation] (i.e. `am instrument` with the runner class, which ships
 * only in the androidTest APK) and only when the target app is debuggable.
 * There is no intent, deep link, URL or push path into replay.
 */
object BugCapsuleReplay {
    /** A replay armed before `Application.onCreate`: capsule fetched, storage restored. */
    class Armed internal constructor(
        val channel: ReplayChannel,
        val capsule: Capsule,
        val settings: ReplaySettings,
        /** Divergences found while restoring storage (reported by the engine). */
        val earlyDivergences: List<Divergence>,
        internal val sink: StatusSink?,
    ) {
        /** Reporter used by the engine: HTTP POST first, then the status fallback line. */
        val reporter: ReplayReporter = ReplayReporter { report -> deliver(channel, sink, report) }
    }

    @Volatile
    private var armed: Armed? = null

    private val engineRef = AtomicReference<ReplayEngine?>()
    @Volatile
    private var engineLatch = CountDownLatch(1)

    @Volatile
    private var anchorLatch = CountDownLatch(1)

    @JvmStatic
    fun armed(): Armed? = armed

    @JvmStatic
    fun isArmed(): Boolean = armed != null

    /**
     * Fetches the capsule and settings from [url] and restores checkpointed
     * storage (SharedPreferences, Preferences DataStore) into the target app.
     * Must run before `Application.onCreate` (the runner's `onCreate`).
     * Returns false when replay was refused or could not start (a report was sent).
     */
    @JvmStatic
    fun arm(instrumentation: Instrumentation, url: String, sink: StatusSink?): Boolean {
        val context = instrumentation.targetContext
        val channel = try {
            ReplayChannel(url)
        } catch (e: IllegalArgumentException) {
            SdkLog.w("invalid replay URL: ${e.message}")
            return false
        }
        if (!isDebuggable(context)) {
            SdkLog.w("replay refused: ${context.packageName} is not debuggable")
            deliverOffMain(channel, sink, errorReport(null, "replay refused: the app under test is not debuggable"))
            return false
        }
        val fetched = AtomicReference<Any?>()
        val worker = Thread({
            fetched.set(
                try {
                    channel.fetch()
                } catch (e: Throwable) {
                    e
                },
            )
        }, "BugCapsule-arm")
        worker.start()
        worker.join(60_000)
        val result = fetched.get()
        if (result !is Pair<*, *>) {
            val e = result as? Throwable
            val outcome = if (e is CapsuleFormatException) ReplayOutcome.INCOMPATIBLE else ReplayOutcome.ERROR
            deliverOffMain(channel, sink, errorReport(null, "could not load capsule: ${e ?: "timeout"}", outcome))
            return false
        }
        val capsule = result.first as Capsule
        val settings = result.second as ReplaySettings
        val divergences = ArrayList<Divergence>()
        try {
            EarlyRestore.restore(context, capsule.checkpoint.data) { type, detail -> divergences.add(Divergence(type, detail)) }
        } catch (e: Throwable) {
            divergences.add(Divergence("restore.storage", e.toString()))
        }
        armed = Armed(channel, capsule, settings, divergences, sink)
        SdkLog.i("replay armed: capsule ${capsule.manifest.capsuleId}, attempt ${settings.attempt}")
        return true
    }

    @JvmStatic
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    internal fun publishEngine(engine: ReplayEngine?) {
        engineRef.set(engine)
        engineLatch.countDown()
    }

    internal fun anchorReached() = anchorLatch.countDown()

    /** The engine created by `BugCapsule.init` in replay mode; null after [timeoutMs] or when init reported already. */
    @JvmStatic
    fun awaitEngine(timeoutMs: Long): ReplayEngine? {
        engineLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engineRef.get()
    }

    /** True once `BugCapsule.init` ran in replay mode (with or without an engine). */
    @JvmStatic
    fun initialized(): Boolean = engineLatch.count == 0L

    /** Waits for the first drawn frame of the replayed app (the session anchor). */
    @JvmStatic
    fun awaitAnchor(timeoutMs: Long): Boolean = anchorLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Reports an attempt that could not run (e.g. the app never called `BugCapsule.init`). */
    @JvmStatic
    fun reportError(message: String) {
        val a = armed ?: return
        val engine = engineRef.get()
        if (engine != null) {
            engine.message = message
            engine.finishNow(message)
            return
        }
        deliverOffMain(a.channel, a.sink, errorReport(a.settings, message))
    }

    internal fun errorReport(settings: ReplaySettings?, message: String, outcome: ReplayOutcome = ReplayOutcome.ERROR) =
        ReplayReport(
            outcome = outcome,
            attempt = settings?.attempt ?: 1,
            durationMs = 0,
            strategy = settings?.strategy ?: ReplayStrategy.AUTO,
            message = message,
            attemptId = settings?.attemptId,
            runtime = "android",
        )

    /** Delivers [report] from any thread, including the main thread in a crash handler. */
    internal fun deliverOffMain(channel: ReplayChannel, sink: StatusSink?, report: ReplayReport) = deliver(channel, sink, report)

    private fun deliver(channel: ReplayChannel, sink: StatusSink?, report: ReplayReport) {
        val json = Json.stringify(report.toJson())
        val status = AtomicReference<Int?>()
        // Never network on the calling thread: it may be the main thread.
        val poster = Thread({ status.set(channel.postResult(report)) }, "BugCapsule-report")
        poster.start()
        poster.join(10_000)
        when (val s = status.get()) {
            204, 200 -> SdkLog.i("replay result posted (${report.outcome.wire})")
            409, 410 -> SdkLog.w("replay result rejected (HTTP $s): the attempt is stale")
            else -> SdkLog.w("replay result not posted (HTTP $s); using the status fallback")
        }
        val line = ReplayProtocol.STDOUT_PREFIX + json
        try {
            sink?.emit(line)
        } catch (e: Throwable) {
            SdkLog.w("status fallback failed: $e")
        }
        println(line)
    }

    /** Test hook: forget armed state. */
    @androidx.annotation.VisibleForTesting
    @JvmStatic
    fun resetForTest() {
        armed = null
        engineRef.set(null)
        engineLatch = CountDownLatch(1)
        anchorLatch = CountDownLatch(1)
    }
}
