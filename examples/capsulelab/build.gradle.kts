plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.bugcapsule.capsulelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bugcapsule.capsulelab"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "dev.bugcapsule.android.replay.BugCapsuleReplayRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":bugcapsule-android"))
    implementation(project(":bugcapsule-patch"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    androidTestImplementation(project(":bugcapsule-android-replay"))
    androidTestImplementation(libs.androidx.test.runner)
}
