package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KatMovieHDProvider : MainAPI() {

    override var mainUrl = "https://new1.katmoviehd.cymru"
    override var name = "KatMovieHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "page/"                              to "Latest",
        "category/dubbed-movie/page/"        to "Hindi Dubbed Movies",
        "category/dual-audio/page/"          to "Dual Audio",
        "category/tv-series-dubbed/page/"    to "TV Series (Dubbed)",
        "category/netflix/page/"             to "Netflix",
        "category/amzn-prime-video/page/"    to "Prime Video",
        "category/hotstar/page/"             to "Hotstar",
        "category/k-drama/page/"             to "K-Drama",
        "category/hindi-dubbed/page/"        to "Hindi Dubbed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}$page/"
        val doc = app.get(url, headers = headers).document
        val items = parseListing(doc)
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = app.get(url, headers = headers).document
        return parseListing(doc).toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val direct = doc.select("article, .post").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct

        return doc.select("a:has(img)")
            .mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2 a, h1 a, .entry-title a")
            ?: selectFirst("a[href*=katmoviehd]")
            ?: selectFirst("a") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val titleRaw = anchor.attr("title").ifBlank { anchor.text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(titleRaw),
            fixUrl(href),
            guessTvType(titleRaw)
        ) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        if (!href.contains("katmoviehd", ignoreCase = true)) return null
        val bad = listOf("/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-")
        if (bad.any { href.contains(it) }) return null
        if (href.trimEnd('/') == mainUrl.trimEnd('/')) return null

        val titleRaw = attr("title").ifBlank { text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(titleRaw),
            fixUrl(href),
            guessTvType(titleRaw)
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text() ?: doc.title()
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".entry-content img, article img")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val year = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = guessTvType(rawTitle) == TvType.TvSeries
        val seasonNumber = Regex("""(?i)Season\s*(\d+)|\bS(\d{1,2})""")
            .find(rawTitle)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
            ?.toIntOrNull() ?: 1

        if (isSeries) {
            val episodes = parseEpisodes(doc, seasonNumber)
            Log.d(TAG, "load(): series '$title' parsed ${episodes.size} episode(s)")

            if (episodes.isEmpty()) {
                Log.w(TAG, "Series had 0 episodes — falling back to movie-style")
                val allLinks = parseMovieLinks(doc)
                Log.d(TAG, "Fallback movieList has ${allLinks.split('\n').size} links")
                return newMovieLoadResponse(title, url, TvType.Movie, allLinks) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    imdbUrl?.let { addImdbUrl(it) }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                imdbUrl?.let { addImdbUrl(it) }
            }
        } else {
            val movieDataString = parseMovieLinks(doc)
            Log.d(TAG, "load(): movie '$title' has ${movieDataString.split('\n').size} link(s)")

            return newMovieLoadResponse(title, url, TvType.Movie, movieDataString) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                imdbUrl?.let { addImdbUrl(it) }
            }
        }
    }

    private fun parseMovieLinks(doc: Document): String {
        val content = doc.selectFirst("article, .entry-content") ?: doc
        return content.select("a[href]")
            .map { it.attr("href") }
            .filter { it.contains(linkHostRegex) }
            .distinct()
            .joinToString("\n")
    }

    private fun parseEpisodes(doc: Document, seasonNumber: Int): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()
        val epHeaderRegex = Regex("""(?i)\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")
        val map = linkedMapOf<Int, MutableList<String>>()
        var currentEp: Int? = null

        // Phase 1 — Layout A walker (Episode N → links)
        for (node in container.allElements) {
            val nodeText = node.text()
            if (nodeText.length < 40 &&
                node.tagName() in headerTags &&
                !nodeText.contains("more episodes", ignoreCase = true) &&
                !nodeText.contains("will be added", ignoreCase = true)
            ) {
                val match = epHeaderRegex.find(nodeText)
                if (match != null) {
                    val newEp = match.groupValues[1].toIntOrNull()
                    if (newEp != null && newEp != currentEp) {
                        currentEp = newEp
                    }
                }
            }

            val ep = currentEp
            if (ep != null && node.tagName() == "a") {
                val href = node.attr("href")
                if (href.contains(linkHostRegex)) {
                    val bucket = map.getOrPut(ep) { mutableListOf() }
                    if (href !in bucket) bucket.add(href)
                }
            }
        }

        if (map.isNotEmpty()) {
            Log.d(TAG, "Layout A: detected ${map.size} episodes")
            return map.entries.map { (ep, links) ->
                newEpisode(links.joinToString("\n")) {
                    this.name = "Episode $ep"
                    this.season = seasonNumber
                    this.episode = ep
                }
            }
        }

        // Phase 2 — Layout B: full-season packs.
        Log.d(TAG, "Layout A found nothing, trying Layout B (packs)")
        val packEpisodes = mutableListOf<Episode>()
        var packIdx = 1
        container.select("h1, h2, h3, h4").forEach { header ->
            val htext = header.text().trim()
            val links = mutableListOf<String>()
            header.select("a[href]").forEach {
                val h = it.attr("href")
                if (h.contains(linkHostRegex)) links.add(h)
            }
            var sibling = header.nextElementSibling()
            var hops = 0
            while (sibling != null && hops < 3 &&
                   sibling.tagName() !in listOf("h1", "h2", "h3", "h4")) {
                sibling.select("a[href]").forEach {
                    val h = it.attr("href")
                    if (h.contains(linkHostRegex) && h !in links) links.add(h)
                }
                sibling = sibling.nextElementSibling()
                hops++
            }

            if (links.isNotEmpty() && htext.isNotBlank()) {
                packEpisodes.add(
                    newEpisode(links.joinToString("\n")) {
                        this.name = htext.take(60)
                        this.season = seasonNumber
                        this.episode = packIdx
                    }
                )
                packIdx++
            }
        }
        if (packEpisodes.isNotEmpty()) {
            Log.d(TAG, "Layout B: detected ${packEpisodes.size} pack(s)")
            return packEpisodes
        }

        // Phase 3 — last resort.
        Log.w(TAG, "Both layouts failed, dumping all kmhd links as episodes")
        val allLinks = container.select("a[href]")
            .map { it.attr("href") }
            .filter { it.contains(linkHostRegex) }
            .distinct()
        return allLinks.mapIndexed { idx, link ->
            newEpisode(link) {
                this.name = "Source ${idx + 1}"
                this.season = seasonNumber
                this.episode = idx + 1
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks called. data length=${data.length}")

        val linksList = data
            .split("\n", ",")
            .map { it.trim().trim('"', '[', ']') }
            .filter { it.startsWith("http") }
            .distinct()

        Log.d(TAG, "loadLinks parsed ${linksList.size} URLs")

        if (linksList.isEmpty()) {
            Log.w(TAG, "loadLinks: NO URLs extracted from data!")
            return false
        }

        linksList.amap { rawUrl ->
            val u = rawUrl.trim()
            try {
                if (u.contains("kmhd.eu", ignoreCase = true)) {
                    KmhdExtractor().getUrl(u, mainUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(u, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extractor crashed for $u: ${e.message}")
            }
        }
        return true
    }

    private fun fixUrl(url: String): String =
        if (url.startsWith("http")) url else mainUrl + (if (url.startsWith("/")) url else "/$url")

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\s*\|.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?]"""), "")
            .replace(Regex("""(?i)\s*Hindi Dubbed.*$"""), "")
            .replace(Regex("""(?i)\s*Dual Audio.*$"""), "")
            .replace(Regex("""(?i)\s*WEB[-\s]?DL.*$"""), "")
            .replace(Regex("""(?i)\s*BluRay.*$"""), "")
            .replace(Regex("""(?i)\s*Full Movie.*$"""), "")
            .replace(Regex("""(?i)\s*Download """), "")
            .replace(Regex("""(?i)\s*-\s*KatMovieHD.*$"""), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val t = title.lowercase()
        return if (
            t.contains("season") || t.contains("episode") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(t) ||
            t.contains("series") || t.contains("(s0") || t.contains("(s1")
        ) TvType.TvSeries else TvType.Movie
    }

    private val headerTags = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "strong", "b", "div", "span", "em"
    )

    private val linkHostRegex = Regex(
        """(?i)(kmhd\.eu|kmhd\.net|gdflix|gd\.kmhd|hubcloud|gdlink|drive\.google|""" +
                """streamtape|filemoon|doodstream|mixdrop|streamlare|hubdrive|katdrive|""" +
                """1fichier|send\.cm|hglink|fuckingfast)"""
    )

    companion object {
        private const val TAG = "KatMovieHD"
    }
}
