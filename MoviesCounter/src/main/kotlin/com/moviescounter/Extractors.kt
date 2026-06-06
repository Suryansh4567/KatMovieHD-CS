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
 * Custom ExtractorApi classes for the Hubdrive -> HubCloud -> Server chain.
 * Based on actual website structure analysis + phisher98 patterns.
 *
 * Chain: hubdrive.space -> hubcloud.foo -> gamerxyt.com -> actual hoster
 * Alt:   hblinks.dad / mclinks.xyz -> hubdrive/hubcloud/hubcdn
 * Alt:   hubcdn.org -> hub.obsession.buzz -> CDN direct
 * Alt:   hdstream4u.com -> VidHidePro extractor
 */

// ======================================================================
// HdStream4u — delegates to CloudStream's built-in VidHidePro
// ======================================================================

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ======================================================================
// Hubdrive — resolves hubdrive.space link -> hubcloud/hubcdn/direct
//
// Actual page structure:
//   <a class="btn btn-primary btn-user btn-success1 m-1" href="https://hubcloud.foo/drive/...">
//     [HubCloud Server]
//   </a>
//   <a class="btn btn-primary btn-user btn-success m-1" href="https://hubcloud.foo/tg/go?id=...">
//     Telegram File
//   </a>
//   <button class="btn btn-primary btn-user1" onclick="myDirectDownload()">
//     Direct/Instant Download
//   </button>
// ======================================================================

class Hubdrive : ExtractorApi() {
    override val name = "HubDrive"
    override var mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HubDrive"
        private val LAZY_HUBCLOUD by lazy { HubCloud() }
        private val LAZY_HUBCDN by lazy { HUBCDN() }

        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://hubdrive.space/"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, headers = HEADERS, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            return
        }

        // Priority: HubCloud Server link (proven working path)
        val href = doc.selectFirst("a.btn-success1[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a[href*=hubcloud.foo/drive/]")?.attr("href")
            ?: doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcdn]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubdrive]")?.attr("href")
            ?: doc.selectFirst("a.btn-primary[href]")?.attr("href")
            ?: doc.selectFirst("a.btn-success[href]")?.attr("href")
            ?: doc.selectFirst("a[href].btn")?.attr("href")
            ?: doc.selectFirst("a[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a[href*=hubcdn]")?.attr("href")
            // Broadest fallback: any external link that's not the page itself
            ?: doc.select("a[href]").firstOrNull { a ->
                val h = a.attr("href").trim()
                h.startsWith("http") && !h.contains("hubdrive.space", true)
            }?.attr("href")

        if (href.isNullOrBlank()) {
            Log.w(TAG, "No link found on hubdrive page: $url")
            // Debug: log all links on the page
            doc.select("a[href]").forEach { a ->
                Log.d(TAG, "  hubdrive link: ${a.attr("href").take(80)} text='${a.text().take(30)}'")
            }
            return
        }

        val fullHref = resolveRelativeUrl(url, href)
        Log.d(TAG, "HubDrive resolved: $url -> $fullHref")

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
//
// Actual page structure:
//   1. hubcloud.foo/drive/{id} page has a#download link
//   2. a#download points to gamerxyt.com/hubcloud.php?host=hubcloud&id=...&token=...
//   3. gamerxyt.com page has download buttons (FSL, BuzzServer, PixelDrain, etc.)
// ======================================================================

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override var mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HubCloud"

        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://hubcloud.foo/"
        )
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
                val raw = app.get(realUrl, headers = HEADERS, timeout = 15_000L).document
                    .selectFirst("#download")?.attr("href").orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract href: ${e.message}"); return
        }

        if (href.isBlank()) {
            Log.w(TAG, "Empty href resolved from $url")
            // Debug: log the page content for diagnosis
            try {
                val debugDoc = app.get(realUrl, headers = HEADERS, timeout = 15_000L).document
                Log.d(TAG, "Page title: ${debugDoc.title()}")
                debugDoc.select("a[href]").forEach { a ->
                    Log.d(TAG, "  hubcloud link: ${a.attr("href").take(80)} id='${a.id()}' text='${a.text().take(30)}'")
                }
            } catch (_: Exception) {}
            return
        }

        Log.d(TAG, "HubCloud step1: $realUrl -> $href")

        // Step 2: Fetch the download page (gamerxyt.com)
        val document = try {
            app.get(href, headers = HEADERS, timeout = 15_000L).document
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

        Log.d(TAG, "HubCloud step2: quality=$quality header='$header' size='$size' buttons=${document.select("a.btn").size}")

        // Step 3: Process each download button
        document.select("a.btn").forEach { element ->
            val link = element.attr("href").trim()
            val text = element.ownText()
            val label = text.lowercase()

            if (link.isBlank() || !link.startsWith("http")) return@forEach

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
// HUBCDN — resolves hubcdn.org/dl/ and hubcdn.org/file/ URLs
//
// Two patterns:
//   1. /dl/?link=https://hub.obsession.buzz/{hash}  -> CDN direct
//   2. /file/{id} -> base64 encoded reurl -> decode -> CDN link
// ======================================================================

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.org"
    override val requiresReferer = false

    companion object {
        private const val TAG = "HUBCDN"

        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://hubcdn.org/"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "HUBCDN getUrl: $url")

        // Strategy 1: Extract from ?link= parameter (/dl/ URLs)
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            Log.d(TAG, "HUBCDN ?link= -> $decoded")

            // The link usually points to hub.obsession.buzz — try to follow it
            try {
                val resp = app.get(decoded, headers = HEADERS, allowRedirects = false, timeout = 15_000L)
                val location = resp.headers["Location"] ?: resp.headers["location"]
                if (!location.isNullOrEmpty() && location.startsWith("http")) {
                    Log.d(TAG, "obsession.buzz redirect -> $location")
                    callback(newExtractorLink(name, name, location, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

            // Try fetching the page
            try {
                val doc = app.get(decoded, headers = HEADERS, timeout = 15_000L).document
                // Look for download link or video source
                val dlLink = doc.selectFirst("a[href][download]")
                    ?: doc.selectFirst("a.btn[href*=http]")
                    ?: doc.selectFirst("a#download[href]")
                    ?: doc.selectFirst("a#vd[href]")
                    ?: doc.selectFirst("video source[src]")
                val href = dlLink?.attr("href")?.trim() ?: dlLink?.attr("src")?.trim()
                if (!href.isNullOrEmpty() && href.startsWith("http")) {
                    Log.d(TAG, "obsession.buzz page -> $href")
                    callback(newExtractorLink(name, name, href, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

            // Last resort for ?link=: use the decoded URL as direct link
            callback(newExtractorLink(name, name, decoded, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
            return
        }

        // Strategy 2: Fetch page and decode reurl from script (/file/ URLs)
        try {
            val doc = app.get(url, headers = HEADERS, timeout = 10_000L).document

            // Try reurl variable in script
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                ?: doc.selectFirst("script:containsData(reurl)")?.data()

            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")

            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

            if (decodedUrl != null) {
                Log.d(TAG, "HUBCDN reurl decoded -> $decodedUrl")
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
                val res = app.get(url, allowRedirects = false, headers = HEADERS, timeout = 10_000L)
                val location = res.headers["Location"]
                if (!location.isNullOrEmpty() && location.startsWith("http")) {
                    Log.d(TAG, "HUBCDN redirect -> $location")
                    callback(newExtractorLink(name, name, location, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

            Log.w(TAG, "HUBCDN: no link found for $url")

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
//
// Actual mclinks.xyz page structure:
//   <div class="entry-content">
//     <p><a href="https://hubcdn.org/file/..."><img ... alt=""></a></p>
//     <hr>
//     <p><a href="https://hubcloud.foo/drive/..."><img ... alt=""></a></p>
//     <hr>
//     <p><a href="https://hubdrive.space/file/..."><img ... alt=""></a></p>
//   </div>
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

        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://mclinks.xyz/"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, headers = HEADERS, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            return
        }

        Log.d(TAG, "Hblinks fetched: $url, title: ${doc.title()}")

        // Primary selectors for mclinks.xyz pages (image-button links)
        val selectors = "div.entry-content a[href], div.entry-content p a[href], " +
                "h3 a[href], h5 a[href], article a[href], main a[href], " +
                ".post-body a[href], .content a[href]"

        val links = doc.select(selectors).mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (href.isBlank() || !href.startsWith("http")) null
            else href
        }.distinct()

        Log.d(TAG, "Hblinks found ${links.size} link(s) on page")

        if (links.isEmpty()) {
            // Debug: log all links
            doc.select("a[href]").forEach { a ->
                Log.d(TAG, "  hblinks all: ${a.attr("href").take(80)}")
            }
        }

        for (href in links) {
            val lower = href.lowercase()
            try {
                when {
                    "hubdrive" in lower ->
                        LAZY_HUBDRIVE.getUrl(href, name, subtitleCallback, callback)
                    "hubcloud" in lower ->
                        LAZY_HUBCLOUD.getUrl(href, name, subtitleCallback, callback)
                    "hubcdn" in lower ->
                        LAZY_HUBCDN.getUrl(href, name, subtitleCallback, callback)
                    "obsession.buzz" in lower -> {
                        // Try to resolve obsession.buzz URL
                        try {
                            val resp = app.get(href, headers = HEADERS, allowRedirects = false, timeout = 15_000L)
                            val location = resp.headers["Location"] ?: resp.headers["location"]
                            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                                resolveAndLoadLink(location, subtitleCallback, callback)
                            }
                        } catch (_: Exception) {}
                    }
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

    private suspend fun resolveAndLoadLink(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = url.lowercase()
        when {
            "hubdrive" in lower -> LAZY_HUBDRIVE.getUrl(url, name, subtitleCallback, callback)
            "hubcloud" in lower -> LAZY_HUBCLOUD.getUrl(url, name, subtitleCallback, callback)
            "hubcdn" in lower -> LAZY_HUBCDN.getUrl(url, name, subtitleCallback, callback)
            else -> callback(newExtractorLink(name, name, url, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
        }
    }
}
