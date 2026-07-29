package com.olamovies.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Simple Google Drive resolver for OlaMovies
 * Delegates to CloudStream's native extractors when possible.
 */
class OlaMoviesGoogleDriveExtractor : ExtractorApi() {
    override val name = "OlaMovies GDrive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // If the URL is already a GDrive file link, let CloudStream handle it
        // Otherwise try to resolve the canonical file URL
        val cleanUrl = if (url.contains("drive.google.com/file/d/")) {
            url
        } else {
            // Attempt to extract file ID
            val idMatch = Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)
                ?: Regex("""/d/([a-zA-Z0-9_-]+)""").find(url)
            if (idMatch != null) {
                "https://drive.google.com/file/d/${idMatch.groupValues[1]}/view"
            } else url
        }

        // Emit the link — CloudStream will resolve it using its internal GDrive extractor
        callback(
            newExtractorLink(
                source = name,
                name = "Google Drive",
                url = cleanUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer ?: OlaMoviesConstants.BASE_URL
                this.quality = Qualities.Unknown.value
            }
        )
    }
}