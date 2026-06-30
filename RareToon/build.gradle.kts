// Bump this integer every time you push a fix.
version = 14

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Rare Toon India: Hindi cartoons, anime, movies and series from raretoonindia.in with ByseKozE playback. v14 avoids BAD_HTTP_DATA_SOURCE for direct MEGA and MediaFire movie links by exposing them as direct links instead of forcing extractor resolution."
    language = "hi"

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=raretoonindia.in&sz=64"
}
