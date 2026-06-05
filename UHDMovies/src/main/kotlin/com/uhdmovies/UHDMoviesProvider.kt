package com.uhdmovies

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
import com.lagradost.cloudstream3.fixUrl
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
 * UHDMovies provider for CloudStream.
 *
 * Site structure (DataLife Engine / DLE):
 *   - Homepage / category / search pages: movie cards as `article.post-item` elements
 *     containing `a.blog-img > img.blog-picture` (poster) and `h3.entry-title > a` (title).
 *   - Detail pages: title in `og:title` or `<title>` (strip " Free Download UHDMovies"),
 *     poster in `og:image`, plot in `div.entry-content`, download section in
 *     `div.download-links-div` with `h3 > span` quality labels followed by `a.btn`
 *     download buttons.
 *   - Download flow: UHDMovies detail page → `a.btn` button → intermediate
 *     redirector (nexdrive.help / nexdrive.vip) → actual file hoster links
 *     (gdrive, hubcloud, etc.).
 *
 * This provider follows proven patterns from MkvHub/KatMovieHD providers:
 *   - Domain fallback across multiple known TLDs
 *   - Proper CloudStream API types (SearchQuality, Score)
 *   - Parallel link resolution via `amap`
 *   - Robust error handling with diagnostic logging
 *   - Quality-aware download link parsing with size extraction
 */
class UHDMoviesProvider : MainAPI() {
    override var mainUrl = "https://hdmovieplus4u.com"
    override var name = "UHDMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TAG = "UHDMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /**
         * Known UHDMovies domain TLDs — the site frequently changes domains.
         * On domain failure, each alternative is tried in order.
         */
        private val DOMAIN_TLDS = listOf(
            "hdmovieplus4u.com",
            "uhdmovies.site",
            "uhdmovies.foo",
            "uhdmovies.tube"
        )

        // ------------------------------------------------------------------
        // Regexes
        // ------------------------------------------------------------------

        /** Match season numbers from headings like "S05" or "Season 5" */
        private val SEASON_REGEX = Regex("""(?i)(?:S(\d{1,2})\b|Season\s*(\d{1,2}))""")

        /** Match episode numbers from headings like "S05E01", "E01", "Episode 01" */
        private val EPISODE_REGEX =
            Regex("""(?i)(?:Ep(?:isode)?\s*[-:#]?\s*(\d{1,3})\b|E(\d{1,3})\b)""")

        /** Match quality labels in text */
        private val QUALITY_REGEX = Regex("""(?i)(2160p|4[Kk]|1080p|720p|480p)""")

        /** Match year from title */
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

        /** Match size from button text like [590MB] or [3.6GB] */
        private val SIZE_REGEX = Regex("""(?i)\[([\d.]+\s*(?:MB|GB))\]""")

        /** Match IMDB rating */
        private val IMDB_RATING_REGEX = Regex("""(\d+\.?\d*)/10""")

        /** Nexdrive redirector domains */
        private val NEXDRIVE_REGEX = Regex("""(?i)nexdrive\.(help|vip)""")

        /**
         * Phrases that indicate a heading is NOT an episode marker even if it
         * contains numbers (e.g. "more episodes will be added").
         */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list",
            "all episodes", "single episodes link"
        )

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
                    """hdmovieplus4u\.com|uhdmovies\.(?:site|foo|tube)|""" +
                    """gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|""" +
                    """nexdrive\.(?:help|vip)|""" +
                    """doubleclick|popads|propeller|profitablecpmrate""" +
                    """)"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/dual-audio-hindi-english-movies/" to "Dual Audio Hindi",
        "$mainUrl/dual-audio-hindi-english-movies/south-indian-dubbed-movies-download/" to "South Indian Hindi",
        "$mainUrl/tv-shows/web-series-hindi/" to "Web Series Hindi",
        "$mainUrl/tv-shows/hindi-dubbed-tv-shows/" to "Hindi Dubbed TV"
    )

    // ------------------------------------------------------------------
    // Domain fallback
    // ------------------------------------------------------------------

    /**
     * Attempt to fetch a URL, falling back through alternate domain TLDs
     * if the primary domain fails. Returns the successful response or throws.
     */
    private suspend fun fetchWithDomainFallback(url: String): org.jsoup.nodes.Document {
        return try {
            val doc = app.get(url, headers = headers, timeout = 30).document
            // Check that we got a valid page (not a domain-parking redirect)
            val titleText = doc.selectFirst("title")?.text()?.trim().orEmpty()
            // If the title looks like a parking page, try alternate domains
            if (titleText.contains("Domain for sale", ignoreCase = true) ||
                titleText.contains("This domain is for sale", ignoreCase = true) ||
                titleText.contains("Suspended", ignoreCase = true)
            ) {
                Log.w(TAG, "Domain parking detected for $url, trying fallbacks")
                throw Exception("Domain parking page detected")
            }
            doc
        } catch (e: Exception) {
            Log.w(TAG, "Primary domain failed for $url: ${e.message}, trying fallbacks")
            tryAlternateDomains(url)
        }
    }

    /**
     * Try fetching the same path on each alternate domain TLD.
     */
    private suspend fun tryAlternateDomains(originalUrl: String): org.jsoup.nodes.Document {
        val path = Regex("""https?://[^/]+(.*)""").find(originalUrl)?.groupValues?.get(1)
            ?: throw Exception("Cannot parse URL path: $originalUrl")

        for (tld in DOMAIN_TLDS) {
            val altUrl = "https://$tld$path"
            // Skip if this is the same domain that already failed
            if (altUrl == originalUrl) continue
            try {
                Log.d(TAG, "Trying alternate domain: $altUrl")
                val doc = app.get(altUrl, headers = headers, timeout = 30).document
                val titleText = doc.selectFirst("title")?.text()?.trim().orEmpty()
                if (!titleText.contains("Domain for sale", ignoreCase = true) &&
                    !titleText.contains("This domain is for sale", ignoreCase = true) &&
                    !titleText.contains("Suspended", ignoreCase = true)
                ) {
                    Log.d(TAG, "Alternate domain succeeded: $altUrl")
                    // Update mainUrl so subsequent requests use the working domain
                    mainUrl = "https://$tld"
                    return doc
                }
            } catch (e: Exception) {
                Log.w(TAG, "Alternate domain $tld also failed: ${e.message}")
            }
        }
        throw Exception("All domain TLDs failed for path: $path")
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // DLE pagination: homepage uses /page/N/, categories use /category/page/N/
        val url = if (page == 1) {
            request.data
        } else {
            val base = request.data.trimEnd('/')
            "$base/page/$page/"
        }
        val doc = fetchWithDomainFallback(url)
        val items = doc.select("article.post-item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null ||
                doc.selectFirst("nav.navigation a:not(.prev)") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // DLE search format
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = fetchWithDomainFallback(searchUrl)
        return doc.select("article.post-item").mapNotNull { it.toSearchResult() }
    }

    /**
     * Parse an `article.post-item` card element into a SearchResponse.
     *
     * HTML structure (verified against live DLE site):
     *   <article class="post-item site__col">
     *     <a class="blog-img img-box" href="DETAIL_URL">
     *       <img class="blog-picture" src="/uploads/posts/covers/IMAGE.webp" alt="TITLE">
     *     </a>
     *     <h3 class="entry-title h5 h6-mobile post-title">
     *       <a href="DETAIL_URL" title="TITLE">TITLE</a>
     *     </h3>
     *   </article>
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.entry-title a") ?: selectFirst("h3 a") ?: return null
        val title = titleEl.attr("title")
            .ifBlank { titleEl.text() }
            .trim()
            .ifBlank { return null }
        val href = fixUrlNull(titleEl.attr("href")) ?: return null

        // Poster: img.blog-picture with relative URL (needs fixUrl)
        val imgEl = selectFirst("img.blog-picture") ?: selectFirst("img")
        val posterUrl = imgEl?.let {
            val src = it.attr("src").trim()
            if (src.startsWith("/")) fixUrl(src) else fixUrlNull(src)
        }

        val isSeries = SEASON_REGEX.containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                title.contains("[ALL EPISODES]", ignoreCase = true) ||
                title.contains("Web Series", ignoreCase = true) ||
                title.contains("TV Series", ignoreCase = true) ||
                href.contains("/tv-shows/")

        val quality = detectSearchQuality(title)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
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

    // ------------------------------------------------------------------
    // load() — detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchWithDomainFallback(url)

        // Title: from og:title or <title> (strip site suffix)
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: doc.selectFirst("h1, h2")?.text()?.trim()
            ?: throw Exception("No title found at $url")

        // Strip common UHDMovies title suffixes
        val title = rawTitle
            .replace(Regex("""\s*Free\s+Download\s+UHDMovies\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*UHDMovies\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*UHDMovies\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

        // Poster: from og:image meta tag (DLE sites typically set this)
        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.entry-content img")?.attr("src")
        )

        // Parse content area
        val entryContent = doc.selectFirst("div.entry-content") ?: doc

        // Plot: first long paragraph before download section
        var plot = ""
        entryContent.select("p").forEach { p ->
            val text = p.text().trim()
            // Skip short lines, movie metadata lines, and download section content
            if (text.length < 80) return@forEach
            if (text.contains("Download Links", ignoreCase = true)) return@forEach
            if (text.startsWith("Size:", ignoreCase = true)) return@forEach
            if (text.startsWith("Format:", ignoreCase = true)) return@forEach
            if (text.startsWith("Runtime:", ignoreCase = true)) return@forEach
            if (text.startsWith("Quality:", ignoreCase = true)) return@forEach
            if (text.startsWith("Language:", ignoreCase = true)) return@forEach
            if (text.startsWith("Genres:", ignoreCase = true)) return@forEach
            if (text.startsWith("Cast:", ignoreCase = true)) return@forEach
            if (text.startsWith("Subtitle:", ignoreCase = true)) return@forEach
            if (text.startsWith("IMDb", ignoreCase = true)) return@forEach
            if (plot.isBlank()) {
                plot = text
            }
        }

        // Fallback: og:description for plot
        if (plot.isBlank()) {
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            if (!ogDesc.isNullOrBlank()) {
                plot = ogDesc.trim()
            }
        }

        // Score: look for IMDB rating in content
        var score: Score? = null
        entryContent.select("p, span, div").forEach { el ->
            val text = el.text().trim()
            if (text.startsWith("IMDb", ignoreCase = true) || text.contains("IMDb Rating", ignoreCase = true)) {
                IMDB_RATING_REGEX.find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    score = Score.from10(it)
                }
            }
        }

        // Tags: from Genres line in entry-content
        val tags = mutableListOf<String>()
        entryContent.select("p, span, div").forEach { el ->
            val text = el.text().trim()
            if (text.startsWith("Genres", ignoreCase = true)) {
                val genreStr = text.substringAfter(":").trim()
                genreStr.split(",").map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { tags.add(it) }
            }
        }

        // Year from title
        val year = YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Series detection
        val isSeries = SEASON_REGEX.containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("[ALL EPISODES]", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                title.contains("Web Series", ignoreCase = true) ||
                title.contains("TV Series", ignoreCase = true) ||
                url.contains("/tv-shows/") ||
                tags.any {
                    it.equals("Web Series", ignoreCase = true) ||
                    it.equals("TV Show", true) ||
                    it.equals("TV Shows", true)
                }

        // Season number from title
        val titleSeason = SEASON_REGEX.find(title)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries year=$year season=$titleSeason")

        if (isSeries) {
            // For series: parse download-links-div and create per-quality episodes
            val episodes = buildSeriesEpisodes(entryContent, title, titleSeason)
            Log.d(TAG, "load() built ${episodes.size} series episode(s)")

            // Fallback: if no episodes found, create a single generic one
            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(newEpisode(url) { name = "Watch" })
            } else {
                episodes
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { entryContent.text().take(500) }
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            // For movies: collect all quality download URLs with quality info
            // encoded as "QUALITY_LABEL|URL" pairs, newline-joined
            val links = collectDownloadLinks(entryContent)
            Log.d(TAG, "load() found ${links.lines().filter { it.isNotBlank() }.size} download URL(s)")

            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { entryContent.text().take(500) }
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    // ------------------------------------------------------------------
    // Download link parsing — the CRITICAL part
    // ------------------------------------------------------------------

    /**
     * Data class to hold parsed download link info.
     * qualityLabel: "480p", "720p x265 HEVC", "1080p x264", "2160p", etc.
     * url: the download URL (nexdrive.help/...)
     * size: extracted size string like "590MB", "3.6GB" or empty
     */
    private data class DownloadLink(
        val qualityLabel: String,
        val url: String,
        val size: String
    )

    /**
     * Parse `<div class="download-links-div">` to extract quality label + URL pairs.
     *
     * Expected HTML structure (VERY CONSISTENT across all UHDMovies pages):
     *   <div class="download-links-div">
     *     <h3><span style="color: #ffffff;">480p</span></h3>
     *     <h3><div><a class="btn" href="https://nexdrive.help/genxfmXXXXX/">
     *       <button class="dwd-button">Click Here To Download [590MB]</button>
     *     </a></div></h3>
     *     <h3><span style="color: #ffffff;">720p x265 HEVC</span></h3>
     *     <h3><div><a class="btn" href="https://nexdrive.help/genxfmXXXXX/">
     *       <button class="dwd-button">Click Here To Download [920MB]</button>
     *     </a></div></h3>
     *     ...
     *   </div>
     *
     * Strategy:
     *  1. Find the download-links-div container
     *  2. Walk all h3 children in document order
     *  3. h3 containing a span (quality label) → record as current quality
     *  4. h3 containing an a.btn (download link) → pair with current quality
     *  5. Also try sibling-based pairing as fallback
     */
    private fun parseDownloadLinksDiv(container: Element): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()
        val downloadDiv = container.selectFirst("div.download-links-div") ?: return emptyList()

        var currentQuality = ""
        var currentSize = ""

        // Walk all direct h3 children of the download div
        val h3Elements = downloadDiv.select("h3")
        for (h3 in h3Elements) {
            // Check if this h3 contains a quality label span
            val span = h3.selectFirst("span")
            if (span != null) {
                val labelText = span.text().trim()
                // Only update quality if the span text looks like a quality label
                if (QUALITY_REGEX.containsMatchIn(labelText) ||
                    labelText.contains("x265", ignoreCase = true) ||
                    labelText.contains("x264", ignoreCase = true) ||
                    labelText.contains("HEVC", ignoreCase = true) ||
                    labelText.contains("HEVC 10bit", ignoreCase = true)
                ) {
                    currentQuality = labelText
                    currentSize = ""
                    continue
                }
            }

            // Check if this h3 contains a download link (a.btn)
            val anchor = h3.selectFirst("a.btn") ?: h3.selectFirst("a[href]")
            if (anchor != null) {
                val url = anchor.attr("href").trim()
                if (url.startsWith("http")) {
                    // Extract size from button text: "Click Here To Download [590MB]"
                    val buttonText = anchor.text().trim()
                    val sizeMatch = SIZE_REGEX.find(buttonText)
                    currentSize = sizeMatch?.groupValues?.get(1) ?: ""

                    // Use quality label we found, or try to detect from button text
                    val quality = if (currentQuality.isNotBlank()) {
                        currentQuality
                    } else {
                        detectQualityFromText(buttonText)
                    }

                    links.add(DownloadLink(quality, url, currentSize))
                    Log.d(TAG, "Parsed download link: quality='$quality' size='$currentSize' url='$url'")
                }
            }
        }

        return links
    }

    /**
     * Fallback: try to find download links without the download-links-div container.
     * Scans for a.btn buttons and h5 headings that might indicate the download section.
     */
    private fun parseDownloadLinksFallback(container: Element): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()

        // Look for the download section heading
        val downloadHeading = container.select("h5, h4, h3, h2").firstOrNull {
            it.text().contains("Download Links", ignoreCase = true)
        } ?: return emptyList()

        // Walk siblings after the heading looking for quality labels and links
        var currentQuality = ""
        var element = downloadHeading.nextElementSibling()
        var depth = 0

        while (element != null && depth < 50) {
            depth++
            val text = element.text().trim()

            // Check for quality label in heading
            if (element.tagName() in listOf("h2", "h3", "h4", "h5")) {
                if (QUALITY_REGEX.containsMatchIn(text)) {
                    currentQuality = text
                        .replace(Regex("""^[^0-9]*"""), "")  // Strip prefix before quality
                        .trim()
                    element = element.nextElementSibling()
                    continue
                }
            }

            // Check for download link
            val anchor = element.selectFirst("a.btn") ?: element.selectFirst("a[href]")
            if (anchor != null) {
                val url = anchor.attr("href").trim()
                if (url.startsWith("http")) {
                    val buttonText = anchor.text().trim()
                    val sizeMatch = SIZE_REGEX.find(buttonText)
                    val size = sizeMatch?.groupValues?.get(1) ?: ""

                    val quality = if (currentQuality.isNotBlank()) {
                        currentQuality
                    } else {
                        detectQualityFromText(buttonText)
                    }

                    links.add(DownloadLink(quality, url, size))
                    Log.d(TAG, "Fallback parsed: quality='$quality' size='$size' url='$url'")
                }
            }

            element = element.nextElementSibling()
        }

        return links
    }

    /**
     * Try to detect quality from arbitrary text (button text, heading, etc.)
     */
    private fun detectQualityFromText(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> "2160p"
            lower.contains("1080p") && lower.contains("x265") -> "1080p x265"
            lower.contains("1080p") && lower.contains("hevc") -> "1080p HEVC"
            lower.contains("1080p") && lower.contains("x264") -> "1080p x264"
            lower.contains("1080p") -> "1080p"
            lower.contains("720p") && lower.contains("x265") -> "720p x265 HEVC"
            lower.contains("720p") && lower.contains("hevc") -> "720p HEVC"
            lower.contains("720p") && lower.contains("x264") -> "720p x264"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> "HD"
        }
    }

    /**
     * Collect all download links from the page for a movie.
     * Returns newline-joined string of "QUALITY_LABEL|URL" pairs.
     * The pipe separator allows loadLinks() to extract quality info.
     */
    private fun collectDownloadLinks(container: Element): String {
        // Strategy 1: Parse download-links-div (primary, most reliable)
        var links = parseDownloadLinksDiv(container)

        // Strategy 2: Fallback parsing without download-links-div
        if (links.isEmpty()) {
            links = parseDownloadLinksFallback(container)
        }

        // Strategy 3: Last resort — find all a.btn or dwd-button links
        if (links.isEmpty()) {
            container.select("a.btn, a:has(button.dwd-button)").forEach { anchor ->
                val url = anchor.attr("href").trim()
                if (url.startsWith("http")) {
                    val buttonText = anchor.text().trim()
                    val quality = detectQualityFromText(buttonText)
                    val sizeMatch = SIZE_REGEX.find(buttonText)
                    val size = sizeMatch?.groupValues?.get(1) ?: ""
                    links.add(DownloadLink(quality, url, size))
                }
            }
        }

        Log.d(TAG, "collectDownloadLinks: found ${links.size} download link(s)")

        // Encode as "QUALITY_LABEL|URL" for quality-aware resolution in loadLinks
        return links.joinToString("\n") { "${it.qualityLabel}|${it.url}" }
    }

    // ------------------------------------------------------------------
    // Series episode parsing
    // ------------------------------------------------------------------

    /**
     * Build episodes for a series page. UHDMovies series pages bundle
     * all episodes for one season in a single page. The download-links-div
     * contains quality options (480p, 720p, 1080p, 2160p) for the whole
     * season pack.
     *
     * We create one "episode" per quality option so the user sees:
     *   - "480p All Episodes (590MB)"
     *   - "720p All Episodes (920MB)"
     *   - "1080p All Episodes (3.6GB)"
     *   - "2160p All Episodes (5.5GB)"
     */
    private fun buildSeriesEpisodes(
        container: Element,
        title: String,
        defaultSeason: Int
    ): List<Episode> {
        val downloadLinks = parseDownloadLinksDiv(container)
        if (downloadLinks.isEmpty()) {
            // Try fallback
            val fallbackLinks = parseDownloadLinksFallback(container)
            if (fallbackLinks.isEmpty()) return emptyList()
            return fallbackLinks.mapIndexed { idx, link ->
                val epName = buildEpisodeName(link.qualityLabel, link.size, true)
                newEpisode("${link.qualityLabel}|${link.url}") {
                    name = epName
                    season = defaultSeason
                    episode = idx + 1
                }
            }
        }

        return downloadLinks.mapIndexed { idx, link ->
            val epName = buildEpisodeName(link.qualityLabel, link.size, true)
            newEpisode("${link.qualityLabel}|${link.url}") {
                name = epName
                season = defaultSeason
                episode = idx + 1
            }
        }
    }

    /**
     * Build a human-readable episode name.
     * Example: "480p All Episodes (590MB)"
     */
    private fun buildEpisodeName(quality: String, size: String, isAllEpisodes: Boolean): String {
        return buildString {
            append(quality)
            if (isAllEpisodes) append(" All Episodes")
            if (size.isNotBlank()) append(" ($size)")
        }
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

        // Data format: newline-separated "QUALITY_LABEL|URL" pairs
        // Fall back to plain URLs if no pipe separator found
        val linkEntries = data.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("http") }
            .map { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else {
                    "" to line
                }
            }
            .filter { it.second.startsWith("http") }

        if (linkEntries.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${linkEntries.size} URL(s)")

        var anySuccess = false

        linkEntries.amap { (qualityLabel, url) ->
            try {
                // If URL is a nexdrive redirector, follow it to get the actual URL
                val resolvedUrls = resolveNexdrive(url)

                if (resolvedUrls.isEmpty()) {
                    // Direct URL — try loadExtractor first, then add as direct link
                    if (!tryLoadExtractor(url, subtitleCallback, callback)) {
                        addDirectLink(url, callback, qualityLabel)
                    }
                    anySuccess = true
                } else {
                    // Got destination URLs from nexdrive redirect
                    resolvedUrls.amap { resolvedUrl ->
                        try {
                            if (!tryLoadExtractor(resolvedUrl, subtitleCallback, callback)) {
                                addDirectLink(resolvedUrl, callback, qualityLabel)
                            }
                            anySuccess = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Hoster extraction failed for $resolvedUrl: ${e.message}")
                            addDirectLink(resolvedUrl, callback, qualityLabel)
                            anySuccess = true
                        }
                    }
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

    /**
     * Resolve a nexdrive.help / nexdrive.vip redirector URL.
     * These redirectors perform a simple HTTP redirect to the actual file host.
     * Follow redirects and return the final destination URL(s).
     *
     * Some nexdrive pages contain multiple mirror links on the landing page,
     * so we parse the HTML body for additional links if a simple redirect
     * doesn't yield a direct file URL.
     */
    private suspend fun resolveNexdrive(url: String): List<String> {
        if (!NEXDRIVE_REGEX.containsMatchIn(url)) return emptyList()

        return try {
            val res = app.get(url, referer = mainUrl, headers = headers, timeout = 15000)
            val doc = res.document
            val results = mutableListOf<String>()

            // Parse the response body for external links
            // (nexdrive pages list mirror/host links on the landing page)
            val excludeDomains = listOf("nexdrive.help", "nexdrive.vip") + "profitablecpmrate"
            doc.select("a[href]").mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                val linkText = anchor.text().trim()
                if (href.startsWith("http") &&
                    excludeDomains.none { href.contains(it, ignoreCase = true) } &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    linkText.isNotBlank()
                ) {
                    href
                } else null
            }.filter { it.startsWith("http") && it !in results }.forEach {
                results.add(it)
            }

            Log.d(TAG, "resolveNexdrive($url): found ${results.size} resolved URL(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveNexdrive failed for $url: ${e.message}")
            emptyList()
        }
    }

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
        val quality = when {
            url.contains("2160p", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            label.contains("2160p", ignoreCase = true) || label.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720p", ignoreCase = true) -> Qualities.P720.value
            label.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.P720.value
        }

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
}
