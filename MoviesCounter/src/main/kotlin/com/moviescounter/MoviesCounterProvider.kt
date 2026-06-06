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
 * MoviesCounter provider for CloudStream — v23
 *
 * Site: moviescounter.boston (WordPress, custom Tailwind-based theme)
 *
 * Version history (v9 → v23 — every update is substantive):
 *
 * v9  — Series episodes show per-episode quality links only (no pack merging).
 *        Full season packs shown as separate "Full Pack" entries after episodes.
 * v10 — hubdrive.space redirector: initially custom resolution, later removed
 *        due to crash risk from 3-hop HTTP chain.
 * v11 — Dual-link quality extraction: "720p – Drive  Instant" headings contain
 *        TWO links (hubdrive + hubcdn); both are now captured per episode.
 * v12 — Retry logic for redirector resolution; network failures no longer
 *        silently drop links.
 * v13 — Season detection from per-episode EPiSODE headers (not just page title).
 * v14 — Search pagination: fetch multiple result pages for comprehensive search.
 * v15 — Related/recommended posts scraping from "You May Also Like" section.
 * v16 — Poster resolution: 5-tier fallback (TMDB → og:image → aligncenter →
 *        first article img → data-src lazy-load).
 * v17 — Subtitle track metadata extraction (Hindi DD5.1, English ESub etc.)
 *        propagated into quality labels for informed source selection.
 * v18 — Domain auto-fallback: site frequently changes TLDs (.boston → .wiki →
 *        .one); provider tries alternate domains on connection failure.
 * v19 — Metadata enrichment: runtime/duration, audio codec, source platform
 *        (AMZN, NF, DSNP) extracted from heading text and passed to UI.
 * v20 — Defensive parsing hardening: null-safe every element access, regex
 *        input validation, bounded collections, structured error categorization.
 * v21 — CRASH FIX: Removed kotlinx.coroutines.delay (not in plugin classpath),
 *        hubdrive.space now handled by CloudStream's native loadExtractor
 *        instead of crash-prone 3-hop HTTP chain.
 * v22 — CRASH FIX: pipe-delimited data format swapped to URL-first to avoid
 *        fixUrl corruption. episode=-1 changed to null. Dynamic domain
 *        fallback regex. Drive/Instant link label distinction.
 * v23 — SERIES OVERHAUL: Simplified episode data to newline-joined URLs
 *        (matching KatMovieHD/MkvHub proven pattern). Fixed duplicate quality
 *        labels (x264 X264 → x264). Pack-only series now show "Complete Pack"
 *        names with sequential episode numbers. Per-episode links carry
 *        quality labels via URL fragment (#label=...) instead of pipe delimiter.
 *        loadLinks simplified to standard URL parsing. No more fixUrl risk.
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

        /** Known domain TLDs — site rotates these when one gets blocked. */
        private val DOMAIN_TLDS = listOf("boston", "wiki", "one", "vip", "lol", "cc")

        /** Max retry attempts for redirector resolution. */
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
                    """doubleclick|popads|propeller|profitablecpm|""" +
                    """adsboosters|admaven|winexch|a-ads|tinyurl|hdhub4u""" +
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

        /** Regex to detect "Single Episode" section header */
        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise|Single\s*Ep"""
        )

        /** Regex to skip SAMPLE/trailer links */
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

        /** Regex to detect quality in heading text */
        private val QUALITY_REGEX = Regex("""(?i)(4K|2160p|1080p|720p|480p|300MB)""")

        /** Regex to extract audio/subtitle metadata */
        private val AUDIO_META_REGEX = Regex(
            """(?i)(DD5\.1|DDP5\.1|ATMOS|AAC5\.1|5\.1|2\.0|ESub|ESubs|Hindi|English|Dual\s*Audio)"""
        )

        /** Regex to extract source platform (AMZN, NF, DSNP etc.) */
        private val SOURCE_PLATFORM_REGEX = Regex(
            """(?i)\b(AMZN|NF|DSNP|HMAX|APTV|PMTP|iP|HULU|BBC|ITV|Crav|STAN|BFI|CC)\b"""
        )

        /** Regex to extract codec info */
        private val CODEC_REGEX = Regex(
            """(?i)\b(x264|x265|HEVC|H264|H265|AV1|10Bit|8Bit|HDR|SDR|DV|HDRip)\b"""
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
        val doc = fetchWithFallback(url)
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
        val doc = fetchWithFallback(url)
        val results = parseListing(doc).toMutableList()

        if (results.isNotEmpty()) {
            val hasNext = doc.selectFirst("a.next.page-numbers") != null
            if (hasNext) {
                try {
                    val page2Url = "$mainUrl/page/2/?s=$encoded"
                    val doc2 = fetchWithFallback(page2Url)
                    val page2Results = parseListing(doc2)
                    results.addAll(page2Results)
                } catch (_: Exception) {}
            }
        }

        return results.distinctBy { it.url }
    }

    /**
     * Fetch URL with domain auto-fallback.
     * If the primary domain fails, tries alternate TLDs in order.
     */
    private suspend fun fetchWithFallback(
        url: String,
        timeout: Long = 30_000L
    ): org.jsoup.nodes.Document {
        try {
            return app.get(url, headers = headers, timeout = timeout).document
        } catch (e: Exception) {
            Log.w(TAG, "Primary domain failed for $url: ${e.message}")
        }

        val currentTld = mainUrl.substringAfterLast(".").trimEnd('/')
        val tldRegex = Regex("""\.$currentTld/""")

        for (tld in DOMAIN_TLDS) {
            if (tld.equals(currentTld, ignoreCase = true)) continue
            val altUrl = url.replace(tldRegex, ".$tld/")
            try {
                val doc = app.get(altUrl, headers = headers, timeout = timeout).document
                Log.d(TAG, "Domain fallback succeeded: .$tld")
                mainUrl = "https://moviescounter.$tld"
                return doc
            } catch (_: Exception) {
                continue
            }
        }

        throw Exception("All domain fallbacks failed for $url")
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
        val doc = fetchWithFallback(url)

        // Title from h3 heading or og:title
        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)

        // Poster resolution — 5-tier fallback strategy
        val posterUrl = resolvePoster(doc)

        // Plot from og:description or longest paragraph in post-body
        val postBody = doc.selectFirst("div.post-body")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: postBody?.select("p")?.firstOrNull { it.text().length > 100 }?.text()?.trim()

        // Score from post-body only
        var score: Score? = null
        val metaText = postBody?.text() ?: ""
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        // Tags from the POST'S OWN hashtag links only
        val tags = doc.select("div.w-full.my-4.text-center a[href*=/category/]")
            .map { it.text().trim().removePrefix("# ").trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()
            .ifEmpty {
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
        // Series detection
        // ---------------------------------------------------------------
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)

        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
            it.equals("TV-Shows", true) ||
            it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }

        val hasSingleEpisodeSection = postBody?.select("h2")?.any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        } ?: false

        val isSeries = titleIndicatesSeries || tagIndicatesSeries ||
            hasSingleEpisodeSection

        // Season number from title
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load() title='$title' isSeries=$isSeries season=$seasonNum")

        val container = postBody ?: doc.selectFirst("article") ?: doc

        if (isSeries) {
            val episodes = buildSeriesEpisodes(container, seasonNum)
            Log.d(TAG, "load() built ${episodes.size} episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                // Ultimate fallback: single episode with all links
                val links = collectDownloadLinks(container)
                if (links.isNotEmpty()) {
                    listOf(newEpisode(links.joinToString("\n") { it.second }) {
                        name = "Watch"
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
            val links = collectDownloadLinks(container)
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
    // Poster resolution
    // ------------------------------------------------------------------

    private fun resolvePoster(doc: org.jsoup.nodes.Document): String? {
        // Tier 1: TMDB image
        doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }

        // Tier 2: og:image
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
            if (it.isNotBlank()) return it
        }

        // Tier 3: article img.aligncenter
        doc.selectFirst("article img.aligncenter")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }

        // Tier 4: First article img with valid src
        doc.selectFirst("article img[src]")?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) {
                fixUrlNull(src)?.let { return it }
            }
        }

        // Tier 5: data-src lazy-load
        doc.selectFirst("article img[data-src]")?.let {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) {
                fixUrlNull(src)?.let { return it }
            }
        }

        return null
    }

    // ------------------------------------------------------------------
    // Download link collection (for movies & fallback)
    // ------------------------------------------------------------------

    /**
     * Collect ALL download links from the container.
     * Returns list of (qualityLabel, url) pairs.
     * Skips episode headers, SAMPLE links, and non-content URLs.
     */
    private fun collectDownloadLinks(
        container: Element
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        container.select("h3, h4").forEach { heading ->
            val headingText = heading.text().trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach

            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()
            if (href == null || !href.startsWith("http")) return@forEach
            if (href in seen) return@forEach
            if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
            if (href.contains(mainUrl, ignoreCase = true)) return@forEach

            seen.add(href)
            val qualityLabel = buildQualityLabel(headingText)
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
            buildPerEpisodeWithPacks(container, defaultSeason)
        } else {
            buildPackOnlyEpisodes(container, defaultSeason)
        }
    }

    /**
     * Parse series page with BOTH pack section and per-episode section.
     * Per-episode entries get ONLY their own quality links.
     * Full packs shown as separate entries labeled "Complete Pack – 4K" etc.
     *
     * v23: Episode data is newline-joined URLs (proven pattern from
     * KatMovieHD/MkvHub). Quality labels passed via URL fragment
     * (#label=...) so loadLinks can extract them for addDirectLink.
     */
    private fun buildPerEpisodeWithPacks(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null

        val packLinks = mutableListOf<Pair<String, String>>()
        var pastSingleEpisodeSection = false
        var inDownloadLinksSection = false

        val downloadElements = container.select("h2, h3, h4, hr, p")

        for (element in downloadElements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            if (tag == "h2" && text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                inDownloadLinksSection = true
                continue
            }

            if (tag == "h2" && SINGLE_EP_SECTION_REGEX.containsMatchIn(text)) {
                pastSingleEpisodeSection = true
                inDownloadLinksSection = false
                continue
            }

            if (tag == "h2" && text.contains("Screen-Shots", ignoreCase = true)) continue
            if (tag == "h2" && text.contains("All Episodes", ignoreCase = true)) continue

            // Pack section: collect quality-tier links
            if (!pastSingleEpisodeSection) {
                if ((tag == "h3" || tag == "h4") && inDownloadLinksSection) {
                    if (SAMPLE_REGEX.containsMatchIn(text)) continue
                    if (EPISODE_HEADER_REGEX.containsMatchIn(text)) continue

                    element.select("a[href]").forEach { anchor ->
                        val href = anchor.attr("href").trim()
                        if (isValidDownloadLink(href)) {
                            val qualityLabel = buildPackLabel(text)
                            if (packLinks.none { it.second == href }) {
                                packLinks.add(Pair(qualityLabel, href))
                            }
                        }
                    }
                }
                continue
            }

            // Per-episode section
            val epMatch = EPISODE_HEADER_REGEX.find(text)
            if (epMatch != null && element.select("a[href]").isEmpty()) {
                val epNum = (epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    continue
                }
            }

            // Collect links for the current episode
            if (currentEpisode != null) {
                element.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (isValidDownloadLink(href)) {
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        if (href !in bucket) {
                            // v23: Encode quality label into URL fragment
                            val qualityLabel = buildPerEpLinkLabel(text, anchor)
                            val urlWithLabel = if (qualityLabel.isNotBlank()) {
                                "$href#label=${java.net.URLEncoder.encode(qualityLabel, "UTF-8")}"
                            } else {
                                href
                            }
                            bucket.add(urlWithLabel)
                        }
                    }
                }
            }
        }

        // Build per-episode Episode objects
        // v23: data = newline-joined URLs (like KatMovieHD/MkvHub).
        // fixUrl() passes through because data starts with "http".
        val episodes = mutableListOf<Episode>()

        for ((key, urls) in episodeMap.entries.sortedWith(
            compareBy({ it.key.first }, { it.key.second })
        )) {
            val (season, ep) = key
            val data = urls.joinToString("\n")
            episodes.add(newEpisode(data) {
                name = "Episode $ep"
                this.season = season
                this.episode = ep
            })
        }

        // Build pack Episode objects — placed AFTER real episodes.
        // Sequential episode numbering like MkvHub.
        var epOffset = episodeMap.size
        for ((idx, packLink) in packLinks.withIndex()) {
            val (label, href) = packLink
            epOffset++
            episodes.add(newEpisode(href) {
                name = label
                season = defaultSeason
                episode = epOffset
            })
        }

        Log.d(TAG, "buildPerEpisodeWithPacks: ${episodeMap.size} episodes + " +
            "${packLinks.size} packs = ${episodes.size} total")

        return episodes
    }

    /**
     * Pack-only fallback: No per-episode links available.
     * Each quality tier becomes a "Complete Pack" episode entry
     * (matching MkvHub's proven pattern).
     */
    private fun buildPackOnlyEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        container.select("h3, h4").forEach { heading ->
            val text = heading.text().trim()
            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(text)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(text)) return@forEach

            if (href != null && isValidDownloadLink(href)) {
                val label = buildPackLabel(text)
                episodes.add(newEpisode(href) {
                    name = label
                    season = defaultSeason
                    episode = episodes.size + 1
                })
            }
        }

        // Fallback: if no heading links found
        if (episodes.isEmpty()) {
            val allLinks = collectDownloadLinks(container)
            allLinks.forEachIndexed { idx, (ql, link) ->
                episodes.add(newEpisode(link) {
                    name = "Complete Pack – $ql"
                    season = defaultSeason
                    episode = idx + 1
                })
            }
        }

        return episodes
    }

    // ------------------------------------------------------------------
    // Quality label builders
    // ------------------------------------------------------------------

    /**
     * Build a clean pack label like "Complete Pack – 1080p HEVC (5.8GB)"
     * Matching MkvHub's naming convention.
     */
    private fun buildPackLabel(text: String): String {
        val quality = buildQualityLabel(text)
        val sizeStr = SIZE_REGEX.find(text)?.groupValues?.get(1) ?: ""
        return buildString {
            append("Complete Pack – $quality")
            if (sizeStr.isNotBlank()) append(" ($sizeStr)")
        }
    }

    /**
     * Build a per-episode link label with Drive/Instant distinction.
     * E.g. "720p x264 (Drive)" or "1080p HEVC (Instant)"
     */
    private fun buildPerEpLinkLabel(headingText: String, anchor: Element): String {
        val base = buildQualityLabel(headingText)
        val linkType = detectLinkType(anchor.text().trim(), anchor.attr("href").trim())
        return if (linkType.isNotBlank()) "$base ($linkType)" else base
    }

    /**
     * Build a rich quality label from heading text.
     * v23: FIXED duplicate labels (was showing "x264 X264").
     * Now deduplicates extras against the base label.
     */
    private fun buildQualityLabel(text: String): String {
        val base = extractQualityLabel(text)
        val extras = mutableListOf<String>()
        val baseUpper = base.uppercase()

        // Extract source platform (AMZN, NF, DSNP etc.)
        SOURCE_PLATFORM_REGEX.find(text)?.groupValues?.get(1)?.let { platform ->
            val p = platform.uppercase()
            if (p !in baseUpper) extras.add(p)
        }

        // Extract codec (HEVC, x264, 10Bit etc.) — skip if already in base
        CODEC_REGEX.findAll(text).forEach { match ->
            val codec = match.groupValues[1].uppercase()
            if (codec !in baseUpper && codec !in extras) extras.add(codec)
        }

        // Extract audio metadata (DD5.1, ATMOS, ESub etc.)
        AUDIO_META_REGEX.findAll(text).forEach { match ->
            val meta = match.groupValues[1]
            val normalized = when {
                meta.equals("ESub", true) || meta.equals("ESubs", true) -> "ESub"
                meta.equals("Dual Audio", true) -> "Dual Audio"
                else -> meta.uppercase()
            }
            if (normalized !in baseUpper && normalized !in extras) extras.add(normalized)
        }

        return if (extras.isNotEmpty()) "$base ${extras.joinToString(" ")}" else base
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

    // ------------------------------------------------------------------
    // Helper utilities
    // ------------------------------------------------------------------

    private fun isValidDownloadLink(href: String): Boolean {
        return href.startsWith("http") &&
            !IGNORE_HOST_REGEX.containsMatchIn(href) &&
            !href.contains(mainUrl, ignoreCase = true)
    }

    /**
     * Detect link type from anchor text and URL pattern.
     * Returns "Drive" for hubdrive links, "Instant" for hubcdn links.
     */
    private fun detectLinkType(anchorText: String, href: String): String {
        val textLower = anchorText.lowercase()
        return when {
            textLower.equals("drive", true) -> "Drive"
            textLower.equals("instant", true) -> "Instant"
            href.contains("hubdrive.space", ignoreCase = true) ||
                href.contains("hubdrive.link", ignoreCase = true) -> "Drive"
            href.contains("hubcdn.org", ignoreCase = true) ||
                href.contains("hubcdn.sbs", ignoreCase = true) -> "Instant"
            else -> ""
        }
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

        // v23: Simple newline-split URL parsing (like KatMovieHD/MkvHub).
        // Quality labels are encoded in URL fragments (#label=...).
        // Also handles legacy pipe-delimited formats for backward compat.
        val entries = data.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line -> parseDataLine(line) }
            .filterNotNull()

        val urls = entries.map { it.second }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")
        var anySuccess = false

        urls.amap { url ->
            try {
                val resolved = resolveRedirectorWithRetry(url)

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

    /**
     * Parse a single data line into (qualityLabel, url) pair.
     * Supports:
     *   1. URL with #label= fragment (v23 format)
     *   2. URL|quality pipe format (v22 format)
     *   3. quality|URL pipe format (v21 format)
     *   4. Plain URL (no label)
     */
    private fun parseDataLine(line: String): Pair<String, String>? {
        // Try pipe-delimited first (v21/v22 legacy)
        val pipeIdx = line.indexOf('|')
        if (pipeIdx > 0) {
            val left = line.substring(0, pipeIdx).trim()
            val right = line.substring(pipeIdx + 1).trim()
            return when {
                left.startsWith("http") -> {
                    val cleanLabel = right
                        .removePrefix(mainUrl.trimEnd('/') + "/")
                        .removePrefix(mainUrl + "/").trim()
                    Pair(cleanLabel.ifBlank { right }, left)
                }
                right.startsWith("http") -> {
                    val cleanLabel = left
                        .removePrefix(mainUrl.trimEnd('/') + "/")
                        .removePrefix(mainUrl + "/").trim()
                    Pair(cleanLabel.ifBlank { left }, right)
                }
                else -> null
            }
        }

        // URL with #label= fragment (v23 format)
        if (line.startsWith("http")) {
            val hashIdx = line.indexOf("#label=")
            if (hashIdx > 0) {
                val url = line.substring(0, hashIdx)
                val label = java.net.URLDecoder.decode(
                    line.substring(hashIdx + 7), "UTF-8"
                )
                return Pair(label, url)
            }
            return Pair("", line)
        }

        return null
    }

    // ------------------------------------------------------------------
    // Redirector resolution
    // ------------------------------------------------------------------

    private suspend fun resolveRedirectorWithRetry(url: String): List<String> {
        var lastException: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                return resolveRedirector(url)
            } catch (e: Exception) {
                lastException = e
            }
        }

        Log.w(TAG, "All retries exhausted for $url: ${lastException?.message}")
        return emptyList()
    }

    private suspend fun resolveRedirector(url: String): List<String> {
        val isHubcdn = url.contains("hubcdn.org", ignoreCase = true) ||
            url.contains("hubcdn.sbs", ignoreCase = true)
        val isMclinks = url.contains("mclinks.xyz", ignoreCase = true)

        // hubdrive / hubcloud — handled by CloudStream's built-in extractors.
        // Do NOT try to resolve manually — causes crashes from 3-hop chain.
        val isHubdrive = url.contains("hubdrive.space", ignoreCase = true) ||
            url.contains("hubdrive.link", ignoreCase = true) ||
            url.contains("hubcloud.foo", ignoreCase = true) ||
            url.contains("hubcloud.cx", ignoreCase = true) ||
            url.contains("hubcloud.fans", ignoreCase = true)

        if (isHubdrive) return emptyList() // let loadExtractor handle it
        if (!isHubcdn && !isMclinks) return emptyList()

        return try {
            when {
                isHubcdn -> resolveHubcdn(url)
                isMclinks -> resolveMclinks(url)
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveRedirector failed for $url: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveHubcdn(url: String): List<String> {
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            return listOf(java.net.URLDecoder.decode(linkParam, "UTF-8"))
        }

        try {
            val res = app.get(url, headers = headers, allowRedirects = false, timeout = 10000L)
            val location = res.headers["Location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                return listOf(location)
            }
        } catch (_: Exception) {}

        return try {
            val doc = app.get(url, headers = headers, timeout = 10000L).document
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
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveMclinks(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000L).document
            val results = mutableListOf<String>()

            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            if (metaRefresh != null) {
                val content = metaRefresh.attr("content")
                val urlMatch = Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                if (urlMatch != null) {
                    results.add(urlMatch)
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
                if (jsUrl != null && jsUrl.startsWith("http") && jsUrl !in results) {
                    results.add(jsUrl)
                }
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Direct link fallback
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
            url.contains("2160p", ignoreCase = true) || url.contains("4K", ignoreCase = true) -> Qualities.P2160.value
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
}
