plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule Jetpack Navigation integration: route events, checkpoint and restore"

android {
    namespace = "dev.bugcapsule.navigation"
}

dependencies {
    api(project(":bugcapsule-android"))
    api(libs.navigation.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.navigation.testing)
}
