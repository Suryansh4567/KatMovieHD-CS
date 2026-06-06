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
 * Alt:   mclinks.xyz -> hubdrive/hubcloud/hubcdn
 * Alt:   hubcdn.sbs/dl/?link=obsession.buzz -> CDN direct
 * Alt:   hubcdn.sbs/file/{id} -> base64 reurl decode -> CDN
 * Alt:   hdstream4u.com -> VidHidePro extractor
 *
 * v30 fixes:
 * - HUBCDN mainUrl changed from hubcdn.org to hubcdn.sbs (actual domain in URLs)
 * - HUBCDN obsession.buzz handling: don't try to download 500MB+ video as HTML document
 * - New Mclinks extractor with mainUrl=mclinks.xyz for proper loadExtractor routing
 * - BuzzServer label check fixed: "buzz server" (with space) not "buzzserver"
 */

// ======================================================================
// HdStream4u — delegates to CloudStream's built-in VidHidePro
// ======================================================================

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ======================================================================
// Mclinks — extracts links from mclinks.xyz intermediary pages
//
// Actual page structure:
//   <div class="entry-content">
//     <p><a href="https://hubcloud.foo/drive/..."><img ...></a></p>
//     <p><a href="https://hubdrive.space/file/..."><img ...></a></p>
//   </div>
// ======================================================================

class Mclinks : ExtractorApi() {
    override val name = "Mclinks"
    override var mainUrl = "https://mclinks.xyz"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Mclinks"
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

        Log.d(TAG, "Mclinks fetched: $url")

        // Primary selectors for mclinks.xyz pages
        val selectors = "div.entry-content a[href], div.entry-content p a[href], " +
                "h3 a[href], h5 a[href], article a[href], main a[href], " +
                ".post-body a[href], .content a[href]"

        val links = doc.select(selectors).mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (href.isBlank() || !href.startsWith("http")) null
            else href
        }.distinct()

        Log.d(TAG, "Mclinks found ${links.size} link(s) on page")

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
                        // Direct CDN link — don't parse as HTML
                        callback(newExtractorLink(name, "Direct [CDN]", href, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    "mclinks" in lower -> {
                        // Skip self-referential mclinks links
                        continue
                    }
                    // Skip obviously non-download links
                    lower.contains("wordpress.org") || lower.contains("michaelvandenberg") -> continue
                    else -> {
                        if (!loadExtractor(href, name, subtitleCallback, callback)) {
                            callback(newExtractorLink(name, name, href, INFER_TYPE) {
                                this.quality = Qualities.Unknown.value
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mclinks failed for $href: ${e.message}")
            }
        }
    }
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
// Chain: hubcloud.foo/drive/{id} -> a#download -> gamerxyt.com -> buttons
//
// gamerxyt.com page has buttons like:
//   "Download [FSL Server]"     -> obsession.buzz CDN
//   "Download [FSLv2 Server]"  -> cdn.fukggl.buzz
//   "Download [Buzz Server]"   -> bzzhr.co
//   "Download [PixelServer]"   -> pixel.hubcloud.cx
//   "Download [PixelServer : 2]" -> pixeldrain.dev
//   "Download [Server : 10Gbps]" -> pixel.hubcloud.cx
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

                    "buzz server" in label || "buzzserver" in label -> {
                        try {
                            val resp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = resp.headers["hx-redirect"]
                                ?: resp.headers["HX-Redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback(newExtractorLink(
                                    "$ref [Buzz]", "$ref [Buzz] $labelExtras", dlink, INFER_TYPE
                                ) { this.quality = quality })
                            } else {
                                // If no hx-redirect, try the link directly
                                callback(newExtractorLink(
                                    "$ref [Buzz]", "$ref [Buzz] $labelExtras", link, INFER_TYPE
                                ) { this.quality = quality })
                            }
                        } catch (_: Exception) {
                            callback(newExtractorLink(
                                "$ref [Buzz]", "$ref [Buzz] $labelExtras", link, INFER_TYPE
                            ) { this.quality = quality })
                        }
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
// HUBCDN — resolves hubcdn.sbs/dl/ and hubcdn.sbs/file/ URLs
//
// CRITICAL: mainUrl is hubcdn.sbs (actual domain), NOT hubcdn.org
//
// Two patterns:
//   1. /dl/?link=https://hub.obsession.buzz/{hash}  -> CDN direct
//      obsession.buzz returns HTTP 200 with content-disposition (VIDEO FILE, not redirect!)
//   2. /file/{id} -> base64 encoded reurl -> decode -> CDN link
// ======================================================================

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.sbs"  // FIX: was hubcdn.org, actual URLs use hubcdn.sbs
    override val requiresReferer = false

    companion object {
        private const val TAG = "HUBCDN"

        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://hubcdn.sbs/"
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

            // obsession.buzz is a DIRECT VIDEO FILE (HTTP 200, not redirect)
            // Do NOT try to fetch it as HTML document (it's 500MB+ and would timeout)
            // Check for redirect first (some URLs might redirect)
            try {
                val resp = app.get(decoded, headers = HEADERS, allowRedirects = false, timeout = 10_000L)
                val location = resp.headers["Location"] ?: resp.headers["location"]
                if (!location.isNullOrEmpty() && location.startsWith("http")) {
                    Log.d(TAG, "obsession.buzz redirect -> $location")
                    callback(newExtractorLink(name, name, location, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

            // It's a direct video file — use URL as-is
            callback(newExtractorLink(name, "Direct [CDN]", decoded, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
            return
        }

        // Strategy 2: Fetch hubcdn.sbs page and look for a#vd link
        try {
            val doc = app.get(url, headers = HEADERS, timeout = 10_000L).document

            // hubcdn.sbs page has: <a id="vd" href='https://hub.obsession.buzz/...'>
            val vdLink = doc.selectFirst("a#vd")?.attr("href")?.trim()
            if (!vdLink.isNullOrEmpty() && vdLink.startsWith("http")) {
                Log.d(TAG, "HUBCDN a#vd -> $vdLink")
                // obsession.buzz is a direct video file
                callback(newExtractorLink(name, "Direct [CDN]", vdLink, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            // Also check for a[download] or a#download
            val dlLink = doc.selectFirst("a[href][download]")
                ?: doc.selectFirst("a#download[href]")
            val dlHref = dlLink?.attr("href")?.trim()
            if (!dlHref.isNullOrEmpty() && dlHref.startsWith("http")) {
                Log.d(TAG, "HUBCDN download link -> $dlHref")
                callback(newExtractorLink(name, name, dlHref, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                })
                return
            }
        } catch (_: Exception) {}

        // Strategy 3: Try reurl variable in script (/file/ URLs)
        try {
            val doc = app.get(url, headers = HEADERS, timeout = 10_000L).document

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
        } catch (_: Exception) {}

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
    }
}

// ======================================================================
// Hblinks — aggregator that extracts links from intermediary pages
// Routes to Hubdrive/HubCloud/HUBCDN based on domain
//
// Actual mclinks.xyz page structure:
//   <div class="entry-content">
//     <p><a href="https://hubcdn.sbs/file/..."><img ... alt=""></a></p>
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
            "Referer" to "https://hblinks.dad/"
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

        Log.d(TAG, "Hblinks fetched: $url")

        // Primary selectors for intermediary pages
        val selectors = "div.entry-content a[href], div.entry-content p a[href], " +
                "h3 a[href], h5 a[href], article a[href], main a[href], " +
                ".post-body a[href], .content a[href]"

        val links = doc.select(selectors).mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (href.isBlank() || !href.startsWith("http")) null
            else href
        }.distinct()

        Log.d(TAG, "Hblinks found ${links.size} link(s) on page")

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
                        // Direct CDN link — don't parse as HTML
                        callback(newExtractorLink(name, "Direct [CDN]", href, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    // Skip obviously non-download links
                    lower.contains("wordpress.org") || lower.contains("michaelvandenberg") -> continue
                    else -> {
                        if (!loadExtractor(href, name, subtitleCallback, callback)) {
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
