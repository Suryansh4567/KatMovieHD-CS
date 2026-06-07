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
 * MoviesCounter CloudStream Provider — v33
 *
 * CRITICAL FIXES from v32:
 * - FIXED: Pre-episode full-season quality links (480p/720p/1080p via mclinks,
 *   720p/1080p HEVC via hubdrive) were LOST between "DOWNLOAD LINKS" and the
 *   first EPiSODE header because currentEpisode was null. These are now
 *   collected in a fullSeasonLinks bucket and prepended to Episode 1.
 * - FIXED: IGNORE_HOST_REGEX contained standalone `#` which incorrectly
 *   filtered valid streaming URLs like https://hubstream.art/#xsvrxc.
 *   Anchor-only hrefs are already filtered by startsWith("http") check.
 * - FIXED: HubCloud extractor was getting 403 from hubcloud.foo because
 *   it didn't send proper User-Agent and Referer headers. Now sends both.
 * - FIXED: Hubdrive and HUBCDN extractors now also send proper User-Agent.
 * - ADDED: hubstream.art support in resolveAndLoad() — handles hash-based
 *   SPA streaming URLs that were previously filtered.
 * - ADDED: gofile.io handling in Mclinks extractor.
 * - ADDED: noirspy.buzz as recognized CDN domain in Mclinks extractor.
 *
 * Chain: MoviesCounter -> mclinks.xyz/hubdrive.space/hubcloud.foo -> hubcloud.foo -> gamerxyt.com -> download
 * Alt:   hubcdn.sbs/dl/?link=obsession.buzz -> direct video file
 * Alt:   hdstream4u.com -> VidHidePro
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

        // Active domains confirmed: .boston (primary), .fit (mirror)
        private val DOMAIN_TLDS = listOf("boston", "fit", "wiki", "one", "vip", "lol", "cc")

        // TMDB API for episode metadata (names, posters, descriptions)
        private const val TMDB_API_KEY = "b0a40f4a5d03e4a1f128e5d89d4a2b32"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300"

        // Episode detection — handles "EPiSODE 1", "Episode 1", "E01", etc.
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )
        private val E_NUM_REGEX = Regex("""\bE(\d{1,3})\b(?!\d)""")
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise|Single\s*Ep|Episode\s*Links"""
        )

        // PACK detection — both text and URL patterns
        private val PACK_REGEX = Regex(
            """(?i)\bPACK\b|\bPack\s|Pack\s*\[|\bComplete\s+Season\b|\bFull\s+Season\b|\bSeason\s+Pack\b"""
        )
        private val PACK_URL_REGEX = Regex("""/packs/""", RegexOption.IGNORE_CASE)
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

        private val QUALITY_REGEX = Regex("""(?i)(4K|2160p|1080p|720p|480p|300MB)""")
        private val QUALITY_LINE_REGEX = Regex(
            """(?i)^(4K|2160p|1080p|720p|480p)\s*[–\-]"""
        )
        private val AUDIO_META_REGEX = Regex(
            """(?i)(DD5\.1|DDP5\.1|ATMOS|AAC5\.1|5\.1|2\.0|ESub|ESubs|Hindi|English|Dual\s*Audio)"""
        )
        private val SOURCE_PLATFORM_REGEX = Regex(
            """(?i)\b(AMZN|NF|DSNP|HMAX|APTV|PMTP|iP|HULU|BBC|ITV|Crav|STAN|BFI|CC)\b"""
        )
        private val CODEC_REGEX = Regex(
            """(?i)\b(x264|x265|HEVC|H264|H265|AV1|10Bit|8Bit|HDR|SDR|DV|HDRip)\b"""
        )

        // IGNORE_HOST_REGEX — filters non-download URLs
        // v33 FIX: Removed standalone `#` from regex — it was incorrectly filtering
        // valid streaming URLs like https://hubstream.art/#xsvrxc. Anchor-only
        // hrefs (e.g. "#section") are already filtered by the `startsWith("http")`
        // check in isValidDownloadLink().
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
                    """.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|\.zip|""" +
                    """.css|\.js|/feed/|""" +
                    """doubleclick|popads|propeller|profitablecpm|""" +
                    """adsboosters|admaven|winexch|a-ads|tinyurl|hdhub4u|inventoryidea|""" +
                    """bonuscaf|snvhost|llvpn""" +
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
                    val page2Url = "$mainUrl/page/2/?s=$encoded"
                    val doc2 = fetchWithFallback(page2Url)
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

        // Strategy 1: Tailwind card layout (confirmed site structure)
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

        // ---------------------------------------------------------------
        // Series detection
        // ---------------------------------------------------------------
        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)
        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
                    it.equals("TV-Shows", true) ||
                    it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }

        // Check for EPiSODE headers — both standalone AND inside links (Pattern A fix)
        val hasEpisodeHeaders = postBody.select("h3, h4, h5").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text())
        }
        val hasSingleEpisodeSection = postBody.select("h2, h3").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        val isSeries = titleIndicatesSeries || tagIndicatesSeries || hasSingleEpisodeSection || hasEpisodeHeaders

        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load() title='$title' isSeries=$isSeries season=$seasonNum")

        if (isSeries) {
            // Fetch TMDB episode metadata for rich episode info
            val tmdbEpisodes = fetchTmdbEpisodes(imdbUrl, seasonNum)

            val episodes = buildSeriesEpisodes(postBody, seasonNum, posterUrl, tmdbEpisodes)
            Log.d(TAG, "load() built ${episodes.size} episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                // Super-fallback: all external links -> Episode 1
                val links = collectDownloadLinks(postBody)
                val nonPackLinks = links.map { it.second }.filter { !PACK_URL_REGEX.containsMatchIn(it) }
                if (nonPackLinks.isNotEmpty()) {
                    listOf(newEpisode(nonPackLinks) {
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
            // Movie: pass List<String> of URLs — same professional pattern as v26
            val links = collectDownloadLinks(postBody)
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
    // Fetches episode names, posters, descriptions from TMDB API
    // Falls back gracefully if API unavailable
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
            // Extract IMDB ID from URL (e.g., "https://www.imdb.com/title/tt1234567/" -> "tt1234567")
            val imdbId = Regex("""/title/(tt\d+)""").find(imdbUrl)?.groupValues?.get(1)
            if (imdbId.isNullOrBlank()) return emptyMap()

            Log.d(TAG, "fetchTmdbEpisodes: imdbId=$imdbId season=$seasonNum")

            // Step 1: Find TMDB TV show ID from IMDB ID
            val findUrl = "$TMDB_BASE/find/$imdbId?external_source=imdb_id&api_key=$TMDB_API_KEY"
            val findResponse = app.get(findUrl, timeout = 10_000L).text
            val findData = tryParseJson<TmdbFindResponse>(findResponse)
            val tvId = findData?.tv_results?.firstOrNull()?.id
            if (tvId == null) {
                Log.w(TAG, "TMDB: No TV show found for IMDB ID $imdbId")
                return emptyMap()
            }

            // Step 2: Get season details with episode metadata
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

    // TMDB API response data classes
    data class TmdbFindResponse(
        val tv_results: List<TmdbTvResult>? = null
    )

    data class TmdbTvResult(
        val id: Int? = null
    )

    data class TmdbSeasonResponse(
        val episodes: List<TmdbEpisodeData>? = null
    )

    data class TmdbEpisodeData(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val vote_average: Double? = null,
        val air_date: String? = null,
        val runtime: Int? = null
    )

    // ------------------------------------------------------------------
    // Poster resolution
    // ------------------------------------------------------------------

    private fun resolvePoster(doc: org.jsoup.nodes.Document): String? {
        // Priority 1: TMDB poster (highest quality)
        doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }
        // Priority 2: OG image meta tag
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        // Priority 3: Aligncenter image (standard DooPlay pattern)
        doc.selectFirst("article img.aligncenter")?.let {
            fixUrlNull(it.attr("src"))?.let { return it }
        }
        // Priority 4: Any article image
        doc.selectFirst("article img[src]")?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !isNonContentImage(src)) {
                fixUrlNull(src)?.let { return it }
            }
        }
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

    private fun collectDownloadLinks(container: Element): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Links inside headings (standard MoviesCounter pattern)
        container.select("h3, h4, h5").forEach { heading ->
            val headingText = heading.text().trim()

            // Skip episode headers — those are for series, not movies
            // But only skip if they're ACTUAL episode markers (standalone, no quality info)
            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText) &&
                !QUALITY_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach
            // PACK links — skip for single movie downloads
            if (PACK_REGEX.containsMatchIn(headingText)) return@forEach

            heading.select("a[href]").forEach { link ->
                val href = link.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
                if (href.contains(mainUrl, ignoreCase = true)) return@forEach
                if (PACK_URL_REGEX.containsMatchIn(href)) return@forEach

                seen.add(href)
                val qualityLabel = buildQualityLabel(headingText)
                results.add(Pair(qualityLabel, href))
            }
        }

        // Strategy 2: ALL external download links (fallback)
        if (results.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
                if (href.contains(mainUrl, ignoreCase = true)) return@forEach
                if (PACK_URL_REGEX.containsMatchIn(href)) return@forEach

                seen.add(href)
                val qualityLabel = extractQualityLabel(anchor.text().trim())
                results.add(Pair(qualityLabel, href))
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Series episode parsing — v32 COMPLETE REWRITE
    //
    // Handles THREE confirmed site patterns:
    //
    // Pattern A (Simple): <h3><a href="url">EPiSODE N</a></h3>
    //   - All qualities bundled into one link per episode
    //   - e.g., FROM S4, Dutton Ranch
    //
    // Pattern B (Detailed): <h4><strong>EPiSODE N</strong></h4>
    //   then <h4>720p – <a>Drive</a> <a>Instant</a></h4>
    //   then <h4>1080p – <a>Drive</a> <a>Instant</a></h4>
    //   - Each quality has "Drive" (hubdrive) and "Instant" (hubcdn) mirrors
    //   - e.g., Lawmen: Bass Reeves S1, Spider-Noir
    //
    // Pattern C (Pack-only): Just quality headings with no EPiSODE breakdown
    //   - Each quality tier becomes a "virtual episode"
    //   - e.g., series with only PACK downloads
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int,
        showPosterUrl: String?,
        tmdbEpisodes: Map<Int, TmdbEpisodeInfo>
    ): List<Episode> {
        val hasSingleEpisodeSection = container.select("h2, h3").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        // Check for standalone EPiSODE headers (Pattern B — no links in header)
        val hasStandaloneEpisodeHeaders = container.select("h4, h3").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text()) &&
                    el.select("a[href]").isEmpty()
        }

        // Check for EPiSODE inside links (Pattern A — link text is "EPiSODE N")
        val hasLinkedEpisodeHeaders = container.select("h3, h4").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text()) &&
                    el.select("a[href]").isNotEmpty()
        }

        // Check for E-num patterns like "E01 – Drive"
        val hasENumPatterns = container.select("h4, h3, h5").any { el ->
            E_NUM_REGEX.containsMatchIn(el.text()) &&
                    !PACK_REGEX.containsMatchIn(el.text())
        }

        val hasPerEpisodeStructure = hasSingleEpisodeSection ||
                hasStandaloneEpisodeHeaders ||
                hasLinkedEpisodeHeaders ||
                hasENumPatterns

        Log.d(TAG, "buildSeriesEpisodes: singleEpSection=$hasSingleEpisodeSection " +
                "standaloneHeaders=$hasStandaloneEpisodeHeaders " +
                "linkedHeaders=$hasLinkedEpisodeHeaders " +
                "eNumPatterns=$hasENumPatterns")

        return if (hasPerEpisodeStructure) {
            buildPerEpisodeLinks(container, defaultSeason, showPosterUrl, tmdbEpisodes)
        } else {
            buildQualityEntries(container, defaultSeason, showPosterUrl, tmdbEpisodes)
        }
    }

    /**
     * Parse series page with per-episode sections.
     * Handles BOTH Pattern A and Pattern B.
     *
     * Uses pipe format "url|qualityLabel" to preserve quality info
     * through the newEpisode(List<String>) -> loadLinks chain.
     */
    private fun buildPerEpisodeLinks(
        container: Element,
        defaultSeason: Int,
        showPosterUrl: String?,
        tmdbEpisodes: Map<Int, TmdbEpisodeInfo>
    ): List<Episode> {
        // episodeMap: (season, episode) -> list of "url|qualityLabel" entries
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null

        var pastDownloadSection = false
        var pastSingleEpisodeSection = false
        var foundAnyEpisode = false

        // v33 FIX: Bucket for full-season quality links that appear BEFORE any
        // EPiSODE header (e.g. "480p x264 [2.4GB]" via mclinks). These are
        // added to Episode 1 after the main loop completes.
        val fullSeasonLinks = mutableListOf<String>()

        val downloadElements = container.select("h2, h3, h4, h5, hr")

        for (element in downloadElements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            // ---- Section markers ----
            if (tag == "h2" && text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                pastDownloadSection = true
                continue
            }
            if (tag == "h2" && SINGLE_EP_SECTION_REGEX.containsMatchIn(text)) {
                pastSingleEpisodeSection = true
                continue
            }
            if (tag == "h2" && text.contains("Screen-Shots", ignoreCase = true)) continue
            if (tag == "h2" && PACK_REGEX.containsMatchIn(text)) continue

            // ---- PACK link filtering ----
            if (PACK_REGEX.containsMatchIn(text)) continue

            // Skip "New Episode Every Monday" etc.
            if (text.contains("New Episode", ignoreCase = true) &&
                element.select("a[href]").isEmpty()) continue
            if (text.contains("Releasing SooN", ignoreCase = true)) continue

            // ---- Detect episode number ----
            val epHeaderMatch = EPISODE_HEADER_REGEX.find(text)

            if (epHeaderMatch != null) {
                val epNum = (epHeaderMatch.groupValues[1].ifBlank { epHeaderMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    foundAnyEpisode = true
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }

                    // *** KEY FIX: Pattern A — EPiSODE N is inside a link ***
                    // If this heading contains links, the EPiSODE link IS the download link.
                    // e.g., <h3><a href="https://mclinks.xyz/archives/65194">EPiSODE 1</a></h3>
                    val linksInHeader = element.select("a[href]")
                    if (linksInHeader.isNotEmpty()) {
                        linksInHeader.forEach { anchor ->
                            val href = anchor.attr("href").trim()
                            if (isValidDownloadLink(href) && !PACK_URL_REGEX.containsMatchIn(href)) {
                                val key = currentSeason to currentEpisode
                                val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                                // For simple episode links, no quality label available at this level
                                val entry = href
                                if (bucket.none { it.substringBefore("|") == href }) {
                                    bucket.add(entry)
                                }
                            }
                        }
                        // Don't continue — episode detected AND links collected
                        continue
                    }

                    // Pattern B: standalone header, no links — continue to collect quality lines
                    continue
                }
            }

            // E-num pattern (e.g., "E01 – Drive | Instant")
            val eNumMatch = E_NUM_REGEX.find(text)
            if (eNumMatch != null && currentEpisode == null) {
                val epNum = eNumMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    foundAnyEpisode = true
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    // Fall through — this element may also contain links (S4 format)
                }
            }

            // ---- Collect quality links for the current episode ----
            // Only collect if we've found an episode AND we're in the download section
            if (currentEpisode != null && (pastSingleEpisodeSection || foundAnyEpisode || pastDownloadSection)) {
                val links = element.select("a[href]")
                if (links.isNotEmpty()) {
                    // Detect quality prefix from text (e.g., "720p – Drive Instant" -> "720p")
                    val qualityPrefix = QUALITY_LINE_REGEX.find(text)?.groupValues?.get(1)
                        ?: QUALITY_REGEX.find(text)?.groupValues?.get(1)

                    links.forEach { anchor ->
                        val href = anchor.attr("href").trim()
                        if (!isValidDownloadLink(href)) return@forEach
                        if (PACK_URL_REGEX.containsMatchIn(href)) return@forEach

                        // Build quality label: "720p Drive", "1080p Instant", etc.
                        val mirrorLabel = anchor.text().trim()  // "Drive" or "Instant"
                        val qualityLabel = when {
                            qualityPrefix != null && mirrorLabel.isNotEmpty() ->
                                "$qualityPrefix $mirrorLabel"
                            qualityPrefix != null -> qualityPrefix
                            mirrorLabel.isNotEmpty() -> mirrorLabel
                            else -> null
                        }

                        // Use pipe format to preserve quality info: "url|qualityLabel"
                        val entry = if (qualityLabel != null) "$href|$qualityLabel" else href
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        val urlOnly = entry.substringBefore("|")
                        if (bucket.none { it.substringBefore("|") == urlOnly }) {
                            bucket.add(entry)
                        }
                    }
                }
            } else if (currentEpisode == null && pastDownloadSection && !pastSingleEpisodeSection) {
                // v33 FIX: Collect full-season quality links that appear between
                // "DOWNLOAD LINKS" and the first EPiSODE / "Single Episode" header.
                // These are links like "480p x264 [2.4GB]" via mclinks or
                // "720p HEVC [1.9GB]" via hubdrive — full-season downloads.
                val links = element.select("a[href]")
                if (links.isNotEmpty()) {
                    val qualityPrefix = QUALITY_LINE_REGEX.find(text)?.groupValues?.get(1)
                        ?: QUALITY_REGEX.find(text)?.groupValues?.get(1)

                    links.forEach { anchor ->
                        val href = anchor.attr("href").trim()
                        if (!isValidDownloadLink(href)) return@forEach
                        if (PACK_URL_REGEX.containsMatchIn(href)) return@forEach

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

        // v33 FIX: Prepend full-season links to Episode 1 (if it exists)
        // or create Episode 1 from them. Full-season download links are useful
        // as they provide additional mirrors for the first episode or the whole season.
        if (fullSeasonLinks.isNotEmpty()) {
            val ep1Key = defaultSeason to 1
            val ep1Bucket = episodeMap.getOrPut(ep1Key) { mutableListOf() }
            // Prepend so full-season links appear first (higher quality usually)
            val combined = fullSeasonLinks.filter { fs ->
                ep1Bucket.none { it.substringBefore("|") == fs.substringBefore("|") }
            }
            ep1Bucket.addAll(0, combined)
            Log.d(TAG, "buildPerEpisodeLinks: prepended ${combined.size} full-season links to E1")
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

        Log.d(TAG, "buildPerEpisodeLinks: ${episodes.size} episodes with " +
                "${episodeMap.values.sumOf { it.size }} total links")

        return episodes
    }

    /**
     * Pack-only fallback — series with NO per-episode breakdown.
     * Each quality tier gets its own entry with its URL list.
     * Used when there's no EPiSODE headers at all.
     */
    private fun buildQualityEntries(
        container: Element,
        defaultSeason: Int,
        showPosterUrl: String?,
        tmdbEpisodes: Map<Int, TmdbEpisodeInfo>
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val qualityMap = linkedMapOf<String, MutableList<String>>()

        container.select("h3, h4, h5").forEach { heading ->
            val text = heading.text().trim()

            if (SAMPLE_REGEX.containsMatchIn(text)) return@forEach

            heading.select("a[href]").forEach { link ->
                val href = link.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (!isValidDownloadLink(href)) return@forEach
                if (PACK_URL_REGEX.containsMatchIn(href)) return@forEach

                val qualityLabel = buildQualityLabel(text)
                    .replace(Regex("""(?i)\s*PACK\s*"""), " ")
                    .replace(Regex("""\s+"""), " ").trim()

                qualityMap.getOrPut(qualityLabel) { mutableListOf() }
                if (href !in qualityMap[qualityLabel]!!) {
                    qualityMap[qualityLabel]!!.add(href)
                }
            }
        }

        // Fallback
        if (qualityMap.isEmpty()) {
            val allLinks = collectDownloadLinks(container)
            allLinks.forEach { (ql, link) ->
                qualityMap.getOrPut(ql) { mutableListOf() }.add(link)
            }
        }

        qualityMap.entries.forEachIndexed { idx, (ql, urls) ->
            val tmdbInfo = tmdbEpisodes[idx + 1]
            episodes.add(newEpisode(urls.distinct()) {
                name = ql.ifBlank { tmdbInfo?.name ?: "Episode ${idx + 1}" }
                season = defaultSeason
                episode = idx + 1
                posterUrl = tmdbInfo?.posterUrl ?: showPosterUrl
                description = tmdbInfo?.overview
            })
        }

        return episodes
    }

    // ------------------------------------------------------------------
    // Quality label builders
    // ------------------------------------------------------------------

    private fun buildQualityLabel(text: String): String {
        val base = extractQualityLabel(text)
        val extras = mutableListOf<String>()
        val baseUpper = base.uppercase()

        SOURCE_PLATFORM_REGEX.find(text)?.groupValues?.get(1)?.let { platform ->
            val p = platform.uppercase()
            if (p !in baseUpper) extras.add(p)
        }
        CODEC_REGEX.findAll(text).forEach { match ->
            val codec = match.groupValues[1].uppercase()
            if (codec !in baseUpper && codec !in extras) extras.add(codec)
        }
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
            text.contains("1080p", ignoreCase = true) && text.contains("MULTi", ignoreCase = true) -> "1080p MULTi WEB-DL"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) && text.contains("HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720p", ignoreCase = true) && text.contains("x264", ignoreCase = true) -> "720p x264"
            text.contains("720p", ignoreCase = true) && text.contains("10Bit", ignoreCase = true) -> "720p 10Bit"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            text.contains("300MB", ignoreCase = true) -> "300MB"
            text.contains("DS4K", ignoreCase = true) -> "DS4K"
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
    // loadLinks — v32 with pipe format support
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Parse JSON list of URLs — handles pipe format "url|qualityLabel"
        val linksList = tryParseJson<List<String>>(data)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (linksList.isEmpty()) {
            // Legacy fallback: try newline-separated URLs (no pipe split for legacy)
            val legacyLinks = data.lines()
                .map { it.trim() }
                .filter { it.startsWith("http") }

            if (legacyLinks.isEmpty()) return false

            legacyLinks.amap { link -> resolveAndLoad(link, null, subtitleCallback, callback) }
            return true
        }

        Log.d(TAG, "loadLinks(): resolving ${linksList.size} link(s)")
        linksList.amap { entry ->
            // Parse pipe format: "url|qualityLabel" or plain "url"
            val pipeIdx = entry.indexOf("|")
            if (pipeIdx > 0 && entry.startsWith("http")) {
                val url = entry.substring(0, pipeIdx)
                val qualityLabel = entry.substring(pipeIdx + 1)
                resolveAndLoad(url, qualityLabel, subtitleCallback, callback)
            } else {
                resolveAndLoad(entry, null, subtitleCallback, callback)
            }
        }

        return linksList.isNotEmpty()
    }

    /**
     * Resolve a single URL and route to the correct extractor.
     * Supports optional qualityLabel from pipe format for display purposes.
     */
    private suspend fun resolveAndLoad(
        url: String,
        qualityLabel: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                url.contains("hubdrive.space", ignoreCase = true) ||
                url.contains("hubdrive.link", ignoreCase = true) ||
                url.contains("hubdrive.me", ignoreCase = true) -> {
                    Hubdrive().getUrl(url, name, subtitleCallback, callback)
                }

                url.contains("hubcloud.foo", ignoreCase = true) ||
                url.contains("hubcloud.cx", ignoreCase = true) ||
                url.contains("hubcloud.fans", ignoreCase = true) ||
                url.contains("hubcloud.lol", ignoreCase = true) -> {
                    HubCloud().getUrl(url, name, subtitleCallback, callback)
                }

                url.contains("hubcdn.org", ignoreCase = true) ||
                url.contains("hubcdn.sbs", ignoreCase = true) ||
                url.contains("hubcdn.fans", ignoreCase = true) -> {
                    HUBCDN().getUrl(url, name, subtitleCallback, callback)
                }

                url.contains("mclinks.xyz", ignoreCase = true) -> {
                    Mclinks().getUrl(url, name, subtitleCallback, callback)
                }

                url.contains("hblinks", ignoreCase = true) -> {
                    Hblinks().getUrl(url, name, subtitleCallback, callback)
                }

                url.contains("hdstream4u", ignoreCase = true) -> {
                    HdStream4u().getUrl(url, name, subtitleCallback, callback)
                }

                // v33 FIX: hubstream.art streaming links — hash-based SPA URLs
                // hubstream.art, obsession.buzz, noirspy.buzz, etc. -> generic extractor
                // phisher98 pattern: pass empty string as referer, not mainUrl
                else -> {
                    if (!loadExtractor(url, "", subtitleCallback, callback)) {
                        // Fallback: try as direct link
                        addDirectLink(url, callback, qualityLabel ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveAndLoad failed for $url: ${e.message}")
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

    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityLabel: String = ""
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

        val displayName = if (qualityLabel.isNotBlank()) qualityLabel else "Direct"

        callback(
            newExtractorLink(
                name,
                displayName,
                url
            ) {
                this.quality = quality
                this.referer = mainUrl
            }
        )
    }
}
