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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import org.jsoup.nodes.Element

/**
 * MoviesCounter CloudStream Provider — v27
 *
 * Professional rewrite based on patterns from phisher98 extensions
 * (HDhub4u, FourKHDHub, Movierulzhd) and verified CloudStream 3 API contracts.
 *
 * Key fixes from v26:
 * - Series parsing: two-pass strategy with nextSibling traversal for S5 format
 * - PACK links completely excluded — only collect after "Single Episode" marker
 * - mainPage uses lazy getter to respect domain fallback changes
 * - WordPress ?id= redirect bypass
 * - Extractor instances lazily cached (no per-call allocation)
 * - Comprehensive PACK filtering (PACK, Complete Season, Full Season, Zip, etc.)
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

        private val DOMAIN_TLDS = listOf("boston", "wiki", "one", "vip", "lol", "cc")

        // ---- Episode / Season detection ----
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )
        private val E_NUM_REGEX = Regex("""\bE(\d{1,3})\b(?!\d)""")
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** "Single Episode" / "Per Episode" / "Episode Wise" section header */
        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise|Single\s*Ep|Episode\s*Links"""
        )

        // ---- PACK filtering (comprehensive) ----
        private val PACK_REGEX = Regex(
            """(?i)\bPACK\b|\bPack\s|\bComplete\s+Season\b|\bFull\s+Season\b|""" +
                    """\bSeason\s+Pack\b|\bAll\s+Episodes?\s+Pack\b|\bZip\b|\b\.zip\b|Pack\s*\["""
        )
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

        // ---- Quality / metadata extraction ----
        private val QUALITY_REGEX = Regex("""(?i)(4K|2160p|1080p|720p|480p|300MB)""")
        private val AUDIO_META_REGEX = Regex(
            """(?i)(DD5\.1|DDP5\.1|ATMOS|AAC5\.1|5\.1|2\.0|ESub|ESubs|Hindi|English|Dual\s*Audio)"""
        )
        private val SOURCE_PLATFORM_REGEX = Regex(
            """(?i)\b(AMZN|NF|DSNP|HMAX|APTV|PMTP|iP|HULU|BBC|ITV|Crav|STAN|BFI|CC)\b"""
        )
        private val CODEC_REGEX = Regex(
            """(?i)\b(x264|x265|HEVC|H264|H265|AV1|10Bit|8Bit|HDR|SDR|DV|HDRip)\b"""
        )

        // ---- URL filtering ----
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
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|\.zip|""" +
                    """\.css|\.js\?|/feed/|#|""" +
                    """doubleclick|popads|propeller|profitablecpm|""" +
                    """adsboosters|admaven|winexch|a-ads|tinyurl|hdhub4u""" +
                    """)"""
        )

        // ---- Lazy extractor instances (avoid per-call allocation) ----
        private val hubdrive by lazy { Hubdrive() }
        private val hubcloud by lazy { HubCloud() }
        private val hubcdn by lazy { HUBCDN() }
        private val hblinks by lazy { Hblinks() }
        private val hdstream4u by lazy { HdStream4u() }
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------------------
    // Main page — lazy getter to respect domain fallback
    // ------------------------------------------------------------------

    override val mainPage
        get() = mainPageOf(
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

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

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
            try {
                val page2Url = "$mainUrl/page/2/?s=$encoded"
                val doc2 = fetchWithFallback(page2Url)
                results.addAll(parseListing(doc2))
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Domain fallback
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Listing page parsing — layered selectors
    // ------------------------------------------------------------------

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Strategy 1: Tailwind card layout
        doc.select("div.inline-flex.flex-col > a").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!isValidListingUrl(href, seenUrls)) return@forEach

            val img = anchor.selectFirst("img") ?: return@forEach
            val title = extractImageTitle(img, anchor) ?: return@forEach
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (isNonContentImage(src)) return@forEach

            seenUrls.add(href)
            val poster = fixUrlNull(src.ifBlank { null })
            val isSeries = detectSeriesFromTitle(title, href)
            val quality = detectSearchQuality(title)

            results.add(
                if (isSeries) {
                    newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                        this.posterUrl = poster; this.quality = quality
                    }
                } else {
                    newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                        this.posterUrl = poster; this.quality = quality
                    }
                }
            )
        }

        // Strategy 2: Any anchor with poster image
        if (results.isEmpty()) {
            doc.select("a:has(img)").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (!isValidListingUrl(href, seenUrls)) return@forEach

                val img = anchor.selectFirst("img") ?: return@forEach
                val title = extractImageTitle(img, anchor) ?: return@forEach
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (isNonContentImage(src)) return@forEach

                seenUrls.add(href)
                val poster = fixUrlNull(src.ifBlank { null })
                val isSeries = detectSeriesFromTitle(title, href)
                val quality = detectSearchQuality(title)

                results.add(
                    if (isSeries) {
                        newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                            this.posterUrl = poster; this.quality = quality
                        }
                    } else {
                        newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                            this.posterUrl = poster; this.quality = quality
                        }
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    private fun isValidListingUrl(href: String, seenUrls: Set<String>): Boolean {
        return href.isNotBlank() && href.startsWith("http") &&
                href !in seenUrls && !isNonContentUrl(href)
    }

    private fun extractImageTitle(img: Element, anchor: Element): String? {
        return img.attr("alt").trim()
            .ifBlank { img.attr("title").trim() }
            .ifBlank { anchor.selectFirst("div.transition-all")?.text()?.trim().orEmpty() }
            .ifBlank { anchor.text()?.trim().orEmpty() }
            .ifBlank { null }
    }

    private fun isNonContentUrl(href: String): Boolean {
        return href.contains("/category/", true) ||
                href.contains("/page/", true) ||
                href.contains("/wp-", true) ||
                href.contains("/feed/", true) ||
                href.contains("how-to-download", true) ||
                href.contains("join-our-group", true) ||
                href.contains("request-a-movie", true) ||
                href.contains("disclaimer", true) ||
                href.contains("privacy-policy", true) ||
                href.contains("contact", true) ||
                (href.contains(mainUrl.trimEnd('/') + "/", true) &&
                        href.replace(mainUrl, "").trimEnd('/').isEmpty())
    }

    private fun isNonContentImage(src: String): Boolean {
        return src.contains("logo", true) || src.contains("favicon", true) ||
                src.contains("badge", true) || src.contains("banner", true) ||
                src.contains("wp-content/themes", true)
    }

    // ------------------------------------------------------------------
    // load() — detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchWithFallback(url)

        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)
        val posterUrl = resolvePoster(doc)
        val postBody = doc.selectFirst("div.post-body")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: postBody?.select("p")?.firstOrNull { it.text().length > 100 }?.text()?.trim()

        var score: Score? = null
        val metaText = postBody?.text() ?: ""
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        val tags = doc.select("div.w-full.my-4.text-center a[href*=/category/]")
            .map { it.text().trim().removePrefix("# ").trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()
            .ifEmpty {
                doc.selectFirst("meta[property=article:section]")?.attr("content")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
            }

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")

        // Series detection
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)
        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
                    it.equals("TV-Shows", true) ||
                    it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }
        val hasSingleEpisodeSection = postBody?.select("h2")?.any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        } ?: false

        val isSeries = titleIndicatesSeries || tagIndicatesSeries || hasSingleEpisodeSection
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load() title='$title' isSeries=$isSeries season=$seasonNum")

        val container = postBody ?: doc.selectFirst("article") ?: doc

        if (isSeries) {
            val episodes = buildSeriesEpisodes(container, seasonNum)
            Log.d(TAG, "load() built ${episodes.size} episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                val links = collectMovieLinks(container)
                if (links.isNotEmpty()) {
                    listOf(newEpisode(links.map { it.second }) {
                        name = "Episode 1"
                        season = seasonNum
                        episode = 1
                    })
                } else emptyList()
            } else episodes

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year
                this.tags = tags.ifEmpty { null }; this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        } else {
            val links = collectMovieLinks(container)
            val urlList = links.map { it.second }
            Log.d(TAG, "load() found ${urlList.size} download link(s) for movie")

            return newMovieLoadResponse(title, url, TvType.Movie, urlList) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year
                this.tags = tags.ifEmpty { null }; this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Poster resolution — 5-tier fallback
    // ------------------------------------------------------------------

    private fun resolvePoster(doc: org.jsoup.nodes.Document): String? {
        doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        doc.selectFirst("article img.aligncenter")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }
        doc.selectFirst("article img[src]")?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) fixUrlNull(src)?.let { return it }
        }
        doc.selectFirst("article img[data-src]")?.let {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) fixUrlNull(src)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Movie link collection
    // ------------------------------------------------------------------

    private fun collectMovieLinks(container: Element): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        container.select("h3, h4, h5").forEach { heading ->
            val headingText = heading.text().trim()
            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach
            if (E_NUM_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach
            if (isPackReference(headingText)) return@forEach

            heading.select("a[href]").forEach { link ->
                val href = fixUrl(link.attr("href").trim())
                if (!isValidDownloadLink(href) || href in seen) return@forEach
                seen.add(href)
                results.add(Pair(buildQualityLabel(headingText), href))
            }
        }

        if (results.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = fixUrl(anchor.attr("href").trim())
                if (!isValidDownloadLink(href) || href in seen) return@forEach
                seen.add(href)
                results.add(Pair(extractQualityLabel(anchor.text().trim()), href))
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Series episode parsing — v27: Two-pass strategy
    //
    // Pass 1: Element-by-element traversal (handles both S4 and S5 formats)
    // Pass 2: Heading + nextSibling traversal (fallback if Pass 1 finds nothing)
    //
    // KEY RULE: Only collect links AFTER "Single Episode" section marker
    //           to prevent PACK link leakage.
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val hasSingleEpSection = container.select("h2, h3").any { el ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(el.text())
        }

        Log.d(TAG, "buildSeriesEpisodes: hasSingleEpSection=$hasSingleEpSection")

        // Pass 1: Element traversal
        val episodeMap = parseEpisodesWithElementTraversal(container, defaultSeason, hasSingleEpSection)

        // Pass 2: nextSibling fallback
        if (episodeMap.isEmpty() || episodeMap.values.all { it.isEmpty() }) {
            Log.d(TAG, "Pass 1 found nothing, trying nextSibling traversal")
            val fallbackMap = parseEpisodesWithNextSibling(container, defaultSeason, hasSingleEpSection)
            if (fallbackMap.isNotEmpty() && fallbackMap.values.any { it.isNotEmpty() }) {
                return buildEpisodeList(fallbackMap)
            }
        }

        return buildEpisodeList(episodeMap)
    }

    /**
     * Pass 1: Traverse elements in document order.
     * - S4: "E01 – 720p Drive | Instant" → episode + links in SAME element
     * - S5: "EPiSODE 5" header → links in SUBSEQUENT <p> elements
     *
     * CRITICAL: Only collects after "Single Episode" section marker.
     */
    private fun parseEpisodesWithElementTraversal(
        container: Element,
        defaultSeason: Int,
        hasSingleEpSection: Boolean
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var pastSingleEpisodeSection = false

        // Broad element selection to capture all possible link containers
        val elements = container.select("h2, h3, h4, h5, h6, p, div.links, div.link, ul, li")

        for (element in elements) {
            val text = element.text().trim()
            val tag = element.tagName()
            if (text.isEmpty()) continue

            // ---- Section markers ----
            if (tag in listOf("h2", "h3")) {
                when {
                    text.contains("DOWNLOAD LINKS", true) -> continue
                    SINGLE_EP_SECTION_REGEX.containsMatchIn(text) -> {
                        pastSingleEpisodeSection = true
                        Log.d(TAG, "Found Single Episode section marker")
                        continue
                    }
                    text.contains("Screen-Shots", true) -> continue
                    text.contains("All Episodes", true) -> continue
                }
            }

            // ---- PACK/SAMPLE filtering ----
            if (isPackReference(text)) continue
            if (SAMPLE_REGEX.containsMatchIn(text)) continue

            // ---- GATE: Only collect after Single Episode section ----
            if (hasSingleEpSection && !pastSingleEpisodeSection) continue

            // ---- Episode number detection ----
            val epFromHeader = EPISODE_HEADER_REGEX.find(text)
            if (epFromHeader != null) {
                val epNum = (epFromHeader.groupValues[1].ifBlank { epFromHeader.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    detectSeasonNumber(text)?.let { currentSeason = it }
                    Log.d(TAG, "Episode header: ep=$epNum season=$currentSeason")
                    // Fall through to collect links from this element
                }
            }

            if (epFromHeader == null) {
                val epFromENum = E_NUM_REGEX.find(text)
                if (epFromENum != null) {
                    val epNum = epFromENum.groupValues[1].toIntOrNull()
                    if (epNum != null) {
                        currentEpisode = epNum
                        detectSeasonNumber(text)?.let { currentSeason = it }
                        Log.d(TAG, "E-num: ep=$epNum season=$currentSeason")
                    }
                }
            }

            // ---- Collect links for current episode ----
            if (currentEpisode != null) {
                element.select("a[href]").forEach { anchor ->
                    val href = fixUrl(anchor.attr("href").trim())
                    if (isValidDownloadLink(href)) {
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        if (href !in bucket) {
                            bucket.add(href)
                            Log.d(TAG, "S${currentSeason}E$currentEpisode: $href")
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Pass 1: ${episodeMap.size} episodes, ${episodeMap.values.sumOf { it.size }} links")
        return episodeMap
    }

    /**
     * Pass 2: Heading + nextSibling traversal.
     * For each episode heading, collects links from:
     *   1. The heading itself (S4 format)
     *   2. Following sibling elements until next heading (S5 format)
     */
    private fun parseEpisodesWithNextSibling(
        container: Element,
        defaultSeason: Int,
        hasSingleEpSection: Boolean
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var pastSingleEpisodeSection = false

        // Check all h2/h3 for section markers first
        for (h in container.select("h2, h3")) {
            if (SINGLE_EP_SECTION_REGEX.containsMatchIn(h.text())) {
                pastSingleEpisodeSection = true
                break
            }
        }

        val headings = container.select("h4, h5, h6").toList()

        for (heading in headings) {
            val text = heading.text().trim()
            if (isPackReference(text)) continue
            if (SAMPLE_REGEX.containsMatchIn(text)) continue

            val epNum = detectEpisodeNumber(text) ?: continue
            detectSeasonNumber(text)?.let { currentSeason = it }

            // If there's a Single Episode section, check we're past it
            if (hasSingleEpSection && !pastSingleEpisodeSection) {
                var prev = heading.previousElementSibling()
                var found = false
                while (prev != null) {
                    if (SINGLE_EP_SECTION_REGEX.containsMatchIn(prev.text())) {
                        found = true; pastSingleEpisodeSection = true; break
                    }
                    prev = prev.previousElementSibling()
                }
                if (!found) continue
            }

            val links = mutableListOf<String>()

            // Links inside the heading (S4 format)
            heading.select("a[href]").forEach { anchor ->
                extractValidHref(anchor)?.let { if (it !in links) links.add(it) }
            }

            // Links in next siblings until next heading (S5 format)
            var sibling = heading.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 10) {
                if (sibling.tagName().matches(Regex("h[1-6]"))) break
                if (isPackReference(sibling.text())) break
                if (EPISODE_HEADER_REGEX.containsMatchIn(sibling.text())) break
                if (E_NUM_REGEX.containsMatchIn(sibling.text())) break

                sibling.select("a[href]").forEach { anchor ->
                    extractValidHref(anchor)?.let { if (it !in links) links.add(it) }
                }

                sibling = sibling.nextElementSibling()
                attempts++
            }

            if (links.isNotEmpty()) {
                val key = currentSeason to epNum
                val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                for (link in links) { if (link !in bucket) bucket.add(link) }
                Log.d(TAG, "Sibling: S${currentSeason}E$epNum → ${links.size} links")
            }
        }

        Log.d(TAG, "Pass 2: ${episodeMap.size} episodes, ${episodeMap.values.sumOf { it.size }} links")
        return episodeMap
    }

    /** Build Episode objects from map using newEpisode(List<String>) */
    private fun buildEpisodeList(
        episodeMap: LinkedHashMap<Pair<Int, Int>, MutableList<String>>
    ): List<Episode> {
        return episodeMap.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, urls) ->
                val (season, ep) = key
                newEpisode(urls.distinct()) {
                    name = "Episode $ep"
                    this.season = season
                    this.episode = ep
                }
            }
    }

    // ------------------------------------------------------------------
    // Detection helpers
    // ------------------------------------------------------------------

    private fun detectEpisodeNumber(text: String): Int? {
        EPISODE_HEADER_REGEX.find(text)?.let { match ->
            return (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
        }
        E_NUM_REGEX.find(text)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun detectSeasonNumber(text: String): Int? {
        return SEASON_REGEX.find(text)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        }
    }

    private fun isPackReference(text: String): Boolean = PACK_REGEX.containsMatchIn(text)

    private fun extractValidHref(anchor: Element): String? {
        val href = fixUrl(anchor.attr("href").trim())
        return if (isValidDownloadLink(href)) href else null
    }

    private fun isValidDownloadLink(href: String): Boolean {
        return href.startsWith("http") &&
                !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                !href.contains(mainUrl, ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // Quality label builders
    // ------------------------------------------------------------------

    private fun buildQualityLabel(text: String): String {
        val base = extractQualityLabel(text)
        val extras = mutableListOf<String>()
        val baseUpper = base.uppercase()

        SOURCE_PLATFORM_REGEX.find(text)?.groupValues?.get(1)?.let { p ->
            if (p.uppercase() !in baseUpper) extras.add(p.uppercase())
        }
        CODEC_REGEX.findAll(text).forEach { match ->
            val c = match.groupValues[1].uppercase()
            if (c !in baseUpper && c !in extras) extras.add(c)
        }
        AUDIO_META_REGEX.findAll(text).forEach { match ->
            val m = match.groupValues[1]
            val n = when {
                m.equals("ESub", true) || m.equals("ESubs", true) -> "ESub"
                m.equals("Dual Audio", true) -> "Dual Audio"
                else -> m.uppercase()
            }
            if (n !in baseUpper && n !in extras) extras.add(n)
        }

        return if (extras.isNotEmpty()) "$base ${extras.joinToString(" ")}" else base
    }

    private fun extractQualityLabel(text: String): String = when {
        text.contains("2160p", true) || text.contains("4K", true) -> "4K"
        text.contains("1080p", true) && text.contains("HEVC", true) -> "1080p HEVC"
        text.contains("1080p", true) && text.contains("x264", true) -> "1080p x264"
        text.contains("1080p", true) && text.contains("10Bit", true) -> "1080p 10Bit"
        text.contains("1080p", true) && text.contains("WEB-DL", true) -> "1080p WEB-DL"
        text.contains("1080p", true) -> "1080p"
        text.contains("720p", true) && text.contains("HEVC", true) -> "720p HEVC"
        text.contains("720p", true) && text.contains("x264", true) -> "720p x264"
        text.contains("720p", true) && text.contains("10Bit", true) -> "720p 10Bit"
        text.contains("720p", true) -> "720p"
        text.contains("480p", true) -> "480p"
        text.contains("300MB", true) -> "300MB"
        else -> "HD"
    }

    // ------------------------------------------------------------------
    // Title cleaning & series detection
    // ------------------------------------------------------------------

    private fun cleanTitle(raw: String): String = raw
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

    private fun detectSeriesFromTitle(title: String, href: String): Boolean {
        return title.contains("Season", true) ||
                title.contains("WEB-Series", true) ||
                title.contains("TV Series", true) ||
                title.contains("Web Series", true) ||
                title.contains("NF Series", true) ||
                title.contains("Zee5 Series", true) ||
                Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
                href.contains("/category/web-series/", true) ||
                href.contains("/category/tv-shows/", true)
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray",
            "web-dl", "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd", "300mb")
        val lower = title.lowercase()
        for (tok in tokens) { if (lower.contains(tok)) return getQualityFromString(tok) }
        return null
    }

    // ------------------------------------------------------------------
    // loadLinks — resolves URLs via extractors
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val linksList = tryParseJson<List<String>>(data)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.startsWith("http") }
            ?: emptyList()

        if (linksList.isEmpty()) {
            val legacyLinks = data.lines()
                .flatMap { it.split("|") }
                .map { it.trim() }
                .filter { it.startsWith("http") }

            if (legacyLinks.isEmpty()) return false

            Log.d(TAG, "loadLinks(): ${legacyLinks.size} legacy link(s)")
            legacyLinks.amap { resolveAndLoad(it, subtitleCallback, callback) }
            return true
        }

        Log.d(TAG, "loadLinks(): resolving ${linksList.size} link(s)")
        linksList.amap { resolveAndLoad(it, subtitleCallback, callback) }
        return linksList.isNotEmpty()
    }

    /** Route URL to the correct extractor based on domain */
    private suspend fun resolveAndLoad(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                url.contains("hubdrive", true) ->
                    hubdrive.getUrl(url, name, subtitleCallback, callback)

                url.contains("hubcloud", true) ->
                    hubcloud.getUrl(url, name, subtitleCallback, callback)

                url.contains("hubcdn", true) ->
                    hubcdn.getUrl(url, name, subtitleCallback, callback)

                url.contains("hblinks", true) || url.contains("mclinks", true) ->
                    hblinks.getUrl(url, name, subtitleCallback, callback)

                url.contains("hdstream4u", true) ->
                    hdstream4u.getUrl(url, name, subtitleCallback, callback)

                // WordPress internal redirect (?id= pattern)
                url.contains("?id=", true) && url.contains(mainUrl, true) -> {
                    val resolved = resolveWordPressRedirect(url)
                    if (resolved != null && resolved != url) {
                        resolveAndLoad(resolved, subtitleCallback, callback)
                    } else {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                    }
                }

                else -> {
                    if (!loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        addDirectLink(url, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveAndLoad failed for $url: ${e.message}")
        }
    }

    /** WordPress redirect bypass for internal ?id= URLs */
    private suspend fun resolveWordPressRedirect(url: String): String? {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000L).document
            doc.select("a[href*=hubdrive], a[href*=hubcloud], a[href*=hubcdn], a[href*=mclinks]")
                .firstOrNull()?.attr("href")?.trim()?.let { if (it.startsWith("http")) it else null }
        } catch (e: Exception) {
            Log.w(TAG, "WordPress redirect failed: ${e.message}"); null
        }
    }

    /** Add a direct link as fallback */
    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityLabel: String = ""
    ) {
        val quality = when {
            qualityLabel.contains("4K", true) || qualityLabel.contains("2160p", true) -> Qualities.P2160.value
            qualityLabel.contains("1080p", true) -> Qualities.P1080.value
            qualityLabel.contains("720p", true) -> Qualities.P720.value
            qualityLabel.contains("480p", true) -> Qualities.P480.value
            url.contains("2160p", true) || url.contains("4K", true) -> Qualities.P2160.value
            url.contains("1080p", true) -> Qualities.P1080.value
            url.contains("720p", true) -> Qualities.P720.value
            url.contains("480p", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(newExtractorLink(name, qualityLabel.ifBlank { "Direct" }, url, INFER_TYPE) {
            this.quality = quality; this.referer = mainUrl
        })
    }
}
