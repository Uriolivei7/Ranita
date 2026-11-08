// use an integer for version numbers
version = 7

cloudstream {
    language = "es"
    description = "El Chavo del 8 y Chespirito - Episodios completos"
    authors = listOf("Ranita")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Cartoon",
        "Anime"
    )
}

// ByseExtractor usa AppUtils.parseJson, que es inline JVM 11 (AppUtils.class major 55).
// El root fuerza Java/Kotlin 1.8 en todos los módulos, así que hay que subir AMBOS a 11
// a nivel de módulo (mismo patrón que YoutubeProvider) para evitar
// "Cannot inline bytecode built with JVM target 11" e "Inconsistent JVM-target compatibility".
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
