package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Custom ExtractorApi classes for the Hubdrive -> HubCloud -> Server chain.
 * Based on v26 proven working extractors + phisher98 patterns.
 *
 * Chain: hubdrive.space -> hubcloud.foo -> gamerxyt.com -> FSL/BuzzServer/PixelDrain/S3
 * Alt:   mclinks.xyz -> hubdrive/hubcloud/hubcdn
 * Alt:   hblinks.dad -> hubdrive/hubcloud/hubcdn
 * Alt:   hubcdn.sbs/dl/?link=obsession.buzz -> CDN direct
 * Alt:   hubcdn.sbs/file/{id} -> base64 reurl decode -> CDN
 * Alt:   hdstream4u.com -> VidHidePro
 *
 * v31: Restored from v26 working code, added Mclinks extractor.
 * Uses 3-arg newExtractorLink (proven working in v26).
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

        doc.select("h3 a, h5 a, div.entry-content p a, div.entry-content a").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            try {
                when {
                    "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                    "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                    "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                    "obsession.buzz" in lower -> {
                        // Direct CDN link
                        callback(newExtractorLink(name, "Direct [CDN]", href) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    "mclinks" in lower -> {
                        // Skip self-referential links
                        return@forEach
                    }
                    lower.contains("wordpress.org") || lower.contains("michaelvandenberg") -> return@forEach
                    else -> loadExtractor(href, name, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.w("Mclinks", "Failed for $href: ${e.message}")
            }
        }
    }
}

// ======================================================================
// Hubdrive — resolves hubdrive.space link -> hubcloud/hubcdn/direct
// v26 proven working pattern
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
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.w("HubDrive", "Failed to fetch $url: ${e.message}")
            return
        }

        // v26 proven selectors — priority order
        val href = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcdn]")?.attr("href")
            ?: doc.selectFirst("a[href].btn")?.attr("href")
            ?: doc.selectFirst("a[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a[href*=hubcdn]")?.attr("href")
            // Broadest fallback: any external link that's not the page itself
            ?: doc.select("a[href]").firstOrNull { a ->
                val h = a.attr("href").trim()
                h.startsWith("http") && !h.contains("hubdrive.space", true)
            }?.attr("href")

        if (href.isNullOrBlank()) {
            Log.w("HubDrive", "No link found on hubdrive page: $url")
            return
        }

        val fullHref = resolveRelativeUrl(url, href)
        Log.d("HubDrive", "HubDrive resolved: $url -> $fullHref")

        when {
            fullHref.contains("hubcloud", ignoreCase = true) ->
                HubCloud().getUrl(fullHref, name, subtitleCallback, callback)
            fullHref.contains("hubcdn", ignoreCase = true) ->
                HUBCDN().getUrl(fullHref, name, subtitleCallback, callback)
            else -> loadExtractor(fullHref, name, subtitleCallback, callback)
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, href: String): String {
        if (href.startsWith("http")) return href
        val base = try { URI(baseUrl) } catch (_: Exception) { return href }
        return "${base.scheme}://${base.host}${if (href.startsWith("/")) href else "/$href"}"
    }
}

// ======================================================================
// HubCloud — main download resolver
// v26 proven working pattern — 3-arg newExtractorLink
//
// Chain: hubcloud.foo/drive/{id} -> a#download -> gamerxyt.com -> buttons
//
// gamerxyt.com page has buttons like:
//   "Download [FSL Server]"     -> obsession.buzz CDN
//   "Download [FSLv2 Server]"  -> cdn.fukggl.buzz
//   "Download [Buzz Server]"   -> bzzhr.co
//   "Download [PixelServer]"   -> pixel.hubcloud.cx
//   "Download [Server : 10Gbps]" -> pixel.hubcloud.cx
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

        // Step 3: Process each download button
        document.select("a.btn").forEach { element ->
            val link = element.attr("href").trim()
            val text = element.ownText()
            val label = text.lowercase()

            if (link.isBlank() || !link.startsWith("http")) return@forEach

            try {
                when {
                    "fsl server" in label || "fsl server" in link.lowercase() -> {
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
                                // If no hx-redirect, try the link directly
                                callback(
                                    newExtractorLink(
                                        "$ref [BuzzServer]",
                                        "$ref [BuzzServer] $labelExtras",
                                        link
                                    ) { this.quality = quality }
                                )
                            }
                        } catch (_: Exception) {
                            callback(
                                newExtractorLink(
                                    "$ref [BuzzServer]",
                                    "$ref [BuzzServer] $labelExtras",
                                    link
                                ) { this.quality = quality }
                            )
                        }
                    }

                    "pixeldra" in label || "pixelserver" in label || "pixel server" in label -> {
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
                        loadExtractor(link, ref, subtitleCallback, callback)
                    }

                    "hubcdn" in link.lowercase() -> {
                        HUBCDN().getUrl(link, ref, subtitleCallback, callback)
                    }

                    else -> {
                        if (!loadExtractor(link, ref, subtitleCallback, callback)) {
                            callback(
                                newExtractorLink(
                                    ref,
                                    "$ref $labelExtras",
                                    link
                                ) { this.quality = quality }
                            )
                        }
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

        val parts = normalized.split(" ", "_", ".")
        val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "HDTV", "BRRIP")
        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AV1", "10BIT")
        val audioTags = setOf("AAC", "AC3", "DTS", "DD", "DDP", "EAC3", "ATMOS", "DD5.1", "DDP5.1")
        val hdrTags = setOf("SDR", "HDR", "HDR10", "DV", "DOLBYVISION")
        val platformTags = setOf("NF", "AMZN", "DSNP", "HMAX", "APTV", "HULU", "CC")

        return parts.mapNotNull { part ->
            val p = part.uppercase()
            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } || audioTags.contains(p) -> p
                hdrTags.contains(p) -> p
                platformTags.contains(p) -> p
                else -> null
            }
        }.distinct().joinToString(" ")
    }
}

// ======================================================================
// HUBCDN — resolves hubcdn URLs
// v26 pattern with hubcdn.fans mainUrl (proven working)
//
// Two patterns:
//   1. /dl/?link=https://hub.obsession.buzz/{hash}  -> CDN direct
//   2. /file/{id} -> base64 encoded reurl -> decode -> CDN link
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
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            Log.d("HUBCDN", "?link= -> $decoded")
            callback(
                newExtractorLink(name, name, decoded) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Strategy 2: Fetch page and decode reurl from script (/file/ URLs)
        try {
            val doc = app.get(url, timeout = 10000L).document

            // Check for a#vd link (hubcdn page structure)
            val vdLink = doc.selectFirst("a#vd")?.attr("href")?.trim()
            if (!vdLink.isNullOrEmpty() && vdLink.startsWith("http")) {
                Log.d("HUBCDN", "a#vd -> $vdLink")
                callback(
                    newExtractorLink(name, name, vdLink) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Check for a[download] link
            val dlLink = doc.selectFirst("a[href][download]")
                ?: doc.selectFirst("a#download[href]")
            val dlHref = dlLink?.attr("href")?.trim()
            if (!dlHref.isNullOrEmpty() && dlHref.startsWith("http")) {
                Log.d("HUBCDN", "download link -> $dlHref")
                callback(
                    newExtractorLink(name, name, dlHref) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try reurl variable in script
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
                    newExtractorLink(name, name, decodedUrl) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (e: Exception) {
            Log.w("HUBCDN", "Failed to resolve $url: ${e.message}")
        }

        // Strategy 3: Try redirect header
        try {
            val res = app.get(url, allowRedirects = false, timeout = 10000L)
            val location = res.headers["Location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                Log.d("HUBCDN", "redirect -> $location")
                callback(
                    newExtractorLink(name, name, location) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (_: Exception) {}

        Log.w("HUBCDN", "No link found for $url")
    }
}

// ======================================================================
// Hblinks — aggregator that extracts links from intermediary pages
// Routes to Hubdrive/HubCloud/HUBCDN based on domain
// v26 proven working pattern
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
                    "obsession.buzz" in lower -> {
                        callback(newExtractorLink(name, "Direct [CDN]", href) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    lower.contains("wordpress.org") || lower.contains("michaelvandenberg") -> return@forEach
                    else -> loadExtractor(href, name, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.w("Hblinks", "Failed for $href: ${e.message}")
            }
        }
    }
}
