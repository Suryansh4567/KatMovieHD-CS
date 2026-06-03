package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Browser-like headers for HubCloud/Hubdrive — many hosts block bare requests */
private val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to "https://hubcloud.lol/"
)

/** Known destination hosts that OlaMovies short links resolve to */
private val KNOWN_HOSTS = listOf(
    "hubcloud", "gdflix", "gdtot", "drive.google", "gofile",
    "pixeldrain", "hubdrive", "hubstream", "hubcdn", "katdrive",
    "olamovies.dad", "space.olamovies", "gdmirrorbot", "gd-flix",
    "gdlink", "gdmirror", "1fichier", "send.cm", "mediafire",
    "gdtot", "fuckingfast", "fastdl", "driveseed", "driveleech",
    "bbupload", "filepress", "vidstack", "doodstream", "mixdrop",
    "streamtape", "filemoon", "streamlare"
)

private fun isKnownHost(url: String): Boolean =
    KNOWN_HOSTS.any { url.contains(it, ignoreCase = true) }

// ─── OlaLinks — Shortener Extractor (COMPLETELY REWRITTEN) ──────────────────

/**
 * Extractor for links.ol-am.top / links.olamovies.mov — OlaMovies'
 * Cloudflare-protected link shortener.
 *
 * Resolution strategies (in order):
 *   1. Direct GET with CF bypass — check response.url for known host
 *   2. Parse response page for meta-refresh, JS redirects, known host links
 *   3. Manual HTTP redirect following (allowRedirects=false)
 *   4. HDHub4U-style JS deobfuscation (getRedirectLinks)
 *   5. Retry with different headers + text search for known hosts
 */
open class OlaLinks : ExtractorApi() {
    override val name = "OlaLinks"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("OlaLinks", "Resolving: $url")

            // Strategy 1: Direct GET with CF bypass — check response.url
            try {
                val response = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to "https://v2.olamovies.mov/"
                ), timeout = 30_000L)

                val finalUrl = response.url
                if (finalUrl != url && isKnownHost(finalUrl)) {
                    Log.d("OlaLinks", "Strategy1 (response.url): $url -> $finalUrl")
                    dispatchResolved(finalUrl, subtitleCallback, callback)
                    return
                }

                // If we got a real page (not CF challenge), try scraping it
                if (response.code == 200) {
                    val doc = response.document
                    val scraped = scrapePageForLinks(doc, url)
                    if (scraped != null) {
                        Log.d("OlaLinks", "Strategy1 (page scrape): $url -> $scraped")
                        dispatchResolved(scraped, subtitleCallback, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "Strategy1 failed: ${e.message}")
            }

            // Strategy 2: Manual redirect following
            try {
                val resolved = resolveFinalUrl(url)
                if (resolved != null && resolved != url && isKnownHost(resolved)) {
                    Log.d("OlaLinks", "Strategy2 (redirect): $url -> $resolved")
                    dispatchResolved(resolved, subtitleCallback, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "Strategy2 failed: ${e.message}")
            }

            // Strategy 3: getRedirectLinks for JS obfuscation
            try {
                val redirectResolved = getRedirectLinks(url)
                if (redirectResolved != null && redirectResolved != url) {
                    Log.d("OlaLinks", "Strategy3 (JS deobfuscation): $url -> $redirectResolved")
                    dispatchResolved(redirectResolved, subtitleCallback, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "Strategy3 failed: ${e.message}")
            }

            // Strategy 4: Second attempt with longer timeout and different headers
            try {
                val response2 = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to "https://v2.olamovies.mov/",
                    "Connection" to "keep-alive"
                ), timeout = 60_000L)

                val finalUrl2 = response2.url
                if (finalUrl2 != url && isKnownHost(finalUrl2)) {
                    Log.d("OlaLinks", "Strategy4 (retry): $url -> $finalUrl2")
                    dispatchResolved(finalUrl2, subtitleCallback, callback)
                    return
                }

                // Try scraping the page again with different approach
                try {
                    val pageText = response2.text
                    // Look for URL patterns in JavaScript/HTML content
                    for (host in KNOWN_HOSTS) {
                        val urlRegex = Regex("""https?://[^\s"'<>]*$host[^\s"'<>]*""")
                        val found = urlRegex.find(pageText)?.value?.trimEnd('\\', ',', '"', '\'', ')')
                        if (!found.isNullOrBlank() && found.startsWith("http")) {
                            Log.d("OlaLinks", "Strategy4 (text search): found $found")
                            dispatchResolved(found, subtitleCallback, callback)
                            return
                        }
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.d("OlaLinks", "Strategy4 failed: ${e.message}")
            }

            // Strategy 5: Last resort — try loadExtractor which uses built-in CF bypass
            try {
                Log.w("OlaLinks", "All strategies failed for $url, trying loadExtractor as last resort")
                loadExtractor(url, "https://v2.olamovies.mov/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("OlaLinks", "Every strategy failed for $url: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("OlaLinks", "Fatal error for $url: ${e.message}")
        }
    }

    /**
     * Scrape a page for redirect URLs:
     *   - meta http-equiv="refresh" redirect
     *   - JavaScript window.location / location.href redirect
     *   - Known host links in the page content
     *   - #download anchor
     *   - Any <a> link pointing to a known host
     */
    private fun scrapePageForLinks(doc: org.jsoup.nodes.Document, originalUrl: String): String? {
        // Meta refresh redirect
        val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
        if (!metaRefresh.isNullOrBlank()) {
            val redirectUrl = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)
                .find(metaRefresh)?.groupValues?.get(1)?.trim()
            if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                return redirectUrl
            }
        }

        // #download link
        val downloadHref = doc.selectFirst("#download")?.attr("href")
        if (!downloadHref.isNullOrBlank()) {
            val resolved = if (downloadHref.startsWith("http")) downloadHref
                           else getBaseUrl(originalUrl) + downloadHref
            if (isKnownHost(resolved)) return resolved
        }

        // Search all anchors for known host links
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.isNotBlank() && isKnownHost(href)) return href
        }

        // Search page text for known host URLs (JS variables, etc.)
        val pageText = doc.toString()
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>]*$host[^\s"'<>]*""")
            val found = urlRegex.find(pageText)?.value?.trimEnd('\\', ',', '"', '\'', ')')
            if (!found.isNullOrBlank()) return found
        }

        return null
    }

    private suspend fun dispatchResolved(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("hubcloud", ignoreCase = true) ->
                OlaHubCloud().getUrl(url, name, subtitleCallback, callback)
            url.contains("hubdrive", ignoreCase = true) ->
                OlaHubdrive().getUrl(url, name, subtitleCallback, callback)
            url.contains("hubstream", ignoreCase = true) ->
                OlaHubstream().getUrl(url, name, subtitleCallback, callback)
            url.contains("gdflix", ignoreCase = true) ||
            url.contains("gd-flix", ignoreCase = true) ->
                OlaGDFlix().getUrl(url, name, subtitleCallback, callback)
            url.contains("gdtot", ignoreCase = true) ->
                OlaGDTotCfd().getUrl(url, name, subtitleCallback, callback)
            url.contains("pixeldrain", ignoreCase = true) ->
                OlaPixelDrainDev().getUrl(url, name, subtitleCallback, callback)
            url.contains("vidstack", ignoreCase = true) ->
                OlaVidStack().getUrl(url, name, subtitleCallback, callback)
            url.contains("gdmirrorbot", ignoreCase = true) -> {
                loadExtractor(url, name, subtitleCallback, callback)
            }
            url.contains("olamovies.dad", ignoreCase = true) ||
            url.contains("space.olamovies", ignoreCase = true) -> {
                callback.invoke(
                    newExtractorLink(
                        "OlaMovies",
                        "OlaMovies Direct",
                        url,
                        ExtractorLinkType.VIDEO
                    )
                )
            }
            else -> {
                Log.d("OlaLinks", "Falling back to loadExtractor for: $url")
                loadExtractor(url, "", subtitleCallback, callback)
            }
        }
    }
}

// ─── OlaLinksMov — Extractor for links.olamovies.mov ────────────────────────

/**
 * Same as OlaLinks but registered for the links.olamovies.mov domain.
 * The shortener redirects from links.ol-am.top → links.olamovies.mov,
 * so both need to be handled.
 */
class OlaLinksMov : OlaLinks() {
    override val mainUrl = "https://links.olamovies.mov"
}

// ─── HubCloud Extractor (FIXED mainUrl for proper dispatch) ─────────────────

open class OlaHubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = app.get(url, headers = browserHeaders).document

            var link = if (url.contains("/video/")) {
                doc.selectFirst("div.vd > center > a")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { a ->
                        val h = a.attr("href")
                        val t = a.text()
                        h.contains("hubcloud.php", true) ||
                            t.contains("generate direct", true) ||
                            t.contains("direct download", true)
                    }?.attr("href")
                    ?: ""
            } else {
                val raw = doc.selectFirst("#download")?.attr("href").orEmpty()
                if (raw.startsWith("http", true)) raw
                else if (raw.isNotBlank()) baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
                else {
                    Regex("""var\s+url\s*=\s*'([^']*)'""")
                        .find(doc.selectFirst("script:containsData(url)")?.toString() ?: "")
                        ?.groupValues?.get(1) ?: ""
                }
            }

            if (link.isBlank()) {
                link = getRedirectLinks(url) ?: ""
            }
            if (link.isBlank()) return
            if (!link.startsWith("https://")) link = baseUrl + link

            val document = app.get(link, headers = browserHeaders).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            val labelExtras = buildString {
                if (header.isNotEmpty()) append("[$header]")
                if (size.isNotEmpty()) append("[$size]")
            }

            val ref = referer.orEmpty()

            document.select("a.btn, .btn-success, a[href*=download]").amap { element ->
                val btnLink = element.attr("href")
                val text = element.ownText().lowercase()

                when {
                    text.contains("fsl") -> {
                        callback.invoke(newExtractorLink(
                            "$ref [FSL]", "$ref [FSL] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("download file") || text.contains("direct") -> {
                        callback.invoke(newExtractorLink(
                            "$ref [Direct]", "$ref [Direct] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("buzz") -> {
                        try {
                            val resp = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                            val dlink = resp.headers["hx-redirect"]
                                ?: resp.headers["HX-Redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback.invoke(newExtractorLink(
                                    "$ref [Buzz]", "$ref [Buzz] $labelExtras", dlink, ExtractorLinkType.VIDEO
                                ) { this.quality = quality })
                            }
                        } catch (_: Exception) {}
                    }
                    text.contains("pixeldra") -> {
                        val pxBase = getBaseUrl(btnLink)
                        val finalUrl = when {
                            btnLink.contains("download", true) -> btnLink
                            btnLink.contains("/u/") -> "$pxBase/api/file/${btnLink.substringAfterLast("/u/").substringBefore("?")}?download"
                            else -> "$pxBase/api/file/${btnLink.substringAfterLast("/")}?download"
                        }
                        callback.invoke(newExtractorLink(
                            "$ref [PixelDrain]", "$ref [PixelDrain] $labelExtras", finalUrl, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("mega") -> {
                        callback.invoke(newExtractorLink(
                            "$ref [Mega]", "$ref [Mega] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("10gbps") || text.contains("server : 10") -> {
                        val resolved = resolveFinalUrl(btnLink)
                        val finalLink = resolved?.let { r ->
                            if (r.contains("link=")) r.substringAfter("link=") else r
                        } ?: btnLink
                        callback.invoke(newExtractorLink(
                            "$ref [10Gbps]", "$ref [10Gbps] $labelExtras", finalLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("cloud") || text.contains("r2") -> {
                        callback.invoke(newExtractorLink(
                            "$ref [Cloud R2]", "$ref [Cloud R2] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    text.contains("s3") -> {
                        callback.invoke(newExtractorLink(
                            "$ref [S3]", "$ref [S3] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                    else -> {
                        if (btnLink.startsWith("http")) {
                            loadExtractor(btnLink, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OlaHubCloud", "Failed: ${e.message}")
        }
    }
}

// ─── Hubdrive Extractor ──────────────────────────────────────────────────────

class OlaHubdrive : ExtractorApi() {
    override val name = "OlaHubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val href = app.get(url, headers = browserHeaders, timeout = 5000L).document
                .select(".btn.btn-primary.btn-user.btn-success1.m-1")
                .attr("href")
            when {
                href.contains("hubcloud", ignoreCase = true) ->
                    OlaHubCloud().getUrl(href, name, subtitleCallback, callback)
                href.isNotBlank() ->
                    loadExtractor(href, name, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("OlaHubdrive", "Failed: ${e.message}")
        }
    }
}

// ─── Hubstream (independent extractor, HubCloud-like handling) ────────────────

class OlaHubstream : ExtractorApi() {
    override val name = "OlaHubstream"
    override val mainUrl = "https://hubstream.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // HubStream links typically redirect to HubCloud, so delegate
        try {
            OlaHubCloud().getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("OlaHubstream", "Failed: ${e.message}")
        }
    }
}

// ─── VidStack Extractor with AES Decryption ──────────────────────────────────

open class OlaVidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
        )
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getBaseUrl(url)

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try { AesHelper.decryptAES(encoded, key, iv) } catch (_: Exception) { null }
        } ?: throw Exception("Failed to decrypt with all IVs")

        val m3u8 = Regex(""""source":"(.*?)"""").find(decryptedText)
            ?.groupValues?.get(1)?.replace("\\/", "/") ?: ""

        // Subtitle extraction
        val subtitleSection = Regex(""""subtitle":\{(.*?)\}""").find(decryptedText)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            Regex(""""([^"]+)":\s*"([^"]+)"""").findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8.replace("https", "http"),
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─── AES Helper ──────────────────────────────────────────────────────────────

object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"

    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

// ─── PixelDrainDev ───────────────────────────────────────────────────────────

class OlaPixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

// ─── GDFlix family ───────────────────────────────────────────────────────────

open class OlaGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, referer = referer)
            val document = response.document
            val baseUrl = getBaseUrl(response.url).ifBlank { "https://new18.gdflix.net" }
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").substringBefore(" | ")
            val quality = getIndexQuality(fileName)
            suspend fun push(l: String, s: String = "") {
                callback.invoke(newExtractorLink("$name$s", "$name$s $fileName[$fileSize]", l, ExtractorLinkType.VIDEO) { this.quality = quality })
            }
            document.select("a.btn, .btn, a[href*='/file/'], a[href*='busycdn'], a[href*='zfile'], a[href*='goflix'], a[href*='mirror']").amap { anchor ->
                val originalText = anchor.text(); val text = originalText.lowercase()
                val link = anchor.attr("href"); val absLink = if (link.startsWith("http")) link else baseUrl + link
                when {
                    text.contains("login") || text.contains("telegram") -> {}
                    text.contains("instant dl") || text.contains("instant download") -> {
                        try {
                            if (link.contains("busycdn.xyz") || link.contains("instant.")) {
                                val loc = app.get(absLink, referer = baseUrl, allowRedirects = false, timeout = 15).headers["Location"].orEmpty()
                                val cdn = Regex("""[?&]url=([^&]+)""").find(loc)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                                val f = when { !cdn.isNullOrBlank() && cdn.startsWith("http") -> cdn; loc.startsWith("http") && !loc.contains("busycdn", true) -> loc; else -> null }
                                if (!f.isNullOrBlank()) push(f, "[Instant 10GBPS]")
                            } else if (absLink.startsWith("http")) push(absLink, "[Instant DL]")
                        } catch (_: Exception) {}
                    }
                    text.contains("cloud download") || text.contains("[r2]") -> push(absLink, "[Cloud R2]")
                    text.contains("direct") -> push(absLink, "[Direct]")
                    text.contains("fsl") -> push(absLink, "[FSL V2]")
                    text.contains("fast cloud") || text.contains("zipdisk") -> try {
                        val d = app.get(absLink, referer = baseUrl).document.select("a[href]").map { it.attr("href") }
                            .firstOrNull { h -> Regex("""(?i)(workers\.dev|awscdn|googleusercontent|busycdn|\.mkv|\.mp4|/download)""").containsMatchIn(h) }.orEmpty()
                        if (d.isNotBlank()) push(if (d.startsWith("http")) d else baseUrl + d, "[Fast Cloud]")
                    } catch (_: Exception) {}
                    text.contains("pixeldrain") || link.contains("pixeldra") -> push(when {
                        absLink.contains("/u/") -> "${getBaseUrl(absLink)}/api/file/${absLink.substringAfterLast("/u/").substringBefore("?")}?download"
                        absLink.contains("download", true) -> absLink
                        else -> "${getBaseUrl(absLink)}/api/file/${absLink.substringAfterLast("/")}?download"
                    }, "[Pixeldrain]")
                    text.contains("gofile") || link.contains("multiup") || link.contains("mirror") -> try {
                        val h = app.get(absLink, referer = baseUrl).text
                        Regex("""https?://(?:gofile\.io/d/|validate\.multiup2\.workers\.dev/)[A-Za-z0-9]+""").findAll(h).map { it.value }.distinct().forEach { g ->
                            if (g.contains("multiup2")) resolveFinalUrl(g)?.let { f -> loadExtractor(f, absLink, subtitleCallback, callback) }
                            else loadExtractor(g, absLink, subtitleCallback, callback)
                        }
                    } catch (_: Exception) {}
                    else -> if (link.startsWith("http")) push(absLink, "[${originalText.take(15)}]")
                }
            }
        } catch (e: Exception) { Log.e("OlaGDFlix", "Failed for $url: ${e.message}") }
    }
}

class OlaGDFlixNet : OlaGDFlix() { override val mainUrl = "https://new18.gdflix.net" }
class OlaGDFlixNew1 : OlaGDFlix() { override val mainUrl = "https://new1.gdflix.dev" }
class OlaGDFlixNew17 : OlaGDFlix() { override val mainUrl = "https://new17.gdflix.net" }
class OlaGDFlixDotDev : OlaGDFlix() { override val mainUrl = "https://gdflix.dev" }
class OlaGDFlixDad : OlaGDFlix() { override val mainUrl = "https://new.gdflix.dad" }
class OlaGDFlixDad3 : OlaGDFlix() { override val mainUrl = "https://new3.gdflix.dad" }
class OlaGDFlixDad4 : OlaGDFlix() { override val mainUrl = "https://new4.gdflix.dad" }
class OlaGDFlixRest : OlaGDFlix() { override val mainUrl = "https://gdflix.rest" }
class OlaGDFlixCfd5 : OlaGDFlix() { override val mainUrl = "https://new5.gdflix.cfd" }
class OlaGDTotCfd : OlaGDFlix() { override val mainUrl = "https://new10.gdtot.cfd" }
class OlaGDLinkDev : OlaGDFlix() { override val mainUrl = "https://gdlink.dev" }
