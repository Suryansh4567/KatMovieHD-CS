package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Custom ExtractorApi classes — v33
 *
 * COMPLETELY REWRITTEN to match phisher98's PROVEN WORKING patterns exactly.
 * The key insight: phisher98's extractors are SIMPLE and they WORK.
 * No custom headers, no complex fallbacks, just the essential selectors.
 *
 * Chain: hubdrive.space -> hubcloud.foo -> gamerxyt.com -> FSL/BuzzServer/PixelDrain/S3
 * Alt:   mclinks.xyz -> hubdrive/hubcloud/hubcdn
 * Alt:   hblinks.dad -> hubdrive/hubcloud/hubcdn
 * Alt:   hubcdn.org/dl/?link=hub.obsession.buzz/{hash} -> CDN direct
 * Alt:   hdstream4u.com -> VidHidePro
 */

// ======================================================================
// HdStream4u — delegates to CloudStream's built-in VidHidePro
// ======================================================================

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ======================================================================
// Mclinks — extracts links from mclinks.xyz intermediary pages
// Routes to Hubdrive/HubCloud/HUBCDN based on domain
// Simplified to match phisher98's Hblinks pattern
// ======================================================================

class Mclinks : ExtractorApi() {
    override val name = "Mclinks"
    override var mainUrl = "https://mclinks.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w("Mclinks", "Failed to fetch $url: ${e.message}")
            return
        }

        // Match phisher98's Hblinks selector pattern
        doc.select("h3 a, h5 a, div.entry-content p a, div.entry-content a").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            try {
                when {
                    "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                    "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                    "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                    "obsession.buzz" in lower || "noirspy.buzz" in lower -> {
                        callback(newExtractorLink(name, "Direct [CDN]", href) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    "mclinks" in lower -> return@forEach
                    "gofile" in lower -> loadExtractor(href, "", subtitleCallback, callback)
                    "wordpress.org" in lower || "michaelvandenberg" in lower -> return@forEach
                    else -> loadExtractor(href, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.w("Mclinks", "Failed for $href: ${e.message}")
            }
        }
    }
}

// ======================================================================
// Hubdrive — EXACT copy of phisher98's proven working Hubdrive
// Simple: fetch page, find the button, route to hubcloud or loadExtractor
// ======================================================================

class Hubdrive : ExtractorApi() {
    override val name = "HubDrive"
    override var mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // phisher98 pattern: simple app.get, no custom headers
        val href = try {
            app.get(url, timeout = 5000L).document
                .selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")
                ?.attr("href")
        } catch (e: Exception) {
            Log.w("HubDrive", "Failed to fetch $url: ${e.message}")
            return
        }

        if (href.isNullOrBlank()) {
            Log.w("HubDrive", "No link found on hubdrive page: $url")
            return
        }

        Log.d("HubDrive", "HubDrive resolved: $url -> $href")

        // phisher98 pattern: if hubcloud -> HubCloud, else loadExtractor
        if (href.contains("hubcloud", ignoreCase = true)) {
            HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
        } else if (href.contains("hubcdn", ignoreCase = true)) {
            HUBCDN().getUrl(href, "HubDrive", subtitleCallback, callback)
        } else {
            loadExtractor(href, "", subtitleCallback, callback)
        }
    }
}

// ======================================================================
// HubCloud — EXACT copy of phisher98's proven working HubCloud
// No custom headers, no complex fallbacks
//
// Chain: hubcloud.foo/drive/{id} -> a#download -> gamerxyt.com -> buttons
// ======================================================================

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override var mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = referer.orEmpty().ifBlank { name }

        val uri = try { URI(url) } catch (e: Exception) {
            Log.e(tag, "Invalid URL: ${e.message}"); return
        }
        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        // Step 1: Resolve the #download href from hubcloud page
        // phisher98 pattern: no custom headers
        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl, timeout = 15000L).document
                    .selectFirst("#download")?.attr("href").orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract href: ${e.message}"); return
        }

        if (href.isBlank()) return

        Log.d(tag, "HubCloud step1: $realUrl -> $href")

        // Step 2: Fetch the download page (gamerxyt.com)
        val document = try {
            app.get(href, timeout = 15000L).document
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch download page: ${e.message}"); return
        }

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        Log.d(tag, "HubCloud step2: quality=$quality header='$header' size='$size' buttons=${document.select("a.btn").size}")

        // Step 3: Process each download button — phisher98 exact pattern
        document.select("a.btn").forEach { element ->
            val link = element.attr("href").trim()
            val text = element.ownText()
            val label = text.lowercase()

            if (link.isBlank() || !link.startsWith("http")) return@forEach

            try {
                when {
                    "fsl server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [FSL Server]",
                                "$ref [FSL Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    "download file" in label -> {
                        callback(
                            newExtractorLink(
                                ref,
                                "$ref $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    "buzzserver" in label || "buzz server" in label -> {
                        try {
                            val resp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = resp.headers["hx-redirect"]
                                ?: resp.headers["HX-Redirect"].orEmpty()

                            if (dlink.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        "$ref [BuzzServer]",
                                        "$ref [BuzzServer] $labelExtras",
                                        dlink
                                    ) { this.quality = quality }
                                )
                            } else {
                                Log.w(tag, "BuzzServer: No redirect")
                            }
                        } catch (_: Exception) {}
                    }

                    "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                        val base = getBaseUrl(link)
                        val finalUrl = if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                        callback(
                            newExtractorLink(
                                "$ref Pixeldrain",
                                "$ref Pixeldrain $labelExtras",
                                finalUrl
                            ) { this.quality = quality }
                        )
                    }

                    "s3 server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [S3 Server]",
                                "$ref [S3 Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    "fslv2" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [FSLv2]",
                                "$ref [FSLv2] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    "mega server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [Mega Server]",
                                "$ref [Mega Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    "gofile" in label -> {
                        loadExtractor(link, "", subtitleCallback, callback)
                    }

                    "hubcdn" in link.lowercase() -> {
                        HUBCDN().getUrl(link, ref, subtitleCallback, callback)
                    }

                    else -> {
                        // phisher98 pattern: just loadExtractor, no custom direct link fallback
                        loadExtractor(link, "", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to resolve button '$label': ${e.message}")
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) { "" }
    }

    private fun cleanTitle(title: String): String {
        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")

        val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP")
        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")
        val audioExtras = setOf("ATMOS")
        val hdrTags = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        return parts.mapNotNull { part ->
            val p = part.uppercase()
            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                audioExtras.contains(p) -> p
                hdrTags.contains(p) -> when (p) {
                    "DV", "DOLBYVISION" -> "DOLBYVISION"
                    else -> p
                }
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }.distinct().joinToString(" ")
    }
}

// ======================================================================
// HUBCDN — EXACT copy of phisher98's proven working HUBCDN
// Simple: fetch page, decode reurl, done. No extra strategies.
// ======================================================================

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.fans"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Strategy 1: Extract from ?link= parameter (/dl/ URLs)
        // hubcdn.org/dl/?link=https://hub.obsession.buzz/{hash}
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            Log.d("HUBCDN", "?link= -> $decoded")
            // phisher98 pattern: try to decode as base64 reurl from the decoded link
            try {
                val cdnDoc = app.get(decoded, timeout = 10000L).document.toString()
                val encoded = Regex("""r=([A-Za-z0-9+/=]+)""").find(cdnDoc)?.groups?.get(1)?.value
                if (!encoded.isNullOrEmpty()) {
                    val m3u8 = base64Decode(encoded).substringAfterLast("link=")
                    callback(
                        newExtractorLink(name, name, m3u8, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (_: Exception) {}

            // Fallback: use decoded URL directly
            callback(
                newExtractorLink(name, name, decoded, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Strategy 2: Fetch page and decode reurl from script (/file/ URLs)
        // phisher98's exact pattern
        try {
            val doc = app.get(url, timeout = 10000L).document

            // First check a#vd link (some hubcdn pages)
            val vdLink = doc.selectFirst("a#vd")?.attr("href")?.trim()
            if (!vdLink.isNullOrEmpty() && vdLink.startsWith("http")) {
                Log.d("HUBCDN", "a#vd -> $vdLink")
                callback(
                    newExtractorLink(name, name, vdLink, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // phisher98 pattern: decode reurl from script
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                ?: doc.selectFirst("script:containsData(reurl)")?.data()

            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")

            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

            if (decodedUrl != null) {
                Log.d("HUBCDN", "reurl decoded -> $decodedUrl")
                callback(
                    newExtractorLink(name, name, decodedUrl, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (e: Exception) {
            Log.w("HUBCDN", "Failed to resolve $url: ${e.message}")
        }

        Log.w("HUBCDN", "No link found for $url")
    }
}

// ======================================================================
// Hblinks — EXACT copy of phisher98's proven working Hblinks
// Routes to Hubdrive/HubCloud/HUBCDN based on domain
// ======================================================================

class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override var mainUrl = "https://hblinks.dad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // phisher98 pattern: simple app.get, no headers
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.w("Hblinks", "Failed to fetch $url: ${e.message}")
            return
        }

        doc.select("h3 a, h5 a, div.entry-content p a, div.entry-content a").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            try {
                when {
                    "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                    "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                    "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                    "obsession.buzz" in lower || "noirspy.buzz" in lower -> {
                        callback(newExtractorLink(name, "Direct [CDN]", href) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    "gofile" in lower -> loadExtractor(href, "", subtitleCallback, callback)
                    "wordpress.org" in lower || "michaelvandenberg" in lower -> return@forEach
                    else -> loadExtractor(href, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.w("Hblinks", "Failed for $href: ${e.message}")
            }
        }
    }
}
