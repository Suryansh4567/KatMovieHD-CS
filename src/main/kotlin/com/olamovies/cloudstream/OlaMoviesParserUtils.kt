package com.olamovies.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object OlaMoviesParserUtils {

    fun parseSearchCards(doc: Document, baseUrl: String): List<SearchResponse> {
        return doc.select("article, .post, .entry-image").mapNotNull { element ->
            parseCard(element, baseUrl)
        }.distinctBy { it.url }
    }

    private fun parseCard(element: Element, baseUrl: String): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        val url = if (href.startsWith("http")) href else baseUrl + href

        val titleEl = element.selectFirst(OlaMoviesConstants.SELECTOR_CARD_TITLE)
            ?: element.selectFirst("a")
        val title = titleEl?.text()?.trim() ?: return null

        val img = element.selectFirst(OlaMoviesConstants.SELECTOR_CARD_IMG)
            ?: element.selectFirst("img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }

        val fullPoster = poster?.let { if (it.startsWith("http")) it else baseUrl + it }

        val isSeries = title.contains("Season", ignoreCase = true) ||
                title.contains("S0", ignoreCase = true) ||
                url.contains("tv-series", ignoreCase = true) ||
                element.selectFirst(".entry-category a")?.text()?.contains("TV", ignoreCase = true) == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = fullPoster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fullPoster
            }
        }
    }

    fun parseMovieLoadResponse(doc: Document, url: String, baseUrl: String): MovieLoadResponse? {
        val title = doc.selectFirst(OlaMoviesConstants.SELECTOR_DETAIL_TITLE)?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null

        val poster = extractPoster(doc, baseUrl)
        val synopsis = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim()
            ?: doc.select("meta[name=description]").attr("content").takeIf { it.isNotBlank() }

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
        }
    }

    fun parseTvSeriesLoadResponse(doc: Document, url: String, baseUrl: String): TvSeriesLoadResponse? {
        val title = doc.selectFirst(OlaMoviesConstants.SELECTOR_DETAIL_TITLE)?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null

        val poster = extractPoster(doc, baseUrl)
        val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim()
            ?: doc.select("meta[name=description]").attr("content")

        // Extract season number
        val seasonMatch = Regex("""Season\s*(\d+)|S(\d{1,2})""", RegexOption.IGNORE_CASE).find(title)
        val season = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

        // === FIX 3: Extract individual episodes ===
        val episodes = mutableListOf<Episode>()

        // Look for episode links on the page (e.g. "episode 01", "episode 02")
        val episodeLinks = doc.select("a[href]").filter { el ->
            val text = el.text().lowercase()
            text.contains("episode") || Regex("ep\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        if (episodeLinks.isNotEmpty()) {
            episodeLinks.forEachIndexed { index, a ->
                val href = a.attr("href")
                val epText = a.text()
                val epNum = Regex("(?:episode|ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                episodes.add(
                    newEpisode(href) {
                        this.name = epText.ifBlank { "Episode $epNum" }
                        this.season = season
                        this.episode = epNum
                    }
                )
            }
        } else {
            // Fallback: Season pack model (no individual episodes)
            episodes.add(
                newEpisode(url) {
                    this.name = "Season $season - Full Pack"
                    this.season = season
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    private fun extractPoster(doc: Document, baseUrl: String): String? {
        val img = doc.selectFirst(OlaMoviesConstants.SELECTOR_DETAIL_POSTER)
            ?: doc.selectFirst("img.wp-post-image")
            ?: doc.selectFirst("meta[property=\"og:image\"]")

        return when {
            img == null -> null
            img.tagName() == "meta" -> img.attr("content")
            else -> img.attr("src") ?: img.attr("data-src")
        }?.let { if (it.startsWith("http")) it else baseUrl + it }
    }

    fun extractDownloadLinks(doc: Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()

        // Primary: links that contain "episode" or quality labels
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            val text = a.text().trim()

            if (href.contains("links.ol-am.top") || 
                href.contains("drive.google.com") || 
                text.lowercase().contains("episode") ||
                Regex("\\d{3,4}p", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                
                if (href.startsWith("http") && !href.contains("olamovies") && !href.contains("youtube")) {
                    links.add(text.ifBlank { "Download" } to href)
                }
            }
        }

        // Also capture GDrive links directly
        doc.select("a[href*='drive.google.com']").forEach { a ->
            val href = a.attr("href")
            val label = a.text().ifBlank { a.selectFirst("span")?.text() ?: "Google Drive" }
            links.add(label to href)
        }

        return links.distinctBy { it.second }
    }

    fun parseQuality(label: String): Int {
        return when {
            label.contains("2160", true) || label.contains("4K", true) || label.contains("HDR", true) -> Qualities.P2160.value
            label.contains("1080", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}