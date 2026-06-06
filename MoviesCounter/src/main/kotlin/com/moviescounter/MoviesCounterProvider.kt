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
 * Structure:
 *   - Homepage / category / search: movie cards as <div class="inline-flex flex-col">
 *     containing <a href> with <img> (poster) and title in a nested <div>.
 *   - Pagination: <a class="next page-numbers" href="...">Next</a>
 *   - Detail pages (movies): title in <h3 class="text-gray-500">, poster from TMDB,
 *     download section with <h3>/<h4> headings per quality level.
 *   - Detail pages (series): TWO sections:
 *     (a) "Full Pack" quality-level downloads (same as movie pattern)
 *     (b) "Single Episode" section with <h4><strong>EPiSODE N</strong></h4>
 *         headers followed by quality rows with links per episode.
 *   - Download flow: MoviesCounter -> redirector (hubcdn.org, mclinks.xyz) or
 *     direct host (hubdrive.space) -> file hoster -> playable stream.
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
         * Known redirector/file host domains used by MoviesCounter.
         */
        private val KNOWN_HOST_REGEX = Regex(
            """(?i)(""" +
                    // MoviesCounter redirectors
                    """hubcdn\.org|hubcdn\.sbs|hubcdn|""" +
                    """mclinks\.xyz|mclinks|""" +
                    // HubCloud / Hubdrive family
                    """hubcloud\.[a-z]+|hubdrive\.space|hubdrive|hubstream\.art|hubstream|katdrive|""" +
                    // GDFlix family
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot|gdmirror|""" +
                    // Video streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // File sharing / cloud hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|""" +
                    // Hindi-dub specific hosts
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|techkit|""" +
                    // Common download hosts
                    """clicknupload|uploadflix|rapidgator|megaup|""" +
                    """uploadrar|nitroflare|turbobit|mega\.nz""" +
                    """)"""
        )

        /**
         * Hosts that are NEVER stream sources (social, navigation, ads, images).
         */
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

        /** Match quality labels in link text or heading text. */
        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p\s*(?:HEVC|x265|x264|10Bit|WEB-?DL)?|720p\s*(?:HEVC|x265|x264|10Bit)?|480p|300MB|DS4K)"""
        )

        /** Match "Episode N" / "EPiSODE N" headers. */
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )

        /** Match "Season N" / "S0N" in titles. */
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Regex to extract size like [510MB] or [1.3GB] */
        private val SIZE_REGEX = Regex("""\[([\d.]+(?:MB|GB|TB))\]""", RegexOption.IGNORE_CASE)

        /** Regex to match "PACK" keyword in headings — these are full-season downloads */
        private val PACK_REGEX = Regex("""(?i)\bPACK\b""")

        /** Regex to detect "Single Episode" section header */
        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

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
        val doc = app.get(url, headers = headers, timeout = 30).document
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
        val doc = app.get(url, headers = headers, timeout = 30).document
        return parseListing(doc)
    }

    /**
     * Parse search/listing results from a WordPress page.
     *
     * MoviesCounter cards are:
     *   <div class="w-5/12 sm:w-3/12 md:w-2/12 mx-1 md:mx-3 my-2 inline-flex flex-col">
     *     <a href="URL">
     *       <div><img src="POSTER" alt="TITLE"></div>
     *       <div class="transition-all ...">TITLE TEXT</div>
     *     </a>
     *   </div>
     */
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

        // Strategy 2: Fallback — any anchor with poster image (older theme / search results)
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
        val doc = app.get(url, headers = headers, timeout = 30).document

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

        // Plot from og:description or longest paragraph
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.select(".post-body p, article p")
                .firstOrNull { it.text().length > 100 }?.text()?.trim()

        // Score from page content
        var score: Score? = null
        val metaText = doc.select(".post-body, article").text()
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        // Tags from category links
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()

        // Year from title
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()

        // IMDB link
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")

        // ---------------------------------------------------------------
        // CRITICAL FIX: Series detection
        //
        // We use THREE indicators — but NOT the broad "EPiSODE in text" check
        // that was causing movies to be misdetected as series:
        //
        //   1. Title/URL pattern (Season, WEB-Series, etc.)
        //   2. Category tags (WEB-Series, TV-Shows)
        //   3. ACTUAL EPiSODE headers in the download section
        //      (not just the word "episode" appearing in description text)
        // ---------------------------------------------------------------

        // Check 1: Title/URL-based detection
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)

        // Check 2: Category tag-based detection
        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
            it.equals("TV-Shows", true) ||
            it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }

        // Check 3: Actual EPiSODE headers in the download section
        // This is the KEY fix — we look for <h4><strong>EPiSODE N</strong></h4>
        // NOT just the word "episode" anywhere in the page text
        val container = doc.selectFirst("article, .post-body") ?: doc
        val hasActualEpisodeHeaders = container.select("h4, h3").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text()) &&
                // The header must NOT also contain a download link (to distinguish
                // from quality lines like "720p – Episode 1 Drive")
                el.select("a[href]").isEmpty()
        }

        // Check 4: "Single Episode" section header exists
        val hasSingleEpisodeSection = container.select("h2").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        val isSeries = titleIndicatesSeries || tagIndicatesSeries ||
            hasActualEpisodeHeaders || hasSingleEpisodeSection

        // Season number from title
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries " +
            "titleIndicates=$titleIndicatesSeries tagIndicates=$tagIndicatesSeries " +
            "epHeaders=$hasActualEpisodeHeaders singleSection=$hasSingleEpisodeSection " +
            "season=$seasonNum")

        if (isSeries) {
            val episodes = buildSeriesEpisodes(container, seasonNum)
            Log.d(TAG, "load() built ${episodes.size} series episode(s)")

            // Fallback: if no episodes parsed, create one episode with all links
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
            // When user clicks Play, loadLinks() resolves each URL and shows
            // ALL available quality options (480p, 720p, 1080p, 4K) as separate links.
            // This means the movie page shows a simple Play button, and clicking it
            // reveals all quality choices — NOT displayed as "episodes" or "packs".
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

    /**
     * Collect all download links from a detail page.
     *
     * Returns list of (qualityLabel, downloadUrl) pairs.
     * For movies, all URLs are joined with newlines and passed as data to
     * newMovieLoadResponse. When the user clicks Play, loadLinks() resolves
     * each URL to show ALL quality options.
     *
     * This function parses both h3/h4 heading links AND fallback anchor links,
     * but SKIPS:
     *   - EPiSODE headers (those are for series per-episode parsing)
     *   - PACK links (full-season downloads like "1080p WEB-DL PACK [36GB]")
     *   - Self-referential links back to moviescounter
     *   - Social/navigation/image links
     */
    private fun collectAllDownloadLinks(
        container: Element
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Walk h3/h4 headings that contain download links
        container.select("h3, h4").forEach { heading ->
            val headingText = heading.text().trim()

            // Skip EPiSODE headings — only used for series per-episode layout
            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach

            // Skip PACK links — these are full-season downloads, not per-episode
            if (PACK_REGEX.containsMatchIn(headingText)) return@forEach

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

        // Fallback: if no heading links found, try all <a> in the post body
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

    /**
     * Build episodes for a series detail page.
     *
     * MoviesCounter series pages have TWO download sections:
     *
     * Section A — Full Pack (quality-level packs):
     *   <h2>: DOWNLOAD LINKS :</h2>
     *   <h3><a href="URL">480p x264 [1.1GB]</a></h3>
     *   <h4><em><a href="URL">720p HEVC [1.9GB]</a></em></h4>
     *   <h3><a href="URL">1080p x264 [6.5GB]</a></h3>
     *   <h4><a href="URL">4K SDR WEB-DL PACK [16.3GB]</a></h4>
     *
     * Section B — Single Episode (per-episode with quality):
     *   <h2>: Single Episode x264 Links :</h2>
     *   <h4><strong>EPiSODE 1</strong></h4>
     *   <h4>720p – <a href="URL">Drive</a>  Instant  <a href="URL">WATCH</a></h4>
     *   <h4>1080p – <a href="URL">Drive</a>  Instant</h4>
     *   <h4><strong>EPiSODE 2</strong></h4>
     *   <h4>720p – <a href="URL">Drive</a>  Instant  <a href="URL">WATCH</a></h4>
     *   <h4>1080p – <a href="URL">Drive</a>  Instant</h4>
     *
     * Strategy:
     *   - If "Single Episode" section exists with EPiSODE headers, use per-episode
     *     layout where each episode has ALL quality links inside it.
     *   - If only "Full Pack" section exists (no EPiSODE headers), treat each
     *     quality link as a separate episode (fallback behavior).
     */
    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        // Check if there's a "Single Episode" section or actual EPiSODE headers
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
            // No episode headers — treat each quality as a separate entry
            buildPackEpisodes(container, defaultSeason)
        }
    }

    /**
     * Per-episode layout: Walk elements in document order, track the current
     * (season, episode) from "EPiSODE N" headers, and bucket every download
     * link under the current cursor. Each Episode stores ALL quality URLs
     * as newline-separated data, so the user sees all quality options
     * (480p, 720p, 1080p) when they pick an episode.
     *
     * CRITICAL: We also extract the quality label from each link line
     * (e.g., "720p – Drive", "1080p – Instant") so loadLinks() can
     * create properly labeled extractor links.
     *
     * We SKIP the "Full Pack" section entirely — only links after the
     * first EPiSODE header (or after the "Single Episode" h2) are collected.
     */
    private fun buildPerEpisodeLayout(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        // (season, episode) -> list of (qualityLabel, downloadUrl)
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<Pair<String, String>>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var pastPackSection = false  // true once we see "Single Episode" h2 or first EPiSODE

        // Get the direct children of the container in document order
        // Using allElements can cause duplicates from nested tags like <strong>
        // Instead, walk only the top-level block-level children
        val downloadElements = container.select("h2, h3, h4, hr, p")

        for (element in downloadElements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            // Detect "Single Episode" section header
            if (tag == "h2" && SINGLE_EP_SECTION_REGEX.containsMatchIn(text)) {
                pastPackSection = true
                Log.d(TAG, "buildPerEpisodeLayout: found Single Episode section")
                continue
            }

            // Detect "DOWNLOAD LINKS" section header (pack section) — skip until past it
            if (tag == "h2" && text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                // We're in the pack section — don't collect links yet
                continue
            }

            // Check for episode marker: "EPiSODE 1", "Episode 2", etc.
            val epMatch = EPISODE_HEADER_REGEX.find(text)
            if (epMatch != null && element.select("a[href]").isEmpty()) {
                val epNum = (epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    pastPackSection = true
                    // Season might also be in the same header
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    Log.d(TAG, "buildPerEpisodeLayout: episode header -> S${currentSeason}E${currentEpisode}")
                    continue
                }
            }

            // Only collect links after we've passed the pack section
            // (i.e., after "Single Episode" h2 or after the first EPiSODE header)
            if (!pastPackSection) continue

            // Collect download links from this element, bucket under current episode
            if (currentEpisode != null) {
                val qualityLabel = extractQualityLabel(text)

                element.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") &&
                        !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                        !href.contains(mainUrl, ignoreCase = true) &&
                        !PACK_REGEX.containsMatchIn(text)  // Skip PACK links even in episode section
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

        // Build Episode objects — each episode contains ALL its quality links
        return episodeMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, links) ->
                val (season, ep) = key
                // Data format: qualityLabel|URL per line
                // This allows loadLinks() to parse quality labels for each link
                val data = links.joinToString("\n") { (ql, url) -> "$ql|$url" }
                newEpisode(data) {
                    name = "Episode $ep"
                    this.season = season
                    this.episode = ep
                }
            }
    }

    /**
     * Pack-only layout: No episode headers found. Each quality heading
     * (with a download link) becomes its own entry.
     * This is a FALLBACK for series pages that only have pack downloads.
     */
    private fun buildPackEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Walk h3/h4 headings that contain quality labels and download links
        container.select("h3, h4").forEach { heading ->
            val text = heading.text().trim()
            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()

            // Skip EPiSODE headings
            if (EPISODE_HEADER_REGEX.containsMatchIn(text)) return@forEach

            // Skip PACK links
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

        // Fallback: try all download links on the page
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

        // Parse data lines — format may be:
        //   Simple URL: "https://..."
        //   Quality-labeled: "720p|https://..."
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
                // Step 1: Try resolving redirectors
                val resolved = resolveRedirector(url)

                if (resolved.isEmpty()) {
                    // Direct URL — try loadExtractor, then direct link
                    if (!loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        val qualityLabel = entries.find { it.second == url }?.first ?: ""
                        addDirectLink(url, callback, qualityLabel)
                    }
                    anySuccess = true
                } else {
                    // Got resolved URLs from redirector
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
                // Last resort: try loadExtractor directly
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

    /**
     * Resolve redirector URLs (hubcdn.org, hubcdn.sbs, mclinks.xyz)
     * to actual host links.
     */
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

    /**
     * Resolve hubcdn.org / hubcdn.sbs redirector.
     * URL format: https://hubcdn.org/dl/?link=https://hub.obsession.buzz/XXXX
     */
    private suspend fun resolveHubcdn(url: String): List<String> {
        // Try extracting the link param first
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            Log.d(TAG, "resolveHubcdn: extracted link param: $linkParam")
            return listOf(java.net.URLDecoder.decode(linkParam, "UTF-8"))
        }

        // Follow the redirect chain
        try {
            val res = app.get(url, headers = headers, allowRedirects = false, timeout = 10000)
            val location = res.headers["Location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                Log.d(TAG, "resolveHubcdn: redirect to $location")
                return listOf(location)
            }
        } catch (_: Exception) {}

        // Fetch the page and extract links
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

    /**
     * Resolve mclinks.xyz redirector.
     * URL format: https://mclinks.xyz/archives/NNNNN
     */
    private suspend fun resolveMclinks(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            // Look for meta refresh redirect
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

            // Look for links in the page body
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

            // Also check for JS redirects
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

    /**
     * Add a direct video link when loadExtractor can't handle the host.
     * Uses qualityLabel from the download section if available.
     */
    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityLabel: String = "",
        sourceName: String? = null
    ) {
        // Determine quality from label first, then URL pattern
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

        // Build display name from quality label
        val displayName = if (qualityLabel.isNotBlank()) {
            qualityLabel
        } else {
            sourceName ?: "Direct"
        }

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
     * Uses title keywords and URL category patterns ONLY — does NOT
     * check page body text for "episode" (too many false positives).
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
