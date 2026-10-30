plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule patch runtime: signed replacements of registered module logic on an installed release build"

android {
    namespace = "dev.bugcapsule.patch"

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
