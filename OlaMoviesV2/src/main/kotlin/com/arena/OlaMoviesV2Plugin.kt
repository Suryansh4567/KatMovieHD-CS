package com.arena

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
 * Architecture mirrors KatMovieHDPlugin:
 *   - Dynamic domain via shared domains.json in this repo
 *   - 6-hour cache TTL so domain bumps propagate without app restart
 *   - Triple-fallback URL resolution
 *
 * The site uses a Cloudflare-protected link shortener (links.ol-am.top
 * which redirects to links.olamovies.mov) which resolves to HubCloud /
 * GDFlix / Google Drive URLs. We register extractors for all known mirror
 * hosts plus our custom OlaLinks resolver with multi-strategy CF bypass.
 */
@CloudstreamPlugin
class OlaMoviesV2Plugin : BasePlugin() {

    override fun load() {
        registerMainAPI(OlaMoviesV2Provider())

        // ─── Custom OlaMovies link shortener extractors ────────────────
        // links.ol-am.top redirects to links.olamovies.mov — both need
        // to be handled. OlaLinks uses multi-strategy CF bypass:
        //   1. Direct GET with CF bypass → check response.url
        //   2. Page scraping for meta-refresh / known host links
        //   3. Manual HTTP redirect following
        //   4. HDHub4U-style JS deobfuscation
        //   5. WebViewResolver as ultimate CF bypass
        registerExtractorAPI(OlaLinks())
        registerExtractorAPI(OlaLinksMov())

        // ─── HubCloud ecosystem ────────────────────────────────────────
        // mainUrl = "https://hubcloud.*" — wildcard matches any TLD
        // via CloudStream's FuzzySearch fallback in loadExtractor()
        registerExtractorAPI(OlaHubCloud())
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
         * Same domains.json as KatMovieHDPlugin — one file, three keys.
         * The "olamovies" key is for this plugin.
         */
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when DOMAINS_URL is unreachable. */
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

        /**
         * Shared with KatMovieHDPlugin — same JSON shape, three optional keys.
         * Jackson's @JsonProperty default = null means a missing key
         * doesn't crash deserialisation.
         */
        data class Domains(
            @JsonProperty("katmoviehd") val katmoviehd: String? = null,
            @JsonProperty("katmovie4k") val katmovie4k: String? = null,
            @JsonProperty("olamovies") val olamovies: String? = null
        )
    }
}
