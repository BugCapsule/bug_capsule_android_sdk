plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "BugCapsule NDK crash handler: native signals recorded and sent on next launch"

android {
    namespace = "dev.bugcapsule.ndk"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // Pure C: no C++ runtime to package.
                arguments += "-DANDROID_STL=none"
                cFlags += listOf("-Wall", "-Wextra")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
