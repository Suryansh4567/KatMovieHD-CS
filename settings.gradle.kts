rootProject.name = "CloudstreamPlugins"

// This repository only publishes the two extensions owned by this project.
// Keeping an allow-list prevents unrelated provider folders from being
// accidentally included in CI or the generated plugins.json.
val enabled = setOf("KatMovieHD", "KMMovies")

File(rootDir, ".").eachDir { dir ->
    if (enabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
