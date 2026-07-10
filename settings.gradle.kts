rootProject.name = "CloudstreamPlugins"

// These two legacy providers contain incomplete upstream source snapshots
// and cannot compile with the current toolchain. Keep them out of the
// generated plugin set until their upstream sources are repaired.
val disabled = listOf(
    "KatMovieHD-CS",
    "M3UPlaylistPlayerProvider",
    "LivXowProvider"
)

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
