// ============================================================================
// Root build script — matches phisher98/cloudstream-extensions-phisher's
// EXACT working toolchain (AGP 9.1.1 + Kotlin 2.3.21 + JDK 17 + Gradle 9.x).
//
// IMPORTANT: cloudstream3:pre-release JAR is compiled with Kotlin 2.3.x.
// Using an older Kotlin compiler (e.g. 1.9.x) causes ALL cloudstream3
// classes to fail loading with: "incompatible Kotlin metadata version
// 2.3.0, but compiler 1.9.0 can only read up to 2.0.0".
// ============================================================================
import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Same versions Phisher uses in production — proven to build on
        // ubuntu-latest with JDK 17.
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            // Required so Jackson @JsonProperty(...) annotations keep
            // working on data-class constructor params.
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(
            System.getenv("GITHUB_REPOSITORY")?.let { "https://github.com/$it" }
                ?: "https://github.com/Suryansh4567/KatMovieHD-CS"
        )
        authors = listOf("arena")
    }

    android {
        namespace = "com.arena"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        lint { targetSdk = 36 }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.2")
        implementation("androidx.annotation:annotation:1.10.0")
        // Pin to 2.13.1 — newer Jackson breaks reflective deserialisation in CS3.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
