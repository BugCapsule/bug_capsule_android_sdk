package dev.bugcapsule.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `dev.bugcapsule.gradle`: for Android application modules, embeds a ProGuard/R8
 * mapping id in the APK/AAB assets and uploads `mapping.txt` and the unstripped
 * native libraries to BugCapsule.
 * Applying it to any other project only creates the `bugcapsule` extension.
 */
class BugCapsulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION_NAME, BugCapsuleExtension::class.java)
        val providers = project.providers
        extension.url.convention(
            providers.gradleProperty("bugcapsuleUrl").orElse(providers.environmentVariable("BUGCAPSULE_URL")),
        )
        extension.secretKey.convention(
            providers.gradleProperty("bugcapsuleSecretKey").orElse(providers.environmentVariable("BUGCAPSULE_SECRET_KEY")),
        )
        extension.uploadProguardMapping.convention(true)
        extension.uploadNativeSymbols.convention(true)
        extension.failOnUploadError.convention(true)

        // AGP classes are only touched once AGP is present (they are compileOnly here).
        project.pluginManager.withPlugin("com.android.application") {
            AndroidIntegration.configure(project, extension)
        }
    }

    companion object {
        const val EXTENSION_NAME = "bugcapsule"
    }
}
