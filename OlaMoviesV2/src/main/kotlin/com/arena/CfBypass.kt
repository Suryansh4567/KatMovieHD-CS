package com.arena

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Cloudflare bypass utility for CloudStream extensions.
 *
 * Uses WebViewResolver (library module — available to extensions) to open
 * a REAL Android WebView popup where the user can solve Cloudflare Turnstile.
 * After solving, cf_clearance cookies are cached and reused for subsequent requests.
 *
 * v22 — Written from scratch based on CloudStream's CloudflareKiller pattern.
 *
 * KEY INSIGHTS:
 *   - CloudStream's CloudflareKiller interceptor is DEAD CODE (never registered)
 *   - Extensions MUST implement their own CF bypass using WebViewResolver
 *   - CF Turnstile requires: userAgent=null, useOkhttp=false, unmatchable interceptUrl
 *   - requestCallBack checks CookieManager for cf_clearance → exits WebView when found
 *   - Cookies + WebView UA must be passed in subsequent OkHttp requests
 *
 * FLOW:
 *   1. app.get() fails with 403 + Server: cloudflare
 *   2. CfBypass.solveCf() opens WebViewResolver
 *   3. User solves Turnstile in WebView popup
 *   4. cf_clearance cookie set in CookieManager
 *   5. Callback detects cookie → WebView destroyed → cookies cached
 *   6. Extension retries app.get() with Cookie + WebView UA headers
 *   7. Request succeeds!
 */
object CfBypass {
    private const val TAG = "CfBypass"

    /** Cached CF cookies per host: host → (cookieMap, timestampMs) */
    private val cookieCache = mutableMapOf<String, MutableMap<String, String>>()
    private val cookieTimestamps = mutableMapOf<String, Long>()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Check if an HTTP response is a Cloudflare block/challenge page.
     *
     * Checks: Server header = "cloudflare" + status 403/503 + CF-specific body markers.
     */
    fun isCfResponse(serverHeader: String?, statusCode: Int, body: String): Boolean {
        if (serverHeader != "cloudflare") return false
        if (statusCode !in 403..503) return false
        return body.contains("challenge-platform") ||
                body.contains("cf-browser-verification") ||
                body.contains("Just a moment") ||
                body.contains("__CF\$cv") ||
                body.contains("cf-chl-") ||
                body.contains("Sorry, you have been blocked") ||
                body.contains("Attention Required") ||
                body.contains("/cdn-cgi/challenge-platform")
    }

    /**
     * Get cached cookies for a host, or null if expired/missing.
     */
    fun getCachedCookies(host: String): Map<String, String>? {
        val cookies = cookieCache[host] ?: return null
        val ts = cookieTimestamps[host] ?: return null
        return if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
            cookies.toMap()
        } else {
            cookieCache.remove(host)
            cookieTimestamps.remove(host)
            null
        }
    }

    /**
     * Solve Cloudflare challenge using WebViewResolver.
     *
     * Opens a REAL Android WebView popup. The CF Turnstile JavaScript runs
     * in the WebView, the user solves the challenge (or it auto-solves),
     * and cf_clearance cookie is set in CookieManager.
     *
     * After solving, cookies are cached for 30 minutes and can be retrieved
     * via getCachedCookies().
     *
     * @param url The CF-protected URL to solve
     * @return Map of cookies if successful, null if failed/timeout
     */
    suspend fun solveCf(url: String): Map<String, String>? {
        Log.d(TAG, ">> Opening WebView to solve CF for: $url")
        var solvedCookies: Map<String, String>? = null

        try {
            WebViewResolver(
                interceptUrl = Regex(".^"),       // NEVER matches → WebView stays open
                additionalUrls = listOf(Regex(".")), // Match every URL → callback fires for all
                userAgent = null,                   // CRITICAL: Use WebView default UA (not custom!)
                useOkhttp = false,                  // CRITICAL: OkHttp CANNOT solve CF Turnstile
                timeout = 90_000L                   // 90 seconds for slow connections
            ).resolveUsingWebView(url = url) { request ->
                // This callback fires for EVERY URL the WebView loads/navigates to.
                // Check if cf_clearance cookie is now set for the host.
                try {
                    val cookieStr = CookieManager.getInstance().getCookie(url)
                    if (cookieStr != null && cookieStr.contains("cf_clearance")) {
                        Log.d(TAG, ">> cf_clearance cookie detected! CF challenge SOLVED!")
                        solvedCookies = parseCookieString(cookieStr)
                        return@resolveUsingWebView true  // true = destroy WebView immediately
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Callback error: ${e.message}")
                }
                false // Keep waiting
            }

            if (solvedCookies != null) {
                val host = url.substringAfter("://").substringBefore("/")
                val mutableCookies = solvedCookies.toMutableMap()

                // Store WebView UA for future requests (CF ties clearance to UA)
                try {
                    WebViewResolver.webViewUserAgent?.let { ua ->
                        mutableCookies["_WebViewUA"] = ua
                        Log.d(TAG, "WebView UA: ${ua.take(60)}...")
                    }
                } catch (_: Exception) {}

                cookieCache[host] = mutableCookies
                cookieTimestamps[host] = System.currentTimeMillis()
                Log.d(TAG, ">> CF cookies cached for $host (${solvedCookies.size} cookies, TTL=${CACHE_TTL_MS / 1000}s)")
            } else {
                Log.w(TAG, ">> WebViewResolver finished but no cf_clearance cookie found (timeout?)")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">> WebViewResolver failed: ${e.message}")
            e.printStackTrace()
        }

        return solvedCookies
    }

    /**
     * Parse "key=value; key2=value2" cookie string into a Map.
     */
    private fun parseCookieString(str: String): Map<String, String> {
        return str.split(";").mapNotNull { cookie ->
            val trimmed = cookie.trim()
            val idx = trimmed.indexOf('=')
            if (idx > 0) trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
            else null
        }.toMap()
    }

    /**
     * Build "key=value; key2=value2" cookie header string from a Map.
     * Skips internal keys (prefixed with _).
     */
    fun buildCookieHeader(cookies: Map<String, String>): String {
        return cookies.entries
            .filter { !it.key.startsWith("_") }
            .joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * CF-safe GET request wrapper.
     *
     * Flow:
     *   1. Try with cached CF cookies (fast path — no WebView popup)
     *   2. Try normal app.get() (works if no CF or already solved)
     *   3. If CF detected → solveCf() → opens WebView popup
     *   4. Retry with fresh cookies
     *
     * @return Response text if successful, null if all attempts failed
     */
    suspend fun cfSafeGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long = 30_000L
    ): String? {
        val host = url.substringAfter("://").substringBefore("/").substringBefore("?")

        // ── Phase 1: Try with cached cookies (fast path) ──
        val cached = getCachedCookies(host)
        if (cached != null) {
            try {
                val h = headers.toMutableMap()
                h["Cookie"] = buildCookieHeader(cached)
                cached["_WebViewUA"]?.let { h["User-Agent"] = it }

                val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
                if (!isCfResponse(resp.headers["server"], resp.code, resp.text)) {
                    Log.d(TAG, "cfSafeGet: OK with cached cookies")
                    return resp.text
                }
                Log.d(TAG, "cfSafeGet: Cached cookies expired for $host")
            } catch (e: Exception) {
                Log.d(TAG, "cfSafeGet: Cached cookie request failed: ${e.message}")
            }
        }

        // ── Phase 2: Try normal request (no cookies) ──
        try {
            val resp = app.get(url, headers = headers, referer = referer, timeout = timeout)
            if (!isCfResponse(resp.headers["server"], resp.code, resp.text)) {
                Log.d(TAG, "cfSafeGet: OK without cookies (no CF)")
                return resp.text
            }
            Log.d(TAG, "cfSafeGet: CF detected for $host")
        } catch (e: Exception) {
            Log.d(TAG, "cfSafeGet: Normal request failed: ${e.message}")
        }

        // ── Phase 3: Solve CF via WebView ──
        val cookies = solveCf(url)
        if (cookies == null) {
            Log.w(TAG, "cfSafeGet: CF solve failed for $host")
            return null
        }

        // ── Phase 4: Retry with fresh cookies ──
        val h = headers.toMutableMap()
        h["Cookie"] = buildCookieHeader(cookies)
        cookies["_WebViewUA"]?.let { h["User-Agent"] = it }

        return try {
            val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
            if (!isCfResponse(resp.headers["server"], resp.code, resp.text)) {
                Log.d(TAG, "cfSafeGet: OK after CF solve")
            } else {
                Log.w(TAG, "cfSafeGet: CF still present after solving (cookies may be invalid)")
            }
            resp.text
        } catch (e: Exception) {
            Log.e(TAG, "cfSafeGet: Retry after CF solve failed: ${e.message}")
            null
        }
    }

    /**
     * CF-safe GET that returns both text and final URL (after redirects).
     * Useful when the caller needs the resolved URL.
     */
    suspend fun cfSafeGetWithUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long = 30_000L
    ): Pair<String?, String> {
        val host = url.substringAfter("://").substringBefore("/").substringBefore("?")
        var finalUrl = url

        // Phase 1: Cached cookies
        val cached = getCachedCookies(host)
        if (cached != null) {
            try {
                val h = headers.toMutableMap()
                h["Cookie"] = buildCookieHeader(cached)
                cached["_WebViewUA"]?.let { h["User-Agent"] = it }
                val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
                if (!isCfResponse(resp.headers["server"], resp.code, resp.text)) {
                    return resp.text to resp.url
                }
            } catch (_: Exception) {}
        }

        // Phase 2: Normal request
        try {
            val resp = app.get(url, headers = headers, referer = referer, timeout = timeout)
            if (!isCfResponse(resp.headers["server"], resp.code, resp.text)) {
                return resp.text to resp.url
            }
        } catch (_: Exception) {}

        // Phase 3: Solve CF
        val cookies = solveCf(url) ?: return null to url

        // Phase 4: Retry
        val h = headers.toMutableMap()
        h["Cookie"] = buildCookieHeader(cookies)
        cookies["_WebViewUA"]?.let { h["User-Agent"] = it }

        return try {
            val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
            resp.text to resp.url
        } catch (e: Exception) {
            Log.e(TAG, "cfSafeGetWithUrl retry failed: ${e.message}")
            null to url
        }
    }

    /**
     * Clear cached cookies (e.g., after they expire on the server).
     */
    fun clearCache(host: String? = null) {
        if (host != null) {
            cookieCache.remove(host)
            cookieTimestamps.remove(host)
        } else {
            cookieCache.clear()
            cookieTimestamps.clear()
        }
        // Also clear Android CookieManager for olamovies domains
        try {
            val cm = CookieManager.getInstance()
            listOf("olamovies.mov", "ol-am.top", "olamovies.dad").forEach { domain ->
                cm.removeAllCookies(null)
            }
        } catch (_: Exception) {}
    }
}
