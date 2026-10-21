package dev.bugcapsule.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Uploads the variant's `mapping.txt` to BugCapsule under the id written in the
 * debug-meta asset. Always runs when requested (an upload has no local output).
 */
@UntrackedTask(because = "Uploads to a remote server; has no local outputs")
abstract class UploadProguardMappingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** The generated `bugcapsule-debug-meta.properties`, source of the debug id. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val debugMetaFile: RegularFileProperty

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
            val missing = listOfNotNull(
                "url (bugcapsule.url, -PbugcapsuleUrl or BUGCAPSULE_URL)".takeIf { baseUrl.isEmpty() },
                "secret key (bugcapsule.secretKey, -PbugcapsuleSecretKey or BUGCAPSULE_SECRET_KEY)".takeIf { key.isEmpty() },
            )
            fail("BugCapsule: cannot upload the ProGuard mapping, missing ${missing.joinToString(" and ")}")
            return
        }
        val debugId = DebugMeta.readProguardUuid(debugMetaFile.get().asFile)
        val mapping = mappingFile.get().asFile
        logger.lifecycle("BugCapsule: uploading ProGuard mapping {} ({} bytes), debug id {}", mapping, mapping.length(), debugId)
        try {
            val result = MappingUploader(baseUrl, key).uploadProguardMapping(mapping, debugId)
            logger.lifecycle("BugCapsule: uploaded ProGuard mapping, debug id {} (HTTP {})", debugId, result.status)
        } catch (e: Exception) {
            // Messages come from MappingUploader/the server and never contain the key.
            fail(e.message ?: e.javaClass.name, e)
        }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (failOnUploadError.get()) throw GradleException(message, cause)
        logger.warn("{} (ignored: bugcapsule.failOnUploadError = false)", message)
    }
}
