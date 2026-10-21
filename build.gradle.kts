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
                }
            }
            lint {
                abortOnError = false
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
                            description.set(project.description ?: project.name)
                            licenses {
                                license {
                                    name.set("Apache-2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
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

tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes every SDK artifact to build/repo (a local Maven file repository)."
    dependsOn(
        subprojects.filter { it.name.startsWith("bugcapsule-") }
            .map { "${it.path}:publishReleasePublicationToLocalRepository" },
    )
}
