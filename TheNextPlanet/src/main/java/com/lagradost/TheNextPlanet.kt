package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

class TheNextPlanet : MainAPI() {
    override var name = "TheNextPlanet"
    override var mainUrl = "https://www.thenextplanet-official.space"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "https://www.thenextplanet-official.space/Bollywood/?page=1" to "Bollywood Movies",
        "https://www.thenextplanet-official.space/Hollywood/?page=1" to "Hollywood Movies",
        "https://www.thenextplanet-official.space/south-movies/?page=1" to "South Indian Movies",
        "https://www.thenextplanet-official.space/Webseries/?page=1" to "Web & TV Series"
    )

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""(?i)\b(download|hindi|dubbed|dual|audio|web-dl|webdl|bluray|cam-rip|camrip|hevc|hd-tc|hdtc|full|movie)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val articles = doc.select("article")
        return articles.mapNotNull { art ->
            val anchor = art.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href")

            // Block explicit/adult paths
            if (href.contains("adult", ignoreCase = true) || href.contains("hitclit", ignoreCase = true)) {
                return@mapNotNull null
            }

            val url = absoluteUrl(href)
            val pName = art.selectFirst("p.movie_name")?.text()?.trim()
            val rawTitle = pName ?: anchor.attr("title").trim()

            if (url.isBlank() || rawTitle.isBlank()) return@mapNotNull null

            val cleanName = cleanTitle(rawTitle)
            val posterImg = art.selectFirst("img")?.attr("src")
            val posterUrl = posterImg?.let { absoluteUrl(it) }

            val isSeries = rawTitle.contains("Season", ignoreCase = true) || 
                           rawTitle.contains("S0", ignoreCase = true) || 
                           rawTitle.contains("S1", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(cleanName, url, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http")) return url
        val clean = url.trimStart('/')
        return "$mainUrl/$clean"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("page=1", "page=$page")
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/searchmovie?name=${URLEncoder.encode(query, "UTF-8")}"
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"

        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { absoluteUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()

        val isSeries = rawTitle.contains("Season", ignoreCase = true) || 
                       rawTitle.contains("S0", ignoreCase = true) || 
                       rawTitle.contains("S1", ignoreCase = true)

        if (isSeries) {
            val episodesList = listOf(
                newEpisode(url) {
                    this.name = "Full Season"
                    this.episode = 1
                    this.season = 1
                    this.posterUrl = poster
                }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("Referer" to "$mainUrl/")

        try {
            // Step 1: Load detail page to find the /galaxy/ link
            val response = app.get(data, headers = headers).text
            val doc = Jsoup.parse(response)
            val galaxyHref = doc.selectFirst("a[href*=/galaxy/]")?.attr("href") ?: return false
            val galaxyUrl = absoluteUrl(galaxyHref)

            // Step 2: Fetch /galaxy/ page to find the /unlock-links/ link
            val galaxyResponse = app.get(galaxyUrl, headers = headers).text
            val galaxyDoc = Jsoup.parse(galaxyResponse)
            val shortenerHref = galaxyDoc.selectFirst("a[href*=solution-hub.com]")?.attr("href") 
                ?: galaxyDoc.selectFirst("a[href*=/unlock-links/]")?.attr("href") 
                ?: return false

            // Extract the direct unlock-links URL from the shortener link if present
            val unlockUrl = if (shortenerHref.contains("link=")) {
                val encodedUrl = shortenerHref.substringAfter("link=")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                absoluteUrl(shortenerHref)
            }

            // Step 3: Fetch the /unlock-links/ page
            val unlockResponse = app.get(unlockUrl, headers = headers).text
            val unlockDoc = Jsoup.parse(unlockResponse)

            // Step 4: Extract and resolve direct hoster links
            var foundAny = false
            val linkElements = unlockDoc.select("a[href*=/depisode/]")
            for (el in linkElements) {
                val href = el.attr("href")
                if (!href.contains("url=")) continue
                
                val finalUrl = href.substringAfter("url=")
                val decodedUrl = URLDecoder.decode(finalUrl, "UTF-8")
                val label = el.text().trim()

                // Filter out non-streamable links (like screenshots, etc.)
                if (decodedUrl.contains(".jpg", ignoreCase = true) || decodedUrl.contains(".png", ignoreCase = true)) {
                    continue
                }

                if (decodedUrl.contains("gdflix", ignoreCase = true) || decodedUrl.contains("mediafire", ignoreCase = true)) {
                    // Load the direct extractor if supported (like GDFlix, Mediafire)
                    loadExtractor(decodedUrl, referer = unlockUrl, subtitleCallback, callback)
                    foundAny = true
                } else {
                    // Emit direct link
                    val isM3u8 = decodedUrl.contains(".m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val quality = when {
                        decodedUrl.contains("1080") -> Qualities.P1080.value
                        decodedUrl.contains("720") -> Qualities.P720.value
                        decodedUrl.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            source = "TheNextPlanet",
                            name = if (label.isNotBlank()) "TheNextPlanet ($label)" else "TheNextPlanet Link",
                            url = decodedUrl,
                            type = linkType
                        ) {
                            this.referer = unlockUrl
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
            }

            return foundAny
        } catch (e: Exception) {
            return false
        }
    }
}
