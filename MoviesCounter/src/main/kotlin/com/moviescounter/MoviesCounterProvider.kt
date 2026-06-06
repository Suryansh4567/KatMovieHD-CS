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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import org.jsoup.nodes.Element

/**
 * MoviesCounter CloudStream Provider — v30
 *
 * Chain: MoviesCounter → mclinks.xyz/hubdrive.space/hubcloud.foo → hubcloud.foo → gamerxyt.com → download
 * Alt:   hubcdn.sbs/dl/?link=obsession.buzz → direct video file
 * Alt:   hdstream4u.com → VidHidePro
 *
 * Key fixes from v29:
 * - Use List<String> for movie/episode data (CloudStream generic overload handles serialization)
 * - Fix HUBCDN mainUrl from hubcdn.org to hubcdn.sbs (actual domain in URLs)
 * - Fix HUBCDN obsession.buzz handling (direct video file, not HTML page)
 * - Add Mclinks extractor with correct mainUrl for loadExtractor routing
 * - Fix BuzzServer label check ("buzz server" with space, not "buzzserver")
 * - Fix resolveObsessionBuzz (HTTP 200 direct file, not redirect)
 * - Proper PACK link filtering on both URL and text
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

        // Known download link domains — positive identification
        private val DOWNLOAD_DOMAINS = listOf(
            "hubdrive", "hubcloud", "hubcdn", "mclinks", "hblinks",
            "hdstream4u", "hubstream", "gadgetsweb", "obsession.buzz",
            "multicloud", "drive.google", "gdtot", "gofile", "gamerxyt"
        )

        // Episode header patterns — matches "EPiSODE 1", "Episode 2", etc.
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )
        private val E_NUM_REGEX = Regex("""\bE(\d{1,3})\b(?!\d)""")
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise|Single\s*Ep|Episode\s*Links"""
        )

        // PACK detection — both text and URL patterns
        private val PACK_TEXT_REGEX = Regex(
            """(?i)\bPACK\b|\bComplete\s+Season\b|\bFull\s+Season\b|""" +
                    """\bSeason\s+Pack\b|\bAll\s+Episodes?\s+Pack\b"""
        )
        private val PACK_URL_REGEX = Regex("""/packs/""", RegexOption.IGNORE_CASE)
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

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

        // URL filter — domains and patterns to skip
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
                    """jsdelivr|cloudflare\.com|gravatar|effectivecpm|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|\.zip|""" +
                    """\.css|\.js|/feed/|/wp-|""" +
                    """doubleclick|popads|propeller|profitablecpm|""" +
                    """adsboosters|admaven|winexch|a-ads|tinyurl|hdhub4u|inventoryidea|""" +
                    """bonuscaf|snvhost|llvpn|adsboosters""" +
                    """)"""
        )

        private val hubdrive by lazy { Hubdrive() }
        private val hubcloud by lazy { HubCloud() }
        private val hubcdn by lazy { HUBCDN() }
        private val hblinks by lazy { Hblinks() }
        private val mclinks by lazy { Mclinks() }
        private val hdstream4u by lazy { HdStream4u() }
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://moviescounter.boston/"
    )

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
    // Homepage & Search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = fetchWithFallback(url)
        val items = parseListing(doc)
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(arrayListOf(HomePageList(request.name, items)), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = fetchWithFallback(url)
        val results = parseListing(doc).toMutableList()
        if (results.isNotEmpty()) {
            try {
                val doc2 = fetchWithFallback("$mainUrl/page/2/?s=$encoded")
                results.addAll(parseListing(doc2))
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    private suspend fun fetchWithFallback(url: String, timeout: Long = 30_000L): org.jsoup.nodes.Document {
        try {
            return app.get(url, headers = headers, timeout = timeout).document
        } catch (e: Exception) {
            Log.w(TAG, "Primary domain failed for $url: ${e.message}")
        }

        val currentTld = mainUrl.substringAfterLast(".").trimEnd('/')
        val tldRegex = Regex("""\.$currentTld/""")

        for (tld in DOMAIN_TLDS) {
            if (tld.equals(currentTld, true)) continue
            val altUrl = url.replace(tldRegex, ".$tld/")
            try {
                val doc = app.get(altUrl, headers = headers, timeout = timeout).document
                Log.d(TAG, "Domain fallback succeeded: .$tld")
                mainUrl = "https://moviescounter.$tld"
                return doc
            } catch (_: Exception) { continue }
        }
        throw Exception("All domain fallbacks failed for $url")
    }

    // ------------------------------------------------------------------
    // Listing page parsing
    // ------------------------------------------------------------------

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Primary selector: card layout with inline-flex
        doc.select("div.inline-flex.flex-col > a[href]").forEach { anchor ->
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
            results.add(if (isSeries) newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) { this.posterUrl = poster; this.quality = quality }
            else newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) { this.posterUrl = poster; this.quality = quality })
        }

        // Fallback: any anchor with image
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
                results.add(if (isSeries) newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) { this.posterUrl = poster; this.quality = quality }
                else newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) { this.posterUrl = poster; this.quality = quality })
            }
        }
        return results.distinctBy { it.url }
    }

    private fun isValidListingUrl(href: String, seenUrls: Set<String>): Boolean {
        return href.isNotBlank() && href.startsWith("http") && href !in seenUrls && !isNonContentUrl(href)
    }

    private fun extractImageTitle(img: Element, anchor: Element): String? {
        return img.attr("alt").trim().ifBlank { img.attr("title").trim() }
            .ifBlank { anchor.selectFirst("div.transition-all")?.text()?.trim().orEmpty() }
            .ifBlank { anchor.text()?.trim().orEmpty() }.ifBlank { null }
    }

    private fun isNonContentUrl(href: String): Boolean {
        return href.contains("/category/", true) || href.contains("/page/", true) ||
                href.contains("/feed/", true) || href.contains("how-to-download", true) ||
                href.contains("join-our-group", true) || href.contains("disclaimer", true) ||
                href.contains("privacy-policy", true) || href.contains("contact", true)
    }

    private fun isNonContentImage(src: String): Boolean {
        return src.contains("logo", true) || src.contains("favicon", true) ||
                src.contains("badge", true) || src.contains("banner", true) ||
                src.contains("wp-content/themes", true)
    }

    // ------------------------------------------------------------------
    // load() — Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "=== load() START: $url ===")

        val doc = fetchWithFallback(url)
        Log.d(TAG, "Page fetched OK, title: ${doc.title()}")

        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)
        val posterUrl = resolvePoster(doc)

        // Content container — div.post-body is the primary container
        val postBody = doc.selectFirst("div.post-body")
            ?: doc.selectFirst("div.entry-content")
            ?: doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc

        Log.d(TAG, "Container: ${postBody.tagName()}.${postBody.className().take(50)}")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: postBody.select("p").firstOrNull { it.text().length > 100 }?.text()?.trim()

        var score: Score? = null
        val metaText = postBody.text()
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { score = Score.from10(it) }

        val tags = doc.select("div.w-full.my-4.text-center a[href*=/category/]")
            .map { it.text().trim().removePrefix("# ").trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()
            .ifEmpty {
                doc.selectFirst("meta[property=article:section]")?.attr("content")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            }

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")

        // Series detection
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)
        val tagIndicatesSeries = tags.any { it.equals("WEB-Series", true) || it.equals("TV-Shows", true) }
        val hasSingleEpisodeSection = postBody.select("h2, h3").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        val isSeries = titleIndicatesSeries || tagIndicatesSeries || hasSingleEpisodeSection
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "title='$title' isSeries=$isSeries season=$seasonNum")

        // Collect ALL external download links for fallback
        val allExternalLinks = postBody.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") && !IGNORE_HOST_REGEX.containsMatchIn(it) && !it.contains(mainUrl, true) }
            .distinct()
        Log.d(TAG, "Total external links in container: ${allExternalLinks.size}")

        if (isSeries) {
            val episodes = buildSeriesEpisodes(postBody, seasonNum)
            Log.d(TAG, "Series: ${episodes.size} episode(s) built")

            val finalEpisodes = if (episodes.isEmpty()) {
                // Super-fallback: all external links → Episode 1
                val nonPackLinks = allExternalLinks.filter { !isPackUrl(it) }
                if (nonPackLinks.isNotEmpty()) {
                    Log.d(TAG, "SUPER-FALLBACK: ${nonPackLinks.size} links -> Episode 1")
                    listOf(newEpisode(nonPackLinks) {
                        name = "Episode 1"; season = seasonNum; episode = 1
                    })
                } else emptyList()
            } else episodes

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year
                this.tags = tags.ifEmpty { null }; this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        } else {
            val links = collectMovieLinks(postBody)
            Log.d(TAG, "Movie: ${links.size} link(s) via collectMovieLinks()")

            val urlList = if (links.isNotEmpty()) {
                links.map { it.second }
            } else {
                val nonPackLinks = allExternalLinks.filter { !isPackUrl(it) }
                if (nonPackLinks.isNotEmpty()) {
                    Log.d(TAG, "FALLBACK: Using ${nonPackLinks.size} raw external links")
                    nonPackLinks
                } else {
                    emptyList()
                }
            }

            Log.d(TAG, "Movie: ${urlList.size} total URL(s) for playback")

            // Use List<String> generic overload — CloudStream handles serialization via toJson()
            // This avoids any JSON double-serialization or fixUrl issues
            return newMovieLoadResponse(title, url, TvType.Movie, urlList) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year
                this.tags = tags.ifEmpty { null }; this.score = score
                imdbUrl?.let { addImdbUrl(it) }
            }
        }
    }

    private fun resolvePoster(doc: org.jsoup.nodes.Document): String? {
        doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let { fixUrlNull(it.attr("src"))?.let { return it } }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let { if (it.isNotBlank()) return it }
        doc.selectFirst("article img.aligncenter")?.let { fixUrlNull(it.attr("src"))?.let { return it } }
        doc.selectFirst("article img[src]")?.let { val s = it.attr("src").trim(); if (s.isNotBlank() && !isNonContentImage(s)) fixUrlNull(s)?.let { return it } }
        doc.selectFirst("article img[data-src]")?.let { val s = it.attr("data-src").trim(); if (s.isNotBlank() && !isNonContentImage(s)) fixUrlNull(s)?.let { return it } }
        return null
    }

    // ------------------------------------------------------------------
    // Movie link collection
    // ------------------------------------------------------------------

    private fun collectMovieLinks(container: Element): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Links inside h3/h4/h5 headings (standard MoviesCounter pattern)
        container.select("h3, h4, h5").forEach { heading ->
            val headingText = heading.text().trim()
            if (headingText.isEmpty()) return@forEach

            // Skip episode headers, packs, samples
            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach
            if (E_NUM_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach
            if (isPackText(headingText)) return@forEach

            heading.select("a[href]").forEach { link ->
                val href = link.attr("href").trim()
                if (!isValidDownloadLink(href) || href in seen) return@forEach
                if (isPackUrl(href)) return@forEach
                seen.add(href)
                results.add(Pair(buildQualityLabel(headingText), href))
            }
        }

        // Strategy 2: All links after "DOWNLOAD LINKS" h2
        if (results.isEmpty()) {
            var pastDownloadSection = false
            for (element in container.select("h2, h3, h4, h5, p, div")) {
                val text = element.text().trim()
                if (text.contains("DOWNLOAD LINKS", true)) { pastDownloadSection = true; continue }
                if (text.contains("Screen-Shots", true)) { pastDownloadSection = false; continue }
                if (text.contains("Single Episode", true)) { pastDownloadSection = false; continue }
                if (!pastDownloadSection) continue
                if (isPackText(text) || SAMPLE_REGEX.containsMatchIn(text)) continue

                element.select("a[href]").forEach { link ->
                    val href = link.attr("href").trim()
                    if (!isValidDownloadLink(href) || href in seen) return@forEach
                    if (isPackUrl(href)) return@forEach
                    seen.add(href)
                    results.add(Pair(buildQualityLabel(text), href))
                }
            }
        }

        // Strategy 3: ALL external download links (last resort)
        if (results.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (!isValidDownloadLink(href) || href in seen) return@forEach
                if (isPackUrl(href)) return@forEach
                seen.add(href)
                val label = anchor.text().trim().ifBlank { "HD" }
                results.add(Pair(label, href))
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Series episode parsing
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(container: Element, defaultSeason: Int): List<Episode> {
        val hasSingleEpSection = container.select("h2, h3").any { el ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(el.text())
        }
        Log.d(TAG, "buildSeriesEpisodes: hasSingleEpSection=$hasSingleEpSection")

        // Pass 1: Element-by-element traversal (most robust for actual HTML structure)
        val episodeMap = parseEpisodesElementTraversal(container, defaultSeason, hasSingleEpSection)
        if (episodeMap.isNotEmpty() && episodeMap.values.any { it.isNotEmpty() }) {
            Log.d(TAG, "Pass 1 succeeded: ${episodeMap.size} episodes")
            return buildEpisodeList(episodeMap)
        }

        // Pass 2: Heading + nextSibling traversal
        Log.d(TAG, "Pass 1 empty, trying nextSibling traversal")
        val fallbackMap = parseEpisodesNextSibling(container, defaultSeason)
        if (fallbackMap.isNotEmpty() && fallbackMap.values.any { it.isNotEmpty() }) {
            Log.d(TAG, "Pass 2 succeeded: ${fallbackMap.size} episodes")
            return buildEpisodeList(fallbackMap)
        }

        return emptyList()
    }

    private fun parseEpisodesElementTraversal(
        container: Element, defaultSeason: Int, hasSingleEpSection: Boolean
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var pastSingleEpisodeSection = false

        val elements = container.select("h2, h3, h4, h5, h6")

        for (element in elements) {
            val text = element.text().trim()
            val tag = element.tagName()
            if (text.isEmpty()) continue

            // Section markers
            if (tag in listOf("h2", "h3")) {
                when {
                    text.contains("DOWNLOAD LINKS", true) -> continue
                    SINGLE_EP_SECTION_REGEX.containsMatchIn(text) -> {
                        pastSingleEpisodeSection = true
                        Log.d(TAG, "Found Single Episode marker")
                        continue
                    }
                    text.contains("Screen-Shots", true) -> continue
                }
            }

            // Skip PACK and SAMPLE headings
            if (isPackText(text) || SAMPLE_REGEX.containsMatchIn(text)) continue

            // Check if any link in this element is a PACK URL
            val elementLinks = element.select("a[href]")
            val hasPackLink = elementLinks.any { isPackUrl(it.attr("href")) }
            if (hasPackLink) continue

            // Only collect links after the Single Episode section marker
            if (hasSingleEpSection && !pastSingleEpisodeSection) continue

            // Episode detection — look for EPiSODE N pattern
            val epFromHeader = EPISODE_HEADER_REGEX.find(text)
            if (epFromHeader != null) {
                val epNum = (epFromHeader.groupValues[1].ifBlank { epFromHeader.groupValues[2] }).toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    detectSeasonNumber(text)?.let { currentSeason = it }
                    Log.d(TAG, "Episode detected: S${currentSeason}E${currentEpisode} from '$text'")
                }
            }

            // Also try E-num pattern (E01, E02, etc.)
            if (epFromHeader == null) {
                E_NUM_REGEX.find(text)?.let { match ->
                    val epNum = match.groupValues[1].toIntOrNull()
                    if (epNum != null) {
                        currentEpisode = epNum
                        detectSeasonNumber(text)?.let { currentSeason = it }
                        Log.d(TAG, "E-num detected: S${currentSeason}E${currentEpisode} from '$text'")
                    }
                }
            }

            // Collect download links from this element
            if (currentEpisode != null) {
                elementLinks.forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (isValidDownloadLink(href) && !isPackUrl(href)) {
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        if (href !in bucket) bucket.add(href)
                    }
                }
            }
        }

        Log.d(TAG, "Pass 1: ${episodeMap.size} episodes, ${episodeMap.values.sumOf { it.size }} links")
        return episodeMap
    }

    private fun parseEpisodesNextSibling(
        container: Element, defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason

        val headings = container.select("h4, h5, h6").toList()
        for (heading in headings) {
            val text = heading.text().trim()
            if (isPackText(text) || SAMPLE_REGEX.containsMatchIn(text)) continue

            val epNum = detectEpisodeNumber(text) ?: continue
            detectSeasonNumber(text)?.let { currentSeason = it }

            val links = mutableListOf<String>()
            heading.select("a[href]").forEach { a ->
                val h = a.attr("href").trim()
                if (isValidDownloadLink(h) && !isPackUrl(h) && h !in links) links.add(h)
            }

            // Links in next siblings until we hit another heading or <hr>
            var sibling = heading.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 10) {
                if (sibling.tagName().matches(Regex("h[1-6]"))) break
                if (isPackText(sibling.text())) break
                if (EPISODE_HEADER_REGEX.containsMatchIn(sibling.text())) break
                if (E_NUM_REGEX.containsMatchIn(sibling.text())) break
                sibling.select("a[href]").forEach { a ->
                    val h = a.attr("href").trim()
                    if (isValidDownloadLink(h) && !isPackUrl(h) && h !in links) links.add(h)
                }
                sibling = sibling.nextElementSibling()
                attempts++
            }

            if (links.isNotEmpty()) {
                val key = currentSeason to epNum
                val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                for (l in links) { if (l !in bucket) bucket.add(l) }
            }
        }

        Log.d(TAG, "Pass 2: ${episodeMap.size} episodes, ${episodeMap.values.sumOf { it.size }} links")
        return episodeMap
    }

    private fun buildEpisodeList(episodeMap: LinkedHashMap<Pair<Int, Int>, MutableList<String>>): List<Episode> {
        return episodeMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, urls) ->
                val (season, ep) = key
                val distinctUrls = urls.distinct()
                // Use List<String> generic overload — CloudStream serializes via toJson()
                // This avoids fixUrl() corruption and JSON double-serialization
                newEpisode(distinctUrls) {
                    name = "Episode $ep"; this.season = season; this.episode = ep
                }
            }
    }

    // ------------------------------------------------------------------
    // Link validation
    // ------------------------------------------------------------------

    private fun isValidDownloadLink(href: String): Boolean {
        if (href.isBlank() || !href.startsWith("http")) return false
        if (IGNORE_HOST_REGEX.containsMatchIn(href)) return false

        // Known download domains always accepted
        if (DOWNLOAD_DOMAINS.any { href.contains(it, true) }) return true

        // Skip self-domain links (unless they look like redirects)
        if (href.contains(mainUrl, true)) {
            return href.contains("?id=", true) || href.contains("/go/", true) || href.contains("/out/", true)
        }

        // Accept any other external URL
        return true
    }

    private fun isPackUrl(href: String): Boolean = PACK_URL_REGEX.containsMatchIn(href)

    private fun isPackText(text: String): Boolean = PACK_TEXT_REGEX.containsMatchIn(text)

    private fun detectEpisodeNumber(text: String): Int? {
        EPISODE_HEADER_REGEX.find(text)?.let { return (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
        E_NUM_REGEX.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun detectSeasonNumber(text: String): Int? {
        return SEASON_REGEX.find(text)?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
    }

    // ------------------------------------------------------------------
    // Quality label builders
    // ------------------------------------------------------------------

    private fun buildQualityLabel(text: String): String {
        val base = extractQualityLabel(text)
        val extras = mutableListOf<String>()
        val baseUpper = base.uppercase()
        SOURCE_PLATFORM_REGEX.find(text)?.groupValues?.get(1)?.let { if (it.uppercase() !in baseUpper) extras.add(it.uppercase()) }
        CODEC_REGEX.findAll(text).forEach { val c = it.groupValues[1].uppercase(); if (c !in baseUpper && c !in extras) extras.add(c) }
        AUDIO_META_REGEX.findAll(text).forEach { val m = it.groupValues[1]; val n = when { m.equals("ESub", true) || m.equals("ESubs", true) -> "ESub"; m.equals("Dual Audio", true) -> "Dual Audio"; else -> m.uppercase() }; if (n !in baseUpper && n !in extras) extras.add(n) }
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
    // Title & series detection
    // ------------------------------------------------------------------

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s*\|\s*Full Movie\s*$"""), "").replace(Regex("""\s*\|\s*Zee5 Series\s*$"""), "")
        .replace(Regex("""\s*\|\s*Full Series\s*$"""), "").replace(Regex("""\s*\|\s*NF Series\s*$"""), "")
        .replace(Regex("""\s*[-|]\s*Movies Counter\s*$"""), "").replace(Regex("""\s*[-–]\s*Movies Counter\s*$"""), "")
        .replace(Regex("""\s*DS4K\s+"""), " ").replace(Regex("""\s*(WEB-DL|WEBRip|HDRip|BluRay|BRRip)\s*""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\[(?:Hindi|DD5\.1|x264|HEVC|10Bit|ESubs)[^\]]*\]"""), "")
        .replace(Regex("""\d+p\s*(?:HEVC|x264|10Bit)?\s*[\[(]"""), "").trim()

    private fun detectSeriesFromTitle(title: String, href: String): Boolean {
        return title.contains("Season", true) || title.contains("WEB-Series", true) ||
                title.contains("TV Series", true) || title.contains("Web Series", true) ||
                title.contains("NF Series", true) || title.contains("Zee5 Series", true) ||
                Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
                href.contains("/category/web-series/", true) || href.contains("/category/tv-shows/", true)
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl", "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd", "300mb")
        val lower = title.lowercase()
        for (tok in tokens) { if (lower.contains(tok)) return getQualityFromString(tok) }
        return null
    }

    // ------------------------------------------------------------------
    // loadLinks
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "=== loadLinks() START ===")
        Log.d(TAG, "Raw data (first 300): ${data.take(300)}")

        if (data.isBlank()) { Log.w(TAG, "loadLinks: data is blank!"); return false }

        // Try parsing as JSON List<String> (CloudStream serializes List<String> via toJson())
        val linksList = tryParseJson<List<String>>(data)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.startsWith("http") }
            ?: emptyList()

        Log.d(TAG, "Parsed ${linksList.size} link(s) from JSON")

        if (linksList.isNotEmpty()) {
            linksList.amap { resolveAndLoad(it, subtitleCallback, callback) }
            return true
        }

        // Fallback: try to parse data manually
        val manualLinks = data
            .removeSurrounding("[", "]")
            .split(Regex(""",\s*"""))
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.startsWith("http") }
            .distinct()

        Log.d(TAG, "Manual parse: ${manualLinks.size} link(s)")

        if (manualLinks.isNotEmpty()) {
            manualLinks.amap { resolveAndLoad(it, subtitleCallback, callback) }
            return true
        }

        // Legacy: try line/pipe separated
        val legacyLinks = data.lines().flatMap { it.split("|") }.map { it.trim() }.filter { it.startsWith("http") }
        Log.d(TAG, "Legacy fallback: ${legacyLinks.size} link(s)")
        if (legacyLinks.isEmpty()) return false
        legacyLinks.amap { resolveAndLoad(it, subtitleCallback, callback) }
        return true
    }

    private suspend fun resolveAndLoad(
        url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(TAG, "resolveAndLoad: $url")
            when {
                // mclinks.xyz — intermediary page that links to hubdrive/hubcloud/hubcdn
                url.contains("mclinks", true) ->
                    mclinks.getUrl(url, name, subtitleCallback, callback)
                // hubdrive.space — resolves to hubcloud/hubcdn
                url.contains("hubdrive", true) ->
                    hubdrive.getUrl(url, name, subtitleCallback, callback)
                // hubcloud.foo — main download resolver
                url.contains("hubcloud", true) ->
                    hubcloud.getUrl(url, name, subtitleCallback, callback)
                // hubcdn.sbs — redirect to obsession.buzz CDN
                url.contains("hubcdn", true) ->
                    hubcdn.getUrl(url, name, subtitleCallback, callback)
                // hblinks.dad — aggregator
                url.contains("hblinks", true) ->
                    hblinks.getUrl(url, name, subtitleCallback, callback)
                // hdstream4u.com — VidHidePro
                url.contains("hdstream4u", true) ->
                    hdstream4u.getUrl(url, name, subtitleCallback, callback)
                // obsession.buzz — direct CDN link (video file)
                url.contains("obsession.buzz", true) ->
                    resolveObsessionBuzz(url, subtitleCallback, callback)
                // WordPress redirect
                url.contains("?id=", true) -> {
                    val resolved = resolveWordPressRedirect(url)
                    if (resolved != null) resolveAndLoad(resolved, subtitleCallback, callback)
                    else loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
                // Default: try loadExtractor, then direct link
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

    /**
     * Resolve hub.obsession.buzz URLs — these are direct CDN video files.
     * They return HTTP 200 with content-disposition attachment header.
     * Do NOT try to parse as HTML (it's a 500MB+ video file).
     */
    private suspend fun resolveObsessionBuzz(
        url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            // First try: check if it's a redirect (some obsession.buzz URLs redirect)
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://hubcdn.sbs/"
            ), allowRedirects = false, timeout = 10_000L)

            val location = resp.headers["Location"] ?: resp.headers["location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                Log.d(TAG, "obsession.buzz redirect -> $location")
                resolveAndLoad(location, subtitleCallback, callback)
                return
            }

            // It's a direct video file (HTTP 200) — use as-is
            // Extract quality from URL if possible
            val quality = when {
                url.contains("2160p", true) || url.contains("4K", true) -> Qualities.P2160.value
                url.contains("1080p", true) -> Qualities.P1080.value
                url.contains("720p", true) -> Qualities.P720.value
                url.contains("480p", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            callback(newExtractorLink("MoviesCounter", "Direct [CDN]", url, INFER_TYPE) {
                this.quality = quality
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveObsessionBuzz failed: ${e.message}")
            // Last resort: add as direct link
            callback(newExtractorLink("MoviesCounter", "Direct [CDN]", url, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
        }
    }

    private suspend fun resolveWordPressRedirect(url: String): String? {
        return try {
            val doc = app.get(url, headers = headers, timeout = 10_000L).document
            val downloadDomains = listOf("hubdrive", "hubcloud", "hubcdn", "mclinks", "hblinks", "hdstream4u")
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && downloadDomains.any { href.contains(it, true) }) {
                    return href
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private suspend fun addDirectLink(url: String, callback: (ExtractorLink) -> Unit, qualityLabel: String = "") {
        val quality = when {
            url.contains("2160p", true) || url.contains("4K", true) -> Qualities.P2160.value
            url.contains("1080p", true) -> Qualities.P1080.value
            url.contains("720p", true) -> Qualities.P720.value
            url.contains("480p", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        val label = if (qualityLabel.isNotBlank()) qualityLabel else "Direct"
        callback(newExtractorLink("MoviesCounter", label, url, INFER_TYPE) {
            this.quality = quality
        })
    }
}
