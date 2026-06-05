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
 * v24 — COMPLETE REWRITE based on CloudStream source code analysis:
 *
 *   STUDIED: CloudStream repo (recloudstream/cloudstream)
 *     - WebViewResolver: library/src/commonMain/kotlin/.../network/WebViewResolver.kt
 *     - CloudflareKiller: app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt
 *     - ExtractorApi: library/src/commonMain/kotlin/.../utils/ExtractorApi.kt
 *
 *   KEY DISCOVERY — WebViewResolver behavior:
 *     When interceptUrl matches a URL → callback fires → WebView IMMEDIATELY DESTROYED.
 *     This means if interceptUrl matches the INITIAL page URL, the WebView opens and
 *     closes instantly — user sees nothing!
 *
 *   CloudflareKiller's CORRECT approach (that we must follow):
 *     interceptUrl = Regex(".^")         → NEVER matches (impossible regex)
 *     additionalUrls = listOf(Regex(".")) → matches EVERY URL
 *     → callback fires on every navigation/subrequest
 *     → checks CookieManager for cf_clearance
 *     → if found → return true → WebView destroyed (by additionalUrls callback)
 *     → if not found → return false → WebView continues loading
 *
 *   WHY Regex(".^") IS CORRECT (NOT a bug!):
 *     - "." matches any char, "^" asserts start of string
 *     - A single char cannot be both "any char" AND "start of string" simultaneously
 *     - So it NEVER matches — this is intentional!
 *     - It prevents interceptUrl from auto-destroying the WebView prematurely
 *
 *   v23 BUG — what went wrong:
 *     Changed interceptUrl to Regex(".*olamovies.mov.*")
 *     → Initial URL (links.olamovies.mov) matched on FIRST load
 *     → WebView destroyed immediately → user saw nothing → "nhi hota"
 *
 *   FLOW (v24 — corrected):
 *     1. app.get() fails with 403 + Server: cloudflare
 *     2. CfBypass.solveCf() opens WebViewResolver
 *     3. WebViewResolver:
 *        - interceptUrl = Regex(".^") → never auto-destroys
 *        - additionalUrls = listOf(Regex(".")) → callback on every URL
 *        - useOkhttp = false → WebView handles requests natively
 *        - userAgent = null → WebView uses its own default UA
 *     4. User sees CF challenge page in WebView popup
 *     5. User solves Turnstile (clicks checkbox)
 *     6. CF sets cf_clearance cookie, redirects back
 *     7. additionalUrls callback detects cf_clearance via CookieManager
 *     8. callback returns true → WebView destroyed
 *     9. Cookies cached for 30 minutes
 *    10. Extension retries app.get() with cookies + WebView UA → success!
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
     * Follows CloudflareKiller's EXACT approach from CloudStream source:
     *   - interceptUrl = Regex(".^")        → NEVER matches (no auto-destroy)
     *   - additionalUrls = listOf(Regex(".")) → every URL triggers callback
     *   - Callback checks CookieManager for cf_clearance → true = destroy
     *   - userAgent = null → keeps WebView's native UA (CF requires this)
     *   - useOkhttp = false → WebView handles requests natively (CF needs this)
     *
     * After WebView closes (cf_clearance found / timeout / back press),
     * checks CookieManager directly as fallback.
     *
     * @param url The CF-protected URL to solve
     * @return Map of cookies if successful, null if failed/timeout/hard-block
     */
    suspend fun solveCf(url: String): Map<String, String>? {
        val host = url.substringAfter("://").substringBefore("/")
        Log.e(TAG, ">> solveCf: Opening WebView for $url (host=$host)")

        var solvedCookies: Map<String, String>? = null

        try {
            // ═══════════════════════════════════════════════════════════════════
            // CLOUDFLARE KILLER APPROACH — from CloudStream source code:
            //
            // interceptUrl = Regex(".^")  → impossible pattern, NEVER matches
            //   → interceptUrl callback NEVER fires
            //   → WebView is NEVER auto-destroyed by interceptUrl
            //   → WebView stays open until WE decide to close it
            //
            // additionalUrls = listOf(Regex("."))  → matches EVERY URL
            //   → callback fires on EVERY request the WebView makes
            //   → (main page, iframes, XHR, redirects, everything)
            //   → if callback returns true → WebView destroyed (our control)
            //   → if callback returns false → WebView continues loading
            //
            // This is the ONLY correct way to use WebViewResolver for CF bypass!
            // ═══════════════════════════════════════════════════════════════════
            WebViewResolver(
                interceptUrl = Regex(".^"),                    // NEVER matches
                additionalUrls = listOf(Regex(".")),            // Match every URL
                userAgent = null,                               // Keep WebView native UA
                useOkhttp = false,                              // Native request handling
                timeout = 90_000L                               // 90 seconds
            ).resolveUsingWebView(url = url) { request ->
                // This callback fires for EVERY URL the WebView loads
                // (initial page, CF challenge iframe, redirects, etc.)
                try {
                    val requestUrl = request.url.toString()
                    Log.e(TAG, ">> callback: ${requestUrl.take(80)}")

                    // Check CookieManager for cf_clearance on every navigation
                    // After Turnstile is solved, CF sets cf_clearance cookie
                    // and redirects back → this callback will detect it
                    val cm = CookieManager.getInstance()
                    val cookieStr = cm.getCookie(requestUrl)
                        ?: cm.getCookie(url)
                        ?: cm.getCookie("https://$host")

                    if (cookieStr != null && cookieStr.contains("cf_clearance")) {
                        Log.e(TAG, ">> cf_clearance FOUND! Destroying WebView...")
                        solvedCookies = parseCookieString(cookieStr)
                        return@resolveUsingWebView true  // true = destroy WebView
                    }

                    Log.d(TAG, ">> no cf_clearance yet...")
                } catch (e: Exception) {
                    Log.d(TAG, ">> callback error: ${e.message}")
                }
                false // Continue loading — don't destroy WebView
            }

            // ═══════════════════════════════════════════════════════════════════
            // FALLBACK: Check CookieManager after WebView closes
            // This catches: user pressed back, or cookie was set after last callback
            // ═══════════════════════════════════════════════════════════════════
            if (solvedCookies == null) {
                try {
                    val cm = CookieManager.getInstance()
                    val directCookies = cm.getCookie(url)
                        ?: cm.getCookie("https://$host")
                        ?: cm.getCookie("https://www.$host")

                    if (directCookies != null && directCookies.contains("cf_clearance")) {
                        Log.d(TAG, ">> FALLBACK: cf_clearance found in CookieManager!")
                        solvedCookies = parseCookieString(directCookies)
                    } else {
                        Log.d(TAG, ">> FALLBACK: no cf_clearance (cookies: ${directCookies?.take(60) ?: "empty"})")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, ">> FALLBACK check failed: ${e.message}")
                }
            }

            Log.e(TAG, ">> solveCf result: ${if (solvedCookies != null) "SUCCESS (${solvedCookies.size} cookies)" else "FAILED"}")

            if (solvedCookies != null) {
                val mutableCookies = solvedCookies.toMutableMap()

                // Save WebView UA — CF requires same UA for subsequent requests
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
                Log.w(TAG, ">> solveCf FAILED: no cf_clearance found")
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
        Log.e(TAG, "cfSafeGet: $url (host=$host)")

        // ── Phase 1: Try with cached cookies (fast path) ──
        val cached = getCachedCookies(host)
        if (cached != null) {
            Log.e(TAG, "cfSafeGet Phase 1: Trying cached cookies...")
            try {
                val h = headers.toMutableMap()
                h["Cookie"] = buildCookieHeader(cached)
                cached["_WebViewUA"]?.let { h["User-Agent"] = it }

                val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
                val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
                if (cfType == null) {
                    Log.e(TAG, "cfSafeGet Phase 1: OK (${resp.text.length} chars)")
                    return resp.text
                }
                Log.d(TAG, "cfSafeGet Phase 1: CF still present ($cfType) — cached cookies expired")
            } catch (e: Exception) {
                Log.d(TAG, "cfSafeGet Phase 1: Failed: ${e.message}")
            }
        }

        // ── Phase 2: Try normal request (no cookies) ──
            Log.e(TAG, "cfSafeGet Phase 2: Normal request...")
        try {
            val resp = app.get(url, headers = headers, referer = referer, timeout = timeout)
            val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
            when (cfType) {
                null -> {
                    Log.e(TAG, "cfSafeGet Phase 2: OK — no CF (${resp.text.length} chars)")
                    return resp.text
                }
                "blocked" -> {
                    Log.w(TAG, "cfSafeGet Phase 2: CF HARD BLOCK for $host — cannot solve!")
                    // Still try WebViewResolver — the user's device might get a different response
                }
                "challenge" -> {
                    Log.e(TAG, "cfSafeGet Phase 2: CF challenge detected for $host — solving...")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "cfSafeGet Phase 2: Request threw: ${e.message}")
        }

        // ── Phase 3: Solve CF via WebView ──
        Log.e(TAG, "cfSafeGet Phase 3: Solving CF via WebViewResolver...")
        val cookies = solveCf(url)
        if (cookies == null) {
            Log.e(TAG, "cfSafeGet: CF solve FAILED for $host — returning null")
            return null
        }

        // ── Phase 4: Retry with fresh cookies ──
        Log.e(TAG, "cfSafeGet Phase 4: Retrying with ${cookies.size} cookies...")
        val h = headers.toMutableMap()
        h["Cookie"] = buildCookieHeader(cookies)
        cookies["_WebViewUA"]?.let { h["User-Agent"] = it }

        return try {
            val resp = app.get(url, headers = h, referer = referer, timeout = timeout)
            val cfType = detectCfType(resp.headers["server"], resp.code, resp.text)
            if (cfType == null) {
                Log.e(TAG, "cfSafeGet Phase 4: SUCCESS after CF solve! (${resp.text.length} chars)")
            } else {
                Log.e(TAG, "cfSafeGet Phase 4: CF still present after solving ($cfType)")
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
