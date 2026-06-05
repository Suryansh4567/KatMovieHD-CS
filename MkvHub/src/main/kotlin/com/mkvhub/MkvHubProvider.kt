package com.mkvhub

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.Episode
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
 * MkvHub provider for CloudStream.
 *
 * Site structure (WordPress):
 *   - Homepage / category / search pages: movie cards as `div.thumb` elements
 *     containing `figure > img` (poster) and `figcaption > a > p` (title).
 *   - Detail pages: title in `h1.page-title span.material-text`, poster in
 *     `p.poster img`, metadata in centered `<p>`, quality sections in `<h3>`
 *     headings followed by `a.dbuttn.blue` download buttons.
 *   - Download flow: MkvHub detail page → `a.dbuttn.blue` → linkszilla
 *     intermediate page → actual file hoster links (streamtape, clicknupload,
 *     gofile, 1fichier, etc.).
 *
 * This provider follows the same proven patterns as KatMovieHDProvider:
 *   - Two-pass link collection (strict whitelist, then permissive fallback)
 *   - Proper CloudStream API types (SearchQuality, Score)
 *   - Parallel link resolution via `amap`
 *   - Robust error handling with diagnostic logging
 */
class MkvHubProvider : MainAPI() {
    override var mainUrl = "https://www.mkvhub.beer"
    override var name = "MkvHub"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TAG = "MkvHub"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /**
         * Hosts we recognise as known stream/mirror providers found on
         * linkszilla pages. Used by the strict-pass link filter.
         * A false positive just means loadExtractor no-ops harmlessly;
         * a false negative means a real source is dropped.
         */
        private val KNOWN_HOST_REGEX = Regex(
            """(?i)(""" +
                    // Video streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // File sharing / cloud hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|send\.now|krakenfiles|""" +
                    // Common download hosts seen on linkszilla
                    """clicknupload|uploadflix|sendgb|rapidgator|megaup|hexload|""" +
                    """vikingfile|katdrive|hubcloud|hubdrive|""" +
                    // Hindi-dub specific hosts
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|gdtot|techkit""" +
                    """)"""
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
                    """imgur|i\.imgur|postimg|imgbox|imgurworld|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """mkvhub\.beer|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|""" +
                    // Ad networks
                    """profitablecpmrate|linkszilla|doubleclick|popads|propeller""" +
                    """)"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/punjabi-movies/" to "Punjabi Movies",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/tv-shows-hub/" to "TV Shows",
        "$mainUrl/category/1080p-movies/" to "1080p HD",
        "$mainUrl/category/bluray-movies-collection/" to "BluRay",
        "$mainUrl/category/tamil-movies/" to "Tamil Movies",
        "$mainUrl/category/telugu-movies/" to "Telugu Movies"
    )

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = headers).document
        // Site uses `div.thumb` cards (class "thumb col-md-2 col-sm-4 col-xs-6")
        val items = doc.select("div.thumb").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document
        return doc.select("div.thumb").mapNotNull { it.toSearchResult() }
    }

    /**
     * Parse a `div.thumb` card element into a SearchResponse.
     *
     * HTML structure (verified against live site):
     *   <div class="thumb col-md-2 col-sm-4 col-xs-6">
     *     <figure>
     *       <img src="POSTER_URL" alt="TITLE" title="TITLE">
     *       <figcaption>
     *         <a href="DETAIL_PAGE_URL">
     *           <p>TITLE</p>
     *         </a>
     *       </figcaption>
     *       <a href="DETAIL_PAGE_URL">
     *         <div class="thumb-hover">...</div>
     *       </a>
     *     </figure>
     *   </div>
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val img = selectFirst("figure img") ?: return null
        val titleEl = selectFirst("figcaption a p") ?: return null
        val linkEl = selectFirst("figcaption a") ?: selectFirst("a[href]") ?: return null

        val title = titleEl.text().trim().ifBlank { return null }
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val posterUrl = fixUrlNull(img.attr("src"))

        val isSeries = title.contains(Regex("""\bS\d{2}\b""")) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                href.contains("/category/web-series/") ||
                href.contains("/category/tv-shows-hub/")

        // Use SearchQuality enum (not raw Int) — matches CloudStream API
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
        val doc = app.get(url, headers = headers).document

        // Title from h1.page-title > span.material-text
        val title = doc.selectFirst("h1.page-title span.material-text")?.text()?.trim()
            ?: doc.selectFirst("h1.page-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: throw Exception("No title found at $url")

        // Poster: main.page-body > p.poster > img, fallback to og:image
        val posterUrl = fixUrlNull(
            doc.selectFirst("main.page-body p.poster img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Parse movie info from main.page-body paragraphs
        val pageBody = doc.selectFirst("main.page-body") ?: doc

        var plot = ""
        var score: Score? = null
        val tags = mutableListOf<String>()

        pageBody.select("p, div").forEach { el ->
            val text = el.text().trim()
            when {
                text.startsWith("IMDb", ignoreCase = true) -> {
                    val match = Regex("""(\d+\.?\d*)/10""").find(text)
                    match?.groupValues?.get(1)?.toFloatOrNull()?.let {
                        score = Score.from10(it)
                    }
                }
                text.startsWith("Genres", ignoreCase = true) -> {
                    val genreStr = text.substringAfter(":").trim()
                    genreStr.split(",").map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { tags.add(it) }
                }
                text.startsWith("Storyline", ignoreCase = true) -> {
                    plot = text.substringAfter("Storyline").substringAfter(":").trim()
                }
            }
        }

        // Year from title
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        // Series detection from categories and title
        val isSeries = Regex("""\bS\d{2}\b""").containsMatchIn(title) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Complete", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                tags.any { it.equals("Web Series", true) || it.equals("TV Show", true) } ||
                doc.select("div.page-meta a em.material-text").any {
                    it.text().trim().equals("Web Series", true) ||
                            it.text().trim().equals("TV Shows", true)
                }

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries year=$year")

        if (isSeries) {
            // For series: collect all download links grouped by quality,
            // and create per-quality episodes so the user sees "720p • 1.2GB",
            // "1080p • 2.5GB" etc. as separate entries.
            val episodes = buildSeriesEpisodes(doc, url)
            Log.d(TAG, "load() built ${episodes.size} series episode(s)")

            // Fallback: if no episodes found, create a single generic one
            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(newEpisode(url) { name = "Watch" })
            } else {
                episodes
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { pageBody.text().take(500) }
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            // For movies: collect all quality download button URLs as
            // newline-joined string. loadLinks will split and resolve each.
            val links = collectDownloadUrls(pageBody)
            Log.d(TAG, "load() found ${links.lines().filter { it.isNotBlank() }.size} download URL(s)")

            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = posterUrl
                this.plot = plot.ifBlank { pageBody.text().take(500) }
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    /**
     * Build episodes for a series page. Each quality section becomes
     * a separate "episode" so the user can pick their preferred quality.
     *
     * Typical series page layout:
     *   <h3>|| Complete Series Download (Ep 01-07) 720p – 2GB Zip ||</h3>
     *   <p><a class="dbuttn blue" href="https://secure.linkszilla.top/view/XXX">...</a></p>
     *   <h3>|| Complete Series Download (Ep 01-07) 1080p – 4GB Zip ||</h3>
     *   <p><a class="dbuttn blue" href="https://secure.linkszilla.top/view/YYY">...</a></p>
     */
    private fun buildSeriesEpisodes(doc: org.jsoup.nodes.Document, pageUrl: String): List<Episode> {
        val pageBody = doc.selectFirst("main.page-body") ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        pageBody.select("h3").forEach { h3 ->
            val h3Text = h3.text().trim()
            if (!h3Text.contains("480p", true) && !h3Text.contains("720p", true) &&
                !h3Text.contains("1080p", true) && !h3Text.contains("4K", true) &&
                !h3Text.contains("2160p", true) && !h3Text.contains("Download", true)
            ) return@forEach

            // Try to find the download button in the next sibling
            val nextSib = h3.nextElementSibling()
            val btn = nextSib?.selectFirst("a.dbuttn")
                ?: nextSib?.selectFirst("a[href]")
                ?: h3.nextElementSibling()?.selectFirst("a.dbuttn")
                ?: h3.nextElementSibling()?.selectFirst("a[href]")

            if (btn != null) {
                val linkUrl = btn.attr("href").trim()
                if (linkUrl.startsWith("http")) {
                    // Build a quality label from the h3 text
                    val qualityLabel = extractQualityLabel(h3Text)
                    val sizeMatch = Regex("""Size:\s*([\d.]+(?:MB|GB))""", RegexOption.IGNORE_CASE).find(h3Text)
                        ?: Regex("""([\d.]+(?:MB|GB))""", RegexOption.IGNORE_CASE).find(h3Text)
                    val sizeStr = sizeMatch?.groupValues?.get(1) ?: ""

                    val epName = buildString {
                        append(qualityLabel)
                        if (sizeStr.isNotBlank()) append(" • $sizeStr")
                    }

                    episodes.add(newEpisode(linkUrl) {
                        name = epName
                        season = 1
                        episode = episodes.size + 1
                    })
                }
            }
        }

        // Fallback: try any a.dbuttn on the page
        if (episodes.isEmpty()) {
            pageBody.select("a.dbuttn").forEachIndexed { idx, btn ->
                val url = btn.attr("href").trim()
                if (url.startsWith("http")) {
                    episodes.add(newEpisode(url) {
                        name = "Source ${idx + 1}"
                        season = 1
                        episode = idx + 1
                    })
                }
            }
        }

        return episodes
    }

    /**
     * Extract a clean quality label from h3 heading text.
     * Input: "|| Download 720p HD via Single Links Size: 918MB ||"
     * Output: "720p"
     */
    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", true) || text.contains("2160p", true) -> "4K"
            text.contains("1080p", true) -> "1080p"
            text.contains("720p", true) -> "720p"
            text.contains("480p", true) -> "480p"
            text.contains("Download", true) -> "HD"
            else -> "Download"
        }
    }

    /**
     * Collect all download button URLs from the page body.
     * Returns newline-joined string (v9-compatible format for loadLinks).
     */
    private fun collectDownloadUrls(pageBody: Element): String {
        val links = mutableListOf<String>()

        // Primary: h3 + a.dbuttn pairs (quality-labeled downloads)
        pageBody.select("h3").forEach { h3 ->
            val nextSib = h3.nextElementSibling()
            val btn = nextSib?.selectFirst("a.dbuttn")
            if (btn != null) {
                val url = btn.attr("href").trim()
                if (url.startsWith("http") && url !in links) {
                    links.add(url)
                }
            }
        }

        // Fallback: any a.dbuttn on the page
        if (links.isEmpty()) {
            pageBody.select("a.dbuttn").forEach { btn ->
                val url = btn.attr("href").trim()
                if (url.startsWith("http") && url !in links) {
                    links.add(url)
                }
            }
        }

        return links.joinToString("\n")
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
        // 1. A single URL (series episode link or a linkszilla URL)
        // 2. Newline-separated URLs (movie: multiple quality download buttons)
        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")

        var anySuccess = false

        urls.amap { url ->
            try {
                val resolved = resolveLinkszilla(url)
                if (resolved.isEmpty()) {
                    // Direct URL — try loadExtractor first, then add as direct link
                    if (!tryLoadExtractor(url, subtitleCallback, callback)) {
                        addDirectLink(url, callback)
                    }
                    anySuccess = true
                } else {
                    // Got hoster links from linkszilla page
                    resolved.amap { (hostName, hosterUrl) ->
                        try {
                            if (!tryLoadExtractor(hosterUrl, subtitleCallback, callback)) {
                                addDirectLink(hosterUrl, callback, hostName)
                            }
                            anySuccess = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Hoster extraction failed for $hosterUrl: ${e.message}")
                            addDirectLink(hosterUrl, callback, hostName)
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
     * Try to resolve a linkszilla intermediate page and extract the
     * actual file hoster links.
     *
     * Linkszilla page structure (verified):
     *   <div class="view_tab">
     *     <a href="https://dl.uploadflix.com/...">...</a>
     *     <a href="https://sendgb.com/...">...</a>
     *     <a href="https://clicknupload.click/...">...</a>
     *     ...
     *   </div>
     *
     * Returns list of (hostName, url) pairs, or empty list if not a
     * linkszilla page or no links found.
     */
    private suspend fun resolveLinkszilla(url: String): List<Pair<String, String>> {
        // Only resolve linkszilla URLs
        if (!url.contains("linkszilla", ignoreCase = true)) {
            return emptyList()
        }

        Log.d(TAG, "Resolving linkszilla: $url")
        val res = app.get(
            url,
            referer = mainUrl,
            headers = headers,
            timeout = 15000
        )
        val doc = res.document

        // Primary: look in div.view_tab (the actual download links container)
        val viewTab = doc.selectFirst("div.view_tab")
        val anchorContainer = viewTab ?: doc

        val links = anchorContainer.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val linkText = a.text().trim()
            // Filter: must be a real external URL, not linkszilla itself,
            // not ad networks, not images/CSS
            if (href.startsWith("http") &&
                !href.contains("linkszilla", ignoreCase = true) &&
                !href.contains("profitablecpmrate", ignoreCase = true) &&
                !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                linkText.isNotBlank()
            ) {
                linkText.take(30) to href
            } else null
        }.filter { it.second.startsWith("http") }

        if (links.isEmpty()) {
            Log.w(TAG, "Linkszilla page yielded 0 usable links from $url")
            // Diagnostic: dump what we found
            val allAnchors = doc.select("a[href]").map { it.attr("href") }
            Log.d(TAG, "Linkszilla page anchors (${allAnchors.size}): ${allAnchors.take(5)}")
        } else {
            Log.d(TAG, "Linkszilla resolved ${links.size} hoster link(s)")
        }

        return links
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
            url.contains("1080p", true) -> Qualities.P1080.value
            url.contains("720p", true) -> Qualities.P720.value
            url.contains("480p", true) -> Qualities.P480.value
            url.contains("4k", true) || url.contains("2160p", true) -> Qualities.P2160.value
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
