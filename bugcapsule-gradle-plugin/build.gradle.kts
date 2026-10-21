import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
}

group = "dev.bugcapsule"
version = "0.3.0"

dependencies {
    // AGP Variant API; supplied at runtime by the Android Gradle Plugin the app applies.
    compileOnly("com.android.tools.build:gradle-api:8.13.0")

    testImplementation("junit:junit:4.13.2")
}

// Bytecode targets Java 17 (the AGP 8 minimum) whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        // Gradle 8.x embeds a Kotlin 2.0 stdlib; do not use newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    plugins {
        create("bugcapsule") {
            id = "dev.bugcapsule.gradle"
            implementationClass = "dev.bugcapsule.gradle.BugCapsulePlugin"
            displayName = "BugCapsule Gradle plugin"
            description = "Embeds a ProGuard/R8 mapping id in Android APKs and uploads mapping.txt to BugCapsule."
        }
    }
}

tasks.withType<ValidatePlugins>().configureEach {
    enableStricterValidation.set(true)
    failOnWarning.set(true)
}
