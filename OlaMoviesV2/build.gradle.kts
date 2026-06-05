// Bump this integer every time you push a fix.
version = 22

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "4K UHD / 2160p HDR / Dolby Vision / REMUX movies & TV series from " +
            "OlaMovies v2 (v2.olamovies.mov). Google Drive & multi-mirror sources. " +
            "v22: CF bypass fix — CloudStream's CloudflareKiller is dead code (never registered). " +
            "Extension now has its own CfBypass utility using WebViewResolver with correct params. " +
            "Main site + link shortener both CF-protected, both handled via WebView popup + cookie caching."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
