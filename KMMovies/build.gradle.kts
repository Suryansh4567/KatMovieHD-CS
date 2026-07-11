// Bump this integer whenever the provider implementation changes.
version = 9

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1
    description = "Bollywood, Hollywood and South Indian movies / TV series from KMMovies."
    language = "hi"
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=kmmovies.shop&sz=64"
}
