package com.olamovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

// ═══════════════════════════════════════════════════
//  OMDrive Extractor — links.ol-am.top/{id}
// ═══════════════════════════════════════════════════

/**
 * Handles links.ol-am.top/{id} and links.olamovies.mov/{id} URLs.
 *
 * OMDrive is OlaMovies' link generator. Each ID maps to a page
 * containing download links (Google Drive, Mega, direct MKV/MP4).
 *
 * On Android, CloudStream's WebViewResolver handles CF challenges
 * automatically when fetching these pages.
 */
open class OMDriveExtractor : ExtractorApi() {
    override val name = "OMDrive"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "OMDrive"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p|720p|480p|UHD|FHD|HD|SD|Remux|BluRay|WEB-DL|DV|Dolby\s*Vision)"""
        )

        private val PLAY_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "video/*,application/octet-stream,*/*;q=0.9",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
        )

        /** Known video file extensions for URL matching */
        private val VIDEO_EXT_REGEX = Regex("""\.(?:mkv|mp4|avi|ts|m4v)(?:\?|$)""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 15).document
            val pageText = doc.html()

            // --- Strategy 1: Google Drive URLs ---
            Regex("""https?://(?:drive\.google\.com|drive\.usercontent\.google\.com|video-downloads\.googleusercontent\.com)/[^\s"'<>]+""")
                .findAll(pageText).forEach { match ->
                    val gUrl = match.value.replace("\\u0026", "&")
                    val qHint = extractQualityFromContext(pageText, match.value) ?: "HD"
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name GDrive $qHint",
                            gUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qHint)
                            this.referer = url
                        }
                    )
                }

            // --- Strategy 2: play.ol-am.top direct stream URLs ---
            doc.select("a[href*=play.ol-am.top], video source[src], a[href*=play.olamovies]")
                .forEach { el ->
                    val src = el.attr("abs:href")
                        .ifBlank { el.attr("abs:src").ifBlank { el.attr("src") } }
                    if (src.isNotBlank() && src.startsWith("http")) {
                        val qHint = extractQualityFromUrl(src)
                        callback.invoke(
                            newExtractorLink(
                                "$name Stream",
                                "$name $qHint",
                                src,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName(qHint)
                                this.referer = "https://v2.olamovies.mov"
                                this.headers = PLAY_HEADERS
                            }
                        )
                    }
                }

            // --- Strategy 3: Direct video file URLs (.mkv, .mp4) ---
            Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4|avi|ts|m4v)""")
                .findAll(pageText).forEach { match ->
                    val vUrl = match.value
                    if (!vUrl.contains(".js") && !vUrl.contains(".css") && !vUrl.contains(".json")) {
                        val qHint = extractQualityFromUrl(vUrl)
                        callback.invoke(
                            newExtractorLink(
                                "$name Direct",
                                "$name $qHint",
                                vUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName(qHint)
                                this.referer = url
                            }
                        )
                    }
                }

            // --- Strategy 4: Mega URLs ---
            Regex("""https?://mega\.(?:nz|co\.nz)/[^\s"'<>]+""")
                .findAll(pageText).forEach { match ->
                    callback.invoke(
                        newExtractorLink(
                            "$name Mega",
                            "$name Mega",
                            match.value,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                }

            // --- Strategy 5: Other known hoster URLs ---
            doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.startsWith("http") && !href.contains("ol-am.top") &&
                    !href.contains("olamovies.mov") && VIDEO_EXT_REGEX.containsMatchIn(href)) {
                    href
                } else null
            }.distinct().forEach { hostUrl ->
                try {
                    loadExtractor(hostUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.d(TAG, "Could not dispatch to extractor: $hostUrl — ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed for $url: ${e.message}")
        }
    }

    private fun extractQualityFromContext(pageText: String, url: String): String? {
        val contextBefore = pageText.substringBefore(url).takeLast(300)
        return QUALITY_REGEX.find(contextBefore)?.groupValues?.get(1)
    }

    private fun extractQualityFromUrl(url: String): String {
        return QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "HD"
    }

    private fun getQualityFromName(name: String?): Int {
        if (name.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            name.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            name.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            name.contains("UHD", ignoreCase = true) -> Qualities.P2160.value
            name.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            name.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
            name.contains("Remux", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720p", ignoreCase = true) -> Qualities.P720.value
            name.contains("HD", ignoreCase = true) -> Qualities.P720.value
            name.contains("480p", ignoreCase = true) -> Qualities.P480.value
            name.contains("SD", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}

// ═══════════════════════════════════════════════════
//  OMDrive Alt — links.olamovies.mov domain variant
// ═══════════════════════════════════════════════════

/**
 * Alternate domain variant for OMDrive.
 * OlaMovies link generator URLs can appear under either:
 *   - links.ol-am.top (current)
 *   - links.olamovies.mov (legacy/alternate)
 * Both resolve to the same OMDrive backend.
 *
 * CloudStream's loadExtractor() matches by URL prefix,
 * so we need both domain variants registered.
 */
class OMDriveExtractorAlt : OMDriveExtractor() {
    override val mainUrl = "https://links.olamovies.mov"
}

// ═══════════════════════════════════════════════════
//  OlaPlay Extractor — play.ol-am.top direct MKV
// ═══════════════════════════════════════════════════

/**
 * Handles play.ol-am.top direct streaming URLs.
 *
 * play.ol-am.top serves MKV/MP4 files directly with:
 *   - HTTP 200 OK (no CF protection)
 *   - Range header support for seeking
 *   - Direct video streaming without any intermediate page
 *
 * URLs typically look like:
 *   play.ol-am.top/path/to/movie.720p.BluRay.mkv
 *   play.ol-am.top/path/to/movie.1080p.WEB-DL.mkv
 */
class OlaPlayExtractor : ExtractorApi() {
    override val name = "OlaPlay"
    override val mainUrl = "https://play.ol-am.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "OlaPlay"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p|720p|480p|UHD|FHD|HD|SD|Remux|BluRay|WEB-DL|DV|Dolby\s*Vision|60fps)"""
        )

        private val PLAY_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "video/*,application/octet-stream,*/*;q=0.9",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qHint = extractQualityFromUrl(url)
            val fileName = url.substringAfterLast("/").substringBefore("?")

            callback.invoke(
                newExtractorLink(
                    name,
                    "$name $qHint — $fileName",
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromName(qHint)
                    this.referer = "https://v2.olamovies.mov"
                    this.headers = PLAY_HEADERS
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed for $url: ${e.message}")
        }
    }

    private fun extractQualityFromUrl(url: String): String {
        return QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "HD"
    }

    private fun getQualityFromName(name: String?): Int {
        if (name.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            name.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            name.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            name.contains("UHD", ignoreCase = true) -> Qualities.P2160.value
            name.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            name.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
            name.contains("Remux", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720p", ignoreCase = true) -> Qualities.P720.value
            name.contains("HD", ignoreCase = true) -> Qualities.P720.value
            name.contains("480p", ignoreCase = true) -> Qualities.P480.value
            name.contains("SD", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
