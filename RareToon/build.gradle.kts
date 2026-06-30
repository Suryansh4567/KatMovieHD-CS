// Bump this integer every time you push a fix.
version = 10

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Rare Toon India: Hindi cartoons, anime, movies and series from raretoonindia.in with ByseKozE playback. v10 tightens collection detection so season/movie pages stop being misclassified as generic internal-page lists."
    language = "hi"

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=raretoonindia.in&sz=64"
}
