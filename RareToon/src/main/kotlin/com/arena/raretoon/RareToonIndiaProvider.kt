package com.arena.raretoon

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class RareToonIndiaProvider : MainAPI() {
    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "Rare Toon India"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "latest" to "Latest",
        "category:doraemon" to "Doraemon",
        "category:episodes" to "Doraemon Episodes",
        "category:all-movies" to "Doraemon Movies",
        "category:animes" to "Animes",
        "category:pokemon" to "Pokemon",
        "category:disney" to "Disney",
        "category:movies" to "Movies",
        "category:shin-chan-movies" to "Shin Chan Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshMainUrl()
        val posts = when {
            request.data == "latest" -> fetchPosts(page = page)
            request.data.startsWith("category:") -> {
                val slug = request.data.substringAfter("category:")
                val categoryId = getCategoryId(slug)
                fetchPosts(page = page, categories = categoryId)
            }
            else -> fetchPosts(page = page)
        }
        val results = posts.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, results, hasNext = posts.size >= POSTS_PER_PAGE)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        refreshMainUrl()
        val posts = fetchPosts(page = page, search = query)
        return posts.mapNotNull { it.toSearchResponse() }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        refreshMainUrl()
        val post = fetchPostByUrl(url) ?: throw Exception("RareToon post not found")
        val rawTitle = decodeHtml(post.title)
        val title = cleanTitle(rawTitle)
        val poster = post.posterUrl
        val plot = post.excerpt?.ifBlank { post.description }
        val pageType = classifyPost(post)
        Log.d(TAG, "RareToon page type for '$title' => $pageType")

        return when (pageType) {
            PageType.COLLECTION -> {
                val entries = collectCollectionEntries(post.content)
                val episodes = buildCollectionEpisodes(entries)
                newTvSeriesLoadResponse(title, post.url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            PageType.SEASON -> {
                val season = seasonNumber(rawTitle) ?: 1
                val mirrors = collectPlayableMirrors(post.content)
                val episodes = buildEpisodes(mirrors, season)
                newTvSeriesLoadResponse(title, post.url, guessSeriesType(rawTitle), episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            PageType.MOVIE -> {
                val mirrors = collectPlayableMirrors(post.content)
                val data = mirrors.joinToString("\n") { it.url }.ifBlank { throw Exception("No links found") }
                newMovieLoadResponse(title, post.url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = Regex("""https?://[^\s"'<>]+""").findAll(data)
            .map { it.value.trim().trim(')', ']', '.', ',', ';') }
            .map { it.removeSuffix("%0A").trim() }
            .filter { it.startsWith("http", true) }
            .distinct()
            .toList()
        if (urls.isEmpty()) return false

        var found = false
        val wrappedSubtitle: (SubtitleFile) -> Unit = {
            found = true
            subtitleCallback(it)
        }
        val wrappedCallback: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        urls.forEach { url ->
            when {
                url.contains("bysekoze.", ignoreCase = true) -> ByseKozE().getUrl(url, mainUrl, wrappedSubtitle, wrappedCallback)
                isDirectVideo(url) -> wrappedCallback.invoke(newExtractorLink(name, name, url) { this.quality = directQuality(url) })
                url.contains("mediafire.com", ignoreCase = true) -> resolveMediaFire(url)?.let { finalUrl ->
                    wrappedCallback.invoke(newExtractorLink(name, "MediaFire", finalUrl) {
                        this.quality = directQuality(finalUrl)
                        this.referer = url
                    })
                }
                url.contains("mega.nz", ignoreCase = true) -> {
                    // Unsupported as a landing page without a dedicated extractor. Skip instead of returning fake playable links.
                }
                else -> loadExtractor(url, mainUrl, wrappedSubtitle, wrappedCallback)
            }
        }
        return found
    }

    private enum class PageType { MOVIE, SEASON, COLLECTION }

    private data class RarePost(
        val id: Int,
        val url: String,
        val slug: String,
        val title: String,
        val excerpt: String?,
        val description: String?,
        val content: String,
        val posterUrl: String?
    )

    private data class Mirror(
        val url: String,
        val label: String?,
        val episode: Int?,
        val season: Int?,
        val quality: String?
    )

    private data class CollectionEntry(val title: String, val url: String)

    private suspend fun refreshMainUrl(): String {
        val active = getActiveMainUrl().trimEnd('/')
        if (active.isNotBlank() && active != mainUrl) {
            Log.d(TAG, "Active RareToon domain refreshed: $mainUrl -> $active")
            mainUrl = active
        }
        return active.ifBlank { mainUrl }
    }

    private suspend fun fetchPosts(page: Int, search: String? = null, categories: Int? = null): List<RarePost> {
        val url = buildString {
            append("$mainUrl/wp-json/wp/v2/posts?per_page=$POSTS_PER_PAGE&page=$page&_embed=1")
            if (!search.isNullOrBlank()) append("&search=${URLEncoder.encode(search.trim(), "UTF-8")}")
            if (categories != null) append("&categories=$categories")
        }
        val text = app.get(url, headers = headers, timeout = 25).text
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { idx ->
            arr.optJSONObject(idx)?.toRarePost()
        }
    }

    private suspend fun fetchPostByUrl(url: String): RarePost? {
        val normalized = normalizeContentUrl(url)
        val slug = normalized.substringAfterLast('/').ifBlank { return null }
        val text = app.get(
            "$mainUrl/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_embed=1",
            headers = headers,
            timeout = 25
        ).text
        val arr = JSONArray(text)
        if (arr.length() > 0) return arr.optJSONObject(0)?.toRarePost()

        val search = slug.replace('-', ' ')
        return fetchPosts(page = 1, search = search).firstOrNull {
            normalizeContentUrl(it.url).endsWith("/$slug")
        }
    }

    private suspend fun getCategoryId(slug: String): Int? {
        CATEGORY_CACHE[slug]?.let { return it }
        val text = app.get(
            "$mainUrl/wp-json/wp/v2/categories?slug=${URLEncoder.encode(slug, "UTF-8")}",
            headers = headers,
            timeout = 20
        ).text
        val arr = JSONArray(text)
        val id = arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        if (id != null) CATEGORY_CACHE[slug] = id
        return id
    }

    private fun JSONObject.toRarePost(): RarePost? {
        val id = optInt("id", 0).takeIf { it > 0 } ?: return null
        val url = optString("link").ifBlank { return null }
        val slug = optString("slug").ifBlank { return null }
        val title = optJSONObject("title")?.optString("rendered").orEmpty().ifBlank { slug }
        val excerpt = optJSONObject("excerpt")?.optString("rendered")?.let(::htmlToText)
        val rawContent = optJSONObject("content")?.optString("rendered").orEmpty()
        val content = sanitizeBrokenWpContent(rawContent)
        val description = extractMetaDescription(content)
        val poster = extractEmbeddedPoster(this) ?: extractFirstPoster(content)
        return RarePost(id, url, slug, title, excerpt, description, content, poster)
    }

    private fun classifyPost(post: RarePost): PageType {
        val text = htmlToText(post.content)
        val episodeMarkers = Regex("""(?i)\bEpisode\s*0*\d{1,3}\b""").findAll(text).count()
        val playableLinks = collectPlayableMirrors(post.content)
        val collectionEntries = collectCollectionEntries(post.content)
        return when {
            isCollectionTitle(post.title) || collectionEntries.size >= 8 -> PageType.COLLECTION
            post.title.contains("season", true) || post.title.contains("episodes", true) || episodeMarkers >= 2 -> PageType.SEASON
            playableLinks.isNotEmpty() -> PageType.MOVIE
            else -> PageType.MOVIE
        }
    }

    private fun RarePost.toSearchResponse(): SearchResponse? {
        if (!isValidContentUrl(url)) return null
        val rawTitle = decodeHtml(title)
        return newMovieSearchResponse(cleanTitle(rawTitle), url, guessCardType(rawTitle, url)) {
            this.posterUrl = posterUrl
            this.quality = detectQuality(rawTitle)
        }
    }

    private fun collectPlayableMirrors(html: String): List<Mirror> {
        val content = Jsoup.parseBodyFragment(html).body()
        val mirrors = mutableListOf<Mirror>()
        var currentEpisode: Int? = null
        var currentSeason: Int? = null
        var currentLabel: String? = null
        var currentQuality: String? = null

        content.select("h1,h2,h3,h4,h5,h6,p,li,div,span,strong,b,a[href],source[src],iframe[src],video source[src]").forEach { el ->
            val text = el.ownText().ifBlank { el.text() }.trim()
            seasonNumber(text)?.let { currentSeason = it }
            episodeNumber(text)?.let {
                currentEpisode = it
                currentLabel = episodeName(text, it)
            }
            detectQualityLabel(text)?.let { currentQuality = it }

            val rawUrl = when {
                el.hasAttr("href") -> el.absUrl("href").ifBlank { el.attr("href") }
                el.hasAttr("src") -> el.absUrl("src").ifBlank { el.attr("src") }
                else -> ""
            }.removeSuffix("%0A").trim()

            if (!isPlayableExternalUrl(rawUrl)) return@forEach
            val parentText = el.parent()?.text()?.take(180).orEmpty()
            val url = if (rawUrl.contains("bysekoze.", true)) {
                byseCode(rawUrl)?.let { "https://bysekoze.com/d/$it" } ?: rawUrl
            } else rawUrl
            mirrors.add(
                Mirror(
                    url = url,
                    label = currentLabel ?: parentText.ifBlank { el.text() },
                    episode = currentEpisode ?: episodeNumber(parentText),
                    season = seasonNumber(parentText).takeIf { it != null } ?: currentSeason,
                    quality = detectQualityLabel(rawUrl) ?: detectQualityLabel(parentText) ?: currentQuality
                )
            )
        }

        return mirrors.distinctBy { it.url }
    }

    private fun collectCollectionEntries(html: String): List<CollectionEntry> {
        val content = Jsoup.parseBodyFragment(html).body()
        return content.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                val title = htmlToText(a.html()).trim()
                if (!isValidContentUrl(href)) return@mapNotNull null
                if (title.length < 6) return@mapNotNull null
                if (title.contains("click here", true) || title.contains("join", true) || title.contains("telegram", true)) return@mapNotNull null
                val lower = title.lowercase()
                val seemsContent = lower.contains("season") || lower.contains("episode") || lower.contains("movie") || lower.contains("doraemon") || lower.contains("shin") || lower.contains("pokemon") || lower.contains("leveling") || lower.contains("slayer") || lower.contains("hero academia")
                if (!seemsContent) return@mapNotNull null
                CollectionEntry(cleanTitle(title), href)
            }
            .distinctBy { it.url }
    }

    private fun buildCollectionEpisodes(entries: List<CollectionEntry>): List<Episode> {
        return entries.mapIndexed { index, entry ->
            newEpisode(entry.url) {
                this.name = entry.title.ifBlank { "Item ${index + 1}" }
                this.season = 1
                this.episode = index + 1
            }
        }
    }

    private fun buildEpisodes(mirrors: List<Mirror>, fallbackSeason: Int): List<Episode> {
        if (mirrors.isEmpty()) return emptyList()
        val grouped = linkedMapOf<Pair<Int, Int>, MutableList<Mirror>>()
        var syntheticEpisode = 1
        mirrors.forEach { mirror ->
            val season = mirror.season ?: fallbackSeason
            val episode = mirror.episode ?: syntheticEpisode++
            grouped.getOrPut(season to episode) { mutableListOf() }.add(mirror)
        }

        return grouped.entries.map { (key, group) ->
            val (season, episode) = key
            val data = group.sortedWith(compareByDescending<Mirror> { qualityRank(it.quality) }.thenBy { it.url })
                .joinToString("\n") { it.url }
            val best = group.maxByOrNull { qualityRank(it.quality) }
            newEpisode(data) {
                this.name = episodeName(best?.label, episode)
                this.season = season
                this.episode = episode
            }
        }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun sanitizeBrokenWpContent(html: String): String {
        if (html.contains("<a ", true)) return html
        return buildString {
            append(html)
            Regex("""https?://(?:mega\.nz|www\.mediafire\.com|mediafire\.com)/[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value.trim() }
                .distinct()
                .forEach { url ->
                    append("<p><a href=\"")
                    append(url)
                    append("\">")
                    append(if (url.contains("mega.nz", true)) "MEGA" else "MEDIAFIRE")
                    append("</a></p>")
                }
        }
    }

    private suspend fun resolveMediaFire(url: String): String? {
        return runCatching {
            val doc = app.get(url, headers = headers, timeout = 20).document
            doc.selectFirst("#downloadButton")?.absUrl("href")?.ifBlank { null }
                ?: Regex("""https?://download\d+\.mediafire\.com/[^\"']+""", RegexOption.IGNORE_CASE)
                    .find(doc.html())?.value
        }.getOrNull()
    }

    private fun extractEmbeddedPoster(obj: JSONObject): String? {
        val embedded = obj.optJSONObject("_embedded") ?: return null
        val media = embedded.optJSONArray("wp:featuredmedia")?.optJSONObject(0) ?: return null
        val src = media.optString("source_url")
        return src.ifBlank { null }
    }

    private fun extractFirstPoster(html: String): String? {
        val doc = Jsoup.parseBodyFragment(html)
        val img = doc.selectFirst("img") ?: return null
        return listOf(
            img.absUrl("data-lazy-src"),
            img.absUrl("data-src"),
            img.absUrl("src")
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun extractMetaDescription(html: String): String? {
        val text = htmlToText(html)
        return text.takeIf { it.length > 40 }?.take(500)
    }

    private fun htmlToText(html: String): String = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ").trim()

    private fun decodeHtml(html: String): String = Jsoup.parse(html).text()

    private fun normalizeContentUrl(url: String): String {
        val clean = if (url.startsWith("http", true)) url else "$mainUrl/${url.trimStart('/')}"
        return clean.trimEnd('/').substringBefore('#').substringBefore('?')
    }

    private fun seasonNumber(text: String): Int? = Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""")
        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun episodeNumber(text: String?): Int? {
        val t = text ?: return null
        Regex("""(?i)\b(?:episode|ep)\s*[-:]?\s*0*(\d{1,3})\b""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(?i)\bS\d{1,2}\s*E\s*0*(\d{1,3})\b""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun episodeName(text: String?, ep: Int): String {
        val cleaned = text
            ?.replace(Regex("""(?i)quickwatch|download|watch online|\[|]|hindi dub|hindi|multiquality"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.length in 5..90 } ?: "Episode $ep"
    }

    private fun byseCode(url: String): String? = Regex("""(?i)bysekoze\.[a-z]+/(?:d|e|download|dwn)/([a-z0-9]+)""")
        .find(url)?.groupValues?.getOrNull(1)

    private fun isPlayableExternalUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("raretoonindia.in")) return false
        if (lower.contains("yawncollaremotion.com") || lower.contains("foreseehawancestor.com")) return false
        if (lower.contains("telegram.me") || lower.contains("t.me/")) return false
        return lower.contains("bysekoze.") ||
            lower.contains("filemoon") ||
            lower.contains("mega.nz") ||
            lower.contains("jwplayer.com") ||
            lower.contains("mp4") ||
            lower.contains("m3u8") ||
            lower.contains("download/") ||
            lower.contains("/d/")
    }

    private fun isValidContentUrl(url: String): Boolean {
        if (!url.contains("raretoonindia.in", true)) return false
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        if (path.isBlank() || path == "/") return false
        if (path.count { it == '/' } <= 1 && !path.contains('-')) return false
        val blockedPrefixes = setOf(
            "/about-us", "/contact-us", "/dmca", "/privacy-policy", "/copyright-issues",
            "/disclaimers", "/terms-and-conditions", "/wp-content", "/wp-json", "/feed", "/comments"
        )
        if (blockedPrefixes.any { path.startsWith(it) }) return false
        if (path.contains("/page/")) return false
        return true
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""(?i)\s*[-|–—]\s*Rare\s*Toons?\s*India.*$"""), "")
        .replace(Regex("""(?i)\b(download|watch online|in hindi|hindi dubbed|hd)\b"""), " ")
        .replace(Regex("""\[[^]]*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun isCollectionTitle(title: String): Boolean = Regex("""(?i)\b(all\s+doraemon\s+movies|all\s+shin\s*chan\s+movies|all\s+movies|movie\s+collection|complete\s+collection|all\s+seasons)\b""")
        .containsMatchIn(title)

    private fun guessSeriesType(title: String): TvType = when {
        title.contains("doraemon", true) ||
            title.contains("shin", true) ||
            title.contains("pokemon", true) ||
            title.contains("anime", true) -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun guessCardType(title: String, url: String): TvType = when {
        isCollectionTitle(title) -> TvType.TvSeries
        title.contains("season", true) || title.contains("episodes", true) -> guessSeriesType(title)
        url.contains("/episodes/", true) -> TvType.Anime
        else -> TvType.Movie
    }

    private fun detectQualityLabel(text: String): String? = Regex("""(?i)\b(2160p|1080p|720p|480p|360p)\b""")
        .find(text)?.groupValues?.getOrNull(1)

    private fun detectQuality(title: String) = when {
        title.contains("1080", true) -> SearchQuality.HD
        title.contains("720", true) -> SearchQuality.HD
        title.contains("480", true) -> SearchQuality.SD
        else -> null
    }

    private fun qualityRank(label: String?): Int = when {
        label.isNullOrBlank() -> 0
        label.contains("2160", true) -> 2160
        label.contains("1080", true) -> 1080
        label.contains("720", true) -> 720
        label.contains("480", true) -> 480
        label.contains("360", true) -> 360
        else -> 1
    }

    private fun directQuality(url: String): Int {
        Regex("""(?i)(\d{3,4})p""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Qualities.Unknown.value
    }

    private fun isDirectVideo(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm")
    }

    companion object {
        private const val TAG = "RareToonIndia"
        private const val DEFAULT_MAIN_URL = "https://raretoonindia.in"
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val POSTS_PER_PAGE = 20
        private val CATEGORY_CACHE = linkedMapOf<String, Int>()

        @Volatile
        private var cachedBase: String? = null
        @Volatile
        private var cachedAtMs: Long = 0L

        suspend fun getActiveMainUrl(forceRefresh: Boolean = false): String {
            val now = System.currentTimeMillis()
            if (!forceRefresh && now - cachedAtMs < CACHE_TTL_MS) {
                cachedBase?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
            }

            val candidates = mutableListOf<String>()
            runCatching {
                val json = JSONObject(app.get(DOMAINS_URL, timeout = 10).text)
                json.optString("raretoon").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                val arr = json.optJSONArray("raretoon_candidates")
                if (arr != null) {
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                }
            }
            candidates.add(DEFAULT_MAIN_URL)

            val active = candidates.mapNotNull { normalizeBaseUrl(it) }
                .distinct()
                .firstOrNull { isUsableRareToonDomain(it) }
                ?: candidates.firstOrNull()?.let { normalizeBaseUrl(it) }
                ?: DEFAULT_MAIN_URL
            cachedBase = active.trimEnd('/')
            cachedAtMs = now
            return cachedBase!!
        }

        private fun normalizeBaseUrl(url: String?): String? {
            val u = url?.trim()?.trimEnd('/') ?: return null
            if (u.isBlank()) return null
            val full = when {
                u.startsWith("https://", true) -> u
                u.startsWith("http://", true) -> u.replaceFirst("http://", "https://")
                else -> "https://$u"
            }
            return runCatching {
                val uri = URI(full)
                "${uri.scheme}://${uri.host}"
            }.getOrNull()
        }

        private suspend fun isUsableRareToonDomain(baseUrl: String): Boolean = try {
            val res = app.get(baseUrl.trimEnd('/') + "/", timeout = 8)
            if (res.code == 404) return false
            val lower = res.text.take(100_000).lowercase()
            lower.contains("rare toon") || lower.contains("raretoon") ||
                lower.contains("raretoons") || lower.contains("wp-content") ||
                lower.contains("elementor")
        } catch (_: Throwable) {
            false
        }
    }
}
