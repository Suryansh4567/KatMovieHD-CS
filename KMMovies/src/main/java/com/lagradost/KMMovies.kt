package com.lagradost

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * KMMovies provider.
 *
 * KMMovies is a WordPress site with a deliberately simple, server-rendered
 * card/detail layout. Download buttons point at a second layer of pages:
 * movie buttons use magiclinks.lol, while episode-wise series buttons use
 * episodes.magiclinks.lol and expose one row per episode. The provider keeps
 * those pages as data until loadLinks(), but expands episode pages during
 * load() when possible so CloudStream gets real season/episode entries.
 *
 * The extractor is intentionally conservative: it follows normal HTTP
 * redirects and parses video/source tags, then delegates recognised hosts to
 * CloudStream's stock extractor registry. It does not execute JavaScript,
 * solve captchas, or download a file into memory.
 */
class KMMovies : MainAPI() {
    override var name = "KMMovies"
    override var mainUrl = "https://kmmovies.shop"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    /** The current site's public category and browse pages. */
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

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

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

    /**
     * Plain GET first, CloudflareKiller second. A challenge page is treated
     * as a failed document so parsers never mistake it for an empty movie.
     */
    private suspend fun safeGetDocument(url: String, referer: String? = null): Document {
        val requestHeaders = if (referer.isNullOrBlank()) {
            headers
        } else {
            headers + ("Referer" to referer)
        }

        try {
            val direct = app.get(url, headers = requestHeaders, timeout = 25).document
            if (!isCloudflarePage(direct)) return direct
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "GET failed for ${safeUrlForLog(url)}: ${error.message}")
        }

        return app.get(
            url,
            headers = requestHeaders,
            interceptor = CloudflareKiller(),
            timeout = 30
        ).document
    }

    private fun absoluteUrl(document: Document, value: String): String {
        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"
        return if (value.startsWith("http", ignoreCase = true)) {
            value.replace(" ", "%20")
        }
        else document.location().let { location ->
            runCatching { URI(location).resolve(value).toString() }
                .getOrElse { fixUrl(value) }
        }
    }

    private fun fixUrl(value: String): String {
        val value = value.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http", ignoreCase = true)) return value
        return mainUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = mainUrl.trimEnd('/')
        val cleanPath = when {
            path.isBlank() -> "/"
            path.startsWith("/") -> path
            else -> "/$path"
        }.replace(Regex("/{2,}"), "/")

        if (page <= 1) return "$base/${cleanPath.trimStart('/')}".trimEnd('/') + "/"
        return if (cleanPath == "/") {
            "$base/page/$page/"
        } else {
            "$base/${cleanPath.trim('/').trimEnd('/')}/page/$page/"
        }
    }

    // ---------------------------------------------------------------------
    // Main page and search
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = safeGetDocument(url)
        val results = parseListing(document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
        val results = parseListing(safeGetDocument(url))
        Log.d(TAG, "search('$query', page=$page) -> ${results.size} results")
        return results.toNewSearchResponseList()
    }

    // CloudStream versions used by older clients still call this overload.
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseListing(safeGetDocument("$mainUrl/?s=$encoded"))
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val cards = document.select("article.movie-card")
        if (cards.isEmpty()) {
            // Small fallback for a future theme that keeps the same title and
            // poster classes but changes <article> to another container.
            return document.select(".movie-title").mapNotNull { titleNode ->
                val card = titleNode.parents().firstOrNull { it.selectFirst("a[href]") != null }
                    ?: return@mapNotNull null
                card.toSearchResponse(document)
            }.distinctBy { it.url }
        }
        return cards.mapNotNull { it.toSearchResponse(document) }.distinctBy { it.url }
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

        val poster = selectFirst("img.poster, img[src]")?.let { image ->
            image.attr("data-src").ifBlank { image.attr("src") }
        }?.let { absoluteUrl(document, it) }

        val typeText = selectFirst(".meta-row span")?.text()?.lowercase().orEmpty()
        val type = if (typeText.contains("series") || isSeriesTitle(rawTitle)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val qualityText = selectFirst(".badge-left")?.text().orEmpty()

        return newMovieSearchResponse(cleanTitle(rawTitle), url, type) {
            this.posterUrl = poster
            this.quality = searchQuality(qualityText.ifBlank { rawTitle })
        }
    }

    private fun searchQuality(value: String): SearchQuality? {
        return runCatching { getQualityFromString(value) }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Detail page
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val document = safeGetDocument(pageUrl)
        val rawTitle = document.selectFirst("h1.hero-title, h1.entry-title, h1")?.text()
            ?.trim()
            ?.ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content") }
            ?: document.title().substringBefore(" - KMMOVIES").trim()
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst("img.hero-poster")?.let { image ->
            absoluteUrl(document, image.attr("src"))
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val backdrop = extractBackdrop(document)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: poster
        val plot = document.selectFirst(".hero-description")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
        val year = extractYear(document, rawTitle)
        val score = extractScore(document)
        val genres = extractAboutValue(document, "Genres")
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
        val cast = document.select(".about-cast-chip")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val trailer = document.selectFirst("a.open-trailer[data-trailer-url]")
            ?.attr("data-trailer-url")
            ?.takeIf { it.isNotBlank() }
        val imdbUrl = document.selectFirst("a[href*='imdb.com/title']")?.attr("href")
        val imdbId = imdbUrl?.let { Regex("""\b(tt\d+)\b""").find(it)?.groupValues?.get(1) }
        val recommendations = parseRecommendations(document)
        val isSeries = isSeriesPage(document, rawTitle)

        suspend fun LoadResponse.applyCommonMetadata() {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score
            this.recommendations = recommendations
            if (cast.isNotEmpty()) {
                this.actors = cast.map { ActorData(Actor(it, null), roleString = null) }
            }
            addTrailer(trailer)
            imdbUrl?.let {
                addImdbUrl(it)
                imdbId?.let { id -> this.addImdbId(id) }
            }
        }

        if (isSeries) {
            val links = expandSeriesLinks(document, pageUrl)
            if (links.isNotEmpty()) {
                val episodes = links
                    .groupBy { it.season to it.episode }
                    .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                    .map { (key, sources) ->
                        val (season, episode) = key
                        newEpisode(sources.joinToString("\n") { it.encode() }) {
                            this.name = "Episode $episode"
                            this.season = season
                            this.episode = episode
                        }
                    }
                return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    applyCommonMetadata()
                }
            }

            // Do not turn a valid series into a movie just because an
            // episode-wise landing page was temporarily unavailable.
            val fallback = collectDownloadLinks(document).map {
                DownloadLink(1, 1, it.label, it.url)
            }
            val episode = newEpisode(fallback.joinToString("\n") { it.encode() }) {
                this.name = "Season 1"
                this.season = 1
                this.episode = 1
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, listOf(episode)) {
                applyCommonMetadata()
            }
        }

        val downloads = collectDownloadLinks(document)
        val data = downloads.joinToString("\n") { it.encode() }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
            applyCommonMetadata()
        }
    }

    private fun extractBackdrop(document: Document): String? {
        val style = document.selectFirst("article.movie-hero")?.attr("style").orEmpty()
        val match = Regex("""url\(['\"]?([^)'\"]+)""").find(style)
        return match?.groupValues?.getOrNull(1)?.let { absoluteUrl(document, it) }
    }

    private fun extractYear(document: Document, title: String): Int? {
        val about = extractAboutValue(document, "Release")?.text().orEmpty()
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(about)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractScore(document: Document): Score? {
        val value = document.selectFirst(".rating-star")?.text().orEmpty()
        val score = Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(value)?.groupValues?.get(1)?.toDoubleOrNull()
        return score?.takeIf { it > 0.0 }?.let { Score.from10(it) }
    }

    private fun extractAboutValue(document: Document, label: String): Element? {
        return document.select(".about-meta-box").firstOrNull { box ->
            box.selectFirst(".about-meta-label")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".about-meta-value")
    }

    private fun isSeriesPage(document: Document, title: String): Boolean {
        val type = document.select(".about-highlight-pill").firstOrNull { pill ->
            pill.selectFirst(".about-highlight-label")?.text()?.trim()?.equals("Type", true) == true
        }?.selectFirst(".about-highlight-value")?.text().orEmpty()
        return type.equals("Series", true) ||
            document.select(".season-block, .download-category.tv-series").isNotEmpty() ||
            isSeriesTitle(title)
    }

    private fun parseRecommendations(document: Document): List<SearchResponse> {
        return document.select(".related-movies article.movie-card")
            .mapNotNull { it.toSearchResponse(document) }
            .distinctBy { it.url }
            .take(12)
    }

    // ---------------------------------------------------------------------
    // Series parsing
    // ---------------------------------------------------------------------

    private data class DownloadLink(
        val season: Int,
        val episode: Int,
        val label: String,
        val url: String
    ) {
        fun encode(): String = listOf(
            "KMM2",
            season.toString(),
            episode.toString(),
            label.replace("|", " ").replace("\n", " "),
            url
        ).joinToString("|")

        companion object {
            fun decode(line: String): DownloadLink? {
                val parts = line.split("|", limit = 5)
                if (parts.size != 5 || parts[0] != "KMM2") return null
                val season = parts[1].toIntOrNull() ?: return null
                val episode = parts[2].toIntOrNull() ?: return null
                val label = parts[3].trim()
                val url = parts[4].trim()
                if (url.isBlank()) return null
                return DownloadLink(season, episode, label, url)
            }
        }
    }

    /**
     * Extracts only the "Episode Wise" tab. Combined and ZIP links are not
     * mixed into episode sources because they are complete-season files.
     */
    private suspend fun expandSeriesLinks(document: Document, referer: String): List<DownloadLink> {
        val blocks = document.select(".season-block")
        val output = mutableListOf<DownloadLink>()
        val work = blocks.flatMap { block ->
            val title = block.selectFirst(".season-block-title")?.text().orEmpty()
            val season = Regex("""(?i)season\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val count = Regex("""\((\d+)\s*eps?\)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val buttons = block.select(".type-content[data-type^=episodes] a.dl-btn")
                .ifEmpty { block.select(".type-content.active a.dl-btn") }
            buttons.mapNotNull { button ->
                val href = absoluteUrl(document, button.attr("href"))
                if (href.isBlank()) null else SeriesWork(season, count, button.text().normalise(), href, referer)
            }
        }

        if (work.isEmpty()) return output
        val expanded = supervisorScope {
            work.map { item ->
                async {
                    runCatching { expandEpisodeWisePage(item) }
                        .onFailure { Log.w(TAG, "Episode page failed ${safeUrlForLog(item.url)}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        output += expanded
        return output.distinctBy { "${it.season}:${it.episode}:${it.label}:${it.url}" }
    }

    private data class SeriesWork(
        val season: Int,
        val episodeCount: Int,
        val label: String,
        val url: String,
        val referer: String
    )

    private suspend fun expandEpisodeWisePage(work: SeriesWork): List<DownloadLink> {
        // Some site revisions publish the per-episode rows directly on the
        // page; the selector is intentionally not limited to one host.
        val document = safeGetDocument(work.url, work.referer)
        val rows = document.select(".ep-row")
        if (rows.isNotEmpty()) {
            return rows.mapIndexedNotNull { index, row ->
                val episode = Regex("""(?i)(?:episode|ep)\s*[-#:]?\s*(\d+)""")
                    .find(row.selectFirst(".ep-name")?.text().orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: index + 1
                val link = row.selectFirst("a[href]")?.let { absoluteUrl(document, it.attr("href")) }
                link?.takeIf { it.isNotBlank() }?.let {
                    DownloadLink(work.season, episode, work.label, it)
                }
            }
        }

        // A landing page can occasionally hide rows behind a redirect. Keep
        // a lazy source for every advertised episode; loadLinks() retries the
        // page and selects the requested row then.
        return (1..work.episodeCount.coerceAtLeast(1)).map {
            DownloadLink(work.season, it, work.label, work.url)
        }
    }

    // ---------------------------------------------------------------------
    // Link extraction
    // ---------------------------------------------------------------------

    private fun collectDownloadLinks(document: Document): List<DownloadLink> {
        val links = document.select(".downloads-section a.dl-btn").mapNotNull { button ->
            val url = absoluteUrl(document, button.attr("href"))
            url.takeIf { it.isNotBlank() }?.let {
                DownloadLink(0, 0, button.text().normalise(), it)
            }
        }
        if (links.isNotEmpty()) return links.distinctBy { it.url }

        // Fallback for an older post template: direct HTML5 sources, iframes,
        // or clearly-labelled download anchors.
        return document.select("video source[src], video[src], iframe[src], a[href]")
            .mapNotNull { node ->
                val value = if (node.tagName() == "a") node.attr("href")
                else node.attr("src")
                val text = node.text().normalise()
                val url = absoluteUrl(document, value)
                if (url.isBlank() || !isCandidateUrl(url, text)) null
                else DownloadLink(0, 0, text.ifBlank { "Source" }, url)
            }
            .distinctBy { it.url }
    }

    private data class SourceTarget(val url: String, val label: String)

    private fun isMagicLinksPost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        // magiclinks.lol and mirrors such as w1.magiclinks.lol
        return host.contains("magiclinks") &&
            Regex("""/\d+-\d+/?$""").containsMatchIn(path)
    }

    /**
     * MagicLinks deliberately serves a decoy WordPress article to plain HTTP
     * clients. The real file metadata is still exposed by the site's public
     * WordPress REST endpoint. Resolve the post by its numeric slug instead of
     * depending on browser JavaScript, cookies, or the decoy HTML page.
     */
    private suspend fun extractMagicLinksSources(url: String): List<SourceTarget> {
        if (!isMagicLinksPost(url)) return emptyList()
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host ?: return emptyList()
        val slug = uri.path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return emptyList()

        // Prefer the post's own host (magiclinks.lol vs w1.magiclinks.lol). Fall back
        // to the sibling host when the slug is unknown on the first host.
        val hostsToTry = linkedSetOf(
            host,
            if (host.startsWith("w1.")) host.removePrefix("w1.") else "w1.$host",
            "magiclinks.lol",
            "w1.magiclinks.lol"
        )

        var root: JSONObject? = null
        var resolvedBase = url
        for (tryHost in hostsToTry) {
            val apiUrl =
                "${uri.scheme ?: "https"}://$tryHost/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=content,meta,link"
            val jsonText = runCatching {
                app.get(
                    apiUrl,
                    headers = headers + mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to url,
                        "Origin" to "${uri.scheme ?: "https"}://$tryHost"
                    ),
                    timeout = 20
                ).text
            }.getOrNull() ?: continue

            val candidate = runCatching { JSONArray(jsonText).optJSONObject(0) }.getOrNull()
            if (candidate != null) {
                root = candidate
                resolvedBase = candidate.optString("link").ifBlank { "https://$tryHost/$slug/" }
                break
            }
        }
        val post = root ?: return scrapeMagicLinksHtml(url)

        val output = linkedMapOf<String, SourceTarget>()
        fun add(raw: String?, label: String) {
            val candidate = raw?.trim().orEmpty()
            if (candidate.isBlank()) return
            if (candidate.startsWith("javascript:", true) || candidate == "#") return
            val absolute = if (candidate.startsWith("http", true)) {
                candidate.replace(" ", "%20")
            } else {
                runCatching { URI(resolvedBase).resolve(candidate).toString() }.getOrElse { return }
            }
            if (isCandidateUrl(absolute, label) || isDirectVideo(absolute) ||
                absolute.contains("download", true) ||
                absolute.contains("online.php", true) ||
                absolute.contains("download99.php", true) ||
                absolute.contains("skydrop", true) ||
                absolute.contains("kmphotos", true)
            ) {
                output.putIfAbsent(absolute, SourceTarget(absolute, label))
            }
        }

        val meta = post.optJSONObject("meta")
        val metaFields = listOf(
            "watch_online_url" to "Watch Online",
            "watch_online2_url" to "Watch Online 2",
            "zip_zap_url" to "Zip-Zap",
            "skydrop_url" to "SkyDrop",
            "cloud_porter_url" to "Cloud Porter",
            "hubcloud_url" to "HubCloud",
            "gdtot_url" to "GDTot",
            "gofile_url" to "GoFile",
            "pixeldrain_url" to "Pixeldrain",
            "gdflix_url" to "GDFlix",
            "filepress_url" to "Filepress",
            "one_click_url" to "One Click",
            "transfer_it_url" to "Transfer.it",
            "ultra_fast_download_url" to "Fast Download"
        )
        metaFields.forEach { (key, label) -> add(meta?.optString(key), label) }

        // Some WP installs put custom fields at the top level instead of meta.
        metaFields.forEach { (key, label) ->
            if (!meta?.optString(key).isNullOrBlank()) return@forEach
            add(post.optString(key), label)
        }

        val rendered = post.optJSONObject("content")?.optString("rendered").orEmpty()
        if (rendered.isNotBlank()) {
            val renderedDocument = org.jsoup.Jsoup.parse(rendered, resolvedBase)
            renderedDocument.select("a[href]").forEach { anchor ->
                add(anchor.absUrl("href").ifBlank { anchor.attr("href") }, anchor.text().normalise())
            }
            Regex("""https?://[^\s"'<>\\]+""")
                .findAll(rendered)
                .forEach { add(it.value.trimEnd('\\', ',', '.', ';'), "MagicLinks source") }
        }

        if (output.isEmpty()) {
            output.putAll(scrapeMagicLinksHtml(url).associateBy { it.url })
        }
        return prioritizeSources(output.values.toList()).take(16)
    }

    private suspend fun scrapeMagicLinksHtml(url: String): List<SourceTarget> {
        val document = runCatching { safeGetDocument(url) }.getOrNull() ?: return emptyList()
        val output = linkedMapOf<String, SourceTarget>()
        document.select("a[href]").forEach { anchor ->
            val href = absoluteUrl(document, anchor.attr("href"))
            val label = anchor.text().normalise().ifBlank { "Source" }
            if (href.isNotBlank() && (
                    isCandidateUrl(href, label) ||
                        href.contains("online.php", true) ||
                        href.contains("download99.php", true) ||
                        href.contains("skydrop", true) ||
                        href.contains("kmphotos", true)
                    )
            ) {
                output.putIfAbsent(href, SourceTarget(href, label))
            }
        }
        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(document.html())
            .map { it.value.trimEnd('\\', ',', '.', ';', ')', ']') }
            .filter {
                it.contains("online.php", true) ||
                    it.contains("download99.php", true) ||
                    it.contains("skydrop", true) ||
                    isDirectVideo(it)
            }
            .forEach { output.putIfAbsent(it, SourceTarget(it, "MagicLinks source")) }
        return prioritizeSources(output.values.toList()).take(12)
    }

    private fun prioritizeSources(sources: List<SourceTarget>): List<SourceTarget> {
        fun rank(label: String, url: String): Int {
            val v = "$label $url".lowercase()
            return when {
                v.contains("watch online") || v.contains("online.php") -> 0
                v.contains("zip") || v.contains("download99") -> 1
                v.contains("skydrop") -> 2
                v.contains("hubcloud") -> 3
                v.contains("gofile") || v.contains("pixeldrain") -> 4
                else -> 5
            }
        }
        return sources.sortedWith(compareBy({ rank(it.label, it.url) }, { it.label }))
    }

    /** Extracts JWPlayer/video URLs embedded in wrapper-page JavaScript. */
    private fun extractEmbeddedMediaUrls(document: Document): List<String> {
        val output = linkedSetOf<String>()
        val scripts = document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val html = document.html()
        val blob = scripts + "\n" + html
        val patterns = listOf(
            Regex("""(?i)\bfile\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""(?i)\b(?:src|url|source|fileUrl|videoUrl|mediaUrl)\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""(?i)\"(?:file|src|url|videoUrl|fileUrl)\"\s*:\s*\"([^\"]+)\""""),
            Regex("""(?i)(?:videoUrl|file|src)\s*=\s*['"]([^'"]+)['"]""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(blob).forEach { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .trim()
                val absolute = absoluteUrl(document, raw)
                if (isDirectVideo(absolute)) output += absolute
            }
        }
        // Form fields on the KMMOVIES player page hold the same direct URL.
        document.select("input#videoUrl, input[name=videoUrl], input[value*=.mkv], input[value*=.mp4], input[value*=.m3u8]")
            .forEach { input ->
                val absolute = absoluteUrl(document, input.attr("value"))
                if (isDirectVideo(absolute)) output += absolute
            }
        // Raw media URLs anywhere in the page.
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mkv|webm|m3u8)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(blob)
            .forEach { output += it.value.trimEnd('\\', ',', '.', ';', ')', ']') }
        return output.toList()
    }

    /** Pulls a nested media URL out of ?videoUrl= style player wrappers. */
    private fun extractNestedMediaParam(url: String): String? {
        // Only accept fully-qualified nested media URLs. online.php?file=NAME.mkv
        // stores a bare filename, not a playable URL, so it must not match here.
        val patterns = listOf(
            Regex("""(?i)[?&]videoUrl=(https?%3A%2F%2F[^&]+|https?://[^&]+)"""),
            Regex("""(?i)[?&](?:src|url|media)=(https?%3A%2F%2F[^&]+|https?://[^&]+)""")
        )
        for (pattern in patterns) {
            val raw = pattern.find(url)?.groupValues?.getOrNull(1) ?: continue
            val candidate = runCatching {
                java.net.URLDecoder.decode(raw, "UTF-8")
            }.getOrDefault(raw).trim().replace(" ", "%20")
            if (!candidate.startsWith("http", true)) continue
            if (isDirectVideo(candidate)) return candidate
            // Nested wrappers sometimes encode another online.php / nf player URL.
            if (candidate.contains("online.php", true) || candidate.contains("/nf/index.php", true)) {
                extractNestedMediaParam(candidate)?.let { return it }
            }
        }
        return null
    }

    private fun parseSourceData(data: String): List<DownloadLink> {
        // CloudStream normally stores the String payload verbatim, but older
        // clients may return it JSON-escaped or with literal \\n sequences.
        // Normalize those variants before decoding the structured KMM2 data.
        val normalized = data.trim()
            .trim('"')
            .replace("""\n""", "\n")
            .replace("""\r""", "\r")
            .replace("""\"""", "\"")
            .replace("&amp;", "&")
        val structured = normalized.lines().mapNotNull { DownloadLink.decode(it.trim()) }
        if (structured.isNotEmpty()) return structured

        // Last-resort URL recovery. This prevents a player/cache format change
        // from turning a valid source payload into an empty source list.
        return Regex("""https?://[^\s\"'<>\\]+""")
            .findAll(normalized)
            .map { it.value.trimEnd(',', '.', ';', ')', ']') }
            .distinct()
            .map { DownloadLink(0, 0, "Source", it) }
            .toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseSourceData(data)
        Log.d(TAG, "loadLinks: parsed ${links.size} source payload(s)")
        if (links.isEmpty()) return false

        var attempted = false
        links.distinctBy { "${it.episode}:${it.label}:${it.url}" }.forEach { link ->
            val targets = if (isMagicLinksPost(link.url)) {
                extractMagicLinksSources(link.url).ifEmpty {
                    listOf(SourceTarget(link.url, link.label))
                }
            } else {
                listOf(SourceTarget(link.url, link.label))
            }
            targets.forEach { target ->
                attempted = true
                runCatching {
                    // `data` is the serialized source payload, not a valid
                    // HTTP Referer. Start with the provider origin; nested
                    // pages then replace it with their own URL.
                    dispatchLink(
                        target.url,
                        target.label.ifBlank { link.label },
                        link.season,
                        link.episode,
                        mainUrl,
                        0,
                        subtitleCallback,
                        callback
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Link failed ${safeUrlForLog(target.url)}: ${error.message}")
                }
            }
        }
        return attempted
    }

    private suspend fun dispatchLink(
        sourceUrl: String,
        label: String,
        season: Int,
        episode: Int,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (depth > 6 || sourceUrl.isBlank()) return
        val url = sourceUrl.trim()

        // Fast path: nested player wrappers already expose the media URL.
        extractNestedMediaParam(url)?.let { nested ->
            emitDirect(nested, label, url, callback)
            return
        }

        // Zip-Zap's `download99.php?...&dl=` endpoint is still a
        // redirector, not the final media URL. Resolve it below first.
        if (isDirectVideo(url) && !isZipZapFinalUrl(url) && !isKmphotosRedirector(url)) {
            emitDirect(url, label, referer, callback)
            return
        }

        // Episode-wise pages contain rows that map to actual episode files.
        // Select only the requested row when this is a series source.
        if (isEpisodeWisePage(url)) {
            val document = safeGetDocument(url, referer)
            val rows = document.select(".ep-row")
            if (rows.isNotEmpty()) {
                val row = rows.firstOrNull { row ->
                    val n = Regex("""(?i)(?:episode|ep)\s*[-#:]?\s*(\d+)""")
                        .find(row.selectFirst(".ep-name")?.text().orEmpty())
                        ?.groupValues?.get(1)?.toIntOrNull()
                    n == episode
                } ?: rows.getOrNull((episode - 1).coerceAtLeast(0))
                row?.select("a[href]")?.forEach { anchor ->
                    dispatchLink(
                        absoluteUrl(document, anchor.attr("href")),
                        label,
                        season,
                        episode,
                        url,
                        depth + 1,
                        subtitleCallback,
                        callback
                    )
                }
                return
            }
        }

        // Watch Online: online.php -> /nf/index.php?videoUrl=... -> JWPlayer file
        if (isWatchOnlineUrl(url)) {
            if (resolveWatchOnline(url, label, referer, callback)) return
        }

        // Zip-Zap intermediate pages expose signed R2 / worker download buttons.
        if (isZipZapPage(url)) {
            val targets = extractZipZapTargets(url, referer)
            if (targets.isNotEmpty()) {
                targets.forEach {
                    dispatchLink(
                        it,
                        label,
                        season,
                        episode,
                        url,
                        depth + 1,
                        subtitleCallback,
                        callback
                    )
                }
                return
            }
        }

        // SkyDrop now uses XHR api.php?id=... rather than a static ready page.
        if (isSkyDropDownloadPage(url)) {
            val targets = resolveSkyDropApi(url, referer)
            if (targets.isNotEmpty()) {
                targets.forEach {
                    dispatchLink(
                        it,
                        label,
                        season,
                        episode,
                        url,
                        depth + 1,
                        subtitleCallback,
                        callback
                    )
                }
                return
            }
        }

        // Follow ordinary redirectors without downloading a possible video
        // body. The final URL is then either emitted directly or delegated.
        val redirected = resolveRedirect(url, referer)
        if (!redirected.isNullOrBlank() && !redirected.equals(url, true)) {
            extractNestedMediaParam(redirected)?.let { nested ->
                emitDirect(nested, label, url, callback)
                return
            }

            if (isDirectVideo(redirected) && !isZipZapFinalUrl(redirected)) {
                emitDirect(redirected, label, url, callback)
                return
            }

            if (isWatchOnlineUrl(redirected) || redirected.contains("/nf/index.php", true)) {
                if (resolveWatchOnline(redirected, label, url, callback)) return
            }

            if (isZipZapPage(redirected)) {
                val targets = extractZipZapTargets(redirected, url)
                if (targets.isNotEmpty()) {
                    targets.forEach {
                        dispatchLink(it, label, season, episode, redirected, depth + 1, subtitleCallback, callback)
                    }
                    return
                }
            }

            // SkyDrop uses a two-step flow: download.php -> api.php -> ready.php?link=...
            if (isSkyDropReadyPage(redirected) || isSkyDropDownloadPage(redirected)) {
                val targets = if (isSkyDropDownloadPage(redirected) && !isSkyDropReadyPage(redirected)) {
                    resolveSkyDropApi(redirected, url)
                } else {
                    extractSkyDropTargets(redirected, url)
                }
                if (targets.isNotEmpty()) {
                    targets.forEach {
                        dispatchLink(
                            it,
                            label,
                            season,
                            episode,
                            redirected,
                            depth + 1,
                            subtitleCallback,
                            callback
                        )
                    }
                    return
                }
            }

            if (URI_SAFE_HOST_REGEX.containsMatchIn(redirected) &&
                !isSkyDropReadyPage(redirected) &&
                !isSkyDropDownloadPage(redirected) &&
                !isZipZapPage(redirected) &&
                !isWatchOnlineUrl(redirected)
            ) {
                val delegated = runCatching {
                    loadExtractor(redirected, url, subtitleCallback, callback)
                }.getOrDefault(false)
                if (delegated) return
            }
        }

        // Try a normal CloudStream extractor before scraping a wrapper page.
        if (!url.contains("magiclinks", true) &&
            !isSkyDropReadyPage(url) &&
            !isSkyDropDownloadPage(url) &&
            !isZipZapPage(url) &&
            !isWatchOnlineUrl(url)
        ) {
            val delegated = runCatching {
                loadExtractor(url, referer, subtitleCallback, callback)
            }.getOrDefault(false)
            if (delegated) return
        }

        // The initial request may itself already be a SkyDrop ready page.
        if (isSkyDropReadyPage(url)) {
            val targets = extractSkyDropTargets(url, referer)
            if (targets.isNotEmpty()) {
                targets.forEach {
                    dispatchLink(
                        it,
                        label,
                        season,
                        episode,
                        url,
                        depth + 1,
                        subtitleCallback,
                        callback
                    )
                }
                return
            }
        }

        // Wrapper pages sometimes expose a direct <video>/<source> or a
        // second host link in HTML. This also gives a useful fallback for
        // hosts that are not yet in CloudStream's extractor registry.
        // If a redirect landed on a wrapper page that CloudStream does not
        // recognize, parse the redirected page rather than going back to the
        // original shortener.
        val pageToParse = redirected?.takeIf {
            it.isNotBlank() && !it.equals(url, true) && !isDirectVideo(it)
        } ?: url
        val document = runCatching { safeGetDocument(pageToParse, referer) }.getOrNull() ?: return
        val inline = document.select("video source[src], video[src], meta[property=og:video], meta[property=og:video:url]")
            .mapNotNull { node ->
                val raw = node.attr("src").ifBlank { node.attr("content") }
                absoluteUrl(document, raw).takeIf { isDirectVideo(it) }
            }
        val embedded = extractEmbeddedMediaUrls(document)
        val playable = (inline + embedded).distinct()
        if (playable.isNotEmpty()) {
            playable.forEach { emitDirect(it, label, pageToParse, callback) }
            return
        }

        // Zip-Zap landing HTML after the first signed redirect.
        if (isZipZapPage(pageToParse) || document.select("a[href*='dl=r2'], a[href*='dl=worker']").isNotEmpty()) {
            val zipTargets = extractZipZapTargetsFromDocument(document, pageToParse)
            if (zipTargets.isNotEmpty()) {
                zipTargets.forEach {
                    dispatchLink(it, label, season, episode, pageToParse, depth + 1, subtitleCallback, callback)
                }
                return
            }
        }

        val nested = document.select("a[href]").mapNotNull { anchor ->
            val candidate = absoluteUrl(document, anchor.attr("href"))
            candidate.takeIf { isCandidateUrl(candidate, anchor.text()) && candidate != url }
        }.distinct().take(8)
        nested.forEach {
            dispatchLink(it, label, season, episode, pageToParse, depth + 1, subtitleCallback, callback)
        }
    }

    private fun isSkyDropReadyPage(url: String): Boolean {
        return url.contains("skydrop", true) &&
            (url.contains("/ready.php", true) || (url.contains("link=", true) && !url.contains("api.php", true)))
    }

    private fun isSkyDropDownloadPage(url: String): Boolean {
        if (!url.contains("skydrop", true)) return false
        if (isSkyDropReadyPage(url)) return false
        // Final streaming endpoints are handled as direct media, not shells.
        if (url.contains("api.php", true) && (url.contains("download=1", true) || url.contains("file=", true))) {
            return false
        }
        return url.contains("/download.php", true) ||
            url.contains("api.php?id=", true) ||
            Regex("""[?&]id=[^&]+""").containsMatchIn(url)
    }

    private fun isWatchOnlineUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("kmphotos") && (
            lower.contains("online.php") ||
                lower.contains("/nf/index.php") ||
                lower.contains("videourl=")
            )
    }

    private fun isZipZapPage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("download99.php") ||
            (lower.contains("kmphotos") && lower.contains("dl="))
    }

    private fun isKmphotosRedirector(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("kmphotos") && (
            lower.contains("online.php") ||
                lower.contains("download99.php") ||
                lower.contains("/nf/index.php")
            )
    }

    /**
     * online.php 302s to /nf/index.php?videoUrl=<direct mkv>.
     * Prefer the query parameter, then the JWPlayer setup block.
     */
    private suspend fun resolveWatchOnline(
        url: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractNestedMediaParam(url)?.let {
            emitDirect(it, label, url, callback)
            return true
        }

        val redirected = resolveRedirect(url, referer) ?: url
        extractNestedMediaParam(redirected)?.let {
            emitDirect(it, label, url, callback)
            return true
        }

        val document = runCatching { safeGetDocument(redirected, referer ?: url) }.getOrNull()
        if (document != null) {
            val embedded = extractEmbeddedMediaUrls(document)
            if (embedded.isNotEmpty()) {
                embedded.forEach { emitDirect(it, label, redirected, callback) }
                return true
            }
        }
        return false
    }

    private suspend fun extractZipZapTargets(url: String, referer: String?): List<String> {
        // First hop: download99.php?file=NAME  ->  download99.php?file=NAME&exp=..&sig=..
        // Final hop: download99.php?...&dl=r2  ->  https://pub-....r2.dev/file.mkv
        val page = resolveRedirect(url, referer) ?: url
        if (isDirectVideo(page)) return listOf(page)

        // Keep following one more redirect if the first hop only attached exp/sig.
        if (page.contains("download99.php", true) && page.contains("dl=", true)) {
            val finalUrl = resolveRedirect(page, referer ?: url) ?: page
            if (isDirectVideo(finalUrl)) return listOf(finalUrl)
        }

        val document = runCatching { safeGetDocument(page, referer ?: url) }.getOrNull()
            ?: return emptyList()
        val fromHtml = extractZipZapTargetsFromDocument(document, page)
        if (fromHtml.isNotEmpty()) return fromHtml

        // Absolute last resort: re-resolve the original URL's Location chain.
        val again = resolveRedirect(url, referer)
        return if (!again.isNullOrBlank() && isDirectVideo(again)) listOf(again) else emptyList()
    }

    private fun extractZipZapTargetsFromDocument(document: Document, pageUrl: String): List<String> {
        val targets = linkedSetOf<String>()
        document.select("a[href]").forEach { anchor ->
            val href = absoluteUrl(document, anchor.attr("href"))
            val text = anchor.text().normalise()
            val useful = href.contains("dl=r2", true) ||
                href.contains("dl=worker", true) ||
                href.contains("download99.php", true) ||
                Regex("""(?i)\b(fast|direct|download|r2|worker)\b""").containsMatchIn(text)
            if (useful && href.isNotBlank()) targets += href
        }
        // Prefer R2 over worker when both exist.
        val ordered = targets.sortedBy { link ->
            when {
                link.contains("dl=r2", true) -> 0
                link.contains("dl=worker", true) -> 1
                else -> 2
            }
        }
        return ordered.take(6)
    }

    /**
     * SkyDrop's download.php page runs XHR against api.php?id=... and returns
     * { success, link, download_url }. Prefer those JSON fields over the
     * decorative HTML, which only has an empty ready.php?link= placeholder.
     */
    private suspend fun resolveSkyDropApi(url: String, referer: String?): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host ?: return emptyList()
        val query = uri.query.orEmpty()
        val id = Regex("""(?:^|&)id=([^&]+)""").find(query)?.groupValues?.get(1)
            ?: Regex("""(?:^|&)id=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: return extractSkyDropTargets(url, referer)

        val apiUrl = "${uri.scheme ?: "https"}://$host/api.php?id=$id"
        val jsonText = runCatching {
            app.get(
                apiUrl,
                headers = headers + mapOf(
                    "Accept" to "application/json, text/javascript, */*",
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to "${uri.scheme ?: "https"}://$host"
                ),
                timeout = 20
            ).text
        }.getOrNull()

        val targets = linkedSetOf<String>()
        if (!jsonText.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(jsonText)
                if (obj.optBoolean("success", obj.has("link") || obj.has("download_url"))) {
                    listOf("link", "download_url", "url", "file", "download").forEach { key ->
                        val value = obj.optString(key).trim()
                        if (value.startsWith("http", true)) targets += value
                    }
                }
            }
        }

        // ready.php fallback if the JSON path is empty.
        if (targets.isEmpty()) {
            targets += extractSkyDropTargets(url, referer)
        } else {
            // Also try the ready page for hosts that only unlock the button there.
            val firstLink = targets.firstOrNull {
                it.contains("googleusercontent", true) || isDirectVideo(it)
            }
            if (firstLink != null) {
                val ready = "${uri.scheme ?: "https"}://$host/ready.php?link=${URLEncoder.encode(firstLink, "UTF-8")}"
                extractSkyDropTargets(ready, url).forEach { targets += it }
            }
        }
        return targets.filter {
            isDirectVideo(it) ||
                it.contains("googleusercontent", true) ||
                it.contains("download=1", true) ||
                it.contains("api.php?file=", true) ||
                it.contains("ready.php", true)
        }.distinct().take(8)
    }

    /**
     * SkyDrop's final button is not always an ordinary anchor. Depending on
     * the current template it can be an <a>, a data-* attribute, a form
     * action, or a small inline JavaScript redirect. Recover all of those
     * forms without executing JavaScript inside CloudStream.
     */
    private suspend fun extractSkyDropTargets(url: String, referer: String?): List<String> {
        val document = runCatching { safeGetDocument(url, referer) }.getOrNull() ?: return emptyList()
        val targets = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            val value = raw?.trim()?.trim('"', '\'', '(', ')', ';') ?: return
            if (value.isBlank() || value.startsWith("javascript:", true) || value == "#") return
            // ready.php?link= with an empty value is useless.
            if (value.contains("ready.php", true) && !value.contains("link=http", true) &&
                !Regex("""link=[^&]+""").containsMatchIn(value)
            ) return
            val absolute = absoluteUrl(document, value)
            if (absolute.isBlank() || absolute.equals(url, true)) return
            val useful = isDirectVideo(absolute) ||
                absolute.contains("download", true) ||
                absolute.contains("ready.php", true) ||
                absolute.contains("skydrop", true) ||
                absolute.contains("googleusercontent", true) ||
                absolute.contains("zip-zap", true) ||
                absolute.contains("api.php", true)
            if (useful) targets += absolute
        }

        document.select("a[href], form[action], [onclick], [data-href], [data-url], [data-download-url], [data-link], [data-target]")
            .forEach { node ->
                val text = node.text().normalise()
                val isDownloadButton = Regex("""(?i)\b(download|start|watch|skydrop|zip[- ]?zap)\b""")
                    .containsMatchIn(text)
                val values = listOf(
                    node.attr("href"),
                    node.attr("action"),
                    node.attr("data-href"),
                    node.attr("data-url"),
                    node.attr("data-download-url"),
                    node.attr("data-link"),
                    node.attr("data-target")
                )
                values.forEach { value ->
                    if (isDownloadButton || value.contains("download", true) ||
                        value.contains("skydrop", true) || value.contains("googleusercontent", true)
                    ) {
                        addCandidate(value)
                    }
                }
                Regex("""(?i)(https?://[^'"\\\s]+|/[^'"\\\s]+)""")
                    .findAll(node.attr("onclick"))
                    .forEach { addCandidate(it.value) }
            }

        // Inline handlers commonly use location.href, window.open, or a
        // quoted downloadUrl variable.
        val html = document.html()
        Regex("""(?i)(?:location(?:\.href)?|window\.open|downloadUrl|download_url|fileUrl|file_url)\s*[=(]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .forEach { addCandidate(it.groupValues.getOrNull(1)) }
        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(html)
            .map { it.value.trimEnd('\\', ',', '.', ';') }
            .filter {
                it.contains("skydrop", true) ||
                    it.contains("googleusercontent", true) ||
                    isDirectVideo(it)
            }
            .forEach(::addCandidate)

        // Do not call resolveSkyDropApi from here: that helper already falls
        // back to this HTML scraper when the JSON endpoint is empty.
        return targets.distinct().take(8)
    }

    private suspend fun resolveRedirect(url: String, referer: String?): String? {
        var current = url
        repeat(8) {
            if (isDirectVideo(current)) return current
            val requestHeaders = if (referer.isNullOrBlank()) headers else headers + ("Referer" to referer)
            val response = try {
                app.get(current, headers = requestHeaders, allowRedirects = false, timeout = 15)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                runCatching {
                    app.get(
                        current,
                        headers = requestHeaders,
                        interceptor = CloudflareKiller(),
                        allowRedirects = false,
                        timeout = 20
                    )
                }.getOrNull() ?: return null
            }
            if (response.code !in 300..399) return current
            val location = response.headers["Location"] ?: response.headers["location"] ?: return current
            current = runCatching { URI(current).resolve(location).toString() }.getOrElse { fixUrl(location) }
        }
        return current
    }

    private suspend fun emitDirect(
        url: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = qualityFrom(label, url)
        val type = if (url.substringBefore('?').contains(".m3u8", true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback.invoke(newExtractorLink("KMMovies", "KMMovies • ${label.ifBlank { "Direct" }}", url, type) {
            this.quality = quality
            this.referer = referer.orEmpty()
        })
    }

    private fun qualityFrom(label: String, url: String): Int {
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

    private fun isDirectVideo(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
            path.endsWith(".mov") || path.endsWith(".avi") || path.endsWith(".m3u8") ||
            url.contains(".mp4?", true) || url.contains(".mkv?", true) ||
            url.contains(".m3u8?", true) || url.contains(".webm?", true) ||
            // SkyDrop's final response is a signed Googleusercontent URL
            // with no file extension, but its host serves video/mkv.
            url.contains("video-downloads.googleusercontent.com/", true) ||
            // SkyDrop's secondary download endpoint streams the same file.
            (url.contains("skydrop", true) && url.contains("api.php", true) &&
                (url.contains("download=1", true) || url.contains("file=", true))) ||
            // Cloudflare R2 public buckets used by Zip-Zap / Watch Online.
            (url.contains(".r2.dev/", true) && (
                path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".webm") ||
                    url.contains(".mkv?", true) || url.contains(".mp4?", true)
                ))
    }

    private fun isZipZapFinalUrl(url: String): Boolean {
        // Any download99.php URL (with or without dl=) is still a redirector.
        return url.contains("download99.php", true)
    }

    private fun isEpisodeWisePage(url: String): Boolean {
        return url.contains("episodes.magiclinks", true) ||
            (url.contains("magiclinks", true) && url.contains("/series/", true)) ||
            (url.contains("/series/", true) && url.contains("magiclinks", true))
    }

    private fun isCandidateUrl(url: String, anchorText: String): Boolean {
        if (!url.startsWith("http", true)) return false
        if (isDirectVideo(url)) return true
        if (url.contains(mainUrl, true)) return false
        // magiclinks.lol is the movie wrapper host. Only its numeric
        // release paths are valid sources; reject its navigation/blog links
        // so a parked/decoy wrapper cannot recursively crawl the whole site.
        if (url.contains("magiclinks", true) &&
            !url.contains("episodes.", true) &&
            !Regex("""/\d+-\d+/?(?:\?.*)?$""").containsMatchIn(url)) return false
        if (url.contains("imdb.com", true) || url.contains("youtube.com", true) ||
            url.contains("youtu.be", true) || url.contains("facebook.com", true) ||
            url.contains("twitter.com", true) || url.contains("instagram.com", true) ||
            url.contains("t.me", true) || url.contains("whatsapp", true) ||
            url.contains("images.", true) || url.contains("wp-content", true) ||
            url.contains("analytics", true) || url.contains("doubleclick", true)) return false

        val hostLooksUseful = STREAM_HOST_REGEX.containsMatchIn(url)
        val textLooksUseful = Regex("""(?i)\b(download|direct|stream|watch|server|mirror|file|zip[- ]?zap)\b""").containsMatchIn(anchorText)
        return hostLooksUseful || textLooksUseful
    }

    private fun String.normalise(): String = replace(Regex("\\s+"), " ").trim()

    private fun cleanTitle(value: String): String {
        val cleaned = value
            .replace(Regex("""(?i)\s*-\s*KMMOVIES.*$"""), "")
            .replace(Regex("""(?i)\s+(?:Download|Watch Online)\s+(?:\d{3,4}p|4K|WEB[- ]?DL|BluRay).*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '|', ':')
        return cleaned.ifBlank { value.trim() }
    }

    private fun isSeriesTitle(value: String): Boolean {
        return Regex("""(?i)\b(?:season\s*\d+|s\d{1,2}(?:\s*[-–]\s*s?\d{1,2})?|\d+\s*episodes?)\b""").containsMatchIn(value) ||
            value.contains("series", ignoreCase = true)
    }


    /** Redacts signed query values so media tokens never hit device logs. */
    private fun safeUrlForLog(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull() ?: return "<url>"
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val sensitive = url.contains("sig=", true) ||
            url.contains("token=", true) ||
            url.contains("googleusercontent", true) ||
            url.contains("id=", true) && url.contains("skydrop", true)
        return if (sensitive) "$host$path?<redacted>" else "$host$path"
    }

    private companion object {
        private const val TAG = "KMMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val STREAM_HOST_REGEX = Regex(
            """(?i)(magiclinks|skydrop|kmphotos|googleusercontent|zip[- ]?zap|""" +
                """r2\.dev|gofile|filemoon|streamtape|streamwish|vidhide|dood|mixdrop|""" +
                """hubcloud|hubdrive|gdflix|gdlink|send\.cm|1fichier|mediafire|""" +
                """pixeldrain|fuckingfast|filepress|driveseed|driveleech|bbupload|""" +
                """voe|vidoza|streamlare|filelions|drop\.download|userscloud|megaup)"""
        )

        private val URI_SAFE_HOST_REGEX = Regex(
            """(?i)(skydrop|googleusercontent|gofile|filemoon|streamtape|streamwish|""" +
                """vidhide|dood|mixdrop|hubcloud|hubdrive|gdflix|gdlink|send\.cm|""" +
                """1fichier|mediafire|pixeldrain|fuckingfast|filepress|driveseed|""" +
                """driveleech|bbupload|voe|vidoza|streamlare|filelions|userscloud|megaup)"""
        )
    }
}
