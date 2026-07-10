package com.lagradost

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * KMMovies CloudStream provider.
 *
 * KMMovies is a WordPress site. Movie download buttons point at
 * magiclinks.lol wrapper posts; series use episode-wise landing pages on
 * episodes.magiclinks.lol that contain per-episode skydrop links.
 *
 * Strategy:
 *   • Listing / search: scrape server-rendered article.movie-card grid.
 *   • Movie load(): collect magiclinks URLs, store as newline-joined payload.
 *   • Series load(): expand season blocks, fetch episode-wise pages,
 *     extract per-episode skydrop links.
 *   • loadLinks(): resolve magiclinks via WP REST API, follow online.php
 *     and download99.php redirects, call skydrop api.php, emit direct
 *     R2 / googleusercontent URLs or delegate recognised hosts to
 *     CloudStream's stock extractor registry.
 */
class KMMovies : MainAPI() {
    override var name = "KMMovies"
    override var mainUrl = "https://kmmovies.shop"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "/" to "Recently Added",
        "/category/movies/" to "Movies",
        "/category/tv-series/" to "TV Series",
        "/category/bollywood/" to "Bollywood",
        "/category/hollywood/" to "Hollywood",
        "/category/south/" to "South Indian",
        "/category/4k/" to "4K Movies",
        "/category/dual-audio/" to "Dual Audio",
        "/category/hindi/" to "Hindi",
        "/category/english/" to "English",
        "/category/tamil/" to "Tamil",
        "/category/telugu/" to "Telugu",
        "/category/kannada/" to "Kannada",
        "/category/kdrama/" to "K-Drama",
        "/category/anime/" to "Anime",
        "/trending/" to "Trending",
        "/genre/action/" to "Action",
        "/genre/comedy/" to "Comedy",
        "/genre/drama/" to "Drama",
        "/genre/thriller/" to "Thriller"
    )

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private fun isCloudflarePage(document: Document): Boolean {
        val title = document.selectFirst("title")?.text()?.lowercase().orEmpty()
        val html = document.html().take(30_000).lowercase()
        return title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("verify you are human") ||
            title.contains("cloudflare") ||
            html.contains("cf-chl") ||
            html.contains("challenge-running") ||
            html.contains("ray id")
    }

    private suspend fun safeGetDocument(url: String, referer: String? = null): Document {
        val requestHeaders = if (referer.isNullOrBlank()) headers else headers + ("Referer" to referer)
        try {
            val direct = app.get(url, headers = requestHeaders, timeout = 25).document
            if (!isCloudflarePage(direct)) return direct
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "GET failed for ${safeUrl(url)}: ${error.message}")
        }
        return app.get(url, headers = requestHeaders, interceptor = CloudflareKiller(), timeout = 30).document
    }

    private fun absoluteUrl(document: Document, value: String): String {
        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"
        return if (value.startsWith("http", ignoreCase = true)) {
            value.replace(" ", "%20")
        } else {
            runCatching { URI(document.location()).resolve(value).toString() }.getOrElse { fixUrl(value) }
        }
    }

    private fun fixUrl(value: String): String {
        val v = value.trim()
        if (v.isBlank()) return ""
        if (v.startsWith("//")) return "https:$v"
        if (v.startsWith("http", ignoreCase = true)) return v
        return mainUrl.trimEnd('/') + "/" + v.trimStart('/')
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = mainUrl.trimEnd('/')
        val clean = when {
            path.isBlank() -> "/"
            path.startsWith("/") -> path
            else -> "/$path"
        }.replace(Regex("/{2,}"), "/")
        if (page <= 1) return "$base/${clean.trimStart('/')}"
        return if (clean == "/") "$base/page/$page/" else "$base/${clean.trim('/').trimEnd('/')}/page/$page/"
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = safeGetDocument(url)
        val results = parseListing(document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        return parseListing(safeGetDocument(url)).toNewSearchResponseList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseListing(safeGetDocument("$mainUrl/?s=$encoded"))
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val cards = document.select("article.movie-card")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { it.toSearchResponse(document) }.distinctBy { it.url }
        }
        // Fallback for alternate containers
        return document.select(".movie-title").mapNotNull { titleNode ->
            val card = titleNode.parents().firstOrNull { it.selectFirst("a[href]") != null } ?: return@mapNotNull null
            card.toSearchResponse(document)
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(document: Document): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val url = absoluteUrl(document, anchor.attr("href"))
        if (url.isBlank() || url.contains("/category/", ignoreCase = true)) return null

        val rawTitle = selectFirst(".movie-title")?.text()?.trim()
            ?.ifBlank { attr("aria-label") }
            ?.ifBlank { anchor.attr("aria-label") }
            ?: return null
        if (rawTitle.isBlank()) return null

        val poster = selectFirst("img.poster, img[src]")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { absoluteUrl(document, it) }

        val typeText = selectFirst(".meta-row span")?.text()?.lowercase().orEmpty()
        val type = if (typeText.contains("series") || isSeriesTitle(rawTitle)) TvType.TvSeries else TvType.Movie
        val qualityText = selectFirst(".badge-left")?.text().orEmpty()

        return newMovieSearchResponse(cleanTitle(rawTitle), url, type) {
            this.posterUrl = poster
            this.quality = searchQuality(qualityText.ifBlank { rawTitle })
        }
    }

    private fun searchQuality(value: String): SearchQuality? {
        return runCatching { getQualityFromString(value) }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val document = safeGetDocument(pageUrl)
        val rawTitle = document.selectFirst("h1.hero-title, h1.entry-title, h1")?.text()?.trim()
            ?.ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content") }
            ?: document.title().substringBefore(" - KMMOVIES").trim()
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst("img.hero-poster")?.let { img ->
            absoluteUrl(document, img.attr("src"))
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val backdrop = extractBackdrop(document)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: poster
        val plot = document.selectFirst(".hero-description")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
        val year = extractYear(document, rawTitle)
        val score = extractScore(document)
        val genres = extractGenres(document)
        val cast = document.select(".about-cast-chip").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val trailer = document.selectFirst("a.open-trailer[data-trailer-url]")?.attr("data-trailer-url")?.takeIf { it.isNotBlank() }
        val imdbUrl = document.selectFirst("a[href*='imdb.com/title']")?.attr("href")
        val imdbId = imdbUrl?.let { Regex("""\b(tt\d+)\b""").find(it)?.groupValues?.get(1) }
        val recommendations = parseRecommendations(document)
        val isSeries = isSeriesPage(document, rawTitle)

        suspend fun LoadResponse.applyCommon() {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score
            this.recommendations = recommendations
            if (cast.isNotEmpty()) addActors(cast.map { Actor(it, null) })
            addTrailer(trailer)
            imdbUrl?.let {
                addImdbUrl(it)
                imdbId?.let { id -> addImdbId(id) }
            }
        }

        if (isSeries) {
            val episodes = expandSeriesLinks(document, pageUrl)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    applyCommon()
                }
            }
            // Fallback: single-season placeholder so the user can still open it
            val fallbackLinks = collectDownloadLinks(document)
            val ep = newEpisode(fallbackLinks.joinToString("\n") { it.url }) {
                this.name = "Season 1"
                this.season = 1
                this.episode = 1
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, listOf(ep)) {
                applyCommon()
            }
        }

        val downloads = collectDownloadLinks(document)
        val dataPayload = downloads.joinToString("\n") { it.url }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, dataPayload) {
            applyCommon()
        }
    }

    private fun extractBackdrop(document: Document): String? {
        val style = document.selectFirst("article.movie-hero")?.attr("style").orEmpty()
        return Regex("""url\(['\"]?([^)'\"]+)""").find(style)?.groupValues?.getOrNull(1)?.let { absoluteUrl(document, it) }
    }

    private fun extractYear(document: Document, title: String): Int? {
        val about = document.select(".about-highlight-pill").firstOrNull { pill ->
            pill.selectFirst(".about-highlight-label")?.text()?.trim()?.equals("Release", true) == true
        }?.selectFirst(".about-highlight-value")?.text().orEmpty()
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(about)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractScore(document: Document): Score? {
        val value = document.selectFirst(".rating-star")?.text().orEmpty()
        val score = Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(value)?.groupValues?.get(1)?.toDoubleOrNull()
        return score?.takeIf { it > 0.0 }?.let { Score.from10(it) }
    }

    private fun extractGenres(document: Document): List<String> {
        return document.select(".about-meta-box").firstOrNull { box ->
            box.selectFirst(".about-meta-label")?.text()?.trim()?.equals("Genres", true) == true
        }?.select(".about-meta-value a")?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct()
            ?: emptyList()
    }

    private fun isSeriesPage(document: Document, title: String): Boolean {
        val type = document.select(".about-highlight-pill").firstOrNull { pill ->
            pill.selectFirst(".about-highlight-label")?.text()?.trim()?.equals("Type", true) == true
        }?.selectFirst(".about-highlight-value")?.text().orEmpty()
        return type.equals("Series", true) ||
            document.select(".season-block").isNotEmpty() ||
            isSeriesTitle(title)
    }

    private fun parseRecommendations(document: Document): List<SearchResponse> {
        return document.select(".related-movies article.movie-card").mapNotNull {
            it.toSearchResponse(document)
        }.distinctBy { it.url }.take(12)
    }

    // ------------------------------------------------------------------
    // Link collection
    // ------------------------------------------------------------------

    private data class DlLink(val label: String, val url: String)

    private fun collectDownloadLinks(document: Document): List<DlLink> {
        return document.select(".downloads-section a.dl-btn").mapNotNull { btn ->
            val url = absoluteUrl(document, btn.attr("href"))
            url.takeIf { it.isNotBlank() }?.let {
                DlLink(btn.text().normalise(), it)
            }
        }.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Series expansion
    // ------------------------------------------------------------------

    private suspend fun expandSeriesLinks(document: Document, referer: String): List<Episode> {
        val blocks = document.select(".season-block")
        if (blocks.isEmpty()) return emptyList()

        val work = blocks.flatMap { block ->
            val title = block.selectFirst(".season-block-title")?.text().orEmpty()
            val season = Regex("""(?i)season\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val buttons = block.select(".type-content[data-type^=episodes] a.dl-btn")
                .ifEmpty { block.select(".type-content.active a.dl-btn") }
            buttons.mapNotNull { btn ->
                val href = absoluteUrl(document, btn.attr("href"))
                if (href.isBlank()) null else SeasonWork(season, btn.text().normalise(), href, referer)
            }
        }

        if (work.isEmpty()) return emptyList()

        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<DlLink>>()
        supervisorScope {
            work.map { item ->
                async {
                    runCatching { fetchEpisodePage(item) }
                        .onFailure { Log.w(TAG, "Episode page failed ${safeUrl(item.url)}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten().forEach { (season, episode, dlLink) ->
                episodeMap.getOrPut(season to episode) { mutableListOf() }.add(dlLink)
            }
        }

        return episodeMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (key, links) ->
            val (season, episode) = key
            newEpisode(links.joinToString("\n") { it.url }) {
                this.name = "Episode $episode"
                this.season = season
                this.episode = episode
            }
        }
    }

    private data class SeasonWork(val season: Int, val label: String, val url: String, val referer: String)

    private suspend fun fetchEpisodePage(work: SeasonWork): List<Triple<Int, Int, DlLink>> {
        val doc = safeGetDocument(work.url, work.referer)
        val rows = doc.select(".ep-row")
        if (rows.isNotEmpty()) {
            return rows.mapIndexedNotNull { index, row ->
                val epName = row.selectFirst(".ep-name")?.text().orEmpty()
                val episode = Regex("""(?i)(?:episode|ep)\s*[-#:]?\s*(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                val href = row.selectFirst("a[href]")?.let { absoluteUrl(doc, it.attr("href")) }
                href?.takeIf { it.isNotBlank() }?.let {
                    Triple(work.season, episode, DlLink(work.label, it))
                }
            }
        }
        // If no rows, treat the landing page itself as a single episode source
        return listOf(Triple(work.season, 1, DlLink(work.label, work.url)))
    }

    // ------------------------------------------------------------------
    // loadLinks
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }.distinct()
        Log.d(TAG, "loadLinks: ${urls.size} URL(s)")
        if (urls.isEmpty()) return false

        var any = false
        urls.forEach { url ->
            any = true
            runCatching {
                when {
                    isMagicLinksPost(url) -> resolveMagicLinks(url, subtitleCallback, callback)
                    isSkydropUrl(url) -> resolveSkydrop(url, subtitleCallback, callback)
                    isDirectVideo(url) -> emitDirect(url, "Direct", url, callback)
                    else -> loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Link failed ${safeUrl(url)}: ${error.message}")
            }
        }
        return any
    }

    // ------------------------------------------------------------------
    // MagicLinks resolution (movies)
    // ------------------------------------------------------------------

    private fun isMagicLinksPost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return host.contains("magiclinks") && Regex("""/\d+-\d+/?$""").containsMatchIn(path)
    }

    private suspend fun resolveMagicLinks(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host ?: return
        val slug = uri.path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return

        val hostsToTry = linkedSetOf(host, "magiclinks.lol", "w1.magiclinks.lol")
        var post: JSONObject? = null
        var resolvedBase = url

        for (tryHost in hostsToTry) {
            val apiUrl = "${uri.scheme ?: "https"}://$tryHost/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=content,meta,link"
            val jsonText = runCatching {
                app.get(apiUrl, headers = headers + mapOf("Accept" to "application/json", "Referer" to url), timeout = 20).text
            }.getOrNull() ?: continue
            val candidate = runCatching { JSONArray(jsonText).optJSONObject(0) }.getOrNull()
            if (candidate != null) {
                post = candidate
                resolvedBase = candidate.optString("link").ifBlank { "https://$tryHost/$slug/" }
                break
            }
        }

        if (post == null) {
            Log.w(TAG, "MagicLinks REST API empty for $url")
            return
        }

        val meta = post.optJSONObject("meta")
        val sources = linkedMapOf<String, String>()

        fun addSource(raw: String?, label: String) {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || value.startsWith("javascript:", true) || value == "#") return
            val absolute = if (value.startsWith("http", true)) value else runCatching { URI(resolvedBase).resolve(value).toString() }.getOrDefault("")
            if (absolute.isNotBlank()) sources[absolute] = label
        }

        listOf(
            "watch_online_url" to "Watch Online",
            "watch_online2_url" to "Watch Online 2",
            "zip_zap_url" to "Zip-Zap",
            "skydrop_url" to "SkyDrop",
            "gdtot_url" to "GDTot",
            "gofile_url" to "GoFile",
            "pixeldrain_url" to "Pixeldrain",
            "gdflix_url" to "GDFlix",
            "filepress_url" to "Filepress",
            "hubcloud_url" to "HubCloud",
            "one_click_url" to "One Click",
            "transfer_it_url" to "Transfer.it",
            "ultra_fast_download_url" to "Fast Download"
        ).forEach { (key, label) -> addSource(meta?.optString(key), label) }

        // Also parse rendered content for any extra links (Google Drive, etc.)
        val rendered = post.optJSONObject("content")?.optString("rendered").orEmpty()
        if (rendered.isNotBlank()) {
            val renderedDoc = org.jsoup.Jsoup.parse(rendered, resolvedBase)
            renderedDoc.select("a[href]").forEach { a ->
                addSource(a.absUrl("href").ifBlank { a.attr("href") }, a.text().normalise().ifBlank { "Source" })
            }
        }

        sources.forEach { (srcUrl, label) ->
            runCatching {
                when {
                    srcUrl.contains("online.php", true) || srcUrl.contains("/nf/index.php", true) -> {
                        resolveWatchOnline(srcUrl, label, callback)
                    }
                    srcUrl.contains("download99.php", true) -> {
                        resolveZipZap(srcUrl, label, callback)
                    }
                    isSkydropUrl(srcUrl) -> {
                        resolveSkydrop(srcUrl, subtitleCallback, callback)
                    }
                    isDirectVideo(srcUrl) -> {
                        emitDirect(srcUrl, label, srcUrl, callback)
                    }
                    else -> {
                        loadExtractor(srcUrl, resolvedBase, subtitleCallback, callback)
                    }
                }
            }.onFailure { Log.w(TAG, "MagicLinks source failed ${safeUrl(srcUrl)}: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Watch Online (online.php -> /nf/index.php?videoUrl=...)
    // ------------------------------------------------------------------

    private suspend fun resolveWatchOnline(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        val redirected = resolveRedirect(url) ?: url
        extractVideoUrlParam(redirected)?.let { videoUrl ->
            if (isDirectVideo(videoUrl)) {
                emitDirect(videoUrl, label, redirected, callback)
                return
            }
        }
        // Fallback: scrape the nf page for video tags or embedded URLs
        val doc = runCatching { safeGetDocument(redirected, url) }.getOrNull() ?: return
        doc.select("video source[src], video[src]").mapNotNull { it.attr("src").ifBlank { null } }
            .filter { isDirectVideo(it) }
            .forEach { emitDirect(it, label, redirected, callback) }
    }

    private fun extractVideoUrlParam(url: String): String? {
        val raw = Regex("""(?i)[?&]videoUrl=(https?%3A%2F%2F[^&]+|https?://[^&]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()
    }

    // ------------------------------------------------------------------
    // Zip-Zap (download99.php -> signed R2 URL)
    // ------------------------------------------------------------------

    private suspend fun resolveZipZap(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        // First hop may attach exp/sig
        var current = resolveRedirect(url) ?: url
        // Second hop if dl= is present
        if (current.contains("download99.php", true) && current.contains("dl=", true)) {
            current = resolveRedirect(current) ?: current
        }
        if (isDirectVideo(current)) {
            emitDirect(current, label, url, callback)
            return
        }
        // Scrape landing page for R2 / worker buttons
        val doc = runCatching { safeGetDocument(current, url) }.getOrNull() ?: return
        val targets = doc.select("a[href]").mapNotNull { a ->
            val href = absoluteUrl(doc, a.attr("href"))
            href.takeIf { it.contains("dl=r2", true) || it.contains("dl=worker", true) || it.contains("download99.php", true) }
        }.distinct()
        for (target in targets) {
            val final = resolveRedirect(target) ?: target
            if (isDirectVideo(final)) {
                emitDirect(final, label, url, callback)
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // SkyDrop resolution
    // ------------------------------------------------------------------

    private fun isSkydropUrl(url: String): Boolean {
        return url.contains("skydrop", true)
    }

    private suspend fun resolveSkydrop(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host ?: return
        val query = uri.query.orEmpty()
        val id = Regex("""(?:^|&)id=([^&]+)""").find(query)?.groupValues?.get(1)
            ?: Regex("""(?:^|&)id=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: return

        val apiUrl = "${uri.scheme ?: "https"}://$host/api.php?id=$id"
        val jsonText = runCatching {
            app.get(apiUrl, headers = headers + mapOf(
                "Accept" to "application/json",
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            ), timeout = 20).text
        }.getOrNull()

        if (!jsonText.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(jsonText)
                listOf("download_url", "link", "url", "file").forEach { key ->
                    val value = obj.optString(key).trim()
                    if (value.startsWith("http", true)) {
                        if (isDirectVideo(value) || value.contains("download=1", true)) {
                            emitDirect(value, "SkyDrop", url, callback)
                        } else if (value.contains("googleusercontent", true)) {
                            emitDirect(value, "SkyDrop", url, callback)
                        } else {
                            loadExtractor(value, url, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Redirect & direct helpers
    // ------------------------------------------------------------------

    private suspend fun resolveRedirect(url: String): String? {
        var current = url
        repeat(6) {
            if (isDirectVideo(current)) return current
            val resp = runCatching {
                app.get(current, headers = headers, allowRedirects = false, timeout = 15)
            }.getOrNull() ?: return current
            if (resp.code !in 300..399) return current
            val loc = resp.headers["Location"] ?: resp.headers["location"] ?: return current
            current = runCatching { URI(current).resolve(loc).toString() }.getOrElse { fixUrl(loc) }
        }
        return current
    }

    private fun isDirectVideo(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
            path.endsWith(".mov") || path.endsWith(".avi") || path.endsWith(".m3u8") ||
            url.contains(".r2.dev/", true) || url.contains("video-downloads.googleusercontent.com", true) ||
            (url.contains("skydrop", true) && url.contains("api.php", true) && url.contains("download=1", true))
    }

    private suspend fun emitDirect(url: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val quality = qualityFromLabel(label, url)
        callback.invoke(newExtractorLink("KMMovies", "KMMovies • ${label.ifBlank { "Direct" }}", url) {
            this.quality = quality
            this.referer = referer
        })
    }

    private fun qualityFromLabel(label: String, url: String): Int {
        val value = "$label $url".lowercase()
        return when {
            value.contains("4320") || value.contains("8k") -> 4320
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1440") || value.contains("2k") -> Qualities.P1440.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private fun String.normalise(): String = replace(Regex("\\s+"), " ").trim()

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("""(?i)\s*-\s*KMMOVIES.*$"""), "")
            .replace(Regex("""(?i)\s+(?:Download|Watch Online)\s+(?:\d{3,4}p|4K|WEB[- ]?DL|BluRay).*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '|', ':')
            .ifBlank { value.trim() }
    }

    private fun isSeriesTitle(value: String): Boolean {
        return Regex("""(?i)\b(?:season\s*\d+|s\d{1,2}(?:\s*[-–]\s*s?\d{1,2})?|\d+\s*episodes?)\b""").containsMatchIn(value) ||
            value.contains("series", ignoreCase = true)
    }

    private fun safeUrl(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull() ?: return "<url>"
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val sensitive = url.contains("sig=", true) || url.contains("token=", true) || url.contains("googleusercontent", true)
        return if (sensitive) "$host$path?<redacted>" else "$host$path"
    }

    private companion object {
        private const val TAG = "KMMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
