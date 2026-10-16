package com.olamovies

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class OlaMoviesProvider : MainAPI() {
    // v6.olamovies.mov is the current live domain ("OM Official") —
    // v4.olamovies.mov 301-redirects to it (verified 2026-10-16).
    // The site is a Next.js / React-Server-Components app, NOT the old
    // WordPress theme, so parsing is card-anchor + RSC-JSON based.
    override var mainUrl = "https://v6.olamovies.mov"
    override var name = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/categories/movies" to "Movies",
        "$mainUrl/categories/tv-series" to "TV Series",
        "$mainUrl/categories/tv-shows" to "TV Shows",
        "$mainUrl/quality/2160p" to "4K / 2160p",
        "$mainUrl/quality/1080p" to "1080p",
        "$mainUrl/quality/720p" to "720p",
        "$mainUrl/quality/dv" to "Dolby Vision",
        "$mainUrl/quality/hdr10-plus" to "HDR10+",
        "$mainUrl/quality/remux" to "Remux",
        "$mainUrl/quality/imax" to "IMAX",
        "$mainUrl/quality/3d" to "3D",
        "$mainUrl/years/2026" to "Year 2026",
        "$mainUrl/years/2025" to "Year 2025",
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
         * First-hop link generator hosts. Detail-page buttons point here; these
         * pages ("OlaMovies Link Generator") then chain to the final file hosts.
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
         * Every drive.olamovies.download/file/<id> page embeds a Mongo-style id in
         * its inline state:  "_id":"<24-hex>"  plus the release name in <h1>.
         * The companion stream worker mints a direct, playable file URL:
         *   https://olam.bypassbot.workers.dev/<_id>?filename=<base64url(name)>
         */
        private val DRIVE_ID_REGEX = Regex("""\\?"_id\\?"\s*:\s*\\?"([0-9a-fA-F]{20,30})""")
        private const val DRIVE_WORKER_URL = "https://olam.bypassbot.workers.dev/"

        private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s"'<>\\\]]+""")

        /**
         * v10 — RSC detail pages embed the download table as escaped JSON.
         * Every leaf entry (file / pack) carries a btnId token:
         *   {\"id\":\"itm_..\",\"btnId\":\"<token>\",\"url\":\"https://links.ol-am.top/<token>\",...}
         * The enclosing object may contain nested maps ("face":{...}), so we
         * brace-count from the nearest '{' instead of flat regex matching.
         * Works for both shapes found live:
         *   movie: sections[].blocks[].files[]          (season:null)
         *   tv:    sections[].sources[].blocks[].files/packs
         */
        private val RSC_BTN_REGEX = Regex("""\\\"btnId\\\":\s*\\\"([^\\" ]+)""")

        /** Nearest enclosing season ("season":"1") scanning backwards. */
        private val RSC_SEASON_REGEX = Regex("""\\"season\\":\s*\\"(\d+)""")

        /** Nearest enclosing release resolution scanning backwards. */
        private val RSC_RESOLUTION_REGEX = Regex("""\\"resolution\\":\s*\\"([^\\" ]+)""")

        private fun isLinkHost(url: String): Boolean =
            LINK_HOSTS.any { url.contains(it, ignoreCase = true) }

        /** True when a response is actually a Cloudflare challenge page.
         *  Takes primitives — the nicehttp Response type is not nameable from
         *  extension modules, but its members (code/text) resolve fine. */
        private fun isCloudflareBlocked(code: Int, body: String): Boolean {
            if (code == 503) return true
            if (code != 403 && code != 429) return false
            return body.contains("Just a moment", true) ||
                    body.contains("cf-challenge", true) ||
                    body.contains("challenge-platform", true) ||
                    body.contains("_cf_chl", true)
        }

        /**
         * ExtractorLink.quality is an Int in this CloudStream version, so map
         * the source label to Qualities.*.value ourselves instead of relying on
         * SearchQuality internals.
         */
        private fun qualityOf(text: String): Int {
            val t = text.lowercase()
            return when {
                "2160" in t || "4k" in t -> Qualities.P2160.value
                "1440" in t || "2k" in t -> Qualities.P1440.value
                "1080" in t -> Qualities.P1080.value
                "720" in t -> Qualities.P720.value
                "480" in t -> Qualities.P480.value
                "360" in t -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        /**
         * Dependency-free base64url (RFC 4648 §5, no padding). Avoids both
         * android.util.Base64 and java.util.Base64 (API 26+) so it works on
         * every Android version the app supports.
         */
        private fun base64UrlEncode(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder((bytes.size + 2) / 3 * 4)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                sb.append(table[b0 shr 2])
                sb.append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (i + 1 < bytes.size) sb.append(table[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                if (i + 2 < bytes.size) sb.append(table[b2 and 0x3F])
                i += 3
            }
            return sb.toString()
        }
    }

    // ---- v10 RSC data model (escaped JSON inside detail pages) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RscFile(
        val id: String? = null,
        val btnId: String? = null,
        val url: String? = null,
        val label: String? = null,
        val episode: String? = null,
        val filename: String? = null,
        val filesize: String? = null,
        val broken: Boolean? = null,
        val href: String? = null
    )

    /**
     * One playable/downloadable entry harvested from the RSC JSON.
     */
    data class RscEntry(
        val source: SourceData,
        val season: Int?,
        val episode: Int?
    )

    /**
     * v9 — cookie persistence & Cloudflare auto-bypass:
     *
     * The headless WebView sessions (CloudflareKiller / WebViewResolver) store
     * their cookies — cf_clearance, generator sessions, drive login sessions —
     * in the system-wide android.webkit.CookieManager, which persists them
     * across app restarts. authedGet replays those cookies on every plain OkHttp
     * request AND overrides the User-Agent to the WebView's, because
     * cf_clearance is bound to the UA that solved the challenge.
     */
    private suspend fun authedGet(
        url: String,
        referer: String? = null,
        timeout: Long = 30
    ) = run {
        val headers = LinkedHashMap<String, String>(
            mapOf(
                "User-Agent" to (WebViewResolver.webViewUserAgent ?: USER_AGENT),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )
        if (referer != null) headers["Referer"] = referer

        // Replay every cookie the WebView earned for this domain so far.
        val rawCookie = runCatching {
            CookieManager.getInstance()?.getCookie(url)
        }.getOrNull()
        if (!rawCookie.isNullOrBlank()) headers["Cookie"] = rawCookie

        val first = app.get(url, headers = headers, timeout = timeout)
        if (!isCloudflareBlocked(
                first.code,
                runCatching { first.text }.getOrNull().orEmpty()
            )
        ) return@run first

        // Challenge still biting → headless WebView solve. CloudflareKiller
        // stores the fresh cf_clearance in CookieManager, so every later
        // authedGet (and app restart) picks it up automatically.
        app.get(
            url,
            headers = headers,
            referer = referer,
            interceptor = CloudflareKiller(),
            timeout = timeout
        )
    }

    // ---- v10 listing parsers ----

    /**
     * Card anchors on the v6 theme look like:
     *   <a class="... group/card ..." href="/movies/<slug>">
     *     <span ...><img ... srcset="/_next/image?url=https%3A%2F%2Fimg.ol-am.top%2F..."></span>
     *     <span class="line-clamp-3 ...">Full Release Title</span>
     *     <span class="truncate font-mono ...">BluRay · 167 GB · Movies</span>
     *   </a>
     * The Next.js image proxy is decoded back to the direct img.ol-am.top URL.
     */
    private fun cardPoster(img: Element): String? {
        val raw = img.attr("abs:src").ifBlank { img.attr("src") }
        if (raw.contains("/_next/image?url=")) {
            val enc = raw.substringAfter("url=", "").substringBefore("&")
            val dec = runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrNull()
            if (!dec.isNullOrBlank() && dec.startsWith("http")) return dec
        }
        // srcset holds the same proxied URLs — try the largest entry.
        val srcset = img.attr("srcset")
        if (srcset.contains("url=") && raw.isBlank()) {
            val enc = srcset.substringAfter("url=", "").substringBefore("&")
            val dec = runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrNull()
            if (!dec.isNullOrBlank() && dec.startsWith("http")) return dec
        }
        return raw.takeIf { it.startsWith("http") }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        document.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.contains("/movies/") && !href.contains("/tv-series/")) return@forEach
            if (out.containsKey(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach // real cards carry posters
            val title = a.selectFirst("[class*=line-clamp]")?.text()?.trim().orEmpty()
                .ifBlank { a.selectFirst("h3, h2")?.text()?.trim().orEmpty() }
                .ifBlank { href.substringAfterLast('/').replace('-', ' ') }
            val poster = cardPoster(img)
            val isSeries = href.contains("/tv-series/", true)
            out[href] = if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/?page=$page"
        val document = authedGet(url).document
        val home = parseCards(document)
        return newHomePageResponse(request.name ?: this.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = authedGet("$mainUrl/search?q=$encoded").document
        return parseCards(document)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---- v10 RSC detail parsing ----

    /**
     * Index of the '}' that closes the '{' at [start], brace-counting through
     * nested objects. Returns null when unbalanced (should not happen).
     */
    private fun braceEnd(s: String, start: Int): Int? {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun lastMatchBefore(html: String, index: Int, regex: Regex): String? {
        var last: MatchResult? = null
        for (m in regex.findAll(html)) {
            if (m.range.first >= index) break
            last = m
        }
        return last?.groupValues?.get(1)
    }

    /** Quality token present in the leaf label itself ("2160p 4k hdr10+"). */
    private val QUALITY_TOKEN_REGEX = Regex("""(?i)(480|720|1080|1440|2160)\s*p\b|\b4k\b|\bds4k\b""")

    /**
     * Harvest every btnId entry from the escaped RSC JSON, brace-count its
     * enclosing object, unescape it, parse it, and decorate it with the
     * nearest enclosing season. Zip packs are skipped — CloudStream streams,
     * it does not unpack archives.
     */
    private fun extractRscEntries(rawHtml: String): List<RscEntry> {
        val entries = LinkedHashMap<String, RscEntry>()
        for (btnMatch in RSC_BTN_REGEX.findAll(rawHtml)) {
            val btn = btnMatch.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            val objStart = rawHtml.lastIndexOf('{', btnMatch.range.first)
            if (objStart < 0) continue
            val objEnd = braceEnd(rawHtml, objStart) ?: continue
            val clean = rawHtml.substring(objStart, objEnd + 1)
                .replace("\\\"", "\"").replace("\\\\", "\\")
            val file = runCatching { parseJson<RscFile>(clean) }.getOrNull() ?: continue
            if (file.broken == true) continue
            val target = file.url?.takeIf { it.startsWith("http") }
                ?: file.href?.takeIf { it.startsWith("http") }
                ?: "https://links.ol-am.top/$btn"
            val filename = file.filename.orEmpty()
            if (filename.endsWith(".zip", true) ||
                (filename.isBlank() && file.label.orEmpty().contains("zip", true))
            ) continue

            val seasonStr = lastMatchBefore(rawHtml, btnMatch.range.first, RSC_SEASON_REGEX)
            val season = seasonStr?.toIntOrNull()
            val resolution = lastMatchBefore(rawHtml, btnMatch.range.first, RSC_RESOLUTION_REGEX)

            val epNum = file.episode?.trim()?.toIntOrNull()
                ?: Regex("""(?i)[. ]E(?:P)?\s*(\d{1,3})""").find(filename)
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)\bep(?:isode)?\s*(\d{1,3})\b""").find(file.label.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()

            val label = file.label?.takeIf { it.isNotBlank() }
            val name = when {
                label == null -> filename.ifBlank { btn }
                QUALITY_TOKEN_REGEX.containsMatchIn(label) -> label
                else -> listOfNotNull(resolution?.uppercase(), label).joinToString(" ")
            }

            entries.putIfAbsent(
                target,
                RscEntry(SourceData(target, name, file.filesize ?: filename), season, epNum)
            )
        }
        return entries.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = authedGet(url)
        val html = response.text
        val document = response.document

        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val heroTitle = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val title = ogTitle?.takeIf { it.isNotBlank() } ?: heroTitle
            .ifBlank { url.substringAfterLast("/").replace("-", " ") }
        val year = Regex("""\b(19|20)\d{2}\b""").find("$ogTitle $heroTitle")?.value?.toIntOrNull()

        val entries = extractRscEntries(html)
        val isTvSeries = url.contains("/tv-series", true) ||
                entries.any { it.episode != null }

        if (isTvSeries) {
            val withEpisodes = entries.filter { it.episode != null }
            val epMap = withEpisodes.groupBy { (it.season ?: 1) to it.episode!! }
            val episodes = epMap.map { (key, list) ->
                newEpisode(LinkPayload(list.map { it.source }).toJson()) {
                    this.name = "Episode ${key.second}"
                    this.episode = key.second
                    this.season = key.first
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkPayload(entries.map { it.source }).toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
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
     * Given a drive.olamovies.download/file/<id> page URL, extract the embedded
     * `_id` and re-build the direct worker stream URL. Returns null when the
     * id is missing (page layout changed / not logged in).
     */
    private suspend fun mintDriveDirectUrl(drivePageUrl: String, referer: String, fallbackName: String): String? {
        val doc = runCatching { authedGet(drivePageUrl, referer = referer, timeout = 20) }.getOrNull()
            ?: return null
        val html = doc.text
        val id = DRIVE_ID_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val fileName = runCatching { doc.document.selectFirst("h1")?.text()?.trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackName
        val b64 = base64UrlEncode(fileName)
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
                        this.quality = qualityOf(sourceName)
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
                this.quality = qualityOf(sourceName)
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
                val head = authedGet(source.url, referer = "$mainUrl/", timeout = 20)
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
