package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "OlaUtils"

/** Resolve final URL by following HTTP redirects (GET with allowRedirects=false) */
suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 7
    while (loopCount < maxRedirects) {
        try {
            val res = app.get(currentUrl, allowRedirects = false, timeout = 5000L)
            when {
                res.code in 300..399 -> {
                    val location = res.headers["Location"]
                    if (location.isNullOrEmpty()) break
                    currentUrl = if (location.startsWith("http")) location
                                 else getBaseUrl(currentUrl) + location
                }
                res.code == 200 -> break
                else -> return null
            }
            loopCount++
        } catch (e: Exception) { return null }
    }
    return if (currentUrl != startUrl) currentUrl else null
}

/** Extract base URL (scheme + host) from a full URL */
fun getBaseUrl(url: String): String {
    return try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { url }
}

/** Enhanced quality index extraction from header text */
fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") || lowerStr.contains("uhd") -> 2160
        lowerStr.contains("2k") -> 1440
        lowerStr.contains("hdr") || lowerStr.contains("dolby") || lowerStr.contains("dv") -> 2160
        lowerStr.contains("remux") -> 1080
        lowerStr.contains("ds4k") -> 1080
        else -> Qualities.Unknown.value
    }
}

/**
 * Load extractor with custom source name tag
 */
suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    quality: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "${link.source} $source",
                    "${link.source} $source",
                    link.url,
                ) {
                    this.quality = quality ?: link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

// ─── LikDev's bypassOlaRedirect (IMPROVED for v2.olamovies.mov) ──────────────

/**
 * Bypass the OlaMovies redirect chain — IMPROVED v8.
 *
 * Handles TWO types of initial links:
 *   1. PLAIN links: links.ol-am.top/XXXXX (no ?key= or &id=)
 *      → Follow the redirect, get the page, find #download > a
 *   2. KEYED links: links.olamovies.mov/XXXXX?key=ENCRYPTED_KEY&id=VALUE
 *      → Use the key as param name, id as param value, GET the page
 *      → Find #download > a for the next link in the chain
 *
 * The chain continues until we find a non-keyed link (final destination)
 * or until max steps reached.
 *
 * Based on LikDev's proven approach + improvements for v2.
 */
suspend fun bypassOlaRedirect(link: String, referer: String): List<String> {
    val shortLinkList = arrayListOf<String>()
    var currentLink = link
    var count = 0
    val maxSteps = 10

    Log.d(TAG, "bypassOlaRedirect: starting with $link")

    while (count < maxSteps) {
        count++
        try {
            Log.d(TAG, "bypassOlaRedirect step $count: $currentLink")

            // ── Handle PLAIN links (no ?key= or &id=) ──
            // Initial links.ol-am.top/XXXXX format — just follow + scrape
            if (!currentLink.contains("?key=") && !currentLink.contains("&id=")) {
                Log.d(TAG, "bypassOlaRedirect: plain link (no key/id), following...")

                val response = try {
                    app.get(currentLink, referer = referer, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Referer" to referer
                    ), timeout = 15_000L)
                } catch (e: Exception) {
                    Log.d(TAG, "bypassOlaRedirect: plain link fetch failed: ${e.message}")
                    // Retry once after delay
                    try {
                        delay(1500L)
                        app.get(currentLink, referer = referer, timeout = 15_000L)
                    } catch (e2: Exception) {
                        Log.d(TAG, "bypassOlaRedirect: retry also failed: ${e2.message}")
                        break
                    }
                }

                val doc = response.document

                // Check if we were redirected to a keyed link
                val finalUrl = response.url
                if (finalUrl != currentLink && finalUrl.contains("?key=") && finalUrl.contains("&id=")) {
                    Log.d(TAG, "bypassOlaRedirect: redirected to keyed link -> $finalUrl")
                    currentLink = finalUrl
                    continue  // Process this keyed link in the next iteration
                }

                // Try to find #download > a
                val nextHref = doc.selectFirst("#download > a")?.attr("href")?.trim()
                if (!nextHref.isNullOrBlank() && nextHref.startsWith("http")) {
                    Log.d(TAG, "bypassOlaRedirect: found #download > a -> $nextHref")
                    shortLinkList.add(nextHref)

                    // If the next link has key/id, continue the chain
                    if (nextHref.contains("?key=") && nextHref.contains("&id=")) {
                        currentLink = nextHref
                        continue
                    } else {
                        // Final destination reached
                        Log.d(TAG, "bypassOlaRedirect: final link (no more key/id)")
                        break
                    }
                }

                // Try other patterns on the page
                val altPatterns = listOf(
                    "#btn6" to "href",
                    "#tp98" to "href",
                    "button.inline-flex" to "data-url",
                    "button.inline-flex" to "data-href",
                    "a.btn-success" to "href",
                    "a[href*=download]" to "href"
                )

                var found = false
                for ((selector, attr) in altPatterns) {
                    val val_ = doc.selectFirst(selector)?.attr(attr)?.trim()
                    if (!val_.isNullOrBlank() && val_.startsWith("http")) {
                        Log.d(TAG, "bypassOlaRedirect: found $selector[$attr] -> $val_")
                        shortLinkList.add(val_)
                        if (val_.contains("?key=") && val_.contains("&id=")) {
                            currentLink = val_
                            found = true
                            break
                        } else {
                            found = true
                            break
                        }
                    }
                }

                if (found) {
                    // If the last found link has key/id, continue; otherwise stop
                    val lastLink = shortLinkList.last()
                    if (lastLink.contains("?key=") && lastLink.contains("&id=")) {
                        currentLink = lastLink
                        continue
                    } else {
                        break
                    }
                }

                // Scrape all <a> tags for any http link
                val allAnchors = doc.select("a[href]").mapNotNull { it.attr("href").trim().takeIf { h -> h.startsWith("http") } }
                for (anchor in allAnchors) {
                    if (anchor.contains("hubcloud", ignoreCase = true) ||
                        anchor.contains("gdflix", ignoreCase = true) ||
                        anchor.contains("gdtot", ignoreCase = true) ||
                        anchor.contains("drive.google", ignoreCase = true) ||
                        anchor.contains("pixeldrain", ignoreCase = true) ||
                        anchor.contains("dulink", ignoreCase = true) ||
                        anchor.contains("ez4short", ignoreCase = true) ||
                        anchor.contains("rocklinks", ignoreCase = true) ||
                        anchor.contains("crazyblog", ignoreCase = true) ||
                        anchor.contains("anylinks", ignoreCase = true) ||
                        anchor.contains("?key=", ignoreCase = true)) {
                        if (anchor !in shortLinkList) {
                            shortLinkList.add(anchor)
                            Log.d(TAG, "bypassOlaRedirect: scraped anchor -> $anchor")
                        }
                    }
                }

                // No more links found on this page
                Log.d(TAG, "bypassOlaRedirect: no more links on plain page, stopping")
                break
            }

            // ── Handle KEYED links: ?key=ENCRYPTED_KEY&id=VALUE ──
            val key = currentLink.substringAfter("?key=").substringBefore("&id=")
                .replace("%2B", "+").replace("%3D", "=").replace("%2F", "/")
            val id = currentLink.substringAfter("&id=")
                .substringBefore("&")  // Handle extra params after &id=

            Log.d(TAG, "bypassOlaRedirect: keyed link, key='$key', id='$id'")

            val param = mapOf(key to id)
            val doc = app.get(currentLink, referer = referer, params = param, timeout = 15_000L).document

            val nextHref = doc.selectFirst("#download > a")?.attr("href")?.trim()
            if (nextHref.isNullOrBlank()) {
                Log.d(TAG, "bypassOlaRedirect: no #download > a at step $count, stopping")
                break
            }

            shortLinkList.add(nextHref)

            // If the next link doesn't have key/id params, it's the final destination
            if (!nextHref.contains("?key=") || !nextHref.contains("&id=")) {
                Log.d(TAG, "bypassOlaRedirect: final link at step $count -> $nextHref")
                break
            }

            currentLink = nextHref
        } catch (e: Exception) {
            Log.d(TAG, "bypassOlaRedirect: failed at step $count: ${e.message}")
            break
        }
    }

    Log.d(TAG, "bypassOlaRedirect: collected ${shortLinkList.size} link(s)")
    return shortLinkList
}

// ─── LikDev's bypassAdLinks (IMPROVED v9 — Multiple Bypass APIs) ──────────────

/**
 * Bypass ad shortener links — IMPROVED v9.
 *
 * MULTI-API APPROACH — less dependency on any single API:
 *
 * Strategy 1: emilyx API (dulink / ez4short / rocklinks)
 * Strategy 2: crazyblog cookie-based POST
 * Strategy 3: Direct redirect following (v2links / bestloansoffers / worldzc / earningtime)
 * Strategy 4: Alternate bypass APIs (bypass.city / bypass.pm)
 * Strategy 5: Form-based extraction (POST with form data)
 * Strategy 6: Cookie-based timer bypass (wait + POST)
 * Strategy 7: JavaScript deobfuscation (for shorteners that use JS)
 * Strategy 8: Nuclear — return as-is, let loadExtractor handle it
 */
suspend fun bypassAdLinks(link: String): String? {
    val emilyxApiSupportedLinks = listOf("dulink", "ez4short")
    val apiUrl = "https://api.emilyx.in/api/bypass"

    val type = when {
        link.contains("rocklinks", ignoreCase = true) -> "rocklinks"
        link.contains("dulink", ignoreCase = true) -> "dulink"
        link.contains("ez4short", ignoreCase = true) -> "ez4short"
        else -> ""
    }

    Log.d(TAG, "bypassAdLinks: resolving $link (type=$type)")

    // ── Strategy 1: Use emilyx API for supported shorteners ──
    if (emilyxApiSupportedLinks.any { link.contains(it, ignoreCase = true) } ||
        link.contains("rocklinks", ignoreCase = true)) {
        try {
            val values = mapOf("type" to type, "url" to link)
            val json = JSONObject(values).toString()
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = json.toRequestBody(mediaType)

            val response = app.post(apiUrl, requestBody = requestBody, timeout = 15_000L)
            val responseText = response.text
            val responseJson = JSONObject(responseText)
            val bypassedUrl = responseJson.optString("url", "")
            if (bypassedUrl.startsWith("http")) {
                Log.d(TAG, "bypassAdLinks: [S1] emilyx API resolved -> $bypassedUrl")
                return bypassedUrl
            }
        } catch (e: Exception) {
            Log.d(TAG, "bypassAdLinks: [S1] emilyx API failed: ${e.message}")
        }
    }

    // ── Strategy 2: Alternate bypass API — bypass.city ──
    try {
        val bypassCityUrl = "https://api.bypass.city/bypass?url=${java.net.URLEncoder.encode(link, "UTF-8")}"
        val bcResponse = app.get(bypassCityUrl, timeout = 15_000L)
        val bcText = bcResponse.text
        // Try parsing as JSON
        try {
            val bcJson = JSONObject(bcText)
            val bcUrl = bcJson.optString("url", bcJson.optString("destination", bcJson.optString("result", "")))
            if (bcUrl.startsWith("http")) {
                Log.d(TAG, "bypassAdLinks: [S2] bypass.city resolved -> $bcUrl")
                return bcUrl
            }
        } catch (_: Exception) {}
        // Try finding URL in raw response
        val urlMatch = Regex("""(https?://[^\s"'<>]+)""").find(bcText)?.groupValues?.get(1)
        if (urlMatch != null && isKnownHostUrl(urlMatch)) {
            Log.d(TAG, "bypassAdLinks: [S2] bypass.city raw resolved -> $urlMatch")
            return urlMatch
        }
    } catch (e: Exception) {
        Log.d(TAG, "bypassAdLinks: [S2] bypass.city failed: ${e.message}")
    }

    // ── Strategy 3: crazyblog cookie-based POST for ser2/ser3.crazyblog ──
    if (link.contains("crazyblog", ignoreCase = true)) {
        try {
            val domain = when {
                link.contains("ser2.crazyblog", ignoreCase = true) -> "https://ser2.crazyblog.in"
                link.contains("ser3.crazyblog", ignoreCase = true) -> "https://ser3.crazyblog.in"
                else -> "https://ser3.crazyblog.in"
            }
            val pagePath = link.substringAfterLast("/")

            // Step 1: GET the page to get cookies
            val html = app.get("$domain/$pagePath", timeout = 15_000L)
            val cookieHeader = html.headers.filter { it.first.contains("set-cookie", ignoreCase = true) }.toString()

            // Parse cookies: AppSession, csrfToken, app_visitor
            val cookieParts = cookieHeader.split("),").map {
                it.substringAfter("=").substringBefore("; path")
            }
            val appVisitor = cookieParts.getOrElse(0) { "" }
            val appSession = cookieParts.getOrElse(1) { "" }
            val csrfToken = cookieParts.getOrElse(2) { "" }

            // Step 2: Extract form data
            val document = html.document
            val data = document.select("#go-link input")
                .mapNotNull { it.attr("name").toString() to it.attr("value").toString() }
                .toMap()

            // Step 3: POST with cookies
            val postResponse = app.post(
                url = "$domain/links/go",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                cookies = mapOf(
                    "app_visitor" to appVisitor,
                    "AppSession" to appSession,
                    "csrfToken" to csrfToken
                ),
                data = data,
                referer = "$domain/$pagePath",
                timeout = 15_000L
            )

            val postText = postResponse.text
            val postJson = JSONObject(postText)
            val resultUrl = postJson.optString("url", "")
            if (resultUrl.startsWith("http")) {
                Log.d(TAG, "bypassAdLinks: [S3] crazyblog resolved -> $resultUrl")
                return resultUrl
            }
        } catch (e: Exception) {
            Log.d(TAG, "bypassAdLinks: [S3] crazyblog failed: ${e.message}")
        }
    }

    // ── Strategy 4: Direct redirect following for known shorteners ──
    if (link.contains("v2links", ignoreCase = true) ||
        link.contains("bestloansoffers", ignoreCase = true) ||
        link.contains("worldzc", ignoreCase = true) ||
        link.contains("earningtime", ignoreCase = true) ||
        link.contains("dulink", ignoreCase = true) ||
        link.contains("ez4short", ignoreCase = true) ||
        link.contains("rocklinks", ignoreCase = true)) {
        try {
            val resolved = resolveFinalUrl(link)
            if (resolved != null && resolved.startsWith("http") && resolved != link) {
                Log.d(TAG, "bypassAdLinks: [S4] redirect resolved -> $resolved")
                return resolved
            }
        } catch (e: Exception) {
            Log.d(TAG, "bypassAdLinks: [S4] redirect failed: ${e.message}")
        }
    }

    // ── Strategy 5: Form-based extraction — GET the page, find form, POST it ──
    try {
        val pageResponse = app.get(link, timeout = 15_000L)
        val pageDoc = pageResponse.document

        // Look for forms with action URLs
        val form = pageDoc.selectFirst("form[action]")
        if (form != null) {
            val action = form.attr("action").trim()
            val method = form.attr("method").lowercase().ifBlank { "get" }
            val formData = form.select("input").mapNotNull {
                it.attr("name").ifBlank { return@mapNotNull null } to it.attr("value")
            }.toMap()

            if (action.isNotBlank()) {
                val formUrl = if (action.startsWith("http")) action else getBaseUrl(link) + action
                val formResponse = if (method == "post") {
                    app.post(formUrl, data = formData, referer = link, timeout = 15_000L)
                } else {
                    app.post(formUrl, referer = link, data = formData, timeout = 15_000L)
                }

                // Check the response for known host URLs
                val formDoc = formResponse.document
                formDoc.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") && isKnownHostUrl(href)) {
                        Log.d(TAG, "bypassAdLinks: [S5] form extraction resolved -> $href")
                        return href
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "bypassAdLinks: [S5] form extraction failed: ${e.message}")
    }

    // ── Strategy 6: Cookie-based timer bypass (wait + POST) ──
    // Some shorteners show a "wait X seconds" page then POST
    try {
        val timerPage = app.get(link, timeout = 15_000L)
        val timerDoc = timerPage.document
        val timerHtml = timerDoc.toString()

        // Check for countdown timer patterns
        val countdownMatch = Regex("""(?:var\s+countdown|timer|wait)\s*=\s*(\d+)""").find(timerHtml)
        if (countdownMatch != null) {
            // Extract the POST URL and form data
            val postUrl = timerDoc.selectFirst("form[action]")?.attr("action")?.trim() ?: ""
            val formData = timerDoc.select("form input").mapNotNull {
                it.attr("name").ifBlank { return@mapNotNull null } to it.attr("value")
            }.toMap()

            if (postUrl.isNotBlank()) {
                val fullPostUrl = if (postUrl.startsWith("http")) postUrl else getBaseUrl(link) + postUrl
                val cookies = timerPage.cookies
                val postResult = app.post(
                    fullPostUrl,
                    data = formData,
                    cookies = cookies,
                    referer = link,
                    timeout = 15_000L
                )
                val postDoc = postResult.document
                postDoc.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") && isKnownHostUrl(href)) {
                        Log.d(TAG, "bypassAdLinks: [S6] timer bypass resolved -> $href")
                        return href
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "bypassAdLinks: [S6] timer bypass failed: ${e.message}")
    }

    // ── Strategy 7: JavaScript deobfuscation — for shorteners that use JS ──
    try {
        val jsPage = app.get(link, timeout = 15_000L)
        val jsHtml = jsPage.document.toString()

        // Check for common JS redirect patterns
        val jsPatterns = listOf(
            Regex("""window\.location\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""location\.replace\s*\(\s*['"]([^'"]+)['"]"""),
            Regex("""window\.open\s*\(\s*['"]([^'"]+)['"]"""),
            Regex("""var\s+redirect\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""atob\s*\(\s*['"]([^'"]+)['"]""")  // base64 encoded URL
        )

        for (pattern in jsPatterns) {
            pattern.find(jsHtml)?.groupValues?.get(1)?.trim()?.let { url ->
                val decoded = if (url.startsWith("http")) url
                              else try { base64Decode(url) } catch (_: Exception) { url }
                if (decoded.startsWith("http")) {
                    Log.d(TAG, "bypassAdLinks: [S7] JS deobfuscation resolved -> $decoded")
                    return decoded
                }
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "bypassAdLinks: [S7] JS deobfuscation failed: ${e.message}")
    }

    Log.d(TAG, "bypassAdLinks: ALL strategies failed for $link")
    return null
}

/** Check if a URL contains a known host */
private fun isKnownHostUrl(url: String): Boolean {
    val knownHosts = listOf(
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
        "gdbot", "drivebot", "drivehub"
    )
    return knownHosts.any { url.contains(it, ignoreCase = true) }
}

// ─── Multi-layer redirect/obfuscation resolver ──────────────────────────────

/**
 * Multi-layer redirect/obfuscation resolver.
 * Handles HDHub4U-style JS obfuscation, meta-refresh, JS redirects, etc.
 */
suspend fun getRedirectLinks(url: String): String? {
    return try {
        val doc = app.get(url, timeout = 15L).toString()

        // Strategy 1: HDHub4U-style JS obfuscation
        val regex = Regex("""s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)""")
        val combinedString = buildString {
            regex.findAll(doc).forEach { matchResult ->
                val extractedValue = matchResult.groups[1]?.value ?: matchResult.groups[2]?.value
                if (!extractedValue.isNullOrEmpty()) append(extractedValue)
            }
        }

        if (combinedString.isNotBlank()) {
            try {
                val decodedString = base64Decode(pen(base64Decode(base64Decode(combinedString))))
                val jsonObject = JSONObject(decodedString)
                val encodedUrl = base64Decode(jsonObject.optString("o", "")).trim()
                val data = androidBase64Encode(jsonObject.optString("data", "")).trim()
                val wphttp1 = jsonObject.optString("blog_url", "").trim()

                if (encodedUrl.isNotBlank()) {
                    Log.d(TAG, "Resolved via JS deobfuscation: $url -> $encodedUrl")
                    return encodedUrl
                }

                if (wphttp1.isNotBlank() && data.isNotBlank()) {
                    val directLink = runCatching {
                        app.get("$wphttp1?re=$data".trim()).document.select("body").text().trim()
                    }.getOrDefault("").trim()

                    if (directLink.startsWith("http")) {
                        Log.d(TAG, "Resolved via blog_url fallback: $url -> $directLink")
                        return directLink
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "JS deobfuscation failed for $url: ${e.message}")
            }
        }

        // Strategy 2: Simple meta-refresh redirect
        val metaRefresh = Regex("""(?i)<meta[^>]*http-equiv=["']?refresh["']?[^>]*content=["']?\d+;\s*url=([^"'>\s]+)""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!metaRefresh.isNullOrBlank() && metaRefresh.startsWith("http")) {
            Log.d(TAG, "Resolved via meta-refresh: $url -> $metaRefresh")
            return metaRefresh
        }

        // Strategy 3: JavaScript window.location redirect
        val jsRedirect = Regex("""(?i)(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!jsRedirect.isNullOrBlank() && jsRedirect.startsWith("http")) {
            Log.d(TAG, "Resolved via JS redirect: $url -> $jsRedirect")
            return jsRedirect
        }

        // Strategy 4: "var url = '...'" pattern
        val varUrl = Regex("""var\s+url\s*=\s*'([^']+)'""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!varUrl.isNullOrBlank()) {
            val resolved = if (varUrl.startsWith("http")) varUrl
                           else getBaseUrl(url) + varUrl
            Log.d(TAG, "Resolved via var url: $url -> $resolved")
            return resolved
        }

        // Strategy 5: Follow HTTP redirects
        val finalUrl = resolveFinalUrl(url)
        if (finalUrl != null && finalUrl != url) {
            Log.d(TAG, "Resolved via HTTP redirect: $url -> $finalUrl")
            return finalUrl
        }

        Log.d(TAG, "Could not resolve redirect for $url")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Redirect resolution failed for $url: ${e.message}")
        null
    }
}

/** ROT13 cipher — used by HDHub4U-style JS obfuscation */
fun pen(value: String): String {
    return value.map {
        when (it) {
            in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> it
        }
    }.joinToString("")
}

/** Decode base64 then URL-encode for blog_url?re= parameter */
fun androidBase64Encode(value: String): String {
    return try {
        val decoded = String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
        java.net.URLEncoder.encode(decoded, "UTF-8")
    } catch (e: Exception) {
        java.net.URLEncoder.encode(value, "UTF-8")
    }
}

// ─── OkHttp helpers for bypassAdLinks ───────────────────────────────────────

/** Create a MediaType from a string (for OkHttp requestBody) */
@Suppress("DEPRECATION_ERROR")
private fun String.toMediaTypeOrNull(): okhttp3.MediaType? {
    return try { okhttp3.MediaType.parse(this) } catch (_: Exception) { null }
}

/** Create a RequestBody from a String with a MediaType */
@Suppress("DEPRECATION")
private fun String.toRequestBody(contentType: okhttp3.MediaType?): okhttp3.RequestBody {
    return okhttp3.RequestBody.create(contentType, this)
}
