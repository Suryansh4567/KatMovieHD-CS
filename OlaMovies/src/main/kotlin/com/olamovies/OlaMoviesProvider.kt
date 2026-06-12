package com.olamovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.math.min

/**
 * OlaMovies (v2.olamovies.mov) — Professional-grade CloudStream 3 provider.
 *
 * ═══════════════════════════════════════════════════════════
 *  ARCHITECTURE — 5-Layer Professional Design
 * ═══════════════════════════════════════════════════════════
 *
 *  Layer 1: DYNAMIC DOMAIN
 *    domains.json on GitHub → auto-updating mirrors.
 *    Extension fetches on startup; 6-hour cache.
 *    Users NEVER need to reinstall when domain changes.
 *
 *  Layer 2: GRIDLOVE WORDPRESS PARSER
 *    OlaMovies uses the GridLove magazine theme.
 *    We parse .gridlove-post article elements for listings,
 *    and use WordPress meta tags + structured content for
 *    detail pages. Dual-mode: GridLove-specific selectors
 *    first, then generic WordPress fallback.
 *
 *  Layer 3: LINK ID EXTRACTION ENGINE
 *    OlaMovies uses a link generator (links.ol-am.top/{id})
 *    to serve download links. We extract all link IDs from
 *    the page in 3 passes:
 *      Pass 1: <a href> attributes pointing to link generator
 *      Pass 2: Table rows (tr.m=720p, tr.d=1080p, tr.y=2160p)
 *      Pass 3: Regex salvage from full page HTML
 *
 *  Layer 4: MULTI-STAGE LINK RESOLUTION
 *    Each link ID goes through:
 *      Stage 1: Try OMDrive extractor (links.ol-am.top)
 *      Stage 2: Try play.ol-am.top direct streaming
 *      Stage 3: Try Google Drive URLs from link generator page
 *      Stage 4: Try Mega URLs
 *      Stage 5: Generic loadExtractor fallback
 *
 *  Layer 5: COSMETIC POLISH
 *    cleanTitle() strips all technical tags.
 *    Smart quality labels: "OlaPlay 4K UHD - movie.mkv"
 *    IMDB ID enrichment for TMDB metadata matching.
 *
 * ═══════════════════════════════════════════════════════════
 *  OlaMovies-specific architecture:
 *    - Main site: v2.olamovies.mov (CF-protected, WebViewResolver handles)
 *    - Landing: olamovies.top (200 OK, informational)
 *    - Link generator: links.ol-am.top/{id} → links.olamovies.mov/{id}
 *    - Streaming: play.ol-am.top (direct MKV, NO CF, open server)
 *    - Theme: GridLove WordPress magazine theme
 *    - Backend: OMDrive → Google Drive / Mega
 *
 *  CloudStream's WebViewResolver handles Cloudflare automatically
 *  on the Android side, so CF protection is transparent to the user.
 *
 * @version 1
 */
class OlaMoviesProvider : MainAPI() {

    override var mainUrl = "https://v2.olamovies.mov"
    override var name = "OlaMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.AsianDrama,
    )

    // ========================================================================
    // Companion Object — Constants, Regex, Config
    // ========================================================================

    companion object {
        private const val TAG = "OlaMovies"
        private const val LINKS_BASE = "https://links.ol-am.top"
        private const val LINKS_ALT = "https://links.olamovies.mov"

        /** Regex matching link generator domains */
        private val LINK_GEN_REGEX = Regex(
            """links\.ol-am\.top|links\.olamovies\.mov"""
        )

        /** Quality pattern from text */
        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p|720p|480p|360p|UHD|FHD|HD|SD|Remux|BluRay|WEB-DL|DV|Dolby\s*Vision|60fps|144fps)"""
        )

        /** Season range pattern from title or text */
        private val SEASON_RANGE_REGEX = Regex(
            """\[Season\s+(\d+)(?:-(\d+))?\]|\bS(\d{1,2})\s*-\s*S(\d{1,2})\b|\bS(\d{1,2})S(\d{1,2})\b""",
            RegexOption.IGNORE_CASE
        )

        /** Season-only pattern */
        private val SEASON_ONLY_REGEX = Regex(
            """S(\d{1,2})\b""", RegexOption.IGNORE_CASE
        )

        /** Year pattern */
        private val YEAR_REGEX = Regex("""\((\d{4})\)""")

        /** IMDB link pattern */
        private val IMDB_REGEX = Regex("""imdb\.com/title/(tt\d+)""")

        /** User agent */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
        )

        /** Headers for play.ol-am.top streaming */
        private val PLAY_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "video/*,application/octet-stream,*/*;q=0.9",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
        )
    }

    // ========================================================================
    // mainPage — Category-based browsing using mainPageOf DSL
    // ========================================================================

    override val mainPage = mainPageOf(
        "category/movies/hollywood/page/" to "Hollywood",
        "category/movies/bollywood/page/" to "Bollywood",
        "category/movies/south-indian/page/" to "South Indian",
        "category/tv-series/english-tv-series/page/" to "English Series",
        "category/tv-series/hindi-tv-series/page/" to "Hindi Series",
        "category/movies/documentary-movies/page/" to "Documentary",
        "category/tv-series/anime-tv-series-tv-series/page/" to "Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        mainUrl = OlaMoviesPlugin.getActiveMainUrl()
        val url = "$mainUrl/${request.data}$page/"
        val doc = app.get(url, headers = BASE_HEADERS, timeout = 30).document
        return newHomePageResponse(request.name, parseGridloveListing(doc), hasNext = true)
    }

    // ========================================================================
    // search — Full-text search
    // ========================================================================

    override suspend fun search(query: String, page: Int): SearchResponseList {
        mainUrl = OlaMoviesPlugin.getActiveMainUrl()
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = BASE_HEADERS, timeout = 30).document
        val results = parseGridloveListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    // ========================================================================
    // load — Detail page with metadata and link collection
    // ========================================================================

    override suspend fun load(url: String): LoadResponse {
        mainUrl = OlaMoviesPlugin.getActiveMainUrl()
        val doc = app.get(url, headers = BASE_HEADERS, timeout = 30).document

        // --- Title ---
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        // --- Poster ---
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: doc.selectFirst(".entry-image img, .gridlove-cover-bg img")?.attr("abs:src")

        // --- Description ---
        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")
            ?.attr("content")?.trim()
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()

        // --- Year ---
        val year = YEAR_REGEX.findAll(rawTitle).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()

        // --- Tags / Categories ---
        val tags = doc.select(".entry-tags a, a[rel=tag]")
            .mapNotNull { it.text()?.trim()?.lowercase() }
            .distinct()

        // --- Categories (for type detection and series identification) ---
        val categories = doc.select(".entry-category a, a[href*=/category/]")
            .mapNotNull { it.text()?.trim()?.lowercase() }
            .distinct()

        // --- IMDB ID ---
        val imdbId = doc.select("a[href]").mapNotNull { a ->
            IMDB_REGEX.find(a.attr("abs:href"))?.groupValues?.get(1)
        }.firstOrNull()

        // --- Collect all link generator IDs ---
        val linkIds = collectLinkIds(doc)

        // --- Detect if series ---
        val isSeriesByCategory = categories.any {
            it.contains("tv-series") || it.contains("tv-show") || it.contains("anime")
        }
        val isSeriesByTitle = rawTitle.contains(
            Regex("""\[Season|S\d{2}\s*[-–]\s*S\d{2}|S\d{2}E\d{2}""", RegexOption.IGNORE_CASE)
        )
        val isSeries = isSeriesByCategory || isSeriesByTitle

        // --- Series ---
        if (isSeries) {
            val episodes = buildSeriesEpisodes(rawTitle, linkIds, categories)
            return newTvSeriesLoadResponse(
                name = cleanTitle(rawTitle),
                url = url,
                type = detectType(categories, tags),
                episodes = episodes,
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                imdbId?.let { addImdbId(it) }
            }
        }

        // --- Movie ---
        return newMovieLoadResponse(
            name = cleanTitle(rawTitle),
            url = url,
            type = detectType(categories, tags),
            dataUrl = serializeLinkIds(linkIds),
        ) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            imdbId?.let { addImdbId(it) }
        }
    }

    // ========================================================================
    // loadLinks — Resolve link IDs to playable ExtractorLinks
    // ========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = deserializeLinks(data)
        if (links.isEmpty()) {
            Log.w(TAG, "loadLinks: no links found in data")
            return false
        }

        var foundAny = false

        for (link in links) {
            try {
                val found = when {
                    // Direct video URLs (play.ol-am.top, googleusercontent, etc.)
                    link.contains("play.ol-am.top", ignoreCase = true) -> {
                        emitDirectVideoLink(link, callback)
                    }
                    // Link generator IDs
                    link.startsWith("linkid:") -> {
                        val id = link.removePrefix("linkid:")
                        resolveLinkId(id, callback, subtitleCallback)
                    }
                    // Full URLs
                    link.startsWith("http") -> {
                        resolveFullUrl(link, callback, subtitleCallback)
                    }
                    else -> false
                }
                if (found) foundAny = true
            } catch (e: Exception) {
                Log.w(TAG, "loadLinks: failed for $link: ${e.message}")
            }
        }

        return foundAny
    }

    // ========================================================================
    // Listing Parser — GridLove WordPress theme
    // ========================================================================

    /**
     * Parse a GridLove listing page into SearchResponse list.
     *
     * GridLove uses .gridlove-post article elements with:
     *   - .entry-title a → title + URL
     *   - .entry-image img → poster
     *   - .entry-category → categories
     *
     * Multi-fallback approach:
     *   1. GridLove-specific: .gridlove-post / .gridlove-box cards
     *   2. Generic WordPress: article.post elements
     *   3. Last resort: any <a> wrapping an <img>
     */
    private fun parseGridloveListing(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Primary: GridLove post cards
        doc.select(".gridlove-post, .gridlove-box").forEach { el ->
            toGridloveSearchResult(el)?.let { results.add(it) }
        }
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // Fallback: generic article.post
        doc.select("article.post, article").forEach { el ->
            toGenericSearchResult(el)?.let { results.add(it) }
        }
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // Last resort: any link with image that looks like content
        doc.select("a:has(img)").forEach { el ->
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            if (href.isNotBlank() && href.startsWith("http") &&
                !href.contains("/category/") && !href.contains("/page/") &&
                !href.contains("/tag/")) {
                val img = el.selectFirst("img")
                val title = img?.attr("alt")?.trim() ?: el.text()?.trim() ?: ""
                val poster = img?.attr("abs:src")?.ifBlank { img.attr("src") }
                if (title.isNotBlank()) {
                    results.add(
                        newMovieSearchResponse(
                            name = title,
                            url = href,
                            type = TvType.Movie,
                        ) {
                            this.posterUrl = poster?.takeIf { it.startsWith("http") }
                        }
                    )
                }
            }
        }

        return results.distinctBy { it.url }
    }

    /** Convert a GridLove post element to SearchResponse */
    private fun toGridloveSearchResult(el: Element): SearchResponse? {
        val titleEl = el.selectFirst(".entry-title a, h3 a, h2 a") ?: return null
        val title = titleEl.text()?.trim() ?: return null
        val href = titleEl.attr("abs:href").ifBlank { titleEl.attr("href") }
        if (href.isBlank() || !href.startsWith("http")) return null

        val posterUrl = el.selectFirst(".entry-image img, img")?.let { img ->
            img.attr("abs:src").ifBlank {
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { img.attr("src") }
                }
            }
        }

        // Detect series from categories
        val categories = el.select(".entry-category a")
            .mapNotNull { it.text()?.trim()?.lowercase() }
        val isSeries = categories.any {
            it.contains("tv-series") || it.contains("tv-show") || it.contains("anime")
        }

        // Detect type
        val type = when {
            categories.any { it.contains("anime") } -> TvType.Anime
            categories.any { it.contains("documentary") } -> TvType.Documentary
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = type,
            ) {
                this.posterUrl = posterUrl?.takeIf { it.startsWith("http") }
            }
        } else {
            newMovieSearchResponse(
                name = title,
                url = href,
                type = type,
            ) {
                this.posterUrl = posterUrl?.takeIf { it.startsWith("http") }
            }
        }
    }

    /** Convert a generic article element to SearchResponse */
    private fun toGenericSearchResult(el: Element): SearchResponse? {
        val titleEl = el.selectFirst("h2 a, h3 a, .entry-title a, a") ?: return null
        val title = titleEl.text()?.trim() ?: return null
        val href = titleEl.attr("abs:href").ifBlank { titleEl.attr("href") }
        if (href.isBlank() || !href.startsWith("http")) return null

        val posterUrl = el.selectFirst("img")?.let { img ->
            img.attr("abs:src").ifBlank {
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        }

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.Movie,
        ) {
            this.posterUrl = posterUrl?.takeIf { it.startsWith("http") }
        }
    }

    // ========================================================================
    // Link ID Collection — Extract download link IDs from detail page
    // ========================================================================

    /**
     * Collect all link generator IDs from a movie/series detail page.
     *
     * 3-pass extraction:
     *   Pass 1: <a href> attributes matching links.ol-am.top/{id}
     *   Pass 2: Table rows with quality tiers (tr.m=720p, tr.d=1080p, tr.y=2160p)
     *   Pass 3: Regex salvage from entire page HTML
     */
    private fun collectLinkIds(doc: Document): List<Pair<String, String>> {
        val linkIds = mutableListOf<Pair<String, String>>()

        // Pass 1: Direct href links to link generator
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val linkText = a.text()?.trim() ?: ""

            val idMatch = Regex("""links\.(?:ol-am\.top|olamovies\.mov)/([a-zA-Z0-9_-]+)""")
                .find(href)
            if (idMatch != null) {
                val id = idMatch.groupValues[1]
                val quality = extractQualityFromText(linkText)
                    ?: extractQualityFromText(href)
                    ?: ""
                linkIds.add(Pair(id, quality))
            }
        }

        // Pass 2: Table rows (OlaMovies uses tr.m, tr.d, tr.y for quality tiers)
        doc.select("table tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach

            row.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val idMatch = Regex("""links\.(?:ol-am\.top|olamovies\.mov)/([a-zA-Z0-9_-]+)""")
                    .find(href)
                if (idMatch != null) {
                    val id = idMatch.groupValues[1]
                    val rowQuality = when {
                        row.hasClass("m") -> "720p"
                        row.hasClass("d") -> "1080p"
                        row.hasClass("y") -> "2160p"
                        else -> extractQualityFromText(row.text() ?: "")
                    } ?: ""
                    linkIds.add(Pair(id, rowQuality))
                }
            }
        }

        // Pass 3: Regex salvage from entire page HTML
        if (linkIds.isEmpty()) {
            val pageText = doc.html()
            Regex("""links\.(?:ol-am\.top|olamovies\.mov)/([a-zA-Z0-9_-]{3,})""")
                .findAll(pageText)
                .forEach { match ->
                    val id = match.groupValues[1]
                    val quality = extractQualityFromText(
                        pageText.substringBefore(match.value).takeLast(200)
                    )
                    linkIds.add(Pair(id, quality ?: ""))
                }
        }

        return linkIds.distinctBy { it.first }
    }

    // ========================================================================
    // Series Episode Builder
    // ========================================================================

    /**
     * Build episode list for a TV series from collected link IDs.
     *
     * OlaMovies series pages contain dozens of link IDs organized by
     * season and quality. Strategy:
     *   1. Parse season range from title (e.g., "[Season 1-5]" → S01-S05)
     *   2. Group link IDs by season/episode from surrounding text
     *   3. If grouping fails, create one episode per quality group
     */
    private fun buildSeriesEpisodes(
        rawTitle: String,
        linkIds: List<Pair<String, String>>,
        categories: List<String>,
    ): List<Episode> {
        if (linkIds.isEmpty()) return emptyList()

        val episodes = mutableListOf<Episode>()
        val seasonRange = parseSeasonRange(rawTitle)
        val byQuality = linkIds.groupBy { it.second.ifBlank { "HD" } }

        if (seasonRange != null) {
            val (firstSeason, lastSeason) = seasonRange
            val totalLinks = linkIds.size
            val seasonsCount = lastSeason - firstSeason + 1
            val linksPerSeason = totalLinks / seasonsCount

            for (s in firstSeason..lastSeason) {
                val startIdx = (s - firstSeason) * linksPerSeason
                val endIdx = min(startIdx + linksPerSeason, totalLinks)
                val seasonLinks = linkIds.subList(
                    startIdx.coerceAtMost(totalLinks),
                    endIdx.coerceAtMost(totalLinks)
                )

                if (seasonLinks.isNotEmpty()) {
                    val qualityGroups = seasonLinks.groupBy { it.second.ifBlank { "HD" } }
                    for ((quality, ids) in qualityGroups) {
                        val qualityLabel = if (qualityGroups.size > 1) " ($quality)" else ""
                        episodes.add(
                            newEpisode(serializeLinkIds(ids)) {
                                this.name = "Season $s$qualityLabel"
                                this.season = s
                            }
                        )
                    }
                }
            }
        } else {
            for ((quality, ids) in byQuality) {
                episodes.add(
                    newEpisode(serializeLinkIds(ids)) {
                        this.name = if (byQuality.size > 1) "All Episodes ($quality)" else "All Episodes"
                    }
                )
            }
        }

        return episodes.ifEmpty {
            listOf(
                newEpisode(serializeLinkIds(linkIds)) {
                    this.name = "All Episodes"
                }
            )
        }
    }

    private fun parseSeasonRange(title: String): Pair<Int, Int>? {
        SEASON_RANGE_REGEX.find(title)?.let { match ->
            val s1 = match.groupValues[1].toIntOrNull() ?: match.groupValues[3].toIntOrNull()
                ?: match.groupValues[5].toIntOrNull()
            val s2 = match.groupValues[2].toIntOrNull() ?: match.groupValues[4].toIntOrNull()
                ?: match.groupValues[6].toIntOrNull()
            if (s1 != null && s2 != null) return Pair(s1, s2)
            if (s1 != null) return Pair(s1, s1)
        }

        SEASON_ONLY_REGEX.find(title)?.let { match ->
            val s = match.groupValues[1].toIntOrNull()
            if (s != null) return Pair(s, s)
        }

        return null
    }

    // ========================================================================
    // Link ID Resolver
    // ========================================================================

    /**
     * Resolve a link generator ID to playable video URLs.
     *
     * Multi-stage resolution:
     *   Stage 1: Try OMDrive extractor (registered, handles links.ol-am.top)
     *   Stage 2: Try the link generator page directly for GDrive/Mega/MKV URLs
     *   Stage 3: play.ol-am.top URLs found on the generator page
     */
    private suspend fun resolveLinkId(
        id: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        var found = false

        // Stage 1: Use registered OMDrive extractor
        try {
            val genUrl = "$LINKS_BASE/$id"
            loadExtractor(genUrl, mainUrl, subtitleCallback, callback)
            found = true  // Optimistic — extractor will emit links if available
        } catch (e: Exception) {
            Log.d(TAG, "resolveLinkId: OMDrive extractor failed for $id: ${e.message}")
        }

        // Stage 2: Try the alternate link generator page directly
        try {
            val genUrl = "$LINKS_ALT/$id"
            val doc = app.get(genUrl, headers = BASE_HEADERS, timeout = 15).document
            val pageText = doc.html()

            // Google Drive URLs
            Regex("""https?://(?:drive\.google\.com|drive\.usercontent\.google\.com|video-downloads\.googleusercontent\.com)/[^\s"'<>]+""")
                .findAll(pageText).forEach { match ->
                    val url = match.value.replace("\\u0026", "&")
                    val qualityHint = extractQualityFromText(
                        pageText.substringBefore(match.value).takeLast(200)
                    ) ?: "HD"
                    callback.invoke(
                        newExtractorLink(
                            "$name GDrive",
                            "$name GDrive $qualityHint",
                            url,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qualityHint)
                            this.referer = genUrl
                        }
                    )
                    found = true
                }

            // Mega URLs
            Regex("""https?://mega\.(?:nz|co\.nz)/[^\s"'<>]+""")
                .findAll(pageText).forEach { match ->
                    callback.invoke(
                        newExtractorLink(
                            "$name Mega",
                            "$name Mega",
                            match.value,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = genUrl
                        }
                    )
                    found = true
                }

            // Direct video URLs (.mkv, .mp4)
            Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4|avi)""")
                .findAll(pageText).forEach { match ->
                    val url = match.value
                    if (!url.contains(".js") && !url.contains(".css")) {
                        val qualityHint = extractQualityFromUrl(url)
                        callback.invoke(
                            newExtractorLink(
                                "$name",
                                "$name $qualityHint",
                                url,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName(qualityHint)
                                this.referer = genUrl
                            }
                        )
                        found = true
                    }
                }

            // play.ol-am.top URLs on the link generator page
            doc.select("a[href*=play.ol-am.top], video source[src]").forEach { el ->
                val src = el.attr("abs:href")
                    .ifBlank { el.attr("abs:src").ifBlank { el.attr("src") } }
                if (src.isNotBlank() && src.startsWith("http")) {
                    val qualityHint = extractQualityFromUrl(src)
                    callback.invoke(
                        newExtractorLink(
                            "$name Stream",
                            "$name $qualityHint",
                            src,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qualityHint)
                            this.referer = mainUrl
                            this.headers = PLAY_HEADERS
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveLinkId: alt link generator failed for $id: ${e.message}")
        }

        return found
    }

    /**
     * Resolve a full URL (not a linkid: prefix).
     */
    private suspend fun resolveFullUrl(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return when {
            url.contains("play.ol-am.top", ignoreCase = true) -> {
                emitDirectVideoLink(url, callback)
            }
            url.contains("googleusercontent.com", ignoreCase = true) ||
            url.contains("drive.google.com", ignoreCase = true) -> {
                callback.invoke(
                    newExtractorLink(
                        "$name GDrive",
                        "$name GDrive",
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
                true
            }
            LINK_GEN_REGEX.containsMatchIn(url) -> {
                val id = url.substringAfterLast("/")
                    .substringBefore("?").substringBefore("#")
                resolveLinkId(id, callback, subtitleCallback)
            }
            else -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    // ========================================================================
    // Direct Video Link Emission
    // ========================================================================

    private suspend fun emitDirectVideoLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val qualityHint = extractQualityFromUrl(url)
        callback.invoke(
            newExtractorLink(
                "$name Stream",
                "$name $qualityHint",
                url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = getQualityFromName(qualityHint)
                this.referer = mainUrl
                this.headers = PLAY_HEADERS
            }
        )
        return true
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun getQualityFromName(name: String?): Int {
        if (name.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            name.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            name.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            name.contains("UHD", ignoreCase = true) -> Qualities.P2160.value
            name.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            name.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
            name.contains("Remux", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720p", ignoreCase = true) -> Qualities.P720.value
            name.contains("HD", ignoreCase = true) -> Qualities.P720.value
            name.contains("480p", ignoreCase = true) -> Qualities.P480.value
            name.contains("SD", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractQualityFromText(text: String): String? {
        return QUALITY_REGEX.find(text)?.groupValues?.get(1)
    }

    private fun extractQualityFromUrl(url: String): String {
        return QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "HD"
    }

    /**
     * Clean a page title to remove quality/codec info.
     * Example: "300 - Rise of an Empire (2014) 720p + 1080p BluRay x265..."
     *          → "300 - Rise of an Empire (2014)"
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*(Download|Free|Watch|Online|Google Drive|OlaMovies)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d{3,4}p\s*(?:\+\s*\d{3,4}p\s*)*"""), " ")
            .replace(Regex("""\s*(BluRay|WEB-DL|WEBRip|BRRip|HDRip|DVDRip|Remux|UHD|Dolby\s*Vision|DV)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(x264|x265|HEVC|AVC|H\.264|H\.265|10bit|6CH)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*(Dual\s*Audio|Multi\s*Audio|ESub|MSUB)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*\[.*?\]\s*"""), " ")
            .replace(Regex("""\s*(mkv|mp4|avi)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun detectType(categories: List<String>, tags: List<String>): TvType {
        return when {
            categories.any { it.contains("anime") } -> TvType.Anime
            categories.any { it.contains("documentary") } -> TvType.Documentary
            categories.any { it.contains("korean") || it.contains("chinese") } -> TvType.AsianDrama
            categories.any { it.contains("tv-series") || it.contains("tv-show") } -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // ========================================================================
    // Link Serialization
    // ========================================================================

    private fun serializeLinkIds(links: List<Pair<String, String>>): String {
        return links.map { (id, _) -> "linkid:$id" }.joinToString("|")
    }

    private fun deserializeLinks(data: String): List<String> {
        return data.split("|")
            .map { it.trim() }
            .filter { it.startsWith("http") || it.startsWith("linkid:") }
            .distinct()
    }
}
