package com.lagradost

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import org.json.JSONObject

/**
 * FreeDriveMovie provider — freedrivemovie.cyou (Dooplay / WordPress).
 *
 * Metadata is enriched from TMDB (search by cleaned title + year), like the
 * professional references (FourKHDHub fetchtmdb, ViStream TmdbProvider). For TV
 * this also pulls per-episode name + still + overview from
 * /tv/{id}/season/{n}. Every TMDB call is best-effort; on any failure we fall
 * back to the page's own metadata so load() never breaks.
 *
 * Hoster chain: content page (.links_table -> /links/<code>/) -> intermediate
 * (<a id="link">) -> dl.freedrivemovie.org/<slug>/ (.wp-block-button a).
 *   - Cloudflare-Worker GDToT mirrors (.mkv) -> directly playable, labelled
 *     "FreeDriveMovie - <quality>" (no URL in the name).
 *   - file lockers (gdflix/hubcloud/gdtot) -> CloudStream's loadExtractor.
 *
 * "Complete season" entries are kept as their own episode.
 *
 * Design follows the in-repo pros (KatMovieHD/YupFlix): load() does a single
 * page fetch + parse (+ best-effort TMDB); all link resolution is in
 * loadLinks(), now parallel (amap) and fetch-capped (<=3 shortlinks).
 */
class FreeDriveMovie : MainAPI() {

    override var mainUrl = "https://freedrivemovie.cyou"
    override var name = "FreeDriveMovie"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val MAIN = "https://freedrivemovie.cyou"
        private const val DL_REFERER = "https://dl.freedrivemovie.org/"

        // TMDB (public CS3 community key, same one ViStream ships).
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "8ff0f5d3eb22a8130a33808a70688dce"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_BACKDROP = "https://image.tmdb.org/t/p/w780"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$MAIN/",
        )

        private val TITLE_YEAR = Regex("""^(.*?)\((\d{4})\)""", RegexOption.IGNORE_CASE)
        private val TITLE_CUT = Regex(
            """\s+(?:HDTC|HDCAM|HDTS|HDTS-PRINT|TS|TC|TCRIP|CAM|HINDI-CAM|WEB-DL|WEB-RIP|""" +
                """WEBRip|WEB-DLRip|BluRay|Blu-Ray|BRRip|HDRip|DVDScr|DVDRip|WEBDL|WEB|""" +
                """HEVC|x264|x265|AVC|AAC|DDP|DDPA|ESub|HC|LiNE|2160p|1080p|720p|480p|360p|4K)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val EPISODE_NUM = Regex("""(?:Episode|Ep)\s*[.-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pure helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun cleanTitle(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        TITLE_YEAR.find(s)?.let {
            val n = it.groupValues[1].trim().trimEnd(',', '-', ':')
            return if (n.isNotEmpty()) "$n (${it.groupValues[2]})" else "(${it.groupValues[2]})"
        }
        val m = TITLE_CUT.find(s)
        return if (m != null) s.substring(0, m.range.first).trim().trimEnd(',', '-', ':') else s
    }

    private fun parseYear(raw: String): Int? =
        Regex("""\((\d{4})\)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

    private fun upScalePoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url
            .replace("/w92/", "/w500/").replace("/w154/", "/w500/")
            .replace("/w185/", "/w500/").replace("/w342/", "/w500/")
            .ifBlank { null }
    }

    private fun tvTypeFor(url: String): TvType =
        if (url.contains("/tvshows/") || url.contains("/episodes/")) TvType.TvSeries else TvType.Movie

    private fun qualityFromLabel(label: String): Int {
        val l = label.lowercase()
        return when {
            "2160" in l || "4k" in l || "uhd" in l -> Qualities.P2160.value
            "1440" in l || "2k" in l -> Qualities.P1440.value
            "1080" in l -> Qualities.P1080.value
            "720" in l -> Qualities.P720.value
            "480" in l -> Qualities.P480.value
            "360" in l -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isDirectPlayable(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".mkv") || u.endsWith(".mp4") || u.endsWith(".webm") ||
            u.endsWith(".m4v") || u.endsWith(".mov") || u.endsWith(".m3u8") ||
            "workers.dev" in u || "/0:/" in u
    }

    private fun cleanLabel(raw: String): String =
        EPISODE_NUM.replace(raw, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('-', ':')
            .trim()
            .ifBlank { "Mirror" }

    // ──────────────────────────────────────────────────────────────────────
    // TMDB enrichment (best-effort; never throws)
    // ──────────────────────────────────────────────────────────────────────

    private data class TmdbMeta(
        val title: String?, val poster: String?, val backdrop: String?,
        val plot: String?, val rating: String?, val year: Int?, val tags: List<String>,
        val actors: List<ActorData>,
    )

    private data class TmdbEp(val name: String?, val still: String?, val overview: String?)

    private suspend fun tmdbId(title: String, year: Int?, isMovie: Boolean): Int? = try {
        val type = if (isMovie) "movie" else "tv"
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val y = year?.let { if (isMovie) "&year=$it" else "&first_air_date_year=$it" } ?: ""
        val res = JSONObject(app.get("$TMDB_API/search/$type?api_key=$TMDB_KEY&query=$q$y", timeout = 10).text)
            .optJSONArray("results")
        res?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    private fun genresOf(d: JSONObject): List<String> {
        val arr = d.optJSONArray("genres") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private suspend fun fetchCredits(type: String, id: Int): List<ActorData> = try {
        val arr = JSONObject(app.get("$TMDB_API/$type/$id/credits?api_key=$TMDB_KEY", timeout = 10).text)
            .optJSONArray("cast")
        if (arr == null) {
            emptyList()
        } else {
            val out = ArrayList<ActorData>()
            for (i in 0 until minOf(arr.length(), 15)) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                val profile = c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "https://image.tmdb.org/t/p/w185$it" }
                out.add(ActorData(Actor(name, profile), roleString = c.optString("character").takeIf { it.isNotBlank() }))
            }
            out
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun fetchMovieMeta(id: Int): TmdbMeta? = try {
        val d = JSONObject(app.get("$TMDB_API/movie/$id?api_key=$TMDB_KEY", timeout = 10).text)
        TmdbMeta(
            d.optString("title").ifBlank { null },
            d.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG$it" },
            d.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_BACKDROP$it" },
            d.optString("overview").ifBlank { null },
            d.optString("vote_average").takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
            d.optString("release_date").take(4).toIntOrNull(),
            genresOf(d),
            fetchCredits("movie", id),
        )
    } catch (_: Throwable) {
        null
    }

    private suspend fun fetchTvMeta(id: Int): TmdbMeta? = try {
        val d = JSONObject(app.get("$TMDB_API/tv/$id?api_key=$TMDB_KEY", timeout = 10).text)
        TmdbMeta(
            d.optString("name").ifBlank { null },
            d.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG$it" },
            d.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_BACKDROP$it" },
            d.optString("overview").ifBlank { null },
            d.optString("vote_average").takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
            d.optString("first_air_date").take(4).toIntOrNull(),
            genresOf(d),
            fetchCredits("tv", id),
        )
    } catch (_: Throwable) {
        null
    }

    /** season -> (episodeNumber -> episode meta). */
    private suspend fun fetchSeasonEpisodes(id: Int, season: Int): Map<Int, TmdbEp>? = try {
        val arr = JSONObject(app.get("$TMDB_API/tv/$id/season/$season?api_key=$TMDB_KEY", timeout = 10).text)
            .optJSONArray("episodes")
            ?: return null
        val map = HashMap<Int, TmdbEp>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val num = e.optInt("episode_number")
            if (num > 0) {
                map[num] = TmdbEp(
                    e.optString("name").ifBlank { null },
                    e.optString("still_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG$it" },
                    e.optString("overview").ifBlank { null },
                )
            }
        }
        map
    } catch (_: Throwable) {
        null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Home page
    // ──────────────────────────────────────────────────────────────────────

    /** YouTube trailer via the Dooplay player API (data-nume='trailer'). */
    private suspend fun fetchTrailer(doc: Document): String? = try {
        val li = doc.selectFirst("[data-nume='trailer']") ?: return null
        val post = li.attr("data-post").takeIf { it.isNotBlank() } ?: return null
        val type = li.attr("data-type").takeIf { it.isNotBlank() } ?: "movie"
        val json = JSONObject(app.get("$MAIN/wp-json/dooplayer/v2/$post/$type/trailer", headers = HEADERS, timeout = 10).text)
        json.optString("embed_url").takeIf { it.startsWith("http") }
    } catch (_: Throwable) {
        null
    }

    /** Related posts from the #single_relacionados block. */
    private fun parseRelated(doc: Document): List<SearchResponse> {
        val rel = doc.selectFirst("#single_relacionados, .srelacionados") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (art in rel.select("article.item, .item, .poster")) {
            val href = art.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                ?.takeIf { it.startsWith("http") } ?: continue
            val name = cleanTitle(
                art.selectFirst(".title")?.text()
                    ?: art.selectFirst("h3")?.text()
                    ?: art.selectFirst("img")?.attr("alt") ?: "",
            )
            if (name.isBlank()) continue
            out.add(
                newMovieSearchResponse(name, href, tvTypeFor(href)) {
                    this.posterUrl = upScalePoster(art.selectFirst("img")?.absUrl("src"))
                },
            )
        }
        return out
    }

    override val mainPage = mainPageOf(
        "$MAIN/" to "Home",
        "$MAIN/tvshows/" to "TV Series",
        "$MAIN/genre/bollywood-genre/" to "Bollywood",
        "$MAIN/genre/south-indian/" to "South Indian",
        "$MAIN/genre/action/" to "Action",
        "$MAIN/genre/drama/" to "Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val base = request.data
            // Homepage doesn't paginate (/page/2/ -> 404); every other section
            // paginates via /page/N/ (verified: genre + tvshows archives -> 200,
            // /page/1/ -> 301 to base, so page 1 uses the base URL).
            val url = when {
                base == "$MAIN/" -> base
                page <= 1 -> base
                else -> base.trimEnd('/') + "/page/$page/"
            }
            val doc = app.get(url, headers = HEADERS, timeout = 15).document
            val cards = parseCards(doc, request.name)
            val hasNext = base != "$MAIN/" && cards.isNotEmpty()
            newHomePageResponse(cards, hasNext = hasNext)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)
        }
    }

    private fun parseCards(doc: Document, section: String): List<HomePageList> {
        val out = mutableListOf<SearchResponse>()
        for (art in doc.select("article.item")) {
            val href = art.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                ?.takeIf { it.startsWith("http") } ?: continue
            val name = cleanTitle(
                art.selectFirst(".title")?.text()
                    ?: art.selectFirst("h3")?.text()
                    ?: art.selectFirst("img")?.attr("alt")
                    ?: "",
            )
            if (name.isBlank()) continue
            val poster = upScalePoster(art.selectFirst("img")?.absUrl("src"))
            out.add(newMovieSearchResponse(name, href, tvTypeFor(href)) { this.posterUrl = poster })
        }
        return if (out.isEmpty()) emptyList() else listOf(HomePageList(section, out))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page <= 1) "$MAIN/?s=$q" else "$MAIN/page/$page/?s=$q"
            val doc = app.get(url, headers = HEADERS, timeout = 15).document
            val results = doc.select(".result-item").mapNotNull { item ->
                val href = item.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val name = cleanTitle(item.selectFirst(".title a")?.text() ?: item.selectFirst(".title")?.text() ?: "")
                if (name.isBlank()) return@mapNotNull null
                newMovieSearchResponse(name, href, tvTypeFor(href)) {
                    this.posterUrl = upScalePoster(item.selectFirst("img")?.absUrl("src"))
                }
            }
            newSearchResponseList(results)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Load (single page fetch + parse + best-effort TMDB enrichment)
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = HEADERS, timeout = 15).document
            val rawTitle = doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            val pageTitle = cleanTitle(rawTitle).ifBlank { url.trimEnd('/').substringAfterLast('/') }
            val year = parseYear(rawTitle)
            val pagePoster = upScalePoster(doc.selectFirst(".poster img")?.absUrl("src"))
            val pagePlot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            val pageTags = doc.select("a[href*=/genre/]").eachTextClean().take(8)
            fun score(rating: String?) =
                if (rating.isNullOrBlank()) null else Score.from((rating.toFloatOrNull() ?: 0f).times(1000).toInt(), 10000)

            val response = if (url.contains("/tvshows/")) {
                val rawEpisodes = parseEpisodes(doc)
                val showId = tmdbId(pageTitle, year, false)
                val show = showId?.let { fetchTvMeta(it) }
                val seasonCache = HashMap<Int, Map<Int, TmdbEp>>()
                if (showId != null) {
                    for (s in rawEpisodes.mapNotNull { it.season }.toSet()) {
                        fetchSeasonEpisodes(showId, s)?.let { seasonCache[s] = it }
                    }
                }
                val episodes = mutableListOf<Episode>()
                for (ep in rawEpisodes) {
                    val data = ep.data
                    val isBatch = ep.name?.contains("Complete", ignoreCase = true) == true
                    val tmdbEp = if (!isBatch) ep.season?.let { seasonCache[it] }?.get(ep.episode ?: 0) else null
                    episodes.add(
                        newEpisode(data) {
                            this.name = tmdbEp?.name ?: ep.name
                            this.season = ep.season
                            this.episode = ep.episode
                            this.posterUrl = tmdbEp?.still
                            this.description = tmdbEp?.overview
                        },
                    )
                }
                newTvSeriesLoadResponse(show?.title ?: pageTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = show?.poster ?: pagePoster
                    this.backgroundPosterUrl = show?.backdrop
                    this.plot = show?.plot ?: pagePlot
                    this.year = show?.year ?: year
                    this.tags = show?.tags?.ifEmpty { null } ?: pageTags
                    score(show?.rating)?.let { this.score = it }
                    show?.actors?.let { this.actors = it }
                }
            } else {
                val movie = tmdbId(pageTitle, year, true)?.let { fetchMovieMeta(it) }
                newMovieLoadResponse(movie?.title ?: pageTitle, url, TvType.Movie, url) {
                    this.posterUrl = movie?.poster ?: pagePoster
                    this.backgroundPosterUrl = movie?.backdrop
                    this.plot = movie?.plot ?: pagePlot
                    this.year = movie?.year ?: year
                    this.tags = movie?.tags?.ifEmpty { null } ?: pageTags
                    score(movie?.rating)?.let { this.score = it }
                    movie?.actors?.let { this.actors = it }
                }
            }
            fetchTrailer(doc)?.let { response.addTrailer(it) }
            parseRelated(doc).takeIf { it.isNotEmpty() }?.let { response.recommendations = it }
            response
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    /** Parse `#seasons > .se-c > .episodios li` into Episodes (batches included). */
    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (seasonEl in doc.select("#seasons .se-c")) {
            val seasonNum = seasonEl.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull() ?: continue
            for (li in seasonEl.select(".episodios li")) {
                val epHref = li.selectFirst("a[href*=/episodes/]")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") } ?: continue
                val numerando = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
                val parts = numerando.split("-").map { it.trim() }
                val isBatch = parts.getOrNull(1)?.equals("All", ignoreCase = true) == true
                val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val baseName = li.selectFirst(".episodiotitle a")?.text()?.trim().orEmpty()
                val epName = if (isBatch) baseName.ifBlank { "Season $seasonNum Complete" } else baseName
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = if (isBatch) 1 else epNum
                    },
                )
            }
        }
        return episodes
    }

    // ──────────────────────────────────────────────────────────────────────
    // Links
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val anchors = parseShortLinks(data).amap { resolveAnchors(it) }.flatten()
            emitLinksFromAnchors(anchors, subtitleCallback, callback)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun resolveAnchors(shortlink: String): List<Pair<String, String>> {
        return try {
            val sdoc = app.get(shortlink, headers = HEADERS, timeout = 15).document
            val target = sdoc.selectFirst("a#link")?.absUrl("href")?.takeIf { it.startsWith("http") }
                ?: return emptyList()
            if (!target.contains("freedrivemovie")) return listOf(target to "Mirror")
            val ddoc = app.get(target, headers = HEADERS, timeout = 15).document
            ddoc.select(".wp-block-button a").mapNotNull { a ->
                val href = a.absUrl("href").trim()
                if (href.startsWith("http")) href to (a.text().trim().ifBlank { "Mirror" }) else null
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Emit sources. Direct mirrors get clean labels ("FreeDriveMovie - 720p",
     * no URL) and are listed first; file lockers go through CloudStream's
     * extractors as a fallback so episodes without a direct mirror still play.
     */
    private suspend fun emitLinksFromAnchors(
        anchors: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val seen = linkedSetOf<String>()
        // 1. Direct mirrors — clean labels, no URL.
        for ((href, rawLabel) in anchors) {
            if (!seen.add(href)) continue
            if (!isDirectPlayable(href)) continue
            callback.invoke(
                newExtractorLink("FreeDriveMovie", "FreeDriveMovie - ${cleanLabel(rawLabel)}", href, ExtractorLinkType.VIDEO) {
                    this.quality = qualityFromLabel(rawLabel)
                    this.referer = DL_REFERER
                },
            )
            found = true
        }
        // 2. File lockers via CS3 extractors (fallback; their names is set by
        //    the extractor and may include a URL — only used when no direct
        //    mirror is available so playback still works).
        if (!found) {
            for ((href, _) in anchors) {
                if (!seen.add(href + "#locker")) continue
                if (isDirectPlayable(href)) continue
                try {
                    loadExtractor(href, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Throwable) { /* best-effort */ }
            }
        }
        return found
    }

    /** Collect every `/links/<code>/` shortlink (capped at 3). */
    private suspend fun parseShortLinks(pageUrl: String): List<String> {
        val doc = app.get(pageUrl, headers = HEADERS, timeout = 15).document
        val out = linkedSetOf<String>()
        for (tr in doc.select(".links_table tr")) {
            val href = tr.selectFirst("a[href]")?.absUrl("href") ?: continue
            if ("/links/" in href) out.add(href)
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href*='/links/']")) {
                a.absUrl("href").takeIf { "/links/" in it }?.let(out::add)
            }
        }
        return out.take(3)
    }
}

/** like Element.eachText() but strips nested <font>/<i> noise and de-dupes. */
private fun org.jsoup.select.Elements.eachTextClean(): List<String> =
    this.mapNotNull { it.text().trim().takeIf { s -> s.isNotEmpty() } }.distinct()
