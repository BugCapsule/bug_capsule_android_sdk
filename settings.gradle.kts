pluginManagement {
    // ProGuard/R8 mapping UUID + upload (dev.bugcapsule.gradle), used by the demo.
    includeBuild("bugcapsule-gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bugcapsule-android-sdk"

include(":bugcapsule-android")
include(":bugcapsule-okhttp")
include(":bugcapsule-navigation")
include(":bugcapsule-compose")
include(":bugcapsule-ndk")
include(":bugcapsule-sqlite")
include(":bugcapsule-android-replay")
include(":bugcapsule-android-play")
include(":bugcapsule-patch")
include(":demo")
project(":demo").projectDir = file("examples/demo")
include(":patchlab")
project(":patchlab").projectDir = file("examples/patchlab")
