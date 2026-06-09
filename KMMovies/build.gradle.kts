// Bump this integer every time you push a fix.
version = 5

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Movies & TV series from KMMovies (kmmovies.lol). Dual-audio, Hindi dubbed, 4K UHD/HDR/DV, multi-season episode-wise support, TMDB metadata, dynamic domain."
    language    = "hi"

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=kmmovies.lol&sz=64"
}
