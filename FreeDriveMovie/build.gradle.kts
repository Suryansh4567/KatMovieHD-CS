// Bump this integer on every published fix.
version = 2

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description =
        "FreeDriveMovie (freedrivemovie.cyou) — stream & download movies and TV series " +
        "(Hindi / English / South Indian). Resolves the Dooplay download-link chain " +
        "(shortlink -> dl.freedrivemovie.org player page) to direct-playable MKV/MP4 " +
        "mirrors (Cloudflare-Worker GDToT). Movies + multi-season TV with episodes."

    language = "en"

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=freedrivemovie.cyou&sz=64"
}
