// Bump this integer every time you push a fix.
version = 26

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "Hindi dubbed & dual-audio movies / TV series from KatMovieHD. v26: Fixed K-Drama, Anime, and Bollywood by pointing to active sub-domains (moviesbaba, katdrama, pikahd), fixed getMainPage CF bypass, and improved search result parsing."
    language    = "hi"

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime", "AnimeTv")

    iconUrl = "https://www.google.com/s2/favicons?domain=new1.katmoviehd.cymru&sz=64"
}
