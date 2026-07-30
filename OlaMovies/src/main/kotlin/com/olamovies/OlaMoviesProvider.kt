package com.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class OlaMoviesProvider : MainAPI() {
    override var mainUrl = "https://v3.olamovies.mov"
    override var name = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/movies/hollywood/" to "Hollywood Movies",
        "$mainUrl/category/movies/anime-movies/" to "Anime Movies",
        "$mainUrl/category/tv-series/hindi-tv-series/" to "Hindi TV Series",
        "$mainUrl/category/tv-series/english-tv-series/" to "English TV Series",
        "$mainUrl/category/tv-series/korean-tv-series/" to "Korean TV Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2 a")?.text() ?: return null
        val href = selectFirst("h2 a")?.attr("abs:href") ?: return null
        val posterUrl = selectFirst("img")?.attr("abs:src")
        
        return if (title.contains("Season", true) || title.contains("S0", true)) {
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst(".wp-post-image")?.attr("abs:src")
        val plot = document.selectFirst(".synopsis p, .entry-content p")?.text()
        val year = document.selectFirst(".year a")?.text()?.toIntOrNull()
        val tvType = if (url.contains("tv-series") || title.contains("Season", true)) TvType.TvSeries else TvType.Movie

        val actors = document.select(".cast a").map { it.text() }
        val trailer = document.selectFirst("iframe[src*=youtube]")?.attr("src")

        if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            
            document.select("a.wp-block-button__link").forEach { link ->
                val linkName = link.attr("data-om-name").ifEmpty { link.text() }
                val linkUrl = link.attr("abs:href")
                val size = link.attr("data-om-size")
                
                val epMatch = Regex("""episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(linkName)
                    ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(linkName)
                
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(linkName)
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (epNum != null) {
                    episodes.add(newEpisode(linkUrl) {
                        this.name = "$linkName ($size)"
                        this.episode = epNum
                        this.season = seasonNum
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieLinks = document.select("a.wp-block-button__link").map {
                "${it.attr("abs:href")}|${it.attr("data-om-name")}|${it.attr("data-om-size")}"
            }.joinToString(";")

            return newMovieLoadResponse(title, url, TvType.Movie, movieLinks) {
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
        val sources = if (data.contains(";")) data.split(";") else listOf(data)

        sources.map { source ->
            val parts = source.split("|")
            val url = parts[0]
            val name = parts.getOrNull(1) ?: "OlaMovies"
            val size = parts.getOrNull(2) ?: ""
            
            callback(
                newExtractorLink(
                    "$name ($size)",
                    this.name,
                    url,
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl
                }
            )
        }
        
        return true
    }
}
