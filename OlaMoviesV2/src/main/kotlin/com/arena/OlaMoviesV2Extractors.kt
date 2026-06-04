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
    "fuckingfast", "fastdl", "driveseed", "driveleech",
    "bbupload", "filepress", "vidstack", "doodstream", "mixdrop",
    "streamtape", "filemoon", "streamlare", "krakenfiles",
    "filelions", "streamhide", "streamwish", "vidhide",
    "busycdn", "goflix", "zfile", "workers.dev",
    "awscdn", "googleusercontent", "megadb", "shrdsk",
    // Added from real-site experiments
    "hubcloud.foo", "hubcloud.dad", "katdrive.in",
    "gdtot.cfd", "gdflix.dad", "gdflix.rest", "gdflix.dev",
    "gdflix.cfd", "gdflix.net", "gdflix.one",
    "filegram", "gdlink", "gdmirror"
)

/** Intermediate shortener domains (Anylinks.in network + v2links + Ola generate) */
private val INTERMEDIATE_HOSTS = listOf(
    "ukrupdate.com", "mastkhabre.com", "aryx.xyz",
    "superheromaniac.com", "spatsify.com",
    "anylinks.in", "rocklinks.net", "dulink.net",
    "ez4short.com", "v2links.com", "v2links.me",
    "olamovies.mov", "links.olamovies.mov",
    "links.ol-am.top", "ol-am.top",
    "olamovies.download", "app2.olamovies.download",
    // Added from real-site experiments
    "bestloansoffers.com", "worldzc.com", "earningtime.in"
)

private fun isKnownHost(url: String): Boolean =
    KNOWN_HOSTS.any { url.contains(it, ignoreCase = true) }

private fun isIntermediateHost(url: String): Boolean =
    INTERMEDIATE_HOSTS.any { url.contains(it, ignoreCase = true) }

// ─── OlaLinks — Shortener Extractor (REWRITE v4) ─────────────────────────────

/**
 * Extractor for links.ol-am.top / links.olamovies.mov — OlaMovies'
 * Cloudflare-protected link shortener.
 *
 * The resolution chain is:
 *   1. links.ol-am.top/{code} → 301 → links.olamovies.mov/{code}
 *   2. links.olamovies.mov → CF Turnstile challenge → Anylinks.in page
 *      (ukrupdate.com / mastkhabre.com / aryx.xyz / superheromaniac.com / spatsify.com)
 *   3. On Anylinks page: click #tp98, click #btn6, submit form[name='tp']
 *      → leads to app2.olamovies.download/generate/?id=XXX
 *   4. On generate page: click button.inline-flex → final host (HubCloud/GDFlix/etc.)
 *
 * KEY INSIGHT: CloudStream's app.get() uses the internal HTTP client which
 * handles CF challenges on real devices (opens WebView for user to solve
 * Turnstile, then stores cf_clearance cookies for subsequent requests).
 *
 * Resolution strategies (4 focused approaches, reordered for max success):
 *   S1: loadExtractor() — CloudStream's built-in CF bypass (WebView solves Turnstile)
 *   S2: Follow chain with app.get() + Anylinks resolution + OlaGenerate resolution
 *   S3: Try alternate domain (links.ol-am.top ↔ links.olamovies.mov)
 *   S4: Direct form POST for Anylinks pages (simulates button click)
 */
open class OlaLinks : ExtractorApi() {
    override val name = "OlaLinks"
    override val mainUrl = "https://links.ol-am.top"
    override val requiresReferer = false

    /** Anylinks.in sites that host the multi-step btn6 flow */
    private val ANYLINKS_HOSTS = listOf(
        "ukrupdate.com", "mastkhabre.com", "aryx.xyz",
        "superheromaniac.com", "spatsify.com"
    )

    private fun isAnylinksPage(url: String): Boolean =
        ANYLINKS_HOSTS.any { url.contains(it, ignoreCase = true) }

    private fun isOlaGeneratePage(url: String): Boolean =
        url.contains("olamovies.download/generate", ignoreCase = true) ||
        url.contains("app2.olamovies.download", ignoreCase = true)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("OlaLinks", "=== Resolving: $url ===")

            // ── S1: loadExtractor() — CloudStream's built-in CF bypass (WebView) ──
            // This is the MOST RELIABLE way to handle CF Turnstile on real devices.
            // CloudStream opens a WebView for the user to solve the challenge,
            // then stores cf_clearance cookies for subsequent requests.
            // We try this FIRST because manual chain resolution can't solve Turnstile.
            try {
                Log.d("OlaLinks", "S1: trying loadExtractor for CF bypass")
                loadExtractor(url, "https://v2.olamovies.mov/", subtitleCallback, callback)
                // If loadExtractor found something, we're done
                // But we also try chain resolution as it might find more links
            } catch (e: Exception) {
                Log.d("OlaLinks", "S1 (loadExtractor) failed: ${e.message}")
            }

            // ── S2: Follow chain with app.get() + specialized resolvers ──────────
            // After CF challenge is solved (cookies stored), app.get() can now
            // follow the full shortener chain.
            try {
                val resolved = resolveChain(url)
                if (resolved != null && isKnownHost(resolved)) {
                    Log.d("OlaLinks", "S2 (chain): $url -> $resolved")
                    dispatchResolved(resolved, subtitleCallback, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S2 failed: ${e.message}")
            }

            // ── S3: Try alternate domain (links.ol-am.top ↔ links.olamovies.mov) ─
            try {
                val altUrl = when {
                    url.contains("links.ol-am.top", ignoreCase = true) ->
                        url.replace("links.ol-am.top", "links.olamovies.mov")
                    url.contains("links.olamovies.mov", ignoreCase = true) ->
                        url.replace("links.olamovies.mov", "links.ol-am.top")
                    else -> null
                }
                if (altUrl != null && altUrl != url) {
                    Log.d("OlaLinks", "S3: trying alternate domain $altUrl")
                    val resolved = resolveChain(altUrl)
                    if (resolved != null && isKnownHost(resolved)) {
                        Log.d("OlaLinks", "S3 (alt chain): $altUrl -> $resolved")
                        dispatchResolved(resolved, subtitleCallback, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S3 failed: ${e.message}")
            }

            // ── S4: Direct form POST for Anylinks pages ──
            // Sometimes the form action URL is available but needs a POST, not GET
            try {
                Log.d("OlaLinks", "S4: trying form POST for $url")
                val formResult = tryFormPost(url)
                if (formResult != null && isKnownHost(formResult)) {
                    Log.d("OlaLinks", "S4 (form POST): $url -> $formResult")
                    dispatchResolved(formResult, subtitleCallback, callback)
                    return
                }
            } catch (e: Exception) {
                Log.d("OlaLinks", "S4 failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("OlaLinks", "Fatal error for $url: ${e.message}")
        }
    }

    // ─── Chain Resolver ────────────────────────────────────────────────────────

    /**
     * Follow the full shortener chain step by step:
     *   1. GET the starting URL (CloudStream handles CF)
     *   2. If we land on a known host → done
     *   3. If we land on an Anylinks page → resolveAnylinksPage()
     *   4. If we land on an OlaGenerate page → resolveOlaGeneratePage()
     *   5. If page has redirect hints → follow them
     *   6. Loop up to MAX_CHAIN_DEPTH times
     */
    private suspend fun resolveChain(startUrl: String): String? {
        val visited = mutableSetOf<String>()
        var currentUrl = startUrl

        repeat(MAX_CHAIN_DEPTH) {
            if (currentUrl in visited) {
                Log.d("OlaLinks", "Chain loop detected at $currentUrl, stopping")
                return null
            }
            visited.add(currentUrl)

            // Already at a known host? Done!
            if (isKnownHost(currentUrl)) return currentUrl

            Log.d("OlaLinks", "Chain step ${it + 1}: fetching $currentUrl")

            val response = try {
                app.get(currentUrl, headers = olaHeaders, timeout = 30_000L)
            } catch (e: Exception) {
                Log.d("OlaLinks", "Chain fetch failed at $currentUrl: ${e.message}")
                return null
            }

            val finalUrl = response.url

            // CloudStream followed through CF and we landed on a known host
            if (finalUrl != currentUrl && isKnownHost(finalUrl)) {
                Log.d("OlaLinks", "Chain: redirect to known host $finalUrl")
                return finalUrl
            }

            // CloudStream followed through CF and we landed on an Anylinks page
            if (isAnylinksPage(finalUrl)) {
                Log.d("OlaLinks", "Chain: landed on Anylinks page $finalUrl")
                val nextUrl = resolveAnylinksPage(response.document, finalUrl)
                if (nextUrl != null) {
                    currentUrl = nextUrl
                    return@repeat // continue loop
                }
            }

            // CloudStream followed through CF and we landed on an OlaGenerate page
            if (isOlaGeneratePage(finalUrl)) {
                Log.d("OlaLinks", "Chain: landed on OlaGenerate page $finalUrl")
                val nextUrl = resolveOlaGeneratePage(response.document, finalUrl)
                if (nextUrl != null) {
                    currentUrl = nextUrl
                    return@repeat // continue loop
                }
            }

            // Got a real page — try scraping for links / redirects
            if (response.code == 200) {
                val doc = response.document

                // Quick scrape: any known host links in the page?
                val scraped = scrapePageForLinks(doc, currentUrl)
                if (scraped != null && isKnownHost(scraped)) return scraped

                // If the scraped link is another intermediate, follow it
                if (scraped != null && isIntermediateHost(scraped)) {
                    currentUrl = scraped
                    return@repeat
                }

                // If we're on an Anylinks-style page (detected by btn6/tp98 elements)
                if (doc.selectFirst("#btn6") != null || doc.selectFirst("#tp98") != null) {
                    Log.d("OlaLinks", "Chain: detected Anylinks page elements at $currentUrl")
                    val nextUrl = resolveAnylinksPage(doc, currentUrl)
                    if (nextUrl != null) {
                        currentUrl = nextUrl
                        return@repeat
                    }
                }

                // If we're on an OlaGenerate-style page (detected by inline-flex button)
                if (doc.selectFirst("button.inline-flex") != null) {
                    Log.d("OlaLinks", "Chain: detected OlaGenerate page elements at $currentUrl")
                    val nextUrl = resolveOlaGeneratePage(doc, currentUrl)
                    if (nextUrl != null) {
                        currentUrl = nextUrl
                        return@repeat
                    }
                }

                // Try getRedirectLinks as generic JS deobfuscation
                val jsResolved = getRedirectLinks(currentUrl)
                if (jsResolved != null && jsResolved != currentUrl) {
                    if (isKnownHost(jsResolved)) return jsResolved
                    if (isIntermediateHost(jsResolved) || isAnylinksPage(jsResolved) || isOlaGeneratePage(jsResolved)) {
                        currentUrl = jsResolved
                        return@repeat
                    }
                }
            }

            // No progress possible from this URL
            Log.d("OlaLinks", "Chain: stuck at $currentUrl (code=${response.code})")
            return null
        }

        // Exhausted chain depth — return whatever we have if it's a known host
        return if (isKnownHost(currentUrl)) currentUrl else null
    }

    // ─── Anylinks Page Resolver ────────────────────────────────────────────────

    /**
     * Resolve an Anylinks.in multi-step page (ukrupdate/mastkhabre/aryx/etc).
     *
     * These pages have a specific flow (from bypass-all-shortlinks-debloated):
     *   1. Click #tp98 link (if present)
     *   2. Click #btn6 button (12s delay for ukrupdate/mastkhabre, 1s for aryx)
     *   3. Submit form[name='tp']
     *
     * Since we can't execute JS or click buttons server-side, we simulate the
     * flow by parsing the page HTML for the target URLs that those clicks
     * would navigate to.
     */
    private suspend fun resolveAnylinksPage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): String? {
        val html = doc.toString()
        Log.d("OlaLinks", "Resolving Anylinks page: $pageUrl")

        // Step 1: Check #tp98 link — it's often a stepping-stone link
        val tp98Href = doc.selectFirst("#tp98")?.attr("href")
        if (!tp98Href.isNullOrBlank()) {
            val tp98Url = if (tp98Href.startsWith("http")) tp98Href
                          else getBaseUrl(pageUrl) + tp98Href
            Log.d("OlaLinks", "Anylinks: found #tp98 -> $tp98Url")
            // #tp98 might be the intermediate step; follow it
            if (isKnownHost(tp98Url)) return tp98Url
            // If it leads to another intermediate or generate page, return it for chain to follow
            if (isIntermediateHost(tp98Url) || isOlaGeneratePage(tp98Url) || isAnylinksPage(tp98Url)) {
                return tp98Url
            }
        }

        // Step 2: Check #btn6 — the main button that leads to the next step
        // In the real browser, btn6.click() fires after a delay.
        // The btn6 element could be:
        //   - <a id="btn6" href="..."> (link)
        //   - <button id="btn6" onclick="window.open('...')"> (JS redirect)
        //   - <a id="btn6" data-href="..."> (data attribute)
        val btn6 = doc.selectFirst("#btn6")
        if (btn6 != null) {
            // Try href first
            var btn6Target = btn6.attr("href").ifBlank { null }
            // Try data-href
            if (btn6Target == null) btn6Target = btn6.attr("data-href").ifBlank { null }
            // Try data-url
            if (btn6Target == null) btn6Target = btn6.attr("data-url").ifBlank { null }
            // Try onclick with window.open or location.href
            if (btn6Target == null) {
                val onclick = btn6.attr("onclick").ifBlank { null }
                if (onclick != null) {
                    btn6Target = Regex("""(?:window\.open|location\.href|location)\s*\(\s*['"]([^'"]+)['"]""")
                        .find(onclick)?.groupValues?.get(1)
                }
            }

            if (!btn6Target.isNullOrBlank()) {
                val btn6Url = if (btn6Target.startsWith("http")) btn6Target
                              else getBaseUrl(pageUrl) + btn6Target
                Log.d("OlaLinks", "Anylinks: #btn6 target -> $btn6Url")

                if (isKnownHost(btn6Url)) return btn6Url
                // btn6 typically leads to the OlaGenerate page or another intermediate
                if (isIntermediateHost(btn6Url) || isOlaGeneratePage(btn6Url) || isAnylinksPage(btn6Url)) {
                    return btn6Url
                }

                // Follow the btn6 link and check where it goes
                try {
                    val btn6Response = app.get(btn6Url, headers = olaHeaders, timeout = 15_000L)
                    val btn6Final = btn6Response.url
                    if (btn6Final != btn6Url) {
                        if (isKnownHost(btn6Final)) return btn6Final
                        if (isIntermediateHost(btn6Final) || isOlaGeneratePage(btn6Final)) return btn6Final
                    }
                    if (btn6Response.code == 200) {
                        val btn6Doc = btn6Response.document
                        // Check if this is an OlaGenerate page
                        if (isOlaGeneratePage(btn6Final) || btn6Doc.selectFirst("button.inline-flex") != null) {
                            val genResult = resolveOlaGeneratePage(btn6Doc, btn6Final)
                            if (genResult != null) return genResult
                        }
                        // Generic scrape
                        val scraped = scrapePageForLinks(btn6Doc, btn6Url)
                        if (scraped != null && isKnownHost(scraped)) return scraped
                        if (scraped != null && (isIntermediateHost(scraped) || isOlaGeneratePage(scraped))) return scraped
                    }
                } catch (e: Exception) {
                    Log.d("OlaLinks", "Anylinks: #btn6 follow failed: ${e.message}")
                }
            }
        }

        // Step 3: Check form[name='tp'] — the final form submission
        val tpForm = doc.selectFirst("form[name='tp']")
        if (tpForm != null) {
            val formAction = tpForm.attr("action").ifBlank { null }
            if (formAction != null) {
                val formUrl = if (formAction.startsWith("http")) formAction
                              else getBaseUrl(pageUrl) + formAction
                Log.d("OlaLinks", "Anylinks: form[name='tp'] action -> $formUrl")
                if (isKnownHost(formUrl)) return formUrl
                if (isIntermediateHost(formUrl) || isOlaGeneratePage(formUrl)) return formUrl
            }
            // Also check hidden inputs in the tp form
            for (input in tpForm.select("input[type=hidden]")) {
                val value = input.attr("value")
                if (value.isNotBlank() && value.startsWith("http")) {
                    if (isKnownHost(value)) return value
                    if (isIntermediateHost(value) || isOlaGeneratePage(value)) return value
                }
            }
        }

        // Step 4: Search page HTML for known host URLs (catch-all)
        findKnownHostUrl(html)?.let { return it }

        // Step 5: Search for base64 encoded URLs
        val base64Pattern = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")
        base64Pattern.findAll(html).forEach { match ->
            try {
                val decoded = base64Decode(match.value)
                if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
                if (decoded.startsWith("http") && (isIntermediateHost(decoded) || isOlaGeneratePage(decoded))) return decoded
            } catch (_: Exception) {}
        }

        // Step 6: Search for OlaGenerate URLs specifically
        val generatePattern = Regex("""https?://(?:app2\.)?olamovies\.download/generate/\?id=[^\s"'<>\\]+""")
        generatePattern.find(html)?.value?.let {
            Log.d("OlaLinks", "Anylinks: found generate URL -> $it")
            return it
        }

        Log.d("OlaLinks", "Anylinks: could not resolve $pageUrl")
        return null
    }

    // ─── OlaGenerate Page Resolver ─────────────────────────────────────────────

    /**
     * Resolve an app2.olamovies.download/generate/?id=XXX page.
     *
     * From bypass-all-shortlinks-debloated:
     *   clickIfExists('button.inline-flex')
     *
     * The page has a button.inline-flex that, when clicked, leads to the
     * final host (HubCloud, GDFlix, etc.). We simulate this by finding
     * the button's href or form target.
     */
    private suspend fun resolveOlaGeneratePage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): String? {
        val html = doc.toString()
        Log.d("OlaLinks", "Resolving OlaGenerate page: $pageUrl")

        // Step 1: Find button.inline-flex and its target
        val inlineBtn = doc.selectFirst("button.inline-flex")
        if (inlineBtn != null) {
            // Check for data attributes
            var target = inlineBtn.attr("data-url").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-href").ifBlank { null }
            if (target == null) target = inlineBtn.attr("data-link").ifBlank { null }
            // Check onclick
            if (target == null) {
                val onclick = inlineBtn.attr("onclick").ifBlank { null }
                if (onclick != null) {
                    target = Regex("""(?:window\.open|location\.href|location)\s*\(\s*['"]([^'"]+)['"]""")
                        .find(onclick)?.groupValues?.get(1)
                }
            }
            // Check parent <a> wrapper
            if (target == null) {
                val parentLink = inlineBtn.parent()?.takeIf { it.tagName() == "a" }
                if (parentLink != null) {
                    target = parentLink.attr("href").ifBlank { null }
                }
            }

            if (!target.isNullOrBlank()) {
                val targetUrl = if (target.startsWith("http")) target
                                else getBaseUrl(pageUrl) + target
                Log.d("OlaLinks", "OlaGenerate: button.inline-flex target -> $targetUrl")
                if (isKnownHost(targetUrl)) return targetUrl
                if (isIntermediateHost(targetUrl) || isOlaGeneratePage(targetUrl)) return targetUrl
            }
        }

        // Step 2: Check for any <a> tags with known host hrefs
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.isNotBlank() && isKnownHost(href)) return href
        }

        // Step 3: Check for form submissions
        val form = doc.selectFirst("form[action]")
        if (form != null) {
            val action = form.attr("action")
            if (action.isNotBlank()) {
                val actionUrl = if (action.startsWith("http")) action
                                else getBaseUrl(pageUrl) + action
                if (isKnownHost(actionUrl)) return actionUrl
                if (isIntermediateHost(actionUrl) || isOlaGeneratePage(actionUrl)) return actionUrl
            }
            // Check hidden inputs for direct URLs
            for (input in form.select("input[type=hidden]")) {
                val value = input.attr("value")
                if (value.isNotBlank() && value.startsWith("http") && isKnownHost(value)) return value
            }
        }

        // Step 4: Search page for known host URLs
        findKnownHostUrl(html)?.let { return it }

        // Step 5: Try JS deobfuscation
        val jsResolved = getRedirectLinks(pageUrl)
        if (jsResolved != null && jsResolved != pageUrl && isKnownHost(jsResolved)) return jsResolved

        // Step 6: Search for base64 encoded URLs
        val base64Pattern = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")
        base64Pattern.findAll(html).forEach { match ->
            try {
                val decoded = base64Decode(match.value)
                if (decoded.startsWith("http") && isKnownHost(decoded)) return decoded
            } catch (_: Exception) {}
        }

        Log.d("OlaLinks", "OlaGenerate: could not resolve $pageUrl")
        return null
    }

    // ─── Generic Page Scraper ──────────────────────────────────────────────────

    /**
     * Scrape a page for redirect URLs:
     *   - meta http-equiv="refresh" redirect
     *   - JavaScript window.location / location.href redirect
     *   - Known host links in the page content
     *   - #download anchor
     *   - Any <a> link pointing to a known host or intermediate
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

        // Search all anchors for known host or intermediate links
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.isNotBlank() && (isKnownHost(href) || isIntermediateHost(href))) return href
        }

        // Search page text for known host URLs (JS variables, etc.)
        findKnownHostUrl(doc.toString())?.let { return it }

        return null
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** Find a known host URL in raw HTML text */
    private fun findKnownHostUrl(html: String): String? {
        for (host in KNOWN_HOSTS) {
            val urlRegex = Regex("""https?://[^\s"'<>\\]*$host[^\s"'<>\\]*""")
            val found = urlRegex.find(html)?.value?.trimEnd('\\', ',', '"', '\'', ')', ';')
            if (!found.isNullOrBlank() && found.startsWith("http")) return found
        }
        return null
    }

    companion object {
        private const val MAX_CHAIN_DEPTH = 10
    }

    // ─── Form POST Simulation ──────────────────────────────────────────────────

    /**
     * Simulate form POST for Anylinks pages.
     * From bypass-all-shortlinks-debloated:
     *   DoIfExists("form[name='tp']", 'submit', 11)
     *
     * We simulate this by:
     *   1. Fetch the page
     *   2. Find form[name='tp'] and its action + hidden inputs
     *   3. POST the form data to the action URL
     *   4. Check the response for the next URL
     */
    private suspend fun tryFormPost(url: String): String? {
        if (!isAnylinksPage(url) && !isIntermediateHost(url)) return null

        try {
            val response = app.get(url, headers = olaHeaders, timeout = 15_000L)
            val doc = response.document
            val html = doc.toString()

            // Look for form[name='tp']
            val tpForm = doc.selectFirst("form[name='tp']")
            if (tpForm != null) {
                val formAction = tpForm.attr("action").ifBlank { url }
                val formUrl = if (formAction.startsWith("http")) formAction
                              else getBaseUrl(url) + formAction

                // Collect all hidden inputs
                val formData = mutableMapOf<String, String>()
                for (input in tpForm.select("input")) {
                    val name = input.attr("name")
                    val value = input.attr("value")
                    if (name.isNotBlank()) formData[name] = value
                }

                if (formData.isNotEmpty()) {
                    Log.d("OlaLinks", "Form POST: posting ${formData.size} fields to $formUrl")
                    val postResponse = app.post(formUrl, headers = olaHeaders, data = formData, timeout = 15_000L)
                    val postFinal = postResponse.url

                    if (isKnownHost(postFinal)) return postFinal
                    if (isOlaGeneratePage(postFinal)) return postFinal

                    // Search the POST response for known host URLs
                    val postHtml = postResponse.document.toString()
                    findKnownHostUrl(postHtml)?.let { return it }

                    // Search for generate URL in POST response
                    val generatePattern = Regex("""https?://(?:app2\.)?olamovies\.download/generate/\?id=[^\s"'<>\\]+""")
                    generatePattern.find(postHtml)?.value?.let { return it }
                }
            }

            // Also try: search for btn6 href that might be generated by JS
            // Pattern: var currentLink = 'https://...'
            val currentLinkPattern = Regex("""var\s+currentLink\s*=\s*['"]([^'"]+)['"]""")
            currentLinkPattern.find(html)?.groupValues?.get(1)?.let { link ->
                Log.d("OlaLinks", "Form POST: found currentLink -> $link")
                if (isKnownHost(link)) return link
                if (isOlaGeneratePage(link) || isIntermediateHost(link)) return link
            }

            // Pattern: var redirect_link = 'https://...'
            val redirectLinkPattern = Regex("""var\s+redirect_link\s*=\s*['"]([^'"]+)['"]""")
            redirectLinkPattern.find(html)?.groupValues?.get(1)?.let { link ->
                Log.d("OlaLinks", "Form POST: found redirect_link -> $link")
                if (isKnownHost(link)) return link
                if (isOlaGeneratePage(link) || isIntermediateHost(link)) return link
            }

        } catch (e: Exception) {
            Log.d("OlaLinks", "tryFormPost failed: ${e.message}")
        }
        return null
    }

    // ─── Dispatch Resolved URL ────────────────────────────────────────────────

    private suspend fun dispatchResolved(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // HubCloud family (lol, foo, dad, etc.)
            url.contains("hubcloud", ignoreCase = true) -> {
                // Route to the appropriate HubCloud extractor based on TLD
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

// ─── HubCloud domain variants (discovered via hacker experiments) ────────────
// HubCloud migrated from hubcloud.lol → hubcloud.foo
// hubcloud.dad is used in the OlaMovies shortener chain

class OlaHubCloudFoo : OlaHubCloud() {
    override val mainUrl = "https://hubcloud.foo"
    override val name = "Hub-Cloud.foo"
}

class OlaHubCloudDad : OlaHubCloud() {
    override val mainUrl = "https://hubcloud.dad"
    override val name = "Hub-Cloud.dad"
}
