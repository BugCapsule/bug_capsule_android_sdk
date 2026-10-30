plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule emergency recovery for Google Play: REQUIRE_UPDATE through Play In-App Updates"

android {
    namespace = "dev.bugcapsule.android.play"
}

dependencies {
    api(project(":bugcapsule-android"))
    api(libs.play.app.update)
    // The update flow is started through an ActivityResultLauncher<IntentSenderRequest>.
    api(libs.activity)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
