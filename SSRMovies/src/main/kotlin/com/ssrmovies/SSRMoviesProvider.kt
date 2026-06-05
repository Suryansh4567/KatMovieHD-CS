package com.ssrmovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * SSRMovies provider for CloudStream.
 *
 * Site structure (WordPress — "moviepress" theme):
 *   - Homepage / category / search pages: movie cards as `div.movie-card` elements
 *     containing `a.card-poster > img.poster-image` (poster — lazy loaded via data-src),
 *     `span.lbl-rating` (rating), `h3.card-title > a` (title + link),
 *     `div.card-meta` (year + quality tag).
 *   - Detail pages (movies): title in `h1.movie-title-main`, poster in
 *     `img.single-poster-img` (lazy loaded via data-src), description in
 *     `div.info-desc`, storyline in `article.main-article`, download section in
 *     `div.dl-grid > a.dl-btn-premium` (each button = one quality level).
 *   - Detail pages (series): same structure but title includes "Season" / "Ep"
 *     patterns. Each page IS one episode with its own download links.
 *   - Download link hosts: pkembed.site (embed player), mxdrop.to (file host),
 *     do7go.com (redirector).
 *
 * This provider follows the same proven patterns as MkvHubProvider:
 *   - Proper CloudStream API types (SearchQuality, Score)
 *   - Parallel link resolution via `amap`
 *   - Robust error handling with diagnostic logging
 *   - Defensive coding with null-safe selectors and fallbacks
 */
class SSRMoviesProvider : MainAPI() {
    override var mainUrl = "https://ssr-movies.com"
    override var name = "SSRMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TAG = "SSRMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** Regex to match season numbers from titles like "S05" or "Season 5" */
        private val SEASON_REGEX = Regex("""(?i)(?:S(\d{1,2})\b|Season\s*(\d{1,2}))""")

        /** Regex to match episode numbers from titles like "S05E01", "E01", "Ep 06", "Episode 01" */
        private val EPISODE_REGEX =
            Regex("""(?i)(?:S\d{1,2}E(\d{1,3})\b|E(\d{1,3})\b|Ep(?:isode)?\s*[-:#]?\s*(\d{1,3})\b)""")

        /** Regex to detect quality labels in text */
        private val QUALITY_REGEX = Regex("""(?i)(4K|2160p|1080p|720p|480p)""")

        /** Regex to extract year from title text */
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

        /**
         * Known embed / redirector hosts found on SSRMovies download buttons.
         * pkembed.site — embed player pages that contain video source URLs
         * mxdrop.to — file host that CloudStream extractors may handle
         * do7go.com — redirector that resolves to an actual file host
         */
        private val PKEMBED_REGEX = Regex("""(?i)pkembed\.site""")
        private val MXDROP_REGEX = Regex("""(?i)mxdrop\.to""")
        private val DO7GO_REGEX = Regex("""(?i)do7go\.com""")

        /**
         * Hosts that look like external links but are NEVER stream sources.
         * Filters out navigation, social media, image hosts, and ad networks.
         */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """ssr-movies\.com|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#""" +
                    """)"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/ssr-movies/" to "SSR Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/web-series/" to "Web Series"
    )

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Homepage uses request.data directly for page 1;
        // category pages use "${request.data}page/$page/" for page > 1
        val url = if (page == 1) {
            request.data
        } else {
            // For the homepage (ends with /), pagination is /page/N/
            // For category pages (ends with /), pagination is category/.../page/N/
            "${request.data}page/$page/"
        }
        Log.d(TAG, "getMainPage: fetching $url")
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.movie-card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        Log.d(TAG, "search: fetching $url")
        val doc = app.get(url, headers = headers).document

        // Detect empty search results: WordPress returns 200 with
        // <body class="search search-no-results"> when no matches
        val bodyTag = doc.selectFirst("body")
        if (bodyTag != null) {
            val bodyClass = bodyTag.className()
            if (bodyClass.contains("search-no-results")) {
                Log.d(TAG, "search('$query'): no results found")
                return emptyList()
            }
        }

        return doc.select("div.movie-card").mapNotNull { it.toSearchResult() }
    }

    /**
     * Parse a `div.movie-card` element into a SearchResponse.
     *
     * HTML structure (verified against live site):
     *   <div class="movie-card">
     *     <a class="card-poster" href="DETAIL_URL">
     *       <img class="poster-image wp-post-image" data-src="POSTER_URL" ...>
     *       <span class="lbl-rating">★ 7.8</span>
     *       <span class="lbl-new">NEW ⚡</span>
     *     </a>
     *     <h3 class="card-title"><a href="DETAIL_URL">TITLE</a></h3>
     *     <div class="card-meta">
     *       <span>2026</span>
     *       <span class="tag-hd">HD</span>
     *     </div>
     *   </div>
     */
    private fun Element.toSearchResult(): SearchResponse? {
        // Title from h3.card-title > a
        val titleEl = selectFirst("h3.card-title a") ?: return null
        val title = titleEl.text().trim().ifBlank { return null }

        // Detail URL from h3.card-title > a (most reliable) or a.card-poster
        val href = fixUrlNull(titleEl.attr("href"))
            ?: fixUrlNull(selectFirst("a.card-poster")?.attr("href"))
            ?: return null

        // Poster: lazy-loaded via data-src on img.poster-image
        val posterUrl = fixUrlNull(
            selectFirst("img.poster-image")?.attr("data-src")
                ?: selectFirst("img.poster-image")?.attr("src")
        )

        // Rating from span.lbl-rating (e.g. "★ 7.8")
        val ratingText = selectFirst("span.lbl-rating")?.text()?.trim()
        val rating = parseRating(ratingText)

        // Quality tag from span.tag-hd or card-meta
        val qualityTag = selectFirst("span.tag-hd")?.text()?.trim() ?: ""
        val quality = detectSearchQuality("$title $qualityTag")

        // Detect series from title patterns and URL
        val isSeries = isSeriesTitle(title) || href.contains("/category/web-series/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.score = rating
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.score = rating
            }
        }
    }

    // ------------------------------------------------------------------
    // Detail page — load()
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: fetching $url")
        val response = app.get(url, headers = headers)
        val doc = response.document

        // Title from h1.movie-title-main (primary), fallback to og:title
        val title = doc.selectFirst("h1.movie-title-main")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: throw Exception("No title found at $url")

        // Poster: img.single-poster-img with data-src (lazy loaded),
        // fallback to og:image
        val posterUrl = fixUrlNull(
            doc.selectFirst("img.single-poster-img")?.attr("data-src")
                ?: doc.selectFirst("img.single-poster-img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Plot: article.main-article (storyline) or div.info-desc (description)
        val plot = doc.selectFirst("article.main-article")?.text()?.trim()
            ?: doc.selectFirst("div.info-desc p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: ""

        // Rating from span.lbl-rating on the detail page, or from og:description
        var score: Score? = null
        val ratingText = doc.selectFirst("span.lbl-rating")?.text()?.trim()
        score = parseRating(ratingText)
        if (score == null) {
            // Fallback: try to extract IMDB rating from og:description
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            if (!ogDesc.isNullOrBlank()) {
                Regex("""IMDb\s+Rating:?\s*(\d+\.?\d*)/10""", RegexOption.IGNORE_CASE)
                    .find(ogDesc)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                        score = Score.from10(it)
                    }
            }
        }

        // Tags from span.meta-tag (e.g. "Hindi / English" → ["Hindi", "English"])
        val tags = doc.select("span.meta-tag").mapNotNull { tag ->
            tag.text().trim().split("/").map { it.trim() }.filter { it.isNotBlank() }
        }.flatten().distinct()

        // Year from title regex, fallback to card-meta span
        val year = YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: doc.selectFirst("div.card-meta span")?.text()?.trim()?.toIntOrNull()

        // Detect series from title, URL, and tags
        val isSeries = isSeriesTitle(title) ||
                url.contains("/category/web-series/") ||
                tags.any { it.equals("Web Series", ignoreCase = true) }

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries year=$year")

        // Collect download URLs with quality labels from dl-grid buttons
        val downloadLinks = collectDownloadLinks(doc)
        Log.d(TAG, "load() found ${downloadLinks.size} download link(s)")

        if (isSeries) {
            // Extract season/episode info from title
            val seasonNum = parseSeasonFromTitle(title)
            val episodeNum = parseEpisodeFromTitle(title)

            // Each SSRMovies series page represents ONE episode
            // Create a single-episode series with all quality links
            val linksData = downloadLinks.joinToString("\n") { it.second }

            val episodes = if (linksData.isNotBlank()) {
                listOf(newEpisode(linksData) {
                    name = buildEpisodeName(title, seasonNum, episodeNum)
                    this.season = seasonNum
                    this.episode = episodeNum
                })
            } else {
                // Fallback: use page URL as episode data
                listOf(newEpisode(url) {
                    name = buildEpisodeName(title, seasonNum, episodeNum)
                    this.season = seasonNum
                    this.episode = episodeNum
                })
            }

            return newTvSeriesLoadResponse(
                cleanSeriesTitle(title),
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            // For movies: collect all quality download button URLs as
            // newline-joined string. loadLinks will split and resolve each.
            val linksData = downloadLinks.joinToString("\n") { it.second }

            return newMovieLoadResponse(title, url, TvType.Movie, linksData) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    // ------------------------------------------------------------------
    // Download link collection
    // ------------------------------------------------------------------

    /**
     * Collect download URLs from the detail page's download section.
     *
     * HTML structure:
     *   <div class="download-section">
     *     <h3 class="dl-title">📥 Download Links</h3>
     *     <div class="dl-grid">
     *       <a href="https://pkembed.site/XXX" class="dl-btn-premium">
     *         <div class="dl-left">
     *           <div class="dl-icon-box">⬇</div>
     *           <div class="dl-text">
     *             <h4>480p</h4><span>Standard</span>
     *           </div>
     *         </div>
     *         <div class="dl-right"><span class="quality-badge">SD</span></div>
     *       </a>
     *       <a href="https://mxdrop.to/XXX" class="dl-btn-premium highlight-btn">
     *         ...
     *         <h4>1080p</h4><span>Full HD</span>
     *         <span class="quality-badge">FHD</span>
     *       </a>
     *     </div>
     *   </div>
     *
     * Returns list of (qualityLabel, url) pairs.
     */
    private fun collectDownloadLinks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()

        // Primary: look in div.dl-grid > a.dl-btn-premium
        val dlGrid = doc.selectFirst("div.dl-grid")
        if (dlGrid != null) {
            dlGrid.select("a.dl-btn-premium").forEach { btn ->
                val href = btn.attr("href").trim()
                if (!href.startsWith("http")) return@forEach

                // Quality label from h4 text (e.g. "480p", "720p", "1080p")
                val qualityLabel = btn.selectFirst("h4")?.text()?.trim() ?: ""
                val qualityBadge = btn.selectFirst("span.quality-badge")?.text()?.trim() ?: ""

                val label = when {
                    qualityLabel.isNotBlank() -> qualityLabel
                    qualityBadge.isNotBlank() -> qualityBadge
                    else -> "Download"
                }

                if (href !in links.map { it.second }) {
                    links.add(label to href)
                }
            }
        }

        // Fallback: try a.dl-btn-premium anywhere on the page
        if (links.isEmpty()) {
            doc.select("a.dl-btn-premium").forEach { btn ->
                val href = btn.attr("href").trim()
                if (!href.startsWith("http")) return@forEach

                val qualityLabel = btn.selectFirst("h4")?.text()?.trim() ?: ""
                val label = qualityLabel.ifBlank { "Download" }

                if (href !in links.map { it.second }) {
                    links.add(label to href)
                }
            }
        }

        // Second fallback: look in div.download-section for any anchors with http hrefs
        if (links.isEmpty()) {
            val downloadSection = doc.selectFirst("div.download-section")
            downloadSection?.select("a[href]")?.forEach { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) return@forEach
                if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
                if (href in links.map { it.second }) return@forEach

                val text = a.text().trim().take(30)
                val label = QUALITY_REGEX.find(text)?.value ?: text.ifBlank { "Download" }
                links.add(label to href)
            }
        }

        Log.d(TAG, "collectDownloadLinks: found ${links.size} download link(s)")
        return links
    }

    // ------------------------------------------------------------------
    // loadLinks — resolve download URLs to playable streams
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Data can be either:
        // 1. A single URL (series episode link)
        // 2. Newline-separated URLs (movie: multiple quality download buttons)
        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")

        var anySuccess = false

        urls.amap { url ->
            try {
                // 1. Try pkembed.site resolution (embed player → video source)
                if (PKEMBED_REGEX.containsMatchIn(url)) {
                    Log.d(TAG, "loadLinks: resolving pkembed embed: $url")
                    val resolved = resolvePkembed(url)
                    if (resolved.isNotEmpty()) {
                        resolved.amap { (label, streamUrl) ->
                            try {
                                if (!tryLoadExtractor(streamUrl, subtitleCallback, callback)) {
                                    addDirectLink(streamUrl, callback, label)
                                }
                                anySuccess = true
                            } catch (e: Exception) {
                                Log.w(TAG, "pkembed stream extraction failed for $streamUrl: ${e.message}")
                                addDirectLink(streamUrl, callback, label)
                                anySuccess = true
                            }
                        }
                    } else {
                        Log.w(TAG, "pkembed resolution yielded no streams for $url")
                        // Fallback: try loadExtractor on the pkembed URL directly
                        if (tryLoadExtractor(url, subtitleCallback, callback)) {
                            anySuccess = true
                        } else {
                            addDirectLink(url, callback, "pkembed")
                            anySuccess = true
                        }
                    }
                }
                // 2. Try do7go.com redirector resolution
                else if (DO7GO_REGEX.containsMatchIn(url)) {
                    Log.d(TAG, "loadLinks: resolving do7go redirector: $url")
                    val resolved = resolveDo7go(url)
                    if (resolved != null) {
                        if (!tryLoadExtractor(resolved, subtitleCallback, callback)) {
                            addDirectLink(resolved, callback, "do7go")
                        }
                        anySuccess = true
                    } else {
                        Log.w(TAG, "do7go redirector yielded no URL for $url")
                        // Try loadExtractor as fallback
                        if (tryLoadExtractor(url, subtitleCallback, callback)) {
                            anySuccess = true
                        }
                    }
                }
                // 3. Try CloudStream's built-in extractors (handles mxdrop.to etc.)
                else {
                    if (!tryLoadExtractor(url, subtitleCallback, callback)) {
                        // No extractor matched — add as direct link
                        addDirectLink(url, callback)
                    }
                    anySuccess = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve $url: ${e.message}")
                // Last resort: try passing the URL directly to loadExtractor
                try {
                    if (tryLoadExtractor(url, subtitleCallback, callback)) {
                        anySuccess = true
                    }
                } catch (_: Exception) {}
            }
        }

        return anySuccess
    }

    // ------------------------------------------------------------------
    // Embed / redirector resolvers
    // ------------------------------------------------------------------

    /**
     * Resolve a pkembed.site URL by fetching the embed page and
     * extracting the video source URL.
     *
     * pkembed pages typically contain:
     *   - An iframe with a video player source
     *   - JavaScript with video source URLs (e.g. in eval() or JSON)
     *   - Direct video source URLs in <source> or <video> tags
     *
     * Returns list of (label, streamUrl) pairs, or empty list on failure.
     */
    private suspend fun resolvePkembed(url: String): List<Pair<String, String>> {
        return try {
            val res = app.get(
                url,
                referer = mainUrl,
                headers = headers,
                timeout = 15000
            )
            val doc = res.document
            val html = res.text
            val results = mutableListOf<Pair<String, String>>()

            // Strategy 1: Look for <source> tag inside <video>
            doc.select("video source[src]").forEach { source ->
                val src = source.attr("src").trim()
                if (src.startsWith("http")) {
                    val label = source.attr("label")?.trim()?.ifBlank { null }
                        ?: source.attr("title")?.trim()?.ifBlank { null }
                        ?: "Stream"
                    results.add(label to src)
                }
            }

            // Strategy 2: Look for iframe with video player
            if (results.isEmpty()) {
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.startsWith("http") && !IGNORE_HOST_REGEX.containsMatchIn(src)) {
                        // Recursively resolve the iframe URL
                        results.add("Player" to src)
                    }
                }
            }

            // Strategy 3: Search raw HTML for video URLs using regex
            // Common patterns: .mp4, .m3u8, or player API URLs
            if (results.isEmpty()) {
                // Look for video source URLs in JavaScript
                val videoUrlRegex = Regex(
                    """(?i)(?:src|source|url|file)\s*[:=]\s*["']([^"']*\.(?:mp4|m3u8|mkv)[^"']*)["']"""
                )
                videoUrlRegex.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    if (videoUrl.startsWith("http")) {
                        results.add("Stream" to videoUrl)
                    }
                }

                // Also look for URLs that look like CDN/stream URLs
                val cdnUrlRegex = Regex(
                    """(?i)(https?://[^\s"'<>]+/(?:index\.m3u8|playlist\.m3u8|[^"'<>\s]+\.(?:mp4|m3u8)))"""
                )
                cdnUrlRegex.findAll(html).forEach { match ->
                    val cdnUrl = match.groupValues[1].trim()
                    if (cdnUrl !in results.map { it.second }) {
                        results.add("CDN" to cdnUrl)
                    }
                }
            }

            // Strategy 4: Look for any URL that looks like a stream endpoint
            if (results.isEmpty()) {
                // Some embed pages use JavaScript to construct the video URL
                // Look for common player patterns like "/v/", "/embed/", "/video/"
                val streamUrlRegex = Regex(
                    """(?i)(https?://[^\s"'<>]+/(?:v|embed|video|stream|play)/[^\s"'<>]+)"""
                )
                streamUrlRegex.findAll(html).forEach { match ->
                    val streamUrl = match.groupValues[1].trim()
                    if (!IGNORE_HOST_REGEX.containsMatchIn(streamUrl) &&
                        streamUrl !in results.map { it.second }
                    ) {
                        results.add("Embed" to streamUrl)
                    }
                }
            }

            if (results.isEmpty()) {
                Log.w(TAG, "pkembed resolution found 0 streams from $url")
                // Diagnostic: log what we found on the page
                val iframes = doc.select("iframe").map { it.attr("src") }
                val sources = doc.select("source, video").map { it.attr("src") }
                Log.d(TAG, "pkembed page iframes: $iframes")
                Log.d(TAG, "pkembed page sources: $sources")
            } else {
                Log.d(TAG, "pkembed resolved ${results.size} stream(s)")
            }

            results
        } catch (e: Exception) {
            Log.e(TAG, "pkembed resolution failed for $url: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolve a do7go.com redirector URL by following redirects
     * to get the actual target URL.
     *
     * Returns the resolved URL, or null on failure.
     */
    private suspend fun resolveDo7go(url: String): String? {
        return try {
            val res = app.get(
                url,
                referer = mainUrl,
                headers = headers,
                timeout = 15000,
                allowRedirects = false
            )
            // Check for HTTP redirect
            val location = res.headers["location"]
            if (!location.isNullOrBlank() && location.startsWith("http")) {
                Log.d(TAG, "do7go redirect: $url → $location")
                return location
            }

            // If no HTTP redirect, check for JavaScript or meta redirect in HTML
            val doc = res.document
            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            if (metaRefresh != null) {
                val content = metaRefresh.attr("content")
                val urlMatch = Regex("""url\s*=\s*(.+)""", RegexOption.IGNORE_CASE).find(content)
                if (urlMatch != null) {
                    val target = urlMatch.groupValues[1].trim().trim('"', '\'')
                    if (target.startsWith("http")) {
                        Log.d(TAG, "do7go meta redirect: $url → $target")
                        return target
                    }
                }
            }

            // Check for JavaScript window.location redirect
            val html = res.text
            val jsRedirect = Regex("""(?:window\.)?location\s*[=:]\s*["']([^"']+)["']""")
                .find(html)
            if (jsRedirect != null) {
                val target = jsRedirect.groupValues[1].trim()
                if (target.startsWith("http")) {
                    Log.d(TAG, "do7go JS redirect: $url → $target")
                    return target
                }
            }

            // Try following with regular GET (allow redirects)
            val resFollow = app.get(url, referer = mainUrl, headers = headers, timeout = 15000)
            val finalUrl = resFollow.url
            if (finalUrl != url && finalUrl.startsWith("http")) {
                Log.d(TAG, "do7go final URL after redirect: $url → $finalUrl")
                return finalUrl
            }

            Log.w(TAG, "do7go could not resolve redirect for $url")
            null
        } catch (e: Exception) {
            Log.e(TAG, "do7go resolution failed for $url: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Helper utilities
    // ------------------------------------------------------------------

    /**
     * Try CloudStream's built-in loadExtractor for a URL.
     * Returns true if extraction succeeded.
     */
    private suspend fun tryLoadExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadExtractor(url, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.d(TAG, "loadExtractor failed for $url: ${e.message}")
            false
        }
    }

    /**
     * Add a direct download link as a fallback when no extractor
     * can handle the URL. Uses INFER_TYPE so CloudStream tries
     * to detect the stream type automatically.
     */
    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        label: String = "Direct"
    ) {
        val quality = detectQualityFromUrl(url)

        callback(
            newExtractorLink(
                "$name - $label",
                label,
                url,
                INFER_TYPE
            ) {
                this.quality = quality
                this.referer = mainUrl
            }
        )
    }

    /**
     * Detect quality value from URL content (quality labels in filename/path).
     */
    private fun detectQualityFromUrl(url: String): Int {
        return when {
            url.contains("2160p", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080p", true) -> Qualities.P1080.value
            url.contains("720p", true) -> Qualities.P720.value
            url.contains("480p", true) -> Qualities.P480.value
            else -> Qualities.P720.value
        }
    }

    /**
     * Detect search quality from title text using CloudStream's
     * getQualityFromString() — returns SearchQuality? (not raw Int).
     */
    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    /**
     * Parse a rating string like "★ 7.8" or "7.8/10" into a Score.
     */
    private fun parseRating(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        // Extract numeric rating from text like "★ 7.8" or "7.8"
        val match = Regex("""(\d+\.?\d*)""").find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.let {
            if (it <= 10.0) Score.from10(it) else null
        }
    }

    /**
     * Determine if a title represents a series (TV show/web series)
     * rather than a standalone movie.
     */
    private fun isSeriesTitle(title: String): Boolean {
        return Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                EPISODE_REGEX.containsMatchIn(title) ||
                title.contains("Web Series", ignoreCase = true) ||
                title.contains("TV Series", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true)
    }

    /**
     * Extract season number from title text.
     * Examples: "Spring Fever (2026 Ep 06) Hindi Dubbed Season 1" → 1
     *           "The Bear S02 Complete" → 2
     * Returns 1 as default if no season is found.
     */
    private fun parseSeasonFromTitle(title: String): Int {
        return SEASON_REGEX.find(title)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1
    }

    /**
     * Extract episode number from title text.
     * Examples: "Spring Fever (2026 Ep 06) Hindi Dubbed Season 1" → 6
     *           "Breaking Bad S01E03" → 3
     * Returns 1 as default if no episode is found.
     */
    private fun parseEpisodeFromTitle(title: String): Int {
        return EPISODE_REGEX.find(title)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] })
                .toIntOrNull()
        } ?: 1
    }

    /**
     * Build a human-readable episode name from the title and parsed
     * season/episode numbers.
     */
    private fun buildEpisodeName(title: String, season: Int, episode: Int): String {
        // Try S01E06 format
        val sxxExx = Regex("""(?i)(S\d{1,2}E\d{1,3})""").find(title)
        if (sxxExx != null) return sxxExx.groupValues[1].uppercase()

        // Build from parsed numbers
        return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    /**
     * Clean a series title by removing episode/season indicators and
     * quality labels to get the base series name.
     * Example: "Spring Fever (2026 Ep 06) Hindi Dubbed Season 1 Full Movie Download"
     *       → "Spring Fever"
     */
    private fun cleanSeriesTitle(title: String): String {
        var cleaned = title
            // Remove common download suffixes
            .replace(Regex("""(?i)\s*Full\s+Movie\s+Download\s*"""), "")
            .replace(Regex("""(?i)\s*Download\s*$"""), "")
            // Remove episode/season info in parentheses
            .replace(Regex("""\s*\(\d{4}\s+Ep\s+\d+\)"""), "")
            // Remove season/episode patterns
            .replace(Regex("""(?i)\s*S\d{1,2}E\d{1,3}\b"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d{1,2}\b.*"""), "")
            .replace(Regex("""(?i)\s*Ep(?:isode)?\s*[-:#]?\s*\d{1,3}\b"""), "")
            // Remove quality labels
            .replace(QUALITY_REGEX, "")
            // Remove language tags
            .replace(Regex("""(?i)\s*(?:Hindi\s*/?\s*English|Dual\s*Audio|Hindi\s*Dubbed)\s*"""), " ")
            // Remove year (it's metadata, not part of the title)
            .replace(YEAR_REGEX, "")
            // Clean up extra whitespace and punctuation
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trimEnd('-', ':', '|')

        return cleaned.ifBlank { title }
    }
}
