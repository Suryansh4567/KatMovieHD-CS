// Bump this integer every time you push a fix.
version = 9

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Hindi dubbed & dual-audio movies / TV series from KatMovieHD (TMDB-enriched)"
    language    = "hi"

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")

    iconUrl = "https://www.google.com/s2/favicons?domain=new1.katmoviehd.cymru&sz=64"
}
