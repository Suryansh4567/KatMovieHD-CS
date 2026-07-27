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
 *   3. dl.freedrivemovie.org is a WordPress "download resolver" whose
 *      `.wp-block-button a` anchors are the actual sources:
 *        - Cloudflare-Worker GDToT mirrors (.mkv) -> directly playable. These
 *          expire sometimes, so each is range-probed via [isPlayable] before it
 *          is offered (dead 404 mirrors are skipped).
 *        - File lockers (gdflix.dev / hubcloud.foo / gdtot / mega) -> handed to
 *          CloudStream's generic [loadExtractor].
 *
 * TV: `/tvshows/<slug>/` has `#seasons > .se-c` seasons. Each season lists
 * either individual episodes (`<li>` -> `/episodes/<slug>/`) or a single
 * "Complete" batch whose dl page embeds per-episode links ("Episode N ...").
 * [expandBatches] turns a batch-only season into real per-episode entries so
 * the user gets discrete, correctly-sourced episodes instead of one entry that
 * dumps every episode's links.
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

        /**
         * Encoded payload for a batch-expanded episode: "<dlPageUrl>||ep=<N>".
         * [loadLinks] branches on this instead of doing the shortlink chain.
         */
        private const val EP_MARKER = "||ep="

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$MAIN/",
        )

        private val TITLE_YEAR = Regex("""^(.*?)\((\d{4})\)""", RegexOption.IGNORE_CASE)
        private val TITLE_CUT = Regex(
            """\s+(?:HDTC|HDCAM|HDTS|HDTS-PRINT|TS|TC|TCRIP|CAM|HINDI-CAM|WEB-DL|WEB-RIP|""" +
                """WEBRip|WEB-DLRip|BluRay|Blu-Ray|BRRip|HDRip|DVDScr|DVDRip|WEBDL|WEB|""" +
                """HEVC|x264|x265|AVC|AAC|DDP|DDPA|ESub|HC|LiNE|2160p|1080p|720p|480p|360p|4K)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Matches "Episode 3" / "Ep 3" inside a dl-page button label. */
        private val EPISODE_NUM = Regex("""(?:Episode|Ep)\s*[.-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pure helpers
    // ──────────────────────────────────────────────────────────────────────

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

    private fun upScalePoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url
            .replace("/w92/", "/w500/").replace("/w154/", "/w500/")
            .replace("/w185/", "/w500/").replace("/w342/", "/w500/")
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

    private fun isDirectPlayable(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".mkv") || u.endsWith(".mp4") || u.endsWith(".webm") ||
            u.endsWith(".m4v") || u.endsWith(".mov") || u.endsWith(".m3u8") ||
            "workers.dev" in u || "/0:/" in u
    }

    /**
     * Range-probes a direct mirror so dead/expired CDN links (observed: some
     * Cloudflare-Worker mirrors 404) are never offered to the player. Workers
     * mirrors honour Range, so this is a ~2-byte request.
     */
    private suspend fun isPlayable(url: String): Boolean = runCatching {
        val r = app.get(url, headers = HEADERS + ("Range" to "bytes=0-1"), timeout = 10)
        val ct = r.headers["content-type"]?.lowercase().orEmpty()
        r.code in 200..299 && !ct.contains("text/html")
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────────────
    // Home page
    // ──────────────────────────────────────────────────────────────────────

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

    private fun parseCards(doc: Document, section: String): List<HomePageList> {
        val out = mutableListOf<SearchResponse>()
        for (art in doc.select("article.item")) {
            val href = art.selectFirst("a[href*=/movies/], a[href*=/tvshows/]")?.absUrl("href")
                ?.takeIf { it.startsWith("http") } ?: continue
            val name = cleanTitle(
                art.selectFirst(".title")?.text()
                    ?: art.selectFirst("h3")?.text()
                    ?: art.selectFirst("img")?.attr("alt")
                    ?: "",
            )
            if (name.isBlank()) continue
            val poster = upScalePoster(art.selectFirst("img")?.absUrl("src"))
            out.add(newMovieSearchResponse(name, href, tvTypeFor(href)) { this.posterUrl = poster })
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
                val name = cleanTitle(item.selectFirst(".title a")?.text() ?: item.selectFirst(".title")?.text() ?: "")
                if (name.isBlank()) continue
                val poster = upScalePoster(item.selectFirst("img")?.absUrl("src"))
                out.add(newMovieSearchResponse(name, href, tvTypeFor(href)) { this.posterUrl = poster })
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
                val episodes = expandBatches(parseEpisodes(doc))
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    /** Parse `#seasons > .se-c > .episodios li` into raw Episodes (batches included). */
    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (seasonEl in doc.select("#seasons .se-c")) {
            val seasonNum = seasonEl.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull()
                ?: continue
            for (li in seasonEl.select(".episodios li")) {
                val epHref = li.selectFirst("a[href*=/episodes/]")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") } ?: continue
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
                        this.episode = if (isBatch) 0 else epNum
                    },
                )
            }
        }
        return episodes
    }

    private fun isBatchEpisode(ep: Episode): Boolean {
        val n = ep.name?.lowercase().orEmpty()
        return n.contains("complete") || n.contains("all episode") || n.contains("(all)") ||
            ep.episode == 0
    }

    /**
     * A season usually has either discrete episode pages OR a single "Complete"
     * batch. If discrete episodes exist we keep them (and drop the batch, which
     * would only duplicate them). If only a batch exists we expand it into real
     * per-episode entries sourced from the batch's dl page.
     */
    private suspend fun expandBatches(episodes: List<Episode>): List<Episode> {
        if (episodes.isEmpty()) return episodes
        val out = mutableListOf<Episode>()
        for ((_, eps) in episodes.groupBy { it.season ?: 0 }) {
            val individuals = eps.filterNot { isBatchEpisode(it) }
            if (individuals.isNotEmpty()) {
                out.addAll(individuals)
                continue
            }
            val batch = eps.firstOrNull { isBatchEpisode(it) } ?: eps.firstOrNull()
            if (batch != null) out.addAll(expandOneBatch(batch))
        }
        return out.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    /** Resolve one batch episode into per-episode entries using its dl page. */
    private suspend fun expandOneBatch(batch: Episode): List<Episode> {
        val pageUrl = batch.data ?: return listOf(batch)
        val dlUrl = resolveFirstDlPage(pageUrl) ?: return listOf(batch)
        val doc = try {
            app.get(dlUrl, headers = HEADERS).document
        } catch (_: Throwable) {
            return listOf(batch)
        }
        val epNumbers = sortedSetOf<Int>()
        for (a in doc.select(".wp-block-button a")) {
            EPISODE_NUM.find(a.text())?.groupValues?.get(1)?.toIntOrNull()?.let(epNumbers::add)
        }
        if (epNumbers.isEmpty()) return listOf(batch) // single-file pack, keep as-is
        return epNumbers.map { n ->
            newEpisode("$dlUrl$EP_MARKER$n") {
                this.name = "Episode $n"
                this.season = batch.season
                this.episode = n
            }
        }
    }

    /** Follow the first shortlink of a content page to its dl.freedrivemovie.org URL. */
    private suspend fun resolveFirstDlPage(pageUrl: String): String? {
        for (sl in parseShortLinks(pageUrl)) {
            val target = try {
                app.get(sl, headers = HEADERS).document.selectFirst("a#link")?.absUrl("href")
            } catch (_: Throwable) {
                null
            }?.takeIf { it.startsWith("http") } ?: continue
            if (target.contains("freedrivemovie")) return target
        }
        return null
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
        // Batch-expanded episode: data is "<dlPageUrl>||ep=<N>".
        if (data.contains(EP_MARKER)) {
            return loadExpandedEpisodeLinks(data, subtitleCallback, callback)
        }
        return try {
            val shortlinks = parseShortLinks(data)
            val dlPages = linkedSetOf<String>()
            for (sl in shortlinks) {
                val sdoc = app.get(sl, headers = HEADERS).document
                val target = sdoc.selectFirst("a#link")?.absUrl("href")?.takeIf { it.startsWith("http") }
                if (target != null && target.contains("freedrivemovie")) {
                    dlPages.add(target)
                } else if (target != null) {
                    emitLinksFromAnchors(listOf(target to "Mirror"), subtitleCallback, callback)
                }
            }
            val anchors = mutableListOf<Pair<String, String>>() // href to label
            for (dlUrl in dlPages) {
                val ddoc = try {
                    app.get(dlUrl, headers = HEADERS).document
                } catch (_: Throwable) {
                    continue
                }
                for (a in ddoc.select(".wp-block-button a")) {
                    val href = a.absUrl("href").trim()
                    if (href.startsWith("http")) anchors.add(href to (a.text().trim().ifBlank { "Mirror" }))
                }
            }
            emitLinksFromAnchors(anchors, subtitleCallback, callback)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        }
    }

    /** Resolve links for a batch-expanded episode, filtered to its episode number. */
    private suspend fun loadExpandedEpisodeLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val dlUrl = data.substringBefore(EP_MARKER)
        val epNum = data.substringAfter(EP_MARKER).toIntOrNull() ?: return false
        val ddoc = try {
            app.get(dlUrl, headers = HEADERS).document
        } catch (_: Throwable) {
            return false
        }
        val anchors = mutableListOf<Pair<String, String>>()
        for (a in ddoc.select(".wp-block-button a")) {
            val label = a.text().trim()
            val matched = EPISODE_NUM.find(label)?.groupValues?.get(1)?.toIntOrNull()
            if (matched != epNum) continue
            val href = a.absUrl("href").trim()
            if (href.startsWith("http")) anchors.add(href to label)
        }
        return emitLinksFromAnchors(anchors, subtitleCallback, callback)
    }

    /**
     * Turn (href, label) pairs into ExtractorLinks. Direct mirrors are
     * range-probed; file-locker anchors go to [loadExtractor]. Returns true if
     * anything was emitted.
     */
    private suspend fun emitLinksFromAnchors(
        anchors: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val seen = linkedSetOf<String>()
        for ((href, rawLabel) in anchors) {
            if (!seen.add(href)) continue
            // Drop the "Episode N" prefix from the displayed label when present.
            val label = EPISODE_NUM.replace(rawLabel, "").trim().replace(Regex("\\s+"), " ").ifBlank { "Mirror" }
            val referer = if (href.contains("dl.freedrivemovie") || href.contains("workers.dev")) DL_REFERER else mainUrl
            if (isDirectPlayable(href)) {
                if (isPlayable(href)) {
                    callback.invoke(
                        newExtractorLink("FreeDriveMovie", "FreeDriveMovie - $label", href, ExtractorLinkType.VIDEO) {
                            this.quality = qualityFromLabel(label)
                            this.referer = referer
                        },
                    )
                    found = true
                }
            } else {
                found = true
                try {
                    loadExtractor(href, mainUrl, subtitleCallback, callback)
                } catch (_: Throwable) { /* best-effort */ }
            }
        }
        return found
    }

    /** Collect every `/links/<code>/` shortlink from a movie/episode page. */
    private suspend fun parseShortLinks(pageUrl: String): List<String> {
        val doc = app.get(pageUrl, headers = HEADERS).document
        val out = linkedSetOf<String>()
        for (tr in doc.select(".links_table tr")) {
            val href = tr.selectFirst("a[href]")?.absUrl("href") ?: continue
            if ("/links/" in href) out.add(href)
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href*='/links/']")) {
                a.absUrl("href").takeIf { "/links/" in it }?.let(out::add)
            }
        }
        return out.take(10)
    }
}

/** like Element.eachText() but strips nested <font>/<i> noise and de-dupes. */
private fun org.jsoup.select.Elements.eachTextClean(): List<String> =
    this.mapNotNull { it.text().trim().takeIf { s -> s.isNotEmpty() } }.distinct()
