package dev.bugcapsule.gradle

import org.gradle.api.provider.Property

/**
 * `bugcapsule { }` settings.
 *
 * ```kotlin
 * bugcapsule {
 *     url.set("https://api.bugcapsule.dev")          // default: -PbugcapsuleUrl / BUGCAPSULE_URL
 *     secretKey.set(providers.environmentVariable("BUGCAPSULE_SECRET_KEY")) // the default
 *     uploadProguardMapping.set(true)                // default true
 *     uploadNativeSymbols.set(true)                  // default true
 *     failOnUploadError.set(true)                    // default true
 * }
 * ```
 */
abstract class BugCapsuleExtension {
    /** Backend base URL. Default: Gradle property `bugcapsuleUrl`, else env `BUGCAPSULE_URL`. */
    abstract val url: Property<String>

    /** Project secret key (`bcs_...`). Default: Gradle property `bugcapsuleSecretKey`, else env `BUGCAPSULE_SECRET_KEY`. */
    abstract val secretKey: Property<String>

    /** Upload mapping.txt automatically after `assemble<Variant>`/`bundle<Variant>` when url and key are set. Default true. */
    abstract val uploadProguardMapping: Property<Boolean>

    /**
     * Upload the unstripped native libraries (`.so` with a build id and symbols) of each
     * non-debuggable variant after `assemble<Variant>`/`bundle<Variant>` when url and key
     * are set, so native crashes are symbolicated. Default true; variants without native
     * code skip it, debuggable ones only upload with `uploadBugCapsuleNativeSymbols<Variant>`.
     */
    abstract val uploadNativeSymbols: Property<Boolean>

    /** Fail the build when the upload fails (otherwise only warn). Default true. */
    abstract val failOnUploadError: Property<Boolean>
}
