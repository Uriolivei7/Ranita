// use an integer for version numbers
version = 1

cloudstream {
    description = "Clown IPTV â€” canales en vivo del mundo (M3U pÃºblico)"
    language = "en"
    authors = listOf("Ranita")
    status = 1
    tvTypes = listOf("Live")
    isCrossPlatform = false
}


// AppUtils.parseJson/toJson son inline JVM 11; el root fuerza 1.8. Alinear ambos a 11.
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
