import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val sdkGroup = providers.gradleProperty("GROUP").get()
val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

// Shared configuration of the SDK library modules (`bugcapsule-*`): Android
// defaults, Kotlin, and a `release` Maven publication to <root>/build/repo.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
    if (!name.startsWith("bugcapsule-")) return@subprojects
    tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.withType<org.gradle.api.tasks.bundling.Zip>().configureEach {
        if (name == "bundleReleaseAar") {
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
    }
    group = sdkGroup
    version = sdkVersion
    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "maven-publish")
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 23
                consumerProguardFiles("consumer-rules.pro")
                buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
            }
            buildFeatures.buildConfig = true
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
                unitTests.isReturnDefaultValues = true
                unitTests.all { it.maxHeapSize = "2g" }
            }
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                    withJavadocJar()
                }
            }
            lint {
                abortOnError = true
            }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        groupId = sdkGroup
                        artifactId = project.name
                        version = sdkVersion
                        pom {
                            name.set(project.name)
                            url.set("https://github.com/draz26648/bug_capsule_android_sdk")
                            scm {
                                url.set("https://github.com/draz26648/bug_capsule_android_sdk")
                                connection.set("scm:git:https://github.com/draz26648/bug_capsule_android_sdk.git")
                                developerConnection.set("scm:git:ssh://git@github.com/draz26648/bug_capsule_android_sdk.git")
                            }
                            developers {
                                developer {
                                    id.set("draz26648")
                                    name.set("BugCapsule maintainers")
                                    url.set("https://github.com/draz26648")
                                }
                            }
                            description.set(project.description ?: project.name)
                            licenses {
                                license {
                                    name.set("Bug Capsule Proprietary License")
                                    url.set("https://github.com/BugCapsule/bug_capsule_android_sdk/blob/main/LICENSE")
                                }
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "local"
                        url = uri(rootProject.layout.buildDirectory.dir("repo"))
                    }
                }
            }
        }
    }
}

tasks.register("test") {
    description = "Runs the BugCapsule Gradle plugin tests (subprojects run their own `test`)."
    dependsOn(gradle.includedBuild("bugcapsule-gradle-plugin").task(":test"))
}

tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes every SDK artifact to build/repo (a local Maven file repository)."
    dependsOn(gradle.includedBuild("bugcapsule-gradle-plugin").task(":publishAllPublicationsToLocalRepository"))
    dependsOn(
        subprojects.filter { it.name.startsWith("bugcapsule-") }
            .map { "${it.path}:publishReleasePublicationToLocalRepository" },
    )
}
