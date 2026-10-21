plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule replay runner for androidTest: BugCapsuleReplayRunner and BugCapsuleReplayTest"

android {
    namespace = "dev.bugcapsule.android.replay"
}

dependencies {
    api(project(":bugcapsule-android"))
    api(libs.androidx.test.runner)
    api(libs.androidx.test.core)
    api(libs.androidx.test.ext.junit)
    api(libs.junit)

    testImplementation(libs.robolectric)
}
