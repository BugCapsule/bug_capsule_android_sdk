package dev.bugcapsule.gradle

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the native symbol upload needs from an ELF shared library: its GNU
 * build id (as the debug id the SDK reports for it, see the Android SDK's
 * `ElfBuildId`) and whether it still has symbols worth uploading.
 */
internal class ElfFile private constructor(
    /** Debug id: the first 16 bytes of the build id with the first three UUID fields byte-swapped (Sentry's rule). */
    val debugId: String?,
    /** Has `.debug_info` or `.symtab`: a stripped library has nothing to resolve. */
    val hasSymbols: Boolean,
) {
    companion object {
        private const val PT_NOTE = 4
        private const val SHT_NOTE = 7
        private const val NT_GNU_BUILD_ID = 3

        /** Null when [file] is not an ELF file. */
        fun read(file: File): ElfFile? = try {
            RandomAccessFile(file, "r").use { read(it) }
        } catch (_: Exception) {
            null
        }

        private fun read(f: RandomAccessFile): ElfFile? {
            val ident = ByteArray(16)
            if (f.length() < 52) return null
            f.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) {
                return null
            }
            val is64 = ident[4].toInt() == 2
            val order = if (ident[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val header = buffer(f, 0, if (is64) 64 else 52, order)
            val phoff = if (is64) header.getLong(32) else u32(header.getInt(28))
            val shoff = if (is64) header.getLong(40) else u32(header.getInt(32))
            val phentsize = u16(header.getShort(if (is64) 54 else 42))
            val phnum = u16(header.getShort(if (is64) 56 else 44))
            val shentsize = u16(header.getShort(if (is64) 58 else 46))
            val shnum = u16(header.getShort(if (is64) 60 else 48))
            val shstrndx = u16(header.getShort(if (is64) 62 else 50))

            var buildId: ByteArray? = null
            for (i in 0 until phnum) {
                val ph = buffer(f, phoff + i.toLong() * phentsize, phentsize, order)
                if (ph.getInt(0) != PT_NOTE) continue
                val offset = if (is64) ph.getLong(8) else u32(ph.getInt(4))
                val size = if (is64) ph.getLong(32) else u32(ph.getInt(16))
                buildId = findBuildId(buffer(f, offset, size.toInt(), order))
                if (buildId != null) break
            }

            var hasSymbols = false
            if (shoff > 0 && shnum > 0 && shstrndx < shnum) {
                val sections = (0 until shnum).map { buffer(f, shoff + it.toLong() * shentsize, shentsize, order) }
                val strtab = sections[shstrndx]
                val strOffset = if (is64) strtab.getLong(24) else u32(strtab.getInt(16))
                val strSize = if (is64) strtab.getLong(32) else u32(strtab.getInt(20))
                val names = buffer(f, strOffset, strSize.toInt(), order).array()
                for (s in sections) {
                    val name = cString(names, s.getInt(0))
                    if (name == ".debug_info" || name == ".symtab") hasSymbols = true
                    if (buildId == null && s.getInt(4) == SHT_NOTE) {
                        val offset = if (is64) s.getLong(24) else u32(s.getInt(16))
                        val size = if (is64) s.getLong(32) else u32(s.getInt(20))
                        buildId = findBuildId(buffer(f, offset, size.toInt(), order))
                    }
                }
            }
            return ElfFile(buildId?.let { debugId(it) }, hasSymbols)
        }

        private fun findBuildId(b: ByteBuffer): ByteArray? {
            var p = 0
            val end = b.limit()
            while (p + 12 <= end) {
                val namesz = b.getInt(p)
                val descsz = b.getInt(p + 4)
                val type = b.getInt(p + 8)
                if (namesz < 0 || descsz < 0) return null
                val descStart = p + 12 + align4(namesz)
                if (descStart + descsz > end) return null
                if (type == NT_GNU_BUILD_ID && namesz == 4 && b.get(p + 12) == 'G'.code.toByte() &&
                    b.get(p + 13) == 'N'.code.toByte() && b.get(p + 14) == 'U'.code.toByte()
                ) {
                    return ByteArray(descsz) { b.get(descStart + it) }
                }
                p = descStart + align4(descsz)
            }
            return null
        }

        fun debugId(id: ByteArray): String {
            val d = ByteArray(16) { if (it < id.size) id[it] else 0 }
            fun swap(i: Int, j: Int) {
                val t = d[i]; d[i] = d[j]; d[j] = t
            }
            swap(0, 3); swap(1, 2); swap(4, 5); swap(6, 7)
            val hex = d.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        private fun buffer(f: RandomAccessFile, offset: Long, size: Int, order: ByteOrder): ByteBuffer {
            require(offset >= 0 && size >= 0 && offset + size <= f.length()) { "outside the file" }
            val bytes = ByteArray(size)
            f.seek(offset)
            f.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(order)
        }

        private fun cString(bytes: ByteArray, start: Int): String {
            if (start < 0 || start >= bytes.size) return ""
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, start, end - start, Charsets.US_ASCII)
        }

        private fun align4(n: Int) = (n + 3) and 3.inv()
        private fun u16(v: Short) = v.toInt() and 0xffff
        private fun u32(v: Int) = v.toLong() and 0xffffffffL
    }
}
