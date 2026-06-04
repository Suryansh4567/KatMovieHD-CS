// Bump this integer every time you push a fix.
version = 8

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "4K UHD / 2160p HDR / Dolby Vision / REMUX movies & TV series from " +
            "OlaMovies v2 (v2.olamovies.mov). Google Drive & multi-mirror sources. " +
            "v8: Multi-Strategy Aggressive — 5 strategies (A-E) with retries, " +
            "aggressive scraping, improved bypassOlaRedirect + bypassAdLinks."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
