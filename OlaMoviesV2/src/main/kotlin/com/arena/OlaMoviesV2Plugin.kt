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
 * v16 — CF + INTERMEDIATE FIX. Key changes:
 *   - Re-registered OlaLinks as ExtractorApi so loadExtractor() handles CF
 *   - dispatchExtractor() now uses loadExtractor() for OlaMovies short URLs
 *   - Anti-recursion: OlaLinks.getUrl() NEVER calls loadExtractor() on own URLs
 *   - Fixed intermediate site bypass (form[name='tp'] + #btn6 with delays)
 *   - Based on Greasy Fork "Bypass All Shortlinks" patterns
 */
@CloudstreamPlugin
class OlaMoviesV2Plugin : BasePlugin() {

    override fun load() {
        registerMainAPI(OlaMoviesV2Provider())

        // ─── OlaMovies shortener chain ─────────────────────────────────
        // v16: OlaLinks IS registered as ExtractorApi again!
        // This allows loadExtractor() to handle CF-protected short URLs.
        // Anti-recursion: OlaLinks.getUrl() NEVER calls loadExtractor() on
        // URLs matching ol-am.top / olamovies.mov / olamovies.download.
        registerExtractorAPI(OlaLinks())

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
