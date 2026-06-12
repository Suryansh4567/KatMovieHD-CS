// Bump this integer every time you push a fix.
version = 2

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 2  // Slow — CF on main site + link generator, WebViewResolver needed

    description = "4K UHD / HDR / Dolby Vision movies & TV series from OlaMovies v2 (olamovies.mov). wp-block-button link extraction, DOM-order series episode builder, OMDrive WebViewResolver, dynamic domain."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Documentary", "AsianDrama")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
