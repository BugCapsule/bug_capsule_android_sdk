import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
}

group = "dev.bugcapsule"
val sdkProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}
version = sdkProperties.getProperty("VERSION_NAME")

dependencies {
    // AGP Variant API; supplied at runtime by the Android Gradle Plugin the app applies.
    compileOnly("com.android.tools.build:gradle-api:8.13.0")

    testImplementation("junit:junit:4.13.2")
}

// Bytecode targets Java 17 (the AGP 8 minimum) whatever JDK runs Gradle.
java {
    withSourcesJar()
    withJavadocJar()
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

publishing {
    repositories {
        maven { name = "local"; url = uri("../build/repo") }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("BugCapsule Gradle plugin")
            description.set("ProGuard/R8 mapping IDs and upload for BugCapsule")
            url.set("https://github.com/draz26648/bug_capsule_android_sdk")
            licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
            scm {
                url.set("https://github.com/draz26648/bug_capsule_android_sdk")
                connection.set("scm:git:https://github.com/draz26648/bug_capsule_android_sdk.git")
            }
            developers { developer { id.set("draz26648"); name.set("BugCapsule maintainers") } }
        }
    }
}

tasks.named<Jar>("javadocJar") {
    from(file("../README.md"))
    from(file("../LICENSE"))
}
