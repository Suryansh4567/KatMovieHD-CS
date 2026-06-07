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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import org.jsoup.nodes.Element

/**
 * MoviesCounter CloudStream Provider — v34
 *
 * COMPLETE REWRITE — built from scratch by studying the ACTUAL site HTML.
 *
 * Site structure (confirmed from live HTML):
 * - Homepage: Tailwind cards with div.inline-flex.flex-col > a > img
 * - Movie detail: h3/h4 headings with <a> download links
 * - Series detail: "DOWNLOAD LINKS" section (full-season links),
 *   then "Single Episode x264 Links" with EPiSODE headers + quality rows
 *
 * Link chain:
 * - mclinks.xyz → hubcloud.foo/hubdrive.space (image links, not text)
 * - hubdrive.space → hubcloud.foo (needs login, but shows HubCloud link)
 * - hubcloud.foo → gamerxyt.com → FSL/S3/PixelDrain/BuzzServer buttons
 * - hubcdn.sbs/dl/?link=hub.obsession.buzz/{hash} → direct MKV file
 * - hubstream.art/#hash → streaming player
 *
 * CRITICAL insight: newEpisode(List<String>) stores data as JSON.
 * loadLinks() must parse it back with tryParseJson<List<String>>.
 * Pipe format "url|qualityLabel" preserves quality through the chain.
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

        private val DOMAIN_TLDS = listOf("boston", "fit", "wiki", "one", "vip", "lol", "cc")

        // TMDB for episode metadata
        private const val TMDB_API_KEY = "b0a40f4a5d03e4a1f128e5d89d4a2b32"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300"

        // Episode detection
        private val EPISODE_REGEX = Regex("""(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b""")
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")
        private val SINGLE_EP_REGEX = Regex("""(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise|Single\s*Ep|Episode\s*Links""")

        // Quality detection
        private val QUALITY_REGEX = Regex("""(?i)(4K|2160p|1080p|720p|480p|300MB)""")
        private val QUALITY_LINE_REGEX = Regex("""(?i)^(4K|2160p|1080p|720p|480p)\s*[–\-]""")
        private val PACK_REGEX = Regex("""(?i)\bPACK\b|\bPack\s|Pack\s*\[|\bComplete\s+Season\b|\bFull\s+Season\b|\bSeason\s+Pack\b""")
        private val PACK_URL_REGEX = Regex("""/packs/""", RegexOption.IGNORE_CASE)

        // Filter non-download URLs
        private val IGNORE_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgshare|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """moviescounter|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|effectivecpm|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png\b|\.jpg\b|\.jpeg\b|\.gif\b|\.webp\b|\.svg\b|\.ico\b|\.zip\b|""" +
                    """\.css\b|\.js\b|/feed/|""" +
                    """doubleclick|popads|propeller|profitablecpm|""" +
                    """adsboosters|admaven|winexch|a-ads|tinyurl|hdhub4u|inventoryidea|""" +
                    """bonuscaf|snvhost|llvpn|use\.fontawesome|cdnjs""" +
                    """)"""
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
                    val doc2 = fetchWithFallback("$mainUrl/page/2/?s=$encoded")
                    results.addAll(parseListing(doc2))
                } catch (_: Exception) {}
            }
        }

        return results.distinctBy { it.url }
    }

    private suspend fun fetchWithFallback(
        url: String,
        timeout: Long = 30_000L
    ): org.jsoup.nodes.Document {
        try {
            return app.get(url, headers = headers, timeout = timeout).document
        } catch (e: Exception) {
            Log.w(TAG, "Primary domain failed: ${e.message}")
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

        // Site uses Tailwind cards: div.inline-flex.flex-col > a
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

        // Fallback
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
                href.contains("disclaimer", ignoreCase = true) ||
                href.contains("privacy-policy", ignoreCase = true) ||
                href.contains("contact", ignoreCase = true)
    }

    private fun isNonContentImage(src: String): Boolean {
        return src.contains("logo", ignoreCase = true) ||
                src.contains("favicon", ignoreCase = true) ||
                src.contains("badge", ignoreCase = true) ||
                src.contains("banner", ignoreCase = true) ||
                src.contains("wp-content/themes", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // load() - Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchWithFallback(url)

        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)
        val posterUrl = resolvePoster(doc)

        val postBody = doc.selectFirst("div.post-body")
            ?: doc.selectFirst("div.entry-content")
            ?: doc.selectFirst("article")
            ?: doc

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
        val hasEpisodeHeaders = postBody.select("h3, h4, h5").any { el ->
            EPISODE_REGEX.containsMatchIn(el.text())
        }
        val hasSingleEpSection = postBody.select("h2, h3").any { h2 ->
            SINGLE_EP_REGEX.containsMatchIn(h2.text())
        }

        val isSeries = titleIndicatesSeries || tagIndicatesSeries || hasSingleEpSection || hasEpisodeHeaders
        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load() title='$title' isSeries=$isSeries season=$seasonNum")

        if (isSeries) {
            val tmdbEpisodes = fetchTmdbEpisodes(imdbUrl, seasonNum)
            val episodes = buildSeriesEpisodes(postBody, seasonNum, posterUrl, tmdbEpisodes)
            Log.d(TAG, "load() built ${episodes.size} episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                // Fallback: all non-PACK external links → Episode 1
                val links = collectAllDownloadLinks(postBody)
                    .filter { !PACK_REGEX.containsMatchIn(it.first) }
                    .filter { !PACK_URL_REGEX.containsMatchIn(it.second) }
                if (links.isNotEmpty()) {
                    listOf(newEpisode(links.map { it.second }) {
                        name = tmdbEpisodes[1]?.name ?: "Episode 1"
                        this.season = seasonNum
                        this.episode = 1
                        this.posterUrl = tmdbEpisodes[1]?.posterUrl ?: posterUrl
                        this.description = tmdbEpisodes[1]?.overview
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
            // Movie: collect all download links
            val links = collectAllDownloadLinks(postBody)
                .filter { !PACK_REGEX.containsMatchIn(it.first) }
                .filter { !PACK_URL_REGEX.containsMatchIn(it.second) }
            val urlList = links.map { it.second }

            Log.d(TAG, "load() found ${urlList.size} download link(s) for movie")

            return newMovieLoadResponse(title, url, TvType.Movie, urlList) {
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
    // TMDB Episode Metadata
    // ------------------------------------------------------------------

    data class TmdbEpisodeInfo(
        val name: String? = null,
        val overview: String? = null,
        val posterUrl: String? = null
    )

    private suspend fun fetchTmdbEpisodes(
        imdbUrl: String?,
        seasonNum: Int
    ): Map<Int, TmdbEpisodeInfo> {
        if (imdbUrl.isNullOrBlank()) return emptyMap()

        try {
            val imdbId = Regex("""/title/(tt\d+)""").find(imdbUrl)?.groupValues?.get(1)
            if (imdbId.isNullOrBlank()) return emptyMap()

            Log.d(TAG, "fetchTmdbEpisodes: imdbId=$imdbId season=$seasonNum")

            val findUrl = "$TMDB_BASE/find/$imdbId?external_source=imdb_id&api_key=$TMDB_API_KEY"
            val findResponse = app.get(findUrl, timeout = 10_000L).text
            val findData = tryParseJson<TmdbFindResponse>(findResponse)
            val tvId = findData?.tv_results?.firstOrNull()?.id
            if (tvId == null) {
                Log.w(TAG, "TMDB: No TV show found for IMDB ID $imdbId")
                return emptyMap()
            }

            val seasonUrl = "$TMDB_BASE/tv/$tvId/season/$seasonNum?api_key=$TMDB_API_KEY"
            val seasonResponse = app.get(seasonUrl, timeout = 10_000L).text
            val seasonData = tryParseJson<TmdbSeasonResponse>(seasonResponse)

            if (seasonData?.episodes.isNullOrEmpty()) {
                Log.w(TAG, "TMDB: No episodes found for season $seasonNum")
                return emptyMap()
            }

            val result = seasonData!!.episodes!!.associate { ep ->
                val epNum = ep.episode_number ?: 0
                val poster = ep.still_path?.let { "$TMDB_IMAGE_BASE$it" }
                epNum to TmdbEpisodeInfo(
                    name = ep.name,
                    overview = ep.overview,
                    posterUrl = poster
                )
            }

            Log.d(TAG, "TMDB: Fetched ${result.size} episode metadata entries")
            return result

        } catch (e: Exception) {
            Log.w(TAG, "TMDB fetch failed: ${e.message}")
            return emptyMap()
        }
    }

    data class TmdbFindResponse(val tv_results: List<TmdbTvResult>? = null)
    data class TmdbTvResult(val id: Int? = null)
    data class TmdbSeasonResponse(val episodes: List<TmdbEpisodeData>? = null)
    data class TmdbEpisodeData(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null
    )

    // ------------------------------------------------------------------
    // Poster resolution
    // ------------------------------------------------------------------

    private fun resolvePoster(doc: org.jsoup.nodes.Document): String? {
        // OG image (most reliable)
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        // TMDB image in article
        doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }
        // Aligncenter image
        doc.selectFirst("article img.aligncenter")?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) {
                fixUrlNull(src)?.let { return it }
            }
        }
        // Any article image
        doc.selectFirst("article img[src]")?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) {
                fixUrlNull(src)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Download link collection — SIMPLIFIED based on actual site HTML
    //
    // Site pattern (confirmed):
    // <h3><a href="mclinks.xyz/archives/XXX">480p x264 [2.4GB]</a></h3>
    // <h4><a href="hubdrive.space/file/XXX">720p HEVC [1.9GB]</a></h4>
    // <h4><a href="hubcloud.foo/drive/XXX">HQ 1080p [4.4GB]</a></h4>
    // ------------------------------------------------------------------

    private fun collectAllDownloadLinks(container: Element): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Collect from ALL headings that have external links
        container.select("h2, h3, h4, h5").forEach { heading ->
            val headingText = heading.text().trim()
            if (headingText.isEmpty()) return@forEach

            // Skip section headers and non-download headings
            if (headingText.contains("Screen-Shots", ignoreCase = true)) return@forEach
            if (headingText.contains("Storyline", ignoreCase = true)) return@forEach
            if (headingText.contains("Review", ignoreCase = true)) return@forEach
            if (headingText.contains("Watch Online", ignoreCase = true)) return@forEach
            if (headingText.contains("DOWNLOAD LINKS", ignoreCase = true)) return@forEach
            if (headingText.contains("Single Episode", ignoreCase = true)) return@forEach
            if (headingText.contains("Download ", ignoreCase = true) &&
                heading.select("a[href]").isEmpty()) return@forEach

            // For SERIES: skip EPiSODE headers (they're handled separately)
            // Only skip standalone EPiSODE headers (no links inside)
            if (EPISODE_REGEX.containsMatchIn(headingText) &&
                heading.select("a[href]").isEmpty()) return@forEach

            heading.select("a[href]").forEach { link ->
                val href = link.attr("href").trim()
                if (!isValidUrl(href)) return@forEach
                if (href in seen) return@forEach

                seen.add(href)

                // Build quality label from heading text
                val quality = extractQualityFromHeading(headingText)
                results.add(Pair(quality, href))
            }
        }

        // Fallback: scan all links if nothing found
        if (results.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (!isValidUrl(href)) return@forEach
                if (href in seen) return@forEach

                seen.add(href)
                results.add(Pair("", href))
            }
        }

        return results
    }

    private fun isValidUrl(href: String): Boolean {
        if (href.isBlank() || !href.startsWith("http")) return false
        if (IGNORE_REGEX.containsMatchIn(href)) return false
        if (href.contains(mainUrl, ignoreCase = true)) return false
        if (PACK_URL_REGEX.containsMatchIn(href)) return false
        return true
    }

    private fun extractQualityFromHeading(text: String): String {
        val parts = mutableListOf<String>()
        QUALITY_REGEX.find(text)?.groupValues?.get(1)?.let { parts.add(it) }
        if (text.contains("HEVC", ignoreCase = true) || text.contains("x265", ignoreCase = true)) parts.add("HEVC")
        if (text.contains("x264", ignoreCase = true)) parts.add("x264")
        if (text.contains("WEB-DL", ignoreCase = true)) parts.add("WEB-DL")
        if (text.contains("WEBRip", ignoreCase = true)) parts.add("WEBRip")
        if (text.contains("DD5.1", ignoreCase = true) || text.contains("DDP5.1", ignoreCase = true)) parts.add("DD5.1")
        if (text.contains("ESub", ignoreCase = true) || text.contains("ESubs", ignoreCase = true)) parts.add("ESubs")
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // Series episode parsing — SIMPLIFIED
    //
    // Actual site structure (confirmed from live HTML):
    //
    // : DOWNLOAD LINKS :
    // <h3><a href="mclinks.xyz/archives/XXX">480p x264 [2.4GB]</a></h3>   ← full-season
    // <h3><a href="mclinks.xyz/archives/XXX">720p x264 [6GB]</a></h3>     ← full-season
    // <h3><a href="mclinks.xyz/archives/XXX">1080p x264 [13.2GB]</a></h3>  ← full-season
    // <h4><a href="hubdrive.space/packs/XXX">1080p PACK [36.2GB]</a></h4>  ← PACK (skip)
    // <h4><a href="hubdrive.space/packs/XXX">4K PACK [87.4GB]</a></h4>     ← PACK (skip)
    //
    // : Single Episode x264 Links :
    // <h4><strong>EPiSODE 1</strong></h4>            ← standalone header
    // <h4>720p – <a>Drive</a>  <a>Instant</a></h4>   ← quality row
    // <h4>1080p – <a>Drive</a>  <a>Instant</a></h4>  ← quality row
    // <h4><strong>EPiSODE 2</strong></h4>
    // ...
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int,
        showPosterUrl: String?,
        tmdbEpisodes: Map<Int, TmdbEpisodeInfo>
    ): List<Episode> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null

        var pastDownloadSection = false
        var pastSingleEpSection = false

        // Pre-episode links (full-season links between "DOWNLOAD LINKS" and "Single Episode")
        val fullSeasonLinks = mutableListOf<String>()

        val elements = container.select("h2, h3, h4, h5")

        for (element in elements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            // Section markers
            if (text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                pastDownloadSection = true
                continue
            }
            if (SINGLE_EP_REGEX.containsMatchIn(text)) {
                pastSingleEpSection = true
                continue
            }
            if (text.contains("Screen-Shots", ignoreCase = true)) continue
            if (text.contains("Storyline", ignoreCase = true)) continue
            if (text.contains("Review", ignoreCase = true)) continue
            if (text.contains("Watch Online", ignoreCase = true)) continue
            if (text.contains("Download ", ignoreCase = true) &&
                element.select("a[href]").isEmpty()) continue

            // PACK filtering — skip PACK headings and PACK URLs
            if (PACK_REGEX.containsMatchIn(text)) continue
            if (element.select("a[href]").any { PACK_URL_REGEX.containsMatchIn(it.attr("href")) }) continue

            // Skip "New Episode Every Monday" etc.
            if (text.contains("New Episode", ignoreCase = true)) continue
            if (text.contains("Releasing SooN", ignoreCase = true)) continue

            // ---- Detect EPiSODE header ----
            val epMatch = EPISODE_REGEX.find(text)
            if (epMatch != null) {
                val epNum = (epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }

                    // Pattern A: EPiSODE link inside header
                    // e.g. <h3><a href="mclinks.xyz/archives/XXX">EPiSODE 1</a></h3>
                    val linksInHeader = element.select("a[href]")
                    if (linksInHeader.isNotEmpty()) {
                        for (anchor in linksInHeader) {
                            val href = anchor.attr("href").trim()
                            if (isValidUrl(href)) {
                                val key = currentSeason to currentEpisode
                                val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                                if (bucket.none { it.substringBefore("|") == href }) {
                                    bucket.add(href)
                                }
                            }
                        }
                        continue
                    }

                    // Pattern B: standalone EPiSODE header, no links
                    // Quality rows follow in subsequent h4 elements
                    continue
                }
            }

            // ---- Collect quality links for current episode ----
            if (currentEpisode != null && (pastSingleEpSection || pastDownloadSection)) {
                val links = element.select("a[href]")
                if (links.isNotEmpty()) {
                    // Extract quality prefix: "720p –" → "720p"
                    val qualityPrefix = QUALITY_LINE_REGEX.find(text)?.groupValues?.get(1)
                        ?: QUALITY_REGEX.find(text)?.groupValues?.get(1)

                    for (anchor in links) {
                        val href = anchor.attr("href").trim()
                        if (!isValidUrl(href)) continue

                        // Build quality label: "720p Drive", "1080p Instant"
                        val mirrorLabel = anchor.text().trim()
                        val qualityLabel = when {
                            qualityPrefix != null && mirrorLabel.isNotEmpty() ->
                                "$qualityPrefix $mirrorLabel"
                            qualityPrefix != null -> qualityPrefix
                            mirrorLabel.isNotEmpty() -> mirrorLabel
                            else -> null
                        }

                        val entry = if (qualityLabel != null) "$href|$qualityLabel" else href
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        val urlOnly = entry.substringBefore("|")
                        if (bucket.none { it.substringBefore("|") == urlOnly }) {
                            bucket.add(entry)
                        }
                    }
                }
            } else if (currentEpisode == null && pastDownloadSection && !pastSingleEpSection) {
                // Full-season links between "DOWNLOAD LINKS" and "Single Episode"
                val links = element.select("a[href]")
                if (links.isNotEmpty()) {
                    val qualityPrefix = QUALITY_LINE_REGEX.find(text)?.groupValues?.get(1)
                        ?: QUALITY_REGEX.find(text)?.groupValues?.get(1)

                    for (anchor in links) {
                        val href = anchor.attr("href").trim()
                        if (!isValidUrl(href)) continue

                        val mirrorLabel = anchor.text().trim()
                        val qualityLabel = when {
                            qualityPrefix != null && mirrorLabel.isNotEmpty() ->
                                "$qualityPrefix $mirrorLabel"
                            qualityPrefix != null -> qualityPrefix
                            mirrorLabel.isNotEmpty() -> mirrorLabel
                            else -> null
                        }

                        val entry = if (qualityLabel != null) "$href|$qualityLabel" else href
                        val urlOnly = entry.substringBefore("|")
                        if (fullSeasonLinks.none { it.substringBefore("|") == urlOnly }) {
                            fullSeasonLinks.add(entry)
                        }
                    }
                }
            }
        }

        // Add full-season links to Episode 1
        if (fullSeasonLinks.isNotEmpty()) {
            val ep1Key = defaultSeason to 1
            val ep1Bucket = episodeMap.getOrPut(ep1Key) { mutableListOf() }
            val newLinks = fullSeasonLinks.filter { fs ->
                ep1Bucket.none { it.substringBefore("|") == fs.substringBefore("|") }
            }
            ep1Bucket.addAll(0, newLinks)
            Log.d(TAG, "Added ${newLinks.size} full-season links to Episode 1")
        }

        // Build Episode objects with TMDB metadata
        val episodes = mutableListOf<Episode>()

        for ((key, entries) in episodeMap.entries.sortedWith(
            compareBy({ it.key.first }, { it.key.second })
        )) {
            val (season, ep) = key
            if (entries.isEmpty()) continue

            val tmdbInfo = tmdbEpisodes[ep]
            episodes.add(newEpisode(entries.distinct()) {
                name = tmdbInfo?.name ?: "Episode $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = tmdbInfo?.posterUrl ?: showPosterUrl
                this.description = tmdbInfo?.overview
            })
        }

        Log.d(TAG, "buildSeriesEpisodes: ${episodes.size} episodes")
        return episodes
    }

    // ------------------------------------------------------------------
    // Utility functions
    // ------------------------------------------------------------------

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\|\s*Full Movie\s*$"""), "")
            .replace(Regex("""\s*\|\s*(Zee5|NF|PrimeVideo|JioCinema|Voot|Disney\+?Hotstar)\s+Series\s*$"""), "")
            .replace(Regex("""\s*\|\s*Full Series\s*$"""), "")
            .replace(Regex("""\s*[-|–]\s*Movies Counter\s*$"""), "")
            .replace(Regex("""\s*DS4K\s+"""), " ")
            .replace(Regex("""\s*(WEB-DL|WEBRip|HDRip|BluRay|BRRip)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[(?:Hindi|DD5\.1|x264|HEVC|10Bit|ESubs|Dual Audio)[^\]]*\]"""), "")
            .trim()
    }

    private fun detectSeriesFromTitle(title: String, href: String): Boolean {
        return title.contains("Season", ignoreCase = true) ||
                title.contains("WEB-Series", ignoreCase = true) ||
                title.contains("TV Series", ignoreCase = true) ||
                title.contains("Web Series", ignoreCase = true) ||
                title.contains("NF Series", ignoreCase = true) ||
                title.contains("Zee5 Series", ignoreCase = true) ||
                title.contains("PrimeVideo Series", ignoreCase = true) ||
                Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
                href.contains("/category/web-series/", ignoreCase = true) ||
                href.contains("/category/tv-shows/", ignoreCase = true)
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf(
            "2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd", "300mb"
        )
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    // ------------------------------------------------------------------
    // loadLinks — resolves URLs through appropriate extractors
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Parse JSON list of URLs (from newMovieLoadResponse or newEpisode)
        val linksList = tryParseJson<List<String>>(data)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (linksList.isEmpty()) {
            // Legacy fallback
            val legacyLinks = data.lines()
                .map { it.trim() }
                .filter { it.startsWith("http") }
            if (legacyLinks.isEmpty()) return false
            legacyLinks.amap { link -> resolveLink(link, null, subtitleCallback, callback) }
            return true
        }

        Log.d(TAG, "loadLinks(): resolving ${linksList.size} link(s)")
        linksList.amap { entry ->
            val pipeIdx = entry.indexOf("|")
            if (pipeIdx > 0 && entry.startsWith("http")) {
                val url = entry.substring(0, pipeIdx)
                val qualityLabel = entry.substring(pipeIdx + 1)
                resolveLink(url, qualityLabel, subtitleCallback, callback)
            } else {
                resolveLink(entry, null, subtitleCallback, callback)
            }
        }

        return linksList.isNotEmpty()
    }

    /**
     * Route URL to the correct extractor based on domain.
     */
    private suspend fun resolveLink(
        url: String,
        qualityLabel: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val lower = url.lowercase()
            when {
                // HubDrive → resolves to HubCloud/HUBCDN
                "hubdrive.space" in lower || "hubdrive.link" in lower || "hubdrive.me" in lower -> {
                    Hubdrive().getUrl(url, name, subtitleCallback, callback)
                }

                // HubCloud → gamerxyt → download buttons
                "hubcloud.foo" in lower || "hubcloud.cx" in lower ||
                "hubcloud.fans" in lower || "hubcloud.lol" in lower -> {
                    HubCloud().getUrl(url, name, subtitleCallback, callback)
                }

                // HUBCDN → obsession.buzz → direct video
                "hubcdn.org" in lower || "hubcdn.sbs" in lower || "hubcdn.fans" in lower -> {
                    HUBCDN().getUrl(url, name, subtitleCallback, callback)
                }

                // Mclinks → hubcloud/hubdrive/hubcdn
                "mclinks.xyz" in lower -> {
                    Mclinks().getUrl(url, name, subtitleCallback, callback)
                }

                // Hblinks → hubcloud/hubdrive/hubcdn
                "hblinks" in lower -> {
                    Hblinks().getUrl(url, name, subtitleCallback, callback)
                }

                // HdStream4u → VidHidePro
                "hdstream4u" in lower -> {
                    HdStream4u().getUrl(url, name, subtitleCallback, callback)
                }

                // HubStream → try loadExtractor or direct
                "hubstream.art" in lower -> {
                    if (!loadExtractor(url, "", subtitleCallback, callback)) {
                        callback(newExtractorLink(name, "Stream", url) {
                            this.quality = getQualityFromLabel(qualityLabel)
                        })
                    }
                }

                // Obsession/noirspy → direct video file
                "obsession.buzz" in lower || "noirspy.buzz" in lower -> {
                    callback(newExtractorLink(name, "Direct [CDN]", url) {
                        this.quality = getQualityFromLabel(qualityLabel)
                    })
                }

                // FSL CDN → direct video
                "fsl-buckets" in lower || "cdn.fsl" in lower -> {
                    callback(newExtractorLink(name, "FSL CDN", url) {
                        this.quality = getQualityFromLabel(qualityLabel)
                    })
                }

                // Everything else → try loadExtractor
                else -> {
                    if (!loadExtractor(url, "", subtitleCallback, callback)) {
                        // Last resort: direct link
                        callback(newExtractorLink(name, "Direct", url) {
                            this.quality = getQualityFromLabel(qualityLabel)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveLink failed for $url: ${e.message}")
        }
    }

    private fun getQualityFromLabel(label: String?): Int {
        if (label.isNullOrBlank()) return Qualities.Unknown.value
        return when {
            label.contains("4K", ignoreCase = true) || label.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720p", ignoreCase = true) -> Qualities.P720.value
            label.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
