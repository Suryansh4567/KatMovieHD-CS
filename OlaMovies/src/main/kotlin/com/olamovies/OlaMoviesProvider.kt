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

/**
 * OlaMovies (v2.olamovies.mov) — Professional-grade CloudStream 3 provider.
 *
 * ═══════════════════════════════════════════════════════════
 *  ARCHITECTURE — 6-Layer Professional Design (v2 rewrite)
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
 *  Layer 3: wp-block-button LINK EXTRACTION
 *    Download links are in div.wp-block-button > a elements.
 *    Link text carries quality+size info: "720p [1.39gb]".
 *    Movie pages: numeric IDs (20871). Series: alphanumeric.
 *    NON-download links (tutorial, FAQ, telegram) are filtered.
 *
 *  Layer 4: DOM-ORDER SERIES EPISODE BUILDER
 *    Series pages use p.has-text-align-center > strong paragraphs
 *    as section labels (e.g., "The Boys S01 720p BluRay x265...").
 *    We walk the DOM in document order, tracking which label
 *    precedes each wp-block-buttons group. Episode numbers come
 *    from link text ("episode 01"). Season from label text.
 *
 *  Layer 5: WebViewResolver LINK RESOLUTION
 *    Link generator (links.olamovies.mov) is CF-protected with
 *    countdown + shortener. We use WebViewResolver to bypass CF,
 *    then follow the shortener chain to reach GDrive/Mega URLs.
 *    Fallback: try direct app.get for pages that work without CF.
 *
 *  Layer 6: COSMETIC POLISH
 *    cleanTitle() strips all technical tags.
 *    Smart quality labels: "OlaMovies 1080p BluRay"
 *    IMDB ID enrichment for TMDB metadata matching.
 *
 * ═══════════════════════════════════════════════════════════
 *  OlaMovies-specific architecture:
 *    - Main site: v2.olamovies.mov (CF-protected, WebViewResolver handles)
 *    - Landing: olamovies.top / olamovies.dad (200 OK, informational)
 *    - Link redirector: links.ol-am.top/{id} → 301 → links.olamovies.mov/{id}
 *    - Link generator: links.olamovies.mov/{id} (Next.js RSC, CF + countdown)
 *    - play.ol-am.top: ONLY serves a tutorial video, NOT actual content
 *    - Theme: GridLove WordPress magazine theme
 *    - Backend: OMDrive → shortener → Google Drive / Mega
 *
 *  CloudStream's WebViewResolver handles Cloudflare automatically
 *  on the Android side, so CF protection is transparent to the user.
 *
 * @version 2 — Complete rewrite based on verified site structure
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
            """links\.(?:ol-am\.top|olamovies\.mov)"""
        )

        /**
         * Regex to extract link ID from a link generator URL.
         * Matches both numeric IDs (20871) and alphanumeric (MCeGujF2W9zvHaR).
         */
        private val LINK_ID_REGEX = Regex(
            """links\.(?:ol-am\.top|olamovies\.mov)/([a-zA-Z0-9_-]+)"""
        )

        /** Quality pattern from text */
        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p|720p|480p|360p|UHD|FHD|HD|SD|Remux|BluRay|WEB-DL|DV|Dolby\s*Vision|60fps|144fps)"""
        )

        /** Season pattern from label text — e.g., "S01", "S02" */
        private val SEASON_FROM_LABEL_REGEX = Regex(
            """\bS(\d{1,2})\b""", RegexOption.IGNORE_CASE
        )

        /** Season range pattern from title — e.g., "[Season 1-5]", "S01-S05" */
        private val SEASON_RANGE_REGEX = Regex(
            """\[Season\s+(\d+)(?:-(\d+))?\]|\bS(\d{1,2})\s*[-–]\s*S(\d{1,2})\b|\bS(\d{1,2})S(\d{1,2})\b""",
            RegexOption.IGNORE_CASE
        )

        /** Episode number from link text — e.g., "episode 01", "Episode 8" */
        private val EPISODE_NUM_REGEX = Regex(
            """episode\s+(\d{1,3})""", RegexOption.IGNORE_CASE
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

        /**
         * URLs that must be EXCLUDED from link collection.
         * These are tutorial/FAQ/navigation links, NOT download links.
         */
        private val JUNK_URL_PATTERNS = listOf(
            "play.ol-am.top",       // Tutorial video, NOT actual content
            "m.ol-am.top",          // Domain redirect bookmark
            "olamovies.top",        // Landing page
            "olamovies.dad",        // Landing page alt
            "liteapks.com",         // 1DM+ download manager
            "lrepacks.net",         // IDM download manager
            "jdownloader.org",      // JDownloader2
            "tinyurl.com",          // URL shortener (FAQ)
            "telegram.me",          // Telegram channel
            "t.me/",                // Telegram alt
            "/recent-comments",     // WordPress internal
            "/wp-",                 // WordPress admin/resources
        )

        /**
         * Link text patterns that indicate NON-download links.
         * These are navigation/tutorial links that happen to match
         * the link generator URL pattern.
         */
        private val JUNK_TEXT_PATTERNS = listOf(
            "tutorial",
            "how to",
            "guide",
            "faq",
            "bookmark",
            "alternative domain",
            "download manager",
            "1dm",
            "idm",
            "jdownloader",
            "premium",
            "more",
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

        // --- Collect download links (movie) or structured sections (series) ---
        val isSeriesByCategory = categories.any {
            it.contains("tv-series") || it.contains("tv-show") || it.contains("anime")
        }
        val isSeriesByTitle = rawTitle.contains(
            Regex("""\[Season|S\d{2}\s*[-–]\s*S\d{2}|S\d{2}E\d{2}""", RegexOption.IGNORE_CASE)
        )
        val isSeries = isSeriesByCategory || isSeriesByTitle

        val type = detectType(categories, tags)

        if (isSeries) {
            val episodes = buildSeriesEpisodes(doc, rawTitle)
            return newTvSeriesLoadResponse(
                name = cleanTitle(rawTitle),
                url = url,
                type = type,
                episodes = episodes,
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                imdbId?.let { addImdbId(it) }
            }
        }

        // --- Movie: collect link IDs with quality labels ---
        val linkIds = collectMovieLinkIds(doc)

        return newMovieLoadResponse(
            name = cleanTitle(rawTitle),
            url = url,
            type = type,
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
                    // Link generator IDs — resolve via WebViewResolver/extractor
                    link.startsWith("linkid:") -> {
                        val id = link.removePrefix("linkid:")
                        resolveLinkId(id, callback, subtitleCallback)
                    }
                    // Full URLs — delegate
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
     * Check if a URL is a junk/non-download link that should be excluded.
     *
     * Filters out:
     *   - play.ol-am.top (tutorial video)
     *   - m.ol-am.top (domain redirect)
     *   - olamovies.top / olamovies.dad (landing pages)
     *   - Download manager links (1DM, IDM, JDownloader)
     *   - Telegram, FAQ, shortener links
     *   - WordPress internal links
     */
    private fun isJunkLink(url: String, linkText: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerText = linkText.lowercase()

        // Check URL patterns
        for (pattern in JUNK_URL_PATTERNS) {
            if (lowerUrl.contains(pattern.lowercase())) return true
        }

        // Check link text patterns
        for (pattern in JUNK_TEXT_PATTERNS) {
            if (lowerText.contains(pattern.lowercase())) return true
        }

        return false
    }

    /**
     * Collect movie download link IDs with quality labels.
     *
     * OlaMovies movie pages use WordPress Gutenberg blocks:
     *   div.wp-block-button > a.wp-block-button__link → download button
     *   Link text: "720p [1.39gb]", "1080p [2.61gb]", "1080p remux [24.2gb]"
     *   URL: https://links.ol-am.top/{numeric_id}
     *
     * Also picks up links from generic <a> tags pointing to the link generator,
     * but filters out junk/tutorial links.
     */
    private fun collectMovieLinkIds(doc: Document): List<LinkEntry> {
        val linkIds = mutableListOf<LinkEntry>()

        // Primary: WordPress Gutenberg block buttons
        doc.select("div.wp-block-button a[href], a.wp-block-button__link[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val linkText = a.text()?.trim() ?: ""

            if (isJunkLink(href, linkText)) return@forEach

            val idMatch = LINK_ID_REGEX.find(href)
            if (idMatch != null) {
                val id = idMatch.groupValues[1]
                val quality = extractQualityFromText(linkText)
                    ?: extractQualityFromText(href)
                    ?: ""
                val label = buildQualityLabel(linkText, quality)
                linkIds.add(LinkEntry(id = id, quality = quality, label = label, linkText = linkText))
            }
        }

        // If Gutenberg blocks found, use those exclusively
        if (linkIds.isNotEmpty()) {
            return linkIds.distinctBy { it.id }
        }

        // Fallback: generic <a> tags pointing to link generator
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val linkText = a.text()?.trim() ?: ""

            if (isJunkLink(href, linkText)) return@forEach

            val idMatch = LINK_ID_REGEX.find(href)
            if (idMatch != null) {
                val id = idMatch.groupValues[1]
                val quality = extractQualityFromText(linkText)
                    ?: extractQualityFromText(href)
                    ?: ""
                val label = buildQualityLabel(linkText, quality)
                linkIds.add(LinkEntry(id = id, quality = quality, label = label, linkText = linkText))
            }
        }

        return linkIds.distinctBy { it.id }
    }

    /**
     * Build a display-quality label from link text.
     * E.g., "720p [1.39gb]" → "720p", "1080p {60FPS} [9.43gb]" → "1080p 60FPS"
     */
    private fun buildQualityLabel(linkText: String, quality: String): String {
        if (quality.isBlank()) return linkText
        // Extract special tags like {60FPS}, {3d}, "remux", "multi"
        val extras = mutableListOf<String>()
        if (linkText.contains("remux", ignoreCase = true)) extras.add("Remux")
        if (linkText.contains("{60fps}", ignoreCase = true) ||
            linkText.contains("60fps", ignoreCase = true)) extras.add("60FPS")
        if (linkText.contains("{3d}", ignoreCase = true)) extras.add("3D")
        if (linkText.contains("multi", ignoreCase = true)) extras.add("Multi")
        if (linkText.contains("x264", ignoreCase = true) &&
            !linkText.contains("x265", ignoreCase = true)) extras.add("x264")

        return if (extras.isNotEmpty()) "$quality ${extras.joinToString(" ")}" else quality
    }

    // ========================================================================
    // Series Episode Builder — DOM-order walking
    // ========================================================================

    /**
     * Represents a section label + its associated download links.
     * In OlaMovies series pages, each quality/season group has:
     *   1. A <p class="has-text-align-center"><strong>Label Text</strong></p>
     *   2. A div.wp-block-buttons containing episode links
     */
    private data class SectionGroup(
        val labelText: String,
        val season: Int?,
        val quality: String,
        val links: MutableList<LinkEntry> = mutableListOf()
    )

    /**
     * Build episode list for a TV series by walking the DOM in document order.
     *
     * OlaMovies series pages structure (verified from live HTML):
     * ```
     * <p class="has-text-align-center"><strong>The Boys S01 720p BluRay x265...</strong></p>
     * <div class="wp-block-buttons">
     *   <div class="wp-block-button"><a href="links.ol-am.top/xxx">episode 01</a></div>
     *   <div class="wp-block-button"><a href="links.ol-am.top/xxx">episode 02</a></div>
     *   ...
     *   <div class="wp-block-button"><a href="links.ol-am.top/xxx">720p zip [5.03gb]</a></div>
     * </div>
     * <p class="has-text-align-center"><strong>The Boys S01 1080p BluRay x265...</strong></p>
     * <div class="wp-block-buttons">
     *   ...
     * </div>
     * ```
     *
     * Strategy:
     *   1. Walk .entry-content children in DOM order
     *   2. Track current section label when we encounter <p> labels
     *   3. When we encounter a wp-block-buttons group, assign links to current section
     *   4. Group episodes by (season, episode_number) across quality variants
     *   5. Create Episode objects with multiple quality links
     */
    private fun buildSeriesEpisodes(doc: Document, rawTitle: String): List<Episode> {
        val contentEl = doc.selectFirst(".entry-content")
            ?: doc.selectFirst("article")
            ?: return emptyList()

        // --- Phase 1: Collect sections by walking DOM ---
        val sections = mutableListOf<SectionGroup>()
        var currentLabel = ""

        for (child in contentEl.children()) {
            // Check for section label paragraph
            val isLabelParagraph = child.tagName() == "p" &&
                (child.hasClass("has-text-align-center") ||
                 child.selectFirst("strong") != null) &&
                child.text()?.trim()?.isNotBlank() == true &&
                child.text().length > 10  // Labels are long descriptive strings

            if (isLabelParagraph) {
                currentLabel = child.text().trim()
                continue
            }

            // Check for download button group
            val isButtonGroup = child.hasClass("wp-block-buttons") ||
                child.selectFirst("div.wp-block-buttons") != null

            if (isButtonGroup && currentLabel.isNotBlank()) {
                val season = SEASON_FROM_LABEL_REGEX.find(currentLabel)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val quality = extractQualityFromText(currentLabel) ?: "HD"

                val section = SectionGroup(
                    labelText = currentLabel,
                    season = season,
                    quality = quality
                )

                // Extract links from this button group
                child.select("div.wp-block-button a[href], a.wp-block-button__link[href]").forEach { a ->
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    val linkText = a.text()?.trim() ?: ""

                    if (isJunkLink(href, linkText)) return@forEach

                    val idMatch = LINK_ID_REGEX.find(href)
                    if (idMatch != null) {
                        val id = idMatch.groupValues[1]
                        val epNum = EPISODE_NUM_REGEX.find(linkText)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        val isZip = linkText.contains("zip", ignoreCase = true)

                        section.links.add(
                            LinkEntry(
                                id = id,
                                quality = quality,
                                label = buildQualityLabel(linkText, quality),
                                linkText = linkText,
                                episodeNum = epNum,
                                isZip = isZip
                            )
                        )
                    }
                }

                if (section.links.isNotEmpty()) {
                    sections.add(section)
                }
                currentLabel = ""  // Reset label after consuming
            }
        }

        // --- Phase 2: Fallback — if DOM walking found nothing, try flat collection ---
        if (sections.isEmpty()) {
            Log.w(TAG, "DOM walking found no sections, falling back to flat collection")
            return buildSeriesEpisodesFallback(doc, rawTitle)
        }

        // --- Phase 3: Group by (season, episodeNumber) across quality variants ---
        // Each episode may appear in multiple sections (720p, 1080p, 4K, etc.)
        // We create one Episode per (season, epNum) with all quality links merged.
        data class EpisodeKey(val season: Int?, val episodeNum: Int?)

        val episodeMap = linkedMapOf<EpisodeKey, MutableList<LinkEntry>>()

        for (section in sections) {
            for (link in section.links) {
                // Skip zip/batch links — they're not individual episodes
                if (link.isZip) continue

                val epNum = link.episodeNum
                val key = EpisodeKey(section.season, epNum)

                episodeMap.getOrPut(key) { mutableListOf() }.add(
                    link.copy(quality = section.quality, season = section.season)
                )
            }
        }

        // --- Phase 4: Build Episode objects ---
        val episodes = mutableListOf<Episode>()

        for ((key, links) in episodeMap) {
            val (season, epNum) = key
            val name = if (epNum != null) {
                "Episode $epNum"
            } else {
                // No episode number — use section label
                links.firstOrNull()?.linkText ?: "Episode"
            }

            episodes.add(
                newEpisode(serializeLinkIds(links)) {
                    this.name = name
                    this.season = season
                    this.episode = epNum
                }
            )
        }

        // --- Phase 5: If no episodes with proper numbering, try zip links as fallback ---
        if (episodes.isEmpty()) {
            val allZipLinks = sections.flatMap { sec ->
                sec.links.filter { it.isZip }.map {
                    it.copy(quality = sec.quality, season = sec.season)
                }
            }
            if (allZipLinks.isNotEmpty()) {
                episodes.add(
                    newEpisode(serializeLinkIds(allZipLinks)) {
                        this.name = "Season Pack"
                    }
                )
            }
        }

        return episodes.ifEmpty {
            // Last resort: single episode with all links
            val allLinks = sections.flatMap { it.links }
            if (allLinks.isNotEmpty()) {
                listOf(
                    newEpisode(serializeLinkIds(allLinks)) {
                        this.name = "All Episodes"
                    }
                )
            } else emptyList()
        }
    }

    /**
     * Fallback series episode builder when DOM walking fails.
     * Uses the old approach of distributing links by season range from title.
     */
    private fun buildSeriesEpisodesFallback(doc: Document, rawTitle: String): List<Episode> {
        val linkIds = collectMovieLinkIds(doc)
        if (linkIds.isEmpty()) return emptyList()

        val episodes = mutableListOf<Episode>()
        val seasonRange = parseSeasonRange(rawTitle)
        val byQuality = linkIds.groupBy { it.quality.ifBlank { "HD" } }

        if (seasonRange != null) {
            val (firstSeason, lastSeason) = seasonRange
            val totalLinks = linkIds.size
            val seasonsCount = lastSeason - firstSeason + 1
            val linksPerSeason = totalLinks / seasonsCount

            for (s in firstSeason..lastSeason) {
                val startIdx = (s - firstSeason) * linksPerSeason
                val endIdx = kotlin.math.min(startIdx + linksPerSeason, totalLinks)
                val seasonLinks = linkIds.subList(
                    startIdx.coerceAtMost(totalLinks),
                    endIdx.coerceAtMost(totalLinks)
                )

                if (seasonLinks.isNotEmpty()) {
                    val qualityGroups = seasonLinks.groupBy { it.quality.ifBlank { "HD" } }
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
        return null
    }

    // ========================================================================
    // Link ID Resolver
    // ========================================================================

    /**
     * Resolve a link generator ID to playable video URLs.
     *
     * Resolution chain:
     *   1. Try OMDrive extractor via loadExtractor (handles WebViewResolver)
     *   2. Try fetching the link generator page directly for any embedded URLs
     *   3. Try the alternate domain (links.olamovies.mov)
     *
     * NOTE: The link generator page is CF-protected with a countdown timer
     * and shortener. On Android, CloudStream's WebViewResolver handles CF.
     * The OMDrive extractor registered in the plugin will be invoked by
     * loadExtractor(), which can use WebViewResolver to bypass CF and
     * follow the shortener chain to reach the final download URL.
     */
    private suspend fun resolveLinkId(
        id: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        var found = false

        // Stage 1: Use registered OMDrive extractor (handles CF via WebViewResolver)
        try {
            val genUrl = "$LINKS_BASE/$id"
            loadExtractor(genUrl, mainUrl, subtitleCallback, callback)
            found = true  // Optimistic — extractor will emit links if available
        } catch (e: Exception) {
            Log.d(TAG, "resolveLinkId: OMDrive extractor failed for $id: ${e.message}")
        }

        // Stage 2: Try the alternate link generator page directly
        // This may work if CF challenge is not active or WebViewResolver handles it
        try {
            val genUrl = "$LINKS_ALT/$id"
            val response = app.get(genUrl, headers = BASE_HEADERS, timeout = 15)
            if (response.code == 200) {
                val doc = response.document
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

                // Direct video URLs (.mkv, .mp4) — but NOT from R2 tutorial
                Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4|avi|ts|m4v)""")
                    .findAll(pageText).forEach { match ->
                        val url = match.value
                        // Filter out tutorial video and static assets
                        if (!url.contains("r2.dev") && !url.contains(".js") &&
                            !url.contains(".css") && !url.contains(".json")) {
                            val qualityHint = extractQualityFromUrl(url)
                            callback.invoke(
                                newExtractorLink(
                                    name,
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

                // Any other known hoster links
                doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (href.startsWith("http") && !isJunkLink(href, "") &&
                        !href.contains("ol-am.top") && !href.contains("olamovies.mov")) {
                        href
                    } else null
                }.distinct().forEach { hostUrl ->
                    try {
                        loadExtractor(hostUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not dispatch to extractor: $hostUrl — ${e.message}")
                    }
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
            // Skip play.ol-am.top — it only serves a tutorial video
            url.contains("play.ol-am.top", ignoreCase = true) -> {
                Log.d(TAG, "Skipping play.ol-am.top tutorial URL: $url")
                false
            }
            url.contains("googleusercontent.com", ignoreCase = true) ||
            url.contains("drive.google.com", ignoreCase = true) -> {
                val qualityHint = extractQualityFromUrl(url)
                callback.invoke(
                    newExtractorLink(
                        "$name GDrive",
                        "$name GDrive $qualityHint",
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(qualityHint)
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
    // Link Entry & Serialization
    // ========================================================================

    /**
     * Represents a single download link with metadata.
     */
    private data class LinkEntry(
        val id: String,
        val quality: String,
        val label: String,
        val linkText: String,
        val episodeNum: Int? = null,
        val isZip: Boolean = false,
        val season: Int? = null,
    )

    private fun serializeLinkIds(links: List<LinkEntry>): String {
        return links.map { entry ->
            // Encode quality & label as query params in the linkid
            val q = URLEncoder.encode(entry.quality, "UTF-8")
            val l = URLEncoder.encode(entry.label, "UTF-8")
            "linkid:${entry.id}?q=$q&l=$l"
        }.joinToString("|")
    }

    private fun deserializeLinks(data: String): List<String> {
        return data.split("|")
            .map { it.trim() }
            .filter { it.startsWith("http") || it.startsWith("linkid:") }
            .distinct()
    }
}
