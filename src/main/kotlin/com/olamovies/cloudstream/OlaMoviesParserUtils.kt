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
            ?: img?.attr("data-lazy-src")

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

        // Extract season info from title
        val seasonMatch = Regex("""Season\s*(\d+)|S(\d{1,2})""", RegexOption.IGNORE_CASE).find(title)
        val season = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

        // We create a single episode representing the full season pack
        val episodes = listOf(
            newEpisode(url) {
                this.name = title
                this.season = season
                this.episode = 1
            }
        )

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

    fun extractGoogleDriveLinks(doc: Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()

        // Look for links containing "drive.google.com"
        doc.select("a[href*='drive.google.com']").forEach { a ->
            val href = a.attr("href")
            if (href.contains("drive.google.com")) {
                val label = a.text().trim().ifBlank { a.selectFirst("span")?.text() ?: "Google Drive" }
                links.add(label to href)
            }
        }

        // Also check inside buttons and wp-block-button
        doc.select(".wp-block-button a, a.wp-block-button__link").forEach { a ->
            val href = a.attr("href")
            if (href.contains("drive.google.com")) {
                val label = a.text().trim()
                links.add(label to href)
            }
        }

        // Fallback: look for any href mentioning drive in the entire page
        if (links.isEmpty()) {
            val driveLinks = doc.select("a[href]").filter { 
                it.attr("href").contains("drive.google", ignoreCase = true) 
            }
            driveLinks.forEach { a ->
                links.add(a.text().take(60) to a.attr("href"))
            }
        }

        return links.distinctBy { it.second }
    }

    fun parseQuality(label: String): Int {
        return when {
            label.contains("2160", true) || label.contains("4K", true) -> Qualities.P2160.value
            label.contains("1080", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}