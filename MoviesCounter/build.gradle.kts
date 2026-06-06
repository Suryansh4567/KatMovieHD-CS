version = 30

cloudstream {
    language = "hi"
    description = "MoviesCounter — Bollywood, Hollywood, South Hindi Dubbed Movies, Web Series. v30: CRITICAL FIX — use List<String> generic overload for movie/episode data (fixes JSON double-serialization), fix HUBCDN mainUrl to hubcdn.sbs (was hubcdn.org), fix obsession.buzz as direct video file (not HTML), add Mclinks extractor, fix BuzzServer label check, proper PACK filtering"
    authors = listOf("Suryansh4567")
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime"
    )

    iconUrl = "https://moviescounter.boston/wp-content/themes/Moviescounter/assets/favicon.ico"

    isCrossPlatform = true
}
