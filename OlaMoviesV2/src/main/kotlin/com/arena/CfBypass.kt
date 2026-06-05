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
 * v23 — Three critical fixes based on QuickNovel CloudflareKiller analysis:
 *
 *   FIX #1: Added AUTO-CLICK JavaScript — CF Turnstile challenges often require
 *     clicking a submit button AFTER the token is generated. Without auto-click,
 *     the challenge never completes and the WebView times out.
 *
 *   FIX #2: Cookie check now uses request.url.toString() — the callback receives
 *     OkHttp Request objects; we must check cookies for the ACTUAL request URL,
 *     not the outer scope URL (which might differ for subresources/redirects).
 *
 *   FIX #3: Better CF detection — "Sorry, you have been blocked" (hard block) vs
 *     "Just a moment" (Turnstile challenge) are handled differently. Hard blocks
 *     cannot be solved via WebView and are reported immediately.
 *
 * FLOW:
 *   1. app.get() fails with 403 + Server: cloudflare
 *   2. CfBypass.solveCf() opens WebViewResolver
 *   3. Auto-click JS clicks Turnstile submit button when token appears
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
     * JavaScript to auto-click Cloudflare Turnstile submit button.
     *
     * Based on QuickNovel's CloudflareKiller pattern:
     *   1. Wait 2 seconds for Turnstile widget to load
     *   2. Check for cf-turnstile-response token in the form
     *   3. If token found + submit button exists → click it
     *   4. If not found → retry every 1 second for up to 60 seconds
     *
     * This handles BOTH:
     *   - Managed Turnstile (auto-solves, needs submit click)
     *   - Interactive Turnstile (user clicks checkbox, then auto-submit)
     */
    private val CF_AUTOCLICK_JS = """
(function() {
    if (window._cfClicked) return;
    
    function tryClick() {
        // Check multiple Turnstile token locations
        var token = 
            document.querySelector('[name="cf-turnstile-response"]')?.value ||
            document.querySelector('#cf-chl-widget-multi-token')?.value ||
            document.querySelector('[name="g-recaptcha-response"]')?.value ||
            document.querySelector('.cf-turnstile-response')?.value;
        
        if (token) {
            // Find and click submit button
            var btn = 
                document.querySelector('#challenge-form button[type="submit"]') ||
                document.querySelector('#challenge-form input[type="submit"]') ||
                document.querySelector('form[action*="cdn-cgi"] button') ||
                document.querySelector('form[action*="cdn-cgi"] input[type="submit"]') ||
                document.querySelector('#challenge-stage button');
            
            if (btn) {
                window._cfClicked = true;
                btn.click();
                return;
            }
        }
        
        // Not ready yet — retry
        if (!window._cfRetry) window._cfRetry = 0;
        if (window._cfRetry < 60) {
            window._cfRetry++;
            setTimeout(tryClick, 1000);
        }
    }
    
    // Start checking after 2 seconds (give Turnstile time to load)
    setTimeout(tryClick, 2000);
})();
""".trimIndent()

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
     * Opens a REAL Android WebView popup. The CF Turnstile JavaScript runs
     * in the WebView. Auto-click JS automatically clicks the submit button
     * when the Turnstile token is generated.
     *
     * v23 FIXES:
     *   - Auto-click JS clicks submit button after Turnstile solves
     *   - Cookie check uses request.url (not outer url) for accuracy
     *   - scriptCallback receives JS debug output
     *
     * @param url The CF-protected URL to solve
     * @return Map of cookies if successful, null if failed/timeout/hard-block
     */
    suspend fun solveCf(url: String): Map<String, String>? {
        val host = url.substringAfter("://").substringBefore("/")
        Log.d(TAG, ">> solveCf: Opening WebView for $url (host=$host)")

        var solvedCookies: Map<String, String>? = null

        try {
            WebViewResolver(
                interceptUrl = Regex(".^"),       // NEVER matches → WebView stays open until callback exits
                additionalUrls = listOf(Regex(".")), // Match every URL → callback fires for all
                userAgent = null,                   // CRITICAL: Use WebView default UA (not custom!)
                useOkhttp = false,                  // CRITICAL: OkHttp CANNOT solve CF Turnstile
                timeout = 90_000L,                  // 90 seconds for slow connections
                script = CF_AUTOCLICK_JS,           // v23: Auto-click Turnstile submit button
                scriptCallback = { result ->
                    Log.d(TAG, ">> JS result: $result")
                }
            ).resolveUsingWebView(url = url) { request ->
                // This callback fires for EVERY URL the WebView loads/navigates to.
                // Check if cf_clearance cookie is now set for the request URL.
                try {
                    // v23 FIX: Use request.url.toString() instead of outer url
                    // This correctly handles subresources/redirects
                    val requestUrl = request.url.toString()
                    val cookieStr = CookieManager.getInstance().getCookie(requestUrl)

                    // Also check the original host URL (cookies may be set at domain level)
                    val hostCookieStr = cookieStr
                        ?: CookieManager.getInstance().getCookie(url)

                    if (hostCookieStr != null && hostCookieStr.contains("cf_clearance")) {
                        Log.d(TAG, ">> cf_clearance DETECTED! Cookie: ${hostCookieStr.take(80)}...")
                        solvedCookies = parseCookieString(hostCookieStr)
                        return@resolveUsingWebView true  // true = destroy WebView immediately
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Callback error: ${e.message}")
                }
                false // Keep waiting
            }

            if (solvedCookies != null) {
                val mutableCookies = solvedCookies.toMutableMap()

                // Store WebView UA for future requests (CF ties clearance to UA)
                try {
                    WebViewResolver.webViewUserAgent?.let { ua ->
                        mutableCookies["_WebViewUA"] = ua
                        Log.d(TAG, ">> WebView UA cached: ${ua.take(60)}...")
                    } ?: run {
                        Log.w(TAG, ">> WebView UA is null after solve!")
                    }
                } catch (_: Exception) {}

                cookieCache[host] = mutableCookies
                cookieTimestamps[host] = System.currentTimeMillis()
                Log.d(TAG, ">> CF SOLVED! Cookies cached for $host (${solvedCookies.size} cookies, TTL=${CACHE_TTL_MS / 1000}s)")
            } else {
                Log.w(TAG, ">> solveCf: WebViewResolver finished but NO cf_clearance found (timeout or hard block)")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">> solveCf FAILED: ${e.message}")
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
