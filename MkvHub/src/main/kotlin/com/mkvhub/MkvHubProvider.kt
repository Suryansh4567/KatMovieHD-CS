package com.mkvhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import org.jsoup.nodes.Element

class MkvHubProvider : MainAPI() {
    override var mainUrl = "https://www.mkvhub.beer"
    override var name = "MkvHub"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

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
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("section.home-wrapper div.thumb").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to USER_AGENT)).document
        return doc.select("section.home-wrapper div.thumb").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val img = selectFirst("figure img") ?: return null
        val titleEl = selectFirst("figcaption a p") ?: return null
        val linkEl = selectFirst("figcaption a") ?: selectFirst("a[href]") ?: return null
        
        val title = titleEl.text().trim().ifBlank { return null }
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val posterUrl = fixUrlNull(img.attr("src"))

        val isSeries = title.contains(Regex("""\bS\d{2}\b""")) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                href.contains("/category/web-series/") ||
                href.contains("/category/tv-shows-hub/")

        val quality = when {
            title.contains("4K", true) || title.contains("2160p", true) -> Qualities.P2160.value
            title.contains("1080p", true) -> Qualities.P1080.value
            title.contains("720p", true) -> Qualities.P720.value
            title.contains("480p", true) -> Qualities.P480.value
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
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        // Title from h1.page-title > span.material-text
        val title = doc.selectFirst("h1.page-title span.material-text")?.text()?.trim()
            ?: doc.selectFirst("h1.page-title")?.text()?.trim()
            ?: throw Exception("No title found")

        // Poster: main.page-body > p.poster > img
        val posterUrl = fixUrlNull(
            doc.selectFirst("main.page-body p.poster img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Parse movie info from main.page-body paragraphs
        val pageBody = doc.selectFirst("main.page-body") ?: doc
        val allText = pageBody.text()
        
        // Extract specific info
        var plot = ""
        var rating: Score? = null
        var year: Int? = null
        val tags = mutableListOf<String>()

        pageBody.select("p, div").forEach { el ->
            val text = el.text().trim()
            when {
                text.startsWith("IMDb", ignoreCase = true) -> {
                    val match = Regex("""(\d+\.?\d*)/10""").find(text)
                    match?.groupValues?.get(1)?.toFloatOrNull()?.let { 
                        rating = Score.from10(it) 
                    }
                }
                text.startsWith("Genres", ignoreCase = true) -> {
                    val genreStr = text.substringAfter(":").trim()
                    genreStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tags.add(it) }
                }
                text.startsWith("Storyline", ignoreCase = true) -> {
                    plot = text.substringAfter("Storyline").substringAfter(":").trim()
                }
            }
        }

        // Year from title
        year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Series detection from page-meta categories and title
        val isSeries = Regex("""\bS\d{2}\b""").containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                tags.any { it.equals("Web Series", true) || it.equals("TV Show", true) } ||
                doc.select("div.page-meta a em.material-text").any { 
                    it.text().trim().equals("Web Series", true) || it.text().trim().equals("TV Shows", true) 
                }

        // Use URL-based episode approach: loadLinks will re-fetch the page and extract links
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) { 
                    name = "Download Links" 
                }
            )) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { allText.take(500) }
                this.year = year
                this.tags = tags
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { allText.take(500) }
                this.year = year
                this.tags = tags
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || !data.startsWith("http")) return false

        // Re-fetch the movie page to extract download links
        val doc = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val pageBody = doc.selectFirst("main.page-body") ?: doc

        // Extract quality + linkszilla links from h3 + a.dbuttn pairs
        val qualityLinks = mutableListOf<Pair<String, String>>()
        pageBody.select("h3").forEach { h3 ->
            val h3Text = h3.text().trim()
            if (!h3Text.contains("480p", true) && !h3Text.contains("720p", true) &&
                !h3Text.contains("1080p", true) && !h3Text.contains("4K", true) &&
                !h3Text.contains("2160p", true)) return@forEach

            val qualityName = when {
                h3Text.contains("4K", true) || h3Text.contains("2160p", true) -> "4K"
                h3Text.contains("1080p", true) -> "1080p"
                h3Text.contains("720p", true) -> "720p"
                h3Text.contains("480p", true) -> "480p"
                else -> "HD"
            }

            val sizeMatch = Regex("""Size:\s*([\d.]+(?:MB|GB))""", RegexOption.IGNORE_CASE).find(h3Text)
            val sizeStr = sizeMatch?.groupValues?.get(1) ?: ""

            val nextSib = h3.nextElementSibling()
            if (nextSib != null) {
                val btn = nextSib.selectFirst("a.dbuttn")
                if (btn != null) {
                    val linkUrl = btn.attr("href").trim()
                    if (linkUrl.startsWith("http")) {
                        val label = buildString {
                            append(qualityName)
                            if (sizeStr.isNotBlank()) append(" • $sizeStr")
                        }
                        qualityLinks.add(label to linkUrl)
                    }
                }
            }
        }

        if (qualityLinks.isEmpty()) {
            // Fallback: try any a.dbuttn
            doc.select("main.page-body a.dbuttn").firstOrNull()?.let { btn ->
                val url = btn.attr("href").trim()
                if (url.startsWith("http")) {
                    qualityLinks.add("Download" to url)
                }
            }
        }

        if (qualityLinks.isEmpty()) return false

        // Now resolve each linkszilla page and extract the real download links
        qualityLinks.amap { (qualityLabel, linkszillaUrl) ->
            try {
                val resolved = app.get(linkszillaUrl, referer = mainUrl, headers = mapOf(
                    "User-Agent" to USER_AGENT
                ), timeout = 15000)

                // linkszilla shows multiple download links in a table/list
                // Extract all http links that are not linkszilla itself
                val realLinks = resolved.document.select("a[href]").mapNotNull { a ->
                    val href = a.attr("href").trim()
                    val linkText = a.text().trim()
                    if (href.startsWith("http") && 
                        !href.contains("linkszilla") && 
                        !href.contains("profitablecpmrate") &&
                        !href.contains("google") &&
                        linkText.isNotBlank()) {
                        linkText to href
                    } else null
                }.filter { it.second.startsWith("http") }

                if (realLinks.isEmpty()) {
                    // If no links extracted, pass the linkszilla URL directly as fallback
                    callback(
                        newExtractorLink(
                            "$name - $qualityLabel",
                            "$qualityLabel [Direct]",
                            resolved.url,
                            INFER_TYPE
                        ) {
                            this.quality = parseQuality(qualityLabel)
                            this.referer = linkszillaUrl
                        }
                    )
                } else {
                    realLinks.forEach { (hostName, linkUrl) ->
                        try {
                            loadExtractor(linkUrl, linkszillaUrl, subtitleCallback, callback)
                        } catch (_: Exception) {
                            // If loadExtractor fails, add as direct link
                            callback(
                                newExtractorLink(
                                    "$name - $qualityLabel",
                                    "$qualityLabel • $hostName",
                                    linkUrl,
                                    INFER_TYPE
                                ) {
                                    this.quality = parseQuality(qualityLabel)
                                    this.referer = linkszillaUrl
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // If linkszilla fetch fails entirely, try passing the URL directly
                try {
                    loadExtractor(linkszillaUrl, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
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
