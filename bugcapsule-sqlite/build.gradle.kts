plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule SQLite / Room integration: db.sql.query spans"

android {
    namespace = "dev.bugcapsule.sqlite"
}

dependencies {
    api(project(":bugcapsule-android"))
    api(libs.sqlite)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sqlite.framework)
}
