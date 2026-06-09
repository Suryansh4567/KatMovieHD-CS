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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * KMMovies (kmmovies.lol) CloudStream 3 provider.
 *
 * Site structure (verified June 2026):
 *   - WordPress + custom theme (GeneratePress-based)
 *   - Movie pages: hero section + `.downloads-section` with
 *     `.download-category.encoded` / `.download-category.webdl`
 *     each containing `.dl-btn` links
 *   - Series pages: `.download-category.tv-series` with
 *     `.season-block` elements, each having tabs:
 *       - "Episode Wise": links to `episodes.magiclinks.lol/series/SLUG-QUALITY/`
 *       - "Combined": links to `w1.magiclinks.lol/XXX-2/`
 *       - "Zip": links to `w1.magiclinks.lol/XXX-2/`
 *   - Episode pages (episodes.magiclinks.lol): per-episode links to
 *     `w1.skydrop.sbs/download.php?id=XXX`
 *   - Redirector chain:
 *       1. `w1.magiclinks.lol/XXX-2/` → links to:
 *          - `z1.kmphotos.cv/online.php?file=XXX.mkv` (stream)
 *          - `w1.skydrop.sbs/download.php?id=XXX` (download)
 *          - `z1.kmphotos.cv/download99.php?file=XXX.mkv` (direct download)
 *          - `t.me/kmsenderbot?start=XXX` (Telegram bot)
 *       2. `w1.skydrop.sbs/api.php?id=XXX` → JSON with Google Drive / R2 direct URLs
 *       3. `z1.kmphotos.cv/online.php?file=XXX` → 302 redirect to R2 stream URL
 *       4. `z1.kmphotos.cv/download99.php?file=XXX` → signed download URL
 *
 * Data format for loadLinks():
 *   - Movie data: JSON string `{"url":"pageUrl"}` or just the page URL
 *   - Episode data: JSON string `{"pageUrl":"...", "seasonIdx":0, "epIdx":0}`
 *   In loadLinks we re-fetch the page, find the download buttons,
 *   resolve redirectors, and return ExtractorLinks.
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
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    companion object {
        private const val TAG = "KMMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /** Known redirector / link-shortener domains used by KMMovies. */
        private val REDIRECTOR_DOMAINS = setOf(
            "magiclinks.lol", "skydrop.sbs", "kmphotos.cv"
        )

        /** Domains that are NEVER stream sources (social, images, etc.). */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#""" +
                    """)"""
        )

        /** Quality label regex for parsing download buttons. */
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
        val doc = fetchDoc(url)
        return newHomePageResponse(request.name, parseListing(doc), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = fetchDoc(url)
        val results = parseListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    /**
     * Fetch a Document with proper headers for Cloudflare bypass.
     * KMMovies uses Cloudflare; we send full browser headers to
     * avoid 403 challenges on individual post pages.
     */
    private suspend fun fetchDoc(url: String): Document {
        return app.get(url, headers = headers, timeout = 30).document
    }

    /**
     * Parse listing pages (homepage, category, search).
     *
     * KMMovies uses `.movie-card` articles with:
     *   - `a[href]` wrapping the card
     *   - `img.poster` or `img` with poster src
     *   - `h3.movie-title` for title
     *   - `.meta-row span` for type (Movie/Series) and genre
     *   - `.badge-left` for quality badge
     */
    private fun parseListing(doc: Document): List<SearchResponse> {
        // Primary: .movie-card elements (current theme)
        val cards = doc.select("article.movie-card")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        // Fallback: any anchor wrapping an img that points to a post
        val anchors = doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
        if (anchors.isNotEmpty()) return anchors

        return emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("abs:href").ifBlank { return null }
        // Skip non-post URLs
        if (href.contains("/category/", ignoreCase = true) ||
            href.contains("/page/", ignoreCase = true) ||
            href.contains("/genre/", ignoreCase = true) ||
            href.trimEnd('/') == mainUrl.trimEnd('/')) return null

        val titleEl = selectFirst("h3.movie-title")
        val rawTitle = titleEl?.text()?.trim()?.ifBlank { null }
            ?: anchor.attr("title").ifBlank { null }
            ?: return null

        val poster = selectFirst("img.poster, img[src*=kmphotos], img[src*=tmdb], div.poster-wrap img")
            ?.let { it.attr("abs:src").ifBlank { it.attr("abs:data-src") }.ifBlank { null } }
            ?: selectFirst("img")?.let {
                it.attr("abs:src").ifBlank { it.attr("abs:data-src") }.ifBlank { null }
            }

        val qualityBadge = selectFirst(".badge-left")?.text()?.trim()
        val typeText = selectFirst(".meta-row span")?.text()?.trim()?.lowercase()
        val isSeries = typeText == "series" || guessIsSeries(rawTitle)

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            if (isSeries) TvType.TvSeries else TvType.Movie
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle, qualityBadge)
        }
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("abs:href").ifBlank { return null }
        if (!href.contains("kmmovies", ignoreCase = true)) return null
        val bad = listOf(
            "/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-",
            "/about", "/contact", "/how-to", "/join-", "/genre/",
            "/trending", "/browse", "/year/", "/director/", "/writer/"
        )
        if (bad.any { href.contains(it, ignoreCase = true) }) return null
        if (href.trimEnd('/') == mainUrl.trimEnd('/')) return null

        val rawTitle = attr("title").ifBlank { text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.attr("abs:src")?.ifBlank { null }
            ?: img?.attr("abs:data-src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            if (guessIsSeries(rawTitle)) TvType.TvSeries else TvType.Movie
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
        val doc = fetchDoc(url)

        // ---- Extract metadata from the page ----

        // Title from hero section
        val rawTitle = doc.selectFirst("h1.hero-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()

        // Poster from hero section
        val sitePoster = doc.selectFirst("img.hero-poster")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img")?.attr("abs:src")

        // Backdrop
        val siteBackdrop = doc.selectFirst("div.hero-backdrop")
            ?.attr("style")
            ?.let { Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1) }

        // Description
        val sitePlot = doc.selectFirst("div.hero-description")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Rating
        val siteRating = doc.selectFirst("span.meta-pill.rating-star")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value }
            ?.toFloatOrNull()?.let { Score.from10(it.toString()) }

        // Year from hero meta pills (exclude rating-star pill)
        val siteYear = doc.selectFirst("span.meta-pill:not(.rating-star):not(.lang-pill)")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // IMDb link and ID
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        // Type detection: check "About This Movie/Series" heading and meta
        val aboutHeading = doc.selectFirst("h2.about-title")?.text()?.trim().orEmpty()
        val typeHighlight = doc.selectFirst("span.about-highlight-value")?.text()?.trim().orEmpty()
        val isSeries = aboutHeading.contains("series", ignoreCase = true) ||
                typeHighlight.equals("series", ignoreCase = true) ||
                guessIsSeries(rawTitle)

        // Season from title
        val titleSeason = Regex("""(?i)\bS(\d{1,2})\b""").find(rawTitle)?.groupValues?.get(1)
            ?.toIntOrNull() ?: 1

        // Genre tags from about section
        val siteTags = doc.select("div.about-meta-box a.meta-link").map { it.text().trim() }
            .filter { it.length in 2..40 }

        // Trailer URL
        val trailerUrl = doc.selectFirst("a.open-trailer")?.attr("data-trailer-url")

        val epCountText = doc.select("div.about-meta-box div.about-meta-value")
            .map { it.text().trim() }
            .firstOrNull { it.contains("Episode", ignoreCase = true) }
        val episodeCount = epCountText?.let {
            Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        // ---- Enrich with TMDB / Cinemeta ----
        val cleanedTitle = cleanTitle(rawTitle)
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
        val backdrop = tmdbMeta?.backdrop ?: cine?.background ?: siteBackdrop ?: poster
        val plot = tmdbMeta?.overview ?: cine?.description ?: sitePlot
        val year = tmdbMeta?.year
            ?: cine?.year?.substringBefore("–")?.toIntOrNull()
            ?: siteYear
        val rating = tmdbMeta?.rating ?: cine?.imdbRating?.let { Score.from10(it) } ?: siteRating
        val tags = (tmdbMeta?.genres ?: cine?.genre ?: emptyList())
            .ifEmpty { siteTags }.distinct()
        val actorData = tmdbMeta?.actors ?: emptyList()
        val cineActors = cine?.cast ?: emptyList()
        val trailer = tmdbMeta?.trailer ?: trailerUrl
        val totalSeasons = tmdbMeta?.totalSeasons

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries imdb=$imdbId titleSeason=$titleSeason")

        if (isSeries) {
            val episodes = discoverEpisodes(doc, titleSeason, episodeCount ?: 0, tmdbSeason, cine)
            Log.d(TAG, "load() discovered ${episodes.size} episodes")

            if (episodes.isEmpty()) {
                Log.w(TAG, "0 episodes discovered, emitting as movie-style links")
                val links = collectAllDownloadLinks(doc)
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
            // Movie: data is the page URL (we'll re-fetch in loadLinks)
            val links = collectAllDownloadLinks(doc)
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

    // ==================================================================
    // Episode discovery
    // ==================================================================

    /**
     * Discover episodes from a series page.
     *
     * KMMovies series pages have a `.download-category.tv-series` section
     * with `.season-block` elements. Each season block has:
     *   - `.season-block-title` with text like "Season 1 (10 eps)"
     *   - Tab content divs:
     *     - `data-type="episodes-N"`: per-quality links to
     *       `episodes.magiclinks.lol/series/SLUG-QUALITY/`
     *     - `data-type="combined-N"`: combined pack links
     *     - `data-type="zip-N"`: zip links
     *
     * Strategy:
     *   1. Parse season blocks to get season numbers and episode counts
     *   2. Get episode-wise URLs from the episodes tab
     *   3. Fetch each episodes page to get per-episode download links
     *   4. Create Episode objects with all quality links per episode
     */
    private suspend fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        fallbackEpCount: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val seasonBlocks = doc.select("div.season-block")
        if (seasonBlocks.isEmpty()) {
            Log.w(TAG, "No season blocks found on series page")
            return emptyList()
        }

        val allEpisodes = mutableListOf<Episode>()
        var seasonIdx = 0

        for (block in seasonBlocks) {
            // Parse season number from header
            val headerText = block.selectFirst("div.season-block-header, span.season-block-title")
                ?.text()?.trim() ?: ""
            val seasonNum = Regex("""(?i)Season\s*(\d+)""").find(headerText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: (defaultSeason + seasonIdx)

            // Parse episode count from "(N eps)"
            val epCount = Regex("""\((\d+)\s*eps\)""").find(headerText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: fallbackEpCount

            if (epCount <= 0) {
                seasonIdx++
                continue
            }

            // Get episode-wise URLs from the episodes tab
            val episodesTab = block.selectFirst("div.type-content[data-type^=episodes-]")
            val episodeUrls = episodesTab?.select("a.dl-btn")?.mapNotNull { btn ->
                val href = btn.attr("abs:href").ifBlank { null } ?: return@mapNotNull null
                val quality = btn.selectFirst("span.dl-res")?.text()?.trim() ?: ""
                quality to href
            } ?: emptyList()

            if (episodeUrls.isEmpty()) {
                // No episode-wise links, try combined links as fallback
                val combinedTab = block.selectFirst("div.type-content[data-type^=combined-]")
                val combinedLinks = combinedTab?.select("a.dl-btn")?.mapNotNull { btn ->
                    val href = btn.attr("abs:href").ifBlank { null } ?: return@mapNotNull null
                    val quality = btn.selectFirst("span.dl-res")?.text()?.trim() ?: ""
                    val size = btn.selectFirst("span.dl-size")?.text()?.trim() ?: ""
                    Triple(quality, size, href)
                } ?: emptyList()

                if (combinedLinks.isNotEmpty()) {
                    // Create pseudo-episodes from combined links
                    for ((idx, triple) in combinedLinks.withIndex()) {
                        val (quality, size, href) = triple
                        val epName = if (quality.isNotBlank()) "$quality Pack" else "Pack ${idx + 1}"
                        val tmdbEp = tmdbSeason
                            ?.takeIf { it.seasonNumber == seasonNum }
                            ?.episodes?.firstOrNull { it.episodeNumber == idx + 1 }
                        allEpisodes.add(newEpisode(href) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = idx + 1
                            this.posterUrl = tmdbEp?.stillUrl
                        })
                    }
                }
                seasonIdx++
                continue
            }

            // Fetch each episodes page to get per-episode links
            // We need at least one quality's episode page to discover episode URLs
            val episodeLinksByEp = mutableMapOf<Int, MutableList<Pair<String, String>>>()

            for ((quality, epPageUrl) in episodeUrls) {
                try {
                    val epLinks = fetchEpisodePageLinks(epPageUrl, epCount)
                    for ((epNum, link) in epLinks) {
                        episodeLinksByEp.getOrPut(epNum) { mutableListOf() }
                            .add("$quality: $link" to link)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch episode page $epPageUrl: ${e.message}")
                }
            }

            // If we got per-episode links, create proper episodes
            if (episodeLinksByEp.isNotEmpty()) {
                for (epNum in 1..epCount) {
                    val links = episodeLinksByEp[epNum] ?: continue
                    // Join all quality links with newlines for loadLinks
                    val data = links.map { it.second }.joinToString("\n")

                    val tmdbEp = tmdbSeason
                        ?.takeIf { it.seasonNumber == seasonNum }
                        ?.episodes?.firstOrNull { it.episodeNumber == epNum }
                    val cineEp = cine?.videos
                        ?.firstOrNull { it.season == seasonNum && it.episode == epNum }

                    allEpisodes.add(newEpisode(data) {
                        this.name = tmdbEp?.name ?: cineEp?.name ?: "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                        this.description = tmdbEp?.overview ?: cineEp?.overview
                        this.score = tmdbEp?.rating
                        (tmdbEp?.airDate ?: cineEp?.released)?.let {
                            addDate(it.substringBefore("T"))
                        }
                    })
                }
            } else {
                // Fallback: create episodes with just the episode page URLs
                // Each episode's data = the first quality's episodes page URL
                for (epNum in 1..epCount) {
                    val firstEpUrl = episodeUrls.firstOrNull()?.second ?: continue
                    val tmdbEp = tmdbSeason
                        ?.takeIf { it.seasonNumber == seasonNum }
                        ?.episodes?.firstOrNull { it.episodeNumber == epNum }

                    allEpisodes.add(newEpisode(firstEpUrl) {
                        this.name = tmdbEp?.name ?: "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = tmdbEp?.stillUrl
                    })
                }
            }

            seasonIdx++
        }

        return allEpisodes
    }

    /**
     * Fetch an episode listing page (episodes.magiclinks.lol)
     * and extract per-episode download links.
     *
     * These pages are simple WordPress pages with a list of links,
     * one per episode. The body text looks like:
     *   "Teach You a Lesson S01 480p Episode 1 Download Episode 2 Download ..."
     *
     * Links go to `w1.skydrop.sbs/download.php?id=XXX`
     */
    private suspend fun fetchEpisodePageLinks(
        url: String,
        expectedEpCount: Int
    ): List<Pair<Int, String>> {
        val doc = app.get(url, headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        ), timeout = 30).document

        // Find all links in the body
        val links = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            // Only keep skydrop or kmphotos links (actual download links)
            if (href.contains("skydrop.sbs", ignoreCase = true) ||
                href.contains("kmphotos.cv", ignoreCase = true) ||
                href.contains("magiclinks.lol", ignoreCase = true)) {
                href
            } else null
        }.distinct()

        if (links.isEmpty()) return emptyList()

        // Map links to episode numbers.
        // The episode page lists links in order: Ep1, Ep2, ..., EpN
        // Each link is for one episode.
        return links.take(expectedEpCount).mapIndexed { idx, link ->
            (idx + 1) to link
        }
    }

    // ==================================================================
    // Link collection for movies
    // ==================================================================

    /**
     * Collect all download links from a movie/series page.
     *
     * For movies: collects from `.dl-btn` in `.download-category` sections
     * (encoded and webdl).
     * For series: collects from combined/zip tabs as fallback.
     *
     * Returns a newline-separated string of URLs.
     */
    private fun collectAllDownloadLinks(doc: Document): String {
        val links = mutableListOf<String>()

        // Movie-style download categories (encoded, webdl)
        val categories = doc.select("div.download-category")
        for (cat in categories) {
            val catTitle = cat.selectFirst("span.category-title")?.text()?.trim() ?: ""
            val isTvSeries = cat.hasClass("tv-series")

            if (!isTvSeries) {
                // Movie download buttons
                for (btn in cat.select("a.dl-btn")) {
                    val href = btn.attr("abs:href").ifBlank { continue }
                    if (href.startsWith("http", ignoreCase = true)) {
                        links.add(href)
                    }
                }
            }
        }

        Log.d(TAG, "collectAllDownloadLinks(): ${links.size} links")
        return links.joinToString("\n")
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

        val urls = data.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")

        var foundAny = false

        for (url in urls) {
            try {
                val resolved = resolveRedirector(url)
                if (resolved != null) {
                    for (link in resolved) {
                        callback(link)
                        foundAny = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve $url: ${e.message}")
            }
        }

        // Also try loadExtractor as a fallback for any URLs we didn't handle
        if (!foundAny) {
            for (url in urls) {
                try {
                    val success = loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                    if (success) foundAny = true
                } catch (_: Exception) {}
            }
        }

        return foundAny
    }

    /**
     * Resolve a redirector URL chain to get actual playable video links.
     *
     * Chain:
     *   1. `w1.magiclinks.lol/XXX-2/` → page with skydrop/kmphotos links
     *   2. `episodes.magiclinks.lol/series/SLUG/` → page with per-ep links
     *   3. `w1.skydrop.sbs/download.php?id=XXX` → API resolves to GDrive/R2
     *   4. `z1.kmphotos.cv/online.php?file=XXX` → 302 redirect to R2 stream
     *   5. `z1.kmphotos.cv/download99.php?file=XXX` → signed download URL
     */
    private suspend fun resolveRedirector(url: String): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()

        when {
            // Direct skydrop download page
            url.contains("skydrop.sbs", ignoreCase = true) &&
                url.contains("download.php", ignoreCase = true) -> {
                resolveSkydrop(url, links)
            }

            // Skydrop API URL
            url.contains("skydrop.sbs", ignoreCase = true) &&
                url.contains("api.php", ignoreCase = true) -> {
                resolveSkydropApi(url, links)
            }

            // Magiclinks redirector page
            url.contains("magiclinks.lol", ignoreCase = true) -> {
                resolveMagiclinks(url, links)
            }

            // kmphotos stream/download
            url.contains("kmphotos.cv", ignoreCase = true) -> {
                resolveKmphotos(url, links)
            }

            // Direct video URL (R2, Google Drive, etc.)
            url.contains("r2.dev", ignoreCase = true) ||
            url.contains("googleusercontent.com", ignoreCase = true) ||
            url.contains("drive.google.com", ignoreCase = true) -> {
                val quality = guessQualityFromUrl(url)
                links.add(
                    ExtractorLink(
                        source = name,
                        name = "$name $quality",
                        url = url,
                        referer = "$mainUrl/",
                        quality = getQualityInt(quality),
                        isM3u8 = url.endsWith(".m3u8", ignoreCase = true)
                    )
                )
            }

            else -> {
                // Try to use loadExtractor for known hosts
                return null // signal to caller to try loadExtractor
            }
        }

        return links.ifEmpty { null }
    }

    /**
     * Resolve a magiclinks.lol redirector page.
     * These pages contain links to:
     *   - z1.kmphotos.cv/online.php?file=XXX (stream)
     *   - w1.skydrop.sbs/download.php?id=XXX (download)
     *   - z1.kmphotos.cv/download99.php?file=XXX (direct download)
     */
    private suspend fun resolveMagiclinks(url: String, links: MutableList<ExtractorLink>) {
        try {
            val doc = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ), timeout = 30).document

            val allLinks = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                href.takeIf {
                    it.contains("skydrop.sbs", ignoreCase = true) ||
                    it.contains("kmphotos.cv", ignoreCase = true)
                }
            }.distinct()

            for (link in allLinks) {
                try {
                    resolveRedirector(link)?.let { links.addAll(it) }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve magiclinks page $url: ${e.message}")
        }
    }

    /**
     * Resolve a skydrop.sbs/download.php?id=XXX URL.
     * This calls the skydrop API at api.php?id=XXX to get
     * the actual download/stream URL.
     */
    private suspend fun resolveSkydrop(url: String, links: MutableList<ExtractorLink>) {
        try {
            // Extract the id parameter
            val id = Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1) ?: return
            val apiUrl = if (url.contains("w1.skydrop", ignoreCase = true)) {
                "https://w1.skydrop.sbs/api.php?id=$id"
            } else {
                // Try to construct API URL from the download URL
                val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                    ?: return
                "$base/api.php?id=$id"
            }
            resolveSkydropApi(apiUrl, links)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve skydrop URL $url: ${e.message}")
        }
    }

    /**
     * Call the skydrop API and extract the video link.
     * Response format: {"success":true,"link":"https://...","download_url":"https://...","type":"video"}
     */
    private suspend fun resolveSkydropApi(apiUrl: String, links: MutableList<ExtractorLink>) {
        try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json"
            ), timeout = 15).parsedSafe<SkydropResponse>()

            if (response?.success == true) {
                val videoLink = response.link
                if (!videoLink.isNullOrBlank()) {
                    val quality = guessQualityFromUrl(videoLink)
                    links.add(
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

                // Also try the download URL (it's another API endpoint that
                // redirects to the actual file — follow it to get the direct URL)
                val downloadUrl = response.download_url
                if (!downloadUrl.isNullOrBlank() && downloadUrl != videoLink) {
                    try {
                        val dlResp = app.get(downloadUrl, headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*"
                        ), timeout = 15, allowRedirects = false)
                        val dlLocation = dlResp.headers["location"]
                        if (!dlLocation.isNullOrBlank()) {
                            val quality = guessQualityFromUrl(dlLocation)
                            links.add(
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
     * Resolve a kmphotos.cv URL (online.php or download99.php).
     *
     * online.php → 302 redirect to a player page with the R2 video URL
     * download99.php → 302 redirect to a signed download URL
     */
    private suspend fun resolveKmphotos(url: String, links: MutableList<ExtractorLink>) {
        try {
            when {
                url.contains("online.php", ignoreCase = true) -> {
                    // online.php → 302 redirect to nf/index.php?videoUrl=ENCODED_R2_URL
                    // We must NOT follow all redirects (the final page is a
                    // player, not a video). Instead extract videoUrl param.
                    val resp = app.get(url, headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,*/*;q=0.8"
                    ), timeout = 15, allowRedirects = false)

                    val redirectUrl = resp.headers["location"] ?: ""
                    val videoUrl = Regex("""videoUrl=([^&]+)""").find(redirectUrl)
                        ?.groupValues?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    if (videoUrl != null) {
                        val quality = guessQualityFromUrl(url)
                        links.add(
                            ExtractorLink(
                                source = name,
                                name = "$name Stream $quality",
                                url = videoUrl,
                                referer = "$mainUrl/",
                                quality = getQualityInt(quality),
                                isM3u8 = false
                            )
                        )
                    }
                }

                url.contains("download99.php", ignoreCase = true) -> {
                    // Follow the redirect to get the signed download URL
                    val resp = app.get(url, headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*"
                    ), timeout = 15, allowRedirects = false)

                    val location = resp.headers["location"]
                    if (!location.isNullOrBlank()) {
                        // The redirect goes to the R2 file with signature
                        val quality = guessQualityFromUrl(url)
                        links.add(
                            ExtractorLink(
                                source = name,
                                name = "$name Download $quality",
                                url = location,
                                referer = "$mainUrl/",
                                quality = getQualityInt(quality),
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve kmphotos URL $url: ${e.message}")
        }
    }

    // ==================================================================
    // Helper utilities
    // ==================================================================

    data class SkydropResponse(
        @JsonProperty("success") val success: Boolean? = false,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("download_url") val download_url: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    /** Guess if a title refers to a TV series based on common patterns. */
    private fun guessIsSeries(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("season") || t.contains("s01") || t.contains("s02") ||
                t.contains("s03") || Regex("""\bS\d{2}\b""").find(t) != null ||
                t.contains("episode", ignoreCase = true) ||
                t.contains("tv series", ignoreCase = true) ||
                t.contains("kdrama", ignoreCase = true)
    }

    /** Clean up site title to remove quality tags and suffixes. */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        // Remove quality tags
        t = Regex("""(?i)\b(480p|720p|1080p|2160p|4k|10bit|hevc|x264|x265|av1|web-dl|webdl|hdr|dv|hdts|hdtc|dual.audio|multi.audio|esubs?)\b""").replace(t, "")
        // Remove site watermark
        t = Regex("""(?i)\bkmmovies(\.com|\.lol|\.life|\.icu)?\b""").replace(t, "")
        // Clean up brackets and extra spaces
        t = Regex("""\[\s*\]""").replace(t, "")
        t = Regex("""\(\s*\)""").replace(t, "")
        t = Regex("""\s{2,}""").replace(t, " ")
        return t.trim().trim('-', ':', '|', '.')
    }

    /** Detect search quality from title and badge text. */
    private fun detectSearchQuality(title: String, badge: String? = null): SearchQuality? {
        val combined = (title + " " + (badge ?: "")).lowercase()
        return when {
            combined.contains("4k") || combined.contains("2160p") -> SearchQuality.FourK
            combined.contains("1080p") -> SearchQuality.HD
            combined.contains("720p") -> SearchQuality.HD
            combined.contains("480p") -> SearchQuality.SD
            combined.contains("hdts") || combined.contains("cam") || combined.contains("ts") -> SearchQuality.Cam
            else -> null
        }
    }

    /** Guess quality from a URL or filename. */
    private fun guessQualityFromUrl(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("2160p") || u.contains("4k") -> "2160p"
            u.contains("1080p") -> "1080p"
            u.contains("720p") -> "720p"
            u.contains("480p") -> "480p"
            else -> "HD"
        }
    }

    /** Convert quality string to CloudStream3 standard quality int. */
    private fun getQualityInt(quality: String): Int {
        return when (quality) {
            "2160p" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            else -> 720  // Default HD
        }
    }

    // ==================================================================
    // TMDB integration
    // ==================================================================

    data class TmdbMeta(
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

    data class TmdbSeason(
        val seasonNumber: Int,
        val episodes: List<TmdbEpisode>
    )

    data class TmdbEpisode(
        val episodeNumber: Int,
        val name: String?,
        val overview: String?,
        val stillUrl: String?,
        val rating: Score?,
        val airDate: String?
    )

    private suspend fun resolveTmdbId(
        imdbId: String?,
        title: String,
        isSeries: Boolean,
        yearHint: Int?
    ): Int? {
        // Try IMDb ID first
        if (!imdbId.isNullOrBlank()) {
            try {
                val type = if (isSeries) "tv" else "movie"
                val url = "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
                val resp = app.get(url, timeout = 10).parsedSafe<TmdbFindResponse>()
                val results = if (isSeries) resp?.tv_results else resp?.movie_results
                val id = results?.firstOrNull()?.id
                if (id != null) return id
            } catch (_: Exception) {}
        }

        // Fallback: text search
        try {
            val type = if (isSeries) "tv" else "movie"
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = "$TMDB_API/search/$type?api_key=$TMDB_API_KEY&query=$encoded" +
                    (if (yearHint != null) "&year=$yearHint" else "")
            val resp = app.get(url, timeout = 10).parsedSafe<TmdbSearchResponse>()
            return resp?.results?.firstOrNull()?.id
        } catch (_: Exception) {}

        return null
    }

    private suspend fun fetchTmdbDetails(id: Int, isSeries: Boolean): TmdbMeta? {
        return try {
            val type = if (isSeries) "tv" else "movie"
            val url = "$TMDB_API/$type/$id?api_key=$TMDB_API_KEY&append_to_response=credits,videos"
            val resp = app.get(url, timeout = 10).parsedSafe<TmdbDetailResponse>() ?: return null

            val poster = resp.poster_path?.let { "${TMDB_IMG}$it" }
            val backdrop = resp.backdrop_path?.let { "${TMDB_IMG}$it" }
            val genres = resp.genres?.mapNotNull { it.name } ?: emptyList()
            val year = (resp.release_date ?: resp.first_air_date)
                ?.substringBefore("-")?.toIntOrNull()
            val rating = resp.vote_average?.let { Score.from10(it.toString()) }

            val actors = resp.credits?.cast?.take(10)?.mapNotNull { cast ->
                val actorPoster = cast.profile_path?.let { "${TMDB_IMG}$it" }
                ActorData(
                    Actor(cast.name ?: return@mapNotNull null),
                    posterUrl = actorPoster
                )
            } ?: emptyList()

            val trailer = resp.videos?.results
                ?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
                ?.key?.let { "https://www.youtube.com/watch?v=$it" }

            TmdbMeta(
                title = resp.title ?: resp.name,
                poster = poster,
                backdrop = backdrop,
                overview = resp.overview,
                year = year,
                rating = rating,
                genres = genres,
                actors = actors,
                trailer = trailer,
                totalSeasons = resp.number_of_seasons
            )
        } catch (_: Exception) { null }
    }

    private suspend fun fetchTmdbSeason(tvId: Int, seasonNum: Int): TmdbSeason? {
        return try {
            val url = "$TMDB_API/tv/$tvId/season/$seasonNum?api_key=$TMDB_API_KEY"
            val resp = app.get(url, timeout = 10).parsedSafe<TmdbSeasonResponse>() ?: return null
            TmdbSeason(
                seasonNumber = resp.season_number ?: seasonNum,
                episodes = resp.episodes?.map { ep ->
                    TmdbEpisode(
                        episodeNumber = ep.episode_number ?: 0,
                        name = ep.name,
                        overview = ep.overview,
                        stillUrl = ep.still_path?.let { "${TMDB_IMG}$it" },
                        rating = ep.vote_average?.let { Score.from10(it.toString()) },
                        airDate = ep.air_date
                    )
                } ?: emptyList()
            )
        } catch (_: Exception) { null }
    }

    // Cinemeta integration for supplementary metadata
    data class CinemetaMeta(
        val name: String?,
        val poster: String?,
        val background: String?,
        val description: String?,
        val year: String?,
        val imdbRating: String?,
        val genre: List<String>,
        val cast: List<String>,
        val videos: List<CinemetaVideo>?
    )

    data class CinemetaVideo(
        val season: Int?,
        val episode: Int?,
        val name: String?,
        val title: String?,
        val thumbnail: String?,
        val overview: String?,
        val released: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        return try {
            val type = if (isSeries) "series" else "movie"
            val url = "$CINEMETA/$type/$imdbId.json"
            val resp = app.get(url, timeout = 10).parsedSafe<CinemetaResponse>()
            val meta = resp?.meta ?: return null

            CinemetaMeta(
                name = meta.name,
                poster = meta.poster,
                background = meta.background,
                description = meta.description,
                year = meta.year,
                imdbRating = meta.imdbRating,
                genre = meta.genre?.split(",")?.map { it.trim() } ?: emptyList(),
                cast = meta.cast?.split(",")?.map { it.trim() } ?: emptyList(),
                videos = meta.videos?.map { v ->
                    CinemetaVideo(
                        season = v.season,
                        episode = v.episode,
                        name = v.name,
                        title = v.title,
                        thumbnail = v.thumbnail,
                        overview = v.overview,
                        released = v.released
                    )
                }
            )
        } catch (_: Exception) { null }
    }

    // ---- Jackson response data classes ----

    data class TmdbFindResponse(
        @JsonProperty("movie_results") val movie_results: List<TmdbIdResult>? = null,
        @JsonProperty("tv_results") val tv_results: List<TmdbIdResult>? = null
    )

    data class TmdbIdResult(
        @JsonProperty("id") val id: Int? = null
    )

    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbIdResult>? = null
    )

    data class TmdbDetailResponse(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
        @JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("vote_average") val vote_average: Float? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("number_of_seasons") val number_of_seasons: Int? = null
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null
    )

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("profile_path") val profile_path: String? = null
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo>? = null
    )

    data class TmdbVideo(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("key") val key: String? = null
    )

    data class TmdbSeasonResponse(
        @JsonProperty("season_number") val season_number: Int? = null,
        @JsonProperty("episodes") val episodes: List<TmdbEpisodeResponse>? = null
    )

    data class TmdbEpisodeResponse(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val still_path: String? = null,
        @JsonProperty("vote_average") val vote_average: Float? = null,
        @JsonProperty("air_date") val air_date: String? = null
    )

    data class CinemetaResponse(
        @JsonProperty("meta") val meta: CinemetaInnerMeta? = null
    )

    data class CinemetaInnerMeta(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cast") val cast: String? = null,
        @JsonProperty("videos") val videos: List<CinemetaInnerVideo>? = null
    )

    data class CinemetaInnerVideo(
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("released") val released: String? = null
    )
}
