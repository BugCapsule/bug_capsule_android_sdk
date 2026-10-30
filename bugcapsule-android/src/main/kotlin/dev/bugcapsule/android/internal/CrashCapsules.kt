package dev.bugcapsule.android.internal

import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.CapsuleLimits
import dev.bugcapsule.android.core.CapsuleDependency
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.CapsuleValidator
import dev.bugcapsule.android.core.Checkpoint
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.DependencyCoverage
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.FailureRecord
import dev.bugcapsule.android.core.InteractionTypes
import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.PATCH_RUNTIME_DEPENDENCY
import java.io.File

/**
 * Crash capsules on the next launch (docs/crash-capsules.md §2): finds the
 * [CrashJournal] of a process that died, and builds a capsule from it.
 */
internal object CrashCapsules {
    const val DIR = "journal"
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** What a journal can hold before reading refuses it (bounds memory if the cap was larger then). */
    const val MAX_READ_BYTES = 64L * 1024 * 1024

    const val GAP_REASON = "the app died in native code or was killed; replay re-runs the steps and checks for the same crash"

    /**
     * The directory this process journals to. A journal left there by an
     * earlier process with the same pid (pids are reused after a reboot)
     * is moved aside so the next launch can still find it.
     */
    fun prepare(root: File, pid: Int): File {
        val dir = File(root, pid.toString())
        if (dir.exists() && !dir.renameTo(File(root, "$pid-${dir.lastModified()}"))) dir.deleteRecursively()
        return dir
    }

    /**
     * The newest journal of [pid] not older than [MAX_AGE_MS], or null.
     * When [pid] is this process's (reused), `journal/<pid>` is this
     * process's own live journal: only the moved-aside ones count.
     */
    fun find(root: File, pid: Int, nowMs: Long = System.currentTimeMillis(), myPid: Int = android.os.Process.myPid()): File? =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && ((it.name == pid.toString() && pid != myPid) || it.name.startsWith("$pid-")) }
            .filter { nowMs - newest(it) <= MAX_AGE_MS }
            .maxByOrNull { newest(it) }

    /**
     * Deletes every journal except those of live processes (this one, and
     * other processes of the app that are journaling now), and any older
     * than [MAX_AGE_MS]. Runs after the previous processes' crashes were
     * reported: a journal no fatal event claimed is of no use.
     */
    fun cleanup(root: File, isAlive: (Int) -> Boolean, nowMs: Long = System.currentTimeMillis()) {
        for (dir in root.listFiles() ?: return) {
            val pid = dir.name.toIntOrNull()
            val live = pid != null && isAlive(pid)
            if (!live || nowMs - newest(dir) > MAX_AGE_MS) dir.deleteRecursively()
        }
    }

    /** True when [pid] is a running process of this app (this process included). */
    fun isAlive(pid: Int, packageName: String): Boolean {
        if (pid == android.os.Process.myPid()) return true
        return try {
            val cmdline = File("/proc/$pid/cmdline")
            cmdline.exists() && cmdline.readText().substringBefore('\u0000').startsWith(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun newest(dir: File): Long {
        var t = dir.lastModified()
        dir.walkTopDown().maxDepth(2).forEach { if (it.lastModified() > t) t = it.lastModified() }
        return t
    }

    /**
     * A journal as read back: header, checkpoints by `afterSeq`, events by
     * `seq`, and the seq ranges the recorder dropped when it rewrote its buffer.
     */
    class Contents(
        val header: Map<String, Any?>,
        val checkpoints: List<Checkpoint>,
        val events: List<CapsuleEvent>,
        val dropped: List<LongRange> = emptyList(),
    ) {
        fun wasDropped(seq: Long): Boolean = dropped.any { seq in it }
    }

    /** Reads [dir]; null without a header or checkpoint, or when larger than [MAX_READ_BYTES]. Torn lines are skipped. */
    fun read(dir: File): Contents? {
        val files = dir.walkTopDown().maxDepth(2).filter { it.isFile }.toList()
        if (files.sumOf { it.length() } > MAX_READ_BYTES) return null
        val header = try {
            J.obj(Json.parse(File(dir, "header.json").readText()))
        } catch (_: Exception) {
            null
        } ?: return null
        val checkpoints = (File(dir, "checkpoints").listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            try {
                Checkpoint.fromJson(J.obj(Json.parse(f.readText()))!!)
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.afterSeq }
        if (checkpoints.isEmpty()) return null
        val events = ArrayList<CapsuleEvent>()
        val eventFiles = (File(dir, "events").listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray())
            .sortedBy { it.name.substringBefore('.').toLongOrNull() ?: Long.MAX_VALUE }
        for (f in eventFiles) {
            for (line in f.readText().split('\n')) {
                if (line.isBlank()) continue
                try {
                    events.add(CapsuleEvent.fromJson(J.obj(Json.parse(line))!!))
                } catch (_: Exception) {
                    // The process died mid-write.
                }
            }
        }
        val dropped = try {
            (J.list(Json.parse(File(dir, CrashJournal.DROPPED_FILE).readText())) ?: emptyList()).mapNotNull { r ->
                val pair = J.list(r) ?: return@mapNotNull null
                J.long(pair[0])!!..J.long(pair[1])!!
            }
        } catch (_: Exception) {
            emptyList()
        }
        return Contents(header, checkpoints, events.distinctBy { it.seq }.sortedBy { it.seq }, dropped)
    }

    /** The crash a capsule is built for. */
    class Crash(
        val capsuleId: String,
        /** `manifest.failure` with `t` still unset (0): [build] places it. */
        val failure: FailureRecord,
        /** `crash.mechanism` label: `signal`, `anr`, `oom`. */
        val mechanism: String,
        /** Epoch ms of the crash, when known. */
        val atMs: Long?,
        /** `manifest.monitoring`: `{eventId, release, environment}` of the fatal event. */
        val monitoring: Map<String, Any?>,
    )

    class Built(val capsuleId: String, val bytes: ByteArray, val json: Map<String, Any?>)

    /**
     * Builds the capsule (format v2): the launch checkpoint when every event
     * after it is in the journal, else the newest periodic checkpoint (also
     * when the first is over [maxCapsuleBytes]). Null when there is no
     * usable checkpoint, the capsule is still too big, or it is invalid.
     * [eligibility] adds the gaps of the window that do not depend on the
     * journal (integrations, dependencies, restorable state).
     */
    fun build(
        journal: Contents,
        crash: Crash,
        maxCapsuleBytes: Long,
        eligibility: (EligibilityBuilder, RecordedWindow, List<CapsuleDependency>) -> Unit,
        nowMs: Long = System.currentTimeMillis(),
    ): Built? {
        val complete = journal.checkpoints.firstOrNull { complete(journal, it) }
        val newestPeriodic = journal.checkpoints.lastOrNull { it.kind == CheckpointKind.PERIODIC }
        val first = complete ?: newestPeriodic ?: journal.checkpoints.last()
        val built = buildFrom(journal, first, crash, eligibility, nowMs)
        if (built != null && built.bytes.size <= maxCapsuleBytes) return built
        if (newestPeriodic != null && newestPeriodic !== first) {
            val smaller = buildFrom(journal, newestPeriodic, crash, eligibility, nowMs)
            if (smaller != null && smaller.bytes.size <= maxCapsuleBytes) return smaller
        }
        SdkLog.w("crash capsule ${crash.capsuleId} not built: over $maxCapsuleBytes bytes or invalid")
        return null
    }

    /** Every event after [checkpoint] is in the journal (none was evicted or lost; dropped by a rewrite is not lost). */
    private fun complete(journal: Contents, checkpoint: Checkpoint): Boolean {
        var expected = checkpoint.afterSeq + 1
        for (e in journal.events) {
            if (e.seq <= checkpoint.afterSeq) continue
            while (expected < e.seq && journal.wasDropped(expected)) expected++
            if (e.seq != expected) return false
            expected++
        }
        return true
    }

    private fun buildFrom(
        journal: Contents,
        checkpoint: Checkpoint,
        crash: Crash,
        addEligibility: (EligibilityBuilder, RecordedWindow, List<CapsuleDependency>) -> Unit,
        nowMs: Long,
    ): Built? {
        val h = journal.header
        val events = journal.events.filter { it.seq > checkpoint.afterSeq }
        val hasGap = !complete(journal, checkpoint)
        // Recorder time of the crash, from the wall clock of the newest checkpoint.
        val reference = journal.checkpoints.last()
        val lastT = events.lastOrNull()?.t ?: checkpoint.t
        val crashT = crash.atMs?.let { reference.t + (it - reference.wallClockMs) }?.coerceAtLeast(lastT) ?: lastT
        val anchor = J.long(h["anchorT"]) ?: crashT
        val session = J.obj(h["session"]) ?: return null
        val wallAtAnchor = J.long(session["wallClockAtAnchorMs"]) ?: (reference.wallClockMs + anchor - reference.t)

        val usedDeps = events.filter { InteractionTypes.isInteraction(it.type) }.mapNotNull { it.data["dep"] as? String }.toSet()
        val dependencies = (J.list(h["dependencies"]) ?: emptyList()).mapNotNull { d ->
            try {
                CapsuleDependency.fromJson(J.obj(d)!!)
            } catch (_: Exception) {
                null
            }
        }.filter {
            // A loaded patch is process state, not an interaction event. Recovery must
            // retain its mandatory runtime even when the process died before any call.
            it.id in usedDeps || it.coverage == DependencyCoverage.PARTIAL ||
                (it.required && it.kind == PATCH_RUNTIME_DEPENDENCY)
        }

        val eligibility = EligibilityBuilder()
        val source = J.obj(h["source"]) ?: emptyMap()
        if (J.str(source["gitSha"]) == null) {
            eligibility.missing("build.gitSha", "BUGCAPSULE_GIT_SHA was not set at build time")
        } else if (J.bool(source["dirty"]) == true) {
            eligibility.missing("build.gitSha", "built from a tree with uncommitted changes")
        }
        eligibility.missing("crash.native", GAP_REASON)
        if (hasGap) {
            eligibility.missing("events.window", "events between the checkpoint and the crash were evicted or lost; raise window or maxEvents")
        }
        val rewritten = journal.dropped.sumOf { r -> maxOf(0L, r.last - maxOf(r.first, checkpoint.afterSeq + 1) + 1) }
        if (rewritten > 0) {
            eligibility.missing("events.redaction", "$rewritten buffered events were dropped when redaction rules changed")
        }
        val window = RecordedWindow(checkpoint, events, 0, hasGap)
        try {
            addEligibility(eligibility, window, dependencies)
        } catch (e: Throwable) {
            eligibility.missing("integration.eligibility", "eligibility check failed: $e")
        }

        // The app's labels give up two slots for the crash labels, so the capsule stays within MAX_LABELS.
        val labels = LinkedHashMap<String, Any?>()
        (J.obj(h["labels"]) ?: emptyMap()).entries
            .filter { it.key != "source" && it.key != "crash.mechanism" }
            .take(CapsuleLimits.MAX_LABELS - 2)
            .forEach { labels[it.key] = it.value }
        labels["source"] = "crash_journal"
        labels["crash.mechanism"] = crash.mechanism
        val stats = linkedMapOf<String, Any?>(
            "eventCount" to events.size,
            "droppedEvents" to if (checkpoint.kind == CheckpointKind.LAUNCH) 0 else checkpoint.afterSeq,
            "checkpointKind" to checkpoint.kind.wire,
        )
        events.lastOrNull()?.let { last -> if (crash.atMs != null) stats["journalGapMs"] = (crashT - last.t).coerceAtLeast(0) }

        val manifest = linkedMapOf<String, Any?>(
            "capsuleId" to crash.capsuleId,
            "createdAt" to Iso8601.format(nowMs),
            "mode" to h["mode"],
            "app" to h["app"],
            "source" to source,
            "sdk" to h["sdk"],
            "stateSchemaVersion" to h["stateSchemaVersion"],
            "platform" to h["platform"],
            "session" to linkedMapOf(
                "id" to session["id"],
                "startedAt" to session["startedAt"],
                "wallClockAtAnchorMs" to wallAtAnchor,
                "seed" to session["seed"],
                "durationMs" to (crashT - anchor).coerceAtLeast(0),
            ),
            "failure" to crash.failure.copy(t = crashT - anchor).toJson(),
            "eligibility" to eligibility.build().toJson(),
            "integrations" to (J.list(h["integrations"]) ?: emptyList<Any?>()),
            "stats" to stats,
            "labels" to labels,
        )
        J.obj(h["redaction"])?.let { manifest["redaction"] = it }
        J.obj(h["runtime"])?.let { manifest["runtime"] = it }
        J.obj(h["capabilities"])?.let { manifest["capabilities"] = it }
        manifest["monitoring"] = crash.monitoring
        manifest["dependencies"] = dependencies.map { it.toJson() }
        J.obj(h["patch"])?.let { manifest["patch"] = it }

        val doc = linkedMapOf<String, Any?>(
            "format" to CapsuleCodec.FORMAT,
            "specVersion" to 2,
            "manifest" to manifest,
            "checkpoint" to checkpoint.shift(anchor).toJson(),
            "events" to events.map { it.shift(anchor).toJson() },
        )
        // Through JSON once: the validator sees exactly what is uploaded.
        val text = Json.stringify(doc)
        @Suppress("UNCHECKED_CAST")
        val parsed = Json.parse(text) as Map<String, Any?>
        val problem = CapsuleValidator.validate(parsed)
        if (problem != null) {
            SdkLog.w("crash capsule ${crash.capsuleId} failed validation: $problem")
            return null
        }
        return Built(crash.capsuleId, CapsuleCodec.gzip(text.toByteArray(Charsets.UTF_8)), parsed)
    }

    /**
     * `manifest.failure` of the fatal event [ev] (an event as
     * [dev.bugcapsule.android.monitoring.PreviousProcess.Crash.fill] fills
     * it): type and message of its exception, the crashed frames as the
     * stack, the signal, and the in-app frame closest to the crash.
     */
    fun failureOf(ev: Map<String, Any?>, mechanism: String): FailureRecord {
        val value = J.obj(J.list(J.obj(ev["exception"])?.get("values"))?.lastOrNull())
        val type = J.str(value?.get("type"))?.takeIf { it.isNotEmpty() } ?: when (mechanism) {
            "anr" -> "ApplicationNotResponding"
            "oom" -> "OutOfMemory"
            else -> "NativeCrash"
        }
        val data = J.obj(J.obj(value?.get("mechanism"))?.get("data"))
        val frames = (J.list(J.obj(value?.get("stacktrace"))?.get("frames"))
            ?: J.list(J.obj(J.list(ev["threads"])?.mapNotNull { J.obj(it) }?.firstOrNull { it["crashed"] == true }?.get("stacktrace"))?.get("frames"))
            ?: emptyList()).mapNotNull { J.obj(it) }
        val stack = frames.asReversed().take(64).mapIndexed { i, f ->
            "#$i ${CrashFrames.name(f) ?: J.str(f["instructionAddr"]) ?: "?"}" + (J.str(f["package"])?.let { " ($it)" } ?: "")
        }.joinToString("\n").ifEmpty { null }
        return FailureRecord.crash(
            type = type,
            message = J.str(value?.get("value")) ?: "",
            t = 0,
            mechanism = mechanism,
            signal = J.int(data?.get("signal")),
            inAppFrame = CrashFrames.inAppFrame(frames),
            stack = stack,
        )
    }
}

/** How crash capsules name frames (`failure.crash.inAppFrame`), the same at capture and replay. */
internal object CrashFrames {
    /** `Class.method` for JVM frames, the function for native ones. */
    fun name(frame: Map<String, Any?>): String? {
        val function = J.str(frame["function"])?.takeIf { it.isNotEmpty() } ?: return null
        val module = J.str(frame["module"])
        return if (module.isNullOrEmpty() || frame["native"] == true) function else "$module.$function"
    }

    /** The in-app frame closest to the crash; [frames] oldest first (monitoring-v1 order). */
    fun inAppFrame(frames: List<Map<String, Any?>>): String? =
        frames.lastOrNull { it["inApp"] == true && name(it) != null }?.let { name(it) }
}
