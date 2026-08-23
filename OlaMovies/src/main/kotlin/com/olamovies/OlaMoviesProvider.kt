package com.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class OlaMoviesProvider : MainAPI() {
    // v4.olamovies.mov is the current domain per the official OlaMovies
    // Telegram channel (@olamovies_officialv6) — v3.olamovies.mov is parked.
    // Verified live with fresh uploads on 2026-08-23.
    override var mainUrl = "https://v4.olamovies.mov"
    override var name = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/movies/hollywood/" to "Hollywood Movies",
        "$mainUrl/category/movies/anime-movies/" to "Anime Movies",
        "$mainUrl/category/tv-series/hindi-tv-series/" to "Hindi TV Series",
        "$mainUrl/category/tv-series/english-tv-series/" to "English TV Series",
        "$mainUrl/category/tv-series/korean-tv-series/" to "Korean TV Series",
    )

    data class SourceData(
        val url: String,
        val name: String,
        val size: String? = null
    )

    data class LinkPayload(
        val sources: List<SourceData>
    )

    companion object {
        /**
         * First-hop link generator hosts. Post buttons point here; these pages
         * ("OlaMovies Link Generator") then chain to the final file hosts.
         */
        private val LINK_HOSTS = listOf(
            "links.ol-am.top",
            "links.olamovies.mov",
            "links.olamovies.top",
            "links.olamovies"
        )

        /** Hosts that terminate the redirect chain (actual file hosts). */
        private val FINAL_HOST_REGEX = Regex(
            """(?i)(drive\.google\.com|docs\.google\.com|mega\.|nz/|pixeldrain\.|""" +
            """gofile\.io|hubcloud|gdflix|gdlink|katdrive|dood|streamtape|mixdrop|""" +
            """filemoon|streamwish|voe\.|dl\.|cdn\.|\.workers\.dev|\.r2\.dev|""" +
            """drive\.olamovies\.download)"""
        )

        /** WebView interceptor: stop the chain at file hosts or direct video files. */
        private val INTERCEPT_REGEX = Regex(
            FINAL_HOST_REGEX.pattern + """|\.(mkv|mp4|m3u8|webm|zip)(\?|#|${'$'})"""
        )

        private val VIDEO_EXT_REGEX = Regex("""(?i)\.(mkv|mp4|m3u8|webm)(\?|#|${'$'})""")

        private val DRIVE_PAGE_REGEX = Regex("""(?i)^https?://drive\.olamovies\.download/file/""")

        /**
         * Reverse-engineered 2026-08-23 (sources: greasyfork script 566947
         * "Olam Drive Bypasser UI", priyanshu3301/olamovies inject.js):
         *
         * Every drive.olamovies.download/file/* page embeds a Mongo-style id in
         * its inline state:  "_id":"<24-hex>"  plus the release name in <h1>.
         * The companion stream worker mints a direct, playable file URL:
         *   https://olam.bypassbot.workers.dev/<_id>?filename=<base64url(name)>
         */
        // Matches both plain  "_id":"<mongo hex>"  and the Next.js escaped
        // form  \"_id\":\"<hex>\"  found inside RSC flight chunks.
        private val DRIVE_ID_REGEX = Regex("""\\?"_id\\?"\s*:\s*\\?"([0-9a-fA-F]{20,30})""")
        private const val DRIVE_WORKER_URL = "https://olam.bypassbot.workers.dev/"

        /** Bold group headers above episode links, e.g. "My.Show.S01.1080p...HEVC-Grp". */
        private val GROUP_HEADER_REGEX = Regex(
            """(?i)(720p|1080p|2160p|480p|4k|ds4k|web-?dl|webrip|bluray|bdrip|""" +
            """hevc|x26[45]|h\.?26[45]|atmos|ddp|\.mkv|\.zip|remux|hdr10|\bdv\b)"""
        )

        private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s"'<>\\\]]+""")

        private fun isLinkHost(url: String): Boolean =
            LINK_HOSTS.any { url.contains(it, ignoreCase = true) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name ?: this.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleNode = selectFirst("h2.entry-title a, h2 a, h3 a, .entry-title a") ?: return null
        val title = titleNode.text().trim()
        val href = titleNode.attr("abs:href").takeIf { it.isNotBlank() } ?: return null
        val posterUrl = selectFirst("img")?.let {
            it.attr("abs:src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("abs:data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("abs:data-lazy-src")
        }

        val isSeries = title.contains(Regex("(?i)\\bS\\d+|Season|Complete|\\bE\\d+"))
                || href.contains("tv-series", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("article").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Walk the post body in document order. The v4 theme marks each quality
     * block with a bold release-name header (e.g. "Con.City.2026.720p…mkv")
     * followed by short anchors ("episode 01", "1080p zip [10.39GB]").
     * We remember the last plausible release header and graft it onto the
     * anchor text so every collected link carries quality + episode context.
     */
    private fun collectDownloadSources(document: org.jsoup.nodes.Document): List<SourceData> {
        val container = document.selectFirst(
            ".entry-content, .post-content, article, #content, main"
        ) ?: document.body()

        val sources = LinkedHashMap<String, SourceData>()
        var currentGroup = ""

        container.getAllElements().forEach { el ->
            when {
                el.tagName() in listOf("strong", "b", "h1", "h2", "h3", "h4", "h5", "p", "li", "span") -> {
                    // ownText avoids inheriting anchor text from nested links
                    val t = el.ownText().trim()
                        .removePrefix("**").removeSuffix("**").trim()
                    if (t.length >= 15 && GROUP_HEADER_REGEX.containsMatchIn(t)
                        && !t.contains("Telegram", true)
                    ) {
                        currentGroup = t
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href").ifBlank { el.attr("href") }
                    if (!isLinkHost(href)) return@forEach
                    val label = el.text().trim()
                    if (label.contains(Regex("(?i)telegram|join|group|admin|contact|premium|paybase"))) {
                        return@forEach
                    }
                    // Short labels like "episode 01" need the release header for
                    // quality parsing; descriptive labels stand on their own.
                    val needsContext = label.length < 15
                        || label.contains(Regex("""(?i)^(?:ep(?:isode)?\s*\d+|part\s*\d+|zip|${'$'})"""))
                    val fullName = when {
                        needsContext && currentGroup.isNotBlank() -> "$currentGroup | $label"
                        label.isNotBlank() -> label
                        else -> currentGroup
                    }
                    if (fullName.isNotBlank()) {
                        sources.putIfAbsent(href, SourceData(href, fullName))
                    }
                }
            }
        }
        return sources.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        val poster = document.selectFirst(".wp-post-image, .thumb img")?.let {
            it.attr("abs:src").takeIf { s -> s.isNotBlank() } ?: it.attr("abs:data-src")
        }
        val plot = document.selectFirst(".synopsis p, .entry-content p")?.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(document.select(".year a, .date a, h1").text())?.value?.toIntOrNull()

        val actors = document.select(".cast a").map { it.text() }.filter { it.isNotBlank() }.distinct()
        val trailer = document.selectFirst("iframe[src*=youtube]")?.attr("abs:src")

        val buttons = collectDownloadSources(document)

        val isTvSeries = url.contains("tv-series", true)
                || title.contains("Season", true)
                || buttons.any {
                    it.name.contains(Regex("""(?i)episode\s*\d+|\bS\d{1,2}\b"""))
                }
                || document.selectFirst(".episodios, .episodes-container") != null

        if (isTvSeries) {
            val epMap = mutableMapOf<Pair<Int, Int>, MutableList<SourceData>>()
            buttons.forEach { source ->
                val epMatch = Regex(
                    "(?i)\\bS(\\d+)\\s*E(\\d+)\\b|episode\\s*(\\d+)|\\bE(\\d+)",
                    RegexOption.IGNORE_CASE
                ).find(source.name)
                val epNum = epMatch?.groupValues?.let { g -> g[2].toIntOrNull() ?: g[3].toIntOrNull() ?: g[4].toIntOrNull() }
                if (epNum == null) return@forEach // zip-pack links: no episode ⇒ skip
                val seasonNum = epMatch.groupValues.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?i)\bS(\d{1,2})\b""").find(source.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: title.let { t -> Regex("(?i)Season\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 1
                val key = Pair(seasonNum, epNum)
                epMap.getOrPut(key) { mutableListOf() }.add(source)
            }

            val episodes = epMap.map { (key, sources) ->
                newEpisode(LinkPayload(sources).toJson()) {
                    this.name = "Episode ${key.second}"
                    this.episode = key.second
                    this.season = key.first
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkPayload(buttons).toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    /**
     * Try to mine the final destination straight out of the generator page
     * HTML (cheap, no WebView). These pages frequently stash the target in
     * data-attributes / JS string assignments even though the visible text
     * only says "Please wait…".
     */
    private fun sniffFinalUrl(html: String): String? =
        URL_IN_TEXT_REGEX.findAll(html)
            .map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull {
                (FINAL_HOST_REGEX.containsMatchIn(it) || VIDEO_EXT_REGEX.containsMatchIn(it)) &&
                    !it.contains("olamovies", true) && !isLinkHost(it)
            }

    /**
     * Given a drive.olamovies.download/file/* page URL, extract the embedded
     * `_id` and re-build the direct worker stream URL. Returns null when the
     * id is missing (page layout changed / not logged in).
     */
    private suspend fun mintDriveDirectUrl(drivePageUrl: String, referer: String, fallbackName: String): String? {
        val doc = runCatching { app.get(drivePageUrl, referer = referer, timeout = 20) }.getOrNull()
            ?: return null
        val html = doc.text
        val id = DRIVE_ID_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val fileName = runCatching { doc.document.selectFirst("h1")?.text()?.trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackName
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fileName.toByteArray(Charsets.UTF_8))
        return "$DRIVE_WORKER_URL$id?filename=$b64"
    }

    private suspend fun emitFinal(
        finalUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (finalUrl.isBlank()) return false

        // OlaMovies' own drive shell page → mint the direct worker stream.
        if (DRIVE_PAGE_REGEX.containsMatchIn(finalUrl)) {
            val direct = mintDriveDirectUrl(finalUrl, mainUrl, sourceName)
            if (direct != null) {
                callback(
                    newExtractorLink(sourceName, this.name, direct) {
                        this.referer = ""
                        this.quality = getQualityFromString(sourceName).value
                    }
                )
                return true
            }
            // Fall through: maybe a registered extractor can still handle it.
        }

        // 1) Known host extractor (GDrive, Mega, Pixeldrain, HubCloud…)
        if (loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)) return true

        val looksPlayable = VIDEO_EXT_REGEX.containsMatchIn(finalUrl) ||
            FINAL_HOST_REGEX.containsMatchIn(finalUrl)
        if (!looksPlayable) return false

        // 2) Direct file / unknown CDN — hand over as a direct link.
        callback(
            newExtractorLink(sourceName, this.name, finalUrl) {
                this.referer = mainUrl
                this.quality = getQualityFromString(sourceName).value
            }
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<LinkPayload>(data) }.getOrNull() ?: return false
        val count = AtomicInteger(0)

        payload.sources.amap { source ->
            runCatching {
                // Fast path: a plain GET follows the 301 to links.olamovies.mov
                // and returns the generator page; sniff it for a stashed URL.
                val head = app.get(source.url, referer = "$mainUrl/", timeout = 20)
                val html = head.text
                val sniffed = sniffFinalUrl(html)
                    ?: sniffFinalUrl(head.url) // sometimes the target is in the URL itself

                if (sniffed != null && sniffed != source.url) {
                    if (emitFinal(sniffed, source.name, subtitleCallback, callback)) {
                        count.incrementAndGet()
                        return@runCatching
                    }
                }

                // Slow path: drive the ad-shortener chain in a WebView and
                // intercept when it touches a real file host.
                val resolver = WebViewResolver(INTERCEPT_REGEX)
                val response = app.get(source.url, interceptor = resolver, referer = mainUrl)
                val finalUrl = response.url
                if (finalUrl != source.url && emitFinal(finalUrl, source.name, subtitleCallback, callback)) {
                    count.incrementAndGet()
                }
            }
        }
        return count.get() > 0
    }
}
