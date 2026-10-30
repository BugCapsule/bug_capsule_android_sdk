plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

description = "Compose renderer for BugCapsule patch surfaces"

android {
    namespace = "dev.bugcapsule.patch.compose"

    buildFeatures {
        compose = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    api(project(":bugcapsule-patch"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
