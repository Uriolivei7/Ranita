// use an integer for version numbers
version = 2

cloudstream {
    description = "Movie! Plus” catálogo de películas, series, animes y más"
    language = "es"
    authors = listOf("Ranita")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    isCrossPlatform = false
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
