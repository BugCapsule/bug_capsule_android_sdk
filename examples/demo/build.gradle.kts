plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** A Gradle property (`-Pname=...`) or, failing that, an environment variable. */
fun setting(property: String, env: String, default: String = ""): String =
    providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: default

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.bugcapsule.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bugcapsule.demo"
        minSdk = 23
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.0"
        testInstrumentationRunner = "dev.bugcapsule.android.replay.BugCapsuleReplayRunner"

        // Embedded at build time; the CLI passes -PbugcapsuleGitSha=<sha> for replay builds.
        buildConfigField("String", "BUGCAPSULE_GIT_SHA", quoted(setting("bugcapsuleGitSha", "BUGCAPSULE_GIT_SHA")))
        buildConfigField("boolean", "BUGCAPSULE_GIT_DIRTY", setting("bugcapsuleGitDirty", "BUGCAPSULE_GIT_DIRTY", "false"))
        // Backend: the emulator reaches the host's 127.0.0.1 as 10.0.2.2. The
        // ingest key (bci_...) comes from the environment, never from sources.
        buildConfigField("String", "BUGCAPSULE_URL", quoted(setting("bugcapsuleBackendUrl", "BUGCAPSULE_DEMO_BACKEND_URL", "http://10.0.2.2:8787")))
        buildConfigField("String", "BUGCAPSULE_INGEST_KEY", quoted(setting("bugcapsuleIngestKey", "BUGCAPSULE_INGEST_KEY")))
        // Temporary, isolated fix toggle for verification: -PbugcapsuleDemoFix=race
        // (or BUGCAPSULE_DEMO_FIX=race). Comma-separated: race, price, legacy.
        buildConfigField("String", "DEMO_FIX", quoted(setting("bugcapsuleDemoFix", "BUGCAPSULE_DEMO_FIX")))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(project(":bugcapsule-android"))
    implementation(project(":bugcapsule-okhttp"))
    implementation(project(":bugcapsule-navigation"))
    implementation(project(":bugcapsule-compose"))

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    androidTestImplementation(project(":bugcapsule-android-replay"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
}
