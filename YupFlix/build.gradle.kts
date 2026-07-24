version = 1

cloudstream {
    authors = listOf("arena")
    status = 1
    description = "Stream movies & TV series from YupFlix (watch.yupflix.org). Direct HLS streams via the public JSON API."
    language = "en"
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=watch.yupflix.org&sz=64"
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // The cloudstream3 "thin" API jar is what the unit-test JVM needs for the
    // CS3 base types (MainAPI, ExtractorLink, SearchResponse, …). It does NOT
    // bundle its own runtime dependencies, so we add the same set TheNextPlanet
    // uses: Jackson (model parsing), kotlinx-coroutines and kotlinx-serialization
    // (CS3 runtime initialisers), plus the Android stub jar so android.util.Log
    // (used by YupFlixLog) resolves under the JVM test runner.
    val cloudstream3Jar = file("/home/user/.gradle/caches/cloudstream/cloudstream/cloudstream.jar")
    if (cloudstream3Jar.exists()) {
        testImplementation(files(cloudstream3Jar))
    }

    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.13.1")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.13.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")

    val androidJar = file("/home/user/android-sdk/platforms/android-36/android.jar")
    if (androidJar.exists()) {
        testImplementation(files(androidJar))
    }

    // FIX D1: `newExtractorLink()` triggers ExtractorApiKt's class initialiser,
    // which references dev.whyoleg.cryptography.CryptographyProvider — a CS3
    // runtime transitive that the thin cloudstream3 test jar does NOT bundle.
    // TheNextPlanet never calls newExtractorLink from its unit tests, so it
    // never hit this; YupFlix's tests do. The exact coordinates/version are
    // taken from CloudStream 3's own version catalog (pre-release tag,
    // libs.versions.toml: cryptography = "0.6.0", with cryptography-core +
    // cryptography-provider-optimal). Pinned here so the unit-test JVM can load
    // ExtractorApiKt without a NoClassDefFoundError.
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
}
