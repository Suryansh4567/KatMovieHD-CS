// Bump this integer every time you push a fix.
version = 24

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Hindi dubbed & dual-audio movies / TV series from KatMovieHD. v24: exhaustive RE audit — fixed /play __data.json URL+parser, gd.kmhd.eu redirect routing, bbupload.to API extractor, gdflix.dad/gdlink.dev chain, dead categories, category listing parser, CF fallback, resolveFinalUrl GET-based."
    language    = "hi"

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=new1.katmoviehd.cymru&sz=64"
}
