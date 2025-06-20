// use an integer for version numbers
version = 2


cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    //description = "Lorem Ipsum"
    authors = listOf("Ranita", "Yeji", "Mina")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "OVA",
    )

    //iconUrl = "https://latanime.org/public/img/logito.png"
}

configurations.all {
    resolutionStrategy {
        force("com.lagradost:cloudstream3:4.5.2") // O la versión estable que quieras usar
    }
}

dependencies {
    val cloudstream by configurations
    val implementation by configurations

    // estas dependencias pueden incluir cualquiera de las que son agregadas por la aplicación,
    // pero no necesitas incluir ninguna de ellas si no las necesitas
    // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts

    implementation(kotlin("stdlib")) // añade características estándar de kotlin, como listOf, mapOf, etc.
    implementation("com.github.Blatzar:NiceHttp:0.4.13") // biblioteca http
    implementation("org.jsoup:jsoup:1.18.3") // analizador html
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.mozilla:rhino:1.8.0") // ejecuta JS
    implementation("com.google.code.gson:gson:2.11.0")
}