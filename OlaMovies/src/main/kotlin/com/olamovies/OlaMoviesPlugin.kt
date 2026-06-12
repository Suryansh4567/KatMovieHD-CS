package com.olamovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Plugin entry-point for OlaMovies.
 *
 * Domains rotate periodically. We fetch the active domain from
 * domains.json in this repo (same pattern as KatMovieHD / KMMovies).
 * Updating one file on GitHub is enough to keep every user's
 * installed extension working without a rebuild.
 *
 * CRITICAL: Custom extractors MUST be registered here so that
 * CloudStream's loadExtractor() can find them by URL prefix.
 * OlaMovies uses OMDrive (links.ol-am.top) for link generation
 * and play.ol-am.top for direct streaming.
 */
@CloudstreamPlugin
class OlaMoviesPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(OlaMoviesProvider())

        // OMDrive extractor handles links.ol-am.top/{id} URLs.
        // These are the link generator pages that resolve to
        // Google Drive / Mega / direct download URLs.
        registerExtractorAPI(OMDriveExtractor())
        registerExtractorAPI(OMDriveExtractorAlt())

        // play.ol-am.top direct streaming extractor.
        // Serves MKV files directly with Range support, no CF.
        registerExtractorAPI(OlaPlayExtractor())
    }

    companion object {
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when DOMAINS_URL is unreachable. */
        const val DEFAULT_MAIN_URL = "https://v2.olamovies.mov"

        @Volatile
        private var cached: Domains? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

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

        data class Domains(
            @JsonProperty("olamovies") val olamovies: String? = null
        )
    }
}
