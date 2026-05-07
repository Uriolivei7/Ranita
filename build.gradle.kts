import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT") {
            exclude(group = "com.github.vidstige", module = "jadb")
        }
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.3.0")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "kotlinx-serialization")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/Uriolivei7/Ranita")
        authors = listOf("Ranita")
    }

    android {
        namespace = "com.example"
        buildFeatures.buildConfig = true

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

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
                    "-Xsuppress-version-warnings",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        //implementation("com.github.Uriolivei7:TestPlugins:-0e9dcb96de-1")
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.22.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.3")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.3")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")

        implementation("com.squareup.okhttp3:okhttp:5.3.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

        implementation("org.mozilla:rhino:1.9.1")
        implementation("com.google.code.gson:gson:2.14.0")
        implementation("androidx.annotation:annotation:1.10.0")

        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}