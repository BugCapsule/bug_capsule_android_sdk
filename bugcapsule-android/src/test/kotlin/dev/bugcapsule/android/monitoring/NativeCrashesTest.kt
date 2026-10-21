package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeCrashesTest {
    private val record = """
        bugcapsule-native-crash 1
        signal 11
        code 1
        address 0x0
        tid 4321
        thread Render Thread
        timestamp 1761000000123
        abi arm64-v8a
        frame 0x7a1b2c3d4e 0x7a1b200000 0x7a1b2c3d00 /data/app/~~x/com.shop-1/lib/arm64/libshop.so crash_here
        frame 0x7a1b2c3f00 0x7a1b200000 0x0 /data/app/~~x/com.shop-1/lib/arm64/libshop.so -
        frame 0x7f00001234 0x7f00000000 0x7f00001200 /apex/com.android.runtime/lib64/bionic/libc.so __libc_start_main
        end
    """.trimIndent()

    @Test
    fun parsesTheRecordWrittenByTheSignalHandler() {
        val r = NativeCrashRecord.parse(record)!!
        assertEquals(11, r.signal)
        assertEquals(1, r.code)
        assertEquals("0x0", r.address)
        assertEquals(4321L, r.tid)
        assertEquals("Render Thread", r.thread)
        assertEquals(1761000000123L, r.timestampMs)
        assertEquals(3, r.frames.size)
        assertEquals(0x7a1b2c3d4eL, r.frames[0].pc)
        assertEquals("crash_here", r.frames[0].symbol)
        assertNull(r.frames[1].symbol)
    }

    @Test
    fun acceptsTruncatedRecordsAndRejectsOtherFiles() {
        val truncated = record.lines().take(9).joinToString("\n")
        assertEquals(1, NativeCrashRecord.parse(truncated)!!.frames.size)
        assertNull(NativeCrashRecord.parse("hello"))
        assertNull(NativeCrashRecord.parse("bugcapsule-native-crash 1\ncode 1\n"))
        assertNull(NativeCrashRecord.parse("bugcapsule-native-crash 1\nsignal 6\nthread \nend")!!.thread)
    }

    @Test
    fun becomesASignalEventWithElfImages() {
        val ev = LinkedHashMap<String, Any?>()
        NativeCrashes.fill(ev, NativeCrashRecord.parse(record)!!, "com.shop") { path ->
            if (path.endsWith("libshop.so")) "11223344-5566-7788-99aa-bbccddeeff00" else null
        }
        val value = J.obj(J.list(J.obj(ev["exception"])!!["values"])!![0])!!
        assertEquals("SIGSEGV", value["type"])
        assertEquals("signal", J.obj(value["mechanism"])!!["type"])
        assertEquals(false, J.obj(value["mechanism"])!!["handled"])
        val frames = J.list(J.obj(value["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals("crashing frame last", "crash_here", frames.last()["function"])
        assertEquals("crashing frame: exact PC", "0x7a1b2c3d4e", frames.last()["instructionAddr"])
        assertEquals(
            "callers: return address - 1",
            listOf("0x7f00001233", "0x7a1b2c3eff"),
            frames.dropLast(1).map { it["instructionAddr"] },
        )
        assertEquals(true, frames.last()["inApp"])
        assertEquals(false, frames.first()["inApp"])
        assertEquals("unresolvable image keeps its load address", "0x7f00000000", frames.first()["imageAddr"])
        val images = J.list(ev["debugImages"])!!.map { J.obj(it)!! }
        assertEquals(1, images.size)
        assertEquals("elf", images[0]["type"])
        assertEquals("arm64", images[0]["arch"])
        assertEquals("0x7a1b200000", images[0]["imageAddr"])
    }

    @Test
    fun readsTheGnuBuildIdOfAnElf64Image() {
        val id = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14)
        val elf = elf64WithBuildId(id)
        assertEquals("04030201-0605-0807-090a-0b0c0d0e0f10", ElfBuildId.debugId(elf))
        assertNull(ElfBuildId.debugId(ByteArray(64)))
        assertEquals("04030201-0000-0000-0000-000000000000", ElfBuildId.format(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun systemLibrariesAreNotInApp() {
        assertFalse(NativeCrashes.isAppLibrary("/system/lib64/libc.so", "com.shop"))
        assertTrue(NativeCrashes.isAppLibrary("/data/app/~~a/com.shop-2/base.apk!/lib/arm64-v8a/libshop.so", "com.shop"))
    }

    /** ELF64 little-endian header + one PT_NOTE program header + an NT_GNU_BUILD_ID note. */
    private fun elf64WithBuildId(id: ByteArray): ByteArray {
        val noteOffset = 64 + 56
        val noteSize = 12 + 4 + id.size
        val b = ByteBuffer.allocate(noteOffset + noteSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        b.putLong(32, 64) // e_phoff
        b.putShort(54, 56) // e_phentsize
        b.putShort(56, 1) // e_phnum
        b.putInt(64, 4) // p_type PT_NOTE
        b.putLong(64 + 8, noteOffset.toLong()) // p_offset
        b.putLong(64 + 32, noteSize.toLong()) // p_filesz
        b.putInt(noteOffset, 4)
        b.putInt(noteOffset + 4, id.size)
        b.putInt(noteOffset + 8, 3)
        b.position(noteOffset + 12)
        b.put("GNU".toByteArray()).put(0)
        b.put(id)
        return b.array()
    }
}
