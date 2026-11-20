// use an integer for version numbers
version = 4

cloudstream {
    description = "Movie! Plus â€” catÃ¡logo de pelÃ­culas, series, animes y mÃ¡s"
    language = "es"
    authors = listOf("Ranita")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    isCrossPlatform = false
}


// AppUtils.parseJson es inline JVM 11 (AppUtils.class major 55); el root fuerza 1.8.
// Alinear Java y Kotlin a 11 a nivel de mÃ³dulo para evitar "Cannot inline bytecode built with JVM target 11".
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
