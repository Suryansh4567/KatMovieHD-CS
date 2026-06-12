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
 * IMPORTANT ARCHITECTURE NOTE:
 * The link generator page (links.olamovies.mov) is a Next.js RSC app
 * that is Cloudflare-protected. The page flow is:
 *   1. CF challenge (WebViewResolver handles on Android)
 *   2. Countdown timer (~10 seconds)
 *   3. "Visit Now" button → shortener URL (madurird.com/...)
 *   4. Shortener → ad pages → eventual redirect to GDrive/Mega
 *
 * Since the actual download URLs are NOT in the HTML (they're behind
 * the shortener), this extractor:
 *   - Uses WebViewResolver (via loadExtractor) which handles CF on Android
 *   - Scans the page for any directly embedded URLs (GDrive, Mega, MKV)
 *   - If the page has a "Visit Now" button, tries to follow the shortener
 *   - Falls back to scanning the final redirected URL
 *
 * On Android, CloudStream's WebViewResolver automatically handles CF
 * challenges, so users can access the link generator transparently.
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

        /** Known video file extensions for URL matching */
        private val VIDEO_EXT_REGEX = Regex("""\.(?:mkv|mp4|avi|ts|m4v)(?:\?|$)""", RegexOption.IGNORE_CASE)

        /** R2 tutorial bucket — must be filtered out */
        private const val R2_TUTORIAL_BUCKET = "pub-bef22e7488a04766bdf11e3bc7498ba4.r2.dev"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // First, try direct fetch (may fail if CF-protected)
            val doc = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 15).document
            extractFromPage(doc, url, callback, subtitleCallback)
        } catch (e: Exception) {
            Log.d(TAG, "Direct fetch failed for $url (likely CF-protected): ${e.message}")
            // On Android, WebViewResolver will handle CF. We rely on loadExtractor
            // fallback chain from the provider's resolveLinkId() method.
        }
    }

    /**
     * Extract video URLs from a link generator page document.
     */
    private suspend fun extractFromPage(
        doc: Document,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
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
                        this.referer = pageUrl
                    }
                )
            }

        // --- Strategy 2: Direct video file URLs (.mkv, .mp4) ---
        // FILTER OUT the R2 tutorial bucket
        Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4|avi|ts|m4v)""")
            .findAll(pageText).forEach { match ->
                val vUrl = match.value
                if (!vUrl.contains(R2_TUTORIAL_BUCKET) &&
                    !vUrl.contains(".js") && !vUrl.contains(".css") &&
                    !vUrl.contains(".json")) {
                    val qHint = extractQualityFromUrl(vUrl)
                    callback.invoke(
                        newExtractorLink(
                            "$name Direct",
                            "$name $qHint",
                            vUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qHint)
                            this.referer = pageUrl
                        }
                    )
                }
            }

        // --- Strategy 3: Mega URLs ---
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
                        this.referer = pageUrl
                    }
                )
            }

        // --- Strategy 4: Try to find "Visit Now" / shortener links ---
        // The link generator has a button that redirects to the shortener.
        // We look for href attributes containing known shortener domains.
        val shortenerPatterns = listOf(
            "madurird.com",
            "olamovies.click",
            "clk.asia",
            "ouo.io",
            "linkshrink.net",
            "adbull.me",
            "adshort.pro",
        )

        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.startsWith("http")) {
                val isShortener = shortenerPatterns.any { href.contains(it) }
                val isKnownHoster = href.contains("hubcloud") || href.contains("gdflix") ||
                    href.contains("katdrive") || href.contains("hglink")

                if (isShortener || isKnownHoster) {
                    try {
                        loadExtractor(href, pageUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not dispatch shortener/hoster: $href — ${e.message}")
                    }
                }
            }
        }

        // --- Strategy 5: Other known hoster URLs ---
        doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.startsWith("http") &&
                !href.contains("ol-am.top") &&
                !href.contains("olamovies.mov") &&
                !href.contains("r2.dev") &&
                VIDEO_EXT_REGEX.containsMatchIn(href)) {
                href
            } else null
        }.distinct().forEach { hostUrl ->
            try {
                loadExtractor(hostUrl, pageUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d(TAG, "Could not dispatch to extractor: $hostUrl — ${e.message}")
            }
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
 *   - links.ol-am.top (current, redirects 301 → links.olamovies.mov)
 *   - links.olamovies.mov (canonical, CF-protected Next.js app)
 * Both resolve to the same OMDrive backend.
 *
 * CloudStream's loadExtractor() matches by URL prefix,
 * so we need both domain variants registered.
 */
class OMDriveExtractorAlt : OMDriveExtractor() {
    override val mainUrl = "https://links.olamovies.mov"
}
