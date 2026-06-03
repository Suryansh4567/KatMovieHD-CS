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

        // ─── Feature #11: Full extractor registration ──────────────────
        // Professional extensions like HDHub4U register 10+ extractors.
        // Each registered ExtractorApi declares a mainUrl prefix, which
        // makes CloudStream's loadExtractor() router actually route to
        // our handler instead of silently no-op'ing.

        // KMHD-family extractor
        registerExtractorAPI(KmhdExtractor())

        // HubCloud ecosystem (Feature #3+#4)
        registerExtractorAPI(HubCloud())       // hubcloud.foo / hubcloud.lol
        registerExtractorAPI(Hubdrive())       // hubdrive.space — follows .btn-success1
        registerExtractorAPI(HUBCDN())         // hubcdn.* — extracts var reurl
        registerExtractorAPI(Hubcdnn())        // hubcdn.* — r=<base64> pattern
        registerExtractorAPI(Hubstream())      // hubstream.* — VidStack + AES decrypt
        registerExtractorAPI(Hubstreamdad())   // hblinks.* — recursive resolver
        registerExtractorAPI(Hblinks())        // hblinks.* — scrapes & dispatches

        // VidStack with AES decryption (Feature #12+#13)
        registerExtractorAPI(VidStack())       // vidstack.io

        // PixelDrain variant (Feature #3)
        registerExtractorAPI(PixelDrainDev())  // pixeldrain.dev

        // HdStream4u (Feature #3)
        registerExtractorAPI(HdStream4u())     // hdstream4u.com

        // GDFlix family — same registrations as KatMovieHD plugin
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixNew1())
        registerExtractorAPI(GDFlixNew17())
        registerExtractorAPI(GDFlixDotDev())
        registerExtractorAPI(GDFlixDad())      // new.gdflix.dad
        registerExtractorAPI(GDFlixDad3())     // new3.gdflix.dad
        registerExtractorAPI(GDFlixDad4())     // new4.gdflix.dad
        registerExtractorAPI(GDFlixRest())     // gdflix.rest
        registerExtractorAPI(GDFlixCfd5())     // new5.gdflix.cfd
        registerExtractorAPI(GDTotCfd())       // new10.gdtot.cfd
        registerExtractorAPI(GDLinkDev())      // gdlink.dev
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
