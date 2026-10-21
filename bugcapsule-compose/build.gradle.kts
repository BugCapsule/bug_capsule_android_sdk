plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

description = "BugCapsule Jetpack Compose helpers: semantic actions, tracked text, navigation, screen tracing, feedback sheet"

android {
    namespace = "dev.bugcapsule.compose"
    buildFeatures.compose = true
}

dependencies {
    api(project(":bugcapsule-android"))
    api(project(":bugcapsule-navigation"))
    implementation(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    // The feedback sheet (Material 3 ModalBottomSheet).
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
