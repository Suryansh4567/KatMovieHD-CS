package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Custom ExtractorApi classes for the Hubdrive → HubCloud → Server chain.
 * Based on patterns from phisher98/HDhub4u and FourKHDHub extensions.
 *
 * Chain: hubdrive.space → hubcloud.foo → FSL/BuzzServer/PixelDrain/S3/Mega/GoFile
 * Alt:   hblinks.dad → hubdrive/hubcloud/hubcdn
 * Alt:   hubcdn.fans → base64 decoded URL
 * Alt:   hdstream4u.com → VidHidePro extractor
 */

// ======================================================================
// HdStream4u — delegates to CloudStream's built-in VidHidePro
// ======================================================================

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ======================================================================
// Hubdrive — resolves hubdrive.space link → hubcloud/hubcdn/direct
// ======================================================================

class Hubdrive : ExtractorApi() {
    override val name = "HubDrive"
    override var mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HubDrive"
        private val LAZY_HUBCLOUD by lazy { HubCloud() }
        private val LAZY_HUBCDN by lazy { HUBCDN() }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            return
        }

        // Multiple button selectors — hubdrive changes these frequently
        val href = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcdn]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubdrive]")?.attr("href")
            ?: doc.selectFirst("a.btn-primary[href]")?.attr("href")
            ?: doc.selectFirst("a.btn-success[href]")?.attr("href")
            ?: doc.selectFirst("a[href].btn")?.attr("href")
            ?: doc.selectFirst("a[href*=http]")?.attr("href")

        if (href.isNullOrBlank()) {
            Log.w(TAG, "No link found on hubdrive page: $url")
            return
        }

        val fullHref = resolveRelativeUrl(url, href)

        Log.d(TAG, "Resolved: $url → $fullHref")

        when {
            fullHref.contains("hubcloud", true) ->
                LAZY_HUBCLOUD.getUrl(fullHref, name, subtitleCallback, callback)
            fullHref.contains("hubcdn", true) ->
                LAZY_HUBCDN.getUrl(fullHref, name, subtitleCallback, callback)
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
// Extracts quality/size from card header, then resolves server buttons
// ======================================================================

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override var mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HubCloud"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer.orEmpty().ifBlank { name }

        val uri = try { URI(url) } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: ${e.message}"); return
        }
        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        // Step 1: Resolve the #download href from hubcloud page
        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl, timeout = 15_000L).document
                    .selectFirst("#download")?.attr("href").orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract href: ${e.message}"); return
        }

        if (href.isBlank()) {
            Log.w(TAG, "Empty href resolved from $url"); return
        }

        // Step 2: Fetch the download page
        val document = try {
            app.get(href, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch download page: ${e.message}"); return
        }

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanHeaderTitle(header)
        val quality = extractQualityFromHeader(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        // Step 3: Process each download button
        document.select("a.btn").forEach { element ->
            val link = element.attr("href")
            val text = element.ownText()
            val label = text.lowercase()

            try {
                when {
                    "fsl server" in label || "fsl server" in link.lowercase() -> {
                        callback(newExtractorLink(
                            "$ref [FSL]", "$ref [FSL] $labelExtras", link, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "download file" in label -> {
                        callback(newExtractorLink(
                            ref, "$ref $labelExtras", link, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "buzzserver" in label -> {
                        try {
                            val resp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = resp.headers["hx-redirect"]
                                ?: resp.headers["HX-Redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback(newExtractorLink(
                                    "$ref [Buzz]", "$ref [Buzz] $labelExtras", dlink, INFER_TYPE
                                ) { this.quality = quality })
                            }
                        } catch (_: Exception) {}
                    }

                    "pixeldra" in label || "pixelserver" in label || "pixel server" in label -> {
                        val base = getBaseUrl(link)
                        val finalUrl = if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"
                        callback(newExtractorLink(
                            "$ref [Pixel]", "$ref [Pixel] $labelExtras", finalUrl, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "s3 server" in label -> {
                        callback(newExtractorLink(
                            "$ref [S3]", "$ref [S3] $labelExtras", link, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "fslv2" in label -> {
                        callback(newExtractorLink(
                            "$ref [FSLv2]", "$ref [FSLv2] $labelExtras", link, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "mega server" in label -> {
                        callback(newExtractorLink(
                            "$ref [Mega]", "$ref [Mega] $labelExtras", link, INFER_TYPE
                        ) { this.quality = quality })
                    }

                    "gofile" in label -> {
                        loadExtractor(link, ref, subtitleCallback, callback)
                    }

                    "hubcdn" in link.lowercase() -> {
                        HUBCDN().getUrl(link, ref, subtitleCallback, callback)
                    }

                    else -> {
                        if (!loadExtractor(link, ref, subtitleCallback, callback)) {
                            callback(newExtractorLink(
                                ref, "$ref $labelExtras", link, INFER_TYPE
                            ) { this.quality = quality })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve button '$label': ${e.message}")
            }
        }
    }

    /** Extract quality value (720, 1080, 2160) from card header text */
    private fun extractQualityFromHeader(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try { URI(url).let { "${it.scheme}://${it.host}" } }
        catch (_: Exception) { "" }
    }

    /** Clean header text to extract meaningful metadata tags */
    private fun cleanHeaderTitle(title: String): String {
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
// HUBCDN — decodes base64 reurl parameter or ?link= param
// ======================================================================

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.fans"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HUBCDN"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Strategy 1: Extract from ?link= parameter (instant CDN)
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            callback(newExtractorLink(name, name, decoded, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
            return
        }

        // Strategy 2: Fetch page and decode reurl from script
        try {
            val doc = app.get(url, timeout = 10_000L).document

            // Try reurl variable in script
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                ?: doc.selectFirst("script:containsData(reurl)")?.data()

            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")

            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

            if (decodedUrl != null) {
                callback(newExtractorLink(name, name, decodedUrl, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            // Strategy 3: Try ?link= in page URL after potential redirects
            val pageLinkParam = doc.selectFirst("a[href*=link=]")?.attr("href")
            if (!pageLinkParam.isNullOrEmpty()) {
                val resolved = if (pageLinkParam.startsWith("http")) pageLinkParam
                else "${getBaseUrl(url)}/$pageLinkParam"
                getUrl(resolved, referer, subtitleCallback, callback)
                return
            }

            // Strategy 4: Try redirect header
            try {
                val res = app.get(url, allowRedirects = false, timeout = 10_000L)
                val location = res.headers["Location"]
                if (!location.isNullOrEmpty() && location.startsWith("http")) {
                    callback(newExtractorLink(name, name, location, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve $url: ${e.message}")
        }
    }

    private fun getBaseUrl(url: String): String {
        return try { URI(url).let { "${it.scheme}://${it.host}" } }
        catch (_: Exception) { "" }
    }
}

// ======================================================================
// Hblinks — aggregator that extracts links from intermediary pages
// Routes to Hubdrive/HubCloud/HUBCDN based on domain
// ======================================================================

class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override var mainUrl = "https://hblinks.dad"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Hblinks"
        private val LAZY_HUBDRIVE by lazy { Hubdrive() }
        private val LAZY_HUBCLOUD by lazy { HubCloud() }
        private val LAZY_HUBCDN by lazy { HUBCDN() }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            return
        }

        // Broad selector coverage — intermediary pages have varied structures
        val selectors = "h3 a, h5 a, div.entry-content p a, div.entry-content a, " +
                "article a, main a, .post-body a, .content a"

        doc.select(selectors).forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            try {
                when {
                    "hubdrive" in lower ->
                        LAZY_HUBDRIVE.getUrl(href, name, subtitleCallback, callback)
                    "hubcloud" in lower ->
                        LAZY_HUBCLOUD.getUrl(href, name, subtitleCallback, callback)
                    "hubcdn" in lower ->
                        LAZY_HUBCDN.getUrl(href, name, subtitleCallback, callback)
                    else -> {
                        if (!loadExtractor(href, name, subtitleCallback, callback)) {
                            // Try as direct link
                            callback(newExtractorLink(name, name, href, INFER_TYPE) {
                                this.quality = Qualities.Unknown.value
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hblinks failed for $href: ${e.message}")
            }
        }
    }
}
