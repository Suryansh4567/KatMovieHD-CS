package com.uhdmovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

/**
 * UHDMovies provider for CloudStream.
 *
 * Site: hdmovieplus4u.com (DataLife Engine CMS)
 *
 * Structure:
 *   - Homepage / category pages: movie cards as `article.post-item` elements
 *     with `h3.entry-title a` (title + link) and `img.blog-picture` (poster).
 *   - Pagination: `div.wp-pagenavi` with numbered page links (NO next/prev).
 *     URL format: `/page/N/` for homepage, `/category-slug/page/N/` for categories.
 *   - Detail pages: title in `h1.entry-title`, poster from `og:image`,
 *     download section in `div.download-links-div` with quality labels as
 *     `h3 > span` (e.g. "480p", "720p x265 HEVC") followed by `a.btn` download
 *     buttons containing `button.dwd-button` with size info.
 *   - Series pages: SAME structure as movies — no individual episode sections.
 *     Only quality-level packs are available. Each quality pack becomes its
 *     own "episode" entry in CloudStream.
 *   - Download flow: UHDMovies detail page → `a.btn` button → nexdrive.help
 *     redirector → actual file hoster links.
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
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** Regex to match quality labels in heading text or link text */
        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p\s*(?:HEVC|x265|x264|10Bit|WEB-?DL)?|720p\s*(?:HEVC|x265|x264|10Bit)?|480p|300MB)"""
        )

        /** Regex to extract size from button text like "[400MB]" or "[1.2GB]" */
        private val SIZE_REGEX = Regex("""\[([\d.]+(?:MB|GB|TB))\]""", RegexOption.IGNORE_CASE)
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/bollywood-movies/" to "Bollywood",
        "$mainUrl/hollywood-movies/" to "Hollywood",
        "$mainUrl/dual-audio-hindi-english-movies/" to "Dual Audio",
        "$mainUrl/dual-audio-hindi-english-movies/south-indian-dubbed-movies-download/" to "South Indian",
        "$mainUrl/tv-shows/web-series-hindi/" to "Web Series",
        "$mainUrl/tv-shows/hindi-dubbed-tv-shows/" to "Hindi Dubbed TV"
    )

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = doc.select("article.post-item").mapNotNull { it.toSearchResult() }

        // UHDMovies uses wp-pagenavi with numbered links (NO a.next class).
        // hasNext = true if there are any page links in the pagination div
        // pointing to a higher page number than the current one.
        val hasNext = doc.select("div.wp-pagenavi a").any { a ->
            a.text().trim().toIntOrNull()?.let { it > page } ?: false
        } || doc.select("div.wp-pagenavi a:last-child").isNotEmpty()

        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${
            java.net.URLEncoder.encode(query, "UTF-8")
        }"
        val doc = app.get(searchUrl, headers = headers, timeout = 30).document
        return doc.select("article.post-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.entry-title a") ?: return null
        val title = titleEl.text().trim().ifBlank { return null }
        val href = titleEl.attr("href").ifBlank { return null }
        val img = selectFirst("img.blog-picture")
        val poster = img?.let { fixUrl(it.attr("src")) }

        val isSeries = detectSeries(title, href)
        val quality = detectSearchQuality(title)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    // ------------------------------------------------------------------
    // load() — detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document

        // Title from h1.entry-title (primary) or og:title (fallback)
        val rawTitle = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Unknown"

        val title = cleanTitle(rawTitle)

        // Poster: prefer og:image (usually high quality TMDB image)
        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img[src*=image.tmdb.org]")?.let { fixUrl(it.attr("src")) }
            ?: doc.selectFirst("img.blog-picture")?.let { fixUrl(it.attr("src")) }

        // Plot from og:description or longest paragraph
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.select("div.entry-content p")
                .firstOrNull { it.text().length > 100 }?.text()?.trim()
            ?: ""

        // Year from title
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        // Tags from category links
        val tags = doc.select("div.speedbar a[href*=category], a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }
            .distinct()

        // Score from page content
        var score: Score? = null
        val metaText = doc.select("div.entry-content").text()
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        // Series detection
        val isSeries = detectSeries(title, url) ||
            tags.any { it.contains("web-series", true) || it.contains("tv-show", true) }

        val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries season=$seasonNum")

        // Parse download links with quality pairing
        val qualityLinks = parseDownloadLinks(doc)
        Log.d(TAG, "load() found ${qualityLinks.size} quality download link(s)")

        if (isSeries) {
            val episodes = if (qualityLinks.size > 1) {
                // Multiple quality options — create one Episode per quality
                // so user sees "480p", "720p HEVC", "1080p", "4K" as separate entries
                qualityLinks.mapIndexed { idx, (qualityLabel, sizeLabel, linkUrl) ->
                    val epName = buildString {
                        append(qualityLabel)
                        if (sizeLabel.isNotBlank()) append(" ($sizeLabel)")
                    }
                    newEpisode(linkUrl) {
                        name = epName
                        season = seasonNum
                        episode = idx + 1
                    }
                }
            } else if (qualityLinks.size == 1) {
                // Only one quality — single episode
                val (ql, sl, lu) = qualityLinks[0]
                val epName = buildString {
                    append(ql)
                    if (sl.isNotBlank()) append(" ($sl)")
                }
                listOf(newEpisode(lu) {
                    name = epName
                    season = seasonNum
                    episode = 1
                })
            } else {
                // Fallback: single "Watch" episode with the page URL
                listOf(newEpisode(url) { name = "Watch" })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags.ifEmpty { null }
                this.score = score
            }
        } else {
            // For movies: join all download URLs as newline-separated string.
            // When user clicks Play, loadLinks() resolves each URL and shows
            // ALL available quality options as separate playable links.
            val data = qualityLinks.joinToString("\n") { it.third }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags.ifEmpty { null }
                this.score = score
            }
        }
    }

    // ------------------------------------------------------------------
    // Download link parsing
    // ------------------------------------------------------------------

    /**
     * Parse download links from the detail page, pairing each quality label
     * with its corresponding download URL and size.
     *
     * UHDMovies download section structure:
     *   <div class="download-links-div">
     *     <h3><span>480p</span></h3>
     *     <h3><div><a class="btn" href="URL"><button>...[400MB]...</button></a></div></h3>
     *     <h3><span>720p x265 HEVC</span></h3>
     *     <h3><div><a class="btn" href="URL"><button>...[700MB]...</button></a></div></h3>
     *     ...
     *   </div>
     *
     * Returns list of (qualityLabel, sizeLabel, downloadUrl) triples.
     */
    private fun parseDownloadLinks(
        doc: org.jsoup.nodes.Document
    ): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        val seen = mutableSetOf<String>()

        // Primary: parse from div.download-links-div
        val downloadDiv = doc.selectFirst("div.download-links-div")
        if (downloadDiv != null) {
            var currentQuality = ""

            // Walk all h3 elements in the download div in document order
            downloadDiv.select("h3").forEach { h3 ->
                val h3Text = h3.text().trim()

                // Check if this h3 contains a quality label (inside a span)
                val spanText = h3.selectFirst("span")?.text()?.trim()
                if (spanText != null && QUALITY_REGEX.containsMatchIn(spanText)) {
                    currentQuality = spanText
                    return@forEach
                }

                // Also check if h3 text itself is a quality label
                if (currentQuality.isBlank() && QUALITY_REGEX.containsMatchIn(h3Text)) {
                    currentQuality = extractQualityFromText(h3Text)
                    return@forEach
                }

                // Check if this h3 contains a download link (a.btn)
                val btn = h3.selectFirst("a.btn") ?: h3.selectFirst("a[href]")
                if (btn != null) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && href !in seen) {
                        seen.add(href)

                        // Extract size from button text
                        val btnText = btn.text().trim()
                        val sizeLabel = SIZE_REGEX.find(btnText)?.groupValues?.get(1) ?: ""

                        // Quality label: use current tracked quality, or extract from text
                        val qualityLabel = if (currentQuality.isNotBlank()) {
                            currentQuality
                        } else {
                            extractQualityFromText(btnText)
                        }

                        results.add(Triple(qualityLabel.ifBlank { "HD" }, sizeLabel, href))
                        currentQuality = "" // Reset after consuming
                    }
                }
            }
        }

        // Fallback 1: look for a.btn buttons anywhere in entry-content
        if (results.isEmpty()) {
            val entryContent = doc.selectFirst("div.entry-content") ?: doc
            var currentQuality = ""

            entryContent.select("h3, h4, h5").forEach { heading ->
                val text = heading.text().trim()

                // Quality label heading
                if (QUALITY_REGEX.containsMatchIn(text)) {
                    currentQuality = extractQualityFromText(text)
                    return@forEach
                }

                // Download link heading
                val btn = heading.selectFirst("a.btn") ?: heading.selectFirst("a[href]")
                if (btn != null) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && href !in seen) {
                        seen.add(href)
                        val btnText = btn.text().trim()
                        val sizeLabel = SIZE_REGEX.find(btnText)?.groupValues?.get(1) ?: ""
                        val qualityLabel = currentQuality.ifBlank { extractQualityFromText(btnText) }
                        results.add(Triple(qualityLabel.ifBlank { "HD" }, sizeLabel, href))
                        currentQuality = ""
                    }
                }
            }
        }

        // Fallback 2: any a.btn or a.dwd-button on the page
        if (results.isEmpty()) {
            doc.select("a.btn, a.dwd-button").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && href !in seen) {
                    seen.add(href)
                    val text = a.text().trim()
                    val sizeLabel = SIZE_REGEX.find(text)?.groupValues?.get(1) ?: ""
                    val qualityLabel = extractQualityFromText(text)
                    results.add(Triple(qualityLabel.ifBlank { "HD" }, sizeLabel, href))
                }
            }
        }

        Log.d(TAG, "parseDownloadLinks: found ${results.size} quality download link(s)")
        return results
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

        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")
        var anySuccess = false

        urls.amap { url ->
            try {
                // Try to resolve nexdrive redirector first
                val resolved = resolveNexdrive(url)
                if (resolved.isEmpty()) {
                    // Direct URL — try loadExtractor, then direct link
                    if (!loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        addDirectLink(url, callback)
                    }
                    anySuccess = true
                } else {
                    // Got hoster links from nexdrive page
                    resolved.amap { resolvedUrl ->
                        try {
                            if (!loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)) {
                                addDirectLink(resolvedUrl, callback)
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
                Log.w(TAG, "Failed to resolve $url: ${e.message}")
                // Last resort: add as direct link
                addDirectLink(url, callback)
                anySuccess = true
            }
        }

        return anySuccess
    }

    // ------------------------------------------------------------------
    // Redirector resolution
    // ------------------------------------------------------------------

    /**
     * Resolve nexdrive.help / nexdrive.vip redirector URLs.
     * These redirectors show a page with links to actual file hosters.
     */
    private suspend fun resolveNexdrive(url: String): List<String> {
        if (!url.contains("nexdrive", ignoreCase = true)) return emptyList()

        return try {
            val res = app.get(url, referer = mainUrl, headers = headers, timeout = 15000)
            val doc = res.document
            val results = mutableListOf<String>()

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("nexdrive", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            // Also check for meta-refresh redirect
            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            if (metaRefresh != null) {
                val content = metaRefresh.attr("content")
                val urlMatch = Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                if (urlMatch != null && urlMatch !in results) {
                    results.add(urlMatch)
                }
            }

            Log.d(TAG, "resolveNexdrive($url): found ${results.size} URL(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveNexdrive failed: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Utility functions
    // ------------------------------------------------------------------

    /**
     * Hosts that are NEVER stream sources (social, navigation, ads, images).
     */
    private val IGNORE_HOST_REGEX = Regex(
        """(?i)(""" +
                """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                """youtube\.com|youtu\.be|""" +
                """t\.me|telegram\.|whatsapp\.|""" +
                """facebook\.com|fb\.com|twitter\.com|instagram\.com|""" +
                """pinterest\.|reddit\.com|tumblr\.com|""" +
                """imgur|i\.imgur|postimg|imgbox|""" +
                """wp-content|wp-includes|wp-json|""" +
                """hdmovieplus4u\.com|gstatic|googletagmanager|google-analytics|""" +
                """jsdelivr|cloudflare\.com|gravatar|""" +
                """fonts\.googleapis|fonts\.gstatic|""" +
                """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                """\.css|\.js\?|/feed/|#|""" +
                """doubleclick|popads|propeller|profitablecpm""" +
                """)"""
    )

    private fun detectSeries(title: String, href: String): Boolean {
        return title.contains("Season", ignoreCase = true) ||
            title.contains("Episode", ignoreCase = true) ||
            title.contains("[ALL EPISODES]", ignoreCase = true) ||
            title.contains("Ep ", ignoreCase = true) ||
            title.contains("Web Series", ignoreCase = true) ||
            title.contains("TV Series", ignoreCase = true) ||
            title.contains("WEB-Series", ignoreCase = true) ||
            Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
            href.contains("/tv-shows/")
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

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*Free\s+Download\s+UHDMovies\s*$"""), "")
            .replace(Regex("""\s*[-|]\s*UHDMovies\s*$"""), "")
            .replace(Regex("""\s*[-–]\s*UHD\s*Movies\s*$"""), "")
            .replace(Regex("""\s*\|\s*Full Movie\s*$"""), "")
            .replace(Regex("""\s*\|\s*Full Series\s*$"""), "")
            .trim()
    }

    private fun extractQualityFromText(text: String): String {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4K", ignoreCase = true) -> "4K"
            text.contains("1080p", ignoreCase = true) && text.contains("HEVC", ignoreCase = true) -> "1080p HEVC"
            text.contains("1080p", ignoreCase = true) && text.contains("x265", ignoreCase = true) -> "1080p HEVC"
            text.contains("1080p", ignoreCase = true) && text.contains("x264", ignoreCase = true) -> "1080p x264"
            text.contains("1080p", ignoreCase = true) && text.contains("10Bit", ignoreCase = true) -> "1080p 10Bit"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) && text.contains("HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720p", ignoreCase = true) && text.contains("x265", ignoreCase = true) -> "720p HEVC"
            text.contains("720p", ignoreCase = true) && text.contains("x264", ignoreCase = true) -> "720p x264"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            text.contains("300MB", ignoreCase = true) -> "300MB"
            else -> ""
        }
    }

    /**
     * Add a direct video link when loadExtractor can't handle the host.
     */
    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        sourceName: String? = null
    ) {
        val quality = when {
            url.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            url.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                sourceName ?: name,
                sourceName ?: "Direct",
                url,
                INFER_TYPE
            ) {
                this.quality = quality
                this.referer = mainUrl
            }
        )
    }
}
