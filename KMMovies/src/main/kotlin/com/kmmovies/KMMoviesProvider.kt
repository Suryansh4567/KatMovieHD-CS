package com.kmmovies

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * KMMovies (kmmovies.lol) — Professional-grade CloudStream 3 provider.
 *
 * ═══════════════════════════════════════════════════════════
 *  ARCHITECTURE — 5-Layer Professional Design
 * ═══════════════════════════════════════════════════════════
 *
 *  Layer 1: DYNAMIC DOMAIN
 *    domains.json on GitHub → auto-updating mirrors.
 *    Extension fetches on startup; 6-hour cache.
 *    Users NEVER need to reinstall when domain changes.
 *
 *  Layer 2: DOOPLAY + WORDPRESS DUAL-MODE PARSER
 *    KMMovies uses the Dooplay theme. We try Dooplay-specific
 *    selectors first (#playeroptionsul, .se-c, ul.episodios),
 *    then fall back to generic WordPress selectors (article,
 *    .entry-content, h1.entry-title). This dual-mode approach
 *    survives any theme change.
 *
 *  Layer 3: STATEFUL DOCUMENT WALKER
 *    Iterates through WordPress content in DOM order.
 *    Tracks "Episode N" / "Season X" / quality labels in
 *    h3/h4/strong/p tags, and buckets all following download
 *    links into the correct (season, episode) slot.
 *    Also handles <table> and <ul><li> layouts.
 *
 *  Layer 4: LINK CHAIN CRACKER
 *    Recursive resolver using allowRedirects = false.
 *    Follows redirect chains through mclinks, linkszilla,
 *    magiclinks, kmphotos, skydrop — with Referer + UA spoof.
 *    Supports: base64-encoded URLs, meta-refresh redirects,
 *    JavaScript variable extraction, and DooPlay AJAX iframe
 *    loading via wp-admin/admin-ajax.php.
 *
 *  Layer 5: COSMETIC POLISH
 *    cleanTitle() strips all technical tags.
 *    Smart labels: "HubCloud - 4K UHD - 12GB [Dolby Vision]"
 *    TMDB enrichment: 4K backdrops, cast, episode names.
 *    Quality tagging from both button text AND parent headers.
 *
 * ═══════════════════════════════════════════════════════════
 *  KMMovies-specific redirectors:
 *    - DooPlay AJAX: POST /wp-admin/admin-ajax.php → iframe embed
 *    - magiclinks.lol → page with skydrop/kmphotos links
 *    - skydrop.sbs/download.php?id=XXX → API → JSON with video URL
 *    - kmphotos.cv/online.php?file=XXX → 302 → R2 stream URL
 *    - kmphotos.cv/download99.php?file=XXX → signed download URL
 *    - mclinks.xyz → 302 redirect chain → final hoster
 *    - linkszilla → 302 redirect chain → final hoster
 *
 *  CloudStream's WebViewResolver handles Cloudflare automatically.
 * ═══════════════════════════════════════════════════════════
 */
class KMMoviesProvider : MainAPI() {

    override var mainUrl: String = runBlocking { KMMoviesPlugin.getActiveMainUrl() }
    override var name = "KMMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    companion object {
        private const val TAG = "KMMovies"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // Updated TMDB API key per user specification
        private const val TMDB_API_KEY = "b0a40f4a5d03e4a1f128e5d89d4a2b32"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /** Maximum redirect chain depth to prevent infinite loops. */
        private const val MAX_REDIRECT_DEPTH = 5

        /** KMPhotos R2/CDN link headers — realistic mobile browser headers that
         *  bypass Cloudflare bot detection on R2 bucket URLs. Without these,
         *  Cloudflare returns 403 HTML and ExoPlayer throws
         *  ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003). */
        private val KMPHOTOS_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36",
            "Accept" to "video/*, application/*;q=0.9",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to "https://z1.kmphotos.cv/"
        )

        // ═══════════════════════════════════════════════════
        //  LINK HOST REGEX — Known stream/download hosts
        // ═══════════════════════════════════════════════════

        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // KMMovies-specific redirectors (w1/w2/z1 subdomain variants)
                    """w\d\.magiclinks\.lol|magiclinks\.lol|episodes\.magiclinks\.lol|""" +
                    """w\d\.skydrop\.sbs|skydrop\.sbs|w\d\.skydrop\.live|skydrop\.live|""" +
                    """z\d\.kmphotos\.cv|kmphotos\.cv|kmphotos|""" +
                    // Dooplay link protection service
                    """savelinks\.me|linkstaker\.xyz|protectedlinks\.[a-z]+|""" +
                    // HubCloud / Hubdrive family
                    """hubcloud\.[a-z]+|hubcloud\.link|hubcdn|hubstream|hubdrive\.|katdrive|""" +
                    // GDFlix family
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot|gdmirror|""" +
                    // Generic cloud/file-share hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|mega\.nz|mega\.co|""" +
                    // Streaming/video hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // Hindi-dub specific
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|techkit|""" +
                    // Cloudflare R2 direct links
                    """r2\.dev|""" +
                    // Known shorteners/redirectors
                    """mclinks\.|hblinks\.|obsession\.buzz|hdstream4u|linkszilla|""" +
                    // KMHD link service (some pages cross-reference)
                    """links\.kmhd\.|gd\.kmhd|kmhd\.net|""" +
                    // Additional common mirrors
                    """gdrive|gdurl|katmovie|katdrive|kmhd""" +
                    """)"""
        )

        /** Hosts that are NEVER stream sources — filtered out during permissive pass.
         *  NOTE: kmmovies.* domains are NOT here because /links/{shortcode}/ URLs
         *  on the same domain are Dooplay download redirect pages that lead to
         *  actual hoster links via savelinks.me. We need to follow them. */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """images\.kmphotos|""" +  // image CDN, not stream source
                    """gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|/comment|""" +
                    // kmmovies pages that are NOT download links
                    """/category/|/page/|/tag/|/genre/|/year/|/director/|/writer/|""" +
                    """wp-content|wp-includes|wp-json|wp-login""" +
                    """)"""
        )

        /** Dooplay internal /links/ URL pattern — these redirect to savelinks.me → actual hoster */
        private val DOOPLAY_LINKS_REGEX = Regex("""/links/[a-zA-Z0-9]+""")

        // ═══════════════════════════════════════════════════
        //  EPISODE / SEASON DETECTION REGEX
        // ═══════════════════════════════════════════════════

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" / "Ep 3" / "EPiSODE 5" */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\bEPi?SODE\s*[-–:#.]?\s*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4" inside a header. */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Header tags that can hold "Episode N" / "Season N" / quality labels. */
        private val LABEL_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "span", "td", "li")

        /** Negative phrases that should never be treated as episode markers. */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link", "download all", "season pack", "complete pack"
        )

        // ═══════════════════════════════════════════════════
        //  QUALITY / SIZE / CODEC DETECTION
        // ═══════════════════════════════════════════════════

        /** Quality regex — priority ordered (4K first to avoid false positives on 1080p). */
        private val QUALITY_REGEX = Regex(
            """(?i)(2160p|4k[\s_-]?uhd|4k|1080p\s*hevc|1080p\s*x264|1080p\s*av1|1080p\s*10bit|""" +
                    """1080p|720p\s*10bit|720p|480p|hdr|dv|dolby[_\s]?vision|""" +
                    """web-dl|webdl|hdts|hdtc|hdrip|brrip|bdrip|dvdrip|predvd|camrip|cam|ts|hdcam)"""
        )

        /** File size regex — matches "2.4GB", "12 GB", "800MB", "1.5 GB" etc. */
        private val SIZE_REGEX = Regex("""(?i)(\d+[\.,]?\d*)\s*(GB|MB|TB)""")

        /** Codec/format regex for smart labels. */
        private val CODEC_REGEX = Regex(
            """(?i)(hevc|x264|x265|av1|h264|h265|10bit|10[\s_-]?bit|6ch|dd[\s\.]?5[\.\s]?1|atmos)"""
        )

        /** Audio language regex for smart labels. */
        private val AUDIO_REGEX = Regex(
            """(?i)(hindi|english|dual[_\s]?audio|org[_\s]?dub|tamil|telugu|korean|japanese|chinese|esubs?)"""
        )

        /** Known hoster display names for smart labels. */
        private val HOSTER_NAMES = mapOf(
            "hubcloud" to "HubCloud",
            "hubcloud.link" to "HubCloud",
            "hubdrive" to "HubDrive",
            "hubcdn" to "HubCDN",
            "katdrive" to "KatDrive",
            "gdflix" to "GDFlix",
            "gdlink" to "GDLink",
            "gdtot" to "GDTot",
            "gdmirror" to "GDMirror",
            "gdrive" to "GDrive",
            "drive.google" to "Google Drive",
            "pixeldrain" to "PixelDrain",
            "gofile" to "GoFile",
            "mediafire" to "MediaFire",
            "1fichier" to "1Fichier",
            "mega.nz" to "MEGA",
            "send.cm" to "SendCM",
            "streamtape" to "StreamTape",
            "filemoon" to "FileMoon",
            "mixdrop" to "MixDrop",
            "doodstream" to "DoodStream",
            "mclinks" to "McLinks",
            "hblinks" to "HBLinks",
            "skydrop" to "SkyDrop",
            "kmphotos" to "KMPhotos",
            "magiclinks" to "MagicLinks",
            "r2.dev" to "Cloudflare R2",
            "fuckingfast" to "FuckingFast",
            "driveseed" to "DriveSeed",
            "driveleech" to "DriveLeech"
        )

        /** Get a friendly display name for a hoster from its URL. */
        fun getHosterName(url: String): String {
            val host = Regex("""(?i)://([a-z0-9.-]+)""").find(url)?.groupValues?.get(1) ?: return "Unknown"
            for ((key, name) in HOSTER_NAMES) {
                if (host.contains(key, ignoreCase = true)) return name
            }
            return host.replace("www.", "").substringBefore(".").replaceFirstChar { it.uppercase() }
        }
    }

    // ═══════════════════════════════════════════════════
    //  MAIN PAGE & SEARCH
    // ═══════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "page/" to "Latest",
        "category/movies/page/" to "Movies",
        "category/tv-series/page/" to "TV Series",
        "category/bollywood/page/" to "Bollywood",
        "category/hollywood/page/" to "Hollywood",
        "category/dual-audio/page/" to "Dual Audio",
        "category/hindi/page/" to "Hindi",
        "category/4k/page/" to "4K",
        "category/south/page/" to "South",
        "category/kdrama/page/" to "KDrama",
        "category/anime/page/" to "Anime",
        "category/tamil/page/" to "Tamil",
        "category/telugu/page/" to "Telugu"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val url = "$mainUrl/${request.data}$page/"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return newHomePageResponse(request.name, parseListing(doc), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val encoded = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$encoded"
                  else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = headers, timeout = 30).document
        val results = parseListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    // ═══════════════════════════════════════════════════
    //  LISTING PARSER — Dooplay + WordPress dual-mode
    // ═══════════════════════════════════════════════════

    /**
     * Parse listing pages (homepage, category, search).
     * Multi-fallback approach:
     *   0. KMMovies custom theme: article.movie-card cards
     *   1. Dooplay theme: article.item cards
     *   2. WordPress: li[id^=post-] items
     *   3. Generic: article / .post elements
     *   4. Last resort: any <a> wrapping an <img>
     */
    private fun parseListing(doc: Document): List<SearchResponse> {
        // KMMovies custom theme: article.movie-card
        val kmCards = doc.select("article.movie-card").mapNotNull { it.toSearchResultKMMovies() }
        if (kmCards.isNotEmpty()) return kmCards.distinctBy { it.url }

        // Dooplay theme: article.item cards
        val dooplay = doc.select("article.item").mapNotNull { it.toSearchResultDooplay() }
        if (dooplay.isNotEmpty()) return dooplay.distinctBy { it.url }

        // WordPress: li[id^=post-] items
        val wpItems = doc.select("li[id^=post-]").mapNotNull { it.toSearchResultFromItem() }
        if (wpItems.isNotEmpty()) return wpItems.distinctBy { it.url }

        // Generic: article/.post card layout
        val direct = doc.select("article, .post").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }

        // Last resort: any anchor wrapping an <img>
        return doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    /** KMMovies custom theme card: article.movie-card */
    private fun Element.toSearchResultKMMovies(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val rawTitle = anchor.attr("title").ifBlank {
            selectFirst(".movie-title")?.text() ?: ""
        }.ifBlank {
            anchor.text()
        }.ifBlank { return null }
        val img = selectFirst("img.poster, img")
        val poster = img?.absUrl("src")?.ifBlank { null }
            ?: img?.absUrl("data-src")?.ifBlank { null }
        val badge = selectFirst(".badge-left")?.text()

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle, badge)
        }
    }

    /** Dooplay theme card: article.item > h3 > a + img */
    private fun Element.toSearchResultDooplay(): SearchResponse? {
        val anchor = selectFirst("h3 a[href]") ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val rawTitle = anchor.attr("title").ifBlank { anchor.text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    private fun Element.toSearchResultFromItem(): SearchResponse? {
        val titleAnchor = selectFirst("h2 a[href], h3 a[href], .post-title a[href], .title a[href]")
            ?: selectFirst("div.post-content a[href]")
            ?: return null
        val href = titleAnchor.attr("href").ifBlank { return null }
        val rawTitle = titleAnchor.attr("title")
            .ifBlank { titleAnchor.text() }
            .ifBlank { return null }

        val poster = select("div.post-thumb img, div.thumbnail img").firstNotNullOfOrNull { img ->
            img.absUrl("data-src").ifBlank { img.absUrl("src") }.takeIf { it.isNotBlank() }
        } ?: selectFirst("img")?.let {
            it.absUrl("data-src").ifBlank { it.absUrl("src") }.ifBlank { null }
        }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2 a, h1 a, .entry-title a")
            ?: selectFirst("a")
            ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val rawTitle = anchor.attr("title")
            .ifBlank { anchor.text() }
            .ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        if (!href.contains("kmmovies", ignoreCase = true) &&
            !href.contains(mainUrl, ignoreCase = true)) return null

        val bad = listOf(
            "/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-",
            "/about", "/contact", "/how-to", "/join-", "/genre/",
            "/trending", "/browse", "/year/", "/director/", "/writer/"
        )
        if (bad.any { href.contains(it, ignoreCase = true) }) return null
        if (href.trimEnd('/').equals(mainUrl.trimEnd('/'), ignoreCase = true)) return null

        val rawTitle = attr("title").ifBlank { text() }.ifBlank { return null }
        val img = selectFirst("img")
        val poster = img?.absUrl("data-src")?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        return newMovieSearchResponse(
            cleanTitle(rawTitle),
            fixUrl(href),
            guessTvType(rawTitle)
        ) {
            this.posterUrl = poster
            this.quality = detectSearchQuality(rawTitle)
        }
    }

    // ═══════════════════════════════════════════════════
    //  load() — THE MAIN PER-TITLE PAGE
    // ═══════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        mainUrl = KMMoviesPlugin.getActiveMainUrl()
        val doc = app.get(url, headers = headers, timeout = 30).document

        // ── Extract metadata using DUAL-MODE selectors ──
        // Dooplay first, then generic WordPress fallback

        val rawTitle = doc.selectFirst(".hero-title, .sheader .data h1, h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()

        val sitePoster = doc.selectFirst(".hero-poster, .sheader .poster img, .post-thumbnail img")?.absUrl("src")
            ?: doc.selectFirst("img.poster")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".entry-content img, article img")?.absUrl("src")

        val sitePlot = doc.selectFirst(".hero-description")?.text()
            ?: doc.select(".wp-content p, .entry-content p")
                .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cleanedTitle = cleanTitle(rawTitle)
        // BUG FIX: Use BOTH title-based detection AND page structure detection.
        // The live kmmovies.lol site uses div.download-category.tv-series as a
        // definitive structural indicator that the page is a TV series, which is
        // more reliable than guessing from the title alone.
        val isSeriesByTitle = guessTvType(rawTitle) == TvType.TvSeries
        val isSeriesByPage = doc.selectFirst("div.download-category.tv-series") != null
        val isSeries = isSeriesByPage || isSeriesByTitle

        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        val yearHint = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // ── Enrich with TMDB / Cinemeta in parallel ──
        val (tmdb, cine) = coroutineScope {
            val tmdbDef = async {
                val id = resolveTmdbId(imdbId, cleanedTitle, isSeries, yearHint)
                if (id != null) fetchTmdbDetails(id, isSeries)?.let {
                    it to (if (isSeries) fetchTmdbSeason(id, titleSeason) else null)
                } else null
            }
            val cineDef = async {
                if (imdbId != null) fetchCinemeta(imdbId, isSeries) else null
            }
            tmdbDef.await() to cineDef.await()
        }
        val tmdbMeta = tmdb?.first
        val tmdbSeason = tmdb?.second

        val title = tmdbMeta?.title ?: cine?.name ?: cleanedTitle
        val poster = tmdbMeta?.poster ?: cine?.poster ?: sitePoster
        val backdrop = tmdbMeta?.backdrop ?: cine?.background ?: poster
        val plot = tmdbMeta?.overview ?: cine?.description ?: sitePlot
        val year = tmdbMeta?.year
            ?: cine?.year?.substringBefore("\u2013")?.toIntOrNull()
            ?: yearHint
        val rating = tmdbMeta?.rating ?: cine?.imdbRating?.let { Score.from10(it) }
        val tags = (tmdbMeta?.genres ?: cine?.genre ?: emptyList()).distinct()
        val actorData = tmdbMeta?.actors ?: emptyList()
        val cineActors = cine?.cast ?: emptyList()
        val trailer = tmdbMeta?.trailer
        val totalSeasons = tmdbMeta?.totalSeasons

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries imdb=$imdbId titleSeason=$titleSeason")

        // ── Extract trailer from hero section if available ──
        val heroTrailer = doc.selectFirst("a.open-trailer")?.attr("data-trailer-url")
            ?: doc.selectFirst(".hero-actions a[href*=youtube]")?.attr("href")
        val effectiveTrailer = trailer ?: heroTrailer

        // ── Try DooPlay AJAX player options first (may not exist on new theme) ──
        val ajaxLinks = extractDooPlayAjaxLinks(doc)

        if (isSeries) {
            val episodes = discoverEpisodes(doc, titleSeason, tmdbSeason, cine, ajaxLinks)
            Log.d(TAG, "load() discovered ${episodes.size} episodes")

            if (episodes.isEmpty()) {
                Log.w(TAG, "0 episodes discovered, emitting as movie-style links")
                val links = collectAllPlayableLinks(doc)
                return newMovieLoadResponse(title, url, TvType.Movie, links) {
                    applyCommonMeta(this, poster, backdrop, plot, year, tags,
                        actorData, cineActors, rating, effectiveTrailer, imdbUrl)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, effectiveTrailer, imdbUrl)
                if (totalSeasons != null && totalSeasons > 1) {
                    addSeasonNames((1..totalSeasons).map { "Season $it" })
                }
            }
        } else {
            // For movies: combine AJAX links + download section + article body links
            val bodyLinks = collectAllPlayableLinks(doc)
            val allLinks = buildString {
                if (ajaxLinks.isNotEmpty()) {
                    append(ajaxLinks.joinToString("\n"))
                    if (bodyLinks.isNotBlank()) append("\n")
                }
                if (bodyLinks.isNotBlank()) append(bodyLinks)
            }
            Log.d(TAG, "load() movie: allLinks length=${allLinks.length}, ajax=${ajaxLinks.size}")
            return newMovieLoadResponse(title, url, TvType.Movie, allLinks) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, effectiveTrailer, imdbUrl)
            }
        }
    }

    private suspend fun applyCommonMeta(
        target: LoadResponse,
        poster: String?,
        backdrop: String?,
        plot: String?,
        year: Int?,
        tags: List<String>,
        actorData: List<ActorData>,
        cineActors: List<String>,
        rating: Score?,
        trailer: String?,
        imdbUrl: String?
    ) {
        target.posterUrl = poster
        target.backgroundPosterUrl = backdrop
        target.plot = plot
        target.year = year
        target.tags = tags
        target.score = rating
        if (actorData.isNotEmpty()) target.actors = actorData
        else if (cineActors.isNotEmpty()) target.addActors(cineActors)
        target.addTrailer(trailer)
        imdbUrl?.let {
            target.addImdbUrl(it)
            Regex("""(tt\d+)""").find(it)?.groupValues?.get(1)?.let { id ->
                target.addImdbId(id)
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  DOOPLAY AJAX — Extract iframe/player URLs
    // ═══════════════════════════════════════════════════

    /**
     * Dooplay themes store video player options in #playeroptionsul > li
     * with data-post and data-nume attributes. These point to an AJAX
     * endpoint that returns an iframe embed URL.
     */
    private suspend fun extractDooPlayAjaxLinks(doc: Document): List<String> {
        // Try both selectors: the class-based .dooplay_player_option and the generic #playeroptionsul > li
        val playerOptions = doc.select("#playeroptionsul li.dooplay_player_option")
        if (playerOptions.isEmpty()) {
            // Fallback: any li inside #playeroptionsul
            val fallback = doc.select("#playeroptionsul > li")
            if (fallback.isEmpty()) return emptyList()
            return extractAjaxFromOptions(fallback)
        }
        return extractAjaxFromOptions(playerOptions)
    }

    private suspend fun extractAjaxFromOptions(options: Elements): List<String> {
        val links = mutableListOf<String>()
        for (option in options) {
            val dataPost = option.attr("data-post").ifBlank { continue }
            val dataNume = option.attr("data-nume").ifBlank { "1" }
            val dataType = option.attr("data-type").ifBlank { "movie" }

            // Skip trailer-only options (they just embed YouTube)
            if (dataNume.equals("trailer", ignoreCase = true)) continue

            try {
                // Method 1: admin-ajax.php (most common Dooplay AJAX endpoint)
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/"
                    ),
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to dataPost,
                        "nume" to dataNume,
                        "type" to dataType
                    ),
                    timeout = 15
                )

                val json = response.text
                if (json.isNotBlank()) {
                    val jsonObj = JSONObject(json)
                    var embedUrl = jsonObj.optString("embed_url", "")

                    // Try alternative JSON keys
                    if (embedUrl.isBlank()) {
                        embedUrl = jsonObj.optString("iframe", "")
                    }
                    if (embedUrl.isBlank()) {
                        embedUrl = jsonObj.optString("url", "")
                    }

                    if (embedUrl.isNotBlank()) {
                        // Extract iframe src from embed_url (may be HTML or direct URL)
                        val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedUrl)
                            ?.groupValues?.get(1) ?: embedUrl
                        if (iframeSrc.isNotBlank() &&
                            !iframeSrc.contains("youtube.com", ignoreCase = true) &&
                            !iframeSrc.contains("youtu.be", ignoreCase = true)) {
                            links.add(iframeSrc)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DooPlay AJAX failed for post=$dataPost nume=$dataNume: ${e.message}")

                // Method 2: Try REST API fallback /wp-json/dooplayer/v2/
                try {
                    val restUrl = "$mainUrl/wp-json/dooplayer/v2/$dataPost/$dataType/$dataNume"
                    val restResp = app.get(restUrl, headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "Referer" to "$mainUrl/"
                    ), timeout = 10)
                    val restJson = restResp.text
                    if (restJson.isNotBlank()) {
                        val restObj = JSONObject(restJson)
                        val embedUrl = restObj.optString("embed_url", "")
                            .ifBlank { restObj.optString("iframe", "") }
                            .ifBlank { restObj.optString("url", "") }
                        if (embedUrl.isNotBlank() &&
                            !embedUrl.contains("youtube.com", ignoreCase = true)) {
                            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedUrl)
                                ?.groupValues?.get(1) ?: embedUrl
                            links.add(iframeSrc)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "DooPlay AJAX: found ${links.size} player links")
        return links
    }

    // ═══════════════════════════════════════════════════
    //  EPISODE DISCOVERY — STATEFUL DOCUMENT WALKER
    // ═══════════════════════════════════════════════════

    private suspend fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?,
        ajaxLinks: List<String> = emptyList()
    ): List<Episode> {
        // CRITICAL: On the current kmmovies.lol theme, download links are in
        // div.downloads-section which is a SIBLING of article.movie-hero, NOT
        // a child. We must search the broader container (.single-container or
        // the full document), otherwise we find zero links and show "Coming Soon".
        val container = doc.selectFirst(".single-container")
            ?: doc.selectFirst(".downloads-section")
            ?: doc.selectFirst("article, .entry-content, .wp-content")
            ?: doc

        // ── Stage -1: KMMovies custom theme series layout (HIGHEST PRIORITY) ──
        // The live kmmovies.lol site uses a custom WordPress theme, NOT Dooplay.
        // Series pages have: div.season-block > div.type-content[data-type^="episodes-"]
        //   with links to episodes.magiclinks.lol subpages that list individual episodes.
        // Without this stage, quality links (480p/720p/1080p) get mapped as episodes.
        val kmSeries = parseKMMoviesSeriesLayout(doc, defaultSeason, tmdbSeason, cine)
        if (kmSeries.isNotEmpty()) {
            Log.d(TAG, "Stage-1 (KMMovies series): ${kmSeries.size} episodes")
            return kmSeries
        }

        // Stage 0: Dooplay theme episode list (ul.episodios > li)
        val dooplayEps = parseDooplayEpisodes(doc, defaultSeason, tmdbSeason, cine)
        if (dooplayEps.isNotEmpty()) {
            Log.d(TAG, "Stage0 (Dooplay episodes): ${dooplayEps.size} episodes")
            return dooplayEps
        }

        // Stage 1: Dooplay season blocks (#seasons > .se-c)
        val dooplaySeasons = parseDooplaySeasonBlocks(doc, tmdbSeason, cine)
        if (dooplaySeasons.isNotEmpty()) {
            Log.d(TAG, "Stage1 (Dooplay seasons): ${dooplaySeasons.size} episodes")
            return dooplaySeasons
        }

        // Stage 2: Document-walker episode headers ("Episode 3" + links below)
        val perHeader = parseEpisodeHeaderLayout(container, defaultSeason)
        if (perHeader.isNotEmpty()) {
            Log.d(TAG, "Stage2 (header layout): ${perHeader.size} episodes")
            return buildEpisodes(perHeader, tmdbSeason, cine)
        }

        // Stage 3: Table-based episode layout
        val tableEps = parseTableEpisodes(container, defaultSeason)
        if (tableEps.isNotEmpty()) {
            Log.d(TAG, "Stage3 (table layout): ${tableEps.size} episodes")
            return buildEpisodes(tableEps, tmdbSeason, cine)
        }

        // Stage 4: Flat fallback - every mirror link as pseudo-episode
        // Also include AJAX links if available
        val flat = collectMirrorLinksWithLabels(container)
        val allItems = mutableListOf<Pair<String?, String>>()
        ajaxLinks.forEachIndexed { idx, link ->
            allItems.add("Player ${idx + 1}" to link)
        }
        allItems.addAll(flat)

        if (allItems.isEmpty()) return emptyList()
        Log.w(TAG, "Stage4 (flat fallback): ${allItems.size} raw link(s)")
        return allItems.mapIndexed { idx, (label, link) ->
            newEpisode(link) {
                this.name = label ?: "Source ${idx + 1}"
                this.season = defaultSeason
                this.episode = idx + 1
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  KMMOVIES CUSTOM THEME — Series Episode Parser
    // ═══════════════════════════════════════════════════

    /**
     * Parse the KMMovies custom theme series layout.
     *
     * Live site structure (verified 2025-06):
     *   div.downloads-section
     *     div.download-category.tv-series
     *       div.season-block
     *         div.season-block-header
     *           span.season-block-title → "Season N"
     *         div.season-block-body
     *           div.type-tabs → "Episode Wise" / "Combined" / "Zip"
     *           div.type-content[data-type="episodes-{idx}"]
     *             div.download-buttons
     *               a.dl-btn → episodes.magiclinks.lol/series/{slug}-s{NN}-{quality}/
     *
     * Each episodes subpage (episodes.magiclinks.lol) contains:
     *   div.ep-row
     *     span.ep-name → "Episode 1"
     *     a.dl-btn → w1.skydrop.sbs/download.php?id=XXX
     *
     * BUG FIXED: Without this parser, quality links (480p/720p/1080p) from the
     * episodes tab get mapped as pseudo-episodes in Stage 4 fallback, showing:
     *   Episode 1 → 480p, Episode 2 → 720p, Episode 3 → 1080p
     * Instead of the correct:
     *   Season 1 → Episode 1, Episode 2, Episode 3 (each with multiple quality sources)
     */
    private suspend fun parseKMMoviesSeriesLayout(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        // Quick check: is this a KMMovies custom theme series page?
        val tvSeriesCategory = doc.selectFirst("div.download-category.tv-series")
        if (tvSeriesCategory == null) {
            Log.d(TAG, "No div.download-category.tv-series found — not a KMMovies series page")
            return emptyList()
        }

        val seasonBlocks = doc.select("div.season-block")
        if (seasonBlocks.isEmpty()) {
            Log.d(TAG, "No div.season-block elements found")
            return emptyList()
        }

        Log.d(TAG, "KMMovies series: found ${seasonBlocks.size} season blocks")

        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        for ((blockIdx, block) in seasonBlocks.withIndex()) {
            // Extract season number from title like "Season 4" or "Season 4 (7 eps)"
            val titleEl = block.selectFirst("span.season-block-title")
            val titleText = titleEl?.text() ?: ""
            val seasonNum = SEASON_HEADER_REGEX.find(titleText)?.let { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            } ?: Regex("""(\d+)""").find(titleText)?.groupValues?.get(1)?.toIntOrNull()
            ?: defaultSeason

            Log.d(TAG, "Season block $blockIdx: title='$titleText' → season=$seasonNum")

            // Find ALL episode-wise links in this season block
            // The data-type format is "episodes-{seasonIndex}" for the "Episode Wise" tab
            val episodeTabs = block.select("div.type-content[data-type^=episodes-]")

            val qualityUrls = mutableListOf<String>()
            if (episodeTabs.isNotEmpty()) {
                for (tab in episodeTabs) {
                    val btns = tab.select("a.dl-btn")
                    for (btn in btns) {
                        val href = btn.attr("href").ifBlank { continue }
                        val fixedHref = if (href.startsWith("http")) href else fixUrl(href)
                        if (fixedHref.isNotBlank() && fixedHref !in qualityUrls) {
                            qualityUrls.add(fixedHref)
                        }
                    }
                }
            } else {
                // Fallback: look for download buttons directly in the season block body
                val directBtns = block.select("div.season-block-body a.dl-btn")
                for (btn in directBtns) {
                    val href = btn.attr("href").ifBlank { continue }
                    val fixedHref = if (href.startsWith("http")) href else fixUrl(href)
                    if (fixedHref.isNotBlank() && fixedHref !in qualityUrls) {
                        qualityUrls.add(fixedHref)
                    }
                }
            }

            if (qualityUrls.isEmpty()) {
                Log.w(TAG, "No episode-wise links found for season $seasonNum")
                continue
            }

            Log.d(TAG, "Season $seasonNum: ${qualityUrls.size} quality URLs: ${qualityUrls.map { it.substringAfterLast("/") }}")

            // Sort URLs to prefer 1080p first (for faster episode discovery)
            val sortedUrls = qualityUrls.sortedByDescending { url ->
                when {
                    url.contains("2160p", ignoreCase = true) -> 5
                    url.contains("1080p", ignoreCase = true) -> 4
                    url.contains("720p", ignoreCase = true) -> 3
                    url.contains("480p", ignoreCase = true) -> 2
                    else -> 1
                }
            }

            // Fetch episode subpages to discover individual episode download links.
            // We fetch ALL quality subpages to provide multiple quality sources per episode.
            // Use coroutineScope for parallel fetching.
            val perQualityEpisodes = coroutineScope {
                sortedUrls.mapIndexed { idx, url ->
                    idx to async {
                        url to fetchEpisodesFromSubpage(url)
                    }
                }.map { it.second.await() }
            }

            // Merge all qualities: group by episode number across all quality pages
            // perQualityEpisodes = list of (url, Map<epNum, skydropUrl>)
            val episodeQualityMap = linkedMapOf<Int, MutableList<String>>() // epNum → [skydrop urls]
            for ((subpageUrl, episodes) in perQualityEpisodes) {
                for ((epNum, downloadUrl) in episodes) {
                    val bucket = episodeQualityMap.getOrPut(epNum) { mutableListOf() }
                    if (downloadUrl !in bucket) bucket.add(downloadUrl)
                }
            }

            if (episodeQualityMap.isEmpty()) {
                Log.w(TAG, "No episodes resolved from subpages for season $seasonNum")
                continue
            }

            // Fill the main map with properly grouped episodes
            for ((epNum, downloadUrls) in episodeQualityMap) {
                val key = seasonNum to epNum
                val bucket = map.getOrPut(key) { mutableListOf() }
                for (url in downloadUrls) {
                    if (url !in bucket) bucket.add(url)
                }
            }

            Log.d(TAG, "Season $seasonNum: ${episodeQualityMap.size} episodes discovered")
        }

        return if (map.isNotEmpty()) buildEpisodes(map, tmdbSeason, cine) else emptyList()
    }

    /**
     * Fetch an episodes.magiclinks.lol subpage and parse individual episode links.
     *
     * Page structure:
     *   div.ep-row
     *     span.ep-name → "Episode 1"
     *     a.dl-btn[href] → https://w1.skydrop.sbs/download.php?id=XXX
     *
     * Returns a map of episode number → download URL.
     */
    private suspend fun fetchEpisodesFromSubpage(url: String): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ), timeout = 15)

            val doc = resp.document

            // Method 1: Parse div.ep-row elements (standard episodes subpage)
            val epRows = doc.select("div.ep-row")
            if (epRows.isNotEmpty()) {
                for (row in epRows) {
                    val nameEl = row.selectFirst("span.ep-name")
                    val name = nameEl?.text()?.trim() ?: ""
                    val epNum = EPISODE_HEADER_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (result.size + 1)

                    val linkEl = row.selectFirst("a.dl-btn, a[href]")
                    val href = linkEl?.attr("href")?.ifBlank { null } ?: continue
                    val fixedHref = if (href.startsWith("http")) href else fixUrl(href)

                    result[epNum] = fixedHref
                }
                Log.d(TAG, "fetchEpisodesFromSubpage($url): ${result.size} episodes via ep-row")
                return result
            }

            // Method 2: Look for any list of episode links
            val allLinks = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.ifBlank { return@mapNotNull null }
                val fixedHref = if (href.startsWith("http")) href else fixUrl(href)
                val text = a.text().trim()
                val epNum = EPISODE_HEADER_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null && fixedHref.startsWith("http")) {
                    epNum to fixedHref
                } else null
            }

            if (allLinks.isNotEmpty()) {
                for ((epNum, href) in allLinks) {
                    result[epNum] = href
                }
                Log.d(TAG, "fetchEpisodesFromSubpage($url): ${result.size} episodes via link scan")
                return result
            }

            // Method 3: Broad fallback — all external download links
            val downloadLinks = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                href.takeIf {
                    it.startsWith("http", ignoreCase = true) &&
                    (it.contains("skydrop", ignoreCase = true) ||
                     it.contains("kmphotos", ignoreCase = true) ||
                     it.contains("hubcloud", ignoreCase = true) ||
                     it.contains("hubdrive", ignoreCase = true) ||
                     it.contains("katdrive", ignoreCase = true) ||
                     it.contains("gdflix", ignoreCase = true) ||
                     it.contains("gdlink", ignoreCase = true) ||
                     it.contains("gdtot", ignoreCase = true) ||
                     it.contains("gdmirror", ignoreCase = true) ||
                     it.contains("r2.dev", ignoreCase = true) ||
                     it.contains("drive.google", ignoreCase = true) ||
                     it.contains("pixeldrain", ignoreCase = true) ||
                     it.contains("gofile", ignoreCase = true) ||
                     it.contains("streamtape", ignoreCase = true) ||
                     it.contains("filemoon", ignoreCase = true) ||
                     it.contains("doodstream", ignoreCase = true) ||
                     it.contains("mixdrop", ignoreCase = true) ||
                     it.contains("streamwish", ignoreCase = true) ||
                     it.contains("vidhide", ignoreCase = true) ||
                     it.contains("voe", ignoreCase = true) ||
                     it.contains("fuckingfast", ignoreCase = true) ||
                     it.contains("driveseed", ignoreCase = true) ||
                     it.contains("driveleech", ignoreCase = true) ||
                     it.contains("send.cm", ignoreCase = true) ||
                     it.contains("1fichier", ignoreCase = true) ||
                     it.contains("mega", ignoreCase = true) ||
                     it.contains("mediafire", ignoreCase = true) ||
                     it.contains("mclinks", ignoreCase = true) ||
                     it.contains("kmhd", ignoreCase = true))
                }
            }.distinct()

            if (downloadLinks.isNotEmpty()) {
                for ((idx, link) in downloadLinks.withIndex()) {
                    result[idx + 1] = link
                }
                Log.d(TAG, "fetchEpisodesFromSubpage($url): ${result.size} episodes via broad fallback")
            }

            if (result.isEmpty()) {
                Log.w(TAG, "fetchEpisodesFromSubpage($url): 0 episodes found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch episodes subpage $url: ${e.message}")
        }
        return result
    }

    /**
     * Parse Dooplay-style episode list: ul.episodios > li
     * Each li has: .numerando (S01E02), .episodiotitle > a (link + name)
     */
    private fun parseDooplayEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val episodeItems = doc.select("ul.episodios > li")
        if (episodeItems.isEmpty()) return emptyList()

        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        for (li in episodeItems) {
            val numerando = li.selectFirst(".numerando")
            val (season, episode) = if (numerando != null) {
                val parts = numerando.text().trim().split(Regex("\\s+"))
                val s = parts.getOrNull(0)?.trimStart('0')?.toIntOrNull() ?: defaultSeason
                val e = parts.getOrNull(1)?.trimStart('0')?.toIntOrNull() ?: continue
                s to e
            } else {
                defaultSeason to (map.size + 1)
            }

            val linkEl = li.selectFirst(".episodiotitle a[href]") ?: li.selectFirst("a[href]") ?: continue
            val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }.ifBlank { continue }
            val fixedHref = fixUrl(href)

            val bucket = map.getOrPut(season to episode) { mutableListOf() }
            if (fixedHref !in bucket) bucket.add(fixedHref)
        }

        return buildEpisodes(map, tmdbSeason, cine)
    }

    /**
     * Parse Dooplay season blocks: #seasons > .se-c
     * Each .se-c has: .se-t (Season N), .se-a > li (episode links)
     */
    private fun parseDooplaySeasonBlocks(
        doc: Document,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val seasonBlocks = doc.select("#seasons > .se-c")
        if (seasonBlocks.isEmpty()) return emptyList()

        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        for (block in seasonBlocks) {
            val seasonNum = block.selectFirst(".se-t")?.text()
                ?.trimStart('0')?.toIntOrNull() ?: 1

            val episodeLinks = block.select(".se-a > li a[href]")
            for ((idx, linkEl) in episodeLinks.withIndex()) {
                val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }.ifBlank { continue }
                val epNum = idx + 1

                val bucket = map.getOrPut(seasonNum to epNum) { mutableListOf() }
                val fixedHref = fixUrl(href)
                if (fixedHref !in bucket) bucket.add(fixedHref)
            }
        }

        return buildEpisodes(map, tmdbSeason, cine)
    }

    /**
     * Walk the article in document order, tracking "Season X" / "Episode N"
     * labels, and bucket every known-host link into (season, episode) -> [links].
     * This handles the common WordPress layout where episodes are marked by
     * h3/h4/strong tags and download links follow beneath them.
     */
    private fun parseEpisodeHeaderLayout(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var currentQualityLabel: String? = null

        for (node in container.allElements) {
            val tag = node.tagName()

            // Track episode/season/quality from label tags
            if (tag in LABEL_TAGS) {
                val text = node.ownText().ifBlank { node.text() }.trim()
                if (text.isNotEmpty() && text.length < 120 &&
                    EPISODE_NEGATIVE_PHRASES.none { it in text.lowercase() }) {

                    SEASON_HEADER_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    EPISODE_HEADER_REGEX.find(text)?.let { m ->
                        m.groupValues[1].toIntOrNull()?.let { currentEpisode = it }
                    }
                    // Track quality label from headers
                    QUALITY_REGEX.find(text)?.let { match ->
                        currentQualityLabel = match.groupValues[1]
                    }
                }
            }

            // Collect links under the current episode
            val ep = currentEpisode
            if (ep != null && tag == "a") {
                val href = node.attr("href")
                if (href.isNotBlank() && (LINK_HOST_REGEX.containsMatchIn(href) ||
                        DOOPLAY_LINKS_REGEX.containsMatchIn(href))) {
                    val fixedHref = if (href.startsWith("http")) href else fixUrl(href)
                    val bucket = map.getOrPut(currentSeason to ep) { mutableListOf() }
                    if (fixedHref !in bucket) bucket.add(fixedHref)
                }
            }
        }
        return map
    }

    /**
     * Parse table-based episode layouts.
     * Some series pages use tables where each row has:
     *   - Column 1: "Episode N" text
     *   - Column 2+: Download links
     */
    private fun parseTableEpisodes(
        container: Element,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val map = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        val tables = container.select("table")

        for (table in tables) {
            var currentSeason = defaultSeason
            val rows = table.select("tr")
            for (row in rows) {
                val cells = row.select("td, th")
                if (cells.isEmpty()) continue

                // Try to find episode/season number from first cells
                val firstCellText = cells.first()?.text() ?: ""
                val epNum = EPISODE_HEADER_REGEX.find(firstCellText)?.groupValues?.get(1)?.toIntOrNull()
                SEASON_HEADER_REGEX.find(firstCellText)?.let { m ->
                    (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                        ?.let { currentSeason = it }
                }

                if (epNum != null) {
                    // Collect links from all cells in this row
                    val links = row.select("a[href]")
                        .map { it.attr("href") }
                        .filter { it.isNotBlank() && (LINK_HOST_REGEX.containsMatchIn(it) ||
                                DOOPLAY_LINKS_REGEX.containsMatchIn(it)) }
                        .map { if (it.startsWith("http")) it else fixUrl(it) }
                        .distinct()

                    if (links.isNotEmpty()) {
                        val bucket = map.getOrPut(currentSeason to epNum) { mutableListOf() }
                        links.forEach { if (it !in bucket) bucket.add(it) }
                    }
                }
            }
        }
        return map
    }

    // ═══════════════════════════════════════════════════
    //  TWO-PASS LINK COLLECTION
    // ═══════════════════════════════════════════════════

    /**
     * Two-pass link collector:
     *   Pass 1 (strict): URLs matching LINK_HOST_REGEX (known-good hosts).
     *   Pass 2 (permissive): if pass 1 found nothing, every external URL
     *                        not on the blacklist and not the site itself.
     */
    private fun collectMirrorLinks(container: Element): List<String> {
        // Collect all hrefs, including relative /links/ URLs from Dooplay
        val all = container.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            when {
                href.startsWith("http", ignoreCase = true) -> href
                DOOPLAY_LINKS_REGEX.containsMatchIn(href) -> fixUrl(href)
                else -> null
            }
        }.distinct()

        // Pass 1 (strict): URLs matching LINK_HOST_REGEX (known-good hosts)
        val strict = all.filter { LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        // Pass 1.5: Dooplay /links/ redirect URLs (will be resolved in loadLinks)
        val dooplayLinks = all.filter { DOOPLAY_LINKS_REGEX.containsMatchIn(it) }
        if (dooplayLinks.isNotEmpty()) {
            Log.d(TAG, "Found ${dooplayLinks.size} Dooplay /links/ redirect URLs")
            return dooplayLinks
        }

        // Pass 2: anything not obviously junk (but allow Dooplay /links/ redirects even on mainUrl)
        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    (!url.contains(mainUrl, ignoreCase = true) || DOOPLAY_LINKS_REGEX.containsMatchIn(url))
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict matched 0, permissive fallback (${permissive.size}): ${permissive.take(3)}")
        }
        return permissive
    }

    /**
     * Collect mirror links with rich metadata labels.
     * Labels include: Hoster name + Quality + Size + Codec + Audio
     * Example: "HubCloud - 4K UHD - 12GB [Dolby Vision]"
     */
    private fun collectMirrorLinksWithLabels(container: Element): List<Pair<String?, String>> {
        val cleanUrls: Set<String> = LinkedHashSet(collectMirrorLinks(container))
        if (cleanUrls.isEmpty()) return emptyList()

        val out = mutableListOf<Pair<String?, String>>()
        val seen = mutableSetOf<String>()

        for (a in container.select("a[href]")) {
            val href = a.attr("href")
            if (href !in cleanUrls || href in seen) continue
            seen.add(href)

            val raw = (a.text().ifBlank {
                a.parents().firstOrNull { p -> p.tagName() in LABEL_TAGS }?.text() ?: ""
            }).trim().trim('_', '*', ':', ' ')

            val label = buildSmartLabel(raw, href)
            out.add(label to href)
        }
        return out
    }

    /**
     * Build a professional smart label from raw text and URL.
     * Format: "Hoster - Quality - Size [Codec] [Audio]"
     * Example: "HubCloud - 4K UHD - 12GB [HEVC] [Hindi+Eng]"
     */
    private fun buildSmartLabel(rawText: String, url: String): String {
        val parts = mutableListOf<String>()

        // 1. Hoster name
        val hoster = getHosterName(url)
        parts.add(hoster)

        // 2. Quality (from text, then URL)
        val quality = QUALITY_REGEX.find(rawText)?.groupValues?.get(1)
            ?: QUALITY_REGEX.find(url)?.groupValues?.get(1)
        if (quality != null) {
            parts.add(formatQuality(quality))
        }

        // 3. Size
        val size = SIZE_REGEX.find(rawText)?.groupValues?.let { "${it[1]} ${it[2]}" }
        if (size != null) {
            parts.add(size)
        }

        // 4. Codec tags in brackets
        val codecs = CODEC_REGEX.findAll(rawText).map { it.groupValues[1].uppercase() }.distinct().toList()
        if (codecs.isNotEmpty()) {
            parts.add("[${codecs.joinToString(" + ")}]")
        }

        // 5. Audio tags in brackets
        val audio = AUDIO_REGEX.findAll(rawText).map {
            it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        }.distinct().toList()
        if (audio.isNotEmpty()) {
            parts.add("[${audio.joinToString(" + ")}]")
        }

        return parts.joinToString(" - ").ifBlank { hoster }
    }

    /** Format quality string for display (e.g., "4K" → "4K UHD", "dv" → "Dolby Vision"). */
    private fun formatQuality(q: String): String = when {
        q.equals("4k", ignoreCase = true) -> "4K UHD"
        q.equals("dv", ignoreCase = true) || q.contains("dolby", ignoreCase = true) -> "Dolby Vision"
        q.equals("hdr", ignoreCase = true) -> "HDR"
        q.contains("2160p", ignoreCase = true) -> "4K UHD"
        q.contains("1080p", ignoreCase = true) && q.contains("hevc", ignoreCase = true) -> "1080p HEVC"
        q.contains("1080p", ignoreCase = true) -> "1080p"
        q.contains("720p", ignoreCase = true) -> "720p"
        q.contains("480p", ignoreCase = true) -> "480p"
        else -> q.replaceFirstChar { it.uppercase() }
    }

    private fun collectAllPlayableLinks(doc: Document): String {
        val allLinks = mutableListOf<String>()

        // Source 0: KMMovies-specific download buttons (.downloads-section a.dl-btn)
        // On the current kmmovies.lol theme, download links live in
        // div.downloads-section which is OUTSIDE article.movie-hero.
        // These are the primary source of playable links.
        // Live site structure: div.download-category.encoded/webdl → div.download-buttons → a.dl-btn
        // Each a.dl-btn has: span.dl-res (resolution), span.dl-quality, span.dl-size
        val dlButtons = doc.select(
            ".downloads-section a.dl-btn, .download-buttons a.dl-btn, " +
            ".download-buttons a[href], .download-category a.dl-btn"
        )
        if (dlButtons.isNotEmpty()) {
            for (btn in dlButtons) {
                val href = btn.attr("href").ifBlank { continue }
                val fixedHref = if (href.startsWith("http")) href else fixUrl(href)
                if (fixedHref.isNotBlank()) {
                    allLinks.add(fixedHref)
                    val res = btn.selectFirst(".dl-res")?.text() ?: ""
                    val quality = btn.selectFirst(".dl-quality")?.text() ?: ""
                    val size = btn.selectFirst(".dl-size")?.text() ?: ""
                    Log.d(TAG, "Download button: $res $quality $size -> $fixedHref")
                }
            }
        }

        // Source 1: Dooplay download links table (#download or #download-links .links_table table)
        // BUG FIX: Live kmmovies.lol uses #download-links (with hyphen), not #download
        val downloadTable = doc.select("#download .links_table table tbody tr, #download table tbody tr, #download-links .links_table table tbody tr, #download-links table tbody tr")
        if (downloadTable.isNotEmpty()) {
            for (row in downloadTable) {
                val link = row.selectFirst("a[href]")
                val href = link?.attr("href")?.ifBlank { null } ?: continue
                val fixedHref = if (href.startsWith("http")) href else fixUrl(href)

                // Extract quality/size/language from the table row for smart labels
                val quality = row.select("strong.quality, td:nth-child(2)").firstOrNull()?.text()?.trim() ?: ""
                val language = row.select("td:nth-child(3)").firstOrNull()?.text()?.trim() ?: ""
                val size = row.select("td:nth-child(4)").firstOrNull()?.text()?.trim() ?: ""

                if (fixedHref.isNotBlank() && fixedHref !in allLinks) {
                    allLinks.add(fixedHref)
                    Log.d(TAG, "Download table: $quality $language $size -> $fixedHref")
                }
            }
        }

        // Source 2: Broader container (single-container or full doc) body links
        // CRITICAL: Must search .single-container or doc, NOT just article,
        // because on the current theme download links are outside article.
        val content = doc.selectFirst(".single-container")
            ?: doc.selectFirst(".downloads-section")
            ?: doc.selectFirst("article, .entry-content, .wp-content")
            ?: doc
        val bodyLinks = collectMirrorLinks(content)
        for (link in bodyLinks) {
            if (link !in allLinks) allLinks.add(link)
        }

        Log.d(TAG, "collectAllPlayableLinks(): ${allLinks.size} total links")
        return allLinks.joinToString("\n")
    }

    private fun buildEpisodes(
        map: LinkedHashMap<Pair<Int, Int>, MutableList<String>>,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val sorted = map.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return sorted.map { (key, links) ->
            val (season, ep) = key
            val tmdbEp = tmdbSeason
                ?.takeIf { it.seasonNumber == season }
                ?.episodes?.firstOrNull { it.episodeNumber == ep }
            val cineEp = cine?.videos
                ?.firstOrNull { it.season == season && it.episode == ep }

            newEpisode(links.joinToString("\n")) {
                this.name = tmdbEp?.name ?: cineEp?.name ?: "Episode $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                this.description = tmdbEp?.overview ?: cineEp?.overview
                this.score = tmdbEp?.rating
                (tmdbEp?.airDate ?: cineEp?.released)?.let {
                    addDate(it.substringBefore("T"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  loadLinks — RESOLVE DOWNLOAD LINKS TO PLAYABLE STREAMS
    // ═══════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls: List<String> = buildList {
            if (data.trimStart().startsWith("[")) {
                runCatching { tryParseJson<List<String>>(data)?.let { addAll(it) } }
            }
            data.split('\n', ',', '\r', '\t').forEach { add(it) }
        }
            .map { it.trim().trim('"', '\'', '[', ']', '(', ')', ' ', '<', '>') }
            .filter { it.startsWith("http", ignoreCase = true) }
            .map { stripJunkSuffix(it) }
            .filter { it.length > 8 }
            .distinct()

        if (urls.isEmpty()) {
            Log.w(TAG, "loadLinks: no URLs extracted from data")
            return false
        }
        Log.d(TAG, "loadLinks: resolving ${urls.size} URL(s)")

        var anyDispatched = false
        urls.amap { rawUrl ->
            val ok = if (DOOPLAY_LINKS_REGEX.containsMatchIn(rawUrl)) {
                // Dooplay /links/ redirect → resolve through savelinks.me to get actual hoster
                resolveDooplayLinksPage(rawUrl, subtitleCallback, callback)
            } else {
                dispatchExtractor(rawUrl, subtitleCallback, callback)
            }
            if (ok) anyDispatched = true
        }

        if (!anyDispatched) {
            Log.w(TAG, "loadLinks: every URL failed to dispatch")
        }
        return anyDispatched
    }

    private fun stripJunkSuffix(url: String): String {
        var u = url
        while (u.isNotEmpty() && u.last() in setOf(')', ']', '}', '.', ',', ';', '!', '?', '"', '\'')) {
            u = u.dropLast(1)
        }
        val hashIdx = u.indexOf('#')
        if (hashIdx > 0) u = u.substring(0, hashIdx)
        return u
    }

    // ═══════════════════════════════════════════════════
    //  PER-URL DISPATCH — LINK CHAIN CRACKER
    // ═══════════════════════════════════════════════════

    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.trim()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false

        return try {
            when {
                // Dooplay internal /links/ redirect → resolve through savelinks.me
                DOOPLAY_LINKS_REGEX.containsMatchIn(url) -> {
                    resolveDooplayLinksPage(url, subtitleCallback, callback)
                }

                // savelinks.me / link protection pages → extract hoster links
                url.contains("savelinks.me", ignoreCase = true) ||
                url.contains("linkstaker", ignoreCase = true) -> {
                    resolveDooplayLinksPage(url, subtitleCallback, callback)
                }

                // KMMovies redirector: magiclinks.lol → page with actual links
                // BUG FIX: Return actual result instead of always true, which masked failures
                url.contains("magiclinks.lol", ignoreCase = true) -> {
                    resolveMagiclinks(url, callback)
                }

                // KMHD link service: links.kmhd.eu/file/ or /pack/ — use KmhdExtractor
                url.contains("links.kmhd", ignoreCase = true) ||
                url.contains("gd.kmhd", ignoreCase = true) ||
                url.contains("kmhd.net", ignoreCase = true) -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }

                // Skydrop download page → call API to get video URL
                // Handles both skydrop.sbs and skydrop.live domain variants
                (url.contains("skydrop.sbs", ignoreCase = true) ||
                    url.contains("skydrop.live", ignoreCase = true)) &&
                    url.contains("download.php", ignoreCase = true) -> {
                    resolveSkydrop(url, callback)
                }

                // Skydrop API URL directly
                (url.contains("skydrop.sbs", ignoreCase = true) ||
                    url.contains("skydrop.live", ignoreCase = true)) &&
                    url.contains("api.php", ignoreCase = true) -> {
                    resolveSkydropApi(url, callback)
                }

                // kmphotos stream/download — handles 3 URL patterns:
                // /nf/index.php?videoUrl= (no HTTP needed), /download99.php, /clouddownload.php
                url.contains("kmphotos", ignoreCase = true) -> {
                    resolveKmphotos(url, callback)
                }

                // Shorteners/redirectors: mclinks, hblinks, linkszilla
                url.contains("mclinks", ignoreCase = true) ||
                url.contains("hblinks", ignoreCase = true) ||
                url.contains("linkszilla", ignoreCase = true) -> {
                    resolveRedirectChain(url, callback)
                }

                // HubCloud family — may need page scraping
                url.contains("hubcloud", ignoreCase = true) ||
                url.contains("hubdrive", ignoreCase = true) -> {
                    resolveHubCloud(url, subtitleCallback, callback)
                }

                // Direct video URL (R2, Google Drive, etc.)
                url.contains("r2.dev", ignoreCase = true) ||
                url.contains("googleusercontent.com", ignoreCase = true) -> {
                    val quality = guessQualityFromUrl(url)
                    callback(
                        newExtractorLink(
                            "$name Direct $quality",
                            "$name Direct $quality",
                            url,
                            INFER_TYPE
                        ) {
                            this.quality = getQualityInt(quality)
                            this.referer = "$mainUrl/"
                        }
                    )
                    true
                }

                // Everything else: try CloudStream's stock extractor registry
                else -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extractor crashed for $url: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════
    //  KMMOVIES-SPECIFIC REDIRECTOR RESOLUTION
    // ═══════════════════════════════════════════════════

    /**
     * Resolve a magiclinks.lol page. These pages contain links to
     * skydrop/kmphotos (actual download/stream sources).
     * Also handles episodes.magiclinks.lol subpages that list individual episodes.
     * Returns true if at least one stream was produced.
     */
    private suspend fun resolveMagiclinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyProduced = false
        try {
            // If this is an episodes subpage (episodes.magiclinks.lol), parse it differently
            if (url.contains("episodes.magiclinks.lol", ignoreCase = true)) {
                val episodes = fetchEpisodesFromSubpage(url)
                if (episodes.isEmpty()) {
                    Log.w(TAG, "episodes subpage had 0 episodes: $url")
                    return false
                }
                // Dispatch each episode's download link
                for ((epNum, downloadUrl) in episodes) {
                    try {
                        val ok = dispatchExtractor(downloadUrl, { _ -> }, callback)
                        if (ok) anyProduced = true
                    } catch (_: Exception) {}
                }
                return anyProduced
            }

            val doc = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ), timeout = 30).document

            // Primary: look for known hoster links on the page
            // BUG FIX: Also check a.download-button, .btn, and any anchor with href
            val allLinks = doc.select("a[href], a.download-button, a.btn").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.ifBlank { return@mapNotNull null }
                val fixedHref = fixUrl(href)
                fixedHref.takeIf {
                    it.startsWith("http", ignoreCase = true) &&
                    (it.contains("skydrop", ignoreCase = true) ||
                     it.contains("kmphotos", ignoreCase = true) ||
                     it.contains("hubcloud", ignoreCase = true) ||
                     it.contains("hubdrive", ignoreCase = true) ||
                     it.contains("katdrive", ignoreCase = true) ||
                     it.contains("gdrive", ignoreCase = true) ||
                     it.contains("r2.dev", ignoreCase = true) ||
                     it.contains("googleusercontent", ignoreCase = true) ||
                     it.contains("drive.google", ignoreCase = true) ||
                     it.contains("gdflix", ignoreCase = true) ||
                     it.contains("gdlink", ignoreCase = true) ||
                     it.contains("gdtot", ignoreCase = true) ||
                     it.contains("gdmirror", ignoreCase = true) ||
                     it.contains("pixeldrain", ignoreCase = true) ||
                     it.contains("gofile", ignoreCase = true) ||
                     it.contains("mediafire", ignoreCase = true) ||
                     it.contains("mega", ignoreCase = true) ||
                     it.contains("fuckingfast", ignoreCase = true) ||
                     it.contains("streamtape", ignoreCase = true) ||
                     it.contains("send.cm", ignoreCase = true) ||
                     it.contains("1fichier", ignoreCase = true) ||
                     it.contains("streamwish", ignoreCase = true) ||
                     it.contains("vidhide", ignoreCase = true) ||
                     it.contains("filemoon", ignoreCase = true) ||
                     it.contains("doodstream", ignoreCase = true) ||
                     it.contains("mixdrop", ignoreCase = true) ||
                     it.contains("voe", ignoreCase = true) ||
                     it.contains("driveseed", ignoreCase = true) ||
                     it.contains("driveleech", ignoreCase = true) ||
                     it.contains("streamlare", ignoreCase = true) ||
                     it.contains("filelions", ignoreCase = true) ||
                     it.contains("mclinks", ignoreCase = true) ||
                     it.contains("hblinks", ignoreCase = true) ||
                     it.contains("linkszilla", ignoreCase = true) ||
                     it.contains("kmhd", ignoreCase = true))
                }
            }.distinct()

            Log.d(TAG, "resolveMagiclinks($url): found ${allLinks.size} hoster links")

            for (link in allLinks) {
                try {
                    val ok = dispatchExtractor(link, { _ -> }, callback)
                    if (ok) anyProduced = true
                } catch (_: Exception) {}
            }

            // Fallback: try ALL external links
            if (allLinks.isEmpty()) {
                val anyLinks = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    href.takeIf {
                        it.startsWith("http", ignoreCase = true) &&
                        !IGNORE_HOST_REGEX.containsMatchIn(it) &&
                        !it.contains("magiclinks", ignoreCase = true)
                    }
                }.distinct()

                Log.d(TAG, "resolveMagiclinks fallback: trying ${anyLinks.size} external links")
                for (link in anyLinks) {
                    try {
                        val ok = loadExtractor(link, mainUrl, { _ -> }, callback)
                        if (ok) anyProduced = true
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve magiclinks page $url: ${e.message}")
        }
        return anyProduced
    }

    /**
     * Resolve skydrop.sbs/download.php?id=XXX by calling the API.
     */
    private suspend fun resolveSkydrop(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val id = Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1) ?: return false
            val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return false
            found = resolveSkydropApi("$base/api.php?id=$id", callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve skydrop URL $url: ${e.message}")
        }
        return found
    }

    /**
     * Call the skydrop API and extract the video link.
     * Response format: {"success":true,"link":"https://...","download_url":"https://...","type":"video"}
     */
    private suspend fun resolveSkydropApi(
        apiUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Referer" to "$mainUrl/"
            ), timeout = 15).parsedSafe<SkydropResponse>()

            if (response?.success == true) {
                val videoLink = response.link
                if (!videoLink.isNullOrBlank()) {
                    val quality = guessQualityFromUrl(videoLink)
                    callback(
                        newExtractorLink(
                            "$name SkyDrop $quality",
                            "$name SkyDrop $quality",
                            videoLink,
                            INFER_TYPE
                        ) {
                            this.quality = getQualityInt(quality)
                            this.referer = "$mainUrl/"
                        }
                    )
                    found = true
                }

                // Also try the download URL via redirect
                val downloadUrl = response.download_url
                if (!downloadUrl.isNullOrBlank() && downloadUrl != videoLink) {
                    try {
                        val dlResp = app.get(downloadUrl, headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        ), timeout = 15, allowRedirects = false)
                        val dlLocation = dlResp.headers["location"]
                        if (!dlLocation.isNullOrBlank()) {
                            val quality = guessQualityFromUrl(dlLocation)
                            callback(
                                newExtractorLink(
                                    "$name Direct $quality",
                                    "$name Direct $quality",
                                    dlLocation,
                                    INFER_TYPE
                                ) {
                                    this.quality = getQualityInt(quality)
                                    this.referer = "$mainUrl/"
                                }
                            )
                            found = true
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve skydrop API $apiUrl: ${e.message}")
        }
        return found
    }

    /**
     * Resolve kmphotos.cv URLs (online.php or download99.php).
     * online.php → 302 redirect to a player page with the R2 video URL
     * download99.php → 302 redirect to a signed download URL
     *
     * Also handles the redirect chain gracefully: if allowRedirects=false
     * doesn't get a Location header (e.g. Cloudflare intercepts), we
     * fall back to following the redirect and scraping the final page.
     *
     * Handles 3 URL patterns from live magiclinks pages:
     *  P1: /nf/index.php?videoUrl=<R2_URL>  — video URL in query param (no HTTP needed)
     *  P2: /download99.php?file=...          — 302 → signed download → HTML with R2 buttons
     *  P3: /clouddownload.php?file_id=...    — 200 → JS with buzzheavier/fuckingfast URL
     */
    private suspend fun resolveKmphotos(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            // ═══════════════════════════════════════════════════════
            //  Pattern 1: /nf/index.php?videoUrl=<R2_URL>
            //  The video URL is already embedded in the href query param.
            //  No HTTP request needed — fastest & most reliable path.
            // ═══════════════════════════════════════════════════════
            if (url.contains("/nf/index.php", ignoreCase = true) && url.contains("videoUrl=")) {
                val rawVideoUrl = url.substringAfter("videoUrl=")
                    .substringBefore("&")
                    .let { URLDecoder.decode(it, "UTF-8") }
                if (rawVideoUrl.startsWith("http") &&
                    (rawVideoUrl.contains("r2.dev", ignoreCase = true) ||
                     rawVideoUrl.contains("googleusercontent", ignoreCase = true) ||
                     rawVideoUrl.contains(".mkv", ignoreCase = true) ||
                     rawVideoUrl.contains(".mp4", ignoreCase = true))) {
                    val quality = guessQualityFromUrl(url)

                    // ── HEAD request verification (optional, resilient) ──────────
                    // Proactively check that the R2 URL is reachable with our
                    // anti-CF headers. If we get 403, log a warning but still
                    // emit the link — the user may have a VPN/proxy that helps.
                    try {
                        val headResp = app.head(rawVideoUrl, headers = KMPHOTOS_HEADERS, timeout = 10)
                        if (headResp.code == 403) {
                            Log.w(TAG, "resolveKmphotos P1: R2 URL returned 403 — " +
                                    "Cloudflare may block this link: $rawVideoUrl")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "resolveKmphotos P1: HEAD verification failed: ${e.message}")
                    }

                    // Use ExtractorLinkType.VIDEO explicitly instead of INFER_TYPE.
                    // INFER_TYPE relies on URL extension detection, which can fail
                    // if query params appear after .mkv. VIDEO tells ExoPlayer to
                    // create a ProgressiveMediaSource with MatroskaExtractor for
                    // .mkv files.
                    //
                    // KMPHOTOS_HEADERS provide realistic mobile browser headers
                    // that bypass Cloudflare bot detection on R2 bucket URLs.
                    // Without these, CF returns 403 HTML → ExoPlayer throws
                    // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003).
                    callback(
                        newExtractorLink(
                            "$name Stream $quality",
                            "$name [KMPhotos] $quality",
                            rawVideoUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityInt(quality)
                            this.referer = "https://z1.kmphotos.cv/"
                            this.headers = KMPHOTOS_HEADERS
                        }
                    )
                    return true
                }
                // videoUrl param present but not a direct video link — fall through
                // to HTTP-based resolution (page may contain JWPlayer with the URL)
            }

            // ═══════════════════════════════════════════════════════
            //  Pattern 2 & 3: need HTTP to resolve
            //  /download99.php?file=...  → 302 → HTML with R2/Worker buttons
            //  /clouddownload.php?file_id=... → 200 → JS with buzzheavier URL
            // ═══════════════════════════════════════════════════════
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,*/*;q=0.8",
                "Referer" to "https://w1.magiclinks.lol/"
            ), timeout = 15, allowRedirects = false)

            // Case A: 302 redirect
            val location = resp.headers["location"] ?: resp.headers["Location"]
            if (!location.isNullOrBlank()) {
                // Resolve relative URLs (e.g. "?file=...&exp=...&sig=...")
                val resolvedLocation = if (location.startsWith("http")) {
                    location
                } else {
                    val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: ""
                    val path = url.substringBefore("?", url)
                    if (location.startsWith("/")) {
                        "$base$location"
                    } else if (location.startsWith("?")) {
                        "$path$location"
                    } else {
                        "$base/$location"
                    }
                }

                // Sub-case A1: Location contains videoUrl param
                if (resolvedLocation.contains("videoUrl=")) {
                    val videoUrl = resolvedLocation.substringAfter("videoUrl=")
                        .substringBefore("&")
                        .let { URLDecoder.decode(it, "UTF-8") }
                    if (videoUrl.startsWith("http")) {
                        val quality = guessQualityFromUrl(url)
                        callback(
                            newExtractorLink(
                                "$name Stream $quality",
                                "$name [KMPhotos] $quality",
                                videoUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityInt(quality)
                                this.referer = "https://z1.kmphotos.cv/"
                                this.headers = KMPHOTOS_HEADERS
                            }
                        )
                        return true
                    }
                }

                // Sub-case A2: Location is a direct R2/CDN link
                if (resolvedLocation.contains("r2.dev", ignoreCase = true) ||
                    resolvedLocation.contains("googleusercontent", ignoreCase = true) ||
                    resolvedLocation.contains(".mp4", ignoreCase = true) ||
                    resolvedLocation.contains(".mkv", ignoreCase = true) ||
                    resolvedLocation.contains(".m3u8", ignoreCase = true)) {
                    val quality = guessQualityFromUrl(resolvedLocation)
                    val linkType = if (resolvedLocation.contains(".m3u8", ignoreCase = true))
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        newExtractorLink(
                            "$name Stream $quality",
                            "$name [KMPhotos] $quality",
                            resolvedLocation,
                            linkType
                        ) {
                            this.quality = getQualityInt(quality)
                            this.referer = "https://z1.kmphotos.cv/"
                            this.headers = KMPHOTOS_HEADERS
                        }
                    )
                    return true
                }

                // Sub-case A3: Redirect to another kmphotos page — recurse once
                if (resolvedLocation.contains("kmphotos", ignoreCase = true)) {
                    found = resolveKmphotos(resolvedLocation, callback)
                    return found
                }

                // Sub-case A4: Redirect to any other hoster — dispatch normally
                if (resolvedLocation.startsWith("http")) {
                    found = dispatchExtractor(resolvedLocation, { _ -> }, callback)
                    return found
                }

                // Relative redirect that didn't resolve — follow the full chain
                Log.w(TAG, "resolveKmphotos: unresolvable redirect $location from $url")
            }

            // Case B: 200 response — parse HTML for video URL
            val doc = resp.document

            // B1: JWPlayer setup — file: "https://...r2.dev/...mkv"
            val jwFile = Regex("""file:\s*["']([^"']+)["']""").find(doc.html())
                ?.groupValues?.get(1)
            if (!jwFile.isNullOrBlank() && jwFile.startsWith("http")) {
                val quality = guessQualityFromUrl(jwFile)
                callback(
                    newExtractorLink(
                        "$name Stream $quality",
                        "$name [KMPhotos] $quality",
                        jwFile,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityInt(quality)
                        this.referer = "https://z1.kmphotos.cv/"
                        this.headers = KMPHOTOS_HEADERS
                    }
                )
                return true
            }

            // B2: HTML5 video/source tag
            val videoSrc = doc.selectFirst("video source[src]")?.attr("abs:src")
                ?: doc.selectFirst("video[src]")?.attr("abs:src")
            if (!videoSrc.isNullOrBlank() && videoSrc.startsWith("http")) {
                val quality = guessQualityFromUrl(videoSrc)
                callback(
                    newExtractorLink(
                        "$name Stream $quality",
                        "$name [KMPhotos] $quality",
                        videoSrc,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityInt(quality)
                        this.referer = "https://z1.kmphotos.cv/"
                        this.headers = KMPHOTOS_HEADERS
                    }
                )
                return true
            }

            // B3: Pattern 2 — download page with R2/Worker buttons
            // Parse <a class="btn btn-primary" href="?file=...&dl=r2&exp=...&sig=...">
            val dlButtons = doc.select("a.btn[href], a.download-button[href]")
            for (btn in dlButtons) {
                val href = btn.attr("href").ifBlank { continue }
                // Resolve relative href
                val resolvedHref = when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> "https://z1.kmphotos.cv$href"
                    href.startsWith("?") -> "${url.substringBefore("?")}$href"
                    else -> continue
                }
                // Try to follow this download link
                try {
                    val dlResp = app.get(resolvedHref, headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,*/*;q=0.8",
                        "Referer" to url
                    ), timeout = 15, allowRedirects = true)

                    val dlUrl = dlResp.url
                    // Check if the final URL is a direct video link
                    if (dlUrl.contains("r2.dev", ignoreCase = true) ||
                        dlUrl.contains(".mkv", ignoreCase = true) ||
                        dlUrl.contains(".mp4", ignoreCase = true)) {
                        val quality = guessQualityFromUrl(dlUrl)
                        callback(
                            newExtractorLink(
                                "$name Stream $quality",
                                "$name [KMPhotos DL] $quality",
                                dlUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityInt(quality)
                                this.referer = "https://z1.kmphotos.cv/"
                                this.headers = KMPHOTOS_HEADERS
                            }
                        )
                        found = true
                        break
                    }
                    // Check if the response body has a redirect to R2
                    val dlDoc = dlResp.document
                    val dlJwFile = Regex("""file:\s*["']([^"']+)["']""").find(dlDoc.html())
                        ?.groupValues?.get(1)
                    if (!dlJwFile.isNullOrBlank() && dlJwFile.startsWith("http")) {
                        val quality = guessQualityFromUrl(dlJwFile)
                        callback(
                            newExtractorLink(
                                "$name Stream $quality",
                                "$name [KMPhotos DL] $quality",
                                dlJwFile,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityInt(quality)
                                this.referer = "https://z1.kmphotos.cv/"
                                this.headers = KMPHOTOS_HEADERS
                            }
                        )
                        found = true
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "resolveKmphotos: download button follow failed: ${e.message}")
                }
            }
            if (found) return true

            // B4: Pattern 3 — clouddownload page with buzzheavier/fuckingfast JS
            // Extract file ID and construct the buzzheavier download URL
            val fileId = Regex("""["'](\w{10,})["']""").find(doc.html())
                ?.groupValues?.get(1)
                ?: Regex("""file_id[=:]\s*["']?([^"'\s&]+)""").find(doc.html())
                    ?.groupValues?.get(1)
                ?: Regex("""/(?:buzzheavier|bzzhr|fuckingfast)/(\w+)""").find(doc.html())
                    ?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                // Try known Buzzheavier mirror domains
                val mirrors = listOf("buzzheavier.com", "bzzhr.co", "fuckingfast.net")
                for (mirror in mirrors) {
                    try {
                        val mirrorUrl = "https://$mirror/$fileId"
                        val ok = loadExtractor(mirrorUrl, url, { _ -> }, callback)
                        if (ok) {
                            found = true
                            break
                        }
                    } catch (_: Exception) {}
                }
                if (found) return true
            }

            // B5: Fallback — check for any direct video link in the page
            val allAnchors = doc.select("a[href]").mapNotNull { a ->
                a.attr("abs:href").ifBlank { null }
            }.filter { it.startsWith("http") && LINK_HOST_REGEX.containsMatchIn(it) }
            for (link in allAnchors) {
                try {
                    val ok = dispatchExtractor(link, { _ -> }, callback)
                    if (ok) found = true
                } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            Log.w(TAG, "resolveKmphotos failed for $url: ${e.message}")
        }
        return found
    }

    // ═══════════════════════════════════════════════════
    //  RECURSIVE REDIRECT CHAIN CRACKER
    // ═══════════════════════════════════════════════════

    /**
     * Follow a redirect chain through shorteners (mclinks, hblinks, linkszilla)
     * using allowRedirects = false, with Referer and User-Agent spoofing.
     * Stops when we reach a known hoster or hit MAX_REDIRECT_DEPTH.
     */
    private suspend fun resolveRedirectChain(
        url: String,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        var found = false
        if (depth >= MAX_REDIRECT_DEPTH) {
            Log.w(TAG, "Redirect chain too deep ($depth) for $url")
            return false
        }

        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ), timeout = 15, allowRedirects = false)

            val location = resp.headers["location"]
            if (!location.isNullOrBlank()) {
                val fixedLocation = fixUrl(location)

                // Check if we've reached a known hoster
                if (LINK_HOST_REGEX.containsMatchIn(fixedLocation) &&
                    !fixedLocation.contains("mclinks", ignoreCase = true) &&
                    !fixedLocation.contains("hblinks", ignoreCase = true) &&
                    !fixedLocation.contains("linkszilla", ignoreCase = true)) {
                    // Reached final hoster — dispatch it
                    val ok = dispatchExtractor(fixedLocation, { _ -> }, callback)
                    if (ok) found = true
                } else {
                    // Still a redirect — follow the chain
                    val ok = resolveRedirectChain(fixedLocation, callback, depth + 1)
                    if (ok) found = true
                }
            } else {
                // No redirect header — this might be an HTML page with links
                val doc = resp.document
                val pageLinks = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    href.takeIf {
                        it.startsWith("http", ignoreCase = true) &&
                        LINK_HOST_REGEX.containsMatchIn(it)
                    }
                }.distinct()

                if (pageLinks.isNotEmpty()) {
                    for (link in pageLinks) {
                        try {
                            val ok = dispatchExtractor(link, { _ -> }, callback)
                            if (ok) found = true
                        } catch (_: Exception) {}
                    }
                } else {
                    // Try meta-refresh redirect
                    val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
                    val content = metaRefresh?.attr("content") ?: ""
                    val refreshUrl = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)
                        .find(content)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
                    if (!refreshUrl.isNullOrBlank()) {
                        val ok = resolveRedirectChain(fixUrl(refreshUrl), callback, depth + 1)
                        if (ok) found = true
                    } else {
                        // Try JavaScript variable extraction
                        val scripts = doc.select("script")
                        for (script in scripts) {
                            val jsUrl = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")
                                .find(script.html())?.groupValues?.get(1)
                            if (!jsUrl.isNullOrBlank()) {
                                val ok = dispatchExtractor(fixUrl(jsUrl), { _ -> }, callback)
                                if (ok) found = true
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Redirect chain failed at depth $depth for $url: ${e.message}")
        }
        return found
    }

    /**
     * Resolve a Dooplay /links/{shortcode}/ redirect page.
     * These pages redirect to savelinks.me or similar link protection services,
     * which then contain the actual hoster URLs (hubcloud, gdflix, etc.).
     *
     * Flow: /links/abc123/ → savelinks.me page → hubcloud.foo/video/xxx
     */
    private suspend fun resolveDooplayLinksPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var dispatched = false
        try {
            Log.d(TAG, "Resolving Dooplay /links/ page: $url")
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ), timeout = 15, allowRedirects = true)

            val finalUrl = resp.url
            val doc = resp.document

            // Case 1: Redirected to savelinks.me or similar link protection page
            // Extract all external links that match known hoster patterns
            val hosterLinks = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.ifBlank { return@mapNotNull null }
                val fixedHref = fixUrl(href)
                fixedHref.takeIf {
                    it.startsWith("http", ignoreCase = true) &&
                    LINK_HOST_REGEX.containsMatchIn(it) &&
                    !it.contains("savelinks", ignoreCase = true)
                }
            }.distinct()

            if (hosterLinks.isNotEmpty()) {
                Log.d(TAG, "Dooplay /links/ page: found ${hosterLinks.size} hoster URLs")
                for (link in hosterLinks) {
                    try {
                        val ok = dispatchExtractor(link, subtitleCallback, callback)
                        if (ok) dispatched = true
                    } catch (_: Exception) {}
                }
                return dispatched
            }

            // Case 2: The redirect page itself might be a hoster page (hubcloud, etc.)
            if (LINK_HOST_REGEX.containsMatchIn(finalUrl) &&
                !finalUrl.contains("savelinks", ignoreCase = true)) {
                val ok = dispatchExtractor(finalUrl, subtitleCallback, callback)
                if (ok) dispatched = true
                return dispatched
            }

            // Case 3: Try ALL external links on the page (permissive fallback)
            val allExternal = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                href.takeIf {
                    it.startsWith("http", ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(it) &&
                    !it.equals(url, ignoreCase = true) &&
                    !it.contains("savelinks", ignoreCase = true)
                }
            }.distinct()

            if (allExternal.isNotEmpty()) {
                Log.d(TAG, "Dooplay /links/ fallback: trying ${allExternal.size} external links")
                for (link in allExternal) {
                    try {
                        val ok = dispatchExtractor(link, subtitleCallback, callback)
                        if (ok) dispatched = true
                    } catch (_: Exception) {}
                }
            }

            // Case 4: Check for JavaScript redirects or meta-refresh
            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            val content = metaRefresh?.attr("content") ?: ""
            val refreshUrl = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            if (!refreshUrl.isNullOrBlank()) {
                val ok = dispatchExtractor(fixUrl(refreshUrl), subtitleCallback, callback)
                if (ok) dispatched = true
            }

            // Case 5: Look for JavaScript variables with URLs
            val scripts = doc.select("script")
            for (script in scripts) {
                val js = script.html()
                val jsUrl = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")
                    .find(js)?.groupValues?.get(1)
                if (!jsUrl.isNullOrBlank()) {
                    val ok = dispatchExtractor(fixUrl(jsUrl), subtitleCallback, callback)
                    if (ok) dispatched = true
                }
                val locUrl = Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
                    .find(js)?.groupValues?.get(1)
                if (!locUrl.isNullOrBlank()) {
                    val ok = dispatchExtractor(fixUrl(locUrl), subtitleCallback, callback)
                    if (ok) dispatched = true
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Dooplay /links/ resolution failed for $url: ${e.message}")
        }
        return dispatched
    }

    /**
     * Resolve HubCloud/HubDrive pages — scrape for actual download links.
     * These pages often have multiple server buttons that lead to file hosters.
     */
    private suspend fun resolveHubCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val doc = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ), timeout = 15).document

            // Try #download links first
            val downloadLinks = doc.select("#download a[href], .download a[href], a[href*=download]")
                .mapNotNull { it.attr("abs:href").ifBlank { null } }
                .filter { it.startsWith("http", ignoreCase = true) }
                .distinct()

            for (link in downloadLinks) {
                try {
                    val ok = dispatchExtractor(link, subtitleCallback, callback)
                    if (ok) found = true
                } catch (_: Exception) {}
            }

            // Also try any external links on the page
            if (downloadLinks.isEmpty()) {
                val allLinks = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    href.takeIf {
                        it.startsWith("http", ignoreCase = true) &&
                        !IGNORE_HOST_REGEX.containsMatchIn(it) &&
                        !it.contains("hubcloud", ignoreCase = true) &&
                        !it.contains("hubdrive", ignoreCase = true)
                    }
                }.distinct()

                for (link in allLinks) {
                    try {
                        val ok = loadExtractor(link, mainUrl, subtitleCallback, callback)
                        if (ok) found = true
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HubCloud resolve failed for $url: ${e.message}")
        }
        return found
    }

    // ═══════════════════════════════════════════════════
    //  HELPER UTILITIES
    // ═══════════════════════════════════════════════════

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun guessQualityFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> "4K"
            lower.contains("1080p") && lower.contains("hevc") -> "1080p HEVC"
            lower.contains("1080p") -> "1080p"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> "HQ"
        }
    }

    private fun getQualityInt(quality: String): Int = when (quality) {
        "4K" -> Qualities.P2160.value
        "1080p HEVC", "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        else -> Qualities.P720.value
    }

    /**
     * Professional cleanTitle() — strips ALL technical/decorative tags.
     * Keeps AKA chunk if present (helps TMDB search).
     * Removes: Download, Free, Watch, Online, Full Movie, quality tags,
     * codec tags, audio tags, season/episode tags, site name, etc.
     */
    private fun cleanTitle(raw: String): String {
        val cleaned = raw
            .replace(Regex("""(?i)\s*\|.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?]"""), "")
            .replace(Regex("""(?i)\s*\(DD\s*\d.*?\)"""), "")
            .replace(Regex("""(?i)\s*\(ORG\)"""), "")
            .replace(Regex("""(?i)\bHindi Dubbed.*$"""), "")
            .replace(Regex("""(?i)\bDual Audio.*$"""), "")
            .replace(Regex("""(?i)\bWEB[-\s]?DL.*$"""), "")
            .replace(Regex("""(?i)\bBluRay.*$"""), "")
            .replace(Regex("""(?i)\bFull Movie.*$"""), "")
            .replace(Regex("""(?i)\bAll Episodes.*$"""), "")
            .replace(Regex("""(?i)\bRemastered\b"""), "")
            .replace(Regex("""(?i)\s*-\s*KMMovies.*$"""), "")
            .replace(Regex("""(?i)\s*-\s*KatMovieHD.*$"""), "")
            .replace(Regex("""(?i)\s*-\s*Khatrimaza.*$"""), "")
            .replace(Regex("""(?i)\s+AKA\s+"""), " AKA ")
            .replace(Regex("""(?i)\((Season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d+"""), "")
            .replace(Regex("""(?i)\s*(Download|Free|Watch|Online|HDRip|720p|1080p|480p|2160p|4K|HEVC|x264|x265|AV1|10bit|ESubs?|S\d{1,2}(?:E\d{1,3})?)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '|', ':')
            .trim()
        return cleaned.ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val lower = title.lowercase()
        val seriesIndicators = listOf(
            "season ", " s0", " s1", " s2", " s3", " s4", " s5",
            "episode ", " e0", " e1", "complete", " kdrama",
            " tv series", " web series", " hindi dubbe"
        )
        val seriesScore = seriesIndicators.count { lower.contains(it) }

        if (Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b""").containsMatchIn(title)) return TvType.TvSeries
        if (Regex("""(?i)\bSeason\s*\d+""").containsMatchIn(title)) return TvType.TvSeries

        return if (seriesScore > 0) TvType.TvSeries else TvType.Movie
    }

    private fun detectSearchQuality(title: String, badge: String? = null): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd")
        val combined = (badge.orEmpty() + " " + title).lowercase()
        for (tok in tokens) {
            if (combined.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    // ═══════════════════════════════════════════════════
    //  TMDB METADATA
    // ═══════════════════════════════════════════════════

    private data class TmdbDetails(
        val title: String?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val year: Int?,
        val rating: Score?,
        val genres: List<String>,
        val actors: List<ActorData>,
        val trailer: String?,
        val totalSeasons: Int?
    )

    private data class TmdbSeason(
        val seasonNumber: Int,
        val episodes: List<TmdbEpisode>
    )

    private data class TmdbEpisode(
        val episodeNumber: Int,
        val name: String?,
        val overview: String?,
        val stillUrl: String?,
        val airDate: String?,
        val rating: Score?
    )

    private suspend fun resolveTmdbId(
        imdbId: String?,
        cleanedTitle: String,
        isSeries: Boolean,
        yearHint: Int? = null
    ): Int? {
        if (!imdbId.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(
                    app.get(
                        "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id",
                        timeout = 15
                    ).text
                )
                val arrKey = if (isSeries) "tv_results" else "movie_results"
                json.optJSONArray(arrKey)?.optJSONObject(0)?.optInt("id", 0)
                    ?.takeIf { it > 0 }?.let { return it }
            }.onFailure { Log.w(TAG, "TMDB find-by-imdb failed: ${it.message}") }
        }

        return runCatching {
            val queryTitle = cleanedTitle.substringBefore("(").trim()
            val q = URLEncoder.encode(queryTitle, "UTF-8")
            val json = JSONObject(
                app.get("$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$q", timeout = 15).text
            )
            val results = json.optJSONArray("results") ?: return@runCatching null
            val targetType = if (isSeries) "tv" else "movie"

            var bestId = 0
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") != targetType) continue
                val id = item.optInt("id", 0).takeIf { it > 0 } ?: continue

                val candidates = listOf(
                    item.optString("title"),
                    item.optString("name"),
                    item.optString("original_title"),
                    item.optString("original_name")
                ).filter { it.isNotBlank() }

                val maxSim = candidates.maxOfOrNull { titleSimilarity(it, queryTitle) } ?: 0.0

                val itemYear = item.optString("release_date", item.optString("first_air_date", ""))
                    .substringBefore("-").toIntOrNull()
                val yearBonus = if (yearHint != null && itemYear == yearHint) 0.15 else 0.0

                val score = maxSim + yearBonus
                if (score > bestScore && score >= 0.5) {
                    bestScore = score
                    bestId = id
                }
            }
            bestId.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun titleSimilarity(a: String, b: String): Double {
        val la = a.lowercase().split(Regex("""\s+""")).toSet()
        val lb = b.lowercase().split(Regex("""\s+""")).toSet()
        if (la.isEmpty() || lb.isEmpty()) return 0.0
        val intersection = la.intersect(lb).size
        val union = la.union(lb).size
        return intersection.toDouble() / union.toDouble()
    }

    private suspend fun fetchTmdbDetails(id: Int, isSeries: Boolean): TmdbDetails? {
        return runCatching {
            val type = if (isSeries) "tv" else "movie"
            val json = JSONObject(
                app.get("$TMDB_API/$type/$id?api_key=$TMDB_API_KEY&append_to_response=credits,videos", timeout = 15).text
            )

            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "$TMDB_IMG$it" }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "$TMDB_IMG$it" }

            val genres = json.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            } ?: emptyList()

            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 10)).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val n = c.optString("name").ifBlank { c.optString("original_name") }
                    if (n.isBlank()) return@mapNotNull null
                    val pf = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
                    val ch = c.optString("character").takeIf { it.isNotBlank() }
                    ActorData(Actor(n, pf), roleString = ch)
                }
            } ?: emptyList()

            val trailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).firstOrNull { i ->
                    val v = arr.optJSONObject(i)
                    v?.optString("type") == "Trailer" && v.optString("site") == "YouTube"
                }?.let { arr.optJSONObject(it)?.optString("key") }
            }?.let { "https://www.youtube.com/watch?v=$it" }

            TmdbDetails(
                title = json.optString("title", json.optString("name", "")).ifBlank { null },
                poster = poster,
                backdrop = backdrop,
                overview = json.optString("overview").ifBlank { null },
                year = json.optString("release_date", json.optString("first_air_date", ""))
                    .substringBefore("-").toIntOrNull(),
                rating = json.optDouble("vote_average", 0.0).let {
                    if (it > 0) Score.from10(it.toString()) else null
                },
                genres = genres,
                actors = actors,
                trailer = trailer,
                totalSeasons = if (isSeries) json.optInt("number_of_seasons", 0).takeIf { it > 0 } else null
            )
        }.onFailure { Log.w(TAG, "TMDB details fetch failed: ${it.message}") }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tvId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = JSONObject(
                app.get("$TMDB_API/tv/$tvId/season/$seasonNum?api_key=$TMDB_API_KEY", timeout = 15).text
            )
            val episodes = json.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    TmdbEpisode(
                        episodeNumber = e.optInt("episode_number", 0),
                        name = e.optString("name").ifBlank { null },
                        overview = e.optString("overview").ifBlank { null },
                        stillUrl = e.optString("still_path").takeIf { it.isNotBlank() }
                            ?.let { "$TMDB_IMG$it" },
                        airDate = e.optString("air_date").ifBlank { null },
                        rating = e.optDouble("vote_average", 0.0).let {
                            if (it > 0) Score.from10(it.toString()) else null
                        }
                    )
                }
            } ?: emptyList()

            TmdbSeason(seasonNumber = seasonNum, episodes = episodes)
        }.onFailure { Log.w(TAG, "TMDB season fetch failed: ${it.message}") }.getOrNull()
    }

    // ═══════════════════════════════════════════════════
    //  CINEMETA METADATA
    // ═══════════════════════════════════════════════════

    private data class CinemetaMeta(
        val name: String?,
        val poster: String?,
        val background: String?,
        val description: String?,
        val genre: List<String>,
        val cast: List<String>,
        val imdbRating: String?,
        val year: String?,
        val videos: List<CinemetaVideo>?
    )

    private data class CinemetaVideo(
        val season: Int?,
        val episode: Int?,
        val name: String?,
        val title: String?,
        val overview: String?,
        val thumbnail: String?,
        val released: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        return runCatching {
            val type = if (isSeries) "series" else "movie"
            val json = JSONObject(
                app.get("$CINEMETA/$type/$imdbId.json", timeout = 15).text
            ).optJSONObject("meta") ?: return null

            val videos = json.optJSONArray("videos")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    CinemetaVideo(
                        season = v.optInt("season", -1).takeIf { it > 0 },
                        episode = v.optInt("episode", -1).takeIf { it > 0 },
                        name = v.optString("name").ifBlank { null },
                        title = v.optString("title").ifBlank { null },
                        overview = v.optString("overview").ifBlank { null },
                        thumbnail = v.optString("thumbnail").ifBlank { null },
                        released = v.optString("released").ifBlank { null }
                    )
                }
            }

            CinemetaMeta(
                name = json.optString("name").ifBlank { null },
                poster = json.optString("poster").ifBlank { null },
                background = json.optString("background").ifBlank { null },
                description = json.optString("description").ifBlank { null },
                genre = json.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() },
                cast = json.optString("cast").split(",").map { it.trim() }.filter { it.isNotBlank() },
                imdbRating = json.optString("imdbRating").ifBlank { null },
                year = json.optString("year").ifBlank { null },
                videos = videos
            )
        }.onFailure { Log.w(TAG, "Cinemeta fetch failed: ${it.message}") }.getOrNull()
    }

    // ═══════════════════════════════════════════════════
    //  SKYDROP API RESPONSE MODEL
    // ═══════════════════════════════════════════════════

    private data class SkydropResponse(
        @JsonProperty("success") val success: Boolean? = false,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("download_url") val download_url: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
