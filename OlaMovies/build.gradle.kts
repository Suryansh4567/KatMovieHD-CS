// Bump this integer every time you push a fix.
version = 1

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 3  // Beta — CF on main site, relies on WebViewResolver

    description = "4K UHD / HDR / Dolby Vision movies & TV series from OlaMovies v2 (olamovies.mov). Dual-audio, multi-quality links, play.ol-am.top direct streaming, dynamic domain."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Documentary", "AsianDrama")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
