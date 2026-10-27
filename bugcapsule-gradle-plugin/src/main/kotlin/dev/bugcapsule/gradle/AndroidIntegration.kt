package dev.bugcapsule.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Per-variant wiring through the AGP Variant API: ProGuard mapping id and upload
 * (minified variants), native symbol upload (every variant). Kept apart from [BugCapsulePlugin]
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
        val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        configureNativeSymbols(project, extension, variant, suffix)
        configureProguard(project, extension, variant, suffix)
    }

    private fun configureProguard(project: Project, extension: BugCapsuleExtension, variant: ApplicationVariant, suffix: String) {
        if (!variant.isMinifyEnabled) {
            project.logger.info("BugCapsule: variant '{}' is not minified, no ProGuard mapping to handle", variant.name)
            return
        }
        val assets = variant.sources.assets
        if (assets == null) {
            project.logger.warn("BugCapsule: variant '{}' has no assets source set; mapping id not embedded", variant.name)
            return
        }
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
        finalizeLifecycle(project, extension, variant.name, suffix, upload, extension.uploadProguardMapping.get(), "bugcapsule.uploadProguardMapping")
    }

    /**
     * `merge<Variant>NativeLibs` holds the variant's `.so` files before the strip task:
     * the app's own and those of its AARs (Flutter's engine and plugins included).
     */
    private fun configureNativeSymbols(project: Project, extension: BugCapsuleExtension, variant: ApplicationVariant, suffix: String) {
        val mergeTask = "merge${suffix}NativeLibs"
        val upload = project.tasks.register("uploadBugCapsuleNativeSymbols$suffix", UploadNativeSymbolsTask::class.java) {
            it.group = TASK_GROUP
            it.description = "Uploads the unstripped native libraries of the '${variant.name}' variant to BugCapsule."
            // A lazy task collection: resolved (outputs and task dependency) only when the upload runs.
            it.nativeLibs.from(project.tasks.matching { t -> t.name == mergeTask })
            it.url.set(extension.url)
            it.secretKey.set(extension.secretKey)
            it.failOnUploadError.set(extension.failOnUploadError)
        }
        // Debuggable builds (every Run from the IDE) are not shipped and their libraries are large:
        // the task exists for them, but only runs when asked.
        if (variant.debuggable) {
            project.logger.info("BugCapsule: variant '{}' is debuggable, run {} to upload its native symbols", variant.name, upload.name)
            return
        }
        finalizeLifecycle(project, extension, variant.name, suffix, upload, extension.uploadNativeSymbols.get(), "bugcapsule.uploadNativeSymbols")
    }

    /** Runs [upload] after `assemble<Variant>`/`bundle<Variant>` unless turned off or url/key are missing. */
    private fun finalizeLifecycle(
        project: Project,
        extension: BugCapsuleExtension,
        variantName: String,
        suffix: String,
        upload: TaskProvider<*>,
        enabled: Boolean,
        switch: String,
    ) {
        val skipReason = when {
            !enabled -> "$switch is false"
            extension.url.orNull.isNullOrBlank() -> "no url (bugcapsule.url, -PbugcapsuleUrl or BUGCAPSULE_URL)"
            extension.secretKey.orNull.isNullOrBlank() ->
                "no secret key (bugcapsule.secretKey, -PbugcapsuleSecretKey or BUGCAPSULE_SECRET_KEY)"
            else -> null
        }
        if (skipReason != null) {
            project.logger.info(
                "BugCapsule: automatic {} for '{}' disabled: {}. Run it to upload manually.",
                upload.name, variantName, skipReason,
            )
            return
        }
        val lifecycleTasks = setOf("assemble$suffix", "bundle$suffix")
        project.tasks.matching { it.name in lifecycleTasks }.configureEach { it.finalizedBy(upload) }
    }

    private const val TASK_GROUP = "bugcapsule"
}
