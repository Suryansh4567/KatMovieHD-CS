// Bump this integer every time you push a fix.
version = 8

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Rare Toon India: Hindi cartoons, anime, movies and series from raretoonindia.in with ByseKozE playback. v8 rewrites provider logic around real page types: movie pages, season pages, and collection pages. Improves archive parsing, collection handling, and multi-host link extraction."
    language = "hi"

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=raretoonindia.in&sz=64"
}
