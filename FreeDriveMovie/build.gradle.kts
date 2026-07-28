// Bump this integer on every published fix.
version = 10

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description =
        "Stream & download movies and TV series (Hindi / English / South Indian) " +
        "from FreeDriveMovie. Movies + multi-season TV with episodes."

    language = "en"

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://freedrivemovie.cyou/wp-content/uploads/2020/10/cropped-PicsArt_10-11-07.20.53-192x192.jpg"
}
