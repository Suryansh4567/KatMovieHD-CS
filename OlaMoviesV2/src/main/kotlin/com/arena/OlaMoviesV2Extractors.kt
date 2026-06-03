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

private val desktopHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.5",
    "Referer" to "https://v2.olamovies.mov/",
    "Connection" to "keep-alive"
)

/** Known destination hosts that OlaMovies short links resolve to */
private val KNOWN_HOSTS = listOf(
    "hubcloud", "gdflix", "gdtot", "drive.google", "gofile",
    "pixeldrain", "hubdrive", "hubstream", "hubcdn", "katdrive",
    "olamovies.dad", "space.olamovies", "gdmirrorbot", "gd-flix",
    "gdlink", "gdmirror", "1fichier", "send.cm", "mediafire",
    "gdtot", "fuckingfast", "fastdl", "driveseed", "driveleech",
    "bbupload", "filepress", "vidstack", "doodstream", "mixdrop",
    "streamtape", "filemoon", "streamlare", "krakenfiles",
    "filelions", "streamhide", "streamwish", "vidhide",
    "busycdn", "goflix", "zfile", "workers.dev",
    "awscdn", "googleusercontent", "megadb", "shrdsk"
)

/** Intermediate shortener domains (Anylinks.in network) */
private val INTERMEDIATE_HOSTS = listOf(
    "ukrupdate.com", "mastkhabre.com", "aryx.xyz",
    "anylinks.in", "rocklinks.net", "dulink.net",
    "ez4short.com", "olamovies.mov", "links.olamovies.mov",
    "links.ol-am.top", "ol-am.top"
)

private fun isKnownHost(url: String): Boolean =
    KNOWN_HOSTS.any { url.contains(it, ignoreCase = true) }

private fun isIntermediateHost(url: String): Boolean =
    INTERMEDIATE_HOSTS.any { url.contains(it, ignoreCase = true) }

// ─── OlaLinks — Shortener Extractor (PROFESSIONAL REWRITE v3) ──────────────────

/**
 * Extractor for links.ol-am.top / links.olamovies.mov — OlaMovies'
 * Cloudflare-protected link shortener.
 *
 * The resolution chain is:
 *   1. links.ol-am.top/{code} → 301 → links.olamovies.mov/{code}
 *   2. links.olamovies.mov → CF Turnstile challenge → Anylinks.in page
 *   3. Anylinks.in multi-step (btn6 clicks) → ~30s delay → final host
 *
 * Resolution strategies (in order):
 *   1. Direct GET — CloudStream's OkHttp handles CF bypass, check response.url
 *   2. Scrape page HTML for meta-refresh, JS redirects, known host links
 *   3. Manual HTTP redirect chain following (allowRedirects=false)
 *   4. HDHub4U-style JS deobfuscation (getRedirectLinks)
 *   5. Intermediate shortener resolution (Anylinks/rocklinks/dulink)
 *   6. Full page text regex search for known host URLs
 *   7. Retry with desktop Firefox UA + longer timeout
 *   8. loadExtractor fallback (CloudStream built-in CF bypass)
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

            // Strategy 1: Direct GET with OlaMovies referer — check response.url
            try {
                val response = app.get(url, headers = olaHeaders, timeout = 30_000L)
                val finalUrl = response.url

                // Check if CloudStream's OkHttp followed through CF and we landed on a known host
                if (finalUrl != url && isKnownHost(finalUrl)) {
                    Log.d("OlaLinks", "S1 (response.url): $url -> $finalUrl")
                    dispatchResolved(finalUrl, subtitleCallback, callback)
                    return
                }

                // If we got a real page (not CF challenge), try scraping it
                if (response.code == 200) {
                    val doc = response.document
                    val scraped = scrapePageForLinks(doc, url)
                    if (scraped != null) {
                        Log.d("OlaLinks", "S1 (page scrape): $url -> $scraped")
                        dispatchResolved(scraped, subtitleCallback, callback)
                        return
                    }

                    // Check if we landed on an intermediate shortener page
                    if (isIntermediateHost(finalUrl) || isIntermediateHost(url)) {
                        val intermediateResolved = resolveIntermediatePage(doc, finalUrl, url)
                        if (intermediateResolved != null && isKnownHost(intermediateResolved)) {
                            Log.d("OlaLinks", "S1 (intermediate): $url -> $intermediateResolved")
                            dispatchResolved(intermediateResolved, subtitleCallback, callback)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S1 failed: ${e.message}")
            }

            // Strategy 2: Manual redirect chain following
            try {
                val redirectChain = followAllRedirects(url)
                if (redirectChain != null && isKnownHost(redirectChain)) {
                    Log.d("OlaLinks", "S2 (redirect chain): $url -> $redirectChain")
                    dispatchResolved(redirectChain, subtitleCallback, callback)
                    return
                }
                // Maybe we got stuck at an intermediate — try resolving it
                if (redirectChain != null && isIntermediateHost(redirectChain)) {
                    Log.d("OlaLinks", "S2 (stuck at intermediate): $redirectChain")
                    val interResult = resolveIntermediateUrl(redirectChain)
                    if (interResult != null && isKnownHost(interResult)) {
                        Log.d("OlaLinks", "S2 (intermediate resolved): $interResult")
                        dispatchResolved(interResult, subtitleCallback, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S2 failed: ${e.message}")
            }

            // Strategy 3: getRedirectLinks for JS obfuscation (HDHub4U-style)
            try {
                val redirectResolved = getRedirectLinks(url)
                if (redirectResolved != null && redirectResolved != url) {
                    if (isKnownHost(redirectResolved)) {
                        Log.d("OlaLinks", "S3 (JS deobfuscation): $url -> $redirectResolved")
                        dispatchResolved(redirectResolved, subtitleCallback, callback)
                        return
                    } else if (isIntermediateHost(redirectResolved)) {
                        val interResult = resolveIntermediateUrl(redirectResolved)
                        if (interResult != null && isKnownHost(interResult)) {
                            Log.d("OlaLinks", "S3 (intermediate): $interResult")
                            dispatchResolved(interResult, subtitleCallback, callback)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S3 failed: ${e.message}")
            }

            // Strategy 4: Fetch the shortener page and try to find the "go" link
            // Many shorteners have a form/button that POSTs to get the real link
            try {
                val pageResult = extractShortenerLink(url)
                if (pageResult != null && isKnownHost(pageResult)) {
                    Log.d("OlaLinks", "S4 (shortener extract): $url -> $pageResult")
                    dispatchResolved(pageResult, subtitleCallback, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S4 failed: ${e.message}")
            }

            // Strategy 5: Desktop Firefox UA with longer timeout + full text search
            try {
                val response2 = app.get(url, headers = desktopHeaders, timeout = 60_000L)
                val pageText = response2.text

                // Search for known host URLs in the raw page content
                for (host in KNOWN_HOSTS) {
                    val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
                    val found = urlRegex.find(pageText)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
                    if (!found.isNullOrBlank() && found.startsWith("http")) {
                        Log.d("OlaLinks", "S5 (text search): found $found")
                        dispatchResolved(found, subtitleCallback, callback)
                        return
                    }
                }

                // Also try scraping the document
                if (response2.code == 200) {
                    val doc2 = response2.document
                    val scraped2 = scrapePageForLinks(doc2, url)
                    if (scraped2 != null) {
                        Log.d("OlaLinks", "S5 (scrape): $url -> $scraped2")
                        dispatchResolved(scraped2, subtitleCallback, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S5 failed: ${e.message}")
            }

            // Strategy 6: Try resolving via the other shortener domain
            // If we got links.ol-am.top, try links.olamovies.mov and vice versa
            try {
                val altUrl = when {
                    url.contains("links.ol-am.top", ignoreCase = true) ->
                        url.replace("links.ol-am.top", "links.olamovies.mov")
                    url.contains("links.olamovies.mov", ignoreCase = true) ->
                        url.replace("links.olamovies.mov", "links.ol-am.top")
                    else -> null
                }
                if (altUrl != null && altUrl != url) {
                    Log.d("OlaLinks", "S6: trying alternate domain $altUrl")
                    val altResponse = app.get(altUrl, headers = olaHeaders, timeout = 30_000L)
                    if (altResponse.url != altUrl && isKnownHost(altResponse.url)) {
                        Log.d("OlaLinks", "S6 (alt domain): $altUrl -> ${altResponse.url}")
                        dispatchResolved(altResponse.url, subtitleCallback, callback)
                        return
                    }
                    if (altResponse.code == 200) {
                        val scraped = scrapePageForLinks(altResponse.document, altUrl)
                        if (scraped != null && isKnownHost(scraped)) {
                            Log.d("OlaLinks", "S6 (alt scrape): $scraped")
                            dispatchResolved(scraped, subtitleCallback, callback)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S6 failed: ${e.message}")
            }

            // Strategy 7: Last resort — try loadExtractor which uses CloudStream's built-in CF bypass
            try {
                Log.w("OlaLinks", "All strategies exhausted for $url, trying loadExtractor as last resort")
                loadExtractor(url, "https://v2.olamovies.mov/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("OlaLinks", "Every strategy failed for $url: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("OlaLinks", "Fatal error for $url: ${e.message}")
        }
    }

    /**
     * Follow all HTTP redirects step by step, collecting the chain.
     * Returns the final URL if it's a known host, or the last URL in chain.
     */
    private suspend fun followAllRedirects(startUrl: String): String? {
        var currentUrl = startUrl
        var loops = 0
        val maxLoops = 10
        val visited = mutableSetOf<String>()

        while (loops < maxLoops) {
            if (currentUrl in visited) break
            visited.add(currentUrl)

            try {
                val res = app.get(currentUrl, headers = olaHeaders, allowRedirects = false, timeout = 10_000L)
                when {
                    res.code in 300..399 -> {
                        val location = res.headers["Location"] ?: res.headers["location"]
                        if (location.isNullOrBlank()) break
                        currentUrl = if (location.startsWith("http")) location
                                     else getBaseUrl(currentUrl) + location
                        if (isKnownHost(currentUrl)) return currentUrl
                    }
                    res.code == 200 -> {
                        // Check page content for redirect
                        val doc = res.document
                        val scraped = scrapePageForLinks(doc, currentUrl)
                        if (scraped != null) return scraped
                        break // No more redirects, stuck at this page
                    }
                    res.code == 403 -> {
                        // CF challenge — try to extract any useful URLs from the challenge page
                        val pageText = res.text
                        for (host in KNOWN_HOSTS) {
                            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
                            val found = urlRegex.find(pageText)?.value?.trimEnd('\\', ',', '"', '\'', ')')
                            if (!found.isNullOrBlank()) return found
                        }
                        break
                    }
                    else -> break
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "followAllRedirects error at $currentUrl: ${e.message}")
                break
            }
            loops++
        }
        return if (currentUrl != startUrl) currentUrl else null
    }

    /**
     * Extract the real link from an intermediate shortener page.
     * These pages typically have:
     *   - A "btn6" or "get-link" button that leads to the next step
     *   - JavaScript countdown timers
     *   - Form submissions with tokens
     */
    private suspend fun resolveIntermediatePage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        originalUrl: String
    ): String? {
        val html = doc.toString()

        // Try finding known host URLs directly in the page
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
            val found = urlRegex.find(html)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
            if (!found.isNullOrBlank() && found.startsWith("http")) return found
        }

        // Look for btn6/get-link button href
        val btnLink = doc.selectFirst("a.btn6, a#get-link, a[href*='get-link'], a[href*='go.php']")
            ?.attr("href")
        if (!btnLink.isNullOrBlank()) {
            val fullLink = if (btnLink.startsWith("http")) btnLink
                           else getBaseUrl(pageUrl) + btnLink
            if (isKnownHost(fullLink)) return fullLink

            // Follow the btn link to the next step
            try {
                val btnResponse = app.get(fullLink, headers = olaHeaders, timeout = 15_000L)
                val btnDoc = btnResponse.document
                val btnHtml = btnDoc.toString()
                for (host in KNOWN_HOSTS) {
                    val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
                    val found = urlRegex.find(btnHtml)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
                    if (!found.isNullOrBlank() && found.startsWith("http")) return found
                }
            } catch (_: Exception) {}
        }

        // Look for base64 encoded URLs in script tags
        val base64Pattern = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")
        base64Pattern.findAll(html).forEach { match ->
            try {
                val decoded = base64Decode(match.value)
                if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Resolve an intermediate shortener URL by fetching and parsing its page.
     */
    private suspend fun resolveIntermediateUrl(url: String): String? {
        return try {
            val response = app.get(url, headers = olaHeaders, timeout = 20_000L)
            if (response.code == 200) {
                resolveIntermediatePage(response.document, response.url, url)
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Extract the target link from a shortener page.
     * Handles common patterns like:
     *   - Form with hidden input fields containing the real URL
     *   - JavaScript that sets window.location after a delay
     *   - Base64 encoded URLs in data attributes
     */
    private suspend fun extractShortenerLink(url: String): String? {
        return try {
            val response = app.get(url, headers = olaHeaders, timeout = 20_000L)
            val doc = response.document
            val html = doc.toString()

            // Look for form action URLs with hidden inputs
            val formAction = doc.selectFirst("form[action]")?.attr("action")
            val hiddenInput = doc.selectFirst("input[type=hidden][name=url], input[type=hidden][name=link], input[type=hidden][name=go]")
            if (!formAction.isNullOrBlank() && hiddenInput != null) {
                val hiddenValue = hiddenInput.attr("value")
                if (hiddenValue.isNotBlank() && hiddenValue.startsWith("http") && isKnownHost(hiddenValue)) {
                    return hiddenValue
                }
            }

            // Look for data attributes with URLs
            for (element in doc.select("[data-url], [data-href], [data-link]")) {
                val dataUrl = element.attr("data-url").ifBlank { element.attr("data-href") }.ifBlank { element.attr("data-link") }
                if (dataUrl.isNotBlank()) {
                    val decoded = if (dataUrl.startsWith("http")) dataUrl else try { base64Decode(dataUrl) } catch (_: Exception) { dataUrl }
                    if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
                }
            }

            // Look for URL in JavaScript variables
            val jsUrlPatterns = listOf(
                Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+redirect\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""window\.location\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""href\s*=\s*['"](https?://[^'"]+)['"]""")
            )
            for (pattern in jsUrlPatterns) {
                val found = pattern.find(html)?.groupValues?.get(1)?.trim()
                if (!found.isNullOrBlank() && found.startsWith("http") && isKnownHost(found)) {
                    return found
                }
            }

            null
        } catch (_: Exception) { null }
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
            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
            val found = urlRegex.find(pageText)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
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
            url.contains("gofile", ignoreCase = true) -> {
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

// ─── HubCloud Extractor (FIXED — uses lol TLD for matching) ─────────────────

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
    override val mainUrl = "https://hubstream.blog"
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
