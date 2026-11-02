package dev.bugcapsule.android.internal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** File helpers that never throw into the host app except where documented. */
internal object Files {
    /** Atomic write (temp file + fsync + rename). Throws [IOException] (e.g. disk full); the temp file is removed. */
    @Throws(IOException::class)
    fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + "." + System.nanoTime() + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                try {
                    out.fd.sync()
                } catch (_: Exception) {
                }
            }
            if (!tmp.renameTo(file)) throw IOException("rename failed: $tmp -> $file")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw IOException(e)
        }
    }

    fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    fun removeTemporaryFiles(dir: File) {
        try {
            dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".tmp")) deleteQuietly(it) }
        } catch (_: Exception) {
        }
    }
}

/**
 * On-device files under `noBackupFilesDir/bugcapsule`: saved capsules (the
 * newest [maxSaved], none older than [maxAgeMs]), the cached remote config
 * and the outbox directory.
 */
internal class CapsuleStore(
    val root: File,
    private val maxSaved: Int = 20,
    private val maxAgeMs: Long = SAVED_CAPTURE_MAX_AGE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val capturesDir = File(root, "captures")
    val outboxDir = File(root, "outbox")
    val configFile = File(root, "remote_config.json")

    init {
        capturesDir.mkdirs()
        Files.removeTemporaryFiles(capturesDir)
        Files.removeTemporaryFiles(root)
        prune()
    }

    @Throws(java.io.IOException::class)
    fun save(capsuleId: String, bytes: ByteArray): File {
        val file = File(capturesDir, "$capsuleId.bugcapsule")
        Files.writeAtomically(file, bytes)
        prune()
        return file
    }

    /** Saved capsules, newest first; expired ones are left out (and deleted by the next prune). */
    fun savedCaptures(): List<File> = all().filter { !expired(it) }

    private fun all(): List<File> = (capturesDir.listFiles { f -> f.isFile && f.name.endsWith(".bugcapsule") }
        ?: emptyArray()).sortedByDescending { it.lastModified() }

    private fun expired(f: File) = nowMs() - f.lastModified() > maxAgeMs

    /** Deletes capsules past [maxAgeMs] and all but the newest [maxSaved]. */
    fun prune() {
        val (old, fresh) = all().partition { expired(it) }
        (old + fresh.drop(maxSaved)).forEach { Files.deleteQuietly(it) }
    }

    /** Kill switch: nothing captured before may leave the device later. */
    fun clearSavedCaptures() {
        all().forEach { Files.deleteQuietly(it) }
    }

    companion object {
        /** Default age after which saved capsules are deleted (BC-ENT-038). */
        const val SAVED_CAPTURE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
