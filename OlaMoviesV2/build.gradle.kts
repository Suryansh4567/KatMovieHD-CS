// Bump this integer every time you push a fix.
version = 16

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "4K UHD / 2160p HDR / Dolby Vision / REMUX movies & TV series from " +
            "OlaMovies v2 (v2.olamovies.mov). Google Drive & multi-mirror sources. " +
            "v16: CF fix (loadExtractor for short URLs), intermediate site two-step bypass " +
            "(form[name='tp'] + #btn6 with delays based on Greasy Fork patterns), " +
            "bypassIntermediateSite() for ukrupdate/mastkhabre/superheromaniac/spatsify."
    language    = "en"

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Documentary")

    iconUrl = "https://www.google.com/s2/favicons?domain=v2.olamovies.mov&sz=64"
}
