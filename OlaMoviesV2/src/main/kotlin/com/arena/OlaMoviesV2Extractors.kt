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

// ─── OlaLinks — 3-Tier CF Bypass (v19 — CLEAN REWRITE) ───────────────────────

/**
 * OlaMovies shortener chain resolver — v19 CLEAN REWRITE.
 *
 * 3-TIER APPROACH — fast to slow, simple to complex:
 *
 *   Tier 1 (0 sec): Try extracting URL from base64/regex in the URL itself (NO HTTP)
 *   Tier 2 (1-2 sec): app.get() with redirect follow for non-CF URLs
 *   Tier 3 (CF bypass): loadExtractor() on intermediate URLs found in Tier 2
 *                      — CloudStream's built-in WebView handles CF Turnstile
 *
 * The link chain:
 *   1. Movie page has `links.ol-am.top/XXXXX` links
 *   2. links.ol-am.top/XXXXX → 301 → links.olamovies.mov/XXXXX (CF Turnstile)
 *   3. After CF solved: page with links to intermediate sites
 *   4. Intermediate sites → Anylinks.in → ad shorteners → final host
 *   5. Final host: HubCloud/GDFlix/etc. → dispatchFinalHost()
 *
 * KEY INSIGHT: app.get() in CloudStream uses OkHttp which CANNOT solve CF Turnstile.
 * Only loadExtractor() triggers CloudStream's WebView which CAN solve CF.
 * So for CF-protected URLs, we must use loadExtractor() on a DIFFERENT ExtractorApi
 * that matches the CF URL (OlaLinksMov), which then shows a WebView popup.
 *
 * v20 FIX:
 *   - Tier 2: Detect CF-blocked redirect (links.ol-am.top → links.olamovies.mov) EARLY.
 *     When we see links.olamovies.mov in the redirect, bail out immediately.
 *   - Tier 3: Rewrite the URL from links.ol-am.top to links.olamovies.mov BEFORE calling
 *     loadExtractor(). This ensures OlaLinksMov is matched (not OlaLinks again).
 *   - OlaLinksMov: Improved link extraction with CF-page-aware parsing.
 */
open class OlaLinks : ExtractorApi() {
    override val name = "OlaLinks"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = false

    companion object {
        private const val TAG = "OlaLinks"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "https://v2.olamovies.mov/"
        var anySuccess = false

        Log.d(TAG, "═══ OlaLinks.getUrl v19: $url ═══")

        // ── Tier 1: Base64/regex extraction from URL (NO HTTP call) ──
        try {
            val extracted = tier1ExtractFromUrl(url)
            if (extracted != null) {
                Log.d(TAG, "  [Tier1] ✓ extracted from URL: $extracted")
                dispatchFinalHost(extracted, subtitleCallback, callback)
                anySuccess = true
            }
        } catch (e: Exception) {
            Log.d(TAG, "  [Tier1] failed: ${e.message}")
        }

        // ── Tier 2: app.get() chain for non-CF URLs ──
        if (!anySuccess) {
            try {
                Log.d(TAG, "→ [Tier2] app.get() chain")
                val result = tier2FollowChain(url, ref, subtitleCallback, callback)
                if (result) anySuccess = true
            } catch (e: Exception) {
                Log.d(TAG, "  [Tier2] failed: ${e.message}")
            }
        }

        // ── Tier 3: loadExtractor() for CF-protected URLs ──
        // This triggers CloudStream's WebView which shows CF Turnstile popup
        if (!anySuccess) {
            try {
                // v20 FIX: Rewrite links.ol-am.top → links.olamovies.mov BEFORE loadExtractor().
                // This ensures OlaLinksMov is matched (not OlaLinks again → infinite loop!)
                val cfUrl = if (url.contains("links.ol-am.top", ignoreCase = true)) {
                    url.replace("links.ol-am.top", "links.olamovies.mov", ignoreCase = true)
                } else {
                    url
                }
                Log.d(TAG, "→ [Tier3] loadExtractor() for CF bypass: $cfUrl")
                loadExtractor(cfUrl, ref, subtitleCallback, callback)
                anySuccess = true
            } catch (e: Exception) {
                Log.d(TAG, "  [Tier3] failed: ${e.message}")
            }
        }

        if (!anySuccess) {
            Log.w(TAG, "═══ ALL TIERS FAILED for $url ═══")
        }
    }

    /**
     * Tier 1: Extract URL from base64/encoded patterns in the URL itself.
     * No HTTP call needed — pure string manipulation.
     */
    protected fun tier1ExtractFromUrl(url: String): String? {
        // Try base64 in URL path segments
        val pathSegments = url.substringAfter("://").substringAfter("/").split("/")
        for (segment in pathSegments) {
            try {
                val decoded = base64Decode(segment)
                if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
            } catch (_: Exception) {}
        }

        // Try base64 in query params (e.g. ?url=BASE64, ?key=BASE64)
        val queryPart = url.substringAfter("?", "")
        if (queryPart.isNotBlank()) {
            for (param in queryPart.split("&")) {
                val value = param.substringAfter("=", "")
                if (value.length > 10) {
                    try {
                        val decoded = base64Decode(value)
                        if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
                    } catch (_: Exception) {}
                }
            }
        }

        return null
    }

    /**
     * Tier 2: Follow the shortener chain using app.get().
     * Works for non-CF URLs and for URLs where CloudStream's interceptor handles CF.
     * Returns true if at least one playable link was dispatched.
     */
    protected suspend fun tier2FollowChain(
        startUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = mutableSetOf<String>()
        var currentUrl = startUrl

        repeat(8) { step ->
            if (currentUrl in visited) return false
            visited.add(currentUrl)

            Log.d(TAG, "  Tier2 step ${step + 1}: $currentUrl")

            // Already at a known host — dispatch it
            if (isKnownHost(currentUrl)) {
                dispatchFinalHost(currentUrl, subtitleCallback, callback)
                return true
            }

            // Intermediate site (ukrupdate/mastkhabre/aryx/etc.)
            if (isIntermediateSite(currentUrl)) {
                val result = bypassIntermediateSite(currentUrl)
                if (result != null) {
                    if (isKnownHost(result)) {
                        dispatchFinalHost(result, subtitleCallback, callback)
                        return true
                    }
                    // Could be an ad shortener or another intermediate
                    if (isAdShortener(result)) {
                        val adResult = bypassAdLinks(result)
                        if (adResult != null && isKnownHost(adResult)) {
                            dispatchFinalHost(adResult, subtitleCallback, callback)
                            return true
                        }
                        if (adResult != null) { currentUrl = adResult; return@repeat }
                    }
                    currentUrl = result
                    return@repeat
                }
                // Intermediate site bypass failed — try loadExtractor on it
                try {
                    loadExtractor(currentUrl, referer, subtitleCallback, callback)
                    return true
                } catch (_: Exception) {}
                return false
            }

            // Ad shortener
            if (isAdShortener(currentUrl)) {
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

            // ── v20: Detect CF-protected redirect target EARLY ──
            // links.ol-am.top → 301 → links.olamovies.mov (CF Turnstile)
            // app.get() CANNOT solve CF Turnstile — bail out and let Tier 3 handle it via WebView.
            if (currentUrl.contains("links.ol-am.top", ignoreCase = true) ||
                currentUrl.contains("links.olamovies.mov", ignoreCase = true)) {
                Log.d(TAG, "  Tier2: CF-protected shortener detected → bail to Tier 3")
                return false
            }

            // Fetch the page
            try {
                val response = app.get(currentUrl, headers = olaHeaders, referer = referer, timeout = 30_000L)
                val doc = response.document
                val finalUrl = response.url

                // v20: Check if we landed on a CF page after redirect
                val html = doc.toString()
                if ((finalUrl.contains("links.olamovies.mov", ignoreCase = true) ||
                    html.contains("Attention Required", ignoreCase = true) ||
                    html.contains("Just a moment", ignoreCase = true) ||
                    html.contains("cf-browser-verification", ignoreCase = true)) &&
                    !html.contains("#download", ignoreCase = true) &&
                    !html.contains("#btn6", ignoreCase = true)) {
                    Log.d(TAG, "  Tier2: CF challenge page detected → bail to Tier 3")
                    return false
                }

                // Redirected to a known host
                if (finalUrl != currentUrl && isKnownHost(finalUrl)) {
                    dispatchFinalHost(finalUrl, subtitleCallback, callback)
                    return true
                }

                // Redirected to a different URL — follow it
                if (finalUrl != currentUrl && finalUrl !in visited) {
                    currentUrl = finalUrl
                    return@repeat
                }

                // Extract next link from the page
                val nextLink = extractNextLinkFromPage(doc, finalUrl)
                if (nextLink != null) {
                    currentUrl = nextLink
                    return@repeat
                }

                // Dead end
                return false
            } catch (e: Exception) {
                Log.d(TAG, "  Tier2 fetch failed: ${e.message}")
                return false
            }
        }

        return false
    }

    /** Check if URL is an intermediate site (not shortener, not known host) */
    protected fun isIntermediateSite(url: String): Boolean {
        val intermediates = listOf(
            "ukrupdate.com", "mastkhabre.com", "aryx.xyz",
            "superheromaniac.com", "spatsify.com", "anylinks.in",
            "olamovies.download", "app2.olamovies.download",
            "tech8s.net", "beautifulfashionnailart.com"
        )
        return intermediates.any { url.contains(it, ignoreCase = true) }
    }

    /** Extract next link from a page using common OlaMovies patterns */
    protected fun extractNextLinkFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        // #download > a (primary OlaMovies pattern)
        doc.selectFirst("#download > a")?.attr("href")?.trim()?.let {
            if (it.startsWith("http")) return it
            if (it.startsWith("/") && it.length > 2) return getBaseUrl(baseUrl) + it
        }

        // #btn6 (Anylinks pattern)
        doc.selectFirst("#btn6")?.let { btn ->
            btn.attr("href")?.trim()?.let { if (it.startsWith("http")) return it }
            for (attr in listOf("data-url", "data-href", "data-link")) {
                btn.attr(attr)?.trim()?.let { if (it.startsWith("http")) return it }
            }
        }

        // #tp98
        doc.selectFirst("#tp98")?.attr("href")?.trim()?.let {
            if (it.startsWith("http")) return it
        }

        // ?key= link
        doc.selectFirst("a[href*='?key=']")?.attr("href")?.trim()?.let {
            return if (it.startsWith("http")) it else getBaseUrl(baseUrl) + it
        }

        // data-url/data-href on any element
        for (attr in listOf("data-url", "data-href", "data-link", "data-go")) {
            doc.selectFirst("[$attr]")?.attr(attr)?.trim()?.let {
                if (it.startsWith("http")) return it
            }
        }

        // onclick handlers
        doc.select("[onclick]").forEach { el ->
            val handler = el.attr("onclick").trim()
            Regex("""(?:window\.open|location\.href|location)\s*\(\s*['"]([^'"]+)['"]""")
                .find(handler)?.groupValues?.get(1)?.let { return it }
        }

        // JS variables
        val html = doc.toString()
        val jsPatterns = listOf(
            Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+currentLink\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+pxl\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
        )
        for (pattern in jsPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.trim()?.let { value ->
                if (value.startsWith("http")) return value
                try {
                    val decoded = base64Decode(value)
                    if (decoded.startsWith("http")) return decoded
                } catch (_: Exception) {}
            }
        }

        // Any <a> tag linking to known/intermediate/ad host
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.startsWith("http") && (isKnownHost(href) || isIntermediateSite(href) || isAdShortener(href))) {
                return href
            }
        }

        return null
    }

    /**
     * Dispatch a resolved URL to the appropriate handler.
     * For known hosts (HubCloud, GDFlix, etc.), use custom extractors.
     * Fallback to loadExtractor() for everything else.
     */
    protected suspend fun dispatchFinalHost(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(TAG, "dispatchFinalHost -> $url")
            when {
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
                url.contains("gdflix", ignoreCase = true) ||
                url.contains("gd-flix", ignoreCase = true) ->
                    OlaGDFlix().getUrl(url, name, subtitleCallback, callback)
                url.contains("gdtot", ignoreCase = true) ->
                    OlaGDTotCfd().getUrl(url, name, subtitleCallback, callback)
                url.contains("vidstack", ignoreCase = true) ->
                    OlaVidStack().getUrl(url, name, subtitleCallback, callback)
                url.contains("pixeldrain", ignoreCase = true) ->
                    OlaPixelDrainDev().getUrl(url, name, subtitleCallback, callback)
                url.contains("olamovies.dad", ignoreCase = true) ||
                url.contains("space.olamovies", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "OlaMovies", "OlaMovies Direct", url, ExtractorLinkType.VIDEO
                        )
                    )
                }
                else -> {
                    Log.d(TAG, "Falling back to loadExtractor for: $url")
                    loadExtractor(url, "", subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "dispatchFinalHost failed for $url: ${e.message}")
            try { loadExtractor(url, "", subtitleCallback, callback) } catch (_: Exception) {}
        }
    }
}

/**
 * OlaLinksMov — CF Turnstile handler for links.olamovies.mov (v20)
 *
 * When CloudStream's loadExtractor() matches a URL to this extractor,
 * it opens a WebView popup so the user can solve the CF Turnstile challenge.
 * After CF is solved, the page loads and we extract the links from it.
 *
 * This is the ONLY reliable way to bypass CF Turnstile in CloudStream.
 *
 * v20 FIX:
 *   - CF challenge page detection: if interceptor doesn't solve CF, detect the
 *     challenge page and fall back to parent OlaLinks' 3-tier chain.
 *   - Fallback in catch block: if everything fails, try parent's chain as last resort.
 */
class OlaLinksMov : OlaLinks() {
    override val name = "OlaLinksMov"
    override val mainUrl = "https://links.olamovies.mov"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "https://v2.olamovies.mov/"
        Log.d("OlaLinksMov", "═══ CF WebView resolver: $url ═══")

        // Try to fetch the page — CloudStream's interceptor will show CF popup if needed
        try {
            val response = app.get(url, headers = olaHeaders, referer = ref, timeout = 60_000L)
            val doc = response.document
            val finalUrl = response.url

            Log.d("OlaLinksMov", "Got response: code=${response.code}, url=$finalUrl")

            val html = doc.toString()

            // ── v20: CF challenge page detection ──
            val isCfPage = html.contains("Attention Required", ignoreCase = true) ||
                    html.contains("Just a moment", ignoreCase = true) ||
                    html.contains("cf-browser-verification", ignoreCase = true) ||
                    (html.contains("challenge-platform", ignoreCase = true) &&
                     html.contains("cf-turnstile", ignoreCase = true))

            val hasDownloadLinks = html.contains("#download", ignoreCase = true) ||
                    html.contains("#btn6", ignoreCase = true) ||
                    html.contains("?key=", ignoreCase = true)

            if (isCfPage && !hasDownloadLinks) {
                Log.w("OlaLinksMov", "Response is CF challenge page, interceptor didn't resolve it")
                // v20: Fall back to parent's 3-tier chain
                super.getUrl(url, referer, subtitleCallback, callback)
                return
            }

            // Check if we landed on a known host directly
            if (isKnownHost(finalUrl)) {
                dispatchFinalHost(finalUrl, subtitleCallback, callback)
                return
            }

            // Extract links from the resolved page
            val links = mutableListOf<String>()

            // #download > a
            doc.select("#download > a, #download a, div#download a").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) links.add(href)
            }

            // #btn6, #tp98 (Anylinks patterns)
            doc.select("#btn6, #tp98, #go-link, .btn-download, .download-btn, a.go-link").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) links.add(href)
                // Check data-* attrs
                for (attr in listOf("data-url", "data-href", "data-link", "data-go")) {
                    it.attr(attr)?.trim()?.let { v -> if (v.startsWith("http")) links.add(v) }
                }
            }

            // ?key= links
            doc.select("a[href*='?key=']").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) links.add(href)
            }

            // data-* attributes on any element
            doc.select("[data-url], [data-href], [data-link], [data-go]").forEach { el ->
                for (attr in listOf("data-url", "data-href", "data-link", "data-go")) {
                    el.attr(attr)?.trim()?.let { v -> if (v.startsWith("http")) links.add(v) }
                }
            }

            // All <a> tags with http hrefs
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") && !href.contains("olamovies.mov/", ignoreCase = true)) {
                    links.add(href)
                }
            }

            // JS variables
            val jsPatterns = listOf(
                Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+currentLink\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+redirect\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""var\s+pxl\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
            )
            for (pattern in jsPatterns) {
                pattern.findAll(html).forEach { match ->
                    val value = match.groupValues[1].trim()
                    if (value.startsWith("http")) links.add(value)
                    try {
                        val decoded = base64Decode(value)
                        if (decoded.startsWith("http")) links.add(decoded)
                    } catch (_: Exception) {}
                }
            }

            // Raw HTML scan for known host URLs
            for (host in KNOWN_HOSTS) {
                val urlRegex = Regex("""https?://[^\s"'<>\\]*${Regex.escape(host)}[^\s"'<>\\]*""")
                urlRegex.findAll(html).forEach { match ->
                    val cleaned = match.value.trimEnd('\\', ',', '"', '\'', ')', ';', ']', '}')
                    if (cleaned.startsWith("http")) links.add(cleaned)
                }
            }

            // Deduplicate
            val uniqueLinks = links.distinct()
            Log.d("OlaLinksMov", "Found ${uniqueLinks.size} links from CF-resolved page")

            var anySuccess = false
            for (link in uniqueLinks) {
                when {
                    isKnownHost(link) -> {
                        dispatchFinalHost(link, subtitleCallback, callback)
                        anySuccess = true
                    }
                    isAdShortener(link) -> {
                        val result = bypassAdLinks(link)
                        if (result != null && isKnownHost(result)) {
                            dispatchFinalHost(result, subtitleCallback, callback)
                            anySuccess = true
                        } else if (result != null) {
                            try {
                                loadExtractor(result, ref, subtitleCallback, callback)
                                anySuccess = true
                            } catch (_: Exception) {}
                        }
                    }
                    isIntermediateSite(link) -> {
                        val result = bypassIntermediateSite(link)
                        if (result != null && isKnownHost(result)) {
                            dispatchFinalHost(result, subtitleCallback, callback)
                            anySuccess = true
                        } else if (result != null) {
                            try {
                                loadExtractor(result, ref, subtitleCallback, callback)
                                anySuccess = true
                            } catch (_: Exception) {}
                        }
                    }
                    else -> {
                        try {
                            loadExtractor(link, ref, subtitleCallback, callback)
                            anySuccess = true
                        } catch (_: Exception) {}
                    }
                }
            }

            if (!anySuccess) {
                Log.w("OlaLinksMov", "No playable links found after CF resolve")
            }
        } catch (e: Exception) {
            Log.e("OlaLinksMov", "Failed to resolve CF page: ${e.message}")
            // v20: Last resort — try parent's full 3-tier chain
            try {
                Log.d("OlaLinksMov", "Falling back to parent 3-tier chain")
                super.getUrl(url, referer, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
    }
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
