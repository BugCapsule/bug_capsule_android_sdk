package dev.bugcapsule.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `bugcapsule-debug-meta.properties` (`proguardUuid=<uuid>`) into [outputDir],
 * which the plugin registers as a generated assets directory of the variant. The
 * id is derived from the mapping content, so it is stable for a given mapping.
 */
@CacheableTask
abstract class GenerateProguardUuidTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        val uuid = DebugMeta.proguardUuid(mappingFile.get().asFile)
        DebugMeta.write(dir, uuid)
        logger.info("BugCapsule: ProGuard mapping id {}", uuid)
    }
}
