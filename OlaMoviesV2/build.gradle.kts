// Bump this integer every time you push a fix.
version = 19

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "4K UHD / 2160p HDR / Dolby Vision / REMUX movies & TV series from " +
            "OlaMovies v2 (v2.olamovies.mov). Google Drive & multi-mirror sources. " +
            "v19: 3-TIER CF bypass — Tier 1: regex/base64 extraction, Tier 2: app.get() chain, " +
            "Tier 3: OlaLinksMov CF WebView bypass. Clean rewrite, simplified flow."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
