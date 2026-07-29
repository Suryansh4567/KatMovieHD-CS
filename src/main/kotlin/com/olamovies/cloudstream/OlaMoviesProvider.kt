package com.olamovies.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class OlaMoviesProvider : MainAPI() {
    override var mainUrl = OlaMoviesConstants.BASE_URL
    override var name = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val client = app

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        
        val doc = withContext(Dispatchers.IO) {
            Jsoup.connect(url)
                .userAgent(OlaMoviesConstants.USER_AGENT)
                .get()
        }

        val homeList = mutableListOf<HomePageList>()

        // Recent / Featured section
        val recent = doc.select("article, .post").mapNotNull {
            OlaMoviesParserUtils.parseSearchCards(doc, mainUrl).firstOrNull()
        }.distinctBy { it.url }.take(20)

        if (recent.isNotEmpty()) {
            homeList.add(
                HomePageList(
                    "Recent Releases",
                    recent,
                    isHorizontalImages = true
                )
            )
        }

        // Category sections (sample)
        val categories = listOf(
            "movies/hollywood" to "Hollywood Movies",
            "tv-series/hindi-tv-series" to "Hindi TV Series"
        )

        for ((cat, label) in categories) {
            try {
                val catDoc = withContext(Dispatchers.IO) {
                    Jsoup.connect("$mainUrl/category/$cat/")
                        .userAgent(OlaMoviesConstants.USER_AGENT)
                        .get()
                }
                val cards = OlaMoviesParserUtils.parseSearchCards(catDoc, mainUrl).take(8)
                if (cards.isNotEmpty()) {
                    homeList.add(HomePageList(label, cards))
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(homeList, hasNext = doc.select("a.next, .next.page-numbers").isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        
        val doc = withContext(Dispatchers.IO) {
            Jsoup.connect(searchUrl)
                .userAgent(OlaMoviesConstants.USER_AGENT)
                .get()
        }

        return OlaMoviesParserUtils.parseSearchCards(doc, mainUrl)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = withContext(Dispatchers.IO) {
            Jsoup.connect(url)
                .userAgent(OlaMoviesConstants.USER_AGENT)
                .get()
        }

        val isSeries = url.contains("tv-series", ignoreCase = true) ||
                doc.selectFirst(".entry-category a")?.text()?.contains("TV", ignoreCase = true) == true ||
                doc.text().contains("Season", ignoreCase = true)

        return if (isSeries) {
            OlaMoviesParserUtils.parseTvSeriesLoadResponse(doc, url, mainUrl)
                ?: newTvSeriesLoadResponse("Unknown Series", url, TvType.TvSeries, emptyList())
        } else {
            OlaMoviesParserUtils.parseMovieLoadResponse(doc, url, mainUrl)
                ?: newMovieLoadResponse("Unknown Movie", url, TvType.Movie, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = withContext(Dispatchers.IO) {
            Jsoup.connect(data)
                .userAgent(OlaMoviesConstants.USER_AGENT)
                .get()
        }

        val links = OlaMoviesParserUtils.extractDownloadLinks(doc)
        var emitted = false

        links.forEach { (label, url) ->
            try {
                val quality = OlaMoviesParserUtils.parseQuality(label)

                // Use loadExtractor for known hosts (GDrive + others)
                val success = loadExtractor(
                    url = url,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = { extracted ->
                        callback(
                            extracted.copy(
                                name = "OlaMovies • $label",
                                quality = if (quality != Qualities.Unknown.value) quality else extracted.quality
                            )
                        )
                    }
                )

                if (success) {
                    emitted = true
                    return@forEach
                }

                // Final fallback direct link
                callback(
                    newExtractorLink(
                        source = name,
                        name = "OlaMovies • $label",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
                emitted = true
            } catch (e: Exception) {
                // log and continue (self-healing)
            }
        }

        return emitted
    }
}