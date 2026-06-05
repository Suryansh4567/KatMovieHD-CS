package com.arena

import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * OlaMovies v2 plugin entry-point.
 *
 * Targets v2.olamovies.mov — a WordPress/Gridlove site that hosts 4K UHD,
 * HDR, Dolby Vision, and REMUX releases via Google Drive mirrors.
 *
 * v23 — CRITICAL CF BYPASS FIX (based on device logcat analysis)
 *
 *   v22 TESTING RESULTS (from actual device logcat):
 *     - WebViewResolver DID open (saw CF challenge URLs being loaded)
 *     - BUT: WebView closed after 1 second — user pressed back (saw nothing useful)
 *     - "JS result: null" in logcat — auto-click JS returning undefined
 *     - NO links found
 *
 *   ROOT CAUSES FOUND via logcat:
 *     BUG #1: interceptUrl = Regex(".^") NEVER matches anything!
 *       → WebViewResolver's main callback mechanism NEVER triggered
 *       → additionalUrls callbacks only LOG, they CANNOT close the WebView
 *       → User must manually press back — but they dismiss after 1 sec thinking it's broken
 *     BUG #2: Auto-click JS useless for interactive Turnstile (needs checkbox click in iframe)
 *     BUG #3: No callback logging when cf_clearance NOT found
 *
 *   v23 SOLUTION:
 *     - interceptUrl = Regex(".*olamovies.mov.*") → matches target URL
 *       After CF challenge completes, page redirects back to olamovies.mov
 *       → callback fires → cf_clearance detected → returns true → WebView AUTO-CLOSES!
 *     - Removed auto-click JS (was useless + confusing "JS result: null")
 *     - User solves Turnstile naturally (clicks checkbox in WebView)
 *     - Added detailed callback logging for debugging
 *
 *   Previous fixes still present:
 *   - CfBypass.kt — WebViewResolver with proper params (userAgent=null, useOkhttp=false)
 *   - Cookie caching (30 min TTL) — first time: WebView popup, then: cached cookies
 *   - v20: Tier 3 infinite loop fix, Tier 2 early CF detection
 */
@CloudstreamPlugin
class OlaMoviesV2Plugin : BasePlugin() {

    override fun load() {
        registerMainAPI(OlaMoviesV2Provider())

        // ─── OlaMovies shortener chain (v22 — CF WebView bypass) ───────────
        // OlaLinks handles links.ol-am.top — Tier 1 (regex) + Tier 2 (app.get chain)
        // OlaLinksMov handles links.olamovies.mov — CF bypass via CfBypass.solveCf()
        // v22: Uses CfBypass utility with PROPER WebViewResolver params from CloudflareKiller
        registerExtractorAPI(OlaLinks())
        registerExtractorAPI(OlaLinksMov())

        // ─── HubCloud ecosystem ────────────────────────────────────────
        registerExtractorAPI(OlaHubCloud())       // hubcloud.lol
        registerExtractorAPI(OlaHubCloudFoo())    // hubcloud.foo
        registerExtractorAPI(OlaHubCloudDad())    // hubcloud.dad
        registerExtractorAPI(OlaHubdrive())
        registerExtractorAPI(OlaHubstream())

        // ─── GDFlix family ─────────────────────────────────────────────
        registerExtractorAPI(OlaGDFlix())
        registerExtractorAPI(OlaGDFlixNet())
        registerExtractorAPI(OlaGDFlixNew1())
        registerExtractorAPI(OlaGDFlixNew17())
        registerExtractorAPI(OlaGDFlixDotDev())
        registerExtractorAPI(OlaGDFlixDad())
        registerExtractorAPI(OlaGDFlixDad3())
        registerExtractorAPI(OlaGDFlixDad4())
        registerExtractorAPI(OlaGDFlixRest())
        registerExtractorAPI(OlaGDFlixCfd5())
        registerExtractorAPI(OlaGDTotCfd())
        registerExtractorAPI(OlaGDLinkDev())

        // ─── PixelDrain variant ────────────────────────────────────────
        registerExtractorAPI(OlaPixelDrainDev())

        // ─── VidStack with AES decryption ──────────────────────────────
        registerExtractorAPI(OlaVidStack())
    }

    companion object {
        /**
         * Three-tier domain resolution:
         *   1. olamovies.dad/current.php — live site API (NO Cloudflare!)
         *   2. GitHub domains.json — manual fallback
         *   3. Hard-coded default
         */
        private const val OLA_CURRENT_API = "https://olamovies.dad/current.php"
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when all domain sources are unreachable. */
        const val DEFAULT_MAIN_URL = "https://v2.olamovies.mov"

        @Volatile
        private var cached: Domains? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L  // 6 hours

        suspend fun getActiveMainUrl(): String {
            val now = System.currentTimeMillis()
            val isFresh = (now - cachedAtMs) < CACHE_TTL_MS
            cached?.takeIf { isFresh }?.olamovies
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trimEnd('/') }

            // Tier 1: olamovies.dad/current.php — NO Cloudflare, returns plain text URL
            try {
                val apiResponse = app.get(OLA_CURRENT_API, timeout = 10).text.trim()
                if (apiResponse.startsWith("http") && apiResponse.contains("olamovies")) {
                    val url = apiResponse.trimEnd('/')
                    cached = Domains(olamovies = url)
                    cachedAtMs = now
                    Log.d("OlaMoviesV2", "Domain from API: $url")
                    return url
                }
            } catch (_: Throwable) {}

            // Tier 2: GitHub domains.json
            return try {
                val fetched = app.get(DOMAINS_URL, timeout = 10).parsedSafe<Domains>()
                if (fetched?.olamovies?.isNotBlank() == true) {
                    cached = fetched
                    cachedAtMs = now
                    fetched.olamovies.trimEnd('/')
                } else {
                    cached?.olamovies?.takeIf { it.isNotBlank() }?.trimEnd('/')
                        ?: DEFAULT_MAIN_URL
                }
            } catch (_: Throwable) {
                cached?.olamovies?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: DEFAULT_MAIN_URL
            }
        }

        data class Domains(
            @JsonProperty("katmoviehd") val katmoviehd: String? = null,
            @JsonProperty("katmovie4k") val katmovie4k: String? = null,
            @JsonProperty("olamovies") val olamovies: String? = null
        )
    }
}
