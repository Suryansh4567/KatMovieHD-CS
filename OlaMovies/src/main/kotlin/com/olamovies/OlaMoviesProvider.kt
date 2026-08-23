package com.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OlaMoviesProvider : MainAPI() {
    // v4.olamovies.mov is the current domain per the official OlaMovies
    // Telegram channel (@olamovies_officialv6) — v3.olamovies.mov is parked.
    // Verified live with fresh uploads on 2026-08-23.
    override var mainUrl = "https://v4.olamovies.mov"
    override var name = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/movies/hollywood/" to "Hollywood Movies",
        "$mainUrl/category/movies/anime-movies/" to "Anime Movies",
        "$mainUrl/category/tv-series/hindi-tv-series/" to "Hindi TV Series",
        "$mainUrl/category/tv-series/english-tv-series/" to "English TV Series",
        "$mainUrl/category/tv-series/korean-tv-series/" to "Korean TV Series",
    )

    data class SourceData(
        val url: String,
        val name: String,
        val size: String? = null
    )

    data class LinkPayload(
        val sources: List<SourceData>
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name ?: this.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleNode = selectFirst("h2.entry-title a, h2 a") ?: return null
        val title = titleNode.text().trim()
        val href = titleNode.attr("abs:href").takeIf { it.isNotBlank() } ?: return null
        val posterUrl = selectFirst("img")?.let {
            it.attr("abs:src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("abs:data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("abs:data-lazy-src")
        }
        
        val isSeries = title.contains(Regex("(?i)\\bS\\d+|Season|Complete|\\bE\\d+"))
                || href.contains("tv-series", true)
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        val poster = document.selectFirst(".wp-post-image, .thumb img")?.let {
            it.attr("abs:src").takeIf { s -> s.isNotBlank() } ?: it.attr("abs:data-src")
        }
        val plot = document.selectFirst(".synopsis p, .entry-content p")?.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(document.select(".year a, .date a, h1").text())?.value?.toIntOrNull()

        val actors = document.select(".cast a").map { it.text() }.filter { it.isNotBlank() }.distinct()
        val trailer = document.selectFirst("iframe[src*=youtube]")?.attr("abs:src")

        val isTvSeries = url.contains("tv-series", true) 
                || title.contains("Season", true)
                || document.selectFirst(".episodios, .episodes-container") != null

        val buttons = document.select("a.wp-block-button__link").mapNotNull { btn ->
            val linkUrl = btn.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val omName = btn.attr("data-om-name").ifBlank { btn.text() }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (omName.contains(Regex("(?i)Telegram|Join|Group|Admin|Contact"))) return@mapNotNull null
            SourceData(linkUrl, omName, btn.attr("data-om-size"))
        }

        if (isTvSeries) {
            val epMap = mutableMapOf<Pair<Int, Int>, MutableList<SourceData>>()
            buttons.forEach { source ->
                val epMatch = Regex("(?i)\\bS(\\d+)\\s*E(\\d+)\\b|episode\\s*(\\d+)|E(\\d+)", RegexOption.IGNORE_CASE).find(source.name)
                val seasonNum = epMatch?.groupValues?.get(1)?.toIntOrNull() 
                    ?: title.let { t -> Regex("(?i)Season\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() } 
                    ?: 1
                val epNum = epMatch?.groupValues?.let { g -> g[2].toIntOrNull() ?: g[3].toIntOrNull() ?: g[4].toIntOrNull() }
                if (epNum != null) {
                    val key = Pair(seasonNum, epNum)
                    epMap.getOrPut(key) { mutableListOf() }.add(source)
                }
            }

            val episodes = epMap.map { (key, sources) ->
                newEpisode(LinkPayload(sources).toJson()) {
                    this.name = "Episode ${key.second}"
                    this.episode = key.second
                    this.season = key.first
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkPayload(buttons).toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<LinkPayload>(data) }.getOrNull() ?: return false
        var count = 0

        payload.sources.amap { source ->
            runCatching {
                val resolver = WebViewResolver(
                    Regex("drive\\.google\\.com|mega\\.nz|dl\\.olamovies|pixeldrain|gofile")
                )
                val response = app.get(source.url, interceptor = resolver, referer = mainUrl)
                val finalUrl = response.url

                if (finalUrl.contains("drive.google.com") || finalUrl.contains("mega.nz")) {
                    if (loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)) {
                        count++
                    }
                } else if (finalUrl.contains(Regex("\\.(mp4|mkv|m3u8|webm)"))) {
                    callback(
                        newExtractorLink(source.name, this.name, finalUrl) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(source.name)
                        }
                    )
                    count++
                }
            }
        }
        return count > 0
    }
}
