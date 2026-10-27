package dev.bugcapsule.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Uploads the variant's unstripped native libraries (`kind=elf`) so native
 * crash frames (NDK handler, tombstones, Flutter engine plugins) resolve to
 * functions, files and lines. One upload per library with a GNU build id and
 * symbols; stripped or id-less libraries are skipped.
 */
@UntrackedTask(because = "Uploads to a remote server; has no local outputs")
abstract class UploadNativeSymbolsTask : DefaultTask() {
    /** Output of `merge<Variant>NativeLibs`: the `.so` files under `lib/<abi>/`, before stripping. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibs: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val url: Property<String>

    /** Not an @Input so the key is never fingerprinted or shown as an input value. */
    @get:Internal
    abstract val secretKey: Property<String>

    @get:Input
    abstract val failOnUploadError: Property<Boolean>

    @TaskAction
    fun upload() {
        val baseUrl = url.orNull?.trim().orEmpty()
        val key = secretKey.orNull?.trim().orEmpty()
        if (baseUrl.isEmpty() || key.isEmpty()) {
            fail("BugCapsule: cannot upload native symbols, missing url or secret key")
            return
        }
        val libs = libraries(nativeLibs.files)
        if (libs.isEmpty()) {
            logger.lifecycle("BugCapsule: no native libraries with symbols to upload")
            return
        }
        val uploader = MappingUploader(baseUrl, key)
        for (lib in libs) {
            logger.lifecycle("BugCapsule: uploading native symbols {} ({}, {} bytes), debug id {}", lib.file.name, lib.arch, lib.file.length(), lib.debugId)
            try {
                uploader.upload("elf", lib.debugId, lib.file, mapOf("arch" to lib.arch, "name" to lib.file.name))
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.name, e)
            }
        }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (failOnUploadError.get()) throw GradleException(message, cause)
        logger.warn("{} (ignored: bugcapsule.failOnUploadError = false)", message)
    }

    internal class Library(val file: File, val debugId: String, val arch: String)

    internal companion object {
        /** `.so` files under [roots] worth uploading, once per debug id. */
        fun libraries(roots: Collection<File>): List<Library> {
            val out = LinkedHashMap<String, Library>()
            for (root in roots) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.sortedBy { it.path }.forEach { so ->
                    val elf = ElfFile.read(so) ?: return@forEach
                    val id = elf.debugId ?: return@forEach
                    if (!elf.hasSymbols) return@forEach
                    out.putIfAbsent(id, Library(so, id, arch(so.parentFile?.name.orEmpty())))
                }
            }
            return out.values.toList()
        }

        fun arch(abi: String) = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            else -> abi
        }
    }
}
