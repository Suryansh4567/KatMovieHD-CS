package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document

/**
 * FreeDriveMovie provider — freedrivemovie.cyou (Dooplay / WordPress).
 *
 * Hoster chain (verified against the live site on 2026-07-27):
 *
 *   1. Content page (movies/tvshows/episodes) carries a Dooplay download-links
 *      table: `.links_table tr > a[href*=/links/<code>/]`.
 *   2. Each `/links/<code>/` is a 5s-countdown intermediate HTML page whose
 *      `<a id="link" href="https://dl.freedrivemovie.org/<slug>/">` is the real
 *      download/player page URL.
 *   3. The dl.freedrivemovie.org page is a WordPress (GeneratePress) "download
 *      resolver" whose first `.wp-block-buttons` container holds direct-playable
 *      Cloudflare-Worker GDToT mirrors (e.g.
 *      https://hugs.cf5-4c5.workers.dev/0:/GDToT/....mkv). The site's own
 *      download-resolver.js feeds exactly those anchors into a Plyr
 *      `<source type="video/mp4">`, and the CDN answers range requests with
 *      HTTP 206 + a valid Matroska signature — i.e. directly playable by
 *      CloudStream's ExoPlayer with no hoster extractor.
 *
 *   Remaining `.wp-block-button a` anchors (gdflix.dev / hubcloud.foo / mega.nz
 *   style file lockers) are handed to CloudStream's generic [loadExtractor] as a
 *   best-effort fallback.
 *
 * Movies use `/movies/<slug>/`; TV uses `/tvshows/<slug>/` with a `#seasons`
 * block of `.se-c` seasons, each with `ul.episodios > li` items linking to
 * `/episodes/<slug>/` pages that reuse the identical links table.
 */
class FreeDriveMovie : MainAPI() {

    override var mainUrl = "https://freedrivemovie.cyou"
    override var name = "FreeDriveMovie"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val MAIN = "https://freedrivemovie.cyou"

        /** Referer used on the dl.freedrivemovie.org player page requests. */
        private const val DL_REFERER = "https://dl.freedrivemovie.org/"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$MAIN/",
        )

        // Cuts the verbose Dooplay title down to "Name (YYYY)" for display.
        private val TITLE_YEAR = Regex("""^(.*?)\((\d{4})\)""", RegexOption.IGNORE_CASE)
        private val TITLE_CUT = Regex(
            """\s+(?:HDTC|HDCAM|HDTS|HDTS-PRINT|TS|TC|TCRIP|CAM|HINDI-CAM|WEB-DL|WEB-RIP|""" +
                """WEBRip|WEB-DLRip|BluRay|Blu-Ray|BRRip|HDRip|DVDScr|DVDRip|WEBDL|WEB|""" +
                """HEVC|x264|x265|AVC|AAC|DDP|DDPA|ESub|HC|LiNE|2160p|1080p|720p|480p|360p|4K)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Small pure helpers.
    // ──────────────────────────────────────────────────────────────────────

    /** "Alpha (2026) HDTC 1080p (1.85GB) ..." -> "Alpha (2026)". */
    private fun cleanTitle(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        TITLE_YEAR.find(s)?.let {
            val n = it.groupValues[1].trim().trimEnd(',', '-', ':')
            return if (n.isNotEmpty()) "$n (${it.groupValues[2]})" else "(${it.groupValues[2]})"
        }
        val m = TITLE_CUT.find(s)
        return if (m != null) s.substring(0, m.range.first).trim().trimEnd(',', '-', ':') else s
    }

    private fun parseYear(raw: String): Int? =
        Regex("""\((\d{4})\)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

    /** Bump tiny TMDB poster thumbs to w500 for a crisper UI. */
    private fun upScalePoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url
            .replace("/w92/", "/w500/")
            .replace("/w154/", "/w500/")
            .replace("/w185/", "/w500/")
            .replace("/w342/", "/w500/")
            .ifBlank { null }
    }

    private fun tvTypeFor(url: String): TvType =
        if (url.contains("/tvshows/") || url.contains("/episodes/")) TvType.TvSeries else TvType.Movie

    private fun qualityFromLabel(label: String): Int {
        val l = label.lowercase()
        return when {
            "2160" in l || "4k" in l || "uhd" in l -> Qualities.P2160.value
            "1440" in l || "2k" in l -> Qualities.P1440.value
            "1080" in l -> Qualities.P1080.value
            "720" in l -> Qualities.P720.value
            "480" in l -> Qualities.P480.value
            "360" in l -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** True if the URL is directly playable (no hoster page / extractor needed). */
    private fun isDirectPlayable(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".mkv") || u.endsWith(".mp4") || u.endsWith(".webm") ||
            u.endsWith(".m4v") || u.endsWith(".mov") || u.endsWith(".m3u8") ||
            "workers.dev" in u || "/0:/" in u
    }

    // ──────────────────────────────────────────────────────────────────────
    // Home page
    // ──────────────────────────────────────────────────────────────────────

    // The homepage does not paginate (/page/2/ -> 404), so each section is a
    // single fetch of its base URL; hasNext is always false.
    override val mainPage = mainPageOf(
        "$MAIN/" to "Home",
        "$MAIN/tvshows/" to "TV Series",
        "$MAIN/genre/bollywood-genre/" to "Bollywood",
        "$MAIN/genre/south-indian/" to "South Indian",
        "$MAIN/genre/action/" to "Action",
        "$MAIN/genre/drama/" to "Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val doc = app.get(request.data, headers = HEADERS).document
            newHomePageResponse(parseCards(doc, request.name), hasNext = false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)
        }
    }

    /** Parse the homepage/genre `article.item` cards into search responses. */
    private fun parseCards(doc: Document, section: String): List<HomePageList> {
        val out = mutableListOf<SearchResponse>()
        for (art in doc.select("article.item")) {
            val href = art.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                ?.takeIf { it.startsWith("http") } ?: continue
            val name = cleanTitle(art.selectFirst("h3 a, h3, .title")?.text() ?: "")
            if (name.isBlank()) continue
            val poster = upScalePoster(art.selectFirst("img")?.absUrl("src"))
            out.add(
                newMovieSearchResponse(name, href, tvTypeFor(href)) {
                    this.posterUrl = poster
                },
            )
        }
        return if (out.isEmpty()) emptyList() else listOf(HomePageList(section, out))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("$MAIN/?s=${java.net.URLEncoder.encode(query, "UTF-8")}", headers = HEADERS).document
            val out = mutableListOf<SearchResponse>()
            for (item in doc.select(".result-item")) {
                val href = item.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") } ?: continue
                val name = cleanTitle(item.selectFirst(".title a, .title, a")?.text() ?: "")
                if (name.isBlank()) continue
                val poster = upScalePoster(item.selectFirst("img")?.absUrl("src"))
                out.add(
                    newMovieSearchResponse(name, href, tvTypeFor(href)) {
                        this.posterUrl = poster
                    },
                )
            }
            out
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Load (detail)
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = HEADERS).document
            val rawTitle = doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            val title = cleanTitle(rawTitle).ifBlank { url.trimEnd('/').substringAfterLast('/') }
            val year = parseYear(rawTitle)
            val poster = upScalePoster(doc.selectFirst(".poster img")?.absUrl("src"))
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            val tags = doc.select("a[href*=/genre/]").eachTextClean().take(8)

            if (url.contains("/tvshows/")) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, parseEpisodes(doc)) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    /** Parse the Dooplay `#seasons > .se-c > .episodios li` into Episodes. */
    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (seasonEl in doc.select("#seasons .se-c")) {
            val seasonNum = seasonEl.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull()
                ?: (seasonEl.selectFirst(".se-q .title")?.text()?.substringAfter("Season")?.trim()?.toIntOrNull())
                ?: continue
            for (li in seasonEl.select(".episodios li")) {
                val epHref = li.selectFirst("a[href*=/episodes/]")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") } ?: continue
                // ".numerando" reads "2 - 1" (S-E) or "1 - All" (batch).
                val numerando = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
                val parts = numerando.split("-").map { it.trim() }
                val isBatch = parts.getOrNull(1)?.equals("All", ignoreCase = true) == true
                val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val baseName = li.selectFirst(".episodiotitle a")?.text()?.trim().orEmpty()
                val epName = if (isBatch) baseName.ifBlank { "Season $seasonNum Complete" } else baseName
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = if (isBatch) 1 else epNum
                    },
                )
            }
        }
        return episodes
    }

    // ──────────────────────────────────────────────────────────────────────
    // Links
    // ──────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            // 1. Pull the Dooplay download-link shortlinks from the content page.
            val shortlinks = parseShortLinks(data)
            var delegated = false

            // 2. Resolve each shortlink to its dl.freedrivemovie.org page URL.
            val dlPages = linkedSetOf<String>()
            for (sl in shortlinks) {
                val sdoc = app.get(sl, headers = HEADERS).document
                val target = sdoc.selectFirst("a#link")?.absUrl("href")?.takeIf { it.startsWith("http") }
                if (target != null && (target.contains("dl.freedrivemovie.org") || target.contains("freedrivemovie"))) {
                    dlPages.add(target)
                } else if (target != null) {
                    // Shortlink pointed straight at a third-party hoster (rare).
                    delegated = true
                    try {
                        loadExtractor(target, mainUrl, subtitleCallback, callback)
                    } catch (_: Throwable) { /* best-effort */ }
                }
            }

            // 3. For each dl page, harvest the direct-playable mirrors + delegate
            //    file-locker anchors to CloudStream's generic extractors.
            val seen = linkedSetOf<String>()
            for (dlUrl in dlPages) {
                val ddoc: Document = try {
                    app.get(dlUrl, headers = HEADERS).document
                } catch (_: Throwable) {
                    continue
                }
                for (a in ddoc.select(".wp-block-button a")) {
                    val href = a.absUrl("href").trim()
                    if (!href.startsWith("http")) continue
                    val label = a.text().trim().ifBlank { "Mirror" }
                    if (isDirectPlayable(href)) {
                        if (seen.add(href)) {
                            callback.invoke(
                                newExtractorLink("FreeDriveMovie", "FreeDriveMovie - $label", href, ExtractorLinkType.VIDEO) {
                                    this.quality = qualityFromLabel(label)
                                    this.referer = DL_REFERER
                                },
                            )
                        }
                    } else {
                        delegated = true
                        try {
                            loadExtractor(href, dlUrl, subtitleCallback, callback)
                        } catch (_: Throwable) { /* best-effort fallback */ }
                    }
                }
            }
            seen.isNotEmpty() || delegated
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        }
    }

    /** Collect every `/links/<code>/` shortlink from a movie/episode page. */
    private suspend fun parseShortLinks(pageUrl: String): List<String> {
        val doc = app.get(pageUrl, headers = HEADERS).document
        val out = linkedSetOf<String>()
        for (tr in doc.select(".links_table tr")) {
            val href = tr.selectFirst("a[href]")?.absUrl("href") ?: continue
            if ("/links/" in href) out.add(href)
        }
        // Fallback: some pages wrap links without the table row structure.
        if (out.isEmpty()) {
            for (a in doc.select("a[href*='/links/']")) {
                a.absUrl("href").takeIf { "/links/" in it }?.let { out.add(it) }
            }
        }
        return out.take(10)
    }
}

/** like Element.eachText() but strips nested <font>/<i> noise and de-dupes. */
private fun org.jsoup.select.Elements.eachTextClean(): List<String> =
    this.mapNotNull { it.text().trim().takeIf { s -> s.isNotEmpty() } }
        .distinct()
