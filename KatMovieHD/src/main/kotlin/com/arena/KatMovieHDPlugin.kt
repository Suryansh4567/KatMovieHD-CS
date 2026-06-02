package com.arena

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Plugin entry-point.
 *
 * Domains for KatMovieHD rotate every few weeks. Rather than hard-coding
 * a URL inside the provider (which forces a full rebuild + reinstall
 * whenever the site moves), we fetch the active domain from a small JSON
 * file in this very repository. Updating one file on GitHub is then
 * enough to keep every user's installed extension working.
 *
 * Result is cached for the lifetime of the process and is wrapped in a
 * safe try/catch so that a transient network failure simply falls back
 * to the last-known good URL (the one shipped in the APK).
 */
@CloudstreamPlugin
class KatMovieHDPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(KatMovieHDProvider())

        // KMHD-specific extractor handles links.kmhd.eu/{file,play}/<id>.
        registerExtractorAPI(KmhdExtractor())

        // Mirror hosts used in download buttons.
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixNew1())
        registerExtractorAPI(GDFlixNew17())
        registerExtractorAPI(GDFlixDotDev())
    }

    companion object {
        /**
         * Public, single source of truth for the active site URL.
         * Pointed at this repo so anyone (including the user) can push
         * a one-line domain bump without recompiling.
         */
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"

        /** Fallback used when DOMAINS_URL is unreachable. */
        const val DEFAULT_MAIN_URL = "https://new1.katmoviehd.cymru"

        @Volatile
        private var cached: Domains? = null

        suspend fun getActiveMainUrl(): String {
            cached?.katmoviehd?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
            return try {
                val fetched = app.get(DOMAINS_URL, timeout = 10).parsedSafe<Domains>()
                if (fetched?.katmoviehd?.isNotBlank() == true) {
                    cached = fetched
                    fetched.katmoviehd.trimEnd('/')
                } else {
                    DEFAULT_MAIN_URL
                }
            } catch (_: Throwable) {
                DEFAULT_MAIN_URL
            }
        }

        data class Domains(
            @JsonProperty("katmoviehd") val katmoviehd: String? = null
        )
    }
}
