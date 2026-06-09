package com.kmmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Plugin entry-point for KMMovies.
 *
 * Domains rotate periodically. We fetch the active domain from
 * domains.json in this repo (same pattern as KatMovieHD).
 * Updating one file on GitHub is enough to keep every user's
 * installed extension working without a rebuild.
 *
 * CRITICAL: Custom extractors MUST be registered here so that
 * CloudStream's loadExtractor() can find them by URL prefix.
 * Without registration, HubCloud/GDFlix URLs no-op silently,
 * which was the primary cause of the "Coming Soon" bug.
 */
@CloudstreamPlugin
class KMMoviesPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(KMMoviesProvider())

        // Mirror hosts used in download buttons — KMMovies links resolve
        // through magiclinks.lol → skydrop/kmphotos to these final hosters.
        // Without registering these, loadExtractor() cannot match the URLs.
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudLink())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixNew1())
        registerExtractorAPI(GDFlixNew17())
        registerExtractorAPI(GDFlixDotDev())
        // KMHD extractor handles links.kmhd.eu/file/ and /pack/ URLs
        // which some kmmovies pages cross-reference.
        registerExtractorAPI(KmhdExtractor())
    }

    companion object {
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when DOMAINS_URL is unreachable. */
        const val DEFAULT_MAIN_URL = "https://kmmovies.lol"

        @Volatile
        private var cached: Domains? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

        suspend fun getActiveMainUrl(): String {
            val now = System.currentTimeMillis()
            val isFresh = (now - cachedAtMs) < CACHE_TTL_MS
            cached?.takeIf { isFresh }?.kmmovies
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trimEnd('/') }

            return try {
                val fetched = app.get(DOMAINS_URL, timeout = 10).parsedSafe<Domains>()
                if (fetched?.kmmovies?.isNotBlank() == true) {
                    cached = fetched
                    cachedAtMs = now
                    fetched.kmmovies.trimEnd('/')
                } else {
                    cached?.kmmovies?.takeIf { it.isNotBlank() }?.trimEnd('/')
                        ?: DEFAULT_MAIN_URL
                }
            } catch (_: Throwable) {
                cached?.kmmovies?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: DEFAULT_MAIN_URL
            }
        }

        data class Domains(
            @JsonProperty("kmmovies") val kmmovies: String? = null
        )
    }
}
