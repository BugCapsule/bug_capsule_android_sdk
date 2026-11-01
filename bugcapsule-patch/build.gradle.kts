plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule patch runtime: signed replacements of registered module logic on an installed release build"

android {
    namespace = "dev.bugcapsule.patch"
    // Keep the reviewed runtime bytes in Maven AARs; app packaging may strip them.
    packaging.jniLibs.keepDebugSymbols += "**/libbcpatch.so"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Instrumentation must install on current Android; library minSdk stays 23.
    testOptions { targetSdk = 36 }
}

dependencies {
    api(project(":bugcapsule-android"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// Publishing an empty runtime AAR is never a valid release. Normal Kotlin tests
// remain usable without downloading native build inputs.
tasks.matching { it.name.startsWith("publishReleasePublication") }.configureEach {
    doFirst {
        providers.exec {
            commandLine("python3", rootProject.file("scripts/validate_patch_runtime.py"))
        }.result.get().assertNormalExitValue()
    }
}
