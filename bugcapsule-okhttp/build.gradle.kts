plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule OkHttp integration: capture exchanges and serve them in replay"

android {
    namespace = "dev.bugcapsule.okhttp"
}

dependencies {
    api(project(":bugcapsule-android"))
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.gson)
}
