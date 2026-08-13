// use an integer for version numbers
version = 2

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    description = "Sincroniza favoritos, progreso, historial, repos y ajustes entre tus dispositivos (via GitHub, sin servidor)"
    authors = listOf("beta")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "es"
}
