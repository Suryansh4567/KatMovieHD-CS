package com.arena

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@CloudstreamPlugin
class PagalMoviesPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(PagalMoviesProvider())
    }

    companion object {
        const val DEFAULT_MAIN_URL = "https://www.pagalmovies.boutique"

        @Volatile
        private var cachedUrl: String? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

        private val MIRRORS = listOf(
            "https://www.pagalmovies.boutique",
            "https://www.pagalmovies.com.co",
            "https://www.pagalmovies.top",
            "https://www.pagalmovies.icu",
            "https://www.pagalmovies.art",
            "https://www.pagalmovies.cam",
            "https://www.pagalmovies.help",
            "https://www.pagalmovies.fun",
            "https://www.pagalmovies.live"
        )

        suspend fun getActiveMainUrl(forceRefresh: Boolean = false): String {
            val now = System.currentTimeMillis()
            val isFresh = !forceRefresh && (now - cachedAtMs) < CACHE_TTL_MS
            cachedUrl?.takeIf { isFresh && !it.isNullOrBlank() }?.let { return it.trimEnd('/') }

            return try {
                val active = probeDomains(MIRRORS)
                val finalUrl = active ?: cachedUrl ?: DEFAULT_MAIN_URL
                cachedUrl = finalUrl.trimEnd('/')
                cachedAtMs = now
                cachedUrl!!
            } catch (_: Throwable) {
                cachedUrl?.takeIf { !it.isNullOrBlank() }?.trimEnd('/') ?: DEFAULT_MAIN_URL
            }
        }

        private suspend fun probeDomains(candidates: List<String>): String? {
            if (candidates.isEmpty()) return null
            var active: String? = null
            coroutineScope {
                val deferreds = candidates.distinct().map { url ->
                    async { if (isUsableDomain(url)) url else null }
                }
                for (d in deferreds) {
                    val result = d.await()
                    if (result != null) {
                        active = result
                        break
                    }
                }
            }
            return active?.trimEnd('/')
        }

        private suspend fun isUsableDomain(baseUrl: String): Boolean = try {
            val res = app.get(
                baseUrl.trimEnd('/') + "/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                timeout = 8
            )
            if (res.code == 404) return false
            val text = res.text.take(120_000)
            val lower = text.lowercase()
            val title = res.document.selectFirst("title")?.text()?.lowercase().orEmpty()
            
            val isCfBlocked = title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("verify you are human") ||
                lower.contains("cf-chl") ||
                lower.contains("challenge-running")

            if (isCfBlocked) return true

            lower.contains("pagalmovies") || lower.contains("movielist") || lower.contains("search.php")
        } catch (_: Throwable) {
            false
        }
    }
}
