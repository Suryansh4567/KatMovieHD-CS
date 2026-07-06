package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class PagalMoviesProvider : MainAPI() {
    override var mainUrl = PagalMoviesPlugin.DEFAULT_MAIN_URL
    override var name = "PagalMovies Alpha-Omega"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "a96013620f4c029df4f78326e7925c48"
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private suspend fun refreshMainUrl(forceRefresh: Boolean = false): String {
        val active = PagalMoviesPlugin.getActiveMainUrl(forceRefresh)
        if (active.isNotBlank()) {
            mainUrl = active
        }
        return mainUrl
    }

    private suspend fun safeGetDocument(url: String, referer: String? = null): Document {
        val reqHeaders = if (referer != null) headers + mapOf("Referer" to referer) else headers
        for (i in 0..1) {
            try {
                val direct = runCatching {
                    app.get(url, headers = reqHeaders, timeout = 30).document
                }.getOrNull()
                if (direct != null && !isCfBlock(direct)) return direct
                return app.get(url, headers = reqHeaders, interceptor = CloudflareKiller(), timeout = 30).document
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return app.get(url, headers = reqHeaders, interceptor = CloudflareKiller(), timeout = 30).document
    }

    private fun isCfBlock(doc: Document): Boolean {
        val title = doc.selectFirst("title")?.text()?.lowercase().orEmpty()
        return title.contains("just a moment") || title.contains("attention required") || title.contains("verify you are human")
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http", ignoreCase = true)) return url
        val base = mainUrl.trimEnd('/')
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    override val mainPage = mainPageOf(
        "/movielist/12/Hollywood_hindi_dubbed_movies/default/" to "Hollywood Hindi Dubbed",
        "/movielist/4/Bollywood_hindi_full_movies/default/" to "Bollywood Movies",
        "/movielist/14/South_indian_hindi_dubbed_movies/default/" to "South Hindi Dubbed",
        "/movielist/21/Hindi_web_series/default/" to "Hindi Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = refreshMainUrl()
        val path = request.data.trimStart('/')
        val url = if (page <= 1) "$base/$path" else "$base/$path$page.html"
        val doc = safeGetDocument(url)
        val items = parseListing(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val base = refreshMainUrl()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/search.php?search=$encoded" else "$base/search.php?search=$encoded&page=$page"
        val doc = safeGetDocument(url)
        val items = parseListing(doc)
        return items.toNewSearchResponseList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = refreshMainUrl()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = safeGetDocument("$base/search.php?search=$encoded")
        return parseListing(doc)
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val anchors = doc.select("a:has(img), div.thumb a, div.catRow a, table tr td a:has(img)")
        return anchors.mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href.contains("javascript") || href == "#") return@mapNotNull null
            val fullUrl = fixUrl(href)
            val img = a.selectFirst("img")
            val rawTitle = a.attr("title").ifBlank { img?.attr("alt") ?: a.text() }.trim()
            if (rawTitle.isBlank()) return@mapNotNull null

            val cleanTitle = cleanItemTitle(rawTitle)
            val poster = fixUrl(img?.attr("src") ?: img?.attr("data-src") ?: "")
            val isTv = fullUrl.contains("web_series", ignoreCase = true) || fullUrl.contains("season", ignoreCase = true) || rawTitle.contains("season", ignoreCase = true)
            val type = if (isTv) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(cleanTitle, fullUrl, type) {
                if (poster.isNotBlank()) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    private fun cleanItemTitle(title: String): String {
        return title.replace(Regex("""(?i)\(.*?\)"""), "")
            .replace(Regex("""(?i)(Full Movie|Hindi Dubbed|Hollywood|Bollywood|South Indian|480p|720p|1080p|2160p|HD|DVDRip|HDRip|Web-DL|BluRay)"""), "")
            .trim()
            .trim('-', '–', '_')
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = safeGetDocument(url)
        val rawTitle = doc.selectFirst("h1, h2, .page-title")?.text() ?: doc.selectFirst("title")?.text() ?: "Untitled"
        val cleanTitle = cleanItemTitle(rawTitle)

        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)
        val pageYear = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val tmdbMeta = getTmdbMetadata(cleanTitle, pageYear)

        val poster = tmdbMeta?.posterPath ?: fixUrl(doc.selectFirst("img[src*=files/images/], div.thumb img")?.attr("src") ?: "")
        val backdrop = tmdbMeta?.backdropPath
        val plot = tmdbMeta?.overview ?: doc.select("b:contains(Description) + i, div.desc, p.description").text().trim()
        val year = tmdbMeta?.year ?: pageYear
        val rating = tmdbMeta?.rating
        val tags = tmdbMeta?.genres ?: emptyList()
        val actorsList = tmdbMeta?.actors ?: emptyList()
        val trailer = tmdbMeta?.trailer

        val fileLinks = doc.select("a[href*=/file/], a[href*=/download/], a[href*=/movie/], a.dbtn").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isNotBlank() && !href.equals(url, ignoreCase = true) && !href.contains("search.php", ignoreCase = true)) {
                val title = a.text().trim().ifBlank { "Part / Episode" }
                fixUrl(href) to title
            } else null
        }.distinctBy { it.first }

        val isSeries = doc.location().contains("web_series", ignoreCase = true) ||
            rawTitle.contains("season", ignoreCase = true) ||
            rawTitle.contains("series", ignoreCase = true) ||
            (fileLinks.size > 2 && fileLinks.any { it.second.contains("episode", ignoreCase = true) })

        if (isSeries) {
            val episodes = fileLinks.mapIndexed { idx, (linkUrl, linkTitle) ->
                val epNum = Regex("""(?i)episode\s*[-_]?\s*(\d+)""").find(linkTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val sNum = Regex("""(?i)season\s*[-_]?\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(linkUrl) {
                    this.name = cleanItemTitle(linkTitle).ifBlank { "Episode $epNum" }
                    this.season = sNum
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                if (poster.isNotBlank()) this.posterUrl = poster
                if (!backdrop.isNullOrBlank()) this.backgroundPosterUrl = backdrop
                if (plot.isNotBlank()) this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating
                if (actorsList.isNotEmpty()) this.actors = actorsList
                addTrailer(trailer)
            }
        } else {
            val dataPayload = if (fileLinks.isNotEmpty()) fileLinks.map { it.first }.joinToString("###") else url
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, dataPayload) {
                if (poster.isNotBlank()) this.posterUrl = poster
                if (!backdrop.isNullOrBlank()) this.backgroundPosterUrl = backdrop
                if (plot.isNotBlank()) this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating
                if (actorsList.isNotEmpty()) this.actors = actorsList
                addTrailer(trailer)
            }
        }
    }

    data class TmdbMetadata(
        val tmdbId: Int,
        val posterPath: String?,
        val backdropPath: String?,
        val overview: String?,
        val year: Int?,
        val rating: Score?,
        val genres: List<String>,
        val actors: List<ActorData>,
        val trailer: String?
    )

    private suspend fun getTmdbMetadata(query: String, targetYear: Int?): TmdbMetadata? {
        if (query.isBlank()) return null
        return try {
            val searchUrl = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}"
            val resText = app.get(searchUrl, timeout = 10).text
            val results = JSONObject(resText).optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            var bestObj = results.optJSONObject(0)
            if (targetYear != null) {
                for (i in 0 until minOf(results.length(), 5)) {
                    val item = results.optJSONObject(i) ?: continue
                    val date = item.optString("release_date").ifBlank { item.optString("first_air_date") }
                    if (date.startsWith(targetYear.toString())) {
                        bestObj = item
                        break
                    }
                }
            }
            if (bestObj == null) return null

            val tmdbId = bestObj.optInt("id", -1)
            if (tmdbId <= 0) return null

            val mediaType = bestObj.optString("media_type", "movie").takeIf { it in listOf("movie", "tv") } ?: "movie"
            val detailUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos"
            val detail = JSONObject(app.get(detailUrl, timeout = 10).text)

            val poster = detail.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = detail.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val overview = detail.optString("overview").takeIf { it.isNotBlank() }
            val dateStr = detail.optString("release_date").ifBlank { detail.optString("first_air_date") }
            val year = dateStr.take(4).toIntOrNull()
            val voteAvg = detail.optDouble("vote_average", -1.0)
            val rating = if (voteAvg > 0) Score.from10(voteAvg) else null

            val genres = mutableListOf<String>()
            detail.optJSONArray("genres")?.let { garr ->
                for (i in 0 until garr.length()) {
                    garr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
                }
            }

            val actorsList = mutableListOf<ActorData>()
            detail.optJSONObject("credits")?.optJSONArray("cast")?.let { carr ->
                for (i in 0 until minOf(carr.length(), 15)) {
                    val c = carr.optJSONObject(i) ?: continue
                    val name = c.optString("name").ifBlank { c.optString("original_name") }
                    if (name.isBlank()) continue
                    val img = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w200$it" }
                    val role = c.optString("character").takeIf { it.isNotBlank() }
                    actorsList.add(ActorData(Actor(name, img), roleString = role))
                }
            }

            var trailer: String? = null
            detail.optJSONObject("videos")?.optJSONArray("results")?.let { varr ->
                for (i in 0 until varr.length()) {
                    val v = varr.optJSONObject(i) ?: continue
                    if (v.optString("site").equals("YouTube", ignoreCase = true) && v.optString("type").equals("Trailer", ignoreCase = true)) {
                        val key = v.optString("key")
                        if (key.isNotBlank()) {
                            trailer = "https://www.youtube.com/watch?v=$key"
                            break
                        }
                    }
                }
            }

            TmdbMetadata(tmdbId, poster, backdrop, overview, year, rating, genres, actorsList, trailer)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targets = data.split("###").map { it.trim() }.filter { it.isNotBlank() }
        for (target in targets) {
            try {
                extractLinksRecursive(target, target, 0, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("PagalMovies", "Extraction failed for $target: ${e.message}")
            }
        }
        return true
    }

    private suspend fun extractLinksRecursive(
        url: String,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (depth > 4 || url.isBlank()) return
        val fixedUrl = fixUrl(url)

        val lower = fixedUrl.substringBefore('?').lowercase()
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".m3u8")) {
            val quality = if (lower.contains("1080p")) Qualities.P1080.value else if (lower.contains("720p")) Qualities.P720.value else Qualities.P480.value
            callback.invoke(
                newExtractorLink(this.name, "${this.name} Direct", fixedUrl) {
                    this.quality = quality
                    this.referer = referer
                }
            )
            return
        }

        if (!fixedUrl.contains("pagalmovies", ignoreCase = true) && !fixedUrl.contains("search.php", ignoreCase = true)) {
            val dispatched = loadExtractor(fixedUrl, referer, subtitleCallback, callback)
            if (dispatched) return
        }

        val res = try {
            app.get(fixedUrl, headers = headers + mapOf("Referer" to referer), allowRedirects = true, timeout = 25)
        } catch (_: Exception) {
            app.get(fixedUrl, headers = headers + mapOf("Referer" to referer), interceptor = CloudflareKiller(), allowRedirects = true, timeout = 25)
        }

        val finalUrl = res.url
        val lowerFinal = finalUrl.substringBefore('?').lowercase()
        if (lowerFinal.endsWith(".mp4") || lowerFinal.endsWith(".mkv") || lowerFinal.endsWith(".webm") || lowerFinal.endsWith(".m3u8") || res.headers["content-type"]?.contains("video") == true) {
            val quality = if (lowerFinal.contains("1080p")) Qualities.P1080.value else if (lowerFinal.contains("720p")) Qualities.P720.value else Qualities.P480.value
            callback.invoke(
                newExtractorLink(this.name, "${this.name} High Speed", finalUrl) {
                    this.quality = quality
                    this.referer = fixedUrl
                }
            )
            return
        }

        val doc = res.document

        val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
        if (metaRefresh != null && metaRefresh.contains("url=", ignoreCase = true)) {
            val redirectUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim()
            if (redirectUrl.isNotBlank() && !redirectUrl.equals(fixedUrl, ignoreCase = true)) {
                extractLinksRecursive(fixUrl(redirectUrl), fixedUrl, depth + 1, subtitleCallback, callback)
                return
            }
        }

        val nextLinks = doc.select("a:contains(Click Here to Go to Download Page), a:contains(Go to Download Page), a.download-btn, a[href*=/server/], a[href*=/download/], a[href*=/dpage/], a:contains(Server 1), a:contains(Server 2), a:contains(Fast Server), a:contains(Direct Stream)")
        for (a in nextLinks) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("javascript", ignoreCase = true)) continue
            val nextUrl = fixUrl(href)
            if (!nextUrl.equals(fixedUrl, ignoreCase = true)) {
                extractLinksRecursive(nextUrl, fixedUrl, depth + 1, subtitleCallback, callback)
            }
        }
    }
}
