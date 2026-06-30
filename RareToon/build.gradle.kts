// Bump this integer every time you push a fix.
version = 6

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Rare Toon India: Hindi cartoons, anime, movies and series from raretoonindia.in with ByseKozE playback. v6 fixes About-page misrouting, improves content-only listing parsing, treats collection posts as series and reduces repeated homepage/category loops."
    language = "hi"

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=raretoonindia.in&sz=64"
}
