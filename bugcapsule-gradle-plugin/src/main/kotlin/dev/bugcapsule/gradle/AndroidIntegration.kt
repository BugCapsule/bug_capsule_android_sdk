package dev.bugcapsule.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project

/**
 * Per-variant wiring through the AGP Variant API. Kept apart from [BugCapsulePlugin]
 * so AGP classes load only when `com.android.application` is applied.
 *
 * No task cycle: the mapping is produced by `minify<Variant>WithR8`, whose inputs are
 * classes, Java resources and keep rules (none depend on assets); the generated assets
 * directory feeds `merge<Variant>Assets`, which is consumed later by packaging.
 */
internal object AndroidIntegration {
    fun configure(project: Project, extension: BugCapsuleExtension) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant -> configureVariant(project, extension, variant) }
    }

    private fun configureVariant(project: Project, extension: BugCapsuleExtension, variant: ApplicationVariant) {
        if (!variant.isMinifyEnabled) {
            project.logger.info("BugCapsule: variant '{}' is not minified, no ProGuard mapping to handle", variant.name)
            return
        }
        val assets = variant.sources.assets
        if (assets == null) {
            project.logger.warn("BugCapsule: variant '{}' has no assets source set; mapping id not embedded", variant.name)
            return
        }
        val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)

        val generate = project.tasks.register("generateBugCapsuleProguardUuid$suffix", GenerateProguardUuidTask::class.java) {
            it.group = TASK_GROUP
            it.description = "Writes the ProGuard mapping id of the '${variant.name}' variant into its assets."
            it.mappingFile.set(mapping)
        }
        assets.addGeneratedSourceDirectory(generate, GenerateProguardUuidTask::outputDir)

        val upload = project.tasks.register("uploadBugCapsuleProguardMapping$suffix", UploadProguardMappingTask::class.java) {
            it.group = TASK_GROUP
            it.description = "Uploads the ProGuard mapping of the '${variant.name}' variant to BugCapsule."
            it.mappingFile.set(mapping)
            it.debugMetaFile.set(generate.flatMap { task -> task.outputDir.file(DebugMeta.FILE_NAME) })
            it.url.set(extension.url)
            it.secretKey.set(extension.secretKey)
            it.failOnUploadError.set(extension.failOnUploadError)
        }

        val skipReason = when {
            !extension.uploadProguardMapping.get() -> "bugcapsule.uploadProguardMapping is false"
            extension.url.orNull.isNullOrBlank() -> "no url (bugcapsule.url, -PbugcapsuleUrl or BUGCAPSULE_URL)"
            extension.secretKey.orNull.isNullOrBlank() ->
                "no secret key (bugcapsule.secretKey, -PbugcapsuleSecretKey or BUGCAPSULE_SECRET_KEY)"
            else -> null
        }
        if (skipReason != null) {
            project.logger.info(
                "BugCapsule: automatic ProGuard mapping upload for '{}' disabled: {}. Run {} to upload manually.",
                variant.name, skipReason, upload.name,
            )
            return
        }
        val lifecycleTasks = setOf("assemble$suffix", "bundle$suffix")
        project.tasks.matching { it.name in lifecycleTasks }.configureEach { it.finalizedBy(upload) }
    }

    private const val TASK_GROUP = "bugcapsule"
}
