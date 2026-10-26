package dev.bugcapsule.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeSymbolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val buildId = ByteArray(20) { (it + 1).toByte() }

    @Test
    fun `reads the build id as the debug id the SDK reports`() {
        val elf = ElfFile.read(write("libshop.so", elf(symbols = true)))!!
        // First three UUID fields byte-swapped, like the Android SDK's ElfBuildId.
        assertEquals("04030201-0605-0807-090a-0b0c0d0e0f10", elf.debugId)
        assertTrue(elf.hasSymbols)
    }

    @Test
    fun `a stripped library has no symbols and a non-elf file is ignored`() {
        assertFalse(ElfFile.read(write("libstripped.so", elf(symbols = false)))!!.hasSymbols)
        assertNull(ElfFile.read(write("notes.so", "hello".toByteArray())))
        assertNull(ElfFile.read(write("truncated.so", elf(symbols = true).copyOf(80))))
    }

    @Test
    fun `picks the abi libraries with symbols once per debug id`() {
        val root = tmp.newFolder("merged")
        write("lib/arm64-v8a/libshop.so", elf(symbols = true), root)
        write("lib/armeabi-v7a/libshop.so", elf(symbols = true, id = ByteArray(20) { 9 }), root)
        write("lib/x86_64/libstripped.so", elf(symbols = false, id = ByteArray(20) { 7 }), root)
        write("lib/x86_64/libnoid.so", elf(symbols = true, id = null), root)
        write("lib/x86/copy.so", elf(symbols = true), root) // same build id as arm64: once
        write("lib/arm64-v8a/readme.txt", "x".toByteArray(), root)
        val libs = UploadNativeSymbolsTask.libraries(listOf(root))
        assertEquals(listOf("arm64-v8a/libshop.so", "armeabi-v7a/libshop.so"), libs.map { it.file.relativeTo(File(root, "lib")).path })
        assertEquals(listOf("arm64", "arm"), libs.map { it.arch })
    }

    @Test
    fun `the upload url carries arch and name`() {
        val url = MappingUploader("https://api.example.test/", "bcs_x").endpoint("elf", "id-1", mapOf("arch" to "arm64", "name" to "libshop.so"))
        assertEquals("https://api.example.test/v1/debug-files?kind=elf&debugId=id-1&arch=arm64&name=libshop.so", url)
    }

    private fun write(path: String, bytes: ByteArray, root: File = tmp.root): File =
        File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    /** A minimal little-endian ELF64: one PT_NOTE with the build id, `.symtab` (optional) and `.shstrtab` sections. */
    private fun elf(symbols: Boolean, id: ByteArray? = buildId): ByteArray {
        val names = "\u0000.symtab\u0000.shstrtab\u0000.text\u0000".toByteArray()
        val note = if (id == null) ByteArray(0) else ByteBuffer.allocate(12 + 4 + id.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putInt(id.size).putInt(3).put("GNU\u0000".toByteArray()).put(id).array()
        val noteOff = 64 + 56
        val namesOff = noteOff + note.size
        val shOff = (namesOff + names.size + 7) and 7.inv()
        val sections = 3
        val b = ByteBuffer.allocate(shOff + sections * 64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        b.position(16)
        b.putShort(3).putShort(183).putInt(1).putLong(0).putLong(64).putLong(shOff.toLong())
        b.putInt(0).putShort(64).putShort(56).putShort(1).putShort(64).putShort(sections.toShort()).putShort(2)
        // Program header: PT_NOTE.
        b.position(64)
        b.putInt(4).putInt(4).putLong(noteOff.toLong()).putLong(0).putLong(0).putLong(note.size.toLong()).putLong(note.size.toLong()).putLong(4)
        b.position(noteOff); b.put(note)
        b.position(namesOff); b.put(names)
        // Section headers: [0] null, [1] .symtab or .text, [2] .shstrtab.
        b.position(shOff + 64)
        b.putInt(if (symbols) 1 else 19).putInt(if (symbols) 2 else 1)
        b.position(shOff + 128)
        b.putInt(9).putInt(3).putLong(0).putLong(0).putLong(namesOff.toLong()).putLong(names.size.toLong())
        return b.array()
    }
}
