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
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        "/" to "Latest",
        "/doraemon/" to "Doraemon",
        "/doraemon/episodes/" to "Doraemon Episodes",
        "/doraemon/all-movies/" to "Doraemon Movies",
        "/animes/" to "Animes",
        "/animes/attack-on-titan/" to "Attack on Titan",
        "/animes/naruto/" to "Naruto",
        "/animes/naruto-shippuden/" to "Naruto Shippuden",
        "/animes/solo-leveling/" to "Solo Leveling",
        "/animes/jujutsu-kaisen/" to "Jujutsu Kaisen",
        "/animes/demon-slayer/" to "Demon Slayer",
        "/animes/my-hero-academia/" to "My Hero Academia",
        "/animes/dr-stone/" to "Dr. Stone",
        "/animes/classroom-of-the-elite/" to "Classroom of the Elite",
        "/pokemon/" to "Pokemon",
        "/disney/" to "Disney",
        "/movies/" to "Movies",
        "/movies/shin-chan-movies/" to "Shin Chan Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshMainUrl()
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers, timeout = 25).document
        val results = parseListing(doc)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        refreshMainUrl()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 25).document
        return parseListing(doc).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        refreshMainUrl()
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers, timeout = 25).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val title = cleanTitle(rawTitle)
        val poster = extractPoster(doc)
        val plot = doc.select("article p, .post p, main p")
            .firstOrNull { it.text().length > 70 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val mirrors = collectPlayableMirrors(doc)
        val isSeries = looksSeries(rawTitle) || mirrors.any { it.episode != null } || mirrors.size > 2
        val season = seasonNumber(rawTitle) ?: 1
        val episodes = if (isSeries) buildEpisodes(mirrors, season) else emptyList()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, guessType(rawTitle), episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val data = mirrors.joinToString("\n") { it.url }.ifBlank { pageUrl }
            newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
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
            .distinct()
            .toList()
        if (urls.isEmpty()) return false
        urls.forEach { url ->
            when {
                url.contains("bysekoze.", ignoreCase = true) -> {
                    ByseKozE().getUrl(url, mainUrl, subtitleCallback, callback)
                }
                isDirectVideo(url) -> {
                    callback.invoke(newExtractorLink(name, name, url) {
                        this.quality = directQuality(url)
                    })
                }
                else -> loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun refreshMainUrl(): String {
        val active = getActiveMainUrl().trimEnd('/')
        if (active.isNotBlank() && active != mainUrl) {
            Log.d(TAG, "Active RareToon domain refreshed: $mainUrl -> $active")
            mainUrl = active
        }
        return active.ifBlank { mainUrl }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http", ignoreCase = true)) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun pageUrl(path: String, page: Int): String {
        val base = if (path.startsWith("http", true)) path.trimEnd('/') else mainUrl + path
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val selectors = listOf(
            "article.elementor-post",
            "article.type-post",
            "article[id^=post-]",
            ".elementor-post",
            ".e-loop-item",
            ".post.type-post",
            "li[id^=post-]"
        )
        val items = selectors.flatMap { selector -> doc.select(selector).mapNotNull { it.toRareSearchResult() } }
        if (items.isNotEmpty()) return items.distinctBy { it.url }
        return doc.select("main a:has(img), .site-main a:has(img), a:has(img)")
            .mapNotNull { it.toRareSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toRareSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title a[href], h3.entry-title a[href], h2 a[href], h3 a[href], .entry-title a[href], .elementor-post__title a[href]")
            ?: selectFirst("a[href]:has(img)")
            ?: takeIf { tagName().equals("a", true) }
            ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.ifBlank { return null }
        if (!href.contains("raretoonindia.in", ignoreCase = true)) return null
        if (listOf("/category/", "/tag/", "/page/", "#", "/dmca", "/contact", "/privacy", "/copyright", "/disclaimers", "/about-us", "/wp-").any { href.contains(it, true) }) return null
        val rawTitle = anchor.attr("title").ifBlank { anchor.text() }
            .ifBlank { selectFirst("img")?.attr("alt") ?: "" }
            .ifBlank { return null }
        val poster = extractPoster(this)
        return newMovieSearchResponse(cleanTitle(rawTitle), href, guessType(rawTitle)) {
            this.posterUrl = poster
            this.quality = detectQuality(rawTitle)
        }
    }

    private data class Mirror(
        val url: String,
        val label: String?,
        val episode: Int?,
        val season: Int?,
        val quality: String?
    )

    private fun contentContainer(doc: Document): Element = doc.selectFirst(
        ".elementor-widget-theme-post-content .elementor-widget-container, article .entry-content, .entry-content, main article, main, article, .post, body"
    ) ?: doc

    private fun collectPlayableMirrors(doc: Document): List<Mirror> {
        val byseMirrors = collectByseLinks(doc)
        val directMirrors = collectDirectLinks(doc)
        return (byseMirrors + directMirrors)
            .distinctBy { it.url }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.quality ?: "" }, { it.url }))
    }

    private fun collectByseLinks(doc: Document): List<Mirror> {
        val content = contentContainer(doc)
        val mirrors = mutableListOf<Mirror>()
        var currentEpisode: Int? = null
        var currentSeason: Int? = seasonNumber(doc.title()) ?: seasonNumber(doc.selectFirst("h1")?.text().orEmpty())
        var currentLabel: String? = null
        var currentQuality: String? = null

        content.select("h1,h2,h3,h4,h5,h6,p,li,div,span,strong,b,a[href]").forEach { el ->
            val text = el.ownText().ifBlank { el.text() }.trim()
            seasonNumber(text)?.let { currentSeason = it }
            val ep = episodeNumber(text)
            if (ep != null && !text.contains("episodes:", true) && !text.contains("all episodes", true)) {
                currentEpisode = ep
                currentLabel = episodeName(text, ep)
            }
            detectQualityLabel(text)?.let { currentQuality = it }

            if (el.tagName().equals("a", true)) {
                val href = el.absUrl("href").ifBlank { el.attr("href") }
                val code = byseCode(href) ?: return@forEach
                val watchUrl = "https://bysekoze.com/d/$code"
                val parentText = el.parent()?.text()?.take(180)
                val derivedSeason = seasonNumber(parentText.orEmpty()) ?: currentSeason
                val derivedEpisode = currentEpisode ?: episodeNumber(parentText)
                val derivedQuality = currentQuality ?: detectQualityLabel(parentText.orEmpty()) ?: detectQualityLabel(el.text())
                val label = currentLabel ?: parentText?.ifBlank { el.text() }
                mirrors.add(Mirror(watchUrl, label, derivedEpisode, derivedSeason, derivedQuality))
            }
        }

        if (mirrors.isEmpty()) {
            content.select("a[href*='bysekoze.']").forEach { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                val code = byseCode(href) ?: return@forEach
                val parentText = a.parent()?.text()?.take(180)
                mirrors.add(
                    Mirror(
                        url = "https://bysekoze.com/d/$code",
                        label = parentText?.ifBlank { a.text() },
                        episode = episodeNumber(parentText),
                        season = seasonNumber(parentText.orEmpty()) ?: currentSeason,
                        quality = detectQualityLabel(parentText.orEmpty()) ?: detectQualityLabel(a.text())
                    )
                )
            }
        }

        return mirrors.distinctBy { byseCode(it.url) ?: it.url }
    }

    private fun collectDirectLinks(doc: Document): List<Mirror> {
        val content = contentContainer(doc)
        val links = mutableListOf<Mirror>()
        var currentEpisode: Int? = null
        var currentSeason: Int? = seasonNumber(doc.title()) ?: seasonNumber(doc.selectFirst("h1")?.text().orEmpty())
        var currentQuality: String? = null

        content.select("h1,h2,h3,h4,h5,h6,p,li,div,span,strong,b,a[href],source[src],iframe[src],video source[src]").forEach { el ->
            val text = el.ownText().ifBlank { el.text() }.trim()
            seasonNumber(text)?.let { currentSeason = it }
            episodeNumber(text)?.let { currentEpisode = it }
            detectQualityLabel(text)?.let { currentQuality = it }

            val rawUrl = when {
                el.hasAttr("href") -> el.absUrl("href").ifBlank { el.attr("href") }
                el.hasAttr("src") -> el.absUrl("src").ifBlank { el.attr("src") }
                else -> ""
            }
            if (!isDirectVideo(rawUrl)) return@forEach
            val parentText = el.parent()?.text()?.take(180).orEmpty()
            links.add(
                Mirror(
                    url = rawUrl,
                    label = parentText.ifBlank { el.text() },
                    episode = currentEpisode ?: episodeNumber(parentText),
                    season = seasonNumber(parentText).takeIf { it != null } ?: currentSeason,
                    quality = detectQualityLabel(rawUrl) ?: detectQualityLabel(parentText) ?: currentQuality
                )
            )
        }

        return links.distinctBy { it.url }
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

    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return listOf(
            img.absUrl("data-lazy-src"),
            img.absUrl("data-src"),
            img.absUrl("data-original"),
            img.absUrl("src"),
            firstSrcSetUrl(img.attr("data-srcset")),
            firstSrcSetUrl(img.attr("srcset"))
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun firstSrcSetUrl(srcset: String?): String? {
        val value = srcset?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .firstOrNull { it.startsWith("http", true) }
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
            ?.replace(Regex("""(?i)quickwatch|download|watch online|\[|]"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.length in 5..80 } ?: "Episode $ep"
    }

    private fun byseCode(url: String): String? = Regex("""(?i)bysekoze\.[a-z]+/(?:d|e|download|dwn)/([a-z0-9]+)""")
        .find(url)?.groupValues?.getOrNull(1)

    private fun cleanTitle(title: String): String = title
        .replace(Regex("""(?i)\s*[-|–—]\s*Rare\s*Toons?\s*India.*$"""), "")
        .replace(Regex("""(?i)\b(download|watch online|episodes?|in hindi|hindi dubbed|hd)\b"""), " ")
        .replace(Regex("""\[[^]]*]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun looksSeries(title: String): Boolean = Regex("""(?i)\b(season|episodes?|all seasons|s\d{1,2})\b""").containsMatchIn(title)

    private fun guessType(title: String): TvType = if (looksSeries(title)) TvType.Anime else TvType.Movie

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
                val uri = java.net.URI(full)
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
