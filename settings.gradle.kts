// settings.gradle.kts
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google() // Asegúrate de que esté aquí (ya lo tienes, ¡bien!)
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google() // <-- ¡AÑADE ESTA LÍNEA! Es crucial para dependencias como desugar_jdk_libs
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "TestPlugins"

// Resto del código de auto-inclusión (no necesita cambios para este problema):
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}