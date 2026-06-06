version = 22

cloudstream {
    language = "hi"
    description = "MoviesCounter — Bollywood, Hollywood, South Hindi Dubbed Movies, Web Series, K-Drama. v22: CRASH FIX — fixUrl corrupting pipe-delimited episode data (now uses fix=false), episode=-1 changed to null (was showing literal -1 in UI), dynamic domain fallback regex, Drive/Instant link label distinction"
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
