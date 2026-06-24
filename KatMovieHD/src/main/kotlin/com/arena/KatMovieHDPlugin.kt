package com.arena
import kotlinx.coroutines.async

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
 * Result is cached with a short TTL and wrapped in a safe try/catch so
 * that a transient network failure simply falls back to the last-known
 * good URL (or the one shipped in the APK).
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
        registerExtractorAPI(GDFlixNewIO())
        registerExtractorAPI(KatDriveClick())
        registerExtractorAPI(GDFlixNew18())
        registerExtractorAPI(GDFlixDad())
        registerExtractorAPI(GDLinkDev())

        // Additional strong extractors
        registerExtractorAPI(Filepress())
        registerExtractorAPI(DriveSeed())
        registerExtractorAPI(DriveLeech())
        registerExtractorAPI(HGLINK())
        registerExtractorAPI(FuckingFast())
        registerExtractorAPI(BBUpload())
        registerExtractorAPI(BBServer())
        registerExtractorAPI(GDTot())
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
        @Volatile
        private var cachedUrl: String? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        /**
         * Cache TTL: re-fetch domains.json from GitHub every 6 hours.
         *
         * Previously the cache was process-lifetime (set once, never
         * refreshed). That meant: when KatMovieHD moved domain and we
         * pushed an update to domains.json, users whose app had been
         * running >1 day still hit the old cached URL until they killed
         * and re-launched CloudStream. With a 6-hour TTL the new domain
         * propagates automatically within at most 6 hours of next request.
         */
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L  // 6 hours

        suspend fun getActiveMainUrl(): String {
            val now = System.currentTimeMillis()
            val isFresh = (now - cachedAtMs) < CACHE_TTL_MS
            cachedUrl?.takeIf { isFresh && it.isNotBlank() }?.let { return it.trimEnd('/') }

            return try {
                val fetched = app.get(DOMAINS_URL, timeout = 10).parsedSafe<Domains>()
                val candidates = buildDomainCandidates(fetched).toMutableList()

                // Try provided candidates first
                var active: String? = null
                kotlinx.coroutines.coroutineScope {
                    val deferreds = candidates.map { url ->
                        async {
                            if (isUsableKatDomain(url)) url else null
                        }
                    }
                    // Find the first successful domain as soon as it resolves
                    for (d in deferreds) {
                        val result = d.await()
                        if (result != null) {
                            active = result
                            break
                        }
                    }
                }
                
                // Future-proofing: If none of the static candidates work, fetch from proxy aggregators dynamically
                if (active == null) {
                    // Limit to 10 to avoid freezing the app's OkHttp thread pool
                    val dynamicDomains = getDynamicFallbackDomains().take(10)
                    candidates.addAll(dynamicDomains)
                    
                    kotlinx.coroutines.coroutineScope {
                        val deferreds = dynamicDomains.map { url ->
                            async {
                                if (isUsableKatDomain(url)) url else null
                            }
                        }
                        for (d in deferreds) {
                            val result = d.await()
                            if (result != null) {
                                active = result
                                break
                            }
                        }
                    }
                }
                
                // Fallback to top candidate if all probes fail
                if (active == null) {
                    active = candidates.firstOrNull() ?: cachedUrl ?: DEFAULT_MAIN_URL
                }

                cached = fetched
                cachedUrl = active.trimEnd('/')
                cachedAtMs = now
                cachedUrl!!
            } catch (_: Throwable) {
                // Network/parse failure - prefer a stale cached entry over
                // the hardcoded default so we don't accidentally fall back
                // to a long-dead URL when the latest one is still in memory.
                cachedUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: cached?.katmoviehd?.takeIf { it.isNotBlank() }?.trimEnd('/')
                    ?: DEFAULT_MAIN_URL
            }
        }

        private fun buildDomainCandidates(domains: Domains?): List<String> = buildList {
            domains?.katmoviehd?.let { add(it) }
            domains?.katmoviehdCandidates?.let { addAll(it) }
            domains?.katmoviehdFallbacks?.let { addAll(it) }
            add(DEFAULT_MAIN_URL)
        }
            .mapNotNull { normalizeBaseUrl(it) }
            .distinct()

        private fun normalizeBaseUrl(url: String?): String? {
            val u = url?.trim()?.trimEnd('/') ?: return null
            if (u.isBlank()) return null
            val fullUrl = when {
                u.startsWith("https://", ignoreCase = true) -> u
                u.startsWith("http://", ignoreCase = true) -> u.replaceFirst("http://", "https://")
                else -> "https://$u"
            }
            // Extract just the protocol + host
            return try {
                val uri = java.net.URI(fullUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                null
            }
        }

        private suspend fun isUsableKatDomain(baseUrl: String): Boolean = try {
            val res = app.get(
                baseUrl.trimEnd('/') + "/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                timeout = 8
            )
            val text = res.text.take(120_000)
            val lower = text.lowercase()
            val title = res.document.selectFirst("title")?.text()?.lowercase().orEmpty()
            val isCfBlocked = title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("verify you are human") ||
                lower.contains("cf-chl") ||
                lower.contains("ray id") ||
                lower.contains("challenge-running")

            // Future-proofing: If it's returning a Cloudflare challenge, it's alive!
            // Since we only probe candidate domains (from GitHub/Telegram/Proxies),
            // a CF challenge is a positive signal that the server is up and protected.
            if (isCfBlocked) return true

            lower.contains("katmoviehd") ||
            lower.contains("katmovie") ||
            lower.contains("li id=\"post-") ||
            lower.contains("wp-content")
        } catch (_: Throwable) {
            false
        }


        private suspend fun getDynamicFallbackDomains(): List<String> {
            val results = mutableListOf<String>()
            try {
                // Source 1: KatWorld proxy list
                val doc = app.get("https://katworld.net/?type=KatmovieHD", timeout = 10).document
                results.addAll(doc.select("a[href], link[href]").mapNotNull { it.attr("href") }
                    .filter { it.contains("katmovie", ignoreCase = true) }
                    .mapNotNull { normalizeBaseUrl(it) })
            } catch(e: Exception) {}

            try {
                // Source 2: CreativePixelMag proxy list
                val doc = app.get("https://www.creativepixelmag.com/katmoviehd-proxy/", timeout = 10).document
                val text = doc.html()
                val regex = Regex("""https?://[a-zA-Z0-9.-]*katmovie[a-zA-Z0-9.-]*/?""", RegexOption.IGNORE_CASE)
                results.addAll(regex.findAll(text).map { m -> m.value }.mapNotNull { normalizeBaseUrl(it) }.toList())
            } catch(e: Exception) {}
            
            return results.distinct()
        }

        data class Domains(
            @JsonProperty("katmoviehd") val katmoviehd: String? = null,
            @JsonProperty("katmoviehd_candidates") val katmoviehdCandidates: List<String>? = null,
            @JsonProperty("katmoviehd_fallbacks") val katmoviehdFallbacks: List<String>? = null
        )
    }
}
