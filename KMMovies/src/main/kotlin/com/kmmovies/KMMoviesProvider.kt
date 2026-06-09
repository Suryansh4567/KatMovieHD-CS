package com.kmmovies

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * KMMovies (kmmovies.lol) CloudStream 3 provider.
 *
 * This is a WordPress-based movie/series download site. The site structure
 * is similar to other Hindi-dub sites (KatMovieHD, MkvHub, etc.) and uses
 * generic WordPress article layouts. We use the SAME robust approach that
 * works in the KatMovieHD provider:
 *
 *   1. Generic WordPress selectors (article, .entry-content, h1.entry-title)
 *      instead of theme-specific CSS classes - works across any theme change.
 *   2. Two-pass link collection (strict known hosts first, permissive fallback).
 *   3. Document-walker episode discovery that tracks "Episode N" / "Season X"
 *      headers and buckets links accordingly.
 *   4. Flat fallback: if no episode structure found, expose every mirror link
 *      as its own pseudo-episode so the user is never left with "0 episodes".
 *
 * KMMovies-specific redirectors:
 *   - magiclinks.lol → page with skydrop/kmphotos links
 *   - skydrop.sbs/download.php?id=XXX → API at skydrop.sbs/api.php?id=XXX → JSON with video URL
 *   - kmphotos.cv/online.php?file=XXX → 302 redirect to R2 stream URL
 *   - kmphotos.cv/download99.php?file=XXX → signed download URL
 *
 * CloudStream's WebViewResolver handles Cloudflare protection automatically
 * when the extension runs inside the app.
 */
class KMMoviesProvider : MainAPI() {

    override var mainUrl: String = runBlocking { KMMoviesPlugin.getActiveMainUrl() }
    override var name = "KMMovies"
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
        private const val TAG = "KMMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /**
         * Known hosts used by KMMovies for download/stream links.
         * A match here promotes the URL to "definitely a stream link" status.
         * Kept deliberately wide - false positives just mean loadExtractor
         * no-ops, but false negatives mean the user sees "no links".
         */
        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // KMMovies-specific redirectors
                    """magiclinks\.lol|skydrop\.sbs|kmphotos\.cv|kmphotos|""" +
                    // HubCloud / Hubdrive family (commonly used)
                    """hubcloud\.[a-z]+|hubcdn|hubstream|hubdrive\.|katdrive|""" +
                    // GDFlix family
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot|gdmirror|""" +
                    // Generic cloud/file-share hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|""" +
                    // Streaming/video hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // Hindi-dub specific
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|techkit|""" +
                    // Cloudflare R2 direct links
                    """r2\.dev|cloudflare\.com/cdn-cgi/|""" +
                    // Known shorteners/redirectors
                    """mclinks\.|hblinks\.|obsession\.buzz|hdstream4u|""" +
                    // Additional common mirrors
                    """gdrive|gdurl|gd\.|drive\.|""" +
                    """katmovie|katdrive|kmhd|kmmovies""" +
                    """)"""
        )

        /**
         * Hosts that are NEVER stream sources. Filtered out during
         * permissive pass so we don't try to extract from IMDb, social
         * media, or image-host links.
         */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|wp-admin|""" +
                    """kmmovies\.lol|kmmovies\.life|kmmovies\.icu|""" +
                    """gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|/comment""" +
                    """)"""
        )

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" / "Ep 3" etc. */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\bEp(?:isode)?\s*[-–:#.]?\s*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4" inside a header. */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Header tags that can hold "Episode N" / "Season N" / quality labels. */
        private val LABEL_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b")

        /** Negative phrases that should never be treated as episode markers. */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link", "download all"
        )

        /** Quality regex for parsing download buttons and link text. */
        private val QUALITY_REGEX = Regex(
            """(?i)(2160p|4k|1080p\s*hevc|1080p\s*x264|1080p\s*av1|1080p\s*10bit|""" +
                    """1080p|720p\s*10bit|720p|480p|hdr|dv|web-dl|webdl|hdts|hdtc|""" +
                    """hdrip|brrip|bdrip|dvdrip|predvd|camrip|cam|ts|hdcam)"""
        )
    }

    // ==================================================================
    // Main page & search
    // ==================================================================

    override val mainPage = mainPageOf(
        "page/" to "Latest",
        "category/movies/page/" to "Movies",
        "category/tv-series/page/" to "TV Series",
        "category/bollywood/page/" to "Bollywood",
        "category/hollywood/page/" to "Hollywood",
        "category/dual-audio/page/" to "Dual Audio",
        "category/hindi/page/" to "Hindi",
        "category/4k/page/" to "4K",
        "category/south/page/" to "South",
        "category/kdrama/page/" to "KDrama",
        "category/anime/page/" to "Anime",
        "category/tamil/page/" to "Tamil",
        "category/telugu/page/" to "Telugu"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val url = "$mainUrl/${request.data}$page/"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return newHomePageResponse(request.name, parseListing(doc), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val results = parseListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    /**
     * Parse listing pages (homepage, category, search).
     * Uses the SAME multi-fallback approach as KatMovieHD:
     *   1. <li id="post-NNNN"> items (most WordPress themes)
     *   2. <article> / .post elements
     *   3. Any <a> wrapping an <img> that points to a post
     */
    private fun parseListing(doc: Document): List<SearchResponse> {
        // Primary: WordPress post list items
        val items = doc.select("li[id^=post-]").mapNotNull { it.toSearchResultFromItem() }
        if (items.isNotEmpty()) return items.distinctBy { it.url }

        // Fallback 1: article/.post card layout
        val direct = doc.select("article, .post").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }

        // Fallback 2: any anchor wrapping an <img>
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
            .ifBlank { return null }

        val poster = select("div.post-thumb img, div.thumbnail img").firstNotNullOfOrNull { img ->
            img.absUrl("data-src").ifBlank { img.absUrl("src") }.takeIf { it.isNotBlank() }
        } ?: selectFirst("img")?.let {
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
        // Must be a link to a kmmovies page
        if (!href.contains("kmmovies", ignoreCase = true) &&
            !href.contains(mainUrl, ignoreCase = true)) return null

        val bad = listOf(
            "/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-",
            "/about", "/contact", "/how-to", "/join-", "/genre/",
            "/trending", "/browse", "/year/", "/director/", "/writer/"
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

    // ==================================================================
    // load() - the main per-title page
    // ==================================================================

    override suspend fun load(url: String): LoadResponse {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val doc = app.get(url, headers = headers, timeout = 30).document

        // ---- Extract metadata using GENERIC WordPress selectors ----
        // Same approach as KatMovieHD: try specific selectors first, then
        // fallback to meta tags. This works regardless of theme.

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

        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        val yearHint = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // ---- Enrich with TMDB / Cinemeta in parallel ----
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
            ?: cine?.year?.substringBefore("\u2013")?.toIntOrNull()
            ?: yearHint
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

            if (episodes.isEmpty()) {
                Log.w(TAG, "0 episodes discovered, emitting as movie-style links")
                val links = collectAllPlayableLinks(doc)
                return newMovieLoadResponse(title, url, TvType.Movie, links) {
                    applyCommonMeta(this, poster, backdrop, plot, year, tags,
                        actorData, cineActors, rating, trailer, imdbUrl)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, trailer, imdbUrl)
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

    private fun applyCommonMeta(
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

    // ==================================================================
    // Episode discovery - same proven document-walker approach
    // ==================================================================

    private fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()

        // Stage 1: per-episode headers ("Episode 3" + links below)
        val perHeader = parseEpisodeHeaderLayout(container, defaultSeason)
        if (perHeader.isNotEmpty()) {
            Log.d(TAG, "Stage1 (header layout): ${perHeader.size} episodes")
            return buildEpisodes(perHeader, tmdbSeason, cine)
        }

        // Stage 2: flat fallback - expose every mirror link as its own
        // pseudo-episode. Always better than a silent empty list.
        val flat = collectMirrorLinksWithLabels(container)
        if (flat.isEmpty()) return emptyList()
        Log.w(TAG, "Stage2 (flat fallback): ${flat.size} raw mirror link(s)")
        return flat.mapIndexed { idx, (label, link) ->
            newEpisode(link) {
                this.name = label ?: "Source ${idx + 1}"
                this.season = defaultSeason
                this.episode = idx + 1
            }
        }
    }

    /**
     * Walk the article in document order, tracking "Season X" / "Episode N"
     * labels, and bucket every known-host link into (season, episode) -> [links].
     */
    private fun parseEpisodeHeaderLayout(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null

        for (node in container.allElements) {
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
     * Two-pass link collector:
     *   Pass 1 (strict): URLs matching LINK_HOST_REGEX (known-good hosts).
     *   Pass 2 (permissive): if pass 1 found nothing, every external URL
     *                        not on the blacklist and not the site itself.
     */
    private fun collectMirrorLinks(container: Element): List<String> {
        val all = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> h.startsWith("http", ignoreCase = true) } }
            .distinct()

        val strict = all.filter { LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        // Pass 2: anything not obviously junk
        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    !url.contains(mainUrl, ignoreCase = true)
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict host whitelist matched 0 links, falling back to permissive (${permissive.size}): ${permissive.take(3)}")
        }
        return permissive
    }

    private fun collectMirrorLinksWithLabels(container: Element): List<Pair<String?, String>> {
        val cleanUrls: Set<String> = LinkedHashSet(collectMirrorLinks(container))
        if (cleanUrls.isEmpty()) return emptyList()

        val qualityLabelRegex = Regex(
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

            val match = qualityLabelRegex.find(raw)
            val label = match?.value?.trim()?.let { q ->
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

    private fun collectAllPlayableLinks(doc: Document): String {
        val content = doc.selectFirst("article, .entry-content") ?: doc
        val links = collectMirrorLinks(content)
        Log.d(TAG, "collectAllPlayableLinks(): ${links.size} links")
        return links.joinToString("\n")
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

            newEpisode(links.joinToString("\n")) {
                this.name = tmdbEp?.name ?: cineEp?.name ?: "Episode $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                this.description = tmdbEp?.overview ?: cineEp?.overview
                this.score = tmdbEp?.rating
                (tmdbEp?.airDate ?: cineEp?.released)?.let {
                    addDate(it.substringBefore("T"))
                }
            }
        }
    }

    // ==================================================================
    // loadLinks - resolve download links to playable streams
    // ==================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Parse URLs from data (newline-separated, same format as KatMovieHD)
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
            Log.w(TAG, "loadLinks: no URLs extracted from data")
            return false
        }
        Log.d(TAG, "loadLinks: resolving ${urls.size} URL(s)")

        var anyDispatched = false
        urls.amap { rawUrl ->
            val ok = dispatchExtractor(rawUrl, subtitleCallback, callback)
            if (ok) anyDispatched = true
        }

        if (!anyDispatched) {
            Log.w(TAG, "loadLinks: every URL failed to dispatch")
        }
        return anyDispatched
    }

    private fun stripJunkSuffix(url: String): String {
        var u = url
        while (u.isNotEmpty() && u.last() in setOf(')', ']', '}', '.', ',', ';', '!', '?', '"', '\'')) {
            u = u.dropLast(1)
        }
        val hashIdx = u.indexOf('#')
        if (hashIdx > 0) u = u.substring(0, hashIdx)
        return u
    }

    /**
     * Per-URL dispatch. Tries KMMovies-specific redirectors first,
     * then falls back to CloudStream's stock loadExtractor.
     */
    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.trim()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false

        return try {
            when {
                // KMMovies redirector: magiclinks.lol → page with actual links
                url.contains("magiclinks.lol", ignoreCase = true) -> {
                    resolveMagiclinks(url, callback)
                    true
                }

                // Skydrop download page → call API to get video URL
                url.contains("skydrop.sbs", ignoreCase = true) &&
                    url.contains("download.php", ignoreCase = true) -> {
                    resolveSkydrop(url, callback)
                    true
                }

                // Skydrop API URL directly
                url.contains("skydrop.sbs", ignoreCase = true) &&
                    url.contains("api.php", ignoreCase = true) -> {
                    resolveSkydropApi(url, callback)
                    true
                }

                // kmphotos stream/download
                url.contains("kmphotos", ignoreCase = true) -> {
                    resolveKmphotos(url, callback)
                    true
                }

                // Direct video URL (R2, Google Drive, etc.)
                url.contains("r2.dev", ignoreCase = true) ||
                url.contains("googleusercontent.com", ignoreCase = true) -> {
                    val quality = guessQualityFromUrl(url)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Direct $quality",
                            url = url,
                            referer = "$mainUrl/",
                            quality = getQualityInt(quality),
                            isM3u8 = url.endsWith(".m3u8", ignoreCase = true)
                        )
                    )
                    true
                }

                // Everything else: try CloudStream's stock extractor registry
                else -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extractor crashed for $url: ${e.message}")
            true
        }
    }

    // ==================================================================
    // KMMovies-specific redirector resolution
    // ==================================================================

    /**
     * Resolve a magiclinks.lol page. These pages contain links to
     * skydrop/kmphotos (actual download/stream sources).
     */
    private suspend fun resolveMagiclinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ), timeout = 30).document

            val allLinks = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                href.takeIf {
                    it.startsWith("http", ignoreCase = true) &&
                    (it.contains("skydrop", ignoreCase = true) ||
                     it.contains("kmphotos", ignoreCase = true) ||
                     it.contains("hubcloud", ignoreCase = true) ||
                     it.contains("gdrive", ignoreCase = true) ||
                     it.contains("r2.dev", ignoreCase = true) ||
                     it.contains("googleusercontent", ignoreCase = true) ||
                     it.contains("drive.google", ignoreCase = true))
                }
            }.distinct()

            for (link in allLinks) {
                try {
                    dispatchExtractor(link, { _ -> }, callback)
                } catch (_: Exception) {}
            }

            // If no specific links found, try ALL external links as fallback
            if (allLinks.isEmpty()) {
                val anyLinks = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    href.takeIf {
                        it.startsWith("http", ignoreCase = true) &&
                        !IGNORE_HOST_REGEX.containsMatchIn(it) &&
                        !it.contains("magiclinks", ignoreCase = true)
                    }
                }.distinct()

                for (link in anyLinks) {
                    try {
                        loadExtractor(link, mainUrl, { _ -> }, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve magiclinks page $url: ${e.message}")
        }
    }

    /**
     * Resolve skydrop.sbs/download.php?id=XXX by calling the API.
     */
    private suspend fun resolveSkydrop(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1) ?: return
            val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                ?: return
            resolveSkydropApi("$base/api.php?id=$id", callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve skydrop URL $url: ${e.message}")
        }
    }

    /**
     * Call the skydrop API and extract the video link.
     * Response format: {"success":true,"link":"https://...","download_url":"https://...","type":"video"}
     */
    private suspend fun resolveSkydropApi(
        apiUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json"
            ), timeout = 15).parsedSafe<SkydropResponse>()

            if (response?.success == true) {
                val videoLink = response.link
                if (!videoLink.isNullOrBlank()) {
                    val quality = guessQualityFromUrl(videoLink)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name SkyDrop $quality",
                            url = videoLink,
                            referer = "$mainUrl/",
                            quality = getQualityInt(quality),
                            isM3u8 = videoLink.endsWith(".m3u8", ignoreCase = true)
                        )
                    )
                }

                // Also try the download URL via redirect
                val downloadUrl = response.download_url
                if (!downloadUrl.isNullOrBlank() && downloadUrl != videoLink) {
                    try {
                        val dlResp = app.get(downloadUrl, headers = mapOf(
                            "User-Agent" to USER_AGENT
                        ), timeout = 15, allowRedirects = false)
                        val dlLocation = dlResp.headers["location"]
                        if (!dlLocation.isNullOrBlank()) {
                            val quality = guessQualityFromUrl(dlLocation)
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name Direct $quality",
                                    url = dlLocation,
                                    referer = "$mainUrl/",
                                    quality = getQualityInt(quality),
                                    isM3u8 = dlLocation.endsWith(".m3u8", ignoreCase = true)
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve skydrop API $apiUrl: ${e.message}")
        }
    }

    /**
     * Resolve kmphotos.cv URLs (online.php or download99.php).
     * online.php → 302 redirect to a player page with the R2 video URL
     * download99.php → 302 redirect to a signed download URL
     */
    private suspend fun resolveKmphotos(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,*/*;q=0.8"
            ), timeout = 15, allowRedirects = false)

            val redirectUrl = resp.headers["location"] ?: ""
            if (redirectUrl.isNotBlank()) {
                // Check if redirect URL contains a videoUrl parameter
                val videoUrl = Regex("""videoUrl=([^&]+)""").find(redirectUrl)
                    ?.groupValues?.get(1)
                    ?.let { URLDecoder.decode(it, "UTF-8") }

                if (videoUrl != null) {
                    val quality = guessQualityFromUrl(url)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Stream $quality",
                            url = videoUrl,
                            referer = "$mainUrl/",
                            quality = getQualityInt(quality),
                            isM3u8 = videoUrl.endsWith(".m3u8", ignoreCase = true)
                        )
                    )
                } else {
                    // The redirect itself might be the video URL
                    try {
                        loadExtractor(redirectUrl, mainUrl, { _ -> }, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve kmphotos URL $url: ${e.message}")
        }
    }

    // ==================================================================
    // Helper utilities
    // ==================================================================

    private fun guessQualityFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> "4K"
            lower.contains("1080p") && lower.contains("hevc") -> "1080p HEVC"
            lower.contains("1080p") -> "1080p"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> "HQ"
        }
    }

    private fun getQualityInt(quality: String): Int = when (quality) {
        "4K" -> 2160
        "1080p HEVC", "1080p" -> 1080
        "720p" -> 720
        "480p" -> 480
        else -> 720
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*(Download|Free|Watch|Online|Full Movie|HDRip|WEB-DL|WEBDL|BluRay|720p|1080p|480p|2160p|4K|HEVC|x264|x265|AV1|10bit|Dual Audio|Hindi Dubbed|Hindi|English|Korean|Chinese|Japanese|ESubs?|S\d{1,2}(?:E\d{1,3})?).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val lower = title.lowercase()
        val seriesIndicators = listOf(
            "season ", " s0", " s1", " s2", " s3", " s4", " s5",
            "episode ", " e0", " e1", "complete", " kdrama",
            " tv series", " web series", " hindi dubbe"
        )
        val movieIndicators = listOf(
            "movie", "film", "720p", "1080p", "480p", "4k", "2160p"
        )
        val seriesScore = seriesIndicators.count { lower.contains(it) }
        val movieScore = movieIndicators.count { lower.contains(it) }

        // Also check for S##E## pattern
        if (Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b""").containsMatchIn(title)) return TvType.TvSeries
        if (Regex("""(?i)\bSeason\s*\d+""").containsMatchIn(title)) return TvType.TvSeries

        return if (seriesScore > movieScore) TvType.TvSeries else TvType.Movie
    }

    private fun detectSearchQuality(title: String, badge: String? = null): SearchQuality {
        val combined = (badge.orEmpty() + " " + title).lowercase()
        return when {
            combined.contains("4k") || combined.contains("2160p") -> SearchQuality.FourK
            combined.contains("1080p") -> SearchQuality.HD
            combined.contains("720p") -> SearchQuality.HD
            combined.contains("480p") -> SearchQuality.SD
            else -> SearchQuality.Unknown
        }
    }

    // ==================================================================
    // TMDB metadata
    // ==================================================================

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
                val json = org.json.JSONObject(
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
            val queryTitle = cleanedTitle.substringBefore("(").trim()
            val q = URLEncoder.encode(queryTitle, "UTF-8")
            val json = org.json.JSONObject(
                app.get("$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$q", timeout = 15).text
            )
            val results = json.optJSONArray("results") ?: return@runCatching null
            val targetType = if (isSeries) "tv" else "movie"

            var bestId = 0
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") != targetType) continue
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue

                val candidates = listOf(
                    item.optString("title"),
                    item.optString("name"),
                    item.optString("original_title"),
                    item.optString("original_name")
                ).filter { it.isNotBlank() }

                val maxSim = candidates.maxOfOrNull { titleSimilarity(it, queryTitle) } ?: 0.0

                val itemYear = item.optString("release_date", item.optString("first_air_date", ""))
                    .substringBefore("-").toIntOrNull()
                val yearBonus = if (yearHint != null && itemYear == yearHint) 0.15 else 0.0

                val score = maxSim + yearBonus
                if (score > bestScore && score >= 0.5) {
                    bestScore = score
                    bestId = id
                }
            }
            bestId.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun titleSimilarity(a: String, b: String): Double {
        val la = a.lowercase().split(Regex("""\s+""")).toSet()
        val lb = b.lowercase().split(Regex("""\s+""")).toSet()
        if (la.isEmpty() || lb.isEmpty()) return 0.0
        val intersection = la.intersect(lb).size
        val union = la.union(lb).size
        return intersection.toDouble() / union.toDouble()
    }

    private suspend fun fetchTmdbDetails(id: Int, isSeries: Boolean): TmdbDetails? {
        return runCatching {
            val type = if (isSeries) "tv" else "movie"
            val json = org.json.JSONObject(
                app.get("$TMDB_API/$type/$id?api_key=$TMDB_API_KEY&append_to_response=credits,videos", timeout = 15).text
            )

            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "$TMDB_IMG$it" }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "$TMDB_IMG$it" }

            val genres = json.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            } ?: emptyList()

            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 10)).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    ActorData(
                        Actor(c.optString("name"), c.optString("character")),
                        c.optString("profile_path")?.let { "$TMDB_IMG$it" }
                    )
                }
            } ?: emptyList()

            val trailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).firstOrNull { i ->
                    val v = arr.optJSONObject(i)
                    v?.optString("type") == "Trailer" && v.optString("site") == "YouTube"
                }?.let { arr.optJSONObject(it)?.optString("key") }
            }?.let { "https://www.youtube.com/watch?v=$it" }

            TmdbDetails(
                title = json.optString("title", json.optString("name", "")).ifBlank { null },
                poster = poster,
                backdrop = backdrop,
                overview = json.optString("overview").ifBlank { null },
                year = json.optString("release_date", json.optString("first_air_date", ""))
                    .substringBefore("-").toIntOrNull(),
                rating = json.optDouble("vote_average", 0.0).let {
                    if (it > 0) Score.from10(it.toString()) else null
                },
                genres = genres,
                actors = actors,
                trailer = trailer,
                totalSeasons = if (isSeries) json.optInt("number_of_seasons", 0).takeIf { it > 0 } else null
            )
        }.onFailure { Log.w(TAG, "TMDB details fetch failed: ${it.message}") }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tvId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = org.json.JSONObject(
                app.get("$TMDB_API/tv/$tvId/season/$seasonNum?api_key=$TMDB_API_KEY", timeout = 15).text
            )
            val episodes = json.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    TmdbEpisode(
                        episodeNumber = e.optInt("episode_number", 0),
                        name = e.optString("name").ifBlank { null },
                        overview = e.optString("overview").ifBlank { null },
                        stillUrl = e.optString("still_path").takeIf { it.isNotBlank() }
                            ?.let { "$TMDB_IMG$it" },
                        airDate = e.optString("air_date").ifBlank { null },
                        rating = e.optDouble("vote_average", 0.0).let {
                            if (it > 0) Score.from10(it.toString()) else null
                        }
                    )
                }
            } ?: emptyList()

            TmdbSeason(seasonNumber = seasonNum, episodes = episodes)
        }.onFailure { Log.w(TAG, "TMDB season fetch failed: ${it.message}") }.getOrNull()
    }

    // ==================================================================
    // Cinemeta metadata
    // ==================================================================

    private data class CinemetaMeta(
        val name: String?,
        val poster: String?,
        val background: String?,
        val description: String?,
        val genre: List<String>,
        val cast: List<String>,
        val imdbRating: String?,
        val year: String?,
        val videos: List<CinemetaVideo>?
    )

    private data class CinemetaVideo(
        val season: Int?,
        val episode: Int?,
        val name: String?,
        val title: String?,
        val overview: String?,
        val thumbnail: String?,
        val released: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        return runCatching {
            val type = if (isSeries) "series" else "movie"
            val json = org.json.JSONObject(
                app.get("$CINEMETA/$type/$imdbId.json", timeout = 15).text
            ).optJSONObject("meta") ?: return null

            val videos = json.optJSONArray("videos")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    CinemetaVideo(
                        season = v.optInt("season", -1).takeIf { it > 0 },
                        episode = v.optInt("episode", -1).takeIf { it > 0 },
                        name = v.optString("name").ifBlank { null },
                        title = v.optString("title").ifBlank { null },
                        overview = v.optString("overview").ifBlank { null },
                        thumbnail = v.optString("thumbnail").ifBlank { null },
                        released = v.optString("released").ifBlank { null }
                    )
                }
            }

            CinemetaMeta(
                name = json.optString("name").ifBlank { null },
                poster = json.optString("poster").ifBlank { null },
                background = json.optString("background").ifBlank { null },
                description = json.optString("description").ifBlank { null },
                genre = json.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() },
                cast = json.optString("cast").split(",").map { it.trim() }.filter { it.isNotBlank() },
                imdbRating = json.optString("imdbRating").ifBlank { null },
                year = json.optString("year").ifBlank { null },
                videos = videos
            )
        }.onFailure { Log.w(TAG, "Cinemeta fetch failed: ${it.message}") }.getOrNull()
    }

    // ==================================================================
    // Skydrop API response model
    // ==================================================================

    private data class SkydropResponse(
        @JsonProperty("success") val success: Boolean? = false,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("download_url") val download_url: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
