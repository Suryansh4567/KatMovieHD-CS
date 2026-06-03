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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * OlaMovies v2 provider.
 *
 * Targets v2.olamovies.mov — a WordPress/Gridlove site that hosts
 * 4K UHD, HDR, Dolby Vision, and REMUX releases. The site uses
 * Cloudflare on its main domain and CF Turnstile on its link
 * shortener (links.ol-am.top).
 *
 * Episode discovery handles three layouts:
 *   1. Accordion layout: w3-card-4 sections with Season/Episode buttons
 *   2. Flat layout: quality groups with release name headers
 *   3. Fallback: expose every link as a pseudo-episode
 *
 * All download links go through links.ol-am.top which resolves to
 * Google Drive / HubCloud / GDFlix etc.
 */
class OlaMoviesV2Provider : MainAPI() {

    override var mainUrl: String = runBlocking { OlaMoviesV2Plugin.getActiveMainUrl() }
    override var name = "OlaMoviesV2"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Documentary
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Linux\""
    )

    companion object {
        private const val TAG = "OlaMoviesV2"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with",
            "el", "la", "los", "las", "un", "una", "y", "de", "del",
            "ka", "ki", "ke", "se", "aur"
        )

        /**
         * OlaMovies link shortener — the primary URL pattern for
         * download links on the site.
         */
        private val OLA_LINK_REGEX = Regex(
            """(?i)links\.ol-am\.top"""
        )

        /**
         * Known mirror hosts that OlaMovies links resolve to.
         */
        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // OlaMovies shortener
                    """links\.ol-am\.top|ol-am\.top|""" +
                    // OlaMovies direct server
                    """olamovies\.dad|space\.olamovies|""" +
                    // HubCloud family
                    """hubcloud\.[a-z]+|hubcdn|hubstream|hubdrive|katdrive|""" +
                    // GDFlix family
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot\.[a-z]+|gdmirror|""" +
                    // Google Drive
                    """drive\.google|""" +
                    // Generic cloud/file-share hosts
                    """gofile\.io|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|filesgram|""" +
                    // Streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // VidStack
                    """vidstack\.io""" +
                    """)"""
        )

        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """olamovies\.mov/(tag/|category/|page/|author/|wp-|feed|$)|""" +
                    """gstatic|googletagmanager|google-analytics|google\.com/search|""" +
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

        /** Match "Specials" in accordion header */
        private val SPECIALS_REGEX = Regex("""(?i)\bSpecials?\b""")

        /**
         * Extract (season, episode) from a release filename.
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
            "single episodes link", "knowledge desk", "changelog"
        )

        /**
         * Quality + size regex for link text like:
         *   "720p [831.79MB]"
         *   "1080p x264 [15.34GB]"
         *   "2160p 4K HDR10+ DV [18.63GB]"
         *   "1080p DS4K [4.71GB]"
         *   "1080p remux [22.77GB]"
         *   "episode 01"
         *   "720p zip [2.76GB]"
         *   "extras [973.3MB]"
         */
        private val QUALITY_SIZE_REGEX = Regex(
            """(?i)(2160p|4k|1080p\s*x264|1080p\s*hevc|1080p\s*ds4k|1080p\s*remux|1080p|720p|480p|""" +
                    """hdr10\+?\s*dv|hdr10\+?|dv|dolby\s*vision|sdr|remux|ds4k|""" +
                    """episode\s*\d+|extras?|zip|pack|""" +
                    """\[[\d.]+\s*(?:MB|GB|TB)])"""
        )
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "page/" to "Latest",
        "category/movies/hollywood/page/" to "Hollywood Movies",
        "category/movies/bollywood/page/" to "Bollywood Movies",
        "category/movies/south-indian/page/" to "South Indian Movies",
        "category/movies/anime-movies/page/" to "Anime Movies",
        "category/movies/documentary-movies/page/" to "Documentaries",
        "category/tv-series/english-tv-series/page/" to "English TV Series",
        "category/tv-series/korean-tv-series/page/" to "Korean TV Series",
        "category/tv-series/anime-tv-series-tv-series/page/" to "Anime TV Series",
        "tag/hindi-dubbed/page/" to "Hindi Dubbed",
        "dolby-vision-dv/" to "Dolby Vision",
        "hdr10plus/" to "HDR10+",
        "remux/" to "REMUX",
        "imax/" to "IMAX"
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
        mainUrl = OlaMoviesV2Plugin.getActiveMainUrl()

        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val results = parseListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        // Primary: Gridlove theme uses article.gridlove-post
        val items = doc.select("article.gridlove-post").mapNotNull { it.toSearchResult() }
        if (items.isNotEmpty()) return items.distinctBy { it.url }

        // Fallback 1: generic article
        val direct = doc.select("article.post, article").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }

        // Fallback 2: any anchor wrapping an <img>
        return doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    /**
     * Parse one Gridlove article card:
     *   <article class="gridlove-post ...">
     *     <div class="entry-image">
     *       <a href><img src=POSTER class="wp-post-image"></a>
     *     </div>
     *     <div class="box-inner-p">
     *       <h2 class="entry-title h3"><a href>TITLE</a></h2>
     *     </div>
     *   </article>
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = selectFirst("h2.entry-title a, h3.entry-title a, .entry-title a")
            ?: selectFirst("h2 a, h3 a, h1 a")
            ?: return null
        val href = titleAnchor.attr("href").ifBlank { return null }
        val rawTitle = titleAnchor.text().ifBlank { return null }

        val poster = selectFirst("img.wp-post-image, .entry-image img")?.let { img ->
            img.absUrl("data-src").ifBlank { img.absUrl("src") }.ifBlank { null }
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

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        if (!href.contains("olamovies", ignoreCase = true)) return null
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
            ?: doc.selectFirst(".entry-image img, article img")?.absUrl("src")
        val sitePlot = doc.select(".entry-content p.wp-block-paragraph")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.select(".entry-content p")
                .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Detect type from article category classes
        val articleClasses = doc.selectFirst("article")?.className() ?: ""
        val isSeries = guessTvType(rawTitle) == TvType.TvSeries ||
                articleClasses.contains("category-tv-series", ignoreCase = true) ||
                articleClasses.contains("category-tv-shows", ignoreCase = true)

        val cleanedTitle = cleanTitle(rawTitle)

        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        val yearHint = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Fetch TMDB + Cinemeta in parallel
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

    // ------------------------------------------------------------------
    // Episode discovery pipeline
    // ------------------------------------------------------------------

    private fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()

        // Stage 1: Accordion layout — w3-card-4 sections with Season buttons
        val accordion = parseAccordionLayout(container, defaultSeason)
        if (accordion.isNotEmpty()) {
            Log.d(TAG, "Stage1 (accordion): ${accordion.size} episodes across ${accordion.keys.map { it.first }.distinct().size} season(s)")
            return buildEpisodes(accordion, tmdbSeason, cine)
        }

        // Stage 2: Per-episode headers ("Episode 3" + links below)
        val perHeader = parseEpisodeHeaderLayout(container, defaultSeason)
        if (perHeader.isNotEmpty()) {
            Log.d(TAG, "Stage2 (header layout): ${perHeader.size} episodes")
            return buildEpisodes(perHeader, tmdbSeason, cine)
        }

        // Stage 3: Flat fallback — expose every link as its own episode
        val flat = collectLinksWithLabels(container)
        if (flat.isEmpty()) return emptyList()
        Log.w(TAG, "Stage3 (flat fallback): ${flat.size} raw link(s)")
        return flat.mapIndexed { idx, (label, link) ->
            newEpisode(link) {
                this.name = label ?: "Source ${idx + 1}"
                this.season = defaultSeason
                this.episode = idx + 1
            }
        }
    }

    /**
     * OlaMovies uses W3.CSS accordion sections for multi-source/multi-season:
     *
     *   <div class="w3-margin w3-monospace w3-card-4">
     *     <button onclick="myFunction('1')">Season 1 <span class="w3-badge">8</span></button>
     *     <div id="1" class="w3-container ...">
     *       <div class="wp-block-buttons">
     *         <a class="wp-block-button__link" href="links.ol-am.top/...">720p [831MB]</a>
     *         <a class="wp-block-button__link" href="links.ol-am.top/...">1080p [2.55GB]</a>
     *       </div>
     *     </div>
     *   </div>
     *
     * We walk all accordion sections, track the season from the button text,
     * and within each section look for "Episode N" headers + quality links.
     */
    private fun parseAccordionLayout(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        val accordions = container.select("div.w3-margin.w3-monospace.w3-card-4, div.w3-card-4")
        if (accordions.isEmpty()) return map

        for (accordion in accordions) {
            // Extract season from button text
            val buttonText = accordion.selectFirst("button.w3-button")?.text() ?: ""
            var currentSeason = SEASON_HEADER_REGEX.find(buttonText)?.let { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            } ?: defaultSeason

            // Handle "Specials" → season 0
            if (SPECIALS_REGEX.containsMatchIn(buttonText)) {
                currentSeason = 0
            }

            // The content div (id matches the onclick target)
            val contentDiv = accordion.selectFirst("div.w3-container") ?: accordion
            var currentEpisode: Int? = null
            var episodeCounter = 0

            for (node in contentDiv.allElements) {
                // Check for episode headers within the accordion
                if (node.tagName() in LABEL_TAGS) {
                    val text = node.ownText().ifBlank { node.text() }.trim()
                    if (text.isNotEmpty() && text.length < 120 &&
                        EPISODE_NEGATIVE_PHRASES.none { it in text.lowercase() }) {

                        EPISODE_HEADER_REGEX.find(text)?.let { m ->
                            m.groupValues[1].toIntOrNull()?.let { currentEpisode = it }
                        }
                    }
                }

                // Collect links
                if (node.tagName() == "a") {
                    val href = node.attr("href")
                    if (href.startsWith("http", ignoreCase = true) &&
                        (OLA_LINK_REGEX.containsMatchIn(href) || LINK_HOST_REGEX.containsMatchIn(href))) {

                        val linkText = node.text().trim().lowercase()
                        val isZipOrPack = linkText.contains("zip") || linkText.contains("pack")

                        // Try to extract episode number from link text like "episode 01"
                        val linkEp = EPISODE_HEADER_REGEX.find(linkText)?.groupValues?.get(1)?.toIntOrNull()

                        val ep = currentEpisode ?: linkEp ?: if (isZipOrPack) {
                            // zip/pack links are full-season bundles — assign episode 0
                            0
                        } else {
                            // No episode context found, auto-increment
                            episodeCounter++
                            episodeCounter
                        }

                        val bucket = map.getOrPut(currentSeason to ep) { mutableListOf() }
                        if (href !in bucket) bucket.add(href)
                    }
                }
            }
        }
        return map
    }

    /**
     * Walk the article in document order, tracking the most recently seen
     * "Season X" / "Episode N" labels, and bucket every link into a
     * (season, episode) -> [links] map.
     */
    private fun parseEpisodeHeaderLayout(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var episodeCounter = 0

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
                if (href.startsWith("http", ignoreCase = true) &&
                    (OLA_LINK_REGEX.containsMatchIn(href) || LINK_HOST_REGEX.containsMatchIn(href))) {
                    val bucket = map.getOrPut(currentSeason to ep) { mutableListOf() }
                    if (href !in bucket) bucket.add(href)
                }
            }
        }
        return map
    }

    /**
     * Collect all download links with their quality labels from the page.
     * Handles both wp-block-buttons and standalone anchors.
     * Returns (label, url) pairs where label comes from the anchor text.
     */
    private fun collectLinksWithLabels(container: Element): List<Pair<String?, String>> {
        val cleanUrls = LinkedHashSet(collectDownloadLinks(container))
        if (cleanUrls.isEmpty()) return emptyList()

        val out = mutableListOf<Pair<String?, String>>()
        val seen = mutableSetOf<String>()

        for (a in container.select("a[href]")) {
            val href = a.attr("href")
            if (href !in cleanUrls || href in seen) continue
            seen.add(href)

            // Label from anchor text (e.g. "720p [831.79MB]", "2160p 4K HDR10 DV [18.63GB]")
            val labelText = a.text().trim()
            val label = if (labelText.isNotBlank()) {
                // Clean up the label for display
                labelText
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            } else null

            out.add(label to href)
        }
        return out
    }

    /**
     * Two-pass link collector for download URLs:
     *   Pass 1 (strict): links.ol-am.top + known mirror hosts
     *   Pass 2 (permissive): any external URL not on the blacklist
     */
    private fun collectDownloadLinks(container: Element): List<String> {
        val all = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> h.startsWith("http", ignoreCase = true) } }
            .distinct()
            // Filter out navigation/landing page URLs
            .filter { url ->
                !url.contains("google.com/search", ignoreCase = true) &&
                !url.contains("/?remux=", ignoreCase = true) &&
                !url.contains("/?dv=", ignoreCase = true) &&
                !url.contains("/?hdr10plus=", ignoreCase = true)
            }

        val strict = all.filter { OLA_LINK_REGEX.containsMatchIn(it) || LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        // Pass 2: permissive — anything not obviously junk
        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    !url.contains(mainUrl, ignoreCase = true)
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict host whitelist matched 0 links, falling back to permissive (${permissive.size})")
        }
        return permissive
    }

    private fun collectAllPlayableLinks(doc: Document): String {
        val content = doc.selectFirst("article, .entry-content") ?: doc
        val links = collectDownloadLinks(content)
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
                this.name = tmdbEp?.name ?: cineEp?.name ?: cineEp?.title ?: "Episode $ep"
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

    // ------------------------------------------------------------------
    // loadLinks - resolve a single (movie or episode) to streams
    // ------------------------------------------------------------------

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
            Log.w(TAG, "loadLinks: no URLs extracted from data")
            return false
        }
        Log.d(TAG, "loadLinks: dispatching ${urls.size} URL(s)")

        var anyDispatched = false
        urls.amap { rawUrl ->
            val ok = dispatchExtractor(rawUrl, subtitleCallback, callback)
            if (ok) anyDispatched = true
        }
        if (!anyDispatched) {
            Log.w(TAG, "loadLinks: every URL failed to dispatch")
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

    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = rawUrl.trim()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false

        return try {
            // Resolve obfuscated redirects BEFORE dispatching
            if (url.contains("?id=") || url.contains("?go=") || url.contains("?link=") ||
                url.contains("?url=") || url.contains("/goto/") || url.contains("/link/") ||
                url.contains("/go/") || url.contains("/download/") || url.contains("?r=") ||
                url.contains("?k=") || url.contains("?d=")) {
                val resolved = getRedirectLinks(url)
                if (resolved != null && resolved != url) {
                    Log.d(TAG, "dispatchExtractor: resolved redirect: $url -> $resolved")
                    url = resolved
                }
            }

            when {
                // OlaMovies link shortener
                OLA_LINK_REGEX.containsMatchIn(url) -> {
                    OlaLinks().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // HubCloud
                Regex("""(?i)(hubcloud\.)""").containsMatchIn(url) -> {
                    OlaHubCloud().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Hubdrive
                url.contains("hubdrive", ignoreCase = true) -> {
                    OlaHubdrive().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Hubstream
                url.contains("hubstream", ignoreCase = true) -> {
                    OlaHubstream().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // GDFlix family
                Regex("""(?i)(gdflix|gd-flix|gdlink|gdtot)""").containsMatchIn(url) -> {
                    OlaGDFlix().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // PixelDrain
                url.contains("pixeldrain", ignoreCase = true) -> {
                    OlaPixelDrainDev().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // VidStack
                url.contains("vidstack", ignoreCase = true) -> {
                    OlaVidStack().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // OlaMovies direct
                url.contains("olamovies.dad", ignoreCase = true) ||
                url.contains("space.olamovies", ignoreCase = true) -> {
                    callback.invoke(
                        com.lagradost.cloudstream3.utils.newExtractorLink(
                            "OlaMovies",
                            "OlaMovies Direct",
                            url,
                            ExtractorLinkType.VIDEO
                        )
                    )
                    true
                }
                // Generic fallback
                else -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatchExtractor failed for $url: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Helper methods (same as KatMovieHD provider)
    // ------------------------------------------------------------------

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    /**
     * Strip verbose tags from titles for cleaner display + better TMDB matching.
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
            .replace(Regex("""(?i)\s*-\s*OlaMovies.*$"""), "")
            .replace(Regex("""(?i)\s+AKA\s+"""), " AKA ")
            .replace(Regex("""(?i)\((Season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d+"""), "")
            .replace(Regex("""(?i)\bDS4K\b"""), "")
            .replace(Regex("""(?i)\bREMUX\b"""), "")
            .replace(Regex("""(?i)\bDV\b"""), "")
            .replace(Regex("""(?i)\bHDR10\+?\b"""), "")
            .replace(Regex("""(?i)\bESub\w*\b"""), "")
            .replace(Regex("""(?i)\b10bit\b"""), "")
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
            t.contains("tv series") ||
            t.contains("tv show") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(t) ||
            Regex("""\bs0\d\b""").containsMatchIn(t) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd",
            "remux", "hdr", "dolby vision", "dv", "ds4k", "imax")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    // ------------------------------------------------------------------
    // TMDB + Cinemeta metadata enrichment (best-effort)
    // ------------------------------------------------------------------

    private data class TmdbMeta(
        val title: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val rating: Score? = null,
        val genres: List<String> = emptyList(),
        val actors: List<ActorData> = emptyList(),
        val trailer: String? = null,
        val totalSeasons: Int? = null
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
        val rating: Score?,
        val airDate: String?
    )

    private data class CinemetaMeta(
        val name: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val description: String? = null,
        val year: String? = null,
        val imdbRating: String? = null,
        val genre: List<String> = emptyList(),
        val cast: List<String> = emptyList(),
        val videos: List<CinemetaVideo> = emptyList()
    )

    private data class CinemetaVideo(
        val season: Int,
        val episode: Int,
        val name: String? = null,
        val title: String? = null,
        val thumbnail: String? = null,
        val overview: String? = null,
        val released: String? = null
    )

    private suspend fun resolveTmdbId(
        imdbId: String?,
        title: String,
        isSeries: Boolean,
        yearHint: Int?
    ): Int? {
        if (!imdbId.isNullOrBlank()) {
            runCatching {
                val type = if (isSeries) "tv" else "movie"
                val json = app.get(
                    "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id",
                    timeout = 10
                ).parsedSafe<Map<String, List<Map<String, Any>>>>()
                val key = if (isSeries) "tv_results" else "movie_results"
                return json?.get(key)?.firstOrNull()?.get("id")?.let { (it as? Number)?.toInt() }
            }
        }

        // Fallback: text search
        runCatching {
            val type = if (isSeries) "tv" else "movie"
            val query = title.split(Regex("""\s+"""))
                .filter { it.lowercase() !in STOPWORDS }
                .joinToString("+")
            val url = "$TMDB_API/search/$type?api_key=$TMDB_API_KEY&query=$query" +
                    (if (yearHint != null) "&first_air_date_year=$yearHint&year=$yearHint" else "")
            val json = app.get(url, timeout = 10).parsedSafe<Map<String, Any>>()
            val results = json?.get("results") as? List<Map<String, Any>> ?: return null
            return results.firstOrNull()?.get("id")?.let { (it as? Number)?.toInt() }
        }
        return null
    }

    private suspend fun fetchTmdbDetails(id: Int, isSeries: Boolean): TmdbMeta? {
        return runCatching {
            val type = if (isSeries) "tv" else "movie"
            val json = app.get(
                "$TMDB_API/$type/$id?api_key=$TMDB_API_KEY&append_to_response=credits,videos",
                timeout = 10
            ).parsedSafe<Map<String, Any>>() ?: return null

            val posterPath = json["poster_path"] as? String
            val backdropPath = json["backdrop_path"] as? String
            val yearStr = (json["first_air_date"] ?: json["release_date"]) as? String
            val voteAvg = (json["vote_average"] as? Number)?.toDouble()
            val genres = (json["genres"] as? List<Map<String, Any>>)
                ?.mapNotNull { it["name"] as? String } ?: emptyList()
            val credits = json["credits"] as? Map<String, Any>
            val castList = (credits?.get("cast") as? List<Map<String, Any>>) ?: emptyList()
            val actors = castList.take(10).mapNotNull { c ->
                val name = c["name"] as? String ?: return@mapNotNull null
                val profilePath = c["profile_path"] as? String
                ActorData(
                    Actor(name, profilePath?.let { "$TMDB_IMG$it" })
                )
            }
            val videos = json["videos"] as? Map<String, Any>
            val trailerObj = (videos?.get("results") as? List<Map<String, Any>>)
                ?.firstOrNull { it["type"] == "Trailer" && it["site"] == "YouTube" }
            val trailerKey = trailerObj?.get("key") as? String
            val numSeasons = (json["number_of_seasons"] as? Number)?.toInt()

            TmdbMeta(
                title = (json["name"] ?: json["title"]) as? String,
                poster = posterPath?.let { "$TMDB_IMG$it" },
                backdrop = backdropPath?.let { "$TMDB_IMG$it" },
                overview = json["overview"] as? String,
                year = yearStr?.substringBefore("-")?.toIntOrNull(),
                rating = voteAvg?.let { Score.from10(it) },
                genres = genres,
                actors = actors,
                trailer = trailerKey?.let { "https://www.youtube.com/watch?v=$it" },
                totalSeasons = numSeasons
            )
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tvId: Int, seasonNumber: Int): TmdbSeason? {
        return runCatching {
            val json = app.get(
                "$TMDB_API/tv/$tvId/season/$seasonNumber?api_key=$TMDB_API_KEY",
                timeout = 10
            ).parsedSafe<Map<String, Any>>() ?: return null

            val episodes = (json["episodes"] as? List<Map<String, Any>>)
                ?.map { ep ->
                    val stillPath = ep["still_path"] as? String
                    val voteAvg = (ep["vote_average"] as? Number)?.toDouble()
                    TmdbEpisode(
                        episodeNumber = (ep["episode_number"] as? Number)?.toInt() ?: 0,
                        name = ep["name"] as? String,
                        overview = ep["overview"] as? String,
                        stillUrl = stillPath?.let { "$TMDB_IMG$it" },
                        rating = voteAvg?.let { Score.from10(it) },
                        airDate = ep["air_date"] as? String
                    )
                } ?: emptyList()

            TmdbSeason(
                seasonNumber = (json["season_number"] as? Number)?.toInt() ?: seasonNumber,
                episodes = episodes
            )
        }.getOrNull()
    }

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        return runCatching {
            val type = if (isSeries) "series" else "movie"
            val json = app.get("$CINEMETA/$type/$imdbId.json", timeout = 10)
                .parsedSafe<Map<String, Any>>() ?: return null
            val meta = json["meta"] as? Map<String, Any> ?: return null

            val videos = (meta["videos"] as? List<Map<String, Any>>)
                ?.map { v ->
                    CinemetaVideo(
                        season = (v["season"] as? Number)?.toInt() ?: 0,
                        episode = (v["episode"] as? Number)?.toInt() ?: 0,
                        name = v["name"] as? String,
                        title = v["title"] as? String,
                        thumbnail = v["thumbnail"] as? String,
                        overview = v["overview"] as? String,
                        released = v["released"] as? String
                    )
                } ?: emptyList()

            CinemetaMeta(
                name = (meta["name"] ?: meta["title"]) as? String,
                poster = meta["poster"] as? String,
                background = meta["background"] as? String,
                description = meta["description"] as? String,
                year = meta["year"] as? String,
                imdbRating = meta["imdbRating"] as? String,
                genre = (meta["genre"] as? List<String>) ?: emptyList(),
                cast = (meta["cast"] as? List<String>) ?: emptyList(),
                videos = videos
            )
        }.getOrNull()
    }
}
