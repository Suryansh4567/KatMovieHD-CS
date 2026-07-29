package com.olamovies.cloudstream

object OlaMoviesConstants {
    const val BASE_URL = "https://v3.olamovies.mov"
    const val TAG = "OlaMovies"

    val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // Selectors
    const val SELECTOR_CARD = ".entry-image"
    const val SELECTOR_CARD_LINK = "a"
    const val SELECTOR_CARD_TITLE = ".entry-title h3 a, .entry-title a"
    const val SELECTOR_CARD_IMG = "img.wp-post-image"
    const val SELECTOR_CARD_CATEGORY = ".entry-category a"

    const val SELECTOR_DETAIL_TITLE = "h1.entry-title, .entry-title"
    const val SELECTOR_DETAIL_POSTER = "img.wp-post-image, .attachment-gridlove-full, meta[property=\"og:image\"]"
    const val SELECTOR_DETAIL_CONTENT = ".entry-content, .post-content"

    // Regex
    val QUALITY_REGEX = Regex("""(\d{3,4}p).*?(\d+(?:\.\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
    val SEASON_EP_REGEX = Regex("""S(\d{1,2}).*?E?(\d{1,2})?|Season\s*(\d+)""", RegexOption.IGNORE_CASE)
}