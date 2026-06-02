package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
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
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    companion object {
        private const val TAG = "KatMovieHD"
        const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/original"
    }

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
        val sitePoster = doc.selectFirst(".entry-content img, article img")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val sitePlot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cleanedTitle = cleanTitle(rawTitle)
        val isSeries = guessTvType(rawTitle) == TvType.TvSeries
        val seasonNumber = Regex("""(?i)Season\s*(\d+)|\bS(\d{1,2})""")
            .find(rawTitle)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
            ?.toIntOrNull() ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        val tmdbId: Int? = resolveTmdbId(imdbId, cleanedTitle, isSeries)
        val tmdb = tmdbId?.let { fetchTmdbDetails(it, isSeries) }

        val title    = tmdb?.title       ?: cleanedTitle
        val poster   = tmdb?.poster      ?: sitePoster
        val backdrop = tmdb?.backdrop    ?: sitePoster
        val plot     = tmdb?.overview    ?: sitePlot
        val year     = tmdb?.year        ?: Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val rating   = tmdb?.rating
        val tags     = tmdb?.genres ?: emptyList()
        val actors   = tmdb?.actors ?: emptyList()
        val trailer  = tmdb?.trailer

        Log.d(TAG, "load(): '$title' isSeries=$isSeries tmdbId=$tmdbId")

        if (isSeries) {
            val tmdbSeason = tmdbId?.let { fetchTmdbSeason(it, seasonNumber) }
            val episodes = parseEpisodes(doc, seasonNumber, tmdbSeason)
            Log.d(TAG, "load(): series parsed ${episodes.size} episode(s)")

            if (episodes.isEmpty()) {
                Log.w(TAG, "Series had 0 episodes — falling back to movie-style")
                val allLinks = parseMovieLinks(doc)
                return newMovieLoadResponse(title, url, TvType.Movie, allLinks) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.actors = actors
                    this.score = rating
                    addTrailer(trailer)
                    imdbUrl?.let { addImdbUrl(it) }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = rating
                addTrailer(trailer)
                imdbUrl?.let { addImdbUrl(it) }
            }
        } else {
            val movieData = parseMovieLinks(doc)
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = rating
                addTrailer(trailer)
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

    private fun parseEpisodes(
        doc: Document,
        seasonNumber: Int,
        tmdbSeason: TmdbSeason?
    ): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()
        val epHeaderRegex = Regex("""(?i)\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")
        val map = linkedMapOf<Int, MutableList<String>>()
        var currentEp: Int? = null

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
                buildEpisode(ep, seasonNumber, links, tmdbSeason)
            }
        }

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

    private fun buildEpisode(
        epNum: Int,
        seasonNum: Int,
        links: List<String>,
        tmdbSeason: TmdbSeason?
    ): Episode {
        val tmdbEp = tmdbSeason?.episodes?.firstOrNull { it.episodeNumber == epNum }
        return newEpisode(links.joinToString("\n")) {
            this.name = tmdbEp?.name ?: "Episode $epNum"
            this.season = seasonNum
            this.episode = epNum
            this.posterUrl = tmdbEp?.stillUrl
            this.description = tmdbEp?.overview
            this.score = tmdbEp?.rating
            tmdbEp?.airDate?.let { addDate(it) }
        }
    }

    private data class TmdbDetails(
        val title: String?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val year: Int?,
        val rating: Score?,
        val genres: List<String>,
        val actors: List<ActorData>,
        val trailer: String?
    )

    private data class TmdbSeason(
        val seasonNumber: Int,
        val episodes: List<TmdbEpisode>
    )

    private data class TmdbEpisode(
        val episodeNumber: Int,
        val name: String?,
        val overview: String?,
        val stillUrl: String?,
        val airDate: String?,
        val rating: Score?
    )

    private suspend fun resolveTmdbId(
        imdbId: String?,
        cleanedTitle: String,
        isSeries: Boolean
    ): Int? {
        if (!imdbId.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(
                    app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id").text
                )
                val arrKey = if (isSeries) "tv_results" else "movie_results"
                val id = json.optJSONArray(arrKey)?.optJSONObject(0)?.optInt("id", 0)
                if (id != null && id > 0) return id
            }.onFailure { Log.w(TAG, "TMDB find-by-imdb failed: ${it.message}") }
        }

        return runCatching {
            val q = URLEncoder.encode(cleanedTitle.substringBefore("(").trim(), "UTF-8")
            val url = "$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$q"
            val json = JSONObject(app.get(url).text)
            val results = json.optJSONArray("results") ?: return@runCatching null
            val targetType = if (isSeries) "tv" else "movie"
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") == targetType) {
                    val id = item.optInt("id", 0)
                    if (id > 0) return@runCatching id
                }
            }
            null
        }.getOrNull()
    }

    private suspend fun fetchTmdbDetails(tmdbId: Int, isSeries: Boolean): TmdbDetails? {
        val type = if (isSeries) "tv" else "movie"
        return runCatching {
            val json = JSONObject(
                app.get(
                    "$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,videos"
                ).text
            )

            val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { null }
            val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = date.takeIf { it.isNotBlank() }?.substringBefore("-")?.toIntOrNull()
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { TMDB_IMAGE_BASE + it }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { TMDB_IMAGE_BASE + it }
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val voteAvg = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
            val rating = voteAvg?.let { Score.from10(it) }

            val genres = json.optJSONArray("genres")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            } ?: emptyList()

            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                buildList {
                    val limit = minOf(arr.length(), 20)
                    for (i in 0 until limit) {
                        val c = arr.optJSONObject(i) ?: continue
                        val n = c.optString("name").ifBlank { c.optString("original_name") }
                        if (n.isBlank()) continue
                        val pf = c.optString("profile_path").takeIf { it.isNotBlank() }
                            ?.let { TMDB_IMAGE_BASE + it }
                        add(ActorData(Actor(n, pf), roleString = c.optString("character").takeIf { it.isNotBlank() }))
                    }
                }
            } ?: emptyList()

            val trailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                var found: String? = null
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    if (v.optString("site") == "YouTube" &&
                        v.optString("type").contains("Trailer", true)) {
                        found = "https://www.youtube.com/watch?v=${v.optString("key")}"
                        break
                    }
                }
                found
            }

            TmdbDetails(title, poster, backdrop, overview, year, rating, genres, actors, trailer)
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tmdbId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = JSONObject(
                app.get("$TMDB_API/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY").text
            )
            val arr = json.optJSONArray("episodes") ?: return@runCatching null
            val episodes = buildList {
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val epNum = ep.optInt("episode_number", -1).takeIf { it > 0 } ?: continue
                    add(
                        TmdbEpisode(
                            episodeNumber = epNum,
                            name = ep.optString("name").takeIf { it.isNotBlank() },
                            overview = ep.optString("overview").takeIf { it.isNotBlank() },
                            stillUrl = ep.optString("still_path").takeIf { it.isNotBlank() }
                                ?.let { TMDB_IMAGE_BASE + it },
                            airDate = ep.optString("air_date").takeIf { it.isNotBlank() },
                            rating = ep.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
                                ?.let { Score.from10(it) }
                        )
                    )
                }
            }
            TmdbSeason(seasonNum, episodes)
        }.getOrNull()
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
            .replace(Regex("""(?i)\s*\((season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*season\s*\d+"""), "")
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
}
