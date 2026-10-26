package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Json
import dev.bugcapsule.android.internal.Files
import dev.bugcapsule.android.internal.SdkLog
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * A crash record written by the `bugcapsule-ndk` signal handler:
 *
 * ```
 * bugcapsule-native-crash 1
 * signal 11
 * code 1
 * address 0x0
 * tid 1234
 * thread RenderThread
 * timestamp 1761000000000
 * abi arm64-v8a
 * scope {"tags":{…},"user":{…},"breadcrumbs":[…],…}
 * frame 0x7a1b2c3d4e 0x7a1b200000 0x7a1b2c3d00 /data/app/…/lib/arm64/libshop.so crash_here
 * end
 * ```
 *
 * Frames are crashing frame first: its exact PC, then the callers' return
 * addresses as unwound (made `- 1` when sent, see [NativeCrashes.fill]);
 * `-` means unknown. `scope` is the crashed process's scope as mirrored by
 * [Monitoring.mirrorScopeTo] (absent before the first mirror). A record
 * without `end` (the handler died while unwinding) is still used.
 */
internal class NativeCrashRecord(
    val signal: Int,
    val code: Int,
    val address: String,
    val tid: Long?,
    val thread: String?,
    val timestampMs: Long?,
    val abi: String?,
    val frames: List<Frame>,
    val scope: Map<String, Any?>? = null,
) {
    class Frame(val pc: Long, val imageBase: Long, val symbolAddress: Long, val image: String?, val symbol: String?)

    companion object {
        private const val HEADER = "bugcapsule-native-crash 1"

        fun parse(text: String): NativeCrashRecord? {
            val lines = text.split('\n')
            if (lines.firstOrNull()?.trim() != HEADER) return null
            var signal: Int? = null
            var code = 0
            var address = "0x0"
            var tid: Long? = null
            var thread: String? = null
            var timestamp: Long? = null
            var abi: String? = null
            var scope: Map<String, Any?>? = null
            val frames = ArrayList<Frame>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore(' ')
                val rest = line.substringAfter(' ', "")
                when (key) {
                    "signal" -> signal = rest.trim().toIntOrNull()
                    "code" -> code = rest.trim().toIntOrNull() ?: 0
                    "address" -> address = rest.trim()
                    "tid" -> tid = rest.trim().toLongOrNull()
                    "thread" -> thread = rest.takeIf { it.isNotBlank() }
                    "timestamp" -> timestamp = rest.trim().toLongOrNull()
                    "abi" -> abi = rest.trim().takeIf { it.isNotEmpty() }
                    "scope" -> scope = try {
                        J.obj(Json.parse(rest))
                    } catch (_: Exception) {
                        null
                    }
                    "frame" -> parseFrame(rest)?.let { frames.add(it) }
                    "end" -> break
                }
            }
            return NativeCrashRecord(signal ?: return null, code, address, tid, thread, timestamp, abi, frames, scope)
        }

        private fun parseFrame(rest: String): Frame? {
            val parts = rest.trim().split(' ')
            if (parts.size < 3) return null
            fun hex(s: String) = java.lang.Long.parseUnsignedLong(s.removePrefix("0x"), 16)
            return try {
                Frame(
                    pc = hex(parts[0]),
                    imageBase = hex(parts[1]),
                    symbolAddress = hex(parts[2]),
                    image = parts.getOrNull(3)?.takeIf { it != "-" },
                    symbol = parts.getOrNull(4)?.takeIf { it != "-" },
                )
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun signalName(signal: Int): String = when (signal) {
            4 -> "SIGILL"
            5 -> "SIGTRAP"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            11 -> "SIGSEGV"
            else -> "SIG$signal"
        }

        fun signalDescription(signal: Int): String = when (signal) {
            4 -> "Illegal instruction"
            5 -> "Trace/breakpoint trap"
            6 -> "Abort"
            7 -> "Bus error"
            8 -> "Floating-point exception"
            11 -> "Segmentation violation (invalid memory reference)"
            else -> "Fatal signal $signal"
        }
    }
}

/** GNU build ids of ELF files, formatted as debug ids like Sentry (first 16 bytes, little-endian UUID fields). */
internal object ElfBuildId {
    private const val PT_NOTE = 4
    private const val NT_GNU_BUILD_ID = 3

    /** Reads [path]; `…/base.apk!/lib/arm64-v8a/libx.so` is read from inside the APK. */
    fun forPath(path: String): String? = try {
        val bang = path.indexOf("!/")
        if (bang > 0) {
            ZipFile(path.substring(0, bang)).use { zip ->
                val entry = zip.getEntry(path.substring(bang + 2)) ?: return null
                zip.getInputStream(entry).use { debugId(readPrefix(it, 256 * 1024)) }
            }
        } else {
            RandomAccessFile(path, "r").use { f ->
                val n = minOf(f.length(), 256L * 1024).toInt()
                val buf = ByteArray(n)
                f.readFully(buf)
                debugId(buf)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readPrefix(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The debug id from the start of an ELF image (program headers and notes must lie in [bytes]). */
    fun debugId(bytes: ByteArray): String? = buildId(bytes)?.let { format(it) }

    fun buildId(bytes: ByteArray): ByteArray? {
        if (bytes.size < 52 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val is64 = bytes[4].toInt() == 2
        val order = if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val b = ByteBuffer.wrap(bytes).order(order)
        val phoff = if (is64) b.getLong(32) else (b.getInt(28).toLong() and 0xffffffffL)
        val phentsize = b.getShort(if (is64) 54 else 42).toInt() and 0xffff
        val phnum = b.getShort(if (is64) 56 else 44).toInt() and 0xffff
        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            if (ph < 0 || ph + phentsize > bytes.size) return null
            if (b.getInt(ph) != PT_NOTE) continue
            val offset = if (is64) b.getLong(ph + 8) else (b.getInt(ph + 4).toLong() and 0xffffffffL)
            val size = if (is64) b.getLong(ph + 32) else (b.getInt(ph + 16).toLong() and 0xffffffffL)
            findNote(b, offset.toInt(), (offset + size).toInt().coerceAtMost(bytes.size))?.let { return it }
        }
        return null
    }

    private fun findNote(b: ByteBuffer, start: Int, end: Int): ByteArray? {
        var p = start
        while (p + 12 <= end) {
            val namesz = b.getInt(p)
            val descsz = b.getInt(p + 4)
            val type = b.getInt(p + 8)
            if (namesz < 0 || descsz < 0) return null
            val nameStart = p + 12
            val descStart = nameStart + align4(namesz)
            if (descStart + descsz > end) return null
            if (type == NT_GNU_BUILD_ID && namesz == 4 && b.get(nameStart) == 'G'.code.toByte() &&
                b.get(nameStart + 1) == 'N'.code.toByte() && b.get(nameStart + 2) == 'U'.code.toByte()
            ) {
                return ByteArray(descsz) { b.get(descStart + it) }
            }
            p = descStart + align4(descsz)
        }
        return null
    }

    private fun align4(n: Int) = (n + 3) and 3.inv()

    /** First 16 bytes (zero-padded), with the first three UUID fields byte-swapped. */
    fun format(id: ByteArray): String {
        val d = ByteArray(16) { if (it < id.size) id[it] else 0 }
        fun swap(i: Int, j: Int) {
            val t = d[i]; d[i] = d[j]; d[j] = t
        }
        swap(0, 3); swap(1, 2); swap(4, 5); swap(6, 7)
        val hex = d.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/**
 * Glue between the `bugcapsule-ndk` artifact (found by reflection) and
 * events: installs the handler, and on the next launch turns records into
 * `fatal` events with mechanism `signal`, `elf` debug images and the
 * release / environment the crashed process ran with.
 */
internal class NativeCrashes(private val dir: File) {
    private val contextFile = File(dir, "context.json")

    /** Records left by previous processes, oldest first. */
    fun pending(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".crash") } ?: emptyArray()).sortedBy { it.lastModified() }

    /** Release / environment / session id of the process that wrote the records (written by [install]). */
    fun previousContext(): Map<String, Any?> = try {
        J.obj(Json.parse(contextFile.readText())) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** Installs the signal handler when `bugcapsule-ndk` is on the classpath; false otherwise. */
    fun install(release: String, environment: String): Boolean {
        try {
            dir.mkdirs()
            Files.writeAtomically(
                contextFile,
                Json.stringify(linkedMapOf("release" to release, "environment" to environment)).toByteArray(),
            )
        } catch (_: Exception) {
        }
        return try {
            val cls = Class.forName("dev.bugcapsule.ndk.BugCapsuleNdk")
            cls.getMethod("install", String::class.java).invoke(null, dir.absolutePath) as Boolean
        } catch (_: ClassNotFoundException) {
            false
        } catch (e: Throwable) {
            SdkLog.w("native crash handler not installed: $e")
            false
        }
    }

    fun delete(file: File) = Files.deleteQuietly(file)

    private val setScopeMethod: java.lang.reflect.Method? by lazy {
        try {
            Class.forName("dev.bugcapsule.ndk.BugCapsuleNdk").getMethod("setScope", ByteArray::class.java)
        } catch (_: Throwable) {
            null // bugcapsule-ndk older than the scope mirror
        }
    }

    /** Hands the scope JSON to the installed handler; false without one. */
    fun setScope(json: ByteArray): Boolean = try {
        setScopeMethod?.invoke(null, json) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    companion object {
        /**
         * Fills an event from [record]. `instructionAddr` is the exact PC for
         * the crashing frame and the return address minus one for every
         * caller, so it points into the call instruction (monitoring-v1 §3:
         * the server resolves addresses exactly as sent).
         */
        fun fill(ev: MutableMap<String, Any?>, record: NativeCrashRecord, appId: String, buildIds: (String) -> String?) {
            val images = LinkedHashMap<String, MutableMap<String, Any?>>()
            val frames = record.frames.mapIndexed { i, f -> f to (if (i == 0 || f.pc == 0L) f.pc else f.pc - 1) }.reversed().map { (f, addr) ->
                val frame = linkedMapOf<String, Any?>("instructionAddr" to hex(addr))
                if (f.symbol != null) frame["function"] = f.symbol
                if (f.image != null) {
                    frame["package"] = f.image
                    frame["inApp"] = isAppLibrary(f.image, appId)
                    if (f.imageBase != 0L && f.image !in images) {
                        val image = linkedMapOf<String, Any?>("type" to "elf", "codeFile" to f.image, "imageAddr" to hex(f.imageBase))
                        record.abi?.let { image["arch"] = arch(it) }
                        buildIds(f.image)?.let { image["debugId"] = it }
                        images[f.image] = image
                    }
                    // Exact image of the frame: without it the server picks the first image starting below the address.
                    if (f.imageBase != 0L) frame["imageAddr"] = hex(f.imageBase)
                } else {
                    frame["inApp"] = false
                }
                frame
            }
            val name = NativeCrashRecord.signalName(record.signal)
            ev["exception"] = linkedMapOf(
                "values" to listOf(
                    linkedMapOf(
                        "type" to name,
                        "value" to "${NativeCrashRecord.signalDescription(record.signal)} (code ${record.code}) at ${record.address}",
                        "mechanism" to ExceptionMapper.Mechanism(
                            "signal",
                            false,
                            linkedMapOf("signal" to record.signal, "code" to record.code, "address" to record.address, "name" to name),
                        ).toJson(),
                        "stacktrace" to linkedMapOf("frames" to frames),
                    ),
                ),
            )
            ev["threads"] = listOf(
                linkedMapOf(
                    "id" to record.tid,
                    "name" to record.thread,
                    "crashed" to true,
                    "current" to true,
                    "stacktrace" to linkedMapOf("frames" to frames),
                ),
            )
            ev["debugImages"] = images.values.filter { it["debugId"] != null }.toList()
        }

        private fun hex(v: Long) = "0x" + java.lang.Long.toHexString(v)

        /** App libraries live in the app's install dir (or its APK), not in /system or /apex. */
        fun isAppLibrary(path: String, appId: String): Boolean =
            !path.startsWith("/system/") && !path.startsWith("/apex/") && !path.startsWith("/vendor/") &&
                (path.contains("/data/app/") || path.contains(appId))

        fun arch(abi: String) = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> abi
        }
    }
}
