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
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * KatMovie4K provider — sister of KatMovieHDProvider.
 *
 * Site (katmovie4k.mov) is the same publisher's 4K UHD release portal.
 * Markup is byte-for-byte identical to katmoviehd: a WordPress install
 * with the same `<li id="post-NNNN">` search card layout and per-Episode
 * <h2>/<h3> heading bodies on detail pages. So the parsing logic is the
 * same shape as KatMovieHDProvider, with three deliberate differences:
 *
 *   1. Mirror host whitelist is wider — pages link out to ziddiflix.com
 *      (kmhd's 4K-only redirector), gdflix.dad subdomains, driveleech.org,
 *      vifix.site, burydibase, kmhd.net. These are added to LINK_HOST_REGEX.
 *
 *   2. Quality detection prioritises 2160p / 4K / HDR / Dolby Vision over
 *      720p / 480p, and the Stage-3 label heuristic recognises the extra
 *      4K-specific labels ("2160p HDR DV", "2160p SDR", "1080p x264",
 *      "REMUX HDR/DV", etc).
 *
 *   3. Main page sections are 4K-themed (4K Movies, 4K Series, Dolby
 *      Vision, Marvel, Disney+) rather than KatMovieHD's Netflix/Prime/
 *      K-Drama mix.
 *
 * Everything else (TMDB title-similarity guard, year-proximity boost,
 * IMDB-id Path 1, 3-stage episode discovery, kmhd directlink skip,
 * quality-aware Stage-3 naming, v9-style newline-separated episode data,
 * Cinemeta fallback, all the same defensive try/catch wrapping) is
 * intentionally a verbatim port from KatMovieHDProvider v19, because
 * those fixes apply equally well to katmovie4k.
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

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    companion object {
        private const val TAG = "KatMovie4K"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        // Same stopwords as KatMovieHDProvider — see titleSimilarity() docs.
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with",
            "el", "la", "los", "las", "un", "una", "y", "de", "del",
            "ka", "ki", "ke", "se", "aur"
        )

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /**
         * Hosts we recognise as "known mirror providers" on katmovie4k.
         * Superset of KatMovieHD's list because 4K releases use a few
         * extra redirectors that don't appear on the HD site:
         *   - ziddiflix.com   (kmhd's 4K-only redirector, seen on Terrifier 3, Heart Eyes pages)
         *   - driveleech.org  (mirror for HDR releases)
         *   - vifix.site      (gdflix wrapper for 4K)
         *   - gdflix.dad      (new TLD for gdflix subdomains)
         *   - burydibase      (ad-gate, explicitly filtered in collectMirrorLinks)
         */
        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // KMHD-native (KmhdExtractor handles these)
                    """links\.kmhd\.[a-z]+|kmhd\.net|kmhd\.eu|gd\.kmhd|""" +
                    // KatMovie4K-specific redirectors
                    """ziddiflix\.com|driveleech\.org|vifix\.site|""" +
                    // GDFlix family
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot|gdmirror|""" +
                    // HubCloud / Hubdrive / Katdrive family
                    """hubcloud\.[a-z]+|hubcdn|hubstream|hubdrive|katdrive|""" +
                    // Generic cloud / file-share hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|""" +
                    // Streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // Hindi-dub / 4K-specific upload mirrors
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|gdtot|techkit|""" +
                    // KatMovie* upload mirrors
                    """katmovie\.|katdrive|kmhd""" +
                    """)"""
        )

        /** Same blacklist as KatMovieHDProvider — IMDb/social/CDN junk. */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """katmovie4k|katmoviehd|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#""" +
                    """)"""
        )

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" etc. */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4". */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Header tags that can hold "Episode N" / "Season N" / quality labels. */
        private val LABEL_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b")

        /** Negative phrases that should never be treated as episode markers. */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link"
        )
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    /**
     * Home-page sections tuned to KatMovie4K's category structure.
     * Verified against live site nav as of 2026-06: the 4K-focused
     * categories are /category/4k-movies/, /category/4k-series/, the
     * Dolby Vision / HDR tag pages, and a few studio-specific ones
     * (Marvel, Disney Plus). Hindi-dubbed slice has its own page too.
     */
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
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        // Same 3-stage layout fallback as KatMovieHD — the markup is
        // literally the same WordPress theme so this works verbatim.
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
            .ifBlank { selectFirst("div.post-thumb img")?.attr("alt") ?: "" }
            .ifBlank { return null }

        val poster = select("div.post-thumb img").firstNotNullOfOrNull { img ->
            val u = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            u.takeIf {
                it.isNotBlank() &&
                    !it.contains("label", ignoreCase = true) &&
                    !it.contains("overlay", ignoreCase = true) &&
                    !it.contains("Unofficial", ignoreCase = true)
            }
        } ?: selectFirst("div.post-thumb img")?.let {
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

    // ------------------------------------------------------------------
    // load() - per-title page (same logic as KatMovieHDProvider v19)
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
    // Episode discovery — 2-stage pipeline (no /pack/ JSON on 4K site;
    // every series page uses the per-Episode header layout directly,
    // verified against Daredevil: Born Again S01 page).
    // ------------------------------------------------------------------

    private fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val container = doc.selectFirst("article, .entry-content") ?: return emptyList()

        // Stage 1: per-Episode headers ("Episode 03" + quality links).
        // Confirmed live on Daredevil: Born Again S01 — bold "Episode 01"
        // followed by a heading containing "[2160p HDR DV] || [2160p SDR]"
        // anchors.
        val perHeader = parseEpisodeHeaderLayout(container, defaultSeason)
        if (perHeader.isNotEmpty()) {
            Log.d(TAG, "Stage1 (header layout): ${perHeader.size} episodes")
            return buildEpisodes(perHeader, tmdbSeason, cine)
        }

        // Stage 2 (degraded): quality-aware flat fallback. KatMovie4K's
        // single-episode "Movie" pages aren't series at all, so this only
        // triggers if a series page ever ships without per-episode markup.
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
     * Variant of collectMirrorLinks that also captures a human label.
     * 4K-specific labels: "2160p HDR DV", "2160p SDR", "1080p x264", "REMUX".
     */
    private fun collectMirrorLinksWithLabels(container: Element): List<Pair<String?, String>> {
        val cleanUrls: Set<String> = LinkedHashSet(collectMirrorLinks(container))
        if (cleanUrls.isEmpty()) return emptyList()

        // 4K-tuned quality regex — recognises the extra HDR/DV/REMUX/SDR
        // variants that KatMovie4K uses but KatMovieHD doesn't.
        val qualityRegex = Regex(
            """(?i)\b(2160p\s*(?:hdr\s*dv|hdr|sdr|remux)?|4k\s*(?:uhd|hdr|sdr)?|""" +
                """1080p\s*hevc|1080p\s*x264|1080p|720p|480p|hdr|dolby\s*vision|""" +
                """remux|hdr\s*10\+?|watch\s*online|play\s*online|trailer|""" +
                """subtitle|pack|esubs?)\b"""
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
                val lower = q.lowercase()
                when {
                    lower == "watch online" || lower == "play online" -> "Watch Online"
                    lower.contains("2160p") && lower.contains("hdr") && lower.contains("dv") -> "2160p HDR DV"
                    lower.contains("2160p") && lower.contains("hdr") -> "2160p HDR"
                    lower.contains("2160p") && lower.contains("sdr") -> "2160p SDR"
                    lower.contains("2160p") && lower.contains("remux") -> "2160p REMUX"
                    lower.contains("2160p") -> "2160p"
                    lower.contains("4k") -> "4K"
                    lower.contains("1080p") && lower.contains("hevc") -> "1080p HEVC"
                    lower.contains("1080p") && lower.contains("x264") -> "1080p x264"
                    lower.contains("dolby") -> "Dolby Vision"
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
     * Same two-pass collector as KatMovieHDProvider v19, including the
     * directlink skip filter. KatMovie4K pages have similar decorative
     * "DOWNLOAD LINKS" headings that bounce to nowhere useful.
     */
    private fun collectMirrorLinks(container: Element): List<String> {
        val all = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> h.startsWith("http", ignoreCase = true) } }
            .distinct()
            .filter { url ->
                // Anchored full-URL match — substring would kill legit files like
                // "links.kmhd.eu/file/Good_directlinkABC". See KatMovieHDProvider docs.
                !url.matches(Regex(
                    """(?i)^https?://(?:www\.)?kmhd\.net/(directlink|home|index)/?$"""
                )) && !url.matches(Regex(
                    """(?i)^https?://(?:www\.)?burydibase\.com/.*$"""
                ))
                // burydibase.com is a katmovie4k ad-gate redirector — never serves
                // a real file. The "DOWNLOAD LINKS" anchor on Daredevil S01 page
                // points to it. Filter it out so it doesn't become a dead Source.
            }

        val strict = all.filter { LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    !url.contains(mainUrl, ignoreCase = true)
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict whitelist matched 0, permissive fallback: ${permissive.size}")
        }
        return permissive
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
    // loadLinks — verbatim port from KatMovieHDProvider v19
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: raw data length=${data.length}, preview='${data.take(120)}'")

        val urls: List<String> = buildList {
            if (data.trimStart().startsWith("[")) {
                runCatching {
                    tryParseJson<List<String>>(data)?.let { addAll(it) }
                }
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
        Log.d(TAG, "loadLinks: dispatching ${urls.size} URL(s):\n${urls.joinToString("\n") { "  - $it" }}")

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
        while (u.isNotEmpty() && u.last() in setOf(')', ']', '}', '.', ',', ';', '!', '?', '"', '\'')) {
            u = u.dropLast(1)
        }
        val hashIdx = u.indexOf('#')
        if (hashIdx > 0) u = u.substring(0, hashIdx)
        return u
    }

    /**
     * Per-URL dispatch with explicit routing for KatMovie4K-specific hosts.
     *
     * v3: Previously we relied solely on CloudStream's loadExtractor() for
     * prefix-based dispatch to registered ExtractorApi subclasses.  That
     * works when the extractor implementation is correct, but gives no
     * diagnostic when a host breaks (e.g. vifix.site's JS challenge →
     * parked domain, driveleech.org going dark).  Explicit routing here
     * means we can add per-host fallback logic and always get clear logs.
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
                // KMHD native — our KmhdExtractor handles both the current
                // SvelteKit /file/<id> format and legacy /archives/<id> pages.
                Regex("""(?i)(links\.kmhd\.|kmhd\.eu/archives/)""").containsMatchIn(url) -> {
                    KmhdExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // vifix.site — v3 FIX: JS challenge → parked domain.  Extract
                // gdflix file id and route straight to new18.gdflix.net.
                url.contains("vifix.site", ignoreCase = true) -> {
                    Vifix().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // driveleech.org / .pro / .net — intermittently down.
                // Use the Driveleech extractor which has id-extraction fallback.
                Regex("""(?i)driveleech\.(org|pro|net)""").containsMatchIn(url) -> {
                    Driveleech().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // new3 / new4 .gdflix.dad → 302 chain to new18.gdflix.net
                url.contains("new3.gdflix.dad", ignoreCase = true) -> {
                    GDFlixDad3().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                url.contains("new4.gdflix.dad", ignoreCase = true) -> {
                    GDFlixDad4().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // ziddiflix.com → 302 chain to new18.gdflix.net
                url.contains("ziddiflix.com", ignoreCase = true) -> {
                    Ziddiflix().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // gdlink.dev → 302 to gdflix.dev → new18.gdflix.net
                url.contains("gdlink.dev", ignoreCase = true) -> {
                    GDLinkDev().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Everything else: try CloudStream's stock registry (HubCloud,
                // GDFlix, Pixeldrain, Streamtape, etc.).
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


    // ------------------------------------------------------------------
    // Metadata: TMDB (identical to KatMovieHDProvider v19)
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
        isSeries: Boolean,
        yearHint: Int? = null
    ): Int? {
        // Path 1: IMDB-id lookup (gold standard).
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

        // Path 2: text search with title-similarity + year-proximity guard.
        // Same scoring algorithm as KatMovieHDProvider v19 — see the
        // titleSimilarity() docs there for full rationale.
        return runCatching {
            val queryTitle = cleanedTitle.substringBefore("(").trim()
            val q = URLEncoder.encode(queryTitle, "UTF-8")
            val json = JSONObject(
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
                val titleSim = candidates.maxOfOrNull { titleSimilarity(queryTitle, it) } ?: 0.0

                val tmdbYear = (item.optString("release_date").ifBlank { item.optString("first_air_date") })
                    .takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
                val yearBoost = if (yearHint != null && tmdbYear != null) {
                    val diff = kotlin.math.abs(tmdbYear - yearHint)
                    when {
                        diff == 0 -> 0.15
                        diff == 1 -> 0.05
                        diff > 3  -> -0.20
                        else      -> 0.0
                    }
                } else 0.0

                val score = titleSim + yearBoost
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }

            if (bestScore >= 0.45 && bestId > 0) {
                Log.d(TAG, "TMDB match for '$queryTitle' -> id=$bestId score=${"%.2f".format(bestScore)}")
                bestId
            } else {
                Log.w(TAG, "TMDB no confident match for '$queryTitle' (best=${"%.2f".format(bestScore)})")
                null
            }
        }.getOrNull()
    }

    /**
     * Hybrid token-set + Jaccard similarity. Same as KatMovieHDProvider v19.
     * 4K release titles tend to be VERY verbose ("Terrifier 3 (2024) 4K
     * Ultra HD Blu-Ray 2160p UHD [Hindi Dubbed & English (5.1 DDP)] Dual
     * Audio | [Dolby Vision / HDR10 & HDR10+ / SDR ]"), so the coverage
     * blend is particularly important here — pure Jaccard would never
     * match such titles against TMDB's clean "Terrifier 3".
     */
    private fun titleSimilarity(a: String, b: String): Double {
        fun tokens(s: String) = s.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in STOPWORDS }
            .toSet()
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
    // Metadata: Cinemeta (identical to KatMovieHDProvider v19)
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
    // Helpers — same as KatMovieHDProvider plus 4K-aware quality detection
    // ------------------------------------------------------------------

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    /**
     * Strip the verbose tags KatMovie4K jams into article titles. The
     * 4K site is even more verbose than KatMovieHD (titles routinely
     * cross 150 chars), and has a few extra noise patterns unique to
     * the 4K UHD release world: "Dolby Vision / HDR10 & HDR10+ / SDR",
     * "x265 HEVC 10BIT", "REMUX", "Blu-Ray 2160p UHD". We strip all
     * of those plus everything KatMovieHDProvider's cleanTitle strips,
     * so TMDB queries get a clean "Terrifier 3" instead of the full
     * 200-char descriptor.
     */
    private fun cleanTitle(raw: String): String {
        val withoutDecorators = raw
            .replace(Regex("""(?i)\s*\|.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?]"""), "")
            .replace(Regex("""(?i)\s*\(DD\s*\d.*?\)"""), "")
            .replace(Regex("""(?i)\s*\(ORG\)"""), "")
            // 4K-specific cruft
            .replace(Regex("""(?i)\b4K\s*Ultra\s*HD.*$"""), "")
            .replace(Regex("""(?i)\bUltra\s*HD.*$"""), "")
            .replace(Regex("""(?i)\b2160p.*$"""), "")
            .replace(Regex("""(?i)\bUHD.*$"""), "")
            .replace(Regex("""(?i)\bx265\b.*$"""), "")
            .replace(Regex("""(?i)\bHEVC\b.*$"""), "")
            .replace(Regex("""(?i)\bREMUX\b.*$"""), "")
            .replace(Regex("""(?i)\bDolby\s*Vision.*$"""), "")
            .replace(Regex("""(?i)\bHDR\d*\+?.*$"""), "")
            .replace(Regex("""(?i)\bSDR\b.*$"""), "")
            .replace(Regex("""(?i)\bBlu[- ]?Ray.*$"""), "")
            // Shared with KatMovieHD
            .replace(Regex("""(?i)\bHindi Dubbed.*$"""), "")
            .replace(Regex("""(?i)\bDual Audio.*$"""), "")
            .replace(Regex("""(?i)\bWEB[-\s]?DL.*$"""), "")
            .replace(Regex("""(?i)\bFull Movie.*$"""), "")
            .replace(Regex("""(?i)\bAll Episodes.*$"""), "")
            .replace(Regex("""(?i)\s*-\s*KatMovie4K.*$"""), "")
            .replace(Regex("""(?i)\s*—\s*KatMovie4K.*$"""), "")
            .replace(Regex("""(?i)\((Season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '|', ':', '—')
            .trim()
        return withoutDecorators.ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val t = title.lowercase()
        return when {
            t.contains("season") ||
            t.contains("episode") ||
            t.contains("series") ||
            t.contains("tv show") ||
            t.contains("4k series") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(t) ||
            Regex("""\bs0\d\b""").containsMatchIn(t) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    /**
     * Quality detection tuned to 4K releases. 2160p / 4K / HDR / Dolby
     * Vision are prioritised before falling back to 1080p, etc.
     */
    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf(
            "2160p", "4k", "uhd", "hdr", "dolby vision",
            "1080p", "720p", "480p",
            "bluray", "web-dl", "webrip", "remux",
            "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd"
        )
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) {
                // Map 4K-specific tokens to the closest SearchQuality enum.
                val mapped = when (tok) {
                    "uhd", "dolby vision" -> "2160p"
                    "hdr" -> "4k"
                    else -> tok
                }
                return getQualityFromString(mapped)
            }
        }
        return null
    }
}
