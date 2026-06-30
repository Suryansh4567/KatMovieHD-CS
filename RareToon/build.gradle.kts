// Bump this integer every time you push a fix.
version = 12

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Rare Toon India: Hindi cartoons, anime, movies and series from raretoonindia.in with ByseKozE playback. v12 fixes runtime no-links behavior by only returning success when extractors actually emit links."
    language = "hi"

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=raretoonindia.in&sz=64"
}
