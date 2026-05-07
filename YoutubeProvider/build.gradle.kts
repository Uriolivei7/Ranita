plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")

}

android {
    namespace = "com.arabseed" // أو com.cimatn حسب مشروعك
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}


dependencies {
    val cloudstream by configurations

    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    cloudstream("com.lagradost:cloudstream3:pre-release")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")



    implementation("org.jsoup:jsoup:1.17.2")

    implementation("org.mozilla:rhino:1.7.14")



    implementation("com.google.protobuf:protobuf-javalite:3.25.1")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.2")
    implementation("com.github.spotbugs:spotbugs-annotations:4.8.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    implementation(kotlin("stdlib-jdk8"))
}