package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
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
    "Referer" to "https://hubcloud.foo/"
)

/** Shortener/referer headers mimicking browser from OlaMovies */
private val olaHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to "https://v2.olamovies.mov/",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "same-site",
    "Sec-Fetch-User" to "?1"
)

/** Known destination hosts that OlaMovies short links resolve to */
private val KNOWN_HOSTS = listOf(
    "hubcloud", "gdflix", "gdtot", "drive.google", "gofile",
    "pixeldrain", "hubdrive", "hubstream", "hubcdn", "katdrive",
    "olamovies.dad", "space.olamovies", "gdmirrorbot", "gd-flix",
    "gdlink", "gdmirror", "1fichier", "send.cm", "mediafire",
    "fuckingfast", "fastdl", "driveseed", "driveleech",
    "bbupload", "filepress", "vidstack", "doodstream", "mixdrop",
    "streamtape", "filemoon", "streamlare", "krakenfiles",
    "filelions", "streamhide", "streamwish", "vidhide",
    "busycdn", "goflix", "zfile", "workers.dev",
    "awscdn", "googleusercontent", "megadb", "shrdsk",
    "hubcloud.foo", "hubcloud.dad", "katdrive.in",
    "gdtot.cfd", "gdflix.dad", "gdflix.rest", "gdflix.dev",
    "gdflix.cfd", "gdflix.net", "gdflix.one",
    "filegram", "gdlink", "gdmirror"
)

/** Intermediate shortener domains */
private val INTERMEDIATE_HOSTS = listOf(
    "ukrupdate.com", "mastkhabre.com", "aryx.xyz",
    "superheromaniac.com", "spatsify.com",
    "anylinks.in", "rocklinks.net", "dulink.net",
    "ez4short.com", "v2links.com", "v2links.me",
    "olamovies.mov", "links.olamovies.mov",
    "links.ol-am.top", "ol-am.top",
    "olamovies.download", "app2.olamovies.download",
    "bestloansoffers.com", "worldzc.com", "earningtime.in"
)

private fun isKnownHost(url: String): Boolean =
    KNOWN_HOSTS.any { url.contains(it, ignoreCase = true) }

private fun isIntermediateHost(url: String): Boolean =
    INTERMEDIATE_HOSTS.any { url.contains(it, ignoreCase = true) }

// ─── OlaLinks — Simplified Shortener Extractor (v6 rewrite) ─────────────────

/**
 * Extractor for links.ol-am.top / links.olamovies.mov — OlaMovies'
 * Cloudflare-protected link shortener.
 *
 * SIMPLE approach based on LikDev's proven pattern:
 *
 * The link chain:
 *   1. Movie page has `links.ol-am.top/XXXXX` links
 *   2. links.ol-am.top/XXXXX → 301 → links.olamovies.mov/XXXXX (CF Turnstile)
 *   3. After CF solved: lands on Anylinks page with ?key=&id= params
 *   4. bypassOlaRedirect follows #download > a chain
 *   5. bypassAdLinks resolves dulink/ez4short/rocklinks/crazyblog
 *   6. Final host: HubCloud/GDFlix/etc. → use loadExtractor()
 *
 * Resolution strategies (SIMPLE — 3 focused approaches):
 *   S1: loadExtractor() — CloudStream's built-in CF bypass (WebView solves Turnstile)
 *   S2: bypassOlaRedirect() + bypassAdLinks() — LikDev's proven chain
 *   S3: Direct app.get() chain follow with page scraping
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
            Log.d("OlaLinks", "=== Resolving: $url ===")
            val ref = referer ?: "https://v2.olamovies.mov/"

            // ── S1: loadExtractor() — CloudStream's built-in CF bypass (WebView) ──
            // This is the MOST RELIABLE way to handle CF Turnstile on real devices.
            try {
                Log.d("OlaLinks", "S1: trying loadExtractor for CF bypass")
                loadExtractor(url, ref, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d("OlaLinks", "S1 (loadExtractor) failed: ${e.message}")
            }

            // ── S2: bypassOlaRedirect + bypassAdLinks — LikDev's proven approach ──
            try {
                Log.d("OlaLinks", "S2: trying bypassOlaRedirect + bypassAdLinks")
                val resolvedLinks = bypassOlaRedirect(url, ref)
                for (shortLink in resolvedLinks) {
                    Log.d("OlaLinks", "S2: got short link -> $shortLink")

                    // If it's an ad shortener, try to bypass
                    val finalUrl = if (isAdShortener(shortLink)) {
                        bypassAdLinks(shortLink) ?: shortLink
                    } else {
                        shortLink
                    }

                    // If we got a known host, dispatch it
                    if (isKnownHost(finalUrl)) {
                        Log.d("OlaLinks", "S2: resolved to known host -> $finalUrl")
                        dispatchFinalHost(finalUrl, subtitleCallback, callback)
                    } else if (finalUrl.startsWith("http")) {
                        // Try loadExtractor as fallback
                        try {
                            loadExtractor(finalUrl, ref, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S2 failed: ${e.message}")
            }

            // ── S3: Direct app.get() chain follow ──
            try {
                Log.d("OlaLinks", "S3: trying direct chain follow")
                val chainResult = followChain(url, ref)
                if (chainResult != null && isKnownHost(chainResult)) {
                    Log.d("OlaLinks", "S3: resolved -> $chainResult")
                    dispatchFinalHost(chainResult, subtitleCallback, callback)
                } else if (chainResult != null) {
                    try {
                        loadExtractor(chainResult, ref, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S3 failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("OlaLinks", "Fatal error for $url: ${e.message}")
        }
    }

    /** Check if a URL is an ad shortener that needs bypass */
    private fun isAdShortener(url: String): Boolean {
        val adHosts = listOf("dulink", "ez4short", "rocklinks", "crazyblog")
        return adHosts.any { url.contains(it, ignoreCase = true) }
    }

    /**
     * Simple chain follower: GET the URL, follow redirects,
     * check if we land on a known host or a page with links.
     * Max 8 steps to avoid infinite loops.
     */
    private suspend fun followChain(startUrl: String, referer: String): String? {
        val visited = mutableSetOf<String>()
        var currentUrl = startUrl

        repeat(8) { step ->
            if (currentUrl in visited) return null
            visited.add(currentUrl)

            if (isKnownHost(currentUrl)) return currentUrl

            Log.d("OlaLinks", "Chain step ${step + 1}: $currentUrl")

            val response = try {
                app.get(currentUrl, headers = olaHeaders, referer = referer, timeout = 30_000L)
            } catch (e: Exception) {
                Log.d("OlaLinks", "Chain fetch failed: ${e.message}")
                return null
            }

            // Check if CloudStream followed through to a known host
            val finalUrl = response.url
            if (finalUrl != currentUrl && isKnownHost(finalUrl)) return finalUrl

            // Check if response is an OlaGenerate page
            if (finalUrl.contains("olamovies.download", ignoreCase = true) ||
                finalUrl.contains("app2.olamovies.download", ignoreCase = true)) {
                val genResult = resolveOlaGeneratePage(response.document, finalUrl)
                if (genResult != null) return genResult
            }

            // Try scraping the page for known host links
            if (response.code == 200) {
                val doc = response.document

                // Check for #download > a (bypassOlaRedirect pattern)
                val downloadHref = doc.selectFirst("#download > a")?.attr("href")
                if (!downloadHref.isNullOrBlank() && downloadHref.startsWith("http")) {
                    if (isKnownHost(downloadHref)) return downloadHref
                    currentUrl = downloadHref
                    return@repeat
                }

                // Check for Anylinks #btn6 or #tp98
                val btn6Href = doc.selectFirst("#btn6")?.attr("href")
                if (!btn6Href.isNullOrBlank() && btn6Href.startsWith("http")) {
                    if (isKnownHost(btn6Href)) return btn6Href
                    currentUrl = btn6Href
                    return@repeat
                }

                val tp98Href = doc.selectFirst("#tp98")?.attr("href")
                if (!tp98Href.isNullOrBlank() && tp98Href.startsWith("http")) {
                    if (isKnownHost(tp98Href)) return tp98Href
                    currentUrl = tp98Href
                    return@repeat
                }

                // Check for button.inline-flex (OlaGenerate pattern)
                val inlineBtn = doc.selectFirst("button.inline-flex")
                if (inlineBtn != null) {
                    val genResult = resolveOlaGeneratePage(doc, finalUrl)
                    if (genResult != null) return genResult
                }

                // Scrape all <a> tags for known host URLs
                for (anchor in doc.select("a[href]")) {
                    val href = anchor.attr("href")
                    if (href.isNotBlank() && isKnownHost(href)) return href
                }

                // Search page text for known host URLs
                findKnownHostUrl(doc.toString())?.let { return it }
            }

            // No progress
            return null
        }

        return if (isKnownHost(currentUrl)) currentUrl else null
    }

    /** Resolve an OlaGenerate page (app2.olamovies.download/generate/?id=XXX) */
    private fun resolveOlaGeneratePage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): String? {
        // Find button.inline-flex target
        val inlineBtn = doc.selectFirst("button.inline-flex")
        if (inlineBtn != null) {
            var target = inlineBtn.attr("data-url").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-href").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-link").ifBlank { null }
            if (target == null) {
                val onclick = inlineBtn.attr("onclick").ifBlank { null }
                if (onclick != null) {
                    target = Regex("""(?:window\.open|location\.href|location)\s*\(\s*['"]([^'"]+)['"]""")
                        .find(onclick)?.groupValues?.get(1)
                }
            }
            if (target == null) {
                val parentLink = inlineBtn.parent()?.takeIf { it.tagName() == "a" }
                if (parentLink != null) {
                    target = parentLink.attr("href").ifBlank { null }
                }
            }
            if (!target.isNullOrBlank()) {
                val targetUrl = if (target.startsWith("http")) target
                                else getBaseUrl(pageUrl) + target
                if (isKnownHost(targetUrl)) return targetUrl
                if (isIntermediateHost(targetUrl)) return targetUrl
            }
        }

        // Fallback: search for known host links in the page
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.isNotBlank() && isKnownHost(href)) return href
        }

        // Fallback: search page text
        findKnownHostUrl(doc.toString())?.let { return it }

        return null
    }

    /** Find a known host URL in raw HTML text */
    private fun findKnownHostUrl(html: String): String? {
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
            val found = urlRegex.find(html)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
            if (!found.isNullOrBlank() && found.startsWith("http")) return found
        }
        return null
    }

    /**
     * Dispatch a resolved URL to the appropriate handler.
     * For known hosts (HubCloud, GDFlix, etc.), use loadExtractor()
     * which lets CloudStream handle them natively.
     * This is SIMPLER than the old approach of routing to custom extractors.
     */
    private suspend fun dispatchFinalHost(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                // HubCloud family → use our custom extractors for quality info
                url.contains("hubcloud", ignoreCase = true) -> {
                    when {
                        url.contains("hubcloud.foo", ignoreCase = true) ->
                            OlaHubCloudFoo().getUrl(url, name, subtitleCallback, callback)
                        url.contains("hubcloud.dad", ignoreCase = true) ->
                            OlaHubCloudDad().getUrl(url, name, subtitleCallback, callback)
                        else ->
                            OlaHubCloud().getUrl(url, name, subtitleCallback, callback)
                    }
                }
                url.contains("hubdrive", ignoreCase = true) ->
                    OlaHubdrive().getUrl(url, name, subtitleCallback, callback)
                url.contains("hubstream", ignoreCase = true) ->
                    OlaHubstream().getUrl(url, name, subtitleCallback, callback)
                // GDFlix family → use our custom extractors
                url.contains("gdflix", ignoreCase = true) ||
                url.contains("gd-flix", ignoreCase = true) ->
                    OlaGDFlix().getUrl(url, name, subtitleCallback, callback)
                url.contains("gdtot", ignoreCase = true) ->
                    OlaGDTotCfd().getUrl(url, name, subtitleCallback, callback)
                // VidStack → use our custom AES-decrypting extractor
                url.contains("vidstack", ignoreCase = true) ->
                    OlaVidStack().getUrl(url, name, subtitleCallback, callback)
                // PixelDrain → use our custom extractor
                url.contains("pixeldrain", ignoreCase = true) ->
                    OlaPixelDrainDev().getUrl(url, name, subtitleCallback, callback)
                // OlaMovies direct
                url.contains("olamovies.dad", ignoreCase = true) ||
                url.contains("space.olamovies", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "OlaMovies", "OlaMovies Direct", url, ExtractorLinkType.VIDEO
                        )
                    )
                }
                // Everything else → let CloudStream handle it via loadExtractor
                else -> {
                    Log.d("OlaLinks", "Falling back to loadExtractor for: $url")
                    loadExtractor(url, "", subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d("OlaLinks", "dispatchFinalHost failed for $url: ${e.message}")
            // Last resort: try loadExtractor
            try {
                loadExtractor(url, "", subtitleCallback, callback)
            } catch (_: Exception) {}
        }
    }
}

// ─── OlaLinksMov — Extractor for links.olamovies.mov ────────────────────────

class OlaLinksMov : OlaLinks() {
    override val mainUrl = "https://links.olamovies.mov"
}

// ─── HubCloud Extractor ─────────────────────────────────────────────────────

open class OlaHubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.lol"
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
                    else -> {
                        callback.invoke(newExtractorLink(
                            "$ref [Download]", "$ref [Download] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                        ) { this.quality = quality })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OlaHubCloud", "Failed: ${e.message}")
        }
    }
}

class OlaHubCloudFoo : OlaHubCloud() {
    override val mainUrl = "https://hubcloud.foo"
    override val name = "Hub-Cloud"
}

class OlaHubCloudDad : OlaHubCloud() {
    override val mainUrl = "https://hubcloud.dad"
    override val name = "Hub-Cloud"
}

// ─── Hubdrive Extractor ─────────────────────────────────────────────────────

class OlaHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = browserHeaders).document
            val header = doc.select("div.card-header").text()
            val quality = getIndexQuality(header)
            val labelExtras = if (header.isNotEmpty()) "[$header]" else ""
            val ref = referer.orEmpty()

            doc.select("a.btn, .btn-success, a[href*=download]").amap { element ->
                val btnLink = element.attr("href")
                val text = element.ownText().lowercase()
                val label = when {
                    text.contains("fsl") -> "$ref [FSL]"
                    text.contains("download file") || text.contains("direct") -> "$ref [Direct]"
                    text.contains("buzz") -> "$ref [Buzz]"
                    text.contains("pixeldra") -> "$ref [PixelDrain]"
                    text.contains("mega") -> "$ref [Mega]"
                    text.contains("10gbps") -> "$ref [10Gbps]"
                    else -> "$ref [Download]"
                }
                callback.invoke(newExtractorLink(
                    label, "$label $labelExtras", btnLink, ExtractorLinkType.VIDEO
                ) { this.quality = quality })
            }
        } catch (e: Exception) {
            Log.e("OlaHubdrive", "Failed: ${e.message}")
        }
    }
}

// ─── Hubstream Extractor ────────────────────────────────────────────────────

class OlaHubstream : ExtractorApi() {
    override val name = "Hubstream"
    override val mainUrl = "https://hubstream.art"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = browserHeaders).document
            val header = doc.select("div.card-header").text()
            val quality = getIndexQuality(header)
            val labelExtras = if (header.isNotEmpty()) "[$header]" else ""
            val ref = referer.orEmpty()

            doc.select("a.btn, .btn-success, a[href*=download]").amap { element ->
                val btnLink = element.attr("href")
                callback.invoke(newExtractorLink(
                    "$ref [Hubstream]", "$ref [Hubstream] $labelExtras", btnLink, ExtractorLinkType.VIDEO
                ) { this.quality = quality })
            }
        } catch (e: Exception) {
            Log.e("OlaHubstream", "Failed: ${e.message}")
        }
    }
}

// ─── VidStack Extractor (with AES decryption) ──────────────────────────────

open class OlaVidStack : ExtractorApi() {
    override val name = "VidStack"
    override val mainUrl = "https://vidstack.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = browserHeaders).document
            val scriptContent = doc.select("script").map { it.html() }
                .firstOrNull { it.contains("crypto") || it.contains("decrypt") || it.contains("aes") }
                ?: return

            // Try to find AES encrypted data
            val encryptedData = Regex("""data:\s*["']([A-Za-z0-9+/=]+)["']""").find(scriptContent)?.groupValues?.get(1) ?: return
            val key = Regex("""key:\s*["']([A-Za-z0-9+/=]+)["']""").find(scriptContent)?.groupValues?.get(1) ?: return
            val iv = Regex("""iv:\s*["']([A-Za-z0-9+/=]+)["']""").find(scriptContent)?.groupValues?.get(1) ?: ""

            val decrypted = aesDecrypt(encryptedData, key, iv) ?: return
            val finalUrl = Regex("""(https?://[^\s"']+)""").find(decrypted)?.groupValues?.get(1) ?: return

            callback.invoke(newExtractorLink(
                "VidStack", "VidStack", finalUrl, INFER_TYPE
            ))
        } catch (e: Exception) {
            Log.e("OlaVidStack", "Failed: ${e.message}")
        }
    }

    private fun aesDecrypt(data: String, key: String, iv: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(android.util.Base64.decode(key, android.util.Base64.DEFAULT), "AES")
            val ivSpec = IvParameterSpec(android.util.Base64.decode(iv, android.util.Base64.DEFAULT))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            null
        }
    }
}

// ─── PixelDrain Extractor ──────────────────────────────────────────────────

class OlaPixelDrainDev : PixelDrain() {
    override val mainUrl = "https://pixeldrain.com"
    override val name = "PixelDrain"
}

// ─── GDFlix Extractor ──────────────────────────────────────────────────────

open class OlaGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new.gdflix.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = browserHeaders).document

            // Try direct download link from the page
            val downloadLink = doc.selectFirst("a[href*=download], a.btn-success")?.attr("href") ?: ""

            if (downloadLink.isNotBlank()) {
                val finalUrl = if (downloadLink.startsWith("http")) downloadLink
                               else getBaseUrl(url) + downloadLink
                callback.invoke(newExtractorLink(
                    name, name, finalUrl, ExtractorLinkType.VIDEO
                ))
            } else {
                // Try resolving via redirect
                val resolved = getRedirectLinks(url)
                if (resolved != null && resolved.startsWith("http")) {
                    callback.invoke(newExtractorLink(
                        name, name, resolved, ExtractorLinkType.VIDEO
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e("OlaGDFlix", "Failed: ${e.message}")
        }
    }
}

// GDFlix domain variants
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
