// Bump this integer every time you push a fix.
version = 28

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Hindi dubbed & dual-audio movies / TV series from KatMovieHD. v28: fixes PikaHD/KatDrama card URLs, iframe watch links, and single-link series detection."
    language    = "hi"

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=new1.katmoviehd.cymru&sz=64"
}
