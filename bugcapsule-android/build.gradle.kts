plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule for Android: capture failures as replayable .bugcapsule capsules"

android {
    namespace = "dev.bugcapsule.android"
}

dependencies {
    api(libs.lifecycle.viewmodel)
    // Preferences DataStore checkpoints are optional: apps that use
    // BugCapsuleDataStore bring datastore-preferences (1.1+) themselves.
    compileOnly(libs.datastore.preferences.core)
    compileOnly(libs.coroutines.core)
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.datastore.preferences.core)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
}

// The shared spec fixtures (../spec-fixtures) are read from the file system
// by the tests; see SpecFixtures.kt.
tasks.withType<Test>().configureEach {
    systemProperty("bugcapsule.specFixtures", rootProject.file("spec-fixtures").absolutePath)
    systemProperty(
        "bugcapsule.upstreamFixtures",
        rootProject.file("../bug_capsule_flutter_sdk/spec/fixtures").absolutePath,
    )
}
