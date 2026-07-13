package com.lagradost

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * KMMovies provider rebuilt around the site's current WordPress, MagicLinks and
 * SkyDrop flows. Media is not hosted by this extension.
 */
class KMMovies : MainAPI() {
    override var name = "KMMovies"
    override var mainUrl = "https://kmmovies.lol"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "Recently Added",
        "/category/movies/" to "Movies",
        "/category/tv-series/" to "TV Series",
        "/category/bollywood/" to "Bollywood",
        "/category/hollywood/" to "Hollywood",
        "/category/south/" to "South Indian",
        "/category/4k/" to "4K",
        "/category/dual-audio/" to "Dual Audio",
        "/category/anime/" to "Anime"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private data class Source(val name: String, val url: String, val referer: String = "")
    private data class EpisodeSource(val season: Int, val episode: Int, val source: Source)

    // -----------------------------------------------------------------------
    // HTTP and URL helpers
    // -----------------------------------------------------------------------

    private suspend fun document(url: String, referer: String? = null): Document {
        val requestHeaders = browserHeaders + referer.orEmpty().takeIf { it.isNotBlank() }
            ?.let { mapOf("Referer" to it) }.orEmpty()
        try {
            val response = app.get(url, headers = requestHeaders, timeout = 25)
            val parsedDoc = Jsoup.parse(response.text, response.url)
            if (!isChallenge(parsedDoc)) return parsedDoc
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Direct request failed for ${safeUrl(url)}: ${error.message}")
        }
        val response = app.get(
            url,
            headers = requestHeaders,
            interceptor = CloudflareKiller(),
            timeout = 30
        )
        return Jsoup.parse(response.text, response.url)
    }

    private fun isChallenge(doc: Document): Boolean {
        val sample = (doc.title() + " " + doc.html().take(15_000)).lowercase()
        return sample.contains("just a moment") || sample.contains("cf-chl") ||
            sample.contains("challenge-running") || sample.contains("verify you are human")
    }

    private fun absolute(doc: Document, raw: String): String {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) return ""
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http", true)) return value.replace(" ", "%20")
        val base = doc.baseUri().takeIf { it.isNotBlank() } ?: doc.location().takeIf { it.isNotBlank() } ?: mainUrl
        return runCatching { URI(base).resolve(value).toString() }
            .getOrElse { base.trimEnd('/') + "/" + value.trimStart('/') }
    }

    private fun providerUrl(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("http", true)) return value
        return mainUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun paged(path: String, page: Int): String {
        val normalized = "/" + path.trim('/')
        return if (page <= 1) {
            mainUrl + if (normalized == "/") "/" else "$normalized/"
        } else if (normalized == "/") {
            "$mainUrl/page/$page/"
        } else {
            "$mainUrl$normalized/page/$page/"
        }
    }

    // -----------------------------------------------------------------------
    // Home and search
    // -----------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = parseCards(document(paged(request.data, page)))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseCards(document("$mainUrl/?s=$encoded"))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        return parseCards(document(url)).toNewSearchResponseList()
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val cards = doc.select("article.movie-card").ifEmpty {
            doc.select(".movie-card, article:has(.movie-title)")
        }
        return cards.mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val url = absolute(doc, anchor.attr("href"))
            val rawTitle = card.selectFirst(".movie-title")?.text()?.trim()
                ?.ifBlank { anchor.attr("aria-label").trim() }
                ?: anchor.attr("aria-label").trim()
            if (url.isBlank() || rawTitle.isBlank()) return@mapNotNull null
            val typeText = card.text().lowercase()
            val type = if (typeText.contains("series") || isSeriesTitle(rawTitle)) {
                TvType.TvSeries
            } else TvType.Movie
            val poster = card.selectFirst("img")?.let {
                absolute(doc, it.attr("data-src").ifBlank { it.attr("src") })
            }
            newMovieSearchResponse(cleanTitle(rawTitle), url, type) {
                posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // -----------------------------------------------------------------------
    // Detail loading
    // -----------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val detailUrl = providerUrl(url)
        val doc = document(detailUrl)
        val rawTitle = doc.selectFirst("h1.hero-title, h1.entry-title, h1")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .orEmpty().ifBlank { doc.title().substringBefore(" - KMMOVIES") }
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("img.hero-poster, meta[property=og:image]")?.let {
            if (it.tagName() == "meta") it.attr("content") else absolute(doc, it.attr("src"))
        }
        val plot = doc.selectFirst(".hero-description")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[name=description]")?.attr("content").orEmpty() }
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select(".about-meta-box").firstOrNull {
            it.selectFirst(".about-meta-label")?.text()?.equals("Genres", true) == true
        }?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }.orEmpty()
        val actors = doc.select(".about-cast-chip").map { it.text().trim() }.filter { it.isNotBlank() }

        suspend fun LoadResponse.common() {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            if (actors.isNotEmpty()) addActors(actors.map { Actor(it, null) })
        }

        val isSeries = doc.select(".season-block").isNotEmpty() || isSeriesTitle(rawTitle) ||
            doc.select(".about-highlight-value").any { it.text().equals("Series", true) }

        if (isSeries) {
            val episodes = loadSeriesEpisodes(doc, detailUrl)
            return newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) { common() }
        }

        val sources = discoverDetailSources(doc, detailUrl)
        val payload = encodePayload(listOf(Source("Detail", detailUrl, detailUrl)) + sources)
        return newMovieLoadResponse(title, detailUrl, TvType.Movie, payload) { common() }
    }

    private fun discoverDetailSources(doc: Document, referer: String): List<Source> {
        val precise = doc.select(".downloads-section a.dl-btn, .season-block a.dl-btn")
        val anchors = if (precise.isNotEmpty()) precise else doc.select("a[href]")
        return anchors.mapNotNull { anchor ->
            val url = absolute(doc, anchor.attr("href"))
            if (!isResolvable(url)) return@mapNotNull null
            val qualitySpan = anchor.selectFirst(".dl-quality")?.text()?.trim()
            val resSpan = anchor.selectFirst(".dl-res")?.text()?.trim()
            val labelName = qualitySpan ?: resSpan ?: anchor.text().normalise().ifBlank { "Source" }
            Source(labelName, url, referer)
        }.distinctBy { it.url }
    }

    private suspend fun loadSeriesEpisodes(doc: Document, detailUrl: String): List<Episode> {
        val jobs = doc.select(".season-block").flatMap { block ->
            val seasonText = block.selectFirst(".season-block-title")?.text().orEmpty()
            val season = Regex("""(?i)season\s*(\d+)""").find(seasonText)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val buttons = block.select(".type-content[data-type^=episodes] a[href]").ifEmpty {
                block.select(".type-content.active a[href]")
            }
            buttons.mapNotNull { button ->
                val landing = absolute(doc, button.attr("href"))
                landing.takeIf { it.isNotBlank() }?.let {
                    Triple(season, button.text().normalise().ifBlank { "Source" }, it)
                }
            }
        }

        if (jobs.isEmpty()) return emptyList()

        val found = supervisorScope {
            jobs.map { (season, label, landing) ->
                async {
                    try {
                        parseEpisodeLanding(season, label, landing, detailUrl)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Episode landing failed ${safeUrl(landing)}: ${error.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        return found.groupBy { it.season to it.episode }
            .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
            .map { (key, rows) ->
                val sources = rows.map { it.source }.distinctBy { it.url }
                newEpisode(encodePayload(sources)) {
                    name = "Episode ${key.second}"
                    season = key.first
                    episode = key.second
                }
            }
    }

    private suspend fun parseEpisodeLanding(
        season: Int,
        label: String,
        landing: String,
        detailUrl: String
    ): List<EpisodeSource> {
        val doc = document(landing, detailUrl)
        val rows = doc.select(".ep-row")
        if (rows.isNotEmpty()) {
            return rows.mapIndexedNotNull { index, row ->
                val text = row.selectFirst(".ep-name")?.text().orEmpty()
                val episode = Regex("""(?i)(?:episode|ep)\s*[-#:]?\s*(\d+)""")
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: index + 1
                val anchor = row.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val url = absolute(doc, anchor.attr("href"))
                url.takeIf { isResolvable(it) }?.let {
                    EpisodeSource(season, episode, Source(label, it, landing))
                }
            }
        }

        return doc.select("a[href]").mapIndexedNotNull { index, anchor ->
            val url = absolute(doc, anchor.attr("href"))
            url.takeIf { isResolvable(it) }?.let {
                EpisodeSource(season, index + 1, Source(label, it, landing))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Stable versioned payload
    // -----------------------------------------------------------------------

    private fun encodePayload(sources: List<Source>): String {
        val array = JSONArray()
        sources.distinctBy { it.url }.forEach { source ->
            array.put(JSONObject().apply {
                put("name", source.name)
                put("url", source.url)
                put("referer", source.referer)
            })
        }
        return JSONObject().apply {
            put("version", PAYLOAD_VERSION)
            put("sources", array)
        }.toString()
    }

    private fun decodePayload(data: String): List<Source> {
        runCatching {
            val root = JSONObject(data)
            val array = root.optJSONArray("sources") ?: JSONArray()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                url.takeIf { it.startsWith("http", true) }?.let {
                    Source(item.optString("name").ifBlank { "Source" }, url, item.optString("referer"))
                }
            }.distinctBy { it.url }
        }

        return Regex("""https?://[^\s"'<>\\]+""").findAll(data.replace("\\/", "/"))
            .map { Source("Source", it.value.trimEnd(',', '.', ';', ')', ']')) }
            .distinctBy { it.url }.toList()
    }

    // -----------------------------------------------------------------------
    // Playback resolution
    // -----------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val initial = decodePayload(data)
        if (initial.isEmpty()) return false

        var emitted = 0
        val actualCallback: (ExtractorLink) -> Unit = { link ->
            emitted += 1
            callback(link)
        }

        val queue = ArrayDeque<Source>()
        queue.addAll(initial)
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty() && visited.size < MAX_RESOLUTION_NODES) {
            val source = queue.removeFirst()
            if (!visited.add(source.url)) continue

            try {
                when {
                    isProviderPage(source.url) -> {
                        val doc = document(source.url)
                        queue.addAll(discoverDetailSources(doc, source.url))
                    }
                    isMagicLinks(source.url) -> {
                        queue.addAll(resolveMagicLinks(source))
                    }
                    urlContainsExtractor(source.url) -> {
                        dispatchExtractor(source, actualCallback)
                    }
                    isDirect(source.url) -> emit(source, actualCallback)
                    else -> loadExtractor(
                        source.url,
                        source.referer.ifBlank { mainUrl },
                        subtitleCallback,
                        actualCallback
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Source failed ${safeUrl(source.url)}: ${error.message}")
            } catch (error: LinkageError) {
                Log.e(TAG, "CloudStream ABI rejected ${safeUrl(source.url)}: ${error.message}")
            }
        }

        return emitted > 0
    }

    private suspend fun resolveMagicLinks(source: Source): List<Source> {
        val url = source.url
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val slug = uri.path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return emptyList()
        val hosts = linkedSetOf(uri.host.orEmpty(), "magiclinks.lol", "w1.magiclinks.lol")
            .filter { it.isNotBlank() }

        for (host in hosts) {
            val endpoint = "https://$host/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}" +
                "&_fields=content,meta,link"
            val text = try {
                app.get(
                    endpoint,
                    headers = browserHeaders + mapOf("Accept" to "application/json", "Referer" to url),
                    timeout = 25
                ).text
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                continue
            }
            val post = runCatching { JSONArray(text).optJSONObject(0) }.getOrNull() ?: continue
            val base = post.optString("link").ifBlank { "https://$host/$slug/" }
            val output = linkedMapOf<String, Source>()

            fun add(label: String, raw: String?) {
                val value = raw.orEmpty().trim().replace("&amp;", "&")
                if (value.isBlank() || value == "#" || value.startsWith("javascript:", true)) return
                val absolute = if (value.startsWith("http", true)) value else
                    runCatching { URI(base).resolve(value).toString() }.getOrDefault("")
                if (absolute.isNotBlank() && !absolute.contains("/photo/", true)) {
                    val cleanLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                        source.name + " • " + label
                    } else label
                    output[absolute] = Source(cleanLabel, absolute, base)
                }
            }

            val meta = post.optJSONObject("meta")
            MAGIC_META.forEach { (key, label) ->
                add(label, meta?.optString(key).orEmpty().ifBlank { post.optString(key) })
            }
            val rendered = post.optJSONObject("content")?.optString("rendered").orEmpty()
            if (rendered.isNotBlank()) {
                val renderedDoc = Jsoup.parse(rendered, base)
                renderedDoc.select("a[href]").forEach {
                    val urlText = it.text().normalise().ifBlank { "Source" }
                    val label = when {
                        it.attr("href").contains("online.php") -> "Watch Online"
                        it.attr("href").contains("download99.php") -> "Zip-Zap"
                        it.attr("href").contains("skydrop") -> "SkyDrop"
                        it.attr("href").contains("pixeldrain") -> "PixelDrain"
                        it.attr("href").contains("gofile") -> "GoFile"
                        else -> urlText
                    }
                    add(label, it.absUrl("href").ifBlank { it.attr("href") })
                }
                URL_REGEX.findAll(rendered.replace("\\/", "/")).forEach { 
                    val linkStr = it.value
                    val label = when {
                        linkStr.contains("online.php") -> "Watch Online"
                        linkStr.contains("download99.php") -> "Zip-Zap"
                        linkStr.contains("skydrop") -> "SkyDrop"
                        linkStr.contains("pixeldrain") -> "PixelDrain"
                        linkStr.contains("gofile") -> "GoFile"
                        else -> "Source"
                    }
                    add(label, linkStr) 
                }
            }
            if (output.isNotEmpty()) return output.values.toList()
        }
        return emptyList()
    }

    private suspend fun resolveBuzzheavier(source: Source, callback: (ExtractorLink) -> Unit): Boolean {
        val url = source.url
        return try {
            Log.d(TAG, "Resolving Buzzheavier URL: $url")
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val path = uri.path.orEmpty()
            val id = path.substringAfterLast("/").trim()
            if (id.isBlank()) return false
            
            val directStreamUrl = "https://dd.buzzheavier.com/f/$id"
            val combinedLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                "${source.name} • Buzzheavier Direct"
            } else "Buzzheavier Direct"
            emitDirect(directStreamUrl, combinedLabel, url, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in resolveBuzzheavier: ${e.message}")
            false
        }
    }

    private suspend fun resolveWatchOnline(source: Source, callback: (ExtractorLink) -> Unit): Boolean {
        val url = source.url
        return try {
            Log.d(TAG, "Resolving WATCH ONLINE URL: $url")
            val final = followRedirects(url, "")
            
            // Check for direct videoUrl in query param
            val encoded = Regex("""(?i)[?&]videoUrl=([^&]+)""").find(final)
                ?.groupValues?.getOrNull(1)
            if (!encoded.isNullOrBlank()) {
                val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                if (decoded.startsWith("http", true)) {
                    val combinedLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                        "${source.name} • Watch Online"
                    } else "Watch Online Stream"
                    emitDirect(decoded, combinedLabel, final, callback)
                    return true
                }
            }

            // Scrape webpage if no query param
            val doc = runCatching { document(final, url) }.getOrNull() ?: return false
            val video = doc.selectFirst("video[src], video source[src]")?.attr("src").orEmpty()
            if (video.isNotBlank()) {
                val absVideoUrl = absolute(doc, video)
                val combinedLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                    "${source.name} • Watch Online"
                } else "Watch Online Stream"
                emitDirect(absVideoUrl, combinedLabel, final, callback)
                return true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error in resolveWatchOnline: ${e.message}")
            false
        }
    }

    private suspend fun resolveKmphotos(source: Source, callback: (ExtractorLink) -> Unit): Boolean {
        val url = source.url
        Log.d(TAG, "resolveKmphotos() called with url: $url")
        return try {
            Log.d(TAG, "Resolving KMPhotos URL: $url")
            
            // If already a direct file stream, emit directly.
            if (isDirect(url)) {
                emitDirect(url, "KMPhotos R2 (${source.name})", url, callback)
                return true
            }

            // Fetch the landing page directly (no pre-redirect resolution needed!)
            val doc = runCatching { document(url, null) }.getOrNull() ?: return false
            var foundLink = false

            val anchors = doc.select("a[href]")
            for (anchor in anchors) {
                val href = anchor.attr("href")
                val absoluteHref = anchor.absUrl("href").ifBlank { absolute(doc, href) }

                // Only resolve links that belong to the download process (dl=r2 or dl=worker)
                if (absoluteHref.contains("dl=r2") || absoluteHref.contains("dl=worker")) {
                    Log.d(TAG, "Following redirect for download link: $absoluteHref")
                    val finalDirectUrl = followRedirects(absoluteHref, "")
                    
                    Log.d(TAG, "R2/Worker candidate href: $absoluteHref")
                    Log.d(TAG, "R2/Worker final redirect resolved to: $finalDirectUrl")
                    Log.d(TAG, "isDirect() result for this URL: ${isDirect(finalDirectUrl)}")
                    
                    if (isDirect(finalDirectUrl)) {
                        val label = if (absoluteHref.contains("dl=r2")) "KMPhotos Fast (R2)" else "KMPhotos Direct (Worker)"
                        val combinedLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                            "${source.name} • $label"
                        } else label
                        callback(
                            newExtractorLink(
                                source = "KMMovies",
                                name = "KMMovies • $combinedLabel",
                                url = finalDirectUrl
                            ) {
                                this.referer = absoluteHref
                                this.quality = this@KMMovies.quality(combinedLabel + " " + finalDirectUrl)
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundLink = true
                    }
                }
            }
            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "Error in resolveKmphotos: ${e.message}")
            false
        }
    }

    private suspend fun resolveSkydropApi(source: Source, callback: (ExtractorLink) -> Unit): Boolean {
        val url = source.url
        return try {
            Log.d(TAG, "Resolving SkyDrop URL: $url")
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val token = queryParam(uri.rawQuery.orEmpty(), "id")
                ?: queryParam(uri.rawQuery.orEmpty(), "file") ?: return false
            val origin = "${uri.scheme ?: "https"}://${uri.host}"
            val encoded = URLEncoder.encode(token, "UTF-8")

            // Current short Google resource tokens are directly streamable.
            if (token.length <= 64) {
                val directUrl = "$origin/api.php?file=$encoded&download=1"
                callback(
                    newExtractorLink(
                        source = "KMMovies",
                        name = "KMMovies • SkyDrop Direct (${source.name})",
                        url = directUrl
                    ) {
                        this.referer = url
                        this.quality = this@KMMovies.quality(source.name + " " + directUrl)
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                return true
            }

            val apiHeaders = browserHeaders + mapOf(
                "Accept" to "application/json",
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            )

            var foundLink = false
            for (parameter in listOf("file", "id")) {
                val text = runCatching {
                    app.get("$origin/api.php?$parameter=$encoded", headers = apiHeaders, timeout = 20).text
                }.getOrNull() ?: continue
                
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (!obj.optBoolean("success", false)) continue
                
                val proxyLink = obj.optString("download_url").trim()
                    .ifBlank { obj.optString("url").trim() }

                if (proxyLink.startsWith("http", true)) {
                    val finalProxyUrl = followRedirects(proxyLink, "")
                    if (isDirect(finalProxyUrl)) {
                        val combinedLabel = if (source.name.isNotBlank() && !source.name.equals("Source", true)) {
                            "${source.name} • SkyDrop Proxy"
                        } else "SkyDrop Proxy"
                        callback(
                            newExtractorLink(
                                source = "KMMovies",
                                name = "KMMovies • $combinedLabel",
                                url = finalProxyUrl
                            ) {
                                this.referer = url
                                this.quality = this@KMMovies.quality(combinedLabel + " " + finalProxyUrl)
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundLink = true
                    }
                }
                
                if (foundLink) break
            }
            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "Error in resolveSkydropApi: ${e.message}")
            false
        }
    }

    private suspend fun dispatchExtractor(
        source: Source, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = source.url
        Log.d(TAG, "dispatchExtractor() called with url: $url")
        return try {
            when {
                url.contains("online.php", ignoreCase = true) || url.contains("/nf/index.php", ignoreCase = true) -> {
                    resolveWatchOnline(source, callback)
                }
                url.contains("kmphotos", ignoreCase = true) || url.contains("download99.php", ignoreCase = true) -> {
                    resolveKmphotos(source, callback)
                }
                url.contains("skydrop", ignoreCase = true) || url.contains("download.php", ignoreCase = true) -> {
                    resolveSkydropApi(source, callback)
                }
                url.contains("buzzheavier", ignoreCase = true) -> {
                    resolveBuzzheavier(source, callback)
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error resolving extractor link $url: ${e.message}")
            false
        }
    }

    private fun urlContainsExtractor(url: String): Boolean {
        return url.contains("kmphotos", ignoreCase = true) || 
            url.contains("download99.php", ignoreCase = true) ||
            url.contains("skydrop", ignoreCase = true) ||
            url.contains("download.php", ignoreCase = true) ||
            url.contains("online.php", ignoreCase = true) ||
            url.contains("/nf/index.php", ignoreCase = true) ||
            url.contains("buzzheavier", ignoreCase = true)
    }

    private suspend fun followRedirects(start: String, referer: String): String {
        var current = start
        repeat(8) {
            if (isDirect(current)) return current
            val response = app.get(
                current,
                headers = browserHeaders + referer.takeIf { it.isNotBlank() }
                    ?.let { mapOf("Referer" to it) }.orEmpty(),
                allowRedirects = false,
                timeout = 15
            )
            if (response.code !in 300..399) return current
            val location = response.headers["Location"] ?: response.headers["location"] ?: return current
            current = runCatching { URI(current).resolve(location).toString() }.getOrDefault(location)
        }
        return current
    }

    private suspend fun emit(source: Source, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                source = "KMMovies",
                name = "KMMovies • ${source.name.ifBlank { "Direct" }}",
                url = source.url
            ) {
                this.referer = source.referer
                this.quality = this@KMMovies.quality(source.name + " " + source.url)
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    private suspend fun emitDirect(url: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                source = "KMMovies",
                name = "KMMovies • $label",
                url = url
            ) {
                this.referer = referer
                this.quality = this@KMMovies.quality(label + " " + url)
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    // -----------------------------------------------------------------------
    // Predicates and text helpers
    // -----------------------------------------------------------------------

    private fun isProviderPage(url: String): Boolean = host(url) == host(mainUrl) || host(url) == "kmmovies.shop"
    private fun isMagicLinks(url: String): Boolean = host(url).contains("magiclinks") &&
        !host(url).startsWith("episodes.")

    private fun isResolvable(url: String): Boolean {
        return isMagicLinks(url) || urlContainsExtractor(url) || isDirect(url) ||
            url.contains("online.php", true) || url.contains("download99.php", true) ||
            url.contains("drive.google.com", true) || url.contains("gofile", true) ||
            url.contains("pixeldrain", true) || url.contains("hubcloud", true)
    }

    private fun isDirect(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
            clean.endsWith(".m3u8") || clean.endsWith(".mov") || clean.endsWith(".avi") ||
            url.contains("r2.dev", true) || url.contains("r2.cloudflarestorage.com", true) ||
            url.contains("googleusercontent.com", true) || url.contains("workers.dev", true) ||
            (url.contains("skydrop", true) && url.contains("api.php", true) && url.contains("download=1", true))
    }

    private fun queryParam(query: String, key: String): String? {
        return query.split('&').firstOrNull { it.substringBefore('=').equals(key, true) }
            ?.substringAfter('=', "")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun host(url: String): String = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    private fun quality(value: String): Int {
        val text = value.lowercase()
        return when {
            "2160" in text || "4k" in text -> Qualities.P2160.value
            "1440" in text || "2k" in text -> Qualities.P1440.value
            "1080" in text -> Qualities.P1080.value
            "720" in text -> Qualities.P720.value
            "480" in text -> Qualities.P480.value
            "360" in text -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("""(?i)\s*-\s*KMMOVIES.*$"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim(' ', '-', '|', ':')
        .ifBlank { value.trim() }

    private fun isSeriesTitle(value: String): Boolean =
        Regex("""(?i)\b(?:season\s*\d+|s\d{1,2}(?:\s*[-–]\s*s?\d{1,2})?|\d+\s*episodes?)\b""")
            .containsMatchIn(value) || value.contains("series", true)

    private fun String.normalise(): String = replace(Regex("\\s+"), " ").trim()

    private fun safeUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "<url>"
        return uri.host.orEmpty() + uri.path.orEmpty()
    }

    private companion object {
        private const val TAG = "KMMovies"
        private const val PAYLOAD_VERSION = 1
        private const val MAX_RESOLUTION_NODES = 40
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val URL_REGEX = Regex("""https?://[^\s"'<>\\]+""")
        private val MAGIC_META = listOf(
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
        )
    }
}