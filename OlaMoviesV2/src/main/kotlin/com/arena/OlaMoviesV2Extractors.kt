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
import kotlinx.coroutines.delay
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
    "filegram", "gdlink", "gdmirror",
    "gdbot", "drivebot", "drivehub"
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
    "bestloansoffers.com", "worldzc.com", "earningtime.in",
    "crazyblog.in", "ser2.crazyblog", "ser3.crazyblog"
)

private fun isKnownHost(url: String): Boolean =
    KNOWN_HOSTS.any { url.contains(it, ignoreCase = true) }

private fun isIntermediateHost(url: String): Boolean =
    INTERMEDIATE_HOSTS.any { url.contains(it, ignoreCase = true) }

/** Check if a URL is an ad shortener that needs bypass */
private fun isAdShortener(url: String): Boolean {
    val adHosts = listOf("dulink", "ez4short", "rocklinks", "crazyblog",
        "v2links", "bestloansoffers", "worldzc", "earningtime")
    return adHosts.any { url.contains(it, ignoreCase = true) }
}

// ─── OlaLinks — Shortener chain resolver (v18 — SIMPLIFIED) ────────────────────

/**
 * OlaMovies shortener chain resolver — v18.
 *
 * v18 KEY CHANGE: Removed broken external CF bypass APIs (bypass.city DOWN,
 * emilyx unreliable). Instead, use app.get() DIRECTLY and let CloudStream's
 * built-in CloudflareInterceptor handle CF challenges by showing a WebView
 * popup for the user to solve. This is the ONLY reliable CF bypass from a plugin.
 *
 * The link chain:
 *   1. Movie page has `links.ol-am.top/XXXXX` links
 *   2. links.ol-am.top/XXXXX → 301 → links.olamovies.mov/XXXXX (CF Turnstile)
 *   3. After CF solved: page with links to intermediate sites
 *   4. Intermediate sites (ukrupdate/mastkhabre/aryx) → Anylinks.in → #btn6/#tp98 click
 *   5. Ad shorteners (dulink/ez4short/rocklinks/crazyblog) → final host
 *   6. Final host: HubCloud/GDFlix/etc. → dispatchFinalHost()
 *
 * Strategies (v18):
 *   PRIMARY: followSimpleChain() — simple app.get() chain, CF handled by CloudStream
 *   FALLBACK1: bypassOlaRedirect + bypassAdLinks (for keyed URLs)
 *   FALLBACK2: Aggressive HTML scraping (last resort)
 */
open class OlaLinks : ExtractorApi() {
    override val name = "OlaLinks"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "OlaLinks"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 800L
    }

    /**
     * Resolve the OlaMovies shortener chain.
     * Returns true if at least one playable link was found.
     *
     * CRITICAL: Do NOT call loadExtractor() on the input URL — it would
     * route back to this class and cause infinite recursion!
     */
    suspend fun resolve(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false
        val ref = referer ?: "https://v2.olamovies.mov/"

        try {
            Log.d(TAG, "═══ RESOLVE START v18: $url ═══")

            // ── PRIMARY: Simple app.get() chain ──
            // CloudStream's built-in CloudflareInterceptor will detect CF challenges
            // and show a WebView popup for the user to solve. This is the ONLY reliable
            // way to bypass CF Turnstile from a plugin.
            try {
                Log.d(TAG, "→ [PRIMARY] Simple app.get() chain")
                val result = followSimpleChain(url, ref, subtitleCallback, callback)
                if (result) {
                    anySuccess = true
                    Log.d(TAG, "  [PRIMARY] ✓ chain resolved successfully")
                }
            } catch (e: Exception) {
                Log.d(TAG, "✗ [PRIMARY] failed: ${e.message}")
            }

            // ── FALLBACK 1: bypassOlaRedirect (handles keyed URLs) ──
            if (!anySuccess) try {
                Log.d(TAG, "→ [FALLBACK1] bypassOlaRedirect")
                val resolvedLinks = bypassOlaRedirect(url, ref)
                Log.d(TAG, "  [FALLBACK1] returned ${resolvedLinks.size} link(s)")

                for (shortLink in resolvedLinks) {
                    val finalUrl = when {
                        isKnownHost(shortLink) -> shortLink
                        isIntermediateHost(shortLink) -> {
                            val intResult = bypassIntermediateSite(shortLink)
                            if (intResult != null && isAdShortener(intResult)) bypassAdLinks(intResult) ?: intResult
                            else intResult
                        }
                        isAdShortener(shortLink) -> bypassAdLinks(shortLink) ?: shortLink
                        else -> shortLink
                    }
                    if (finalUrl != null && isKnownHost(finalUrl)) {
                        dispatchFinalHost(finalUrl, subtitleCallback, callback)
                        anySuccess = true
                    } else if (finalUrl != null && finalUrl.startsWith("http") && !isOwnUrl(finalUrl)) {
                        try {
                            loadExtractor(finalUrl, ref, subtitleCallback, callback)
                            anySuccess = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "✗ [FALLBACK1] failed: ${e.message}")
            }

            // ── FALLBACK 2: Aggressive scrape (last resort) ──
            if (!anySuccess) try {
                Log.d(TAG, "→ [FALLBACK2] Aggressive scrape")
                val scrapedLinks = aggressiveScrape(url, ref)
                for (link in scrapedLinks) {
                    if (isKnownHost(link)) {
                        dispatchFinalHost(link, subtitleCallback, callback)
                        anySuccess = true
                    } else if (link.startsWith("http") && !isOwnUrl(link)) {
                        try {
                            loadExtractor(link, ref, subtitleCallback, callback)
                            anySuccess = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "✗ [FALLBACK2] failed: ${e.message}")
            }

            if (!anySuccess) {
                Log.w(TAG, "═══ ALL STRATEGIES FAILED for $url ═══")
            } else {
                Log.d(TAG, "═══ RESOLVE SUCCESS for $url ═══")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FATAL error for $url: ${e.message}")
        }

        return anySuccess
    }

    /**
     * Follow the shortener chain step by step using simple app.get() calls.
     * CloudStream's built-in CloudflareInterceptor handles CF challenges automatically.
     */
    private suspend fun followSimpleChain(
        startUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = mutableSetOf<String>()
        var currentUrl = startUrl
        val maxSteps = 10

        repeat(maxSteps) { step ->
            if (currentUrl in visited) return false
            visited.add(currentUrl)

            Log.d(TAG, "Chain step ${step + 1}/$maxSteps: $currentUrl")

            // Already at a known host?
            if (isKnownHost(currentUrl)) {
                dispatchFinalHost(currentUrl, subtitleCallback, callback)
                return true
            }

            // Intermediate site? Use bypassIntermediateSite()
            if (isIntermediateHost(currentUrl)) {
                Log.d(TAG, "  → intermediate site, using bypassIntermediateSite()")
                val result = bypassIntermediateSite(currentUrl)
                if (result != null) {
                    if (isKnownHost(result)) {
                        dispatchFinalHost(result, subtitleCallback, callback)
                        return true
                    }
                    currentUrl = result
                    return@repeat
                }
                return false
            }

            // Ad shortener? Use bypassAdLinks()
            if (isAdShortener(currentUrl)) {
                Log.d(TAG, "  → ad shortener, using bypassAdLinks()")
                val result = bypassAdLinks(currentUrl)
                if (result != null) {
                    if (isKnownHost(result)) {
                        dispatchFinalHost(result, subtitleCallback, callback)
                        return true
                    }
                    currentUrl = result
                    return@repeat
                }
                return false
            }

            // Try to fetch the page - CloudStream's CloudflareInterceptor handles CF
            try {
                val response = app.get(currentUrl, headers = olaHeaders, referer = referer, timeout = 30_000L)
                val doc = response.document
                val finalUrl = response.url

                // Check if CloudStream followed through to a known host
                if (finalUrl != currentUrl && isKnownHost(finalUrl)) {
                    dispatchFinalHost(finalUrl, subtitleCallback, callback)
                    return true
                }

                // If redirected to a different URL, continue from there
                if (finalUrl != currentUrl && finalUrl !in visited) {
                    currentUrl = finalUrl
                    return@repeat
                }

                // Try to find the next link in the page
                val nextLink = extractNextLink(doc, finalUrl)
                if (nextLink != null) {
                    currentUrl = nextLink
                    return@repeat
                }

                // No next link found - check if it's a generator page
                val genResult = resolveOlaGeneratePage(doc, finalUrl)
                if (genResult != null) {
                    if (isKnownHost(genResult)) {
                        dispatchFinalHost(genResult, subtitleCallback, callback)
                        return true
                    }
                    currentUrl = genResult
                    return@repeat
                }

                // No more links found - dead end
                Log.d(TAG, "  → dead end at step ${step + 1}")
                return false

            } catch (e: Exception) {
                Log.d(TAG, "  → fetch failed: ${e.message}")
                return false
            }
        }

        return false
    }

    /**
     * Extract the next link from a page in the shortener chain.
     * Checks common patterns used by OlaMovies shortener pages.
     */
    private fun extractNextLink(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        // Pattern 1: #download > a (primary pattern)
        doc.selectFirst("#download > a")?.attr("href")?.trim()?.let {
            if (it.startsWith("http")) return it
            if (it.startsWith("/") && it.length > 2) return getBaseUrl(baseUrl) + it
        }

        // Pattern 2: #btn6 (Anylinks pattern)
        doc.selectFirst("#btn6")?.let { btn ->
            btn.attr("href")?.trim()?.let { if (it.startsWith("http")) return it }
            for (attr in listOf("data-url", "data-href", "data-link")) {
                btn.attr(attr)?.trim()?.let { if (it.startsWith("http")) return it }
            }
        }

        // Pattern 3: #tp98
        doc.selectFirst("#tp98")?.attr("href")?.trim()?.let {
            if (it.startsWith("http")) return it
        }

        // Pattern 4: ?key= link
        doc.selectFirst("a[href*='?key=']")?.attr("href")?.trim()?.let {
            return if (it.startsWith("http")) it else getBaseUrl(baseUrl) + it
        }

        // Pattern 5: data-url/data-href on any element
        for (attr in listOf("data-url", "data-href", "data-link", "data-go")) {
            doc.selectFirst("[$attr]")?.attr(attr)?.trim()?.let {
                if (it.startsWith("http")) return it
            }
        }

        // Pattern 6: onclick handlers
        doc.select("[onclick]").forEach { el ->
            val handler = el.attr("onclick").trim()
            Regex("""(?:window\.open|location\.href|location)\s*\(\s*['"]([^'"]+)['"]""")
                .find(handler)?.groupValues?.get(1)?.let { return it }
        }

        // Pattern 7: JS variables
        val html = doc.toString()
        val jsPatterns = listOf(
            Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+currentLink\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+redirect\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+pxl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
        )
        for (pattern in jsPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.trim()?.let { value ->
                if (value.startsWith("http")) return value
                // Try base64 decode
                try {
                    val decoded = base64Decode(value)
                    if (decoded.startsWith("http")) return decoded
                } catch (_: Exception) {}
            }
        }

        // Pattern 8: Any <a> tag linking to known host or intermediate
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.startsWith("http") && (isKnownHost(href) || isIntermediateHost(href) || isAdShortener(href))) {
                return href
            }
        }

        return null
    }

    /** Check if URL belongs to OlaMovies shortener (prevents recursion) */
    private fun isOwnUrl(url: String): Boolean {
        return url.contains("ol-am.top", ignoreCase = true) ||
               url.contains("links.olamovies.mov", ignoreCase = true) ||
               url.contains("olamovies.download", ignoreCase = true)
    }

    // Keep getUrl() for backward compatibility but it just calls resolve()
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolve(url, referer, subtitleCallback, callback)
    }

    /**
     * ULTRA AGGRESSIVE SCRAPER — scans the ENTIRE page HTML for ALL possible links.
     * This is Phase 2.3 MAXED OUT — catches EVERY possible URL pattern:
     *
     * 17 scraping patterns (v14 SAB FIX — includes generator page scraping):
     *   1. #download > a (LikDev's primary pattern)
     *   2. ?key= links (OlaMovies redirect chain)
     *   3. Anylinks-specific: #btn6, #tp98, #go-link
     *   4. button.inline-flex + ALL data-* attributes on ALL elements
     *   5. onclick / onsubmit handlers (window.open, location.href)
     *   6. Forms — action URLs + hidden input values
     *   7. JS variables (var url=, currentLink=, link=, redirect=, pxl=, base64)
     *   8. JS fetch/XMLHttpRequest URLs
     *   9. Meta refresh
     *   10. window.location / location.replace
     *   11. All <a> tags with http hrefs (known/intermediate/ad)
     *   12. All <iframe> src (some pages embed the final URL in an iframe)
     *   13. All <script> src (CDN-hosted redirect scripts)
     *   14. Raw HTML regex scan for ALL known host URLs
     *   15. Follow intermediate links one more level + scrape there too
     */
    private suspend fun aggressiveScrape(startUrl: String, referer: String): List<String> {
        val found = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addLink(url: String) {
            if (url.startsWith("http") && url !in seen) { seen.add(url); found.add(url) }
        }

        fun addMaybeRelative(url: String, base: String) {
            if (url.isBlank()) return
            val full = if (url.startsWith("http")) url else getBaseUrl(base) + url
            addLink(full)
        }

        // Try to fetch the page with retries
        val fetchResult = retryFetch(startUrl, referer) ?: return found
        val doc = fetchResult.first
        val html = doc.toString()
        val baseUrl = getBaseUrl(fetchResult.second)

        // 1. #download > a (LikDev's primary pattern)
        doc.select("#download > a, #download a, div#download a").forEach { addLink(it.attr("href").trim()) }

        // 2. ?key= links (OlaMovies redirect chain pages)
        doc.select("a[href*='?key=']").forEach { addMaybeRelative(it.attr("href").trim(), baseUrl) }
        // Also find ?key= in raw HTML (sometimes hidden in JS)
        Regex("""[?&]key=([^&"'\s<>]+)""").findAll(html).forEach { match ->
            val keyVal = match.groupValues[1]
            if (keyVal.isNotBlank()) {
                // Try to construct the full keyed URL
                Regex("""(https?://[^\s"'<>]+[?&]key=${Regex.escape(keyVal)}[^\s"'<>]*)""")
                    .find(html)?.groupValues?.get(1)?.let { addLink(it.trim()) }
            }
        }

        // 3. Anylinks-specific: #btn6, #tp98, #go-link, .btn-download
        doc.select("#btn6, #tp98, #go-link, .btn-download, .download-btn, a.go-link").forEach {
            addLink(it.attr("href").trim())
        }

        // 4. ALL data-* attributes on ALL elements (not just buttons!)
        // This catches data-url, data-href, data-link, data-go, data-target, data-redirect, etc.
        doc.select("[data-url], [data-href], [data-link], [data-go], [data-target], [data-redirect], [data-url-download]").forEach { el ->
            for (attr in listOf("data-url", "data-href", "data-link", "data-go", "data-target", "data-redirect", "data-url-download")) {
                el.attr(attr)?.trim()?.let { addMaybeRelative(it, baseUrl) }
            }
        }

        // 5. onclick / onsubmit handlers
        doc.select("[onclick], [onsubmit]").forEach { el ->
            val handler = (el.attr("onclick").ifBlank { el.attr("onsubmit") }).trim()
            if (handler.isNotBlank()) {
                // window.open('...'), location.href='...', location='...'
                val onclickPatterns = listOf(
                    Regex("""(?:window\.open|location\.href|location\.replace|location)\s*\(\s*['"]([^'"]+)['"]"""),
                    Regex("""['"]([^'"]*(?:hubcloud|gdflix|pixeldrain|drive\.google|gofile|gdtot|hubdrive|hubstream)[^'"]*)['"]"""),
                    // v14: broad ANY-http URL in onclick
                    Regex("""['"]?(https?://[^'"\s]+)['"]?""")
                )
                for (pattern in onclickPatterns) {
                    pattern.findAll(handler).forEach { match ->
                        val val_ = match.groupValues[1].trim()
                        if (val_.startsWith("http")) addLink(val_)
                    }
                }
            }
        }

        // 6. Forms — action URLs + hidden input values
        doc.select("form[action]").forEach { form ->
            addMaybeRelative(form.attr("action").trim(), baseUrl)
            // Hidden inputs sometimes contain redirect URLs
            form.select("input[type=hidden]").forEach { input ->
                val val_ = input.attr("value").trim()
                if (val_.startsWith("http")) addLink(val_)
            }
        }

        // 7. JS variables — v14 expanded list (generator URLs + login/continue vars)
        val jsPatterns = listOf(
            Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+currentLink\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+redirect\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+pxl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+finalUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+downloadUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+target\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+dest\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+next\s*=\s*['"]([^'"]+)['"]"""),
            // v14: generator page JS vars
            Regex("""var\s+loginUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+continueUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+generateUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+nextPage\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+shortUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+goToUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+gotoUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+goUrl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""location\.replace\s*\(\s*['"]([^'"]+)['"]""")
        )
        for (pattern in jsPatterns) {
            pattern.findAll(html).forEach { match ->
                val val_ = match.groupValues[1].trim()
                if (val_.isNotBlank()) {
                    // Try base64 decode
                    val decoded = try { base64Decode(val_) } catch (_: Exception) { val_ }
                    for (candidate in listOf(val_, decoded)) {
                        if (candidate.startsWith("http")) addLink(candidate)
                        // Also try as relative URL
                        else addMaybeRelative(candidate, baseUrl)
                    }
                }
            }
        }

        // 8. JS fetch/XMLHttpRequest URLs
        Regex("""fetch\s*\(\s*['"]([^'"]+)['"]""").findAll(html).forEach { match ->
            addMaybeRelative(match.groupValues[1].trim(), baseUrl)
        }
        Regex("""XMLHttpRequest[^}]*open\s*\(\s*['"]GET['"]\s*,\s*['"]([^'"]+)['"]""").findAll(html).forEach { match ->
            addMaybeRelative(match.groupValues[1].trim(), baseUrl)
        }

        // 9. Meta refresh
        Regex("""(?i)<meta[^>]*http-equiv=["']?refresh["']?[^>]*content=["']?\d+;\s*url=([^"'>\s]+)""")
            .find(html)?.groupValues?.get(1)?.trim()?.let { metaUrl ->
                addMaybeRelative(metaUrl, baseUrl)
            }

        // 10. window.location / location.replace in <script> blocks
        Regex("""location\s*=\s*['"]([^'"]+)['"]""").findAll(html).forEach { match ->
            addMaybeRelative(match.groupValues[1].trim(), baseUrl)
        }

        // 11. All <a> tags with http hrefs (broader filter — ANY external link)
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.startsWith("http") &&
                (isKnownHost(href) || isIntermediateHost(href) || isAdShortener(href) ||
                 !href.contains("olamovies.mov", ignoreCase = true))) {
                // Include: known hosts, intermediates, ad shorteners, AND any non-olamovies external link
                if (href !in seen && !href.contains("/feed", ignoreCase = true) &&
                    !href.contains("/wp-", ignoreCase = true) &&
                    !href.contains("#respond", ignoreCase = true)) {
                    seen.add(href); found.add(href)
                }
            }
        }

        // 12. All <iframe> src — some pages embed final URL in iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) addMaybeRelative(src, baseUrl)
        }

        // 13. All <script> src — sometimes redirect scripts are loaded from CDN
        // (Less likely to have direct URLs, but worth checking for "src" containing known hosts)
        doc.select("script[src]").forEach { script ->
            val src = script.attr("src").trim()
            if (src.startsWith("http") && isKnownHost(src)) addLink(src)
        }

        // 14. Raw HTML regex scan for ALL known host URLs
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*${Regex.escape(host)}[^\s"'<>\\]*""")
            urlRegex.findAll(html).forEach { match ->
                val cleaned = match.value.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')
                addLink(cleaned)
            }
        }

        // Also scan for intermediate host URLs in raw HTML
        for (host in INTERMEDIATE_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*${Regex.escape(host)}[^\s"'<>\\]*""")
            urlRegex.findAll(html).forEach { match ->
                val cleaned = match.value.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')
                addLink(cleaned)
            }
        }

        // 15. Follow any found intermediate links one more level + scrape there too
        val intermediates = found.filter { isIntermediateHost(it) && !isKnownHost(it) }.take(5) // Limit to 5 to avoid too many requests
        for (intermediate in intermediates) {
            try {
                val iResponse = app.get(intermediate, headers = olaHeaders, referer = referer, timeout = 15_000L)
                val iDoc = iResponse.document
                val iHtml = iDoc.toString()
                // Scrape the intermediate page for known host links
                iDoc.select("#download > a, #btn6, #tp98, a[href], iframe[src]").forEach { el ->
                    val href = (el.attr("href").ifBlank { el.attr("src") }).trim()
                    if (href.startsWith("http") && (isKnownHost(href) || isAdShortener(href))) addLink(href)
                }
                // Also scan raw HTML of intermediate page
                for (host in KNOWN_HOSTS) {
                    val urlRegex = Regex("""https?://[^\s"'<>\\]*${Regex.escape(host)}[^\s"'<>\\]*""")
                    urlRegex.findAll(iHtml).forEach { match ->
                        val cleaned = match.value.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')
                        addLink(cleaned)
                    }
                }
            } catch (_: Exception) {}
        }

        // 16. v14: Generator button/text scraping — "Login to Continue", "Please wait" pages
        val generatorTexts = listOf("Continue", "Login", "Generate", "Get Link", "Download", "Proceed", "Go", "Unlock", "Verify")
        try {
            doc.select("button, a, [class*='continue'], [class*='login'], [class*='generate'], [class*='download']").forEach { el ->
                val elText = el.text().trim().lowercase()
                if (generatorTexts.any { elText.contains(it.lowercase()) }) {
                    el.attr("href")?.trim()?.let { href ->
                        if (href.startsWith("http")) addLink(href)
                        else if (href.isNotBlank()) addMaybeRelative(href, baseUrl)
                    }
                    for (attr in listOf("data-url", "data-href", "data-link", "data-go", "data-target", "data-redirect", "data-action")) {
                        el.attr(attr)?.trim()?.let { v ->
                            if (v.startsWith("http")) addLink(v)
                            else if (v.isNotBlank()) addMaybeRelative(v, baseUrl)
                        }
                    }
                    el.attr("onclick")?.trim()?.let { handler ->
                        Regex("""['"]?(https?://[^'"\s]+)['"]?""").findAll(handler).forEach { m ->
                            addLink(m.groupValues[1].trim())
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 17. v14: Broad raw regex on full HTML for JS strings containing URLs
        Regex("""['"](https?://[^'"\s<>]{8,})['"]""").findAll(html).forEach { match ->
            addLink(match.groupValues[1].trim())
        }
        Regex("""['"](/(?:generate|go|download|redirect|continue|unlock)/[^'"\s<>]+)['"]""").findAll(html).forEach { match ->
            addMaybeRelative(match.groupValues[1].trim(), baseUrl)
        }

        Log.d(TAG, "aggressiveScrape found ${found.size} total links (v14 17-pattern scan)")
        return found
    }

    /**
     * Retry fetch — tries up to MAX_RETRIES times with delay.
     * Returns Pair(document, finalUrl) on success, null on failure.
     */
    private suspend fun retryFetch(
        url: String,
        referer: String,
        retries: Int = MAX_RETRIES
    ): Pair<org.jsoup.nodes.Document, String>? {
        repeat(retries) { attempt ->
            try {
                val response = app.get(url, headers = olaHeaders, referer = referer, timeout = 30_000L)
                if (response.code == 200 || response.code in 300..399) {
                    return Pair(response.document, response.url)
                }
                Log.d(TAG, "retryFetch attempt ${attempt + 1}: got HTTP ${response.code}")
            } catch (e: Exception) {
                Log.d(TAG, "retryFetch attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < retries - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * AGGRESSIVE chain follower — GET the URL, follow redirects,
     * check if we land on a known host or a page with links.
     * Max depth 12 by default to avoid infinite loops.
     */
    private suspend fun followChain(startUrl: String, referer: String, maxDepth: Int = 8): String? {
        val visited = mutableSetOf<String>()
        var currentUrl = startUrl

        repeat(maxDepth) { step ->
            if (currentUrl in visited) return null
            visited.add(currentUrl)

            if (isKnownHost(currentUrl)) return currentUrl

            Log.d(TAG, "Chain step ${step + 1}/$maxDepth: $currentUrl")

            val response = try {
                app.get(currentUrl, headers = olaHeaders, referer = referer, timeout = 30_000L)
            } catch (e: Exception) {
                Log.d(TAG, "Chain fetch failed: ${e.message}")
                // Try once more with a small delay
                try {
                    delay(RETRY_DELAY_MS)
                    app.get(currentUrl, headers = olaHeaders, referer = referer, timeout = 30_000L)
                } catch (_: Exception) { return null }
            }

            // Check if CloudStream followed through to a known host
            val finalUrl = response.url
            if (finalUrl != currentUrl && isKnownHost(finalUrl)) return finalUrl
            if (finalUrl != currentUrl && isKnownHost(finalUrl)) return finalUrl

            // If we got redirected to a different URL, continue from there
            if (finalUrl != currentUrl && finalUrl !in visited) {
                currentUrl = finalUrl
                return@repeat
            }

            // Check if response is an OlaGenerate page
            if (finalUrl.contains("olamovies.download", ignoreCase = true) ||
                finalUrl.contains("app2.olamovies.download", ignoreCase = true)) {
                val genResult = resolveOlaGeneratePage(response.document, finalUrl)
                if (genResult != null) return genResult
            }

            // Try scraping the page for known host links
            if (response.code == 200) {
                val doc = response.document

                // #download > a (LikDev's primary pattern)
                val downloadHref = doc.selectFirst("#download > a")?.attr("href")?.trim()
                if (!downloadHref.isNullOrBlank() && downloadHref.startsWith("http")) {
                    if (isKnownHost(downloadHref)) return downloadHref
                    if (downloadHref !in visited) { currentUrl = downloadHref; return@repeat }
                }

                // Anylinks #btn6
                val btn6Href = doc.selectFirst("#btn6")?.attr("href")?.trim()
                if (!btn6Href.isNullOrBlank() && btn6Href.startsWith("http")) {
                    if (isKnownHost(btn6Href)) return btn6Href
                    if (btn6Href !in visited) { currentUrl = btn6Href; return@repeat }
                }

                // Anylinks #tp98
                val tp98Href = doc.selectFirst("#tp98")?.attr("href")?.trim()
                if (!tp98Href.isNullOrBlank() && tp98Href.startsWith("http")) {
                    if (isKnownHost(tp98Href)) return tp98Href
                    if (tp98Href !in visited) { currentUrl = tp98Href; return@repeat }
                }

                // button.inline-flex (OlaGenerate pattern)
                val inlineBtn = doc.selectFirst("button.inline-flex")
                if (inlineBtn != null) {
                    val genResult = resolveOlaGeneratePage(doc, finalUrl)
                    if (genResult != null) return genResult
                }

                // data-url, data-href on any element
                for (attr in listOf("data-url", "data-href", "data-link", "data-go")) {
                    val attrVal = doc.selectFirst("[$attr]")?.attr(attr)?.trim()
                    if (!attrVal.isNullOrBlank() && attrVal.startsWith("http")) {
                        if (isKnownHost(attrVal)) return attrVal
                        if (attrVal !in visited) { currentUrl = attrVal; return@repeat }
                    }
                }

                // Scrape all <a> tags for known host URLs
                for (anchor in doc.select("a[href]")) {
                    val href = anchor.attr("href").trim()
                    if (href.isNotBlank() && isKnownHost(href)) return href
                }

                // Search page text for known host URLs
                findKnownHostUrl(doc.toString())?.let { return it }

                // Check for ?key= links (Ola redirect chain continuation)
                val keyLinks = doc.select("a[href*='?key=']").mapNotNull { it.attr("href").trim().takeIf { h -> h.isNotBlank() } }
                for (keyLink in keyLinks) {
                    val full = if (keyLink.startsWith("http")) keyLink else getBaseUrl(finalUrl) + keyLink
                    if (full !in visited) { currentUrl = full; return@repeat }
                }

                // Check for JS variable patterns
                val jsVar = Regex("""var\s+url\s*=\s*'([^']*)'""")
                    .find(doc.selectFirst("script:containsData(url)")?.toString() ?: "")
                    ?.groupValues?.get(1)
                if (!jsVar.isNullOrBlank()) {
                    val resolved = if (jsVar.startsWith("http")) jsVar else getBaseUrl(finalUrl) + jsVar
                    if (isKnownHost(resolved)) return resolved
                    if (resolved !in visited) { currentUrl = resolved; return@repeat }
                }
            }

            // No progress at this step
            // Try one more thing: check if the response URL itself is a known host
            if (isKnownHost(response.url)) return response.url
        }

        return if (isKnownHost(currentUrl)) currentUrl else null
    }

    /** v14: Resolve an OlaGenerate page — enhanced for "Login to Continue" generator pages */
    private fun resolveOlaGeneratePage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): String? {
        // v14: Generator button text detection — "Login to Continue", "Continue", "Generate" etc
        val generatorTexts = listOf("Continue", "Login", "Generate", "Get Link", "Download", "Proceed", "Go", "Unlock")
        val genBtn = doc.select("button, a, [class*='continue'], [class*='login'], [class*='generate']").firstOrNull { el ->
            generatorTexts.any { el.text().contains(it, ignoreCase = true) }
        }
        if (genBtn != null) {
            // Check data-* attrs
            for (attr in listOf("data-url", "data-href", "data-link", "data-go", "data-target", "data-redirect", "data-action")) {
                genBtn.attr(attr)?.trim()?.let { v ->
                    if (v.isNotBlank()) {
                        val targetUrl = if (v.startsWith("http")) v else getBaseUrl(pageUrl) + v
                        if (isKnownHost(targetUrl)) return targetUrl
                        if (isIntermediateHost(targetUrl)) return targetUrl
                    }
                }
            }
            // Check onclick
            genBtn.attr("onclick")?.trim()?.let { handler ->
                Regex("""['"]?(https?://[^'"\s]+)['"]?""").find(handler)?.groupValues?.get(1)?.let { url ->
                    if (isKnownHost(url)) return url
                    if (isIntermediateHost(url)) return url
                }
            }
            // Check href
            genBtn.attr("href")?.trim()?.let { href ->
                if (href.isNotBlank()) {
                    val targetUrl = if (href.startsWith("http")) href else getBaseUrl(pageUrl) + href
                    if (isKnownHost(targetUrl)) return targetUrl
                    if (isIntermediateHost(targetUrl)) return targetUrl
                }
            }
        }

        // Find button.inline-flex target
        val inlineBtn = doc.selectFirst("button.inline-flex")
        if (inlineBtn != null) {
            var target = inlineBtn.attr("data-url").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-href").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-link").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-go").ifBlank { null }
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

        // v14: data-* attrs on ANY element (not just buttons)
        for (el in doc.select("[data-url], [data-href], [data-link], [data-go], [data-target], [data-redirect]")) {
            for (attr in listOf("data-url", "data-href", "data-link", "data-go", "data-target", "data-redirect")) {
                el.attr(attr)?.trim()?.let { v ->
                    if (v.startsWith("http")) {
                        if (isKnownHost(v) || isIntermediateHost(v)) return v
                    }
                }
            }
        }

        // Fallback: search for known host links in the page
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.isNotBlank() && isKnownHost(href)) return href
        }

        // Fallback: search page text
        findKnownHostUrl(doc.toString())?.let { return it }

        // v14: broad raw scan for ANY http URL in generator page
        val html = doc.toString()
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*${Regex.escape(host)}[^\s"'<>\\]*""")
            urlRegex.find(html)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')?.let { found ->
                if (found.startsWith("http")) return found
            }
        }

        return null
    }

    /** Find a known host URL in raw HTML text */
    private fun findKnownHostUrl(html: String): String? {
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
            val found = urlRegex.find(html)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')
            if (!found.isNullOrBlank() && found.startsWith("http")) return found
        }
        return null
    }

    /**
     * Dispatch a resolved URL to the appropriate handler.
     * For known hosts (HubCloud, GDFlix, etc.), use custom extractors.
     * Fallback to loadExtractor() for everything else.
     */
    private suspend fun dispatchFinalHost(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(TAG, "dispatchFinalHost -> $url")
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
                    Log.d(TAG, "Falling back to loadExtractor for: $url")
                    loadExtractor(url, "", subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "dispatchFinalHost failed for $url: ${e.message}")
            // Last resort: try loadExtractor
            try {
                loadExtractor(url, "", subtitleCallback, callback)
            } catch (_: Exception) {}
        }
    }
}

// ─── OlaLinksMov removed in v16 ──
// Previously this was a separate ExtractorApi for links.olamovies.mov
// but it caused recursion. Now OlaLinks handles BOTH ol-am.top and
// olamovies.mov URLs internally, without needing a separate class.

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
