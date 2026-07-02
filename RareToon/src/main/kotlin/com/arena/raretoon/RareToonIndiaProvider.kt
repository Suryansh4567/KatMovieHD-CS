package com.arena.raretoon

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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

/**
 * Rare Toon India provider for CloudStream.
 *
 * The site is a WordPress blog (raretoonindia.in) that publishes Hindi dubbed cartoons,
 * anime, movies and series. Videos are hosted on bysekoze.com via encrypted HLS streams.
 *
 * This provider uses the public WordPress REST API for posts/categories and scrapes the
 * post content for bysekoze links, then delegates playback to the ByseKozE extractor.
 */
class RareToonIndiaProvider : MainAPI() {
    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "Rare Toon India"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    /** Request headers mimicking a real mobile browser. */
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
        "Referer" to "$DEFAULT_MAIN_URL/"
    )

    /** API headers for JSON endpoints. */
    private val apiHeaders = headers + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    /**
     * Home page sections. Each entry maps to either the "latest" posts feed or a
     * WordPress category slug. The slugs below match the current site structure.
     */
    override val mainPage = mainPageOf(
        "latest" to "Latest",
        "category:doraemon" to "Doraemon",
        "category:episodes" to "Doraemon Episodes",
        "category:all-movies" to "All Movies",
        "category:animes" to "Animes",
        "category:pokemon" to "Pokemon",
        "category:disney" to "Disney",
        "category:movies" to "Movies",
        "category:shin-chan-movies" to "Shin Chan Movies",
        "category:demon-slayer" to "Demon Slayer",
        "category:attack-on-titan" to "Attack on Titan",
        "category:my-hero-academia" to "My Hero Academia",
        "category:solo-leveling" to "Solo Leveling",
        "category:jujutsu-kaisen" to "Jujutsu Kaisen",
        "category:naruto" to "Naruto",
        "category:naruto-shippuden" to "Naruto Shippuden"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshMainUrl()
        val posts = try {
            when {
                request.data == "latest" -> fetchPosts(page = page)
                request.data.startsWith("category:") -> {
                    val slug = request.data.substringAfter("category:")
                    val categoryId = getCategoryId(slug)
                        ?: return newHomePageResponse(request.name, emptyList())
                    fetchPosts(page = page, categories = categoryId)
                }
                else -> fetchPosts(page = page)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed for ${request.name}: ${e.message}")
            emptyList()
        }
        val results = posts.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, results, hasNext = results.size >= POSTS_PER_PAGE)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        refreshMainUrl()
        val rawPosts = try {
            fetchPosts(page = page, search = query)
        } catch (e: Exception) {
            Log.e(TAG, "search failed for '$query': ${e.message}")
            emptyList()
        }

        // WordPress search matches inside post content as well, which often returns
        // unrelated posts that merely link to the queried term. Filter to posts whose
        // title contains at least one significant query word.
        val queryTokens = query.tokenizeQuery()
        val filtered = if (queryTokens.isEmpty()) {
            rawPosts
        } else {
            rawPosts.filter { post ->
                val title = decodeHtml(post.title).lowercase()
                queryTokens.any { title.contains(it) }
            }
        }

        return filtered.mapNotNull { it.toSearchResponse() }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        refreshMainUrl()
        val post = fetchPostByUrl(url)
            ?: throw ErrorLoadingException("RareToon post not found: $url")

        val rawTitle = decodeHtml(post.title)
        val title = cleanTitle(rawTitle)
        val poster = post.posterUrl
        val plot = buildPlot(post)
        val pageType = classifyPost(post)
        val year = extractYear(rawTitle) ?: extractYear(post.content)
        val tags = extractTags(post.content) ?: emptyList()
        val recommendations = extractRecommendations(post.content)

        Log.d(TAG, "load() '$title' ($pageType) @ ${post.url}")

        return when (pageType) {
            PageType.COLLECTION -> {
                val entries = collectCollectionEntries(post.content)
                if (entries.isEmpty()) throw ErrorLoadingException("No collection entries found")
                val episodes = buildCollectionEpisodes(entries)
                newTvSeriesLoadResponse(title, post.url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
            PageType.SEASON -> {
                val fallbackSeason = seasonNumber(rawTitle) ?: 1
                val mirrors = collectPlayableMirrors(post.content, fallbackSeason)
                if (mirrors.isEmpty()) throw ErrorLoadingException("No video links found")
                val episodes = buildEpisodes(mirrors, fallbackSeason)
                val type = guessSeriesType(rawTitle)
                newTvSeriesLoadResponse(title, post.url, type, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
            PageType.MOVIE -> {
                val mirrors = collectPlayableMirrors(post.content, season = null)
                if (mirrors.isEmpty()) throw ErrorLoadingException("No video links found")
                // Deduplicate by code so QuickWatch and Download variants of the same
                // quality don't produce identical streams.
                val data = mirrors.deduplicateByCode()
                    .joinToString("\n") { it.url }
                    .ifBlank { throw ErrorLoadingException("No links found") }
                newMovieLoadResponse(title, post.url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
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
        val urls = Regex("""https?://[^\s"'<>]+""")
            .findAll(data)
            .map { it.value.trim().trimEnd(')', ']', '.', ',', ';', '"', '\'') }
            .map { it.removeSuffix("%0A").trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
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
            try {
                when {
                    url.contains("bysekoze.", ignoreCase = true) -> {
                        ByseKozE().getUrl(url, mainUrl, wrappedSubtitle, wrappedCallback)
                    }
                    isDirectVideo(url) -> {
                        wrappedCallback.invoke(
                            newExtractorLink(name, name, url) {
                                this.quality = directQuality(url)
                                this.referer = mainUrl
                            }
                        )
                    }
                    url.contains("mediafire.com", ignoreCase = true) -> {
                        resolveMediaFire(url)?.let { finalUrl ->
                            wrappedCallback.invoke(
                                newExtractorLink(name, "MediaFire", finalUrl) {
                                    this.quality = directQuality(finalUrl)
                                    this.referer = url
                                }
                            )
                        }
                    }
                    url.contains("mega.nz", ignoreCase = true) -> {
                        // MEGA requires a dedicated extractor; skip to avoid fake playable links.
                        Log.d(TAG, "Skipping MEGA link: $url")
                    }
                    else -> loadExtractor(url, mainUrl, wrappedSubtitle, wrappedCallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract link $url: ${e.message}")
            }
        }
        return found
    }

    // -------------------------------------------------------------------------
    // Data classes & enums
    // -------------------------------------------------------------------------

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
        val quality: String?,
        val code: String?
    )

    private data class CollectionEntry(val title: String, val url: String)

    // -------------------------------------------------------------------------
    // Network helpers
    // -------------------------------------------------------------------------

    private suspend fun refreshMainUrl(): String {
        val active = try {
            getActiveMainUrl().trimEnd('/')
        } catch (e: Exception) {
            Log.e(TAG, "refreshMainUrl failed: ${e.message}")
            mainUrl.trimEnd('/')
        }
        if (active.isNotBlank() && active != mainUrl) {
            Log.d(TAG, "Active domain refreshed: $mainUrl -> $active")
            mainUrl = active
        }
        return active.ifBlank { mainUrl }
    }

    private suspend fun fetchPosts(
        page: Int,
        search: String? = null,
        categories: Int? = null
    ): List<RarePost> = try {
        val url = buildString {
            append("$mainUrl/wp-json/wp/v2/posts?per_page=$POSTS_PER_PAGE&page=$page&_embed=1")
            if (!search.isNullOrBlank()) {
                append("&search=${URLEncoder.encode(search.trim(), "UTF-8")}")
            }
            if (categories != null) append("&categories=$categories")
        }
        val text = app.get(url, headers = apiHeaders, timeout = 25).text
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { idx ->
            arr.optJSONObject(idx)?.toRarePost()
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchPosts failed: ${e.message}")
        emptyList()
    }

    private suspend fun fetchPostByUrl(url: String): RarePost? {
        val normalized = normalizeContentUrl(url)
        val slug = normalized.substringAfterLast('/').ifBlank { return null }

        // Try slug lookup first (fastest and most reliable).
        val bySlug = try {
            val text = app.get(
                "$mainUrl/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_embed=1",
                headers = apiHeaders,
                timeout = 25
            ).text
            JSONArray(text).optJSONObject(0)?.toRarePost()
        } catch (e: Exception) {
            Log.e(TAG, "fetchPostByUrl slug lookup failed: ${e.message}")
            null
        }
        if (bySlug != null) return bySlug

        // Fallback to a search against the slug-derived title.
        return try {
            val search = slug.replace('-', ' ')
            fetchPosts(page = 1, search = search).firstOrNull {
                normalizeContentUrl(it.url).endsWith("/$slug")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPostByUrl search fallback failed: ${e.message}")
            null
        }
    }

    private suspend fun getCategoryId(slug: String): Int? {
        CATEGORY_CACHE[slug]?.let { return it }
        return try {
            val text = app.get(
                "$mainUrl/wp-json/wp/v2/categories?slug=${URLEncoder.encode(slug, "UTF-8")}",
                headers = apiHeaders,
                timeout = 20
            ).text
            val arr = JSONArray(text)
            val id = arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
            if (id != null) CATEGORY_CACHE[slug] = id
            id
        } catch (e: Exception) {
            Log.e(TAG, "getCategoryId failed for '$slug': ${e.message}")
            null
        }
    }

    private fun JSONObject.toRarePost(): RarePost? {
        val id = optInt("id", 0).takeIf { it > 0 } ?: return null
        val url = optString("link").ifBlank { return null }
        val slug = optString("slug").ifBlank { return null }
        val title = optJSONObject("title")?.optString("rendered").orEmpty().ifBlank { slug }
        val excerpt = optJSONObject("excerpt")?.optString("rendered")?.let(::htmlToText)?.trim()
        val rawContent = optJSONObject("content")?.optString("rendered").orEmpty()
        val content = sanitizeBrokenWpContent(rawContent)
        val description = extractMetaDescription(content)
        val poster = extractEmbeddedPoster(this) ?: extractFirstPoster(content)
        return RarePost(id, url, slug, title, excerpt, description, content, poster)
    }

    // -------------------------------------------------------------------------
    // Classification & metadata extraction
    // -------------------------------------------------------------------------

    private fun classifyPost(post: RarePost): PageType {
        val text = htmlToText(post.content)
        val title = post.title.lowercase()
        val episodeMarkers = Regex("""(?i)\bEpisode\s*0*\d{1,3}\b""").findAll(text).count()
        val mirrors = collectPlayableMirrors(post.content, season = null)
        val collectionEntries = collectCollectionEntries(post.content)

        return when {
            isCollectionTitle(post.title) -> PageType.COLLECTION
            collectionEntries.size >= 6 -> PageType.COLLECTION
            title.contains("season") || title.contains("episodes") || episodeMarkers >= 2 -> PageType.SEASON
            mirrors.isNotEmpty() -> PageType.MOVIE
            else -> PageType.MOVIE
        }
    }

    private fun RarePost.toSearchResponse(): SearchResponse? {
        if (!isValidContentUrl(url)) return null
        val rawTitle = decodeHtml(title)
        val clean = cleanTitle(rawTitle)
        val type = guessCardType(rawTitle, url)
        val poster = posterUrl
        val quality = detectQuality(rawTitle)

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(clean, url, TvType.Anime) {
                this.posterUrl = poster
                this.quality = quality
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(clean, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
            else -> newMovieSearchResponse(clean, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    private fun buildPlot(post: RarePost): String? {
        val fromExcerpt = post.excerpt?.takeIf { it.length > 20 }
        val fromContent = post.description?.takeIf { it.length > 40 }
        return (fromExcerpt ?: fromContent)?.let { text ->
            text.replace(Regex("""\s+"""), " ").trim().take(1200)
        }
    }

    private fun extractTags(html: String): List<String>? {
        val doc = Jsoup.parseBodyFragment(html)
        val text = doc.text()
        val genrePattern = Regex("""(?i)Genre[s]?\s*[:\-]?\s*([^\n]+)""")
        val match = genrePattern.find(text) ?: return null
        return match.groupValues[1]
            .split(",", "·", "/", "&")
            .map { it.trim() }
            .filter { it.length > 2 && it.length < 40 }
            .takeIf { it.isNotEmpty() }
    }

    private fun extractRecommendations(html: String): List<SearchResponse> {
        val doc = Jsoup.parseBodyFragment(html)
        return doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
                if (!isValidContentUrl(href)) return@mapNotNull null
                val recTitle = htmlToText(a.html()).trim()
                if (recTitle.length < 6 || recTitle.length > 120) return@mapNotNull null
                val type = guessCardType(recTitle, href)
                val clean = cleanTitle(recTitle)
                when (type) {
                    TvType.Anime -> newAnimeSearchResponse(clean, href, TvType.Anime)
                    TvType.TvSeries -> newTvSeriesSearchResponse(clean, href, TvType.TvSeries)
                    else -> newMovieSearchResponse(clean, href, TvType.Movie)
                }
            }
            .distinctBy { it.url }
            .take(12)
    }

    private fun extractYear(text: String): Int? {
        Regex("""(?i)\b(19\d{2}|20\d{2})\b""")
            .findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1920..2099 }
            ?.let { return it }
        return null
    }

    // -------------------------------------------------------------------------
    // Mirror / episode parsing
    // -------------------------------------------------------------------------

    private fun collectPlayableMirrors(html: String, season: Int?): List<Mirror> {
        val doc = Jsoup.parseBodyFragment(html)
        val mirrors = mutableListOf<Mirror>()
        var currentEpisode: Int? = null
        var currentLabel: String? = null
        var currentQuality: String? = null
        var lastHeadingText = ""

        // Traverse the document in order. Whenever we see an episode/quality
        // marker we keep it; when we see a playable link we assign the most
        // recent marker. This handles sites that put "Episode 01" and the
        // QuickWatch link in separate sibling elements.
        doc.select("h1,h2,h3,h4,h5,h6,p,li,div,span,strong,b,a[href]").forEach { el ->
            val text = el.text().trim()
            if (el.tagName().matches(Regex("""h[1-6]"""))) {
                lastHeadingText = text
            }

            // Update rolling markers from any element text.
            episodeNumber(text)?.let {
                currentEpisode = it
                currentLabel = episodeLabel(text)
            }
            detectQualityLabel(text)?.let { currentQuality = it }

            val rawUrl = when {
                el.hasAttr("href") -> el.absUrl("href").ifBlank { el.attr("href") }
                else -> ""
            }.removeSuffix("%0A").trim()

            if (!isPlayableExternalUrl(rawUrl)) return@forEach

            val code = extractByseCode(rawUrl)
            val context = "$lastHeadingText $text"

            val url = if (rawUrl.contains("bysekoze.", true)) {
                code?.let { "https://bysekoze.com/d/$it" } ?: rawUrl
            } else rawUrl

            mirrors.add(
                Mirror(
                    url = url,
                    label = currentLabel ?: episodeLabel(context),
                    episode = currentEpisode ?: episodeNumber(context),
                    season = seasonNumber(context) ?: season,
                    quality = currentQuality ?: detectQualityLabel(context) ?: detectQualityLabel(rawUrl),
                    code = code
                )
            )
        }

        return mirrors
            .distinctBy { it.url }
            .deduplicateByCode()
    }

    private fun collectCollectionEntries(html: String): List<CollectionEntry> {
        val doc = Jsoup.parseBodyFragment(html)
        return doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
                val title = htmlToText(a.html()).trim()
                if (!isValidContentUrl(href)) return@mapNotNull null
                if (title.length < 6 || title.length > 160) return@mapNotNull null
                val lower = title.lowercase()
                if (blockedKeywords.any { lower.contains(it) }) return@mapNotNull null
                val seemsContent = contentKeywords.any { lower.contains(it) }
                if (!seemsContent) return@mapNotNull null
                CollectionEntry(cleanTitle(title), href)
            }
            .distinctBy { it.url }
    }

    private fun buildCollectionEpisodes(entries: List<CollectionEntry>): List<Episode> {
        return entries.mapIndexed { index, entry ->
            newEpisode(entry.url) {
                this.name = entry.title.ifBlank { "Part ${index + 1}" }
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
            val sorted = group.sortedWith(
                compareByDescending<Mirror> { qualityRank(it.quality) }.thenBy { it.url }
            )
            val data = sorted.joinToString("\n") { it.url }
            val best = sorted.firstOrNull()
            newEpisode(data) {
                this.name = best?.label ?: "Episode $episode"
                this.season = season
                this.episode = episode
            }
        }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun List<Mirror>.deduplicateByCode(): List<Mirror> {
        val seen = mutableSetOf<String>()
        return filter { mirror ->
            val code = mirror.code
            if (code.isNullOrBlank()) return@filter true
            if (seen.contains(code)) return@filter false
            seen.add(code)
            true
        }
    }

    // -------------------------------------------------------------------------
    // External resolvers
    // -------------------------------------------------------------------------

    private suspend fun resolveMediaFire(url: String): String? = try {
        val doc = app.get(url, headers = headers, timeout = 20).document
        doc.selectFirst("#downloadButton")?.absUrl("href")?.ifBlank { null }
            ?: Regex("""https?://download\d+\.mediafire\.com/[^\"']+""", RegexOption.IGNORE_CASE)
                .find(doc.html())?.value
    } catch (e: Exception) {
        Log.e(TAG, "resolveMediaFire failed: ${e.message}")
        null
    }

    // -------------------------------------------------------------------------
    // HTML / text helpers
    // -------------------------------------------------------------------------

    private fun sanitizeBrokenWpContent(html: String): String {
        if (html.contains("<a ", ignoreCase = true)) return html
        return buildString {
            append(html)
            Regex(
                """https?://(?:mega\.nz|www\.mediafire\.com|mediafire\.com)/[^\s"'<>]+""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
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

    private fun extractEmbeddedPoster(obj: JSONObject): String? {
        val embedded = obj.optJSONObject("_embedded") ?: return null
        val media = embedded.optJSONArray("wp:featuredmedia")?.optJSONObject(0) ?: return null
        // Prefer a down-sized image (large) to avoid huge WebP files that some
        // CloudStream image loaders struggle with, but fall back to the full source_url.
        val sizes = media.optJSONObject("media_details")?.optJSONObject("sizes")
        listOf("large", "medium_large", "medium", "thumbnail").forEach { size ->
            sizes?.optJSONObject(size)?.optString("source_url")?.ifBlank { null }?.let { return it }
        }
        return media.optString("source_url").ifBlank { null }
    }

    private fun extractFirstPoster(html: String): String? {
        val doc = Jsoup.parseBodyFragment(html)
        val img = doc.selectFirst("img") ?: return null
        return listOf(
            img.absUrl("data-lazy-src"),
            img.absUrl("data-src"),
            img.absUrl("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.ifBlank { null },
            img.absUrl("src")
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun extractMetaDescription(html: String): String? {
        val text = htmlToText(html)
        return text.takeIf { it.length > 40 }?.take(500)
    }

    private fun htmlToText(html: String): String =
        Jsoup.parse(html).text().replace(Regex("""\s+"""), " ").trim()

    private fun decodeHtml(html: String): String = Jsoup.parse(html).text()

    private fun normalizeContentUrl(url: String): String {
        val clean = if (url.startsWith("http", ignoreCase = true)) url else fixUrl(url)
        return clean.trimEnd('/').substringBefore('#').substringBefore('?')
    }

    private fun seasonNumber(text: String): Int? = Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""")
        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun episodeNumber(text: String?): Int? {
        val t = text ?: return null
        Regex("""(?i)\b(?:episode|ep)\s*[-:]?\s*0*(\d{1,3})\b""").find(t)
            ?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(?i)\bS\d{1,2}\s*E\s*0*(\d{1,3})\b""").find(t)
            ?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun episodeLabel(text: String?): String? {
        val t = text ?: return null
        // Try to capture "Episode 01 - Title" or "Episode 01 Hindi" etc.
        Regex("""(?i)\b(?:episode|ep)\s*[-:]?\s*0*(\d{1,3})\b[^\n]*""")
            .find(t)?.value?.let { raw ->
                return raw
                    .replace(Regex("""(?i)QuickWatch|Download|Watch Online|\[|]|\(|\)"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .takeIf { it.length in 5..90 }
            }
        return null
    }

    private fun extractByseCode(url: String): String? = Regex(
        """(?i)bysekoze\.[a-z]+/(?:d|e|download|dwn)/([a-z0-9]+)"""
    ).find(url)?.groupValues?.getOrNull(1)

    private fun isPlayableExternalUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains(mainUrl.lowercase())) return false
        if (lower.contains("telegram.me") || lower.contains("t.me/")) return false
        if (lower.contains("youthideas.in") || lower.contains("finance.")) return false
        return lower.contains("bysekoze.") ||
            lower.contains("filemoon") ||
            lower.contains("mega.nz") ||
            lower.contains("mediafire.com") ||
            lower.contains("jwplayer.com") ||
            lower.contains("mp4") ||
            lower.contains("m3u8") ||
            lower.contains(".mkv") ||
            lower.contains(".webm")
    }

    private fun isValidContentUrl(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        val mainHost = runCatching { URI(mainUrl).host?.lowercase() }.getOrNull() ?: "raretoonindia.in"
        if (!host.equals(mainHost, ignoreCase = true) && !host.contains("raretoon")) {
            return false
        }
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        if (path.isBlank() || path == "/") return false
        if (path.count { it == '/' } <= 1 && !path.contains('-')) return false
        if (blockedPrefixes.any { path.startsWith(it) }) return false
        if (path.contains("/page/")) return false
        return true
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""(?i)\s*[-|–—]\s*Rare\s*Toons?\s*India.*$"""), "")
        .replace(Regex("""(?i)\b(download|watch online|in hindi|hindi dubbed|hindi|multiquality|multi\s*quality)\b"""), " ")
        .replace(Regex("""\[[^]]*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun isCollectionTitle(title: String): Boolean = Regex(
        """(?i)\b(all\s+doraemon\s+movies|all\s+shin\s*chan\s+movies|all\s+movies|movie\s+collection|complete\s+collection|all\s+seasons|all\s+episodes)\b"""
    ).containsMatchIn(title)

    private fun guessSeriesType(title: String): TvType = when {
        title.contains("doraemon", true) ||
            title.contains("shin", true) ||
            title.contains("pokemon", true) ||
            title.contains("anime", true) ||
            title.contains("demonslayer", true) ||
            title.contains("demon slayer", true) ||
            title.contains("attack on titan", true) ||
            title.contains("my hero academia", true) ||
            title.contains("solo leveling", true) ||
            title.contains("jujutsu kaisen", true) ||
            title.contains("naruto", true) ||
            title.contains("classroom of the elite", true) -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun guessCardType(title: String, url: String): TvType = when {
        isCollectionTitle(title) -> TvType.TvSeries
        title.contains("season", true) || title.contains("episodes", true) -> guessSeriesType(title)
        url.contains("/episodes/", true) -> TvType.Anime
        else -> TvType.Movie
    }

    private fun detectQualityLabel(text: String?): String? = text?.let {
        Regex("""(?i)\b(2160p|1080p|720p|480p|360p)\b""").find(it)?.groupValues?.getOrNull(1)
    }

    private fun detectQuality(title: String) = when {
        title.contains("2160", true) -> SearchQuality.HD
        title.contains("1080", true) -> SearchQuality.HD
        title.contains("720", true) -> SearchQuality.HD
        title.contains("480", true) -> SearchQuality.SD
        title.contains("360", true) -> SearchQuality.SD
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

    private fun String.tokenizeQuery(): List<String> =
        lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 3 }
            .distinct()

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "RareToonIndia"
        private const val DEFAULT_MAIN_URL = "https://raretoonindia.in"
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/domains.json"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val POSTS_PER_PAGE = 20
        private val CATEGORY_CACHE = linkedMapOf<String, Int>()

        private val blockedPrefixes = setOf(
            "/about-us", "/contact-us", "/dmca", "/privacy-policy", "/copyright-issues",
            "/disclaimers", "/terms-and-conditions", "/wp-content", "/wp-json", "/feed", "/comments",
            "/animes/", "/doraemon/", "/movies/", "/pokemon/", "/disney/", "/shinchan/",
            "/all-movies/", "/episodes/"
        )

        private val blockedKeywords = setOf(
            "click here", "join", "telegram", "subscribe", "download from", "how to download",
            "stay tooned", "winding up", "thank you for visiting"
        )

        private val contentKeywords = setOf(
            "season", "episode", "movie", "doraemon", "shin", "pokemon", "anime", "leveling",
            "slayer", "academia", "titan", "naruto", "jujutsu", "disney", "pixar", "dragon ball"
        )

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
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    }
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
                u.startsWith("http://", true) -> u.replaceFirst("http://", "https://", true)
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
