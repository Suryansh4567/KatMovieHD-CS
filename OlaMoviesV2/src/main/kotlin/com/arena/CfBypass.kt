package com.arena

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Cloudflare bypass utility for CloudStream extensions.
 *
 * Uses WebViewResolver (library module — available to extensions) to open
 * a REAL Android WebView popup where CF Turnstile is solved.
 * After solving, cf_clearance cookies are cached and reused for subsequent requests.
 *
 * v23 — CRITICAL FIX based on logcat analysis:
 *
 *   BUG #1 (v22): interceptUrl = Regex(".^") NEVER matches anything!
 *     → WebViewResolver callback NEVER fires via interceptUrl
 *     → additionalUrls callbacks only LOG, they DON'T close the WebView
 *     → User has to manually press back — but they dismiss it after 1 sec
 *     → FIX: interceptUrl = Regex(".*olamovies.mov.*") → matches the target URL
 *       After CF challenge completes, page redirects back to olamovies.mov
 *       → callback fires → cf_clearance detected → returns true → AUTO-CLOSE!
 *
 *   BUG #2 (v22): Auto-click JavaScript is useless + harmful
 *     → CF Turnstile is interactive (needs checkbox click in iframe)
 *     → Auto-click JS runs in parent page, can't interact with Turnstile iframe
 *     → JS returns null → confusing "JS result: null" in logcat
 *     → Might interfere with Turnstile widget loading
 *     → FIX: Removed auto-click JS entirely. User solves challenge naturally.
 *
 *   BUG #3 (v22): No logging in callback when cf_clearance NOT found
 *     → Silent callback returns make debugging impossible
 *     → FIX: Added detailed logging for every callback invocation
 *
 * FLOW:
 *   1. app.get() fails with 403 + Server: cloudflare
 *   2. CfBypass.solveCf() opens WebViewResolver
 *   3. User sees CF challenge page, solves Turnstile (clicks checkbox)
 *   4. CF sets cf_clearance cookie, redirects back to original URL
 *   5. interceptUrl callback fires → detects cf_clearance → AUTO-CLOSES WebView
 *   6. Cookies cached for 30 minutes (subsequent requests = no popup)
 *   7. Extension retries app.get() with Cookie + WebView UA headers
 *   8. Request succeeds!
 */
object CfBypass {
    private const val TAG = "CfBypass"

    /** Cached CF cookies per host: host → (cookieMap, timestampMs) */
    private val cookieCache = mutableMapOf<String, MutableMap<String, String>>()
    private val cookieTimestamps = mutableMapOf<String, Long>()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Regex to match olamovies URLs for WebViewResolver interceptUrl.
     *
     * WHY THIS MATTERS (v22 bug fix):
     *   v22 used Regex(".^") which NEVER matches (impossible pattern).
     *   This meant the interceptUrl callback NEVER fired via the primary
     *   intercept mechanism. additionalUrls was used instead, but
     *   additionalUrls callbacks only LOG — they DON'T close the WebView.
     *
     *   With this fix:
     *   1. WebView loads CF challenge page (on challenges.cloudflare.com)
     *      → URL doesn't match → callback doesn't fire → WebView stays open
     *   2. User solves Turnstile challenge
     *   3. CF redirects back to olamovies.mov (original URL)
     *      → URL MATCHES → callback fires → cf_clearance detected → true
     *   4. WebView AUTO-CLOSES → cookies cached → retry with cookies
     */
    private val OLA_URL_REGEX = Regex(""".*olamovies\.mov.*"")

    /**
     * Check if an HTTP response is a Cloudflare block/challenge page.
     *
     * @return "challenge" if it's a solvable Turnstile challenge,
     *         "blocked" if it's a hard block (can't solve),
     *         null if not Cloudflare at all
     */
    fun detectCfType(serverHeader: String?, statusCode: Int, body: String): String? {
        if (serverHeader != "cloudflare") return null
        if (statusCode !in 403..503) return null

        val lowerBody = body.lowercase()

        // Hard block — cannot be solved
        if (lowerBody.contains("sorry, you have been blocked") ||
            lowerBody.contains("access denied") ||
            lowerBody.contains("error 1020")
        ) {
            return "blocked"
        }

        // Solvable challenge — Turnstile or JS challenge
        if (lowerBody.contains("challenge-platform") ||
            lowerBody.contains("cf-browser-verification") ||
            lowerBody.contains("just a moment") ||
            lowerBody.contains("checking your browser") ||
            lowerBody.contains("__cf\$cv") ||
            lowerBody.contains("cf-chl-") ||
            lowerBody.contains("attention required") ||
            lowerBody.contains("/cdn-cgi/challenge-platform") ||
            lowerBody.contains("cf-turnstile")
        ) {
            return "challenge"
        }

        // Cloudflare but unknown type — treat as challenge (try solving)
        return "challenge"
    }

    /**
     * Check if an HTTP response is a Cloudflare response (any type).
     * Backward-compatible wrapper.
     */
    fun isCfResponse(serverHeader: String?, statusCode: Int, body: String): Boolean {
        return detectCfType(serverHeader, statusCode, body) != null
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
     * Opens a REAL Android WebView popup. The CF Turnstile widget runs
     * in the WebView. User interacts with it naturally (clicks checkbox).
     *
     * 3-LAYER COOKIE DETECTION (v23 critical fix):
     *
     *   Layer 1 (auto-close): interceptUrl callback detects cf_clearance during
     *     navigation → returns true → WebView auto-closes with cookies.
     *
     *   Layer 2 (return value): WebViewResolver.resolveUsingWebView() RETURNS cookies
     *     from CookieManager when WebView closes (back press or timeout).
     *     PREVIOUS BUG: We were IGNORING this return value! Even though the user
     *     solved the challenge and pressed back, cookies were lost!
     *
     *   Layer 3 (direct check): After WebViewResolver finishes, check CookieManager
     *     directly for cf_clearance. Last resort fallback.
     *
     * v23 FIXES:
     *   FIX #1: interceptUrl = OLA_URL_REGEX (matches olamovies.mov)
     *   FIX #2: CAPTURE resolveUsingWebView() return value! (was ignored!)
     *   FIX #3: Direct CookieManager check as last resort
     *   FIX #4: Detailed logging at every step
     *
     * @param url The CF-protected URL to solve
     * @return Map of cookies if successful, null if failed/timeout/hard-block
     */
    suspend fun solveCf(url: String): Map<String, String>? {
        val host = url.substringAfter("://").substringBefore("/")
        Log.d(TAG, ">> solveCf: Opening WebView for $url (host=$host)")

        var solvedCookies: Map<String, String>? = null

        try {
            // v23 FIX #2: CAPTURE THE RETURN VALUE!
            // resolveUsingWebView() returns cookies from CookieManager when
            // the WebView closes. We were ignoring this entire return value!
            val webViewResult = WebViewResolver(
                interceptUrl = OLA_URL_REGEX,      // v23 FIX #1: Matches olamovies.mov URLs
                userAgent = null,                   // CRITICAL: Use WebView default UA
                useOkhttp = false,                  // CRITICAL: OkHttp CANNOT solve CF
                timeout = 90_000L                   // 90 seconds
            ).resolveUsingWebView(url = url) { request ->
                // Layer 1: Callback detects cf_clearance during navigation
                try {
                    val requestUrl = request.url.toString()
                    Log.d(TAG, ">> callback fired for: ${requestUrl.take(80)}")

                    val cookieStr = CookieManager.getInstance().getCookie(requestUrl)
                        ?: CookieManager.getInstance().getCookie(url)

                    if (cookieStr != null && cookieStr.contains("cf_clearance")) {
                        Log.d(TAG, ">> LAYER 1 HIT: cf_clearance in callback! AUTO-CLOSE")
                        solvedCookies = parseCookieString(cookieStr)
                        return@resolveUsingWebView true  // true = destroy WebView
                    }

                    Log.d(TAG, ">> LAYER 1: no cf_clearance yet for ${requestUrl.take(50)}")
                } catch (e: Exception) {
                    Log.d(TAG, ">> callback error: ${e.message}")
                }
                false // Keep waiting
            }

            // Layer 2: WebViewResolver returned cookies (user pressed back after solving)
            if (solvedCookies == null && webViewResult.isNotEmpty()) {
                Log.d(TAG, ">> LAYER 2 HIT: WebViewResolver returned ${webViewResult.size} cookies!")
                Log.d(TAG, ">> LAYER 2 cookies: ${webViewResult.keys.joinToString()}")
                solvedCookies = webViewResult
            }

            // Layer 3: Direct CookieManager check (last resort)
            if (solvedCookies == null) {
                try {
                    val cm = CookieManager.getInstance()
                    val directCookies = cm.getCookie(url) ?: cm.getCookie("https://$host")
                    if (directCookies != null && directCookies.contains("cf_clearance")) {
                        Log.d(TAG, ">> LAYER 3 HIT: cf_clearance found in CookieManager directly!")
                        solvedCookies = parseCookieString(directCookies)
                    } else {
                        Log.d(TAG, ">> LAYER 3: no cf_clearance in CookieManager")
                        Log.d(TAG, ">> CookieManager state: ${directCookies?.take(80) ?: "empty"}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, ">> LAYER 3 check failed: ${e.message}")
                }
            }

            Log.d(TAG, ">> solveCf result: ${if (solvedCookies != null) "SUCCESS (${solvedCookies.size} cookies)" else "FAILED"}")

            if (solvedCookies != null) {
                val mutableCookies = solvedCookies.toMutableMap()

                // Store WebView UA (CF ties clearance to UA)
                try {
                    WebViewResolver.webViewUserAgent?.let { ua ->
                        mutableCookies["_WebViewUA"] = ua
                        Log.d(TAG, ">> WebView UA: ${ua.take(60)}...")
                    } ?: Log.w(TAG, ">> WebView UA is null!")
                } catch (_: Exception) {}

                cookieCache[host] = mutableCookies
                cookieTimestamps[host] = System.currentTimeMillis()
                Log.d(TAG, ">> CF SOLVED! Cookies cached for $host (TTL=${CACHE_TTL_MS / 1000}s)")
            } else {
                Log.w(TAG, ">> solveCf FAILED: no cf_clearance in ANY layer")
                Log.w(TAG, ">> User may have pressed back before challenge loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">> solveCf EXCEPTION: ${e.message}")
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
     *   3. If CF challenge detected → solveCf() → opens WebView popup
     *   4. If CF hard block → return null immediately (can't solve)
     *   5. Retry with fresh cookies
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
        Log.d(TAG, "cfSafeGet: $url (host=$host)")

        // ── Phase 1: Try with cached cookies (fast path) ──
        val cached = getCachedCookies(host)
        if (cached != null) {
            Log.d(TAG, "cfSafeGet Phase 1: Trying cached cookies...")
            try {
                val h = headers.toMutableMap()
                h["Cookie"] = buildCookieHeader(cached)
                cached["_WebViewUA"]?.let { h["User-Agent"] = it }

                val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
                val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
                if (cfType == null) {
                    Log.d(TAG, "cfSafeGet Phase 1: OK with cached cookies (${resp.text.length} chars)")
                    return resp.text
                }
                Log.d(TAG, "cfSafeGet Phase 1: CF still present ($cfType) — cached cookies expired")
            } catch (e: Exception) {
                Log.d(TAG, "cfSafeGet Phase 1: Failed: ${e.message}")
            }
        }

        // ── Phase 2: Try normal request (no cookies) ──
        Log.d(TAG, "cfSafeGet Phase 2: Normal request...")
        try {
            val resp = app.get(url, headers = headers, referer = referer, timeout = timeout)
            val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
            when (cfType) {
                null -> {
                    Log.d(TAG, "cfSafeGet Phase 2: OK — no CF (${resp.text.length} chars)")
                    return resp.text
                }
                "blocked" -> {
                    Log.w(TAG, "cfSafeGet Phase 2: CF HARD BLOCK for $host — cannot solve!")
                    // Still try WebViewResolver — the user's device might get a different response
                }
                "challenge" -> {
                    Log.d(TAG, "cfSafeGet Phase 2: CF challenge detected for $host — solving...")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "cfSafeGet Phase 2: Request threw: ${e.message}")
        }

        // ── Phase 3: Solve CF via WebView ──
        Log.d(TAG, "cfSafeGet Phase 3: Solving CF via WebViewResolver...")
        val cookies = solveCf(url)
        if (cookies == null) {
            Log.w(TAG, "cfSafeGet: CF solve FAILED for $host — returning null")
            return null
        }

        // ── Phase 4: Retry with fresh cookies ──
        Log.d(TAG, "cfSafeGet Phase 4: Retrying with ${cookies.size} cookies...")
        val h = headers.toMutableMap()
        h["Cookie"] = buildCookieHeader(cookies)
        cookies["_WebViewUA"]?.let { h["User-Agent"] = it }

        return try {
            val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
            val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
            if (cfType == null) {
                Log.d(TAG, "cfSafeGet Phase 4: SUCCESS after CF solve! (${resp.text.length} chars)")
            } else {
                Log.w(TAG, "cfSafeGet Phase 4: CF still present after solving ($cfType)")
            }
            resp.text
        } catch (e: Exception) {
            Log.e(TAG, "cfSafeGet Phase 4: Retry failed: ${e.message}")
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
