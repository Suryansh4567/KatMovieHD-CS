package com.uhdmovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class UHDMoviesProvider : MainAPI() {
    override var mainUrl = "https://hdmovieplus4u.com"
    override var name = "UHDMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TAG = "UHDMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/bollywood-movies/" to "Bollywood",
        "$mainUrl/hollywood-movies/" to "Hollywood",
        "$mainUrl/dual-audio-hindi-english-movies/" to "Dual Audio",
        "$mainUrl/dual-audio-hindi-english-movies/south-indian-dubbed-movies-download/" to "South Indian",
        "$mainUrl/tv-shows/web-series-hindi/" to "Web Series",
        "$mainUrl/tv-shows/hindi-dubbed-tv-shows/" to "Hindi Dubbed TV"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = doc.select("article.post-item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next") != null ||
            doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, headers = headers, timeout = 30).document
        return doc.select("article.post-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.entry-title a") ?: return null
        val title = titleEl.text().trim().ifBlank { return null }
        val href = titleEl.attr("href").ifBlank { return null }
        val img = selectFirst("img.blog-picture")
        val poster = img?.let { fixUrl(it.attr("src")) }

        val isSeries = title.contains("Season", ignoreCase = true) ||
            title.contains("Episode", ignoreCase = true) ||
            title.contains("[ALL EPISODES]", ignoreCase = true) ||
            href.contains("/tv-shows/")

        val quality = when {
            title.contains("2160p", ignoreCase = true) -> SearchQuality.P2160
            title.contains("1080p", ignoreCase = true) -> SearchQuality.P1080
            title.contains("720p", ignoreCase = true) -> SearchQuality.P720
            title.contains("480p", ignoreCase = true) -> SearchQuality.P480
            else -> null
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Unknown"

        val title = rawTitle
            .replace(Regex("""\s*Free\s+Download\s+UHDMovies\s*$"""), "")
            .replace(Regex("""\s*[-|]\s*UHDMovies\s*$"""), "")
            .trim()

        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""

        val isSeries = title.contains("Season", ignoreCase = true) ||
            title.contains("[ALL EPISODES]", ignoreCase = true) ||
            url.contains("/tv-shows/")

        // Parse download links from div.download-links-div
        val downloadDiv = doc.selectFirst("div.download-links-div")
        val links = mutableListOf<String>()
        downloadDiv?.select("a.btn")?.forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                links.add(href)
            }
        }

        // Also try fallback: any a.dwd-button or a.btn in entry-content
        if (links.isEmpty()) {
            doc.select("div.entry-content a.btn, div.entry-content a.dwd-button").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http")) {
                    links.add(href)
                }
            }
        }

        val data = links.joinToString("\n")
        Log.d(TAG, "load() found ${links.size} download links for $title")

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        return if (isSeries) {
            val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = newEpisode(data) {
                this.name = "All Episodes"
                this.season = seasonNum
                this.episode = 1
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")
        var anySuccess = false

        urls.amap { url ->
            try {
                // Try to resolve nexdrive redirector
                val resolved = resolveNexdrive(url)
                if (resolved.isEmpty()) {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    anySuccess = true
                } else {
                    resolved.amap { resolvedUrl ->
                        try {
                            loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)
                            anySuccess = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Extraction failed for $resolvedUrl: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve $url: ${e.message}")
                // Fallback: add as direct link
                try {
                    val quality = when {
                        url.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
                        url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                        url.contains("720p", ignoreCase = true) -> Qualities.P720.value
                        else -> Qualities.P720.value
                    }
                    callback(
                        newExtractorLink(name, "Direct", url, INFER_TYPE) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                    anySuccess = true
                } catch (_: Exception) {}
            }
        }

        return anySuccess
    }

    private suspend fun resolveNexdrive(url: String): List<String> {
        if (!url.contains("nexdrive", ignoreCase = true)) return emptyList()

        return try {
            val res = app.get(url, referer = mainUrl, headers = headers, timeout = 15000)
            val doc = res.document
            val results = mutableListOf<String>()

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("nexdrive", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            Log.d(TAG, "resolveNexdrive($url): found ${results.size} URL(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveNexdrive failed: ${e.message}")
            emptyList()
        }
    }
}
