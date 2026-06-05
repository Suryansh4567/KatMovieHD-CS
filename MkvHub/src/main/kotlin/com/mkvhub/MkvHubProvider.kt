package com.mkvhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class MkvHubProvider : MainAPI() {
    override var mainUrl = "https://www.mkvhub.beer"
    override var name = "MkvHub"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/punjabi-movies/" to "Punjabi Movies",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/tv-shows-hub/" to "TV Shows",
        "$mainUrl/category/1080p-movies/" to "1080p HD",
        "$mainUrl/category/bluray-movies-collection/" to "BluRay",
        "$mainUrl/category/tamil-movies/" to "Tamil Movies",
        "$mainUrl/category/telugu-movies/" to "Telugu Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("div.thumb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = doc.select("div.thumb").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.thumb").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val figcaption = selectFirst("figcaption") ?: return null
        val titleAnchor = figcaption.selectFirst("a") ?: return null
        val title = titleAnchor.text().trim().ifBlank { return null }
        val href = fixUrlNull(titleAnchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("figure img")?.attr("src"))

        val isSeries = title.contains(Regex("""S\d{2}""")) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Web Series", ignoreCase = true) ||
                title.contains("TV Show", ignoreCase = true)

        val quality = when {
            title.contains("4K", true) || title.contains("2160p", true) -> getQualityFromString("4k")
            title.contains("1080p", true) -> getQualityFromString("1080p")
            title.contains("720p", true) -> getQualityFromString("720p")
            else -> null
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw Exception("No title found")
        val posterUrl = fixUrlNull(
            doc.selectFirst("article img, .entry-content img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val plot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 50 }?.text()?.trim()

        val ratingText = doc.select("p, span, div")
            .firstOrNull { it.text().trim().startsWith("IMDb") }
            ?.text()?.trim()
        val score = ratingText?.let {
            Regex("""(\d+\.?\d*)/10""").find(it)?.groupValues?.get(1)
                ?.toFloatOrNull()?.let { Score.from10(it) }
        }

        val genreText = doc.select("p, span, div")
            .firstOrNull { it.text().trim().startsWith("Genres") }
            ?.text()?.trim()
        val tags = genreText?.substringAfter(":")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = Regex("""S\d{2}""").containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Web Series", ignoreCase = true) ||
                tags.any { it.contains("Web Series", true) || it.contains("TV Show", true) }

        val downloadLinks = extractDownloadLinks(doc)
        val linkData = downloadLinks.joinToString("\n") { "${it.first}|||${it.second}" }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(linkData) { name = title })) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    private fun extractDownloadLinks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        doc.select("h3").forEach { h3 ->
            val text = h3.text().trim()
            if (!text.contains("480p", true) && !text.contains("720p", true) &&
                !text.contains("1080p", true) && !text.contains("4K", true)
            ) return@forEach
            val nextEl = h3.nextElementSibling()
            if (nextEl != null) {
                val btn = nextEl.selectFirst("a.dbuttn[href]")
                if (btn != null) {
                    val link = btn.attr("href").trim()
                    if (link.startsWith("http")) {
                        result.add(text to link)
                    }
                }
            }
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val links = data.split("\n")
            .filter { it.contains("|||") }
            .mapNotNull {
                val parts = it.split("|||", limit = 2)
                if (parts.size == 2) {
                    val q = parts[0].trim()
                    val u = parts[1].trim()
                    if (u.startsWith("http")) q to u else null
                } else null
            }
            .distinctBy { it.second }

        if (links.isEmpty()) return false

        links.amap { (qualityText, url) ->
            try {
                val resolved = app.get(url, referer = mainUrl, timeout = 15000)
                val finalUrl = resolved.url
                val doc = resolved.document
                val hostLink = doc.selectFirst("a[href*=hubcloud], a[href*=gdflix], a[href*=gdtot], a[href*=hubdrive], a[href*=drive.google], a[href*=pixeldrain]")
                val iframe = doc.selectFirst("iframe[src]")
                val target = hostLink?.attr("href") ?: iframe?.attr("src") ?: finalUrl
                loadExtractor(target, url, subtitleCallback, callback)
                callback.invoke(
                    newExtractorLink(
                        name,
                        qualityText,
                        target,
                        mainUrl,
                        parseQuality(qualityText),
                        ExtractorLinkType.VIDEO
                    )
                )
            } catch (_: Exception) { }
        }
        return true
    }

    private fun parseQuality(text: String): Int {
        return when {
            text.contains("4K", true) || text.contains("2160p", true) -> Qualities.P2160.value
            text.contains("1080p", true) -> Qualities.P1080.value
            text.contains("720p", true) -> Qualities.P720.value
            text.contains("480p", true) -> Qualities.P480.value
            else -> Qualities.P720.value
        }
    }
}
