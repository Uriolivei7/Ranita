import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cloudsync"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinJvmCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(listOf(
                "-Xopt-in=kotlin.RequiresOptIn",
                "-Xjvm-default=all"
            ))
        }
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.4")
}

tasks.register("packageCs3") {
    dependsOn("bundleReleaseAar")
    doLast {
        copy {
            from(layout.buildDirectory.dir("outputs/aar"))
            into(layout.buildDirectory.dir("cloudsync"))
            include("*-release.aar")
            rename { "CloudSyncProvider.cs3" }
        }
        val dir = layout.buildDirectory.dir("cloudsync").get()
        println("Plugin empaquetado: ${dir.asFile.absolutePath}/CloudSyncProvider.cs3")
    }
}
