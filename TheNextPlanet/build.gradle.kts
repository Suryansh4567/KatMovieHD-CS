version = 5

cloudstream {
    authors = listOf("arena")
    status = 1
    description = "Stream movies, TV shows & anime from TheNextPlanet. v5: domain moved to thenextplanet-official.site (.space retired); site verified 2026-08-23."
    language = "en"
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=thenextplanet-official.site&sz=64"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Unit tests need cloudstream3 on the classpath because TheNextPlanet
    // extends MainAPI and references ExtractorLink / Qualities in its
    // companion-object methods. The `com.lagradost:cloudstream3:pre-release`
    // coordinate is a special virtual one only resolvable through the
    // `cloudstream` plugin configuration; we add the cached JAR directly
    // so the unit test JVM has MainAPI etc. on its classpath.
    val cloudstream3Jar = file("/home/user/.gradle/caches/cloudstream/cloudstream/cloudstream.jar")
    if (cloudstream3Jar.exists()) {
        testImplementation(files(cloudstream3Jar))
    }
}
