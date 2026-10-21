plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule for Android: replayable .bugcapsule capsules plus error, session, performance and feedback monitoring"

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
    // Frame metrics (slow/frozen frames) of screen transactions. JankStats
    // pins an old androidx.core at runtime; the app's (newer) one wins.
    implementation(libs.metrics.performance)
    implementation(libs.core.ktx)
    // Fragment spans are added only when the app uses AndroidX Fragments.
    compileOnly(libs.fragment.ktx)
    compileOnly(libs.activity.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.datastore.preferences.core)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.fragment.ktx)
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
