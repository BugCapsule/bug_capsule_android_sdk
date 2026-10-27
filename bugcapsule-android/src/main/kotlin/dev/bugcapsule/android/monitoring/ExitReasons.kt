package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Files
import java.io.File
import java.io.InputStream

/**
 * Why a previous process of this app ended, as Android recorded it
 * (`ApplicationExitInfo`, API 30+). [openTrace]: the ANR thread dump
 * (API 30+) or the native crash tombstone in protobuf form (API 31+).
 */
internal class ExitRecord(
    val pid: Int,
    val reason: Int,
    val timestampMs: Long,
    val importance: Int,
    val description: String?,
    val processName: String?,
    /** `ApplicationExitInfo.getStatus()`: the signal number of a signaled process. */
    val status: Int = 0,
    private val trace: () -> InputStream?,
) {
    fun openTrace(): InputStream? = try {
        trace()
    } catch (_: Throwable) {
        null
    }

    val inForeground: Boolean get() = importance <= IMPORTANCE_FOREGROUND

    companion object {
        // ApplicationExitInfo.REASON_* and RunningAppProcessInfo.IMPORTANCE_FOREGROUND.
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_SIGNALED = 2
        const val IMPORTANCE_FOREGROUND = 100
    }
}

/** The system's recent exit records of this app, newest first (empty before API 30). */
internal fun interface ExitInfoSource {
    fun exits(): List<ExitRecord>
}

/**
 * Picks the exit records not handled yet. The newest handled timestamp is
 * kept in [stateFile]; on the very first launch with this feature nothing
 * old is reported, only remembered. Only the app's main process
 * ([processName]) counts.
 */
internal class ExitReasons(
    private val stateFile: File,
    private val processName: String,
    private val source: ExitInfoSource,
) {
    fun takeNew(): List<ExitRecord> {
        val all = try {
            source.exits()
        } catch (_: Throwable) {
            emptyList()
        }.filter { it.processName == null || it.processName == processName }
        val seen = readSeen()
        if (seen == null) {
            // First launch with this feature: older exits are history, later ones (from 0 on a fresh install) count.
            write(all.maxOfOrNull { it.timestampMs } ?: 0L)
            return emptyList()
        }
        return all.filter { it.timestampMs > seen }.sortedBy { it.timestampMs }
    }

    /**
     * Exits up to [timestampMs] are handled: call once their events are
     * queued, so a process that dies before that reports them next time.
     */
    fun markHandled(timestampMs: Long) {
        val seen = readSeen() ?: 0L
        if (timestampMs > seen) write(timestampMs)
    }

    private fun readSeen(): Long? = try {
        if (stateFile.exists()) J.long(J.obj(Json.parse(stateFile.readText()))?.get("lastTimestamp")) else null
    } catch (_: Exception) {
        null
    }

    private fun write(timestampMs: Long) {
        try {
            Files.writeAtomically(stateFile, Json.stringify(linkedMapOf("lastTimestamp" to timestampMs)).toByteArray())
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Exit records that become events (a JVM crash was already reported by the uncaught handler). */
        fun reportable(e: ExitRecord): Boolean = when (e.reason) {
            ExitRecord.REASON_CRASH_NATIVE, ExitRecord.REASON_ANR -> true
            ExitRecord.REASON_LOW_MEMORY -> e.inForeground
            else -> false
        }

        /** An ANR the system killed the app for: the main thread blocked, every thread's stack. */
        fun fillAnr(ev: MutableMap<String, Any?>, exit: ExitRecord, inApp: InAppRules) {
            val threads = exit.openTrace()?.use { AnrTrace.parse(it.readBytes().toString(Charsets.UTF_8), inApp) } ?: emptyList()
            val main = threads.firstOrNull { it["name"] == "main" } ?: threads.firstOrNull()
            val marked = threads.map { t -> if (t === main) LinkedHashMap(t).also { it["crashed"] = true } else t }
            val value = linkedMapOf<String, Any?>(
                "type" to "ApplicationNotResponding",
                "value" to (exit.description?.takeIf { it.isNotBlank() } ?: if (exit.inForeground) "ANR" else "Background ANR"),
                "module" to "dev.bugcapsule.android",
                "mechanism" to ExceptionMapper.Mechanism(
                    "anr", false, linkedMapOf("source" to "exit_info", "foreground" to exit.inForeground),
                ).toJson(),
            )
            main?.get("stacktrace")?.let { value["stacktrace"] = it }
            ev["exception"] = linkedMapOf("values" to listOf(value))
            if (marked.isNotEmpty()) ev["threads"] = marked
        }

        /** The system killed the app while visible to free memory. */
        fun fillLowMemory(ev: MutableMap<String, Any?>, exit: ExitRecord) {
            ev["exception"] = linkedMapOf(
                "values" to listOf(
                    linkedMapOf(
                        "type" to "OutOfMemory",
                        "value" to "The system killed the app in the foreground to free memory" +
                            (exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                        "module" to "dev.bugcapsule.android",
                        "mechanism" to ExceptionMapper.Mechanism("oom", false, linkedMapOf("source" to "exit_info")).toJson(),
                    ),
                ),
            )
        }

        /**
         * A native crash without our own record (handler not installed, or it
         * could not write): signal, crashing thread and every other thread
         * from the tombstone; just the system's description before API 31.
         */
        fun fillNativeFromTombstone(ev: MutableMap<String, Any?>, exit: ExitRecord, tombstone: Tombstone?, appId: String) {
            if (tombstone == null) {
                ev["exception"] = linkedMapOf(
                    "values" to listOf(
                        linkedMapOf(
                            "type" to "NativeCrash",
                            "value" to (exit.description?.takeIf { it.isNotBlank() } ?: "The app crashed in native code"),
                            "mechanism" to ExceptionMapper.Mechanism("signal", false, linkedMapOf("source" to "exit_info")).toJson(),
                        ),
                    ),
                )
                return
            }
            val crashing = tombstone.threads.firstOrNull { it.id == tombstone.tid } ?: tombstone.threads.firstOrNull()
            val name = tombstone.signalName ?: NativeCrashRecord.signalName(tombstone.signal)
            val address = "0x" + java.lang.Long.toHexString(tombstone.faultAddress)
            ev["exception"] = linkedMapOf(
                "values" to listOf(
                    linkedMapOf<String, Any?>(
                        "type" to name,
                        "value" to (tombstone.abortMessage?.takeIf { it.isNotBlank() }
                            ?: "${NativeCrashRecord.signalDescription(tombstone.signal)} (code ${tombstone.code}) at $address"),
                        "mechanism" to ExceptionMapper.Mechanism(
                            "signal", false,
                            linkedMapOf("signal" to tombstone.signal, "code" to tombstone.code, "address" to address, "name" to name, "source" to "tombstone"),
                        ).toJson(),
                    ).also { v -> crashing?.let { v["stacktrace"] = linkedMapOf("frames" to it.frames(appId)) } },
                ),
            )
            ev["threads"] = tombstone.threadsJson(appId, crashedTid = crashing?.id)
            ev["debugImages"] = tombstone.debugImages(appId)
        }

        /**
         * Adds the tombstone's other threads (and their images) to an event
         * built from our own record: the record has the crashing thread with
         * the scope, the tombstone has everything else.
         */
        fun addTombstoneThreads(ev: MutableMap<String, Any?>, tombstone: Tombstone, appId: String) {
            val own = J.list(ev["threads"])?.mapNotNull { J.obj(it) } ?: emptyList()
            val crashedTid = (own.firstOrNull()?.get("id") as? Number)?.toInt()
            val others = tombstone.threadsJson(appId, crashedTid = null).filter { (it["id"] as? Number)?.toInt() != crashedTid }
            ev["threads"] = (own + others).take(ThreadDump.MAX_THREADS)
            val images = LinkedHashMap<String, Map<String, Any?>>()
            for (i in (J.list(ev["debugImages"]) ?: emptyList()).mapNotNull { J.obj(it) } + tombstone.debugImages(appId)) {
                // One image per library; ours first (its address is where this process loaded it).
                images.getOrPut((i["codeFile"] ?: i["debugId"]).toString()) { i }
            }
            ev["debugImages"] = images.values.toList()
        }
    }
}

/**
 * The text thread dump Android writes for an ANR (what
 * `ApplicationExitInfo.getTraceInputStream` returns for `REASON_ANR`).
 */
internal object AnrTrace {
    private val header = Regex("^\"(.*)\"(?: daemon)? prio=\\d+ tid=(\\d+) (\\S+).*")
    /** A native thread the runtime does not manage. */
    private val detachedHeader = Regex("^\"(.*)\"(?: daemon)? prio=\\d+ \\(not attached\\).*")
    private val sysTid = Regex("sysTid=(\\d+)")
    private val javaFrame = Regex("^\\s+at (.+)\\.([^.(]+)\\((.*)\\)\\s*$")
    private val nativeFrame = Regex("^\\s+native: #\\d+ pc ([0-9a-fA-F]+)\\s+(\\S+)(?: \\(offset 0x[0-9a-fA-F]+\\))?(?: \\((.+?)\\))?(?: \\(BuildId: [0-9a-fA-F]+\\))?\\s*$")

    fun parse(text: String, inApp: InAppRules): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var name: String? = null
        var id: Long? = null
        var state: String? = null
        var frames = ArrayList<Map<String, Any?>>()
        fun flush() {
            val n = name ?: return
            if (out.size < ThreadDump.MAX_THREADS) {
                val kept = frames.take(ThreadDump.MAX_FRAMES_PER_THREAD).reversed()
                out.add(
                    linkedMapOf<String, Any?>("id" to id, "name" to n, "crashed" to false, "current" to false, "state" to state).also {
                        if (kept.isNotEmpty()) it["stacktrace"] = linkedMapOf("frames" to kept)
                    },
                )
            }
            name = null
            frames = ArrayList()
        }
        for (line in text.lineSequence()) {
            val h = header.matchEntire(line)
            if (h != null) {
                flush()
                name = h.groupValues[1]
                id = h.groupValues[2].toLongOrNull()
                state = h.groupValues[3]
                continue
            }
            val d = detachedHeader.matchEntire(line)
            if (d != null) {
                flush()
                name = d.groupValues[1]
                id = null
                state = "Native"
                continue
            }
            if (name == null) continue
            if (line.isBlank()) {
                flush()
                continue
            }
            sysTid.find(line)?.let { m -> if (line.trimStart().startsWith("|")) id = m.groupValues[1].toLongOrNull() ?: id }
            javaFrame.matchEntire(line)?.let { m ->
                val (cls, method, location) = m.destructured
                val file = location.substringBefore(':').takeIf { it.isNotEmpty() && it != "Native method" && it != "Unknown Source" }
                val lineNo = location.substringAfter(':', "").toIntOrNull() ?: -1
                val native = location == "Native method"
                frames.add(ExceptionMapper.frame(StackTraceElement(cls, method, file, if (native) -2 else lineNo), inApp))
            }
            nativeFrame.matchEntire(line)?.let { m ->
                val (_, image, symbol) = m.destructured
                frames.add(
                    linkedMapOf<String, Any?>("package" to image, "native" to true, "inApp" to false).also {
                        if (symbol.isNotEmpty()) it["function"] = symbol.substringBefore('+')
                    },
                )
            }
        }
        flush()
        return out
    }
}

/** The parts of a native crash tombstone (Android 12+ `tombstone.proto`) that events use. */
internal class Tombstone(
    val pid: Int,
    val tid: Int,
    val signal: Int,
    val signalName: String?,
    val code: Int,
    val faultAddress: Long,
    val abortMessage: String?,
    val threads: List<TombstoneThread>,
) {
    class Frame(val relPc: Long, val pc: Long, val functionName: String?, val fileName: String?, val buildId: String?)

    class TombstoneThread(val id: Int, val name: String?, val backtrace: List<Frame>) {
        /** Oldest call first. Tombstone pcs are already adjusted into the call instruction: sent as is. */
        fun frames(appId: String): List<Map<String, Any?>> = backtrace.take(ExceptionMapper.MAX_FRAMES).reversed().map { f ->
            linkedMapOf<String, Any?>("instructionAddr" to hex(f.pc)).also {
                if (!f.functionName.isNullOrEmpty()) it["function"] = f.functionName
                if (!f.fileName.isNullOrEmpty()) {
                    it["package"] = f.fileName
                    it["inApp"] = NativeCrashes.isAppLibrary(f.fileName, appId)
                    // Exact image of the frame (the server matches it to the debug image by this address).
                    it["imageAddr"] = hex(f.pc - f.relPc)
                } else {
                    it["inApp"] = false
                }
            }
        }
    }

    fun threadsJson(appId: String, crashedTid: Int?): List<Map<String, Any?>> {
        val ordered = threads.sortedWith(compareBy<TombstoneThread>({ it.id != crashedTid }, { it.id != pid }, { it.id }))
        return ordered.take(ThreadDump.MAX_THREADS).map { t ->
            linkedMapOf(
                "id" to t.id,
                "name" to t.name,
                "crashed" to (t.id == crashedTid),
                "current" to (t.id == crashedTid),
                "stacktrace" to linkedMapOf("frames" to t.frames(appId)),
            )
        }
    }

    /**
     * One `elf` image per app library with a build id; its address is where
     * the frames say it was loaded. System libraries are left out: nobody
     * uploads their symbols, and their frames already have function names.
     */
    fun debugImages(appId: String): List<Map<String, Any?>> {
        val images = LinkedHashMap<String, Map<String, Any?>>()
        for (t in threads) for (f in t.backtrace) {
            val file = f.fileName ?: continue
            if (!NativeCrashes.isAppLibrary(file, appId)) continue
            val buildId = f.buildId?.takeIf { it.length >= 2 } ?: continue
            if (file in images) continue
            val bytes = try {
                ByteArray(buildId.length / 2) { buildId.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                continue
            }
            images[file] = linkedMapOf(
                "type" to "elf",
                "codeFile" to file,
                "debugId" to ElfBuildId.format(bytes),
                "imageAddr" to hex(f.pc - f.relPc),
            )
        }
        return images.values.toList()
    }

    companion object {
        private fun hex(v: Long) = "0x" + java.lang.Long.toHexString(v)

        /** Null when [bytes] is not a tombstone the reader understands. */
        fun parse(bytes: ByteArray): Tombstone? = try {
            val r = Proto(bytes, 0, bytes.size)
            var pid = 0
            var tid = 0
            var signal: Proto? = null
            var abort: String? = null
            val threads = ArrayList<TombstoneThread>()
            while (r.hasMore()) {
                val (field, type) = r.tag()
                when {
                    field == 5 && type == 0 -> pid = r.varint().toInt()
                    field == 6 && type == 0 -> tid = r.varint().toInt()
                    field == 10 && type == 2 -> signal = r.message()
                    field == 14 && type == 2 -> abort = r.string()
                    field == 16 && type == 2 -> mapEntryThread(r.message())?.let { threads.add(it) }
                    else -> r.skip(type)
                }
            }
            var number = 0
            var name: String? = null
            var code = 0
            var fault = 0L
            signal?.let { s ->
                while (s.hasMore()) {
                    val (field, type) = s.tag()
                    when {
                        field == 1 && type == 0 -> number = s.varint().toInt()
                        field == 2 && type == 2 -> name = s.string()
                        field == 3 && type == 0 -> code = s.varint().toInt()
                        field == 9 && type == 0 -> fault = s.varint()
                        else -> s.skip(type)
                    }
                }
            }
            if (number == 0 && threads.isEmpty()) null else Tombstone(pid, tid, number, name, code, fault, abort, threads)
        } catch (_: Exception) {
            null
        }

        /** `map<uint32, Thread>` entry: key = 1, value = 2. */
        private fun mapEntryThread(entry: Proto): TombstoneThread? {
            var thread: TombstoneThread? = null
            while (entry.hasMore()) {
                val (field, type) = entry.tag()
                if (field == 2 && type == 2) thread = thread(entry.message()) else entry.skip(type)
            }
            return thread
        }

        private fun thread(m: Proto): TombstoneThread {
            var id = 0
            var name: String? = null
            val frames = ArrayList<Frame>()
            while (m.hasMore()) {
                val (field, type) = m.tag()
                when {
                    field == 1 && type == 0 -> id = m.varint().toInt()
                    field == 2 && type == 2 -> name = m.string()
                    field == 4 && type == 2 -> frames.add(frame(m.message()))
                    else -> m.skip(type)
                }
            }
            return TombstoneThread(id, name, frames)
        }

        private fun frame(m: Proto): Frame {
            var relPc = 0L
            var pc = 0L
            var function: String? = null
            var file: String? = null
            var buildId: String? = null
            while (m.hasMore()) {
                val (field, type) = m.tag()
                when {
                    field == 1 && type == 0 -> relPc = m.varint()
                    field == 2 && type == 0 -> pc = m.varint()
                    field == 4 && type == 2 -> function = m.string()
                    field == 6 && type == 2 -> file = m.string()
                    field == 8 && type == 2 -> buildId = m.string()
                    else -> m.skip(type)
                }
            }
            return Frame(relPc, pc, function, file, buildId)
        }
    }

    /** A minimal protobuf wire-format reader over [bytes] from [pos] to [end]. */
    private class Proto(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun varint(): Long {
            var shift = 0
            var out = 0L
            while (shift < 64) {
                require(pos < end) { "truncated varint" }
                val b = bytes[pos++].toInt()
                out = out or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return out
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }

        private fun length(): Int {
            val n = varint()
            require(n >= 0 && n <= end - pos) { "bad length" }
            return n.toInt()
        }

        fun message(): Proto {
            val n = length()
            return Proto(bytes, pos, pos + n).also { pos += n }
        }

        fun string(): String {
            val n = length()
            return String(bytes, pos, n, Charsets.UTF_8).also { pos += n }
        }

        fun skip(type: Int) {
            when (type) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    val n = length() // read before `pos` is: the length's own bytes move it
                    pos += n
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("wire type $type")
            }
            require(pos <= end) { "truncated" }
        }
    }
}
