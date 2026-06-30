package com.arena.raretoon

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class RareToonIndiaProvider : MainAPI() {
    override var mainUrl = "https://raretoonindia.in"
    override var name = "Rare Toon India"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/doraemon/" to "Doraemon",
        "/doraemon/episodes/" to "Doraemon Episodes",
        "/doraemon/all-movies/" to "Doraemon Movies",
        "/animes/" to "Animes",
        "/pokemon/" to "Pokemon",
        "/disney/" to "Disney",
        "/movies/" to "Movies",
        "/movies/shin-chan-movies/" to "Shin Chan Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers, timeout = 25).document
        val results = parseListing(doc)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 25).document
        return parseListing(doc).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers, timeout = 25).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img, .post img")?.absUrl("src")
        val plot = doc.select("article p, .post p, main p")
            .firstOrNull { it.text().length > 70 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val mirrors = collectByseLinks(doc)
        val isSeries = looksSeries(rawTitle) || mirrors.size > 2
        val episodes = if (isSeries) buildEpisodes(mirrors) else emptyList()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, guessType(rawTitle), episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val data = mirrors.firstOrNull()?.url ?: mirrors.joinToString("\n") { it.url }
            newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
                this.posterUrl = poster
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
        val urls = Regex("""https?://[^\s"'<>]+""").findAll(data)
            .map { it.value.trim().trim(')', ']', '.', ',', ';') }
            .distinct()
            .toList()
        if (urls.isEmpty()) return false
        urls.forEach { url ->
            when {
                url.contains("bysekoze.com", ignoreCase = true) -> {
                    ByseKozE().getUrl(url, mainUrl, subtitleCallback, callback)
                }
                isDirectVideo(url) -> {
                    callback.invoke(newExtractorLink(name, name, url) {
                        this.quality = directQuality(url)
                    })
                }
                else -> loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http", ignoreCase = true)) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = if (path.startsWith("http", true)) path.trimEnd('/') else mainUrl + path
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val items = doc.select("article, .post, li[id^=post-]").mapNotNull { it.toRareSearchResult() }
        if (items.isNotEmpty()) return items.distinctBy { it.url }
        return doc.select("a:has(img)").mapNotNull { it.toRareSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toRareSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title a[href], h3.entry-title a[href], h2 a[href], h3 a[href], .entry-title a[href]")
            ?: selectFirst("a[href]:has(img)")
            ?: takeIf { tagName().equals("a", true) }
            ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.ifBlank { return null }
        if (!href.contains("raretoonindia.in", ignoreCase = true)) return null
        if (listOf("/category/", "/tag/", "/page/", "#", "/dmca", "/contact", "/privacy", "/copyright", "/disclaimers", "/about-us").any { href.contains(it, true) }) return null
        val rawTitle = anchor.attr("title").ifBlank { anchor.text() }
            .ifBlank { selectFirst("img")?.attr("alt") ?: "" }
            .ifBlank { return null }
        val img = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }
        return newMovieSearchResponse(cleanTitle(rawTitle), href, guessType(rawTitle)) {
            this.posterUrl = poster
            this.quality = detectQuality(rawTitle)
        }
    }

    private data class Mirror(val url: String, val label: String?)

    private fun collectByseLinks(doc: Document): List<Mirror> {
        val content = doc.selectFirst("main, article, .post, body") ?: doc
        return content.select("a[href*='bysekoze.com']").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            // Prefer /d/ watch links over /download/ duplicates for the same code.
            val code = byseCode(href) ?: return@mapNotNull null
            val watchUrl = "https://bysekoze.com/d/$code"
            val parentText = a.parent()?.text()?.take(160)
            Mirror(watchUrl, parentText?.ifBlank { a.text() })
        }
            .distinctBy { byseCode(it.url) ?: it.url }
    }

    private fun buildEpisodes(mirrors: List<Mirror>): List<Episode> {
        return mirrors.mapIndexed { index, mirror ->
            val epNumber = episodeNumber(mirror.label) ?: (index + 1)
            newEpisode(mirror.url) {
                this.name = episodeName(mirror.label, epNumber)
                this.season = 1
                this.episode = epNumber
            }
        }.distinctBy { it.data }
    }

    private fun episodeNumber(text: String?): Int? {
        val t = text ?: return null
        return Regex("""(?i)(?:episode|ep|e)\s*[-:]?\s*(\d{1,3})""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodeName(text: String?, ep: Int): String {
        val cleaned = text
            ?.replace(Regex("""(?i)quickwatch|download|watch online"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.length in 5..80 } ?: "Episode $ep"
    }

    private fun byseCode(url: String): String? = Regex("""(?i)bysekoze\.com/(?:d|e|download|dwn)/([a-z0-9]+)""")
        .find(url)?.groupValues?.getOrNull(1)

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""(?i)\s*[-|–—]\s*Rare\s*Toons?\s*India.*$"""), "")
        .replace(Regex("""(?i)\b(download|watch online|episodes?|in hindi|hindi dubbed|hd)\b"""), " ")
        .replace(Regex("""\[[^]]*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun looksSeries(title: String): Boolean = Regex("""(?i)\b(season|episodes?|all seasons|s\d{1,2})\b""").containsMatchIn(title)

    private fun guessType(title: String): TvType = if (looksSeries(title)) TvType.Anime else TvType.Movie

    private fun detectQuality(title: String) = when {
        title.contains("1080", true) -> SearchQuality.HD
        title.contains("720", true) -> SearchQuality.HD
        title.contains("480", true) -> SearchQuality.SD
        else -> null
    }

    private fun directQuality(url: String): Int {
        Regex("""(?i)(\d{3,4})p""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Qualities.Unknown.value
    }

    private fun isDirectVideo(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm")
    }

    companion object {
        private const val TAG = "RareToonIndia"
    }
}
