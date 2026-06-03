package com.arena

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * KatMovie4K plugin entry-point.
 *
 * Sister plugin to KatMovieHD, targeting the same publisher's 4K Ultra HD
 * release portal (katmovie4k.mov). Architecture is intentionally identical
 * to KatMovieHDPlugin so a fix in one is trivially portable to the other:
 *
 *   - Dynamic domain via the SAME `domains.json` file in this repo's root
 *     (just a different key: "katmovie4k" instead of "katmoviehd"), so
 *     when either site rotates we update one file and both plugins follow.
 *   - 6-hour cache TTL so domain bumps propagate without app restart.
 *   - Triple-fallback URL resolution: in-memory cache → GitHub fetch →
 *     hardcoded default. Survives outages on either side.
 *
 * The shared mirror extractors (HubCloud, GDFlix*, KmhdExtractor) are
 * registered identically because katmovie4k.mov uses the SAME upload
 * hosts as katmoviehd: links.kmhd.net, ziddiflix.com (which is a kmhd
 * sister redirector), gdflix.dad subdomains, driveleech.org, vifix.site.
 */
@CloudstreamPlugin
class KatMovie4KPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(KatMovie4KProvider())

        // KMHD-family extractor — handles links.kmhd.{net,eu}/{file,play}/<id>.
        // Also wired here because KatMovie4K pages occasionally link to
        // kmhd.net/file/... for 1080p x264 alt mirrors (seen on Daredevil
        // Born Again S01 page).
        registerExtractorAPI(KmhdExtractor())

        // Standard GDFlix family — same registrations as KatMovieHD plugin.
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixNew1())
        registerExtractorAPI(GDFlixNew17())
        registerExtractorAPI(GDFlixDotDev())

        // KatMovie4K-specific extra hosts. Diagnosed v1 "no link found" bug:
        // these URLs were appearing on every 4K page but CloudStream's
        // loadExtractor() router silently no-op'd because no registered
        // ExtractorApi declared a matching mainUrl prefix. Each one below
        // is a thin subclass of GDFlix with the correct mainUrl, which
        // makes CS's prefix-based dispatch actually route to our handler.
        // See Extractors.kt v2 docs at the bottom of the file for the
        // full redirect-chain analysis.
        registerExtractorAPI(GDFlixDad())      // new.gdflix.dad
        registerExtractorAPI(GDFlixDad3())     // new3.gdflix.dad
        registerExtractorAPI(GDFlixDad4())     // new4.gdflix.dad
        registerExtractorAPI(GDFlixRest())     // gdflix.rest
        registerExtractorAPI(GDFlixCfd5())     // new5.gdflix.cfd
        registerExtractorAPI(GDTotCfd())       // new10.gdtot.cfd
        registerExtractorAPI(GDLinkDev())      // gdlink.dev (post-redirect target)
        registerExtractorAPI(Ziddiflix())      // ziddiflix.com
        registerExtractorAPI(Vifix())          // vifix.site
        registerExtractorAPI(Appdrive())       // appdrive.lol
        registerExtractorAPI(Driveleech())     // driveleech.org
        registerExtractorAPI(DriveleechPro())  // driveleech.pro
        registerExtractorAPI(DriveleechNet())  // driveleech.net
    }

    companion object {
        /**
         * Same domains.json as KatMovieHDPlugin — one file, two keys.
         * Updating "katmovie4k" there is enough to redirect every installed
         * user without rebuilding either plugin.
         */
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when DOMAINS_URL is unreachable. */
        const val DEFAULT_MAIN_URL = "https://katmovie4k.mov"

        @Volatile
        private var cached: Domains? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        /**
         * Cache TTL: re-fetch domains.json from GitHub every 6 hours.
         *
         * Mirrors the policy in KatMovieHDPlugin so behaviour is uniform
         * across both sister plugins. 6 hours strikes a balance between
         * "propagates fast when we push a domain bump" and "doesn't hammer
         * GitHub raw on every search".
         */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L  // 6 hours

        suspend fun getActiveMainUrl(): String {
            val now = System.currentTimeMillis()
            val isFresh = (now - cachedAtMs) < CACHE_TTL_MS
            cached?.takeIf { isFresh }?.katmovie4k
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trimEnd('/') }

            return try {
                val fetched = app.get(DOMAINS_URL, timeout = 10).parsedSafe<Domains>()
                if (fetched?.katmovie4k?.isNotBlank() == true) {
                    cached = fetched
                    cachedAtMs = now
                    fetched.katmovie4k.trimEnd('/')
                } else {
                    // Bad JSON but network ok — keep stale cache over hardcoded default.
                    cached?.katmovie4k?.takeIf { it.isNotBlank() }?.trimEnd('/')
                        ?: DEFAULT_MAIN_URL
                }
            } catch (_: Throwable) {
                // Network/parse failure — prefer stale cache over default.
                cached?.katmovie4k?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: DEFAULT_MAIN_URL
            }
        }

        /**
         * Shared with KatMovieHDPlugin — same JSON shape, two optional keys.
         * Either plugin will simply skip the field it doesn't care about.
         * (Jackson's @JsonProperty default = null means a missing key
         * doesn't crash deserialisation.)
         */
        data class Domains(
            @JsonProperty("katmoviehd") val katmoviehd: String? = null,
            @JsonProperty("katmovie4k") val katmovie4k: String? = null
        )
    }
}
