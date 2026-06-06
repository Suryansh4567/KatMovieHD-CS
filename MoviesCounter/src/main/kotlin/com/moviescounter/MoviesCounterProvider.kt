package com.moviescounter

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
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * MoviesCounter provider for CloudStream.
 *
 * Site: moviescounter.boston (WordPress, custom Tailwind-based theme)
 *
 * Page structure:
 *   <main>
 *     <section class="md:flex mt-4">
 *       <div class="w-full md:w-8/12 px-2">      ← content column
 *         <h3 class="text-gray-500">TITLE</h3>
 *         <div class="post-body">                  ← POST BODY (downloads only)
 *           ...description, screenshots, download links...
 *         </div>
 *         <div class="w-full my-4 text-center">   ← POST HASHTAGS (#Action, #Horror)
 *           <a href="/category/..."># Tag</a>
 *         </div>
 *         <h3>You May Also Like</h3>               ← RELATED POSTS (contamination!)
 *         <section>...related cards...</section>
 *       </div>
 *       <aside class="w-full md:w-4/12">           ← SIDEBAR (contamination!)
 *         ...categories, recent posts...
 *       </aside>
 *     </section>
 *   </main>
 *
 * CRITICAL: The sidebar and "You May Also Like" sections contain series-related
 * keywords ("WEB-Series", "TV-Shows", "Season", "EPiSODE") that cause FALSE
 * POSITIVES in series detection. ALL scraping must be scoped to div.post-body
 * and the post's own hashtag div ONLY.
 *
 * ── v4 — Reliability & Robustness ──
 *   • Domain rotation fallback: site frequently changes TLDs (.boston, .one,
 *     .win, .vip, .ink). On network failure, each alternative is tried in order.
 *   • Dead page detection: WordPress redirects non-existent pages to the
 *     homepage/category instead of 404. Detected via canonical URL check.
 *   • Search pagination: WordPress /page/N/?s=query format for deep results.
 *   • Retry with timeout tuning: transient failures don't kill the entire load.
 *   • Canonical URL validation: catches silent redirects to wrong pages.
 */
class MoviesCounterProvider : MainAPI() {

    override var mainUrl = "https://moviescounter.boston"
    override var name = "MoviesCounter"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TAG = "MoviesCounter"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /**
         * Known MoviesCounter domain TLDs — the site frequently changes domains.
         * On domain failure, each alternative is tried in order until one works.
         * The first successful domain becomes the active mainUrl.
         */
        private val DOMAIN_TLDS = listOf("boston", "one", "win", "vip", "ink", "lol", "cc")

        /**
         * WordPress redirects non-existent pages to this category
         * instead of returning 404. Used to detect invalid page loads.
         */
        private const val FALLBACK_CATEGORY = "/category/latest"

        /** Request timeout in seconds. */
        private const val TIMEOUT = 30

        /** Maximum retry attempts for network requests. */
        private const val MAX_RETRIES = 2

        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """moviescounter|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|effectivecpm|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|""" +
                    """doubleclick|popads|propeller|profitablecpm""" +
                    """)"""
        )

        /** Match "Episode N" / "EPiSODE N" headers. */
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )

        /** Match "Season N" / "S0N" in titles. */
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Regex to extract size like [510MB] or [1.3GB] */
        private val SIZE_REGEX = Regex("""\[([\d.]+(?:MB|GB|TB))\]""", RegexOption.IGNORE_CASE)

        /** Regex to match "PACK" keyword — full-season downloads */
        private val PACK_REGEX = Regex("""(?i)\bPACK\b""")

        /** Regex to detect "Single Episode" section header */
        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise"""
        )

        /** Regex to skip SAMPLE/trailer links */
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

        /**
         * Regex to skip torrent/magnet links — CloudStream can't play them.
         * These appear on some pages as alternative download options.
         */
        private val TORRENT_REGEX = Regex(
            """(?i)\btorrent\b|\bmagnet\b|\b\.torrent\b|\bmagnet:\?"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------------------
    // Domain rotation & network resilience
    // ------------------------------------------------------------------

    /**
     * Attempt an HTTP GET with domain rotation fallback.
     * If the primary domain fails (timeout, DNS error, 5xx), each
     * alternative TLD from DOMAIN_TLDS is tried in order.
     * On success, [mainUrl] is updated to the working domain so
     * subsequent requests don't have to retry.
     */
    private suspend fun resilientGet(
        url: String,
        customHeaders: Map<String, String> = headers,
        timeout: Int = TIMEOUT
    ): com.lagradost.cloudstream3.mvvm.Resource<com.lagradost.cloudstream3.network.WebPage> {
        // First attempt: use the URL as-is
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val res = app.get(url, headers = customHeaders, timeout = timeout)
                if (res.code < 500) return res
                lastException = Exception("HTTP ${res.code}")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "resilientGet attempt $attempt failed for $url: ${e.message}")
            }
        }

        // Domain rotation: try each alternative TLD
        val currentHost = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
        if (currentHost != null) {
            for (tld in DOMAIN_TLDS) {
                val altHost = "https://moviescounter.$tld"
                if (altHost == currentHost) continue
                val altUrl = url.replace(currentHost, altHost)
                try {
                    val res = app.get(altUrl, headers = customHeaders, timeout = timeout)
                    if (res.code < 500) {
                        // Domain works! Update mainUrl for future requests
                        Log.d(TAG, "Domain rotation: switching from $currentHost to $altHost")
                        mainUrl = altHost
                        return res
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Domain rotation: $altHost failed: ${e.message}")
                }
            }
        }

        // All attempts failed — throw the last exception
        throw lastException ?: Exception("All domain attempts failed for $url")
    }

    /**
     * Check if a document represents a dead/invalid page.
     * WordPress often redirects non-existent pages to a category
     * listing (200 OK) instead of returning 404.
     */
    private fun isDeadPage(doc: org.jsoup.nodes.Document, originalUrl: String): Boolean {
        // Check 1: canonical URL points to a fallback category
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if (canonical != null && canonical.contains(FALLBACK_CATEGORY)) {
            Log.d(TAG, "Dead page detected: canonical redirects to $canonical")
            return true
        }

        // Check 2: body class indicates 404
        val bodyClass = doc.selectFirst("body")?.className() ?: ""
        if (bodyClass.contains("error404", ignoreCase = true) ||
            bodyClass.contains("page-not-found", ignoreCase = true)
        ) {
            Log.d(TAG, "Dead page detected: body class indicates 404")
            return true
        }

        // Check 3: page title contains "Not Found" or "Page not found"
        val pageTitle = doc.title().lowercase()
        if (pageTitle.contains("page not found") || pageTitle.contains("not found") &&
            !pageTitle.contains("moviescounter")
        ) {
            Log.d(TAG, "Dead page detected: title '$pageTitle'")
            return true
        }

        return false
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/bollywood-movies/" to "Bollywood",
        "$mainUrl/category/hollywood-movies/" to "Hollywood",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/south-hindi-movies/" to "South Hindi",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/hd-movies/" to "HD Movies",
        "$mainUrl/category/300mb-movies/" to "300MB Movies",
        "$mainUrl/category/true-web-dl/" to "TRUE WEB-DL"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = resilientGet(url).document
        val items = parseListing(doc)
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = resilientGet(url).document
        return parseListing(doc)
    }

    /**
     * Paginated search — enables deep result browsing.
     * WordPress uses /page/N/?s=query format for search pagination.
     */
    override suspend fun search(query: String, page: Int): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
        val doc = resilientGet(url).document
        return parseListing(doc)
    }

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Strategy 1: Tailwind-based card layout (current theme)
        doc.select("div.inline-flex.flex-col > a").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) return@forEach
            if (href in seenUrls) return@forEach
            if (isNonContentUrl(href)) return@forEach

            val img = anchor.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim()
                .ifBlank { img.attr("title").trim() }
                .ifBlank { anchor.selectFirst("div.transition-all")?.text()?.trim().orEmpty() }
                .ifBlank { return@forEach }

            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (isNonContentImage(src)) return@forEach

            val poster = fixUrlNull(src.ifBlank { null })
            val isSeries = detectSeriesFromTitle(title, href)
            val quality = detectSearchQuality(title)

            seenUrls.add(href)
            results.add(
                if (isSeries) {
                    newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                } else {
                    newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                }
            )
        }

        // Strategy 2: Fallback — any anchor with poster image
        if (results.isEmpty()) {
            doc.select("a:has(img)").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seenUrls) return@forEach
                if (isNonContentUrl(href)) return@forEach

                val img = anchor.selectFirst("img") ?: return@forEach
                val title = img.attr("alt").trim()
                    .ifBlank { img.attr("title").trim() }
                    .ifBlank { anchor.text().trim() }
                if (title.isBlank()) return@forEach

                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (isNonContentImage(src)) return@forEach

                val poster = fixUrlNull(src.ifBlank { null })
                val isSeries = detectSeriesFromTitle(title, href)
                val quality = detectSearchQuality(title)

                seenUrls.add(href)
                results.add(
                    if (isSeries) {
                        newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.quality = quality
                        }
                    } else {
                        newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                            this.posterUrl = poster
                            this.quality = quality
                        }
                    }
                )
            }
        }

        Log.d(TAG, "parseListing(): ${results.size} results")
        return results.distinctBy { it.url }
    }

    private fun isNonContentUrl(href: String): Boolean {
        return href.contains("/category/", ignoreCase = true) ||
            href.contains("/page/", ignoreCase = true) ||
            href.contains("/wp-", ignoreCase = true) ||
            href.contains("/feed/", ignoreCase = true) ||
            href.contains("how-to-download", ignoreCase = true) ||
            href.contains("join-our-group", ignoreCase = true) ||
            href.contains("request-a-movie", ignoreCase = true) ||
            href.contains("disclaimer", ignoreCase = true) ||
            href.contains("privacy-policy", ignoreCase = true) ||
            href.contains("contact", ignoreCase = true) ||
            (href.contains(mainUrl.trimEnd('/') + "/", ignoreCase = true) &&
                href.replace(mainUrl, "").trimEnd('/').isEmpty())
    }

    private fun isNonContentImage(src: String): Boolean {
        return src.contains("logo", ignoreCase = true) ||
            src.contains("favicon", ignoreCase = true) ||
            src.contains("badge", ignoreCase = true) ||
            src.contains("banner", ignoreCase = true) ||
            src.contains("wp-content/themes", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // load() - detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val response = resilientGet(url)
        val doc = response.document

        // Dead page detection: WordPress redirects non-existent pages
        // to a category listing instead of 404
        if (isDeadPage(doc, url)) {
            throw Exception("Page not found (dead/redirected): $url")
        }

        // Title from h3 heading or og:title
        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)

        // Poster: prefer TMDB image, fallback to og:image
        val posterUrl = doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img.aligncenter")?.let { fixUrlNull(it.attr("src")) }

        // Plot from og:description or longest paragraph in post-body
        val postBody = doc.selectFirst("div.post-body")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: postBody?.select("p")?.firstOrNull { it.text().length > 100 }?.text()?.trim()

        // Score from post-body only
        var score: Score? = null
        val metaText = postBody?.text() ?: ""
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        // Tags from the POST'S OWN hashtag links only.
        // The hashtags are in: <div class="w-full my-4 text-center"> which sits
        // right after div.post-body, inside the content column.
        // Do NOT use doc.select("a[href*=/category/]") — that picks up sidebar
        // categories (including "WEB-Series", "TV-Shows") causing false positives.
        val tags = doc.select("div.w-full.my-4.text-center a[href*=/category/]")
            .map { it.text().trim().removePrefix("# ").trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()
            .ifEmpty {
                // Fallback: try meta article:section
                doc.selectFirst("meta[property=article:section]")?.attr("content")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
            }

        // Year from title
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()

        // IMDB link
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")

        // ---------------------------------------------------------------
        // Series detection — SCOPED to post-body ONLY
        //
        // MoviesCounter movie pages have THREE contamination sources:
        //   1. Navigation menu: has "WEB-Series", "TV-Shows" links
        //   2. Sidebar: has "Categories" widget with ALL site categories
        //   3. "You May Also Like": has series cards with "Season 1" etc.
        //
        // ALL series keywords on movie pages come from these sections,
        // NOT from the post's own content. We scope everything to:
        //   - div.post-body (for h2 "Single Episode" check)
        //   - div.w-full.my-4.text-center (for hashtag category check)
        // ---------------------------------------------------------------

        // Check 1: Title/URL-based detection
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)

        // Check 2: Post's OWN category hashtags only (not sidebar/nav)
        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
            it.equals("TV-Shows", true) ||
            it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }

        // Check 3: "Single Episode" section header INSIDE post-body only
        // This is the DEFINITIVE marker — it ONLY appears on series pages
        val hasSingleEpisodeSection = postBody?.select("h2")?.any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        } ?: false

        val isSeries = titleIndicatesSeries || tagIndicatesSeries ||
            hasSingleEpisodeSection

        // Season number from title
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries " +
            "titleIndicates=$titleIndicatesSeries tagIndicates=$tagIndicatesSeries " +
            "singleSection=$hasSingleEpisodeSection tags=$tags season=$seasonNum")

        // Use postBody for all download link parsing (clean, no contamination)
        val container = postBody ?: doc.selectFirst("article") ?: doc

        if (isSeries) {
            val episodes = buildSeriesEpisodes(container, seasonNum)
            Log.d(TAG, "load() built ${episodes.size} series episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                val links = collectAllDownloadLinks(container)
                if (links.isNotEmpty()) {
                    listOf(newEpisode(links.joinToString("\n") { it.second }) {
                        name = "All Episodes"
                        season = seasonNum
                        episode = 1
                    })
                } else emptyList()
            } else {
                episodes
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags.ifEmpty { null }
                this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        } else {
            // For MOVIES: collect ALL quality download links.
            // User sees "Play Movie" button → clicking it shows ALL quality
            // options (480p, 720p, 1080p, 4K) as separate source links.
            val links = collectAllDownloadLinks(container)
            val data = links.joinToString("\n") { it.second }
            Log.d(TAG, "load() found ${links.size} download link(s) for movie")

            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags.ifEmpty { null }
                this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Download link collection
    // ------------------------------------------------------------------

    private fun collectAllDownloadLinks(
        container: Element
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Walk h3/h4 headings that contain download links
        container.select("h3, h4").forEach { heading ->
            val headingText = heading.text().trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach
            if (PACK_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach

            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()
            if (href == null || !href.startsWith("http")) return@forEach
            if (href in seen) return@forEach
            if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
            if (href.contains(mainUrl, ignoreCase = true)) return@forEach

            seen.add(href)
            val qualityLabel = extractQualityLabel(headingText)
            results.add(Pair(qualityLabel, href))
        }

        // Fallback: if no heading links found, try all <a> in the container
        if (results.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
                if (href.contains(mainUrl, ignoreCase = true)) return@forEach

                seen.add(href)
                val text = anchor.text().trim()
                val qualityLabel = extractQualityLabel(text)
                results.add(Pair(qualityLabel, href))
            }
        }

        Log.d(TAG, "collectAllDownloadLinks(): ${results.size} links")
        return results
    }

    // ------------------------------------------------------------------
    // Series episode parsing
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val hasSingleEpisodeSection = container.select("h2").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        val hasEpisodeHeaders = container.select("h4, h3").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text()) &&
                el.select("a[href]").isEmpty()
        }

        Log.d(TAG, "buildSeriesEpisodes: singleEpSection=$hasSingleEpisodeSection " +
            "epHeaders=$hasEpisodeHeaders")

        return if (hasSingleEpisodeSection || hasEpisodeHeaders) {
            buildPerEpisodeLayout(container, defaultSeason)
        } else {
            buildPackEpisodes(container, defaultSeason)
        }
    }

    /**
     * Per-episode layout: Walk h2/h3/h4 in document order, track current
     * episode from "EPiSODE N" headers, bucket download links under it.
     * Each Episode contains ALL quality links for that episode.
     * SKIP the "Full Pack" section entirely.
     */
    private fun buildPerEpisodeLayout(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<Pair<String, String>>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var pastPackSection = false

        val downloadElements = container.select("h2, h3, h4, hr, p")

        for (element in downloadElements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            if (tag == "h2" && SINGLE_EP_SECTION_REGEX.containsMatchIn(text)) {
                pastPackSection = true
                continue
            }

            if (tag == "h2" && text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                continue
            }

            val epMatch = EPISODE_HEADER_REGEX.find(text)
            if (epMatch != null && element.select("a[href]").isEmpty()) {
                val epNum = (epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    pastPackSection = true
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    continue
                }
            }

            if (!pastPackSection) continue

            if (currentEpisode != null) {
                val qualityLabel = extractQualityLabel(text)

                element.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") &&
                        !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                        !href.contains(mainUrl, ignoreCase = true) &&
                        !PACK_REGEX.containsMatchIn(text)
                    ) {
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        if (bucket.none { it.second == href }) {
                            bucket.add(Pair(qualityLabel, href))
                        }
                    }
                }
            }
        }

        if (episodeMap.isEmpty()) return emptyList()

        return episodeMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, links) ->
                val (season, ep) = key
                val data = links.joinToString("\n") { (ql, url) -> "$ql|$url" }
                newEpisode(data) {
                    name = "Episode $ep"
                    this.season = season
                    this.episode = ep
                }
            }
    }

    /**
     * Pack-only fallback: No EPiSODE headers found.
     * Each quality heading becomes its own episode entry.
     */
    private fun buildPackEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        container.select("h3, h4").forEach { heading ->
            val text = heading.text().trim()
            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(text)) return@forEach
            if (PACK_REGEX.containsMatchIn(text)) return@forEach

            if (href != null && href.startsWith("http") &&
                !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                !href.contains(mainUrl, ignoreCase = true)
            ) {
                val qualityLabel = extractQualityLabel(text)
                val sizeStr = SIZE_REGEX.find(text)?.groupValues?.get(1) ?: ""

                val epName = buildString {
                    append(qualityLabel)
                    if (sizeStr.isNotBlank()) append(" ($sizeStr)")
                }

                episodes.add(newEpisode(href) {
                    name = epName
                    season = defaultSeason
                    episode = episodes.size + 1
                })
            }
        }

        if (episodes.isEmpty()) {
            val allLinks = collectAllDownloadLinks(container)
            allLinks.forEachIndexed { idx, (ql, link) ->
                episodes.add(newEpisode(link) {
                    name = ql
                    season = defaultSeason
                    episode = idx + 1
                })
            }
        }

        return episodes
    }

    // ------------------------------------------------------------------
    // loadLinks — resolve URLs to playable streams
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val entries = data.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].startsWith("http")) {
                    Pair(parts[0].trim(), parts[1].trim())
                } else if (line.startsWith("http")) {
                    Pair("", line)
                } else {
                    null
                }
            }
            .filterNotNull()

        val urls = entries.map { it.second }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")
        var anySuccess = false

        urls.amap { url ->
            try {
                val resolved = resolveRedirector(url)

                if (resolved.isEmpty()) {
                    if (!loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        val qualityLabel = entries.find { it.second == url }?.first ?: ""
                        addDirectLink(url, callback, qualityLabel)
                    }
                    anySuccess = true
                } else {
                    resolved.amap { resolvedUrl ->
                        try {
                            if (!loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)) {
                                val qualityLabel = entries.find { it.second == url }?.first ?: ""
                                addDirectLink(resolvedUrl, callback, qualityLabel)
                            }
                            anySuccess = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Extraction failed for $resolvedUrl: ${e.message}")
                            addDirectLink(resolvedUrl, callback)
                            anySuccess = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve $url: ${e.message}")
                try {
                    if (loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        anySuccess = true
                    }
                } catch (_: Exception) {}
            }
        }

        return anySuccess
    }

    // ------------------------------------------------------------------
    // Redirector resolution
    // ------------------------------------------------------------------

    private suspend fun resolveRedirector(url: String): List<String> {
        val isHubcdn = url.contains("hubcdn.org", ignoreCase = true) ||
            url.contains("hubcdn.sbs", ignoreCase = true)
        val isMclinks = url.contains("mclinks.xyz", ignoreCase = true)

        if (!isHubcdn && !isMclinks) return emptyList()

        return try {
            if (isHubcdn) resolveHubcdn(url) else resolveMclinks(url)
        } catch (e: Exception) {
            Log.w(TAG, "resolveRedirector failed for $url: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveHubcdn(url: String): List<String> {
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            Log.d(TAG, "resolveHubcdn: extracted link param: $linkParam")
            return listOf(java.net.URLDecoder.decode(linkParam, "UTF-8"))
        }

        try {
            val res = app.get(url, headers = headers, allowRedirects = false, timeout = 10000)
            val location = res.headers["Location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                Log.d(TAG, "resolveHubcdn: redirect to $location")
                return listOf(location)
            }
        } catch (_: Exception) {}

        return try {
            val doc = app.get(url, headers = headers, timeout = 10000).document
            val results = mutableListOf<String>()
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("hubcdn", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }
            Log.d(TAG, "resolveHubcdn: found ${results.size} link(s)")
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveMclinks(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            if (metaRefresh != null) {
                val content = metaRefresh.attr("content")
                val urlMatch = Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                if (urlMatch != null) {
                    results.add(urlMatch)
                    Log.d(TAG, "resolveMclinks: meta-refresh to $urlMatch")
                    return results
                }
            }

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("mclinks.xyz", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            doc.select("script").forEach { script ->
                val jsUrl = Regex("""(?:window\.)?location\s*[=:]\s*["']([^"']+)["']""")
                    .find(script.data())?.groupValues?.get(1)
                if (jsUrl != null && jsUrl.startsWith("http")) {
                    results.add(jsUrl)
                }
            }

            Log.d(TAG, "resolveMclinks: found ${results.size} link(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveMclinks failed: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Utility functions
    // ------------------------------------------------------------------

    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityLabel: String = "",
        sourceName: String? = null
    ) {
        val quality = when {
            qualityLabel.contains("4K", ignoreCase = true) ||
                qualityLabel.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            qualityLabel.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            qualityLabel.contains("720p", ignoreCase = true) -> Qualities.P720.value
            qualityLabel.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            url.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        val displayName = if (qualityLabel.isNotBlank()) qualityLabel else (sourceName ?: "Direct")

        callback(
            newExtractorLink(
                sourceName ?: name,
                displayName,
                url,
                INFER_TYPE
            ) {
                this.quality = quality
                this.referer = mainUrl
            }
        )
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\|\s*Full Movie\s*$"""), "")
            .replace(Regex("""\s*\|\s*Zee5 Series\s*$"""), "")
            .replace(Regex("""\s*\|\s*Full Series\s*$"""), "")
            .replace(Regex("""\s*\|\s*NF Series\s*$"""), "")
            .replace(Regex("""\s*[-|]\s*Movies Counter\s*$"""), "")
            .replace(Regex("""\s*[-–]\s*Movies Counter\s*$"""), "")
            .replace(Regex("""\s*DS4K\s+"""), " ")
            .replace(Regex("""\s*(WEB-DL|WEBRip|HDRip|BluRay|BRRip)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[(?:Hindi|DD5\.1|x264|HEVC|10Bit|ESubs)[^\]]*\]"""), "")
            .replace(Regex("""\d+p\s*(?:HEVC|x264|10Bit)?\s*[\[(]"""), "")
            .trim()
    }

    /**
     * Detect if a title/URL indicates a TV series.
     * Only uses title keywords and URL category — NO page body text check.
     */
    private fun detectSeriesFromTitle(title: String, href: String): Boolean {
        return title.contains("Season", ignoreCase = true) ||
            title.contains("WEB-Series", ignoreCase = true) ||
            title.contains("TV Series", ignoreCase = true) ||
            title.contains("Web Series", ignoreCase = true) ||
            title.contains("NF Series", ignoreCase = true) ||
            title.contains("Zee5 Series", ignoreCase = true) ||
            Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
            href.contains("/category/web-series/", ignoreCase = true) ||
            href.contains("/category/tv-shows/", ignoreCase = true)
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd", "300mb")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4K", ignoreCase = true) -> "4K"
            text.contains("1080p", ignoreCase = true) && text.contains("HEVC", ignoreCase = true) -> "1080p HEVC"
            text.contains("1080p", ignoreCase = true) && text.contains("x264", ignoreCase = true) -> "1080p x264"
            text.contains("1080p", ignoreCase = true) && text.contains("10Bit", ignoreCase = true) -> "1080p 10Bit"
            text.contains("1080p", ignoreCase = true) && text.contains("WEB-DL", ignoreCase = true) -> "1080p WEB-DL"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) && text.contains("HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720p", ignoreCase = true) && text.contains("x264", ignoreCase = true) -> "720p x264"
            text.contains("720p", ignoreCase = true) && text.contains("10Bit", ignoreCase = true) -> "720p 10Bit"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            text.contains("300MB", ignoreCase = true) -> "300MB"
            else -> "HD"
        }
    }
}
