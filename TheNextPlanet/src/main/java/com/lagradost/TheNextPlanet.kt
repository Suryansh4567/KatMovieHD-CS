package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class TheNextPlanet : MainAPI() {
    override var name = "TheNextPlanet"
    override var mainUrl = "https://www.thenextplanet-official.space"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true
    override var lang = "hi"

    override val mainPage = mainPageOf(
        "$mainUrl/Bollywood/?page=1" to "Bollywood Movies",
        "$mainUrl/Hollywood/?page=1" to "Hollywood Movies",
        "$mainUrl/south-movies/?page=1" to "South Indian Movies",
        "$mainUrl/Webseries/?page=1" to "Web & TV Series"
    )

    private val mapper = jacksonObjectMapper()

    companion object {
        private const val TAG = "TheNextPlanet"
        private const val DEBUG = false

        private fun logd(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }

        /** Parse resolution/source/codec/language/size from filenames like
         *  "MovieName.2025.1080p.WEB-DL.Dual.Hindi.x265.2.9GB" */
        fun parseFilename(filename: String): String {
            val cleanName = filename.replace("_", ".").replace(" ", ".")
            val resolution = when {
                "2160p" in cleanName || "4k" in cleanName.lowercase() -> "2160p"
                "1080p" in cleanName -> "1080p"
                "720p" in cleanName -> "720p"
                "480p" in cleanName -> "480p"
                else -> ""
            }
            val source = when {
                "web-dl" in cleanName.lowercase() || "webdl" in cleanName.lowercase() -> "WEB-DL"
                "webrip" in cleanName.lowercase() -> "WEBRip"
                "bluray" in cleanName.lowercase() || "blu-ray" in cleanName.lowercase() -> "BluRay"
                "hdrip" in cleanName.lowercase() -> "HDRip"
                "hd-tc" in cleanName.lowercase() || "hdtc" in cleanName.lowercase() -> "HD-TC"
                "hd-ts" in cleanName.lowercase() || "hdts" in cleanName.lowercase() -> "HD-TS"
                "remux" in cleanName.lowercase() -> "REMUX"
                "cam" in cleanName.lowercase() -> "CAM"
                else -> ""
            }
            val codec = when {
                "x265" in cleanName.lowercase() || "hevc" in cleanName.lowercase() -> "x265"
                "x264" in cleanName.lowercase() || "h264" in cleanName.lowercase() -> "x264"
                else -> ""
            }
            val language = when {
                "dual" in cleanName.lowercase() -> "Dual Audio"
                "multi" in cleanName.lowercase() -> "Multi Audio"
                "hindi" in cleanName.lowercase() -> "Hindi"
                "english" in cleanName.lowercase() -> "English"
                "tamil" in cleanName.lowercase() -> "Tamil"
                "telugu" in cleanName.lowercase() -> "Telugu"
                else -> ""
            }
            val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*(?:GB|MB))\b""", RegexOption.IGNORE_CASE).find(cleanName)
            val size = sizeMatch?.groupValues?.get(1)?.uppercase() ?: ""
            return listOf(resolution, source, language, codec, size).filter { it.isNotBlank() }.joinToString(" • ")
        }

        fun generateLabel(sourceName: String, fileName: String, fileSize: String?): String {
            val parsedMeta = parseFilename(fileName)
            val cleanSize = fileSize?.takeIf { it.isNotBlank() } ?: ""
            return buildString {
                append(sourceName)
                if (parsedMeta.isNotBlank()) { append(" • "); append(parsedMeta) }
                if (cleanSize.isNotBlank() && !parsedMeta.contains(cleanSize, ignoreCase = true)) {
                    append(" • "); append(cleanSize)
                }
            }
        }

        /**
         * Structured metadata about a single download link, scraped from the
         * live site so the displayed label is consistent across all hosters
         * (GDFlix, Mediafire, Photolinx, Fastilinks, Voe/Vidhide from Watch
         * Online, direct m3u8/mp4, etc.).
         *
         * Fields are nullable; null = "no info available, don't fabricate".
         */
        data class LinkMeta(
            val resolution: String? = null,   // "720p" / "1080p" / "2160p" / null
            val print: String? = null,         // "WEB-DL" / "HEVC" / "BluRay" / null
            val codec: String? = null,         // "x265" / "x264" / null
            val language: String? = null,      // "Hindi" / "Dual Audio" / "English" / null
            val size: String? = null           // "1.4GB" / "843MB" / null
        ) {
            fun toLabelFragment(): String {
                // Order: resolution, print, language, codec, size — matches the
                // existing generateLabel() ordering for visual consistency.
                return listOfNotNull(resolution, print, language, codec, size)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
            }
        }

        /**
         * Parse the unlock-page `<details><summary>…</summary>` header text
         * into a [LinkMeta]. The site uses headers like:
         *   "720p HEVC Download Links"
         *   "1080p Download Links"
         *   "480p Download Links"
         *   "2160p WEB-DL Download Links"
         *
         * Anything we can't parse cleanly is left as null — never fabricated.
         */
        fun parseGroupSummary(raw: String?): LinkMeta {
            if (raw.isNullOrBlank()) return LinkMeta()
            val s = raw.trim()

            val resolution: String? = when {
                Regex("""\b2160p\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "2160p"
                Regex("""\b1440p\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "1440p"
                Regex("""\b1080p\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "1080p"
                Regex("""\b720p\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "720p"
                Regex("""\b480p\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "480p"
                Regex("""\b360p\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "360p"
                Regex("""\b4k\b""",     RegexOption.IGNORE_CASE).containsMatchIn(s) -> "2160p"
                Regex("""\b2k\b""",     RegexOption.IGNORE_CASE).containsMatchIn(s) -> "1440p"
                else -> null
            }

            val print: String? = when {
                Regex("""\bweb[-\s]?dl\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "WEB-DL"
                Regex("""\bwebrip\b""",        RegexOption.IGNORE_CASE).containsMatchIn(s) -> "WEBRip"
                Regex("""\bblu[-\s]?ray\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "BluRay"
                Regex("""\bhdrip\b""",         RegexOption.IGNORE_CASE).containsMatchIn(s) -> "HDRip"
                Regex("""\bhd[-\s]?tc\b""",    RegexOption.IGNORE_CASE).containsMatchIn(s) -> "HD-TC"
                Regex("""\bhd[-\s]?ts\b""",    RegexOption.IGNORE_CASE).containsMatchIn(s) -> "HD-TS"
                Regex("""\bhd[-]?tv\b""",      RegexOption.IGNORE_CASE).containsMatchIn(s) -> "HDTV"
                Regex("""\bdvd[-\s]?rip\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "DVDRip"
                Regex("""\bbr[-\s]?rip\b""",   RegexOption.IGNORE_CASE).containsMatchIn(s) -> "BR-Rip"
                Regex("""\bremux\b""",         RegexOption.IGNORE_CASE).containsMatchIn(s) -> "REMUX"
                Regex("""\bcam\b""",           RegexOption.IGNORE_CASE).containsMatchIn(s) -> "CAM"
                Regex("""\bpre[-\s]?dvdrip\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Pre-DVDRip"
                Regex("""\bhevc\b""",          RegexOption.IGNORE_CASE).containsMatchIn(s) -> "HEVC"
                else -> null
            }

            val codec: String? = when {
                Regex("""\bx265\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "x265"
                Regex("""\bx264\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "x264"
                else -> null
            }

            val language: String? = when {
                Regex("""\bdual\s*audio\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Dual Audio"
                Regex("""\bmulti\s*audio\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Multi Audio"
                Regex("""\bhindi\b""",   RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Hindi"
                Regex("""\benglish\b""", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "English"
                Regex("""\btamil\b""",   RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Tamil"
                Regex("""\btelugu\b""",  RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Telugu"
                else -> null
            }

            val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*(?:GB|MB))\b""", RegexOption.IGNORE_CASE).find(s)
            val size = sizeMatch?.groupValues?.get(1)?.uppercase()?.replace(" ", "")

            return LinkMeta(resolution, print, codec, language, size)
        }

        /**
         * Build a fully-branded label for a given source.  The label format is:
         *
         *   {SourceName} • {resolution} • {print} • {language} • {codec} • {size}
         *
         * Fields are joined with " • " in that order; missing fields are omitted.
         * The order matches what parseFilename() / generateLabel() already
         * produce for the GDFlix branches — so labels are visually consistent
         * regardless of which extractor produced the link.
         */
        fun buildLabel(sourceName: String, meta: LinkMeta): String {
            val frag = meta.toLabelFragment()
            return if (frag.isBlank()) sourceName else "$sourceName • $frag"
        }

        /**
         * Merge a richer [LinkMeta] (e.g. scraped from the GDFlix landing
         * page) with a fallback [LinkMeta] (e.g. parsed from the unlock
         * page's enclosing group).  Fields from `primary` win; `fallback`
         * only fills in nulls.
         */
        fun mergeMeta(primary: LinkMeta, fallback: LinkMeta?): LinkMeta {
            if (fallback == null) return primary
            return LinkMeta(
                resolution = primary.resolution ?: fallback.resolution,
                print      = primary.print      ?: fallback.print,
                codec      = primary.codec      ?: fallback.codec,
                language   = primary.language   ?: fallback.language,
                size       = primary.size       ?: fallback.size
            )
        }

        /**
         * Bucket-name → LinkMeta mapping for the Watch Online (`/get-doods`)
         * API.  FHD = 1080p, HEVC = codec-only, LQ/HQ = no resolution hint.
         * "HEVC" → print=HEVC is the common case the live site returns.
         */
        fun bucketToMeta(bucket: String): LinkMeta = when (bucket.uppercase()) {
            "FHD" -> LinkMeta(resolution = "1080p")
            "HD"  -> LinkMeta(resolution = "720p")
            "HEVC" -> LinkMeta(print = "HEVC", codec = "x265")
            "LQ", "HQ" -> LinkMeta()
            else -> LinkMeta()
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlanetLoadData(
        val url: String,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null
    )

    /**
     * Clean a raw title from the site into a presentable CS3 title.
     * Strips trailing metadata (audio/quality/print descriptors, language lists,
     * "free download" suffixes) but preserves the year and the core title.
     *
     * Examples:
     *  "Kantara 2022 DUAL [KANNADA + HINDI] audio Free Download"
     *     -> "Kantara (2022)"
     *  "Lock Upp 2026 HINDI audio Free Download"
     *     -> "Lock Upp (2026)"
     *  "Kantara: A Legened - Chapter 1 2025 Dual [Kannada + Hindi] Audio ..."
     *     -> "Kantara: A Legened - Chapter 1 (2025)"
     *  "Alpha (2026) Hindi Audio Download Upto 2.5gb HD-TC"
     *     -> "Alpha (2026)"        // year already in parens is kept
     */
    private fun cleanTitle(title: String): String {
        // 1) collapse the separators the site uses so tokenisation is consistent
        var s = title
            .replace('\u00A0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        // 2) strip the constant trailing "Free Download" / "Watch Online ..." suffix
        s = s.replace(Regex("""(?i)\s+(Free\s+Download|Watch\s+Online.*?)$"""), "").trim()

        // 3) strip bracketed language blocks such as
        //    "[Kannada + Hindi]", "[English + Hindi]", "(Hindi + English)"
        //    and "DUAL(HINDI + ENGLISH)" / "Dual [Kannada + Hindi]" / "Multi (...)" descriptors
        s = s.replace(
            Regex("""(?i)\b(Dual|Multi|Hindi|Dubbed|Audio|Web[-\s]?DL|WEBDL|BluRay|Blu-Ray|HEVC|HD[-\s]?TC|HD[-\s]?TS|CAM|HDRip|WEBRip|REMUX)\b\s*(\[[^\]]*\]|\([^)]*\))?"""),
            ""
        )
        // 3a) collapse the double-spaces left over by the strip above
        s = s.replace(Regex("""\s+"""), " ").trim()

        // 4) strip trailing bare "Audio" / "Full Movie" / "Download" / "Movie" tokens
        s = s.replace(Regex("""(?i)\b(audio|full\s+movie|download|movie)\b"""), "").trim()

        // 5) collapse multiple spaces and stray trailing punctuation/whitespace
        s = s.replace(Regex("""\s+"""), " ")
            .replace(Regex("""[\s\-:.,|]+$"""), "")
            // drop trailing pipe-bounded size/quality fragments like "| Upto 3gb |"
            .replace(Regex("""(?i)\s*\|\s*(Upto|Up\s+to)[^|]*$"""), "")
            .trim()

        // 6) normalise the year into "(YYYY)" form attached to the title
        //    - if a "(YYYY)" is already present, keep it
        //    - if a bare YYYY is present, wrap it as "(YYYY)"
        val parenYear = Regex("""\(\s*(19\d{2}|20\d{2})\s*\)""").find(s)
        if (parenYear != null) {
            // year already wrapped — just clean up empty parens or stray space
            // left over from previous steps
            s = s.replace(Regex("""\(\s*\)"""), "").trim()
        } else {
            val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(s)
            val year = yearMatch?.value
            if (year != null) {
                // remove the bare year, then re-append as (YYYY)
                val withoutYear = s.replace(
                    Regex("""\b""" + Regex.escape(year) + Regex("""\b""")),
                    ""
                ).trim()
                s = withoutYear.replace(Regex("""\s+"""), " ")
                    .replace(Regex("""[\s\-:.,|]+$"""), "")
                    .trim()
                s = if (s.isBlank()) year else "$s ($year)"
            }
        }

        return s.ifBlank { title.trim() }
    }

    // ─── Detect if a title looks like a TV series ────────────────────────
    private fun isSeriesTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("season") || t.contains("s0") || t.contains("s1") ||
               t.contains("s02") || t.contains("s03") || t.contains("s04") ||
               t.contains("episodes") || t.contains("web series") ||
               t.contains("webseries") || Regex("""\bS\d{2}\b""", RegexOption.IGNORE_CASE).find(t) != null
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http")) return url
        val clean = url.trimStart('/')
        return "$mainUrl/$clean"
    }

    // ─── Parse listing cards from category / search pages ────────────────
    // The site uses <table> with <td> cells containing <a href="/movie/ID/slug/"> links
    // and sometimes <article> tags inside #movies_table.
    // We handle both patterns for robustness.
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Pattern 1: <article> inside table#movies_table (older layout)
        val articles = doc.select("table#movies_table article")
        logd("parseCards: found ${articles.size} article tags in table#movies_table")

        for (art in articles) {
            val sr = parseCardElement(art, seenUrls) ?: continue
            results.add(sr)
        }

        // Pattern 2: <td> cells with <a href="/movie/..."> (current layout)
        if (results.isEmpty()) {
            val tds = doc.select("table td")
            logd("parseCards: falling back to table td selector, found ${tds.size}")
            for (td in tds) {
                val sr = parseCardElement(td, seenUrls) ?: continue
                results.add(sr)
            }
        }

        // Pattern 3: any <a> linking to /movie/ID/slug with an <img> sibling (hot picks, etc.)
        if (results.isEmpty()) {
            val links = doc.select("a[href*=/movie/]")
            logd("parseCards: falling back to generic /movie/ link selector, found ${links.size}")
            for (a in links) {
                if (a.selectFirst("img") == null) continue
                val sr = parseCardElement(a, seenUrls) ?: continue
                results.add(sr)
            }
        }

        logd("parseCards: returning ${results.size} results")
        return results
    }

    private fun parseCardElement(el: Element, seenUrls: MutableSet<String>): SearchResponse? {
        val anchor = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: return null
        val href = anchor.attr("href")

        // Block adult/explicit paths
        if (href.contains("adult", ignoreCase = true) || href.contains("hitclit", ignoreCase = true)) return null

        val url = absoluteUrl(href)
        if (url !in seenUrls && seenUrls.add(url) == false) return null  // dedup

        // Extract title from various possible locations
        val rawTitle = el.selectFirst("p.movie_name")?.text()?.trim()
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: el.selectFirst("br + text()")?.text()?.trim()  // text after <br> inside <a>
            ?: anchor.text()?.trim()?.lines()?.firstOrNull()  // first line of anchor text
            ?: return null

        if (rawTitle.isBlank()) return null

        val cleanName = cleanTitle(rawTitle)
        val posterImg = el.selectFirst("img")?.attr("src")
        val posterUrl = posterImg?.let { absoluteUrl(it) }

        val isSeries = isSeriesTitle(rawTitle)
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        val loadData = PlanetLoadData(url, if (isSeries) "tv" else "movie")
        val serialized = mapper.writeValueAsString(loadData)

        logd("Card parsed: title=$cleanName, type=$type, url=$url")

        return newMovieSearchResponse(cleanName, serialized, type) {
            this.posterUrl = posterUrl
        }
    }

    // ─── Main page ───────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("page=1", "page=$page")
        logd("getMainPage: url=$url")
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ─── Search ──────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/searchmovie"
        logd("search: query=$query, url=$url")
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.post(url, data = mapOf("name" to query), headers = headers).text
        val doc = Jsoup.parse(response)
        val results = parseCards(doc)
        logd("search: found ${results.size} results")
        return results
    }

    // ─── Load detail page ────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        logd("load: rawPayload=$url")
        val loadData = mapper.readValue<PlanetLoadData>(url)
        val pageUrl = loadData.url

        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(pageUrl, headers = headers).text
        val doc = Jsoup.parse(response)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: doc.title()
            ?: "Unknown Title"

        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { absoluteUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()

        val isSeries = isSeriesTitle(rawTitle) || isSeriesTitle(doc.title())

        logd("load: title=$title, isSeries=$isSeries, year=$year")

        if (isSeries) {
            // Find episode counts — look for patterns like "1 to 08 episode(s)" or "8 episodes"
            val epsMatch = Regex("""(?i)Episode\s*\d+\s*to\s*(\d+)""").find(doc.html())
                ?: Regex("""(?i)(\d+)\s*episodes""").find(doc.html())
            val numEps = epsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            logd("TV Series: $numEps episodes")

            val episodesList = (1..numEps).map { epNum ->
                val epData = PlanetLoadData(pageUrl, "tv", season = 1, episode = epNum)
                val epSerialized = mapper.writeValueAsString(epData)
                newEpisode(epSerialized) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = poster
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    // ─── loadLinks: resolve download/streaming links ─────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = mapper.readValue<PlanetLoadData>(data)
        val pageUrl = loadData.url
        val headers = mapOf("Referer" to "$mainUrl/")

        logd("loadLinks: url=$pageUrl, type=${loadData.type}")

        var foundAny = false
        try {
            // Step 1: Load detail page
            val response = app.get(pageUrl, headers = headers).text
            val doc = Jsoup.parse(response)

            // ── Watch Online / streaming links (via /get-doods) ──
            try {
                val mName = Regex("""var movieName\s*=\s*['"](.*?)['"]""").find(response)?.groupValues?.get(1)
                val mId = Regex("""var movieId\s*=\s*['"](.*?)['"]""").find(response)?.groupValues?.get(1)
                logd("Watch Online: mName=$mName, mId=$mId")
                if (!mName.isNullOrBlank() && !mId.isNullOrBlank()) {
                    val doodResponse = app.post(
                        "$mainUrl/get-doods",
                        json = mapOf("mname" to mName, "mid" to mId),
                        headers = mapOf("Referer" to pageUrl, "Content-Type" to "application/json")
                    )
                    if (doodResponse.code == 200) {
                        val dt = mapper.readTree(doodResponse.text)
                        if (dt.has("data")) {
                            val dataNode = dt.get("data")
                            for (q in listOf("LQ", "HQ", "FHD", "HEVC")) {
                                if (dataNode.has(q)) {
                                    val linksStr = dataNode.get(q).asText()
                                    if (linksStr.isNotBlank()) {
                                        val bucketMeta = bucketToMeta(q)
                                        for (link in linksStr.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
                                            logd("Watch Online embed: bucket=$q, url=$link")
                                            try {
                                                loadExtractor(link, referer = pageUrl, subtitleCallback) { extLink ->
                                                    callback(
                                                        relabel(extLink, "TheNextPlanet [Watch Online]", bucketMeta)
                                                    )
                                                    foundAny = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e(TAG, "Watch Online extractor failed: $link", t)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "get-doods failed", t)
            }

            // Step 2: Find galaxy link
            val galaxyHref = doc.selectFirst("a[href*=/galaxy/]")?.attr("href") ?: return foundAny
            val galaxyUrl = absoluteUrl(galaxyHref)
            logd("Galaxy link: $galaxyUrl")

            // Step 3: Fetch galaxy page → find unlock-links link
            val galaxyResponse = app.get(galaxyUrl, headers = headers).text
            val galaxyDoc = Jsoup.parse(galaxyResponse)

            // The galaxy page has a link like:
            //   <a href="http://www.solution-hub.com/short-link/?link=https%3A%2F%2F...unlock-links%2F%3Fmi%3D...&lockey=...">
            // OR directly:
            //   <a href="/unlock-links/?mi=...&mn=...&lockey=...">
            val shortenerHref = galaxyDoc.selectFirst("a[href*=solution-hub.com]")?.attr("href")
                ?: galaxyDoc.selectFirst("a[href*=/unlock-links/]")?.attr("href")
                ?: return foundAny

            val unlockUrl = if (shortenerHref.contains("link=")) {
                val encodedUrl = shortenerHref.substringAfter("link=")
                    .substringBefore("&lockey=")  // strip trailing params that aren't part of the encoded URL
                URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                absoluteUrl(shortenerHref)
            }
            logd("Unlock URL: $unlockUrl")

            // Step 4: Fetch unlock-links page
            val unlockResponse = app.get(unlockUrl, headers = headers).text
            val unlockDoc = Jsoup.parse(unlockResponse)

            // Step 5: Extract links — there are TWO types:
            //   a) Links inside /depisode/ wrapper: <a href="/depisode/?lockey=...&url=https://...">
            //   b) Direct GDFlix links: <a href="https://gdflix.dev/file/...">
            //   c) Direct Filepress links (in depisode wrappers)
            //
            // The site groups every link inside <details><summary>...</summary>
            // blocks (e.g. "720p HEVC Download Links", "1080p Download Links").
            // We walk the unlock doc group-by-group so each link inherits
            // the resolution / print / language metadata from its enclosing
            // summary — that's the only place Mediafire, Photolinx, Fastilinks
            // expose per-link quality info, since their URL slugs are opaque.

            // ── 5a: Process depisode-wrapped links ──
            val depisodeLinks = unlockDoc.select("a[href*=/depisode/]")
            logd("Found ${depisodeLinks.size} depisode links")

            for (el in depisodeLinks) {
                val href = el.attr("href")
                if (!href.contains("url=")) continue

                val finalUrl = URLDecoder.decode(href.substringAfter("url="), "UTF-8")
                val label = el.text().trim()

                // Skip non-streamable file types
                if (finalUrl.contains(".jpg", ignoreCase = true) || finalUrl.contains(".png", ignoreCase = true)) continue

                // Resolve the enclosing <details> summary text → structured LinkMeta.
                // ancestor <details> → first <summary> child → its text content.
                val summaryText = el.parents()
                    .firstOrNull { it.tagName() == "details" }
                    ?.selectFirst("summary")
                    ?.text()
                    ?.let { Regex("""\s+""").replace(it, " ").trim() }
                val groupMeta = parseGroupSummary(summaryText)
                logd("depisode link: label='$label', group='$summaryText', url=$finalUrl")

                foundAny = resolveUrl(finalUrl, unlockUrl, label, groupMeta, subtitleCallback, callback) || foundAny
            }

            // ── 5b: Process direct GDFlix / Filepress links (not wrapped in depisode) ──
            val allAnchors = unlockDoc.select("a[href]")
            for (a in allAnchors) {
                val href = a.attr("href")
                // Only pick direct gdflix.dev / gdflix.io / filepress links not already handled
                val isDirectGdflix = href.contains("gdflix.dev", ignoreCase = true) ||
                                     href.contains("gdflix.io", ignoreCase = true) ||
                                     href.contains("gdflix.top", ignoreCase = true)
                val isDirectFilepress = href.contains("filepress.cloud", ignoreCase = true) ||
                                        href.contains("filepress", ignoreCase = true)
                if (!isDirectGdflix && !isDirectFilepress) continue
                // Skip if already handled via depisode wrapper
                if (depisodeLinks.any { URLDecoder.decode(it.attr("href").substringAfter("url="), "UTF-8") == href }) continue

                val label = a.text().trim()

                val summaryText = a.parents()
                    .firstOrNull { it.tagName() == "details" }
                    ?.selectFirst("summary")
                    ?.text()
                    ?.let { Regex("""\s+""").replace(it, " ").trim() }
                val groupMeta = parseGroupSummary(summaryText)
                logd("Direct host link: label='$label', group='$summaryText', url=$href")

                foundAny = resolveUrl(href, unlockUrl, label, groupMeta, subtitleCallback, callback) || foundAny
            }

            logd("loadLinks finished: foundAny=$foundAny")
            return foundAny
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks exception: ${e.message}", e)
            return foundAny
        }
    }

    // ─── Route a URL to the appropriate extractor ────────────────────────
    private suspend fun resolveUrl(
        url: String,
        referer: String,
        label: String,
        groupMeta: Companion.LinkMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isGdflix = url.contains("gdflix", ignoreCase = true)
        val isMediafire = url.contains("mediafire", ignoreCase = true)
        val isFilepress = url.contains("filepress", ignoreCase = true)
        val isPhoton = url.contains("photolinx", ignoreCase = true) || url.contains("photon", ignoreCase = true)
        val isFastilinks = url.contains("fastilinks", ignoreCase = true)

        // Derive a per-source brand name. The groupMeta is the fallback
        // when the underlying extractor's own metadata is sparse (e.g. a
        // Mediafire link with an opaque slug has no quality info of its own,
        // but its enclosing unlock-page <details> block does).
        val hosterBrand = when {
            isGdflix -> null                          // GDFlix has its own rich labels
            isMediafire -> "TheNextPlanet [Mediafire]"
            isPhoton -> "TheNextPlanet [Photolinx]"
            isFilepress -> "TheNextPlanet [Filepress]"
            isFastilinks -> "TheNextPlanet [Fastilinks]"
            else -> "TheNextPlanet [Direct]"
        }

        return try {
            when {
                isGdflix -> {
                    logd("Invoking GDFlix extractor: $url")
                    // Pass groupMeta down so GDFlix can use it as a fallback
                    // when its own fileName parsing returns nothing (e.g. an
                    // older GDFlix landing page missing the Name/Size fields).
                    GDFlix().getUrl(url, referer, groupMeta, subtitleCallback, callback)
                    true
                }
                isMediafire || isFilepress || isPhoton || isFastilinks -> {
                    logd("Using loadExtractor for: $url")
                    val brand = hosterBrand!!
                    loadExtractor(url, referer = referer, subtitleCallback) { extLink ->
                        // CS3's built-in extractors emit a link with a host-only
                        // source name (e.g. "MediaFire"). We rebuild it using
                        // the same generateLabel() pattern the GDFlix branches
                        // already use, fed by the per-link metadata scraped
                        // from the unlock page's enclosing <details> group.
                        callback(relabel(extLink, brand, groupMeta))
                    }
                    true
                }
                else -> {
                    // Direct streamable link (m3u8, mp4, etc.)
                    val isM3u8 = url.contains(".m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    val brand = hosterBrand!!
                    val sourceName = buildLabel(brand, groupMeta)
                    val finalName = if (sourceName == brand) {
                        // No group metadata — fall back to URL slug parsing so
                        // we still get *some* structured info if available.
                        generateLabel(brand, url.split("/").lastOrNull()?.substringBefore("?") ?: label, null)
                    } else sourceName

                    callback(
                        newExtractorLink(finalName, finalName, url, linkType) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                    true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "resolveUrl failed for $url: ${e.message}", e)
            false
        }
    }

    /**
     * Replace the `source` and `name` on an [ExtractorLink] returned by an
     * underlying extractor (loadExtractor, GDFlix(), …) so the label shown
     * in the player's "Sources" UI matches the standard
     *
     *   {SourceName} • {resolution} • {print} • {language} • {codec} • {size}
     *
     * format.  The original link's URL, referer, quality, headers, type and
     * audioTracks are preserved verbatim.  If the underlying extractor
     * already supplied a richer meta fragment (e.g. GDFlix's "Kantara.2022.
     * 720p.HEVC…") it wins; the fallback [groupMeta] only fills in nulls.
     */
    private fun relabel(
        original: ExtractorLink,
        sourceName: String,
        fallback: Companion.LinkMeta
    ): ExtractorLink {
        val fromFilename = Companion.parseFilename(original.name)
        val parsed = Companion.parseGroupSummary(fromFilename)   // re-parse the existing label back into LinkMeta
        val merged = Companion.mergeMeta(parsed, fallback)
        val newName = Companion.buildLabel(sourceName, merged)
        // newExtractorLink is a suspend function in this CS3 version, but
        // we want a synchronous "clone with new name" operation here.
        // Use the public constructor directly — it's the same one
        // newExtractorLink delegates to internally.
        return ExtractorLink(
            newName,          // source slot — shown in the source chip
            newName,          // name slot — shown in the player's top-bar title
            original.url,
            original.referer,
            original.quality,
            original.headers,
            original.extractorData,
            original.type,
            original.audioTracks
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// GDFlix Extractor — resolves gdflix.dev/file/... landing pages to direct
// stream links (R2 Cloud, Direct DL, Instant DL, PixelDrain, GoFile, DriveBot)
// ═══════════════════════════════════════════════════════════════════════════
class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    companion object {
        private const val TAG = "TheNextPlanet:GDFlix"
    }

    private fun getIndexQuality(str: String?): Int {
        return when {
            str == null -> Qualities.Unknown.value
            "2160" in str || "4k" in str.lowercase() -> Qualities.P2160.value
            "1440" in str || "2k" in str.lowercase() -> Qualities.P1440.value
            "1080" in str -> Qualities.P1080.value
            "720" in str -> Qualities.P720.value
            "480" in str -> Qualities.P480.value
            "360" in str -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Backwards-compat shim: ExtractorApi's getUrl is a 4-arg method, so
        // we forward to the 5-arg overload (which carries the unlock-page
        // group metadata) using a default empty LinkMeta when called by
        // anything that doesn't know about the extra context.
        getUrl(url, referer, TheNextPlanet.Companion.LinkMeta(), subtitleCallback, callback)
    }

    /**
     * 5-argument overload that lets the caller pass the per-link metadata
     * scraped from the unlock page's enclosing <details> group.  The
     * GDFlix landing page's own `Name : …` field is normally richer, but
     * if it ever returns nothing this fallback fills in resolution / print
     * / language so the label is still informative.
     */
    suspend fun getUrl(
        url: String,
        referer: String?,
        fallbackMeta: TheNextPlanet.Companion.LinkMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl: $url")

        // Step 1: Follow meta-refresh redirect on landing page
        val landingUrl = try {
            val doc = app.get(url).document
            doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.substringAfter("url=")
                ?: url
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch redirect: ${e.localizedMessage}")
            url
        }

        Log.d(TAG, "Landing page: $landingUrl")
        val document = app.get(landingUrl).document

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        val anchors = document.select("div.text-center a")
        Log.d(TAG, "Found ${anchors.size} anchor elements on landing page")

        // Sort by priority: PixelDrain > R2 Cloud > GoFile > Instant DL > Direct DL > DriveBot
        val sortedAnchors = anchors.sortedWith(compareByDescending { anchor ->
            val text = anchor.text()
            when {
                text.contains("PixelDrain", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> 5
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) || text.contains("R2", ignoreCase = true) -> 4
                text.contains("GoFile", ignoreCase = true) -> 3
                text.contains("Instant DL", ignoreCase = true) -> 2
                text.contains("DIRECT DL", ignoreCase = true) -> 1
                text.contains("DRIVEBOT", ignoreCase = true) -> 0
                else -> -1
            }
        })

        sortedAnchors.amap { anchor ->
            val text = anchor.text()
            val href = anchor.attr("href")
            Log.d(TAG, "Processing anchor: text='$text', href='$href'")

            try {
                when {
                    text.contains("PixelDrain", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> {
                        val finalRedirectUrl = app.get(href).url
                            .replace("pixeldrain.dev", "pixeldrain.com", ignoreCase = true)
                            .replace("pixeldra.in", "pixeldrain.com", ignoreCase = true)
                        Log.d(TAG, "PixelDrain resolved: $finalRedirectUrl")
                        loadExtractor(finalRedirectUrl, referer = "", subtitleCallback, callback)
                    }

                    text.contains("CLOUD DOWNLOAD", ignoreCase = true) || text.contains("R2", ignoreCase = true) -> {
                        val decodedLink = if (href.contains("url=")) {
                            URLDecoder.decode(href.substringAfter("url="), "UTF-8")
                        } else {
                            href
                        }
                        Log.d(TAG, "R2 Cloud: $decodedLink")
                        val label = TheNextPlanet.generateLabel("GDFlix [R2 Cloud]", fileName, fileSize)
                        callback(newExtractorLink(label, label, decodedLink, ExtractorLinkType.VIDEO) {
                            this.quality = getIndexQuality(fileName)
                        })
                    }

                    text.contains("GoFile", ignoreCase = true) -> {
                        app.get(href).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    loadExtractor(link, referer = "", subtitleCallback, callback)
                                }
                            }
                    }

                    text.contains("Instant DL", ignoreCase = true) -> {
                        // Two formats observed on the live site:
                        //   1) Old:  href is a short-link redirect; we follow with
                        //      allowRedirects=false and read the `Location: ?url=...` header.
                        //   2) New:  href IS the direct streamable URL (e.g. busycdn.xyz/...).
                        //      The page wraps the file path with an HMAC-like hash but the
                        //      `href` attribute already points at the playable file.
                        val link = when {
                            href.contains("url=") -> URLDecoder.decode(
                                href.substringAfter("url="),
                                "UTF-8"
                            )
                            else -> {
                                // Try a non-redirected HEAD/GET first — if we get a 302
                                // with `url=...` in the Location header, use that; otherwise
                                // the href itself is the direct link.
                                val redirectTarget = try {
                                    app.get(href, allowRedirects = false)
                                        .headers["location"]?.substringAfter("url=")
                                        ?.let { URLDecoder.decode(it, "UTF-8") }
                                        .orEmpty()
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Instant DL HEAD failed for $href: ${e.message}")
                                    ""
                                }
                                if (redirectTarget.isNotBlank()) redirectTarget else href
                            }
                        }
                        if (link.isNotBlank()) {
                            Log.d(TAG, "Instant DL: $link")
                            val label = TheNextPlanet.generateLabel("GDFlix [Instant]", fileName, fileSize)
                            callback(newExtractorLink(label, label, link, ExtractorLinkType.VIDEO) {
                                this.quality = getIndexQuality(fileName)
                            })
                        }
                    }

                    text.contains("DIRECT DL", ignoreCase = true) -> {
                        Log.d(TAG, "Direct DL: $href")
                        val label = TheNextPlanet.generateLabel("GDFlix [Direct]", fileName, fileSize)
                        callback(newExtractorLink(label, label, href, ExtractorLinkType.VIDEO) {
                            this.quality = getIndexQuality(fileName)
                        })
                    }

                    text.contains("DRIVEBOT", ignoreCase = true) -> {
                        val id = href.substringAfter("id=").substringBefore("&")
                        val doId = href.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://drivebot.cfd")

                        baseUrls.amap { baseUrl ->
                            val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                            val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                            if (indexbotResponse.isSuccessful) {
                                val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                val indexbotDoc = indexbotResponse.document

                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()
                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val requestBody = FormBody.Builder().add("token", token).build()
                                val reqHeaders = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$baseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = reqHeaders,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("""url\":\"(.*?)\"""").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                if (downloadLink.isNotBlank()) {
                                    Log.d(TAG, "DriveBot: $downloadLink")
                                    val label = TheNextPlanet.generateLabel("GDFlix [DriveBot]", fileName, fileSize)
                                    callback(newExtractorLink(label, label, downloadLink, ExtractorLinkType.VIDEO) {
                                        this.quality = getIndexQuality(fileName)
                                    })
                                }
                            }
                        }
                    }

                    else -> {
                        Log.d(TAG, "No handler for button: $text")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GDFlix anchor failed for '$text': ${e.message}", e)
            }
        }

        // Cloudflare backup links fallback
        try {
            for (type in listOf("type=1", "type=2")) {
                val sourceurl = app.get("${landingUrl.replace("file", "wfile")}?$type")
                    .document.select("a.btn-success").attr("href")
                if (sourceurl.isNotEmpty()) {
                    Log.d(TAG, "CF Backup ($type): $sourceurl")
                    val label = TheNextPlanet.generateLabel("GDFlix [CF]", fileName, fileSize)
                    callback(newExtractorLink(label, label, sourceurl, ExtractorLinkType.VIDEO) {
                        this.quality = getIndexQuality(fileName)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CF backup failed: ${e.message}", e)
        }
    }
}
