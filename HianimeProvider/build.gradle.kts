import org.jetbrains.kotlin.konan.properties.Properties

version = 27

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val properties = Properties()
        val propertiesFile = project.rootProject.file("local.properties")

        val wasmApiKey = if (propertiesFile.exists()) {
            properties.load(propertiesFile.inputStream())
            properties.getProperty("WASMAPI") ?: ""
        } else {
            System.getenv("WASMAPI") ?: ""
        }

        buildConfigField("String", "WASMAPI", "\"$wasmApiKey\"")
    }
}

dependencies {
    implementation("com.google.firebase:firebase-crashlytics-buildtools:3.0.6")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
    authors = listOf("Stormunblessed", "KillerDogeEmpire", "RowdyRushya", "Phisher98")

    status = 1
    tvTypes = listOf(
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=hianime.to&sz=%size%"
    requiresResources = true
    isCrossPlatform = false
}