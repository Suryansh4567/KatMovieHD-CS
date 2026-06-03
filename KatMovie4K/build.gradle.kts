// Bump this integer every time you push a fix.
// Sister plugin to KatMovieHD â€” 4K UHD focused (Dolby Vision / HDR10+).
version = 10

cloudstream {
    authors = listOf("arena")

    /**
     * Status int:
     *   0 = Down, 1 = Ok, 2 = Slow, 3 = Beta only
     */
    status = 1

    description = "4K Ultra HD / 2160p UHD movies & TV series from KatMovie4K " +
            "(Dolby Vision, HDR10+, SDR). Sister site of KatMovieHD focused on " +
            "the highest-quality releases. Multi-mirror dispatch via ziddiflix, " +
            "gdflix, hubcloud, driveleech, kmhd. TMDB + Cinemeta metadata, " +
            "dynamic domain via shared domains.json."
    language    = "en"

    // 4K content is mostly English-original with Hindi-dub variants;
    // most pages are full movies, with a few TV series sections.
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=katmovie4k.mov&sz=64"
}
