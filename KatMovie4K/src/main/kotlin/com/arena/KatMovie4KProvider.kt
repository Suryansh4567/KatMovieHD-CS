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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
import okhttp3.MediaType.Companion.toMediaType

/**
 * KatMovie4K provider — sister of KatMovieHDProvider.
 */
class KatMovie4KProvider : MainAPI() {

    override var mainUrl: String = runBlocking { KatMovie4KPlugin.getActiveMainUrl() }
    override var name = "KatMovie4K"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /** Feature #6: Browser-like headers (HDHub4U / VegaMovies pattern) */
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Linux\"",
        "Cookie" to "xla=s4t"
    )

    companion object {
        private const val TAG = "KatMovie4K"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with",
            "el", "la", "los", "las", "un", "una", "y", "de", "del",
            "ka", "ki", "ke", "se", "aur"
        )

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // KMHD-native
                    """links\.kmhd\.[a-z]+|kmhd\.net|kmhd\.eu|gd\.kmhd|""" +
                    // KatMovie4K-specific redirectors
                    """ziddiflix\.com|driveleech\.org|vifix\.site|""" +
                    // GDFlix / GDTot family (covers gdflix.rest, .dad, .dev, .cfd, .live etc.)
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot\.[a-z]+|gdmirror|""" +
                    // HubCloud / Hubdrive / Katdrive family (covers hubcloud.lol etc.)
                    """hubcloud\.[a-z]+|hubcdn|hubstream|hubdrive|katdrive|""" +
                    // Appdrive / drive-redirect hosts
                    """appdrive\.lol|appdrive\.[a-z]+|drive\d*\.link|""" +
                    // Generic cloud / file-share hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|filesgram|""" +
                    // Streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // Hindi-dub / 4K-specific upload mirrors
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|techkit|busycdn|""" +
                    // Short-link redirectors / router APIs
                    """dhakrey\.eu\.org|miniroad\.store|parklogic\.com|""" +
                    // KatMovie* upload mirrors
                    """katmovie|katdrive|kmhd""" +
                    """)"""
        )

        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """katmovie4k\.[a-z]+/(tag/|category/|page/|author/|wp-|feed|$)|katmoviehd\.[a-z]+/(tag/|category/|page/|author/|wp-|feed|$)|katmoviehd\.(phd|fit|it)|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#""" +
                    """)"""
        )

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" etc. */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4" inside a header. */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Identifies a kmhd /pack/<id> URL (these need JSON expansion). */
        private val KMHD_PACK_REGEX = Regex("""(?i)links\.kmhd\.[a-z]+/pack/""")

        /** Regex to extract the file ID from a gdtot URL for router API resolution. */
        private val GDTOT_FILE_ID_REGEX = Regex(
            """(?i)/(?:file/)(\d+)"""
        )

        /** Regex to match any gdtot domain. */
        private val GDTOT_DOMAIN_REGEX = Regex(
            """(?i)gdtot\.[a-z]+"""
        )

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

        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link"
        )
    }

    override val mainPage = mainPageOf(
        "page/" to "Latest 4K",
        "category/4k-movies/page/" to "4K Movies",
        "category/4k-series/page/" to "4K Series",
        "category/2160p-hdr/page/" to "2160p HDR",
        "category/dolby-vision/page/" to "Dolby Vision",
        "category/in-hindi-hindi-dubbed/page/" to "Hindi Dubbed (4K)",
        "category/marvel/page/" to "Marvel",
        "category/disney-plus/page/" to "Disney+",
        "category/netflix/page/" to "Netflix"
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
        mainUrl = KatMovie4KPlugin.getActiveMainUrl()
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val results = parseListing(doc)
        return results.toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val items = doc.select("li[id^=post-]").mapNotNull { it.toSearchResultFromItem() }
        if (items.isNotEmpty()) return items.distinctBy { it.url }

        val direct = doc.select("article, .post").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }

        return doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResultFromItem(): SearchResponse? {
        val titleAnchor = selectFirst("h2 a[href], h3 a[href], .post-title a[href], .title a[href]")
            ?: selectFirst("div.post-content a[href]")
            ?: return null
        val href = titleAnchor.attr("href").ifBlank { return null }
        val rawTitle = titleAnchor.attr("title")
            .ifBlank { titleAnchor.text() }
            .ifBlank { selectFirst("img[fifu-featured], img.Thumbnail, .post-thumb img")?.attr("alt") ?: "" }
            .ifBlank { return null }

        val poster = select("img[fifu-featured], img.Thumbnail, .post-thumb img, img[src*='catimages'], img[src*='katimages']").firstNotNullOfOrNull { img ->
            val u = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            u.takeIf {
                it.isNotBlank() &&
                    !it.contains("label", ignoreCase = true) &&
                    !it.contains("overlay", ignoreCase = true) &&
                    !it.contains("Unofficial", ignoreCase = true)
            }
        } ?: selectFirst("img[fifu-featured], img.Thumbnail, .post-thumb img")?.let {
            it.absUrl("data-src").ifBlank { it.absUrl("src") }.ifBlank { null }
        }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2 a, h1 a, .entry-title a")
            ?: selectFirst("a[href*=katmovie4k]")
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
        if (!href.contains("katmovie4k", ignoreCase = true)) return null
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[fifu-featured], .entry-content img, article img")?.absUrl("src")
        val sitePlot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cleanedTitle = cleanTitle(rawTitle)
        val isSeries = guessTvType(rawTitle) == TvType.TvSeries

        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        val yearHint = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val (tmdb, cine) = coroutineScope {
            val tmdbDef = async {
                val id = resolveTmdbId(imdbId, cleanedTitle, isSeries, yearHint)
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
                val links = collectAllPlayableLinks(doc)
                return newMovieLoadResponse(title, url, TvType.Movie, links) {
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
            val links = collectAllPlayableLinks(doc)
            return newMovieLoadResponse(title, url, TvType.Movie, links) {
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

        // Stage 3: degraded - expose every mirror link as its own pseudo-
        // episode. Always better than a silent empty list.
        val flat = collectMirrorLinksWithLabels(container)
        if (flat.isEmpty()) return emptyList()
        Log.w(TAG, "Stage3 (flat fallback): ${flat.size} raw mirror link(s)")
        return flat.mapIndexed { idx, (label, link) ->
            newEpisode(link) {
                this.name = label ?: "Source ${idx + 1}"
                this.season = defaultSeason
                this.episode = idx + 1
            }
        }
    }

    private fun collectMirrorLinksWithLabels(container: Element): List<Pair<String?, String>> {
        val cleanUrls: Set<String> = LinkedHashSet(collectMirrorLinks(container))
        if (cleanUrls.isEmpty()) return emptyList()

        val qualityRegex = Regex(
            """(?i)\b(2160p|4k|1080p\s*hevc|1080p\s*x264|1080p|720p|480p|hdr|""" +
                """watch\s*online|play\s*online|trailer|subtitle|pack|esubs?)\b"""
        )
        val out = mutableListOf<Pair<String?, String>>()
        val seen = mutableSetOf<String>()
        for (a in container.select("a[href]")) {
            val href = a.attr("href")
            if (href !in cleanUrls || href in seen) continue
            seen.add(href)

            val raw = (a.text().ifBlank {
                a.parents().firstOrNull { p -> p.tagName() in LABEL_TAGS }?.text() ?: ""
            }).trim().trim('_', '*', ':', ' ')

            val match = qualityRegex.find(raw)
            val label = match?.value?.trim()?.let { q ->
                // Normalize a few common variants for prettier display.
                when {
                    q.equals("watch online", ignoreCase = true) ||
                        q.equals("play online", ignoreCase = true) -> "Watch Online"
                    q.contains("1080p", ignoreCase = true) && q.contains("hevc", ignoreCase = true) -> "1080p HEVC"
                    q.contains("1080p", ignoreCase = true) && q.contains("x264", ignoreCase = true) -> "1080p x264"
                    else -> q.replaceFirstChar { it.uppercase() }
                }
            }
            out.add(label to href)
        }
        return out
    }

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

    private fun collectMirrorLinks(container: Element): List<String> {
        val all = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> h.startsWith("http", ignoreCase = true) } }
            .distinct()
            // Some KatMovie4K pages put a decorative "DOWNLOAD LINKS" heading
            // that links to kmhd.net/directlink (or similar generic landing
            // pages). These are not real file mirrors - they just bounce the
            // user to the site's homepage / ad-gate. Filter them out so they
            // never become a Source entry that fails with "no link found".
            .filter { url ->
                !url.matches(Regex(
                    """(?i)^https?://(?:www\.)?kmhd\.net/(directlink|home|index)/?$"""
                )) &&
                !url.matches(Regex(
                    """(?i)^https?://(?:www\.)?burydibase\.com/.*$"""
                ))
            }
            // NOTE: We no longer rewrite gdtot.* URLs to gdflix.dev at
            // link-collection time. The old GDTOT_DEAD_REGEX rewrite was
            // actively harmful — gdtot file IDs do NOT exist on gdflix.dev
            // (shows "file deleted"), so every rewritten URL always failed.
            // Instead, gdtot URLs are now resolved at dispatch time via
            // resolveGdtotUrl() which tries the router.parklogic.com API.

        val strict = all.filter { LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        // Pass 2: anything not obviously junk.
        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    !url.contains(mainUrl, ignoreCase = true)
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict host whitelist matched 0 links, falling back to permissive (${permissive.size}): ${permissive.take(3)}")
        }
        return permissive
    }

    private fun collectAllPlayableLinks(doc: Document): String {
        val content = doc.selectFirst("article, .entry-content") ?: doc
        val links = collectMirrorLinks(content)
        Log.d(TAG, "collectAllPlayableLinks(): ${links.size} links")
        return links.joinToString("\n")
    }

    /**
     * Fetch each kmhd pack JSON in parallel and merge the recovered
     * (season, episode) -> [perFileUrl] entries.
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

    private fun buildEpisodes(
        map: LinkedHashMap<Pair<Int, Int>, MutableList<String>>,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val sorted = map.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return sorted.map { (key, links) ->
            val (season, ep) = key
            val tmdbEp = tmdbSeason?.takeIf { it.seasonNumber == season }?.episodes?.firstOrNull { it.episodeNumber == ep }
            val cineEp = cine?.videos?.firstOrNull { it.season == season && it.episode == ep }

            newEpisode(links.joinToString("\n")) {
                this.name = tmdbEp?.name ?: cineEp?.name ?: cineEp?.title ?: "Episode $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                this.description = tmdbEp?.overview ?: cineEp?.overview
                this.score = tmdbEp?.rating
                (tmdbEp?.airDate ?: cineEp?.released)?.let { addDate(it.substringBefore("T")) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls: List<String> = buildList {
            if (data.trimStart().startsWith("[")) {
                runCatching { tryParseJson<List<String>>(data)?.let { addAll(it) } }
            }
            data.split('\n', ',', '\r', '\t').forEach { add(it) }
        }
            .map { it.trim().trim('"', '\'', '[', ']', '(', ')', ' ', '<', '>') }
            .filter { it.startsWith("http", ignoreCase = true) }
            .map { stripJunkSuffix(it) }
            .filter { it.length > 8 }
            .distinct()

        if (urls.isEmpty()) {
            Log.w(TAG, "loadLinks: no URLs extracted from data of length ${data.length}")
            return false
        }
        Log.d(TAG, "loadLinks: dispatching ${urls.size} URL(s)")

        var anyDispatched = false
        urls.amap { rawUrl ->
            val ok = dispatchExtractor(rawUrl, subtitleCallback, callback)
            if (ok) anyDispatched = true
        }
        if (!anyDispatched) {
            Log.w(TAG, "loadLinks: every URL failed to dispatch - check host coverage")
        }
        return true
    }

    private fun stripJunkSuffix(url: String): String {
        var u = url
        while (u.isNotEmpty() && u.last() in setOf(')', ']', '}', '.', ',', ';', '!', '?', '"', '\'')) { u = u.dropLast(1) }
        val hashIdx = u.indexOf('#')
        if (hashIdx > 0) u = u.substring(0, hashIdx)
        return u
    }

    /**
     * Try to resolve a gdtot.* URL through the router.parklogic.com API.
     *
     * GDTot domains (new*.gdtot.dad, new*.gdtot.cfd) are behind Cloudflare
     * JS challenges that CloudStream's HTTP client cannot solve directly.
     * However, their pages contain JavaScript that POSTs to
     * router.parklogic.com/file/{fileId} which returns a redirect URL.
     * This function replicates that API call.
     *
     * @return a resolved URL if the router API returns one, or null.
     */
    private suspend fun resolveGdtotUrl(url: String): String? {
        val fileId = GDTOT_FILE_ID_REGEX.find(url)?.groupValues?.get(1)
        if (fileId.isNullOrBlank()) {
            Log.d(TAG, "resolveGdtotUrl: no file ID found in $url")
            return null
        }
        Log.d(TAG, "resolveGdtotUrl: attempting router API for fileId=$fileId from $url")

        // Extract domain components from the original URL
        val domainRegex = Regex("""(?i)^https?://((?:new\d+\.)?(gdtot\.[a-z]+))/""")
        val match = domainRegex.find(url)
        val domainFull = match?.groupValues?.get(1) ?: "new7.gdtot.dad"
        val domainApex = match?.groupValues?.get(2) ?: "gdtot.dad"

        val routerUrl = "https://router.parklogic.com/file/$fileId"
        val body = JSONObject().apply {
            put("parameters", JSONObject().apply {
                put("domainApex", domainApex)
                put("domainFull", domainFull)
                put("protocol", "https")
                put("path", "/file/$fileId")
                put("adBlockingDetected", false)
                put("timezoneBrowser", "UTC")
                put("webdriver", false)
            })
        }.toString()

        return try {
            // Use OkHttp directly since NiceHttp's app.post() parameter
            // names are not stable across CloudStream3 versions.
            val requestBody = okhttp3.RequestBody.create(
                "text/plain".toMediaType(),
                body
            )
            val request = okhttp3.Request.Builder()
                .url(routerUrl)
                .post(requestBody)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Content-Type", "text/plain")
                .addHeader("Origin", "https://$domainFull")
                .addHeader("Referer", url)
                .build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val httpResponse = client.newCall(request).execute()
            val response = httpResponse.body?.string()?.trim().orEmpty()

            if (response.startsWith("http", ignoreCase = true)) {
                Log.d(TAG, "resolveGdtotUrl: router returned redirect URL: $response")
                // The redirect URL may itself be behind CF, but some
                // CloudStream builds with WebView can handle it.
                return response
            }

            // If the response is HTML, try to parse it for download links
            if (response.startsWith("<", ignoreCase = true)) {
                Log.d(TAG, "resolveGdtotUrl: router returned HTML, parsing for links")
                val doc = org.jsoup.Jsoup.parse(response)
                val link = doc.select("a[href]").mapNotNull { a ->
                    a.attr("href").takeIf { it.startsWith("http", ignoreCase = true) }
                }.firstOrNull()
                if (link != null) {
                    Log.d(TAG, "resolveGdtotUrl: found link in HTML: $link")
                    return link
                }
            }

            Log.d(TAG, "resolveGdtotUrl: router returned unexpected response: ${response.take(100)}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "resolveGdtotUrl: router API failed for $url: ${e.message}")
            null
        }
    }

    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = rawUrl.trim()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false

        return try {
            // Feature #2: Resolve obfuscated redirects BEFORE dispatching
            if (url.contains("?id=") || url.contains("?go=") || url.contains("?link=") || url.contains("?url=") || url.contains("/goto/") || url.contains("/link/") || url.contains("/go/") || url.contains("/download/") || url.contains("?r=") || url.contains("?k=") || url.contains("?d=") || url.contains("miniroad.store") || url.contains("dhakrey.eu.org") || url.contains("parklogic.com")) {
                val resolved = getRedirectLinks(url)
                if (resolved != null && resolved != url) {
                    Log.d(TAG, "dispatchExtractor: resolved obfuscated redirect: $url -> $resolved")
                    url = resolved
                }
            }

            when {
                // KMHD family
                Regex("""(?i)(links\.kmhd\.|kmhd\.(eu|net)/archives/|gd\.kmhd\.)""").containsMatchIn(url) -> {
                    KmhdExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #3: Hubdrive
                url.contains("hubdrive", ignoreCase = true) -> {
                    Hubdrive().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #3+#4: HubCloud
                Regex("""(?i)(hubcloud\.)""").containsMatchIn(url) -> {
                    HubCloud().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #3: HUBCDN
                url.contains("hubcdn", ignoreCase = true) -> {
                    HUBCDN().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #12+#13: Hubstream/VidStack
                url.contains("hubstream", ignoreCase = true) -> {
                    Hubstream().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #3: Hblinks
                url.contains("hblinks", ignoreCase = true) -> {
                    Hblinks().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Feature #3: PixelDrainDev
                url.contains("pixeldrain.dev", ignoreCase = true) -> {
                    PixelDrainDev().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // KatDrive → resolve to HubCloud
                url.contains("katdrive", ignoreCase = true) -> {
                    runCatching {
                        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30).document
                        val hubUrl = doc.select("a[href]").mapNotNull { a ->
                            a.absUrl("href").ifBlank { a.attr("href") }
                                .takeIf { it.contains("hubcloud", ignoreCase = true) }
                        }.firstOrNull()
                        if (!hubUrl.isNullOrBlank()) HubCloud().getUrl(hubUrl, url, subtitleCallback, callback)
                        else {
                            val resolved = getRedirectLinks(url)
                            if (resolved != null) dispatchExtractor(resolved, subtitleCallback, callback)
                            else loadExtractor(url, mainUrl, subtitleCallback, callback)
                        }
                    }
                    true
                }
                url.contains("gdlink.dev", ignoreCase = true) -> { GDLinkDev().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("vifix.site", ignoreCase = true) -> { Vifix().getUrl(url, mainUrl, subtitleCallback, callback); true }
                Regex("""(?i)driveleech\.(org|pro|net)""").containsMatchIn(url) -> { Driveleech().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("appdrive.lol", ignoreCase = true) || Regex("""(?i)appdrive\.[a-z]+""").containsMatchIn(url) -> { Appdrive().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("gdflix.live", ignoreCase = true) -> {
                    val bypassUrl = if (url.contains("fp=")) url else "$url${if (url.contains("?")) "&" else "?"}fp=-7"
                    GDFlix().getUrl(bypassUrl, mainUrl, subtitleCallback, callback); true
                }
                url.contains("new.gdflix.dad", ignoreCase = true) -> { GDFlixDad().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("new3.gdflix.dad", ignoreCase = true) -> { GDFlixDad3().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("new4.gdflix.dad", ignoreCase = true) -> { GDFlixDad4().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("gdflix.rest", ignoreCase = true) -> { GDFlixRest().getUrl(url, mainUrl, subtitleCallback, callback); true }
                url.contains("new5.gdflix.cfd", ignoreCase = true) -> { GDFlixCfd5().getUrl(url, mainUrl, subtitleCallback, callback); true }
                GDTOT_DOMAIN_REGEX.containsMatchIn(url) -> {
                    val resolved = resolveGdtotUrl(url)
                    if (resolved != null) dispatchExtractor(resolved, subtitleCallback, callback)
                    else GDTotCfd().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                url.contains("dhakrey.eu.org", ignoreCase = true) -> {
                    try {
                        val resolved = resolveFinalUrl(url) ?: url
                        if (resolved != url) dispatchExtractor(resolved, subtitleCallback, callback)
                        else loadExtractor(url, mainUrl, subtitleCallback, callback)
                    } catch (_: Exception) { loadExtractor(url, mainUrl, subtitleCallback, callback) }
                    true
                }
                Regex("""(?i)(gdflix|gdmirror|gd-flix|gdlink|ziddiflix)""").containsMatchIn(url) -> { GDFlix().getUrl(url, mainUrl, subtitleCallback, callback); true }
                // Feature #2: Catch-all — try redirect resolution first
                else -> {
                    val resolved = getRedirectLinks(url)
                    if (resolved != null && resolved != url && resolved.startsWith("http")) {
                        Log.d(TAG, "dispatchExtractor: catch-all redirect resolved: $url -> $resolved")
                        dispatchExtractor(resolved, subtitleCallback, callback)
                    } else {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extractor crashed for $url: ${e.message}")
            try {
                val resolved = getRedirectLinks(rawUrl)
                if (resolved != null && resolved != rawUrl) {
                    loadExtractor(resolved, mainUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
            true
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
        isSeries: Boolean,
        yearHint: Int? = null
    ): Int? {
        if (!imdbId.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id", timeout = 15).text)
                val arrKey = if (isSeries) "tv_results" else "movie_results"
                json.optJSONArray(arrKey)?.optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 }?.let { return it }
            }
        }

        return runCatching {
            val queryTitle = cleanedTitle.substringBefore("(").trim()
            val q = URLEncoder.encode(queryTitle, "UTF-8")
            val json = JSONObject(app.get("$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$q", timeout = 15).text)
            val results = json.optJSONArray("results") ?: return@runCatching null
            val targetType = if (isSeries) "tv" else "movie"

            var bestId = 0
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") != targetType) continue
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue

                val candidates = listOf(item.optString("title"), item.optString("name"), item.optString("original_title"), item.optString("original_name")).filter { it.isNotBlank() }
                val titleSim = candidates.maxOfOrNull { titleSimilarity(queryTitle, it) } ?: 0.0

                val tmdbYear = (item.optString("release_date").ifBlank { item.optString("first_air_date") })
                    .takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
                val yearBoost = if (yearHint != null && tmdbYear != null) {
                    val diff = kotlin.math.abs(tmdbYear - yearHint)
                    when { diff == 0 -> 0.15; diff == 1 -> 0.05; diff > 3 -> -0.20; else -> 0.0 }
                } else 0.0

                val score = titleSim + yearBoost
                if (score > bestScore) { bestScore = score; bestId = id }
            }

            if (bestScore >= 0.45 && bestId > 0) bestId else null
        }.getOrNull()
    }

    private fun titleSimilarity(a: String, b: String): Double {
        fun tokens(s: String) = s.lowercase().replace(Regex("""[^a-z0-9\s]"""), " ").split(Regex("""\s+""")).filter { it.isNotBlank() && it !in STOPWORDS }.toSet()
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        if (union == 0.0) return 0.0
        val coverage = maxOf(inter / ta.size, inter / tb.size)
        val jaccard = inter / union
        return 0.7 * coverage + 0.3 * jaccard
    }

    private suspend fun fetchTmdbDetails(tmdbId: Int, isSeries: Boolean): TmdbDetails? {
        val type = if (isSeries) "tv" else "movie"
        return runCatching {
            val json = JSONObject(app.get("$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,videos", timeout = 15).text)
            val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { null }
            val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = date.takeIf { it.isNotBlank() }?.substringBefore("-")?.toIntOrNull()
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val voteAvg = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
            val totalSeasons = json.optInt("number_of_seasons", 0).takeIf { it > 0 }

            val genres = json.optJSONArray("genres")?.let { arr -> buildList { for (i in 0 until arr.length()) { arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) } } } } ?: emptyList()
            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr -> buildList { val limit = minOf(arr.length(), 25); for (i in 0 until limit) { val c = arr.optJSONObject(i) ?: continue; val n = c.optString("name").ifBlank { c.optString("original_name") }; if (n.isBlank()) continue; val pf = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }; val ch = c.optString("character").takeIf { it.isNotBlank() }; add(ActorData(Actor(n, pf), roleString = ch)) } } } ?: emptyList()
            val trailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { arr -> var found: String? = null; for (i in 0 until arr.length()) { val v = arr.optJSONObject(i) ?: continue; if (v.optString("site") == "YouTube" && v.optString("type").contains("Trailer", true)) { found = "https://www.youtube.com/watch?v=${v.optString("key")}"; break } }; found }

            TmdbDetails(title, poster, backdrop, overview, year, voteAvg?.let { Score.from10(it) }, genres, actors, trailer, totalSeasons)
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tmdbId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = JSONObject(app.get("$TMDB_API/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY", timeout = 15).text)
            val arr = json.optJSONArray("episodes") ?: return@runCatching null
            val episodes = buildList { for (i in 0 until arr.length()) { val ep = arr.optJSONObject(i) ?: continue; val epNum = ep.optInt("episode_number", -1).takeIf { it > 0 } ?: continue; add(TmdbEpisode(episodeNumber = epNum, name = ep.optString("name").takeIf { it.isNotBlank() }, overview = ep.optString("overview").takeIf { it.isNotBlank() }, stillUrl = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }, airDate = ep.optString("air_date").takeIf { it.isNotBlank() }, rating = ep.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }?.let { Score.from10(it) })) } }
            TmdbSeason(seasonNum, episodes)
        }.getOrNull()
    }

    private data class CinemetaMeta(
        val name: String?, val description: String?, val poster: String?, val background: String?, val year: String?, val imdbRating: String?, val genre: List<String>?, val cast: List<String>?, val videos: List<CinemetaVideo>?
    )

    private data class CinemetaVideo(
        val season: Int?, val episode: Int?, val name: String?, val title: String?, val released: String?, val overview: String?, val thumbnail: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        val type = if (isSeries) "series" else "movie"
        return runCatching {
            val text = app.get("$CINEMETA/$type/$imdbId.json", timeout = 15).text
            if (text.isBlank()) return@runCatching null
            val root = JSONObject(text).optJSONObject("meta") ?: return@runCatching null
            CinemetaMeta(name = root.optString("name").takeIf { it.isNotBlank() }, description = root.optString("description").takeIf { it.isNotBlank() }, poster = root.optString("poster").takeIf { it.isNotBlank() }, background = root.optString("background").takeIf { it.isNotBlank() }, year = root.optString("year").takeIf { it.isNotBlank() }, imdbRating = root.optString("imdbRating").takeIf { it.isNotBlank() }, genre = root.optJSONArray("genre")?.let { a -> buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } } }, cast = root.optJSONArray("cast")?.let { a -> buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } } }, videos = root.optJSONArray("videos")?.let { a -> buildList { for (i in 0 until a.length()) { val v = a.optJSONObject(i) ?: continue; add(CinemetaVideo(season = v.optInt("season", -1).takeIf { it >= 0 }, episode = v.optInt("episode", -1).takeIf { it >= 0 } ?: v.optInt("number", -1).takeIf { it >= 0 }, name = v.optString("name").takeIf { it.isNotBlank() }, title = v.optString("title").takeIf { it.isNotBlank() }, released = v.optString("released").takeIf { it.isNotBlank() } ?: v.optString("firstAired").takeIf { it.isNotBlank() }, overview = v.optString("overview").takeIf { it.isNotBlank() } ?: v.optString("description").takeIf { it.isNotBlank() }, thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() })) } } })
        }.getOrNull()
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""(?i)\s*\|.*$"""), "").replace(Regex("""(?i)\s*\[.*?]"""), "").replace(Regex("""(?i)\s*\(DD\s*\d.*?\)"""), "").replace(Regex("""(?i)\s*\(ORG\)"""), "").replace(Regex("""(?i)\b4K\s*Ultra\s*HD.*$"""), "").replace(Regex("""(?i)\bUltra\s*HD.*$"""), "").replace(Regex("""(?i)\b2160p.*$"""), "").replace(Regex("""(?i)\bUHD.*$"""), "").replace(Regex("""(?i)\bx265\b.*$"""), "").replace(Regex("""(?i)\bHEVC\b.*$"""), "").replace(Regex("""(?i)\bREMUX\b.*$"""), "").replace(Regex("""(?i)\bDolby\s*Vision.*$"""), "").replace(Regex("""(?i)\bHDR\d*\+?.*$"""), "").replace(Regex("""(?i)\bSDR\b.*$"""), "").replace(Regex("""(?i)\bBlu[- ]?Ray.*$"""), "").replace(Regex("""(?i)\bHindi Dubbed.*$"""), "").replace(Regex("""(?i)\bDual Audio.*$"""), "").replace(Regex("""(?i)\bWEB[-\s]?DL.*$"""), "").replace(Regex("""(?i)\bFull Movie.*$"""), "").replace(Regex("""(?i)\bAll Episodes.*$"""), "").replace(Regex("""(?i)\s*-\s*KatMovie4K.*$"""), "").replace(Regex("""(?i)\s*—\s*KatMovie4K.*$"""), "").replace(Regex("""(?i)\((Season\s*\d+)\)"""), "").replace(Regex("""(?i)\s*Season\s*\d+"""), "").replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '—').trim().ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val t = title.lowercase()
        return when {
            t.contains("season") || t.contains("episode") || t.contains("series") || t.contains("tv show") || t.contains("4k series") || Regex("""\bs\d{1,2}\b""").containsMatchIn(t) || Regex("""\bs0\d\b""").containsMatchIn(t) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        return detectSearchQualityPro(title)
    }
}
