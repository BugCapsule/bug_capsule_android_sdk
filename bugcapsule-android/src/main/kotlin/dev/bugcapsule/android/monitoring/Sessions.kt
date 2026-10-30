package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.Ids as CoreIds
import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.PatchIdentity
import dev.bugcapsule.android.internal.Cancellable
import dev.bugcapsule.android.internal.Files
import java.io.File

/**
 * Release-health sessions (monitoring-v1 §7): one per foreground run, ended
 * [timeoutMs] after the app goes to the background. The running session is
 * persisted on every change, so a process that dies without a clean exit is
 * found on the next launch: `abnormal` when it was in the foreground,
 * `crashed` when a native crash record says so, `exited` when it was
 * already in the background. Thread-safe.
 */
internal class SessionTracker(
    private val file: File,
    private val installId: String,
    private val release: String,
    private val environment: String,
    private val timeoutMs: Long,
    private val nowMs: () -> Long,
    private val userId: () -> String?,
    /** Sends a `session` payload ([sync]: persist before returning, the process may be dying). */
    private val emit: (payload: Map<String, Any?>, sync: Boolean) -> Unit,
    private val schedule: (Long, Runnable) -> Cancellable,
    /** What this process runs; rollout health compares sessions by it. */
    private val patch: () -> PatchIdentity? = { null },
) {
    private class State(
        val sid: String,
        val did: String,
        val started: Long,
        var seq: Long,
        var status: String,
        var errors: Int,
        var timestamp: Long,
        var backgroundSince: Long?,
        /** The release / environment the session ran with: a later process may run a newer build. */
        val release: String,
        val environment: String,
        /** The patch this session ran, never the current one: it may since have been rolled back. */
        val patchBaseBuildId: String?,
        val patchSetDigest: String?,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "sid" to sid, "did" to did, "started" to started, "seq" to seq, "status" to status,
            "errors" to errors, "timestamp" to timestamp, "backgroundSince" to backgroundSince,
            "release" to release, "environment" to environment,
            "patchBaseBuildId" to patchBaseBuildId, "patchSetDigest" to patchSetDigest,
        )

        companion object {
            /** [release] / [environment]: for files written before they were persisted. */
            fun fromJson(j: Map<String, Any?>, release: String, environment: String) = State(
                sid = j["sid"] as String,
                did = j["did"] as String,
                started = J.long(j["started"])!!,
                seq = J.long(j["seq"]) ?: 0,
                status = J.str(j["status"]) ?: "ok",
                errors = J.int(j["errors"]) ?: 0,
                timestamp = J.long(j["timestamp"]) ?: J.long(j["started"])!!,
                backgroundSince = J.long(j["backgroundSince"]),
                release = J.str(j["release"]) ?: release,
                environment = J.str(j["environment"]) ?: environment,
                patchBaseBuildId = J.str(j["patchBaseBuildId"]),
                patchSetDigest = J.str(j["patchSetDigest"]),
            )
        }
    }

    private val lock = Any()
    private var current: State? = null
    private var endTimer: Cancellable? = null

    /** True while a session is running (tests). */
    val isActive: Boolean get() = synchronized(lock) { current != null }

    val currentSid: String? get() = synchronized(lock) { current?.sid }

    /**
     * Finalizes the session a previous process left behind, with the release
     * and environment it ran with. [crashedAtMs]: the previous process ended
     * with a native crash at that time.
     */
    fun recoverPrevious(crashedAtMs: Long?) {
        val previous = try {
            if (file.exists()) J.obj(Json.parse(file.readText()))?.let { State.fromJson(it, release, environment) } else null
        } catch (_: Exception) {
            null
        }
        Files.deleteQuietly(file)
        previous ?: return
        if (previous.status != "ok") return
        when {
            crashedAtMs != null -> {
                previous.status = "crashed"
                previous.errors++
                previous.timestamp = maxOf(previous.timestamp, crashedAtMs)
            }
            previous.backgroundSince != null -> {
                previous.status = "exited"
                previous.timestamp = previous.backgroundSince!!
            }
            else -> previous.status = "abnormal"
        }
        previous.seq++
        emit(payload(previous, init = false), false)
    }

    fun onForeground() = synchronized(lock) {
        endTimer?.cancel()
        endTimer = null
        val s = current
        val now = nowMs()
        if (s != null) {
            val since = s.backgroundSince
            if (since == null) return@synchronized
            if (now - since < timeoutMs) {
                s.backgroundSince = null
                persist(s)
                return@synchronized
            }
            endLocked(s, "exited", since)
        }
        startLocked(now)
    }

    fun onBackground() = synchronized(lock) {
        val s = current ?: return@synchronized
        if (s.backgroundSince != null) return@synchronized
        val now = nowMs()
        s.backgroundSince = now
        s.timestamp = now
        persist(s)
        endTimer?.cancel()
        endTimer = schedule(timeoutMs) {
            synchronized(lock) {
                val c = current
                if (c === s && c.backgroundSince != null) endLocked(c, "exited", c.backgroundSince!!)
            }
        }
    }

    /** Starts a new session (ending a running one); for apps that manage sessions themselves. */
    fun start() = synchronized(lock) {
        current?.let { endLocked(it, "exited", nowMs()) }
        startLocked(nowMs())
    }

    fun end() = synchronized(lock) {
        current?.let { endLocked(it, "exited", nowMs()) }
    }

    /**
     * Counts an error. Returns the session update to send with the event:
     * on the first error (the session becomes errored) and on a crash (final;
     * the session is over).
     */
    fun onError(fatal: Boolean): Map<String, Any?>? = synchronized(lock) {
        val s = current ?: return@synchronized null
        s.errors++
        s.timestamp = nowMs()
        s.seq++
        if (fatal) {
            s.status = "crashed"
            current = null
            endTimer?.cancel()
            Files.deleteQuietly(file)
            return@synchronized payload(s, init = false)
        }
        persist(s)
        if (s.errors == 1) payload(s, init = false) else null
    }

    private fun startLocked(now: Long) {
        val identity = patch()
        val s = State(
            CoreIds.uuidV4(), userId() ?: installId, now, 0, "ok", 0, now, null, release, environment,
            identity?.baseBuildId, identity?.patchSetDigest,
        )
        current = s
        persist(s)
        emit(payload(s, init = true), false)
    }

    private fun endLocked(s: State, status: String, at: Long) {
        s.status = status
        s.timestamp = at
        s.seq++
        current = null
        endTimer?.cancel()
        endTimer = null
        Files.deleteQuietly(file)
        emit(payload(s, init = false), false)
    }

    private fun persist(s: State) {
        try {
            Files.writeAtomically(file, Json.stringify(s.toJson()).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    private fun payload(s: State, init: Boolean): Map<String, Any?> = linkedMapOf<String, Any?>(
        "sid" to s.sid,
        "did" to s.did,
        "seq" to s.seq,
        "init" to init,
        "started" to Iso8601.format(s.started),
        "timestamp" to Iso8601.format(s.timestamp),
        "status" to s.status,
        "errors" to s.errors,
        "duration" to (s.timestamp - s.started).coerceAtLeast(0) / 1000.0,
        "release" to s.release,
        "environment" to s.environment,
        "runtime" to "android",
    ).also { p ->
        s.patchBaseBuildId?.let { p["patchBaseBuildId"] = it }
        s.patchSetDigest?.let { p["patchSetDigest"] = it }
    }
}
