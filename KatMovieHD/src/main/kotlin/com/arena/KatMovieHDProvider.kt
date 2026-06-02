package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * KatMovieHD provider.
 *
 * The site is a WordPress install whose article body is loosely structured:
 * different shows use materially different layouts. This provider treats
 * episode discovery as a small pipeline:
 *
 *   1. Per-episode header layout: paragraphs like "Episode 3 –" followed
 *      by per-quality links. Multi-season aware via inline "S0X" / "Season X"
 *      headers and via filename inspection when packs are used.
 *   2. Pack-only layout: only quality-pack URLs like
 *      https://links.kmhd.eu/pack/<id> are present. We resolve those by
 *      fetching the pack's SvelteKit `/__data.json`, recovering the list of
 *      (fileId, fileName) pairs, deriving (season, episode) from each
 *      filename, then merging quality packs back together.
 *   3. Flat fallback: if nothing else matches, expose every mirror link
 *      as its own pseudo-episode so the user is never left with an empty
 *      "0 episodes" page.
 *
 * Metadata is enriched in parallel from TMDB (primary) and Cinemeta /
 * Stremio (supplementary) - both are best-effort and never block the
 * playable links from being returned.
 */
class KatMovieHDProvider : MainAPI() {

    override var mainUrl: String = runBlocking { KatMovieHDPlugin.getActiveMainUrl() }
    override var name = "KatMovieHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    companion object {
        private const val TAG = "KatMovieHD"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        // Stremio's free metadata API - very rich per-episode data
        // (real episode names, thumbnails, overviews, air dates).
        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /** Hosts we know how to extract from (used to whitelist <a> elements). */
        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    """links\.kmhd\.[a-z]+|kmhd\.net|gdflix|gd\.kmhd|hubcloud|hubdrive|""" +
                    """katdrive|gdlink|drive\.google|streamtape|filemoon|doodstream|""" +
                    """mixdrop|streamlare|1fichier|send\.cm|hglink|fuckingfast|fastdl""" +
                    """)"""
        )

        /** Identifies a kmhd /pack/<id> URL (these need JSON expansion). */
        private val KMHD_PACK_REGEX = Regex("""(?i)links\.kmhd\.[a-z]+/pack/""")

        /** Identifies a per-file kmhd link (terminal, fed to KmhdExtractor). */
        private val KMHD_FILE_OR_PLAY_REGEX =
            Regex("""(?i)links\.kmhd\.[a-z]+/(file|play)""")

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" etc. */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4" inside a header. */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /**
         * Extract (season, episode) from a release filename such as
         * `Brazil.70.S01E03.480p.WEB-DL...mkv`. Falls back gracefully when
         * only an episode number can be recovered.
         */
        private fun parseSeasonEpisode(filename: String): Pair<Int?, Int?> {
            Regex("""(?i)S(\d{1,2})[\s._-]?E(\d{1,3})""").find(filename)?.let {
                return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            }
            Regex("""(?i)(?:^|[\s._-])E(\d{1,3})(?:[\s._-]|$)""").find(filename)?.let {
                return null to it.groupValues[1].toIntOrNull()
            }
            Regex("""(?i)\bEp(?:isode)?[\s._-]?(\d{1,3})\b""").find(filename)?.let {
                return null to it.groupValues[1].toIntOrNull()
            }
            return null to null
        }

        /** Header tags that can hold "Episode N" / "Season N" / quality labels. */
        private val LABEL_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b")

        /** Negative phrases that should never be treated as episode markers. */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link"
        )
    }

    /**
     * Each entry in [Episode.data] is a serialized JSON list of these.
     * Using a typed wrapper instead of a `\n`-joined string future-proofs
     * us against link strings that happen to contain delimiter characters.
     */
    data class EpisodeSource(val url: String)

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "page/" to "Latest",
        "category/dubbed-movie/page/" to "Hindi Dubbed Movies",
        "category/dual-audio/page/" to "Dual Audio",
        "category/tv-series-dubbed/page/" to "TV Series (Dubbed)",
        "category/netflix/page/" to "Netflix",
        "category/amzn-prime-video/page/" to "Prime Video",
        "category/hotstar/page/" to "Hotstar",
        "category/k-drama/page/" to "K-Drama",
        "category/hindi-dubbed/page/" to "Hindi Dubbed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}$page/"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return newHomePageResponse(request.name, parseListing(doc), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return parseListing(doc).toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        // KatMovieHD's WP theme exposes article cards; fallback to any anchor
        // wrapping an <img> if the theme markup ever changes.
        val direct = doc.select("article, .post").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }
        return doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2 a, h1 a, .entry-title a")
            ?: selectFirst("a[href*=katmoviehd]")
            ?: selectFirst("a")
            ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val rawTitle = anchor.attr("title")
            .ifBlank { anchor.text() }
            .ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        if (!href.contains("katmoviehd", ignoreCase = true)) return null
        val bad = listOf(
            "/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-",
            "/about", "/contact", "/how-to", "/join-"
        )
        if (bad.any { href.contains(it, ignoreCase = true) }) return null
        if (href.trimEnd('/').equals(mainUrl.trimEnd('/'), ignoreCase = true)) return null

        val rawTitle = attr("title").ifBlank { text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    // ------------------------------------------------------------------
    // load() - the main per-title page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".entry-content img, article img")?.absUrl("src")
        val sitePlot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cleanedTitle = cleanTitle(rawTitle)
        val isSeries = guessTvType(rawTitle) == TvType.TvSeries

        // Season from the page title; used as default when individual
        // episode headers don't include one. (Pack expansion later
        // overrides this from the actual filename when possible.)
        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        // Fetch TMDB + Cinemeta in parallel so neither blocks the other.
        // Both are best-effort: we always have a sensible fallback to the
        // values scraped from the page itself.
        val (tmdb, cine) = coroutineScope {
            val tmdbDef = async {
                val id = resolveTmdbId(imdbId, cleanedTitle, isSeries)
                if (id != null) fetchTmdbDetails(id, isSeries)?.let {
                    it to (if (isSeries) fetchTmdbSeason(id, titleSeason) else null)
                } else null
            }
            val cineDef = async {
                if (imdbId != null) fetchCinemeta(imdbId, isSeries) else null
            }
            tmdbDef.await() to cineDef.await()
        }
        val tmdbMeta = tmdb?.first
        val tmdbSeason = tmdb?.second

        val title = tmdbMeta?.title ?: cine?.name ?: cleanedTitle
        val poster = tmdbMeta?.poster ?: cine?.poster ?: sitePoster
        val backdrop = tmdbMeta?.backdrop ?: cine?.background ?: poster
        val plot = tmdbMeta?.overview ?: cine?.description ?: sitePlot
        val year = tmdbMeta?.year
            ?: cine?.year?.substringBefore("–")?.toIntOrNull()
            ?: Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val rating = tmdbMeta?.rating ?: cine?.imdbRating?.let { Score.from10(it) }
        val tags = (tmdbMeta?.genres ?: cine?.genre ?: emptyList()).distinct()
        val actorData = tmdbMeta?.actors ?: emptyList()
        val cineActors = cine?.cast ?: emptyList()
        val trailer = tmdbMeta?.trailer
        val totalSeasons = tmdbMeta?.totalSeasons

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries imdb=$imdbId titleSeason=$titleSeason")

        if (isSeries) {
            val episodes = discoverEpisodes(doc, titleSeason, tmdbSeason, cine)
            Log.d(TAG, "load() discovered ${episodes.size} episodes")

            // Degenerate case: even after pack expansion we got nothing.
            // Fall back to a "movie-style" response (still playable) so
            // the page is never dead-empty.
            if (episodes.isEmpty()) {
                Log.w(TAG, "0 episodes after all strategies, emitting as movie-style links")
                val data = collectAllPlayableLinks(doc)
                return newMovieLoadResponse(title, url, TvType.Movie, data) {
                    applyCommonMeta(this, poster, backdrop, plot, year, tags,
                        actorData, cineActors, rating, trailer, imdbUrl)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, trailer, imdbUrl)
                // If TMDB tells us how many seasons exist, expose human
                // names so the season-picker shows e.g. "Season 4" rather
                // than just "4".
                if (totalSeasons != null && totalSeasons > 1) {
                    addSeasonNames((1..totalSeasons).map { "Season $it" })
                }
            }
        } else {
            // Movie - data is a JSON list of EpisodeSource.
            val data = collectAllPlayableLinks(doc)
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, trailer, imdbUrl)
            }
        }
    }

    private suspend fun applyCommonMeta(
        target: LoadResponse,
        poster: String?,
        backdrop: String?,
        plot: String?,
        year: Int?,
        tags: List<String>,
        actorData: List<ActorData>,
        cineActors: List<String>,
        rating: Score?,
        trailer: String?,
        imdbUrl: String?
    ) {
        target.posterUrl = poster
        target.backgroundPosterUrl = backdrop
        target.plot = plot
        target.year = year
        target.tags = tags
        target.score = rating
        if (actorData.isNotEmpty()) target.actors = actorData
        else if (cineActors.isNotEmpty()) target.addActors(cineActors)
        target.addTrailer(trailer)
        imdbUrl?.let {
            target.addImdbUrl(it)
            Regex("""(tt\d+)""").find(it)?.groupValues?.get(1)?.let { id ->
                target.addImdbId(id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Episode discovery pipeline
    // ------------------------------------------------------------------

    private suspend fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()

        // Stage 1: per-episode headers ("Episode 3 –" + a few <a> below).
        val perHeader = parseEpisodeHeaderLayout(container, defaultSeason)
        if (perHeader.isNotEmpty()) {
            Log.d(TAG, "Stage1 (header layout): ${perHeader.size} episodes across ${perHeader.keys.map { it.first }.distinct().size} season(s)")
            return buildEpisodes(perHeader, tmdbSeason, cine)
        }

        // Stage 2: pack-only pages.
        val packUrls = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> KMHD_PACK_REGEX.containsMatchIn(h) } }
            .distinct()
        if (packUrls.isNotEmpty()) {
            Log.d(TAG, "Stage2 (pack expansion): ${packUrls.size} pack(s): $packUrls")
            val expanded = expandKmhdPacks(packUrls, defaultSeason)
            if (expanded.isNotEmpty()) {
                return buildEpisodes(expanded, tmdbSeason, cine)
            }
        }

        // Stage 3: degraded - expose every mirror link as its own "Source N"
        // pseudo-episode. Always better than a silent empty list.
        val flat = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> LINK_HOST_REGEX.containsMatchIn(h) } }
            .distinct()
        if (flat.isEmpty()) return emptyList()
        Log.w(TAG, "Stage3 (flat fallback): ${flat.size} raw mirror link(s)")
        return flat.mapIndexed { idx, link ->
            newEpisode(listOf(EpisodeSource(link)).toJson()) {
                this.name = "Source ${idx + 1}"
                this.season = defaultSeason
                this.episode = idx + 1
            }
        }
    }

    /**
     * Walk the article in document order, tracking the most recently seen
     * "Season X" / "Episode N" labels, and bucket every kmhd/mirror link
     * we encounter into a (season, episode) -> [links] map.
     *
     * This handles three real layouts on the site:
     *  - <p><b>Episode 1 –</b></p> followed by quality <h3>s with <a>s
     *  - <h3>Episode 1</h3><h3>480p || 720p</h3><h3>...</h3>
     *  - Mixed pages where a "Season 2" subheading flips the active season
     *    halfway through the article.
     */
    private fun parseEpisodeHeaderLayout(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null

        for (node in container.allElements) {
            // Headers update our (season, episode) cursor.
            if (node.tagName() in LABEL_TAGS) {
                val text = node.ownText().ifBlank { node.text() }.trim()
                if (text.isNotEmpty() && text.length < 120 &&
                    EPISODE_NEGATIVE_PHRASES.none { it in text.lowercase() }) {

                    SEASON_HEADER_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    EPISODE_HEADER_REGEX.find(text)?.let { m ->
                        m.groupValues[1].toIntOrNull()?.let { currentEpisode = it }
                    }
                }
            }

            // Anchors are bucketed under the current cursor.
            val ep = currentEpisode
            if (ep != null && node.tagName() == "a") {
                val href = node.attr("href")
                if (LINK_HOST_REGEX.containsMatchIn(href)) {
                    val bucket = map.getOrPut(currentSeason to ep) { mutableListOf() }
                    if (href !in bucket) bucket.add(href)
                }
            }
        }
        return map
    }

    /**
     * Fetch each kmhd pack JSON in parallel and merge the recovered
     * (season, episode) -> [perFileUrl] entries.
     *
     * The season number is derived from the filename when present, which
     * is more reliable than the title (some "S1" pages actually contain
     * S03 packs because they were moved without retitling).
     */
    private suspend fun expandKmhdPacks(
        packUrls: List<String>,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val merged = linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        val perPack = supervisorScope {
            packUrls.map { pack ->
                async {
                    runCatching { fetchKmhdPack(pack) }
                        .onFailure { Log.w(TAG, "Pack fetch failed for $pack: ${it.message}") }
                        .getOrNull().orEmpty()
                }
            }.awaitAll()
        }

        for ((packUrl, entries) in packUrls.zip(perPack)) {
            val host = Regex("""^(https?://[^/]+)""").find(packUrl)?.groupValues?.get(1)
                ?: "https://links.kmhd.eu"
            for ((fileId, fileName) in entries) {
                val (parsedSeason, parsedEpisode) = parseSeasonEpisode(fileName)
                val ep = parsedEpisode ?: continue
                val s = parsedSeason ?: defaultSeason
                val link = "$host/file/$fileId"
                merged.getOrPut(s to ep) { mutableListOf() }.let {
                    if (link !in it) it.add(link)
                }
            }
        }
        return merged
    }

    private suspend fun fetchKmhdPack(packUrl: String): List<Pair<String, String>> {
        val normalized = packUrl.trim().trimEnd('/')
        val dataUrl = "$normalized/__data.json"
        val text = app.get(
            dataUrl,
            headers = headers + mapOf(
                "Cookie" to "unlocked=true",
                "Referer" to normalized,
                "Accept" to "application/json"
            ),
            timeout = 20
        ).text
        if (text.isBlank()) return emptyList()
        return parseKmhdPackJson(text)
    }

    /**
     * Parse SvelteKit's dehydrated NDJSON payload for kmhd's pack pages.
     *
     * Shape (one logical record, pretty-printed):
     *   {"type":"chunk","id":1,"data":[
     *      {"_id":1,"name":2,"info":3},
     *      "<packId>",
     *      "<pack human name>",
     *      {"<fileId1>":4, "<fileId2>":6, ...},
     *      {"name":5},
     *      "<fileName1>",
     *      {"name":7},
     *      "<fileName2>",
     *      ...
     *   ]}
     */
    private fun parseKmhdPackJson(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (obj.optString("type") != "chunk") continue
            val arr = obj.optJSONArray("data") ?: continue

            val root = arr.opt(0) as? JSONObject ?: continue
            val infoIdx = root.optInt("info", -1).takeIf { it > 0 } ?: continue
            val infoMap = arr.opt(infoIdx) as? JSONObject ?: continue

            val keys = infoMap.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val nameNodeIdx = infoMap.optInt(fileId, -1).takeIf { it > 0 } ?: continue
                val nameNode = arr.opt(nameNodeIdx) as? JSONObject ?: continue
                val nameStrIdx = nameNode.optInt("name", -1).takeIf { it > 0 } ?: continue
                val fileName = (arr.opt(nameStrIdx) as? String)?.takeIf { it.isNotBlank() } ?: continue
                out.add(fileId to fileName)
            }
        }
        return out
    }

    /** Movie pages just dump every mirror URL we find. */
    private fun collectAllPlayableLinks(doc: Document): String {
        val content = doc.selectFirst("article, .entry-content") ?: doc
        return content.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> LINK_HOST_REGEX.containsMatchIn(h) } }
            .distinct()
            .map { EpisodeSource(it) }
            .toJson()
    }

    private fun buildEpisodes(
        map: LinkedHashMap<Pair<Int, Int>, MutableList<String>>,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val sorted = map.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return sorted.map { (key, links) ->
            val (season, ep) = key
            val tmdbEp = tmdbSeason
                ?.takeIf { it.seasonNumber == season }
                ?.episodes?.firstOrNull { it.episodeNumber == ep }
            val cineEp = cine?.videos
                ?.firstOrNull { it.season == season && it.episode == ep }

            val sources = links.map { EpisodeSource(it) }
            newEpisode(sources.toJson()) {
                this.name = tmdbEp?.name ?: cineEp?.name ?: cineEp?.title ?: "Episode $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                this.description = tmdbEp?.overview ?: cineEp?.overview
                this.score = tmdbEp?.rating
                (tmdbEp?.airDate ?: cineEp?.released)?.let {
                    // Cinemeta uses ISO timestamps; trim to date if needed.
                    addDate(it.substringBefore("T"))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // loadLinks - resolve a single (movie or episode) to streams
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` is either the JSON list we wrote (new format), or a raw
        // \n / , separated string (legacy / movie fallback).
        val urls: List<String> = when {
            data.isBlank() -> emptyList()
            data.trimStart().startsWith("[") -> tryParseJson<List<EpisodeSource>>(data)
                ?.map { it.url }
                ?: parseJson<List<String>>(data).map { it.trim() }
            else -> data.split("\n", ",")
                .map { it.trim().trim('"', '[', ']') }
                .filter { it.startsWith("http") }
        }.distinct()

        if (urls.isEmpty()) {
            Log.w(TAG, "loadLinks: no URLs extracted from data of length ${data.length}")
            return false
        }
        Log.d(TAG, "loadLinks: dispatching ${urls.size} URL(s)")

        urls.amap { rawUrl ->
            dispatchExtractor(rawUrl, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = rawUrl.trim()
        try {
            // kmhd.net shortener: chase HTTP redirects once and re-dispatch
            // the resolved URL through the normal path.
            val resolved = if (url.contains("kmhd.net", ignoreCase = true)) {
                resolveFinalUrl(url) ?: url
            } else url

            when {
                KMHD_FILE_OR_PLAY_REGEX.containsMatchIn(resolved) ->
                    KmhdExtractor().getUrl(resolved, mainUrl, subtitleCallback, callback)
                else ->
                    loadExtractor(resolved, mainUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extractor crashed for $url: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Metadata: TMDB
    // ------------------------------------------------------------------

    private data class TmdbDetails(
        val title: String?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val year: Int?,
        val rating: Score?,
        val genres: List<String>,
        val actors: List<ActorData>,
        val trailer: String?,
        val totalSeasons: Int?
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
                    app.get(
                        "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id",
                        timeout = 15
                    ).text
                )
                val arrKey = if (isSeries) "tv_results" else "movie_results"
                json.optJSONArray(arrKey)?.optJSONObject(0)?.optInt("id", 0)
                    ?.takeIf { it > 0 }?.let { return it }
            }.onFailure { Log.w(TAG, "TMDB find-by-imdb failed: ${it.message}") }
        }
        return runCatching {
            val q = URLEncoder.encode(cleanedTitle.substringBefore("(").trim(), "UTF-8")
            val json = JSONObject(
                app.get("$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$q", timeout = 15).text
            )
            val results = json.optJSONArray("results") ?: return@runCatching null
            val targetType = if (isSeries) "tv" else "movie"
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") == targetType) {
                    item.optInt("id", 0).takeIf { it > 0 }?.let { return@runCatching it }
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
                    "$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,videos",
                    timeout = 15
                ).text
            )
            val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { null }
            val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = date.takeIf { it.isNotBlank() }?.substringBefore("-")?.toIntOrNull()
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val voteAvg = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
            val totalSeasons = json.optInt("number_of_seasons", 0).takeIf { it > 0 }

            val genres = json.optJSONArray("genres")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let { add(it) }
                    }
                }
            } ?: emptyList()

            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                buildList {
                    val limit = minOf(arr.length(), 25)
                    for (i in 0 until limit) {
                        val c = arr.optJSONObject(i) ?: continue
                        val n = c.optString("name").ifBlank { c.optString("original_name") }
                        if (n.isBlank()) continue
                        val pf = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
                        val ch = c.optString("character").takeIf { it.isNotBlank() }
                        add(ActorData(Actor(n, pf), roleString = ch))
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

            TmdbDetails(title, poster, backdrop, overview, year,
                voteAvg?.let { Score.from10(it) }, genres, actors, trailer, totalSeasons)
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tmdbId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = JSONObject(
                app.get("$TMDB_API/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY", timeout = 15).text
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
                                ?.let { TMDB_IMG + it },
                            airDate = ep.optString("air_date").takeIf { it.isNotBlank() },
                            rating = ep.optDouble("vote_average")
                                .takeIf { !it.isNaN() && it > 0.0 }
                                ?.let { Score.from10(it) }
                        )
                    )
                }
            }
            TmdbSeason(seasonNum, episodes)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Metadata: Cinemeta (Stremio)
    // Used as a supplement to TMDB - cleaner per-episode names + thumbnails
    // for popular shows, gracefully absent for brand-new ones.
    // ------------------------------------------------------------------

    private data class CinemetaMeta(
        val name: String?,
        val description: String?,
        val poster: String?,
        val background: String?,
        val year: String?,
        val imdbRating: String?,
        val genre: List<String>?,
        val cast: List<String>?,
        val videos: List<CinemetaVideo>?
    )

    private data class CinemetaVideo(
        val season: Int?,
        val episode: Int?,
        val name: String?,
        val title: String?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        val type = if (isSeries) "series" else "movie"
        return runCatching {
            val text = app.get("$CINEMETA/$type/$imdbId.json", timeout = 15).text
            if (text.isBlank()) return@runCatching null
            val root = JSONObject(text).optJSONObject("meta") ?: return@runCatching null
            CinemetaMeta(
                name = root.optString("name").takeIf { it.isNotBlank() },
                description = root.optString("description").takeIf { it.isNotBlank() },
                poster = root.optString("poster").takeIf { it.isNotBlank() },
                background = root.optString("background").takeIf { it.isNotBlank() },
                year = root.optString("year").takeIf { it.isNotBlank() },
                imdbRating = root.optString("imdbRating").takeIf { it.isNotBlank() },
                genre = root.optJSONArray("genre")?.let { a ->
                    buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
                },
                cast = root.optJSONArray("cast")?.let { a ->
                    buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
                },
                videos = root.optJSONArray("videos")?.let { a ->
                    buildList {
                        for (i in 0 until a.length()) {
                            val v = a.optJSONObject(i) ?: continue
                            add(
                                CinemetaVideo(
                                    season = v.optInt("season", -1).takeIf { it >= 0 },
                                    episode = v.optInt("episode", -1).takeIf { it >= 0 }
                                        ?: v.optInt("number", -1).takeIf { it >= 0 },
                                    name = v.optString("name").takeIf { it.isNotBlank() },
                                    title = v.optString("title").takeIf { it.isNotBlank() },
                                    released = v.optString("released").takeIf { it.isNotBlank() }
                                        ?: v.optString("firstAired").takeIf { it.isNotBlank() },
                                    overview = v.optString("overview").takeIf { it.isNotBlank() }
                                        ?: v.optString("description").takeIf { it.isNotBlank() },
                                    thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    /**
     * Strip the verbose tags KatMovieHD jams into article titles
     * ("Hindi Dubbed (DD 5.1) & English [Dual Audio] WEB-DL 1080p ...").
     * We keep an AKA chunk if present because it often carries the show's
     * original-language name, which helps TMDB search.
     */
    private fun cleanTitle(raw: String): String {
        val withoutDecorators = raw
            .replace(Regex("""(?i)\s*\|.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?]"""), "")
            .replace(Regex("""(?i)\s*\(DD\s*\d.*?\)"""), "")
            .replace(Regex("""(?i)\s*\(ORG\)"""), "")
            .replace(Regex("""(?i)\bHindi Dubbed.*$"""), "")
            .replace(Regex("""(?i)\bDual Audio.*$"""), "")
            .replace(Regex("""(?i)\bWEB[-\s]?DL.*$"""), "")
            .replace(Regex("""(?i)\bBluRay.*$"""), "")
            .replace(Regex("""(?i)\bFull Movie.*$"""), "")
            .replace(Regex("""(?i)\bAll Episodes.*$"""), "")
            .replace(Regex("""(?i)\bRemastered\b"""), "")
            .replace(Regex("""(?i)\s*-\s*KatMovieHD.*$"""), "")
            .replace(Regex("""(?i)\s+AKA\s+"""), " AKA ")
            .replace(Regex("""(?i)\((Season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '|', ':')
            .trim()
        return withoutDecorators.ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val t = title.lowercase()
        return when {
            t.contains("season") ||
            t.contains("episode") ||
            t.contains("series") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(t) ||
            Regex("""\bs0\d\b""").containsMatchIn(t) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }
}
