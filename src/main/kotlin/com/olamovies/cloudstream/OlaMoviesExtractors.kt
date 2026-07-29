package com.olamovies.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Hardened Google Drive + OlaMovies link resolver.
 * Supports:
 * - drive.google.com/file/d/ID/view
 * - drive.google.com/uc?id=ID
 * - links.ol-am.top short links (intermediate)
 * - Large file confirmation page bypass
 */
class OlaMoviesGoogleDriveExtractor : ExtractorApi() {
    override val name = "OlaMovies GDrive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = resolveToDirectGDrive(url) ?: url

        // Try CloudStream built-in first (best)
        try {
            val success = loadExtractor(
                url = cleanUrl,
                referer = referer ?: OlaMoviesConstants.BASE_URL,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            if (success) return
        } catch (_: Exception) {}

        // Fallback: emit link
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

    private suspend fun resolveToDirectGDrive(inputUrl: String): String? = withContext(Dispatchers.IO) {
        var current = inputUrl

        // Handle links.ol-am.top intermediate redirect
        if (current.contains("links.ol-am.top")) {
            try {
                val resp = Jsoup.connect(current)
                    .userAgent(OlaMoviesConstants.USER_AGENT)
                    .followRedirects(true)
                    .timeout(15000)
                    .execute()
                current = resp.url().toString()
            } catch (e: Exception) {
                return@withContext null
            }
        }

        if (!current.contains("drive.google.com")) return@withContext null

        // Extract file ID
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)""").find(current)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(current)?.groupValues?.get(1)
            ?: return@withContext null

        // Try direct view URL
        val viewUrl = "https://drive.google.com/file/d/$fileId/view"

        // Attempt to bypass large file confirmation (best effort)
        try {
            val doc = Jsoup.connect(viewUrl)
                .userAgent(OlaMoviesConstants.USER_AGENT)
                .get()

            // Look for direct download link with confirm token
            val confirmLink = doc.select("a[href*='confirm=']").firstOrNull()?.attr("href")
            if (confirmLink != null) {
                return@withContext "https://drive.google.com$confirmLink"
            }

            // Fallback to uc export
            return@withContext "https://drive.google.com/uc?id=$fileId&export=download"
        } catch (e: Exception) {
            return@withContext "https://drive.google.com/uc?id=$fileId&export=download"
        }
    }
}

/** Fallback for other hosts that OlaMovies sometimes uses */
class OlaMoviesGenericExtractor : ExtractorApi() {
    override val name = "OlaMovies"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val success = loadExtractor(
                url = url,
                referer = referer ?: OlaMoviesConstants.BASE_URL,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            if (success) return
        } catch (_: Exception) {}

        // Emit as direct video if it looks like a stream
        if (url.contains(".mp4") || url.contains(".mkv") || url.contains("stream")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Direct Link",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: OlaMoviesConstants.BASE_URL
                }
            )
        }
    }
}