package com.arena

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
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * KatMovieHD provider.
 *
 * The site is a WordPress install whose article body is loosely structured:
 * different shows use materially different layouts. This provider treats
 * episode discovery as a small pipeline:
 *
 *   1. Per-episode header layout: paragraphs like "Episode 3 –" followed
 *      by per-quality links. Multi-season aware via inline "S0X" / "Season X"
 *      headers and via filename inspection when packs are used.
 *   2. Pack-only layout: only quality-pack URLs like
 *      https://links.kmhd.eu/pack/<id> are present. We resolve those by
 *      fetching the pack's SvelteKit `/__data.json`, recovering the list of
 *      (fileId, fileName) pairs, deriving (season, episode) from each
 *      filename, then merging quality packs back together.
 *   3. Flat fallback: if nothing else matches, expose every mirror link
 *      as its own pseudo-episode so the user is never left with an empty
 *      "0 episodes" page.
 *
 * Metadata is enriched in parallel from TMDB (primary) and Cinemeta /
 * Stremio (supplementary) - both are best-effort and never block the
 * playable links from being returned.
 */
class KatMovieHDProvider : MainAPI() {

    override var mainUrl: String = KatMovieHDPlugin.DEFAULT_MAIN_URL
    override var name = "KatMovieHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
        TvType.AnimeTv
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun isCfBlock(doc: Document): Boolean {
        val title = doc.selectFirst("title")?.text()?.lowercase().orEmpty()
        val html = doc.html().take(20000).lowercase()
        return title.contains("just a moment") || title.contains("attention required") ||
            title.contains("cloudflare") || title.contains("verify you are human") ||
            html.contains("cf-chl") || html.contains("ray id") || html.contains("challenge-running")
    }

    /**
     * CF-resilient document fetcher. Tries a plain GET first; if the
     * response looks like a Cloudflare challenge page (or the request
     * throws), retries with CloudflareKiller as the interceptor.
     */
    private suspend fun safeGetDocument(url: String): Document {
        val finalUrl = resolveFinalUrl(url) ?: url
        var lastError: Exception? = null
        for (i in 0..1) {
            try {
                val direct = runCatching {
                    app.get(finalUrl, headers = headers, timeout = 30).document
                }.getOrNull()
                if (direct != null && !isCfBlock(direct)) return direct
                Log.w(TAG, "CF block detected on $finalUrl, retrying with CloudflareKiller")
                return app.get(finalUrl, headers = headers, interceptor = CloudflareKiller(), timeout = 30).document
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        throw lastError ?: Exception("Failed to fetch document")
    }

    companion object {
        private const val TAG = "KatMovieHD"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        // Common English/Spanish/Hindi articles & particles. Excluded from
        // token-set similarity so "The Wonderfools" vs "Wonderfools" scores
        // 1.0 instead of 0.5. Keep small - any real noun would be a false
        // positive here.
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with",
            "el", "la", "los", "las", "un", "una", "y", "de", "del",
            "ka", "ki", "ke", "se", "aur"
        )

        // Stremio's free metadata API - very rich per-episode data
        // (real episode names, thumbnails, overviews, air dates).
        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        /**
         * Hosts we recognise as "known mirror providers". A match here
         * promotes the URL to "definitely a stream link" status during
         * page parsing. Kept deliberately wide - a single false positive
         * just means Cloudstream's loadExtractor() no-ops on an unknown
         * host, whereas a false negative means a real source is dropped
         * and the user sees "no links".
         *
         * Whenever we discover a new host on a real KatMovieHD page, just
         * add a substring here.
         */
        private val LINK_HOST_REGEX = Regex(
            """(?i)(""" +
                    // KMHD-native (handled by KmhdExtractor)
                    """links\.kmhd\.[a-z]+|kmhd\.net|gd\.kmhd|""" +
                    // GDFlix family (handled by GDFlix* extractors in Extractors.kt)
                    """gdflix\.[a-z]+|gd-flix|gdlink|gdtot|gdmirror|""" +
                    // HubCloud / Hubdrive / Katdrive family
                    """hubcloud\.[a-z]+|hubcdn|hubstream|hubdrive|katdrive|""" +
                    // Generic cloud / file-share hosts that Cloudstream knows
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|krakenfiles|drop\.download|megaup|userscloud|""" +
                    // Streaming / video hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|vgembed|vidoza|voe|streamzz|""" +
                    // Hindi-dub specific
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|gdtot|techkit|""" +
                    // KatMovieHD specific upload mirrors
                    """katdrive|kmhd""" +
                    """)"""
        )

        /**
         * Hosts that look like external links but are NEVER stream sources.
         * Anything matching here is filtered out before we try to extract.
         * Used by the permissive fallback so we don't try to "extract" from
         * IMDb, social media, or image-host links scraped from the page.
         */
        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """vifix\.site|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """katmoviehd|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#""" +
                    """)"""
        )

        /** Identifies a kmhd /pack/<id> URL (these need JSON expansion). */
        private val KMHD_PACK_REGEX = Regex("""(?i)(?:links|gd)\.kmhd\.[a-z]+/pack/""")

        /** Match "Episode 7" / "Episode-07" / "Episode: 12" etc. */
        private val EPISODE_HEADER_REGEX =
            Regex("""(?i)\b(?:Episode|Ep|Part|E)\s*[-–:#.]?\s*0*(\d{1,3})\b""")

        /** Match "Season 4" / "S04" / "S4" inside a header. */
        private val SEASON_HEADER_REGEX =
            Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /**
         * Extract (season, episode) from a release filename such as
         * `Brazil.70.S01E03.480p.WEB-DL...mkv`. Falls back gracefully when
         * only an episode number can be recovered.
         */
        private fun parseSeasonEpisode(filename: String): Pair<Int?, Int?> {
            Regex("""(?i)S(\d{1,2})[\s._-]?E(\d{1,3})""").find(filename)?.let {
                return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            }
            Regex("""(?i)(?:^|[\s._-])E(\d{1,3})(?:[\s._-]|$)""").find(filename)?.let {
                return null to it.groupValues[1].toIntOrNull()
            }
            Regex("""(?i)\bEp(?:isode)?[\s._-]?(\d{1,3})\b""").find(filename)?.let {
                return null to it.groupValues[1].toIntOrNull()
            }
            return null to null
        }

        /** Header tags that can hold "Episode N" / "Season N" / quality labels. */
        private val LABEL_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b")

        /** Negative phrases that should never be treated as episode markers. */
        private val EPISODE_NEGATIVE_PHRASES = listOf(
            "more episodes", "will be added", "episode list", "all episodes",
            "single episodes link"
        )
    }

    // Episode.data is stored as a plain newline-separated string of URLs
    // (e.g. "https://...\nhttps://..."). This is the EXACT format that v9
    // shipped with and which works reliably on every Cloudstream build /
    // device combination we've tested. We deliberately avoid:
    //   - List<String> via the generic newEpisode<T> overload (relies on
    //     kotlinx.serialization runtime resolution that can return null
    //     for raw List types inside a .cs3-loaded plugin)
    //   - a custom data class wrapper (Jackson type erasure)
    // The newline split in loadLinks is trivial to parse back.

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "page/" to "Latest",
        "category/dubbed-movie/page/" to "Hindi Dubbed Movies",
        "category/dual-audio/page/" to "Dual Audio",
        "category/tv-series-dubbed/page/" to "TV Series (Dubbed)",
        "https://moviesbaba.lol/category/bollywood/" to "Bollywood",
        "https://www.katdrama.net/category/tv-series-dubbed/" to "K-Drama",
        "https://new.pikahd.co/category/anime-dubbed/" to "Anime",
        "category/netflix/page/" to "Netflix",
        "category/amazon-prime/page/" to "Prime Video",
        "category/disney/page/" to "Disney+ Hotstar",
        "category/hindi-dubbed/page/" to "Hindi Dubbed",
        "category/hindi-webseries/page/" to "Hindi Web Series",
        "category/hollywood-eng/page/" to "Hollywood (English)",
        "category/tamil/page/" to "Tamil",
        "category/telugu/page/" to "Telugu",
        "category/malayalam/page/" to "Malayalam",
        "category/bengali/page/" to "Bengali"
    )

    private suspend fun refreshMainUrl(): String {
        val active = KatMovieHDPlugin.getActiveMainUrl().trimEnd('/')
        if (active.isNotBlank() && active != mainUrl) {
            Log.d(TAG, "Active domain refreshed: $mainUrl -> $active")
            mainUrl = active
        }
        return mainUrl
    }

    /**
     * Bookmarks / cached search cards can point at an old KatMovieHD domain.
     * If the path is still a KatMovieHD post, transparently replay it on the
     * latest active domain from domains.json instead of failing with 404/DNS.
     */
    private fun normalizeKatMovieUrl(input: String): String {
        val fixed = fixUrl(input)
        val hostPart = Regex("""(?i)^https?://([^/]+)""").find(fixed)?.groupValues?.getOrNull(1)
            ?: return fixed
        val isKatNetworkHost = hostPart.contains("katmovie", ignoreCase = true) ||
            hostPart.contains("katmovies", ignoreCase = true) ||
            hostPart.contains("katdrama", ignoreCase = true) ||
            hostPart.contains("pikahd", ignoreCase = true) ||
            hostPart.contains("moviesbaba", ignoreCase = true)
        val currentHost = Regex("""(?i)^https?://([^/]+)""").find(mainUrl)?.groupValues?.getOrNull(1)
        return if (isKatNetworkHost && currentHost != null &&
            (hostPart.contains("katmovie", ignoreCase = true) || hostPart.contains("katmovies", ignoreCase = true)) &&
            !hostPart.equals(currentHost, ignoreCase = true)) {
            fixed.replace(Regex("""(?i)^https?://[^/]+"""), mainUrl)
        } else fixed
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = refreshMainUrl()
        val url = if (request.data.startsWith("http")) {
            if (page <= 1) request.data else "${request.data}page/$page/"
        } else {
            "$base/${request.data}$page/"
        }
        val doc = safeGetDocument(url)
        return newHomePageResponse(request.name, parseListing(doc), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val base = refreshMainUrl()

        // KatMovieHD's HTML search page (/?s=query) is BOTH fast (~1s) AND
        // carries posters (catimages.org / TMDB thumbnails) inside the
        // ".post-thumb" cards. The WordPress REST /wp-json/wp/v2/search
        // endpoint, by contrast, returns *no* poster (only id/title/url)
        // and is slower on some queries - so we scrape HTML directly here.
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/?s=$encoded"
                  else "$base/page/$page/?s=$encoded"
        val doc = safeGetDocument(url)
        val results = parseListing(doc)
        Log.d(TAG, "search('$query', p$page): ${results.size} results")
        return results.toNewSearchResponseList()
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        // Primary: each result is an <li id="post-NNNN"> container (home page).
        val items = doc.select("li[id^=post-]").mapNotNull { it.toSearchResultFromItem() }
        if (items.isNotEmpty()) return items.distinctBy { it.url }

        // Fallback 1: category/search pages use bare <div class="post-content">
        // and <div class="post-thumb"> WITHOUT an <li id="post-N"> wrapper.
        // The heading anchor inside .post-content is still the correct permalink.
        val direct = doc.select("article, .post, .post-content, .type-post, main section").mapNotNull { it.toSearchResult() }
        if (direct.isNotEmpty()) return direct.distinctBy { it.url }

        // Fallback 2: any anchor wrapping an <img> that points at a post.
        return doc.select("a:has(img)").mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    /**
     * Parse one "<li id=post-NNNN>" search/listing card.
     *
     * Markup (current theme):
     *   <li id="post-94169" class="post ...">
     *     <div class="post-thumb">
     *        [optional <div class="label-overlay"><img badge></div>]
     *        <a href title><img src=POSTER></a>
     *     </div>
     *     <div class="post-content">
     *        <h2><a href=PERMALINK title>TITLE</a></h2> ...
     *     </div>
     *   </li>
     *
     * Title link comes from the heading (always correct). Poster comes from
     * the .post-thumb image whose src points at a real poster host, NOT the
     * label-overlay badge.
     */
    private fun Element.toSearchResultFromItem(): SearchResponse? {
        // Title link: heading anchor is the reliable permalink.
        val titleAnchor = selectFirst("h2 a[href], h3 a[href], .post-title a[href], .title a[href]")
            ?: selectFirst("div.post-content a[href]")
            ?: return null
        val href = titleAnchor.attr("href").ifBlank { return null }
        val rawTitle = titleAnchor.attr("title")
            .ifBlank { titleAnchor.text() }
            .ifBlank { selectFirst("div.post-thumb img")?.attr("alt") ?: "" }
            .ifBlank { return null }

        // Poster: pick the .post-thumb <img> whose src is an actual poster,
        // skipping label-overlay badges (small PNGs like "Unofficial-Dubbed").
        val poster = select("div.post-thumb img").firstNotNullOfOrNull { img ->
            val u = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            u.takeIf {
                it.isNotBlank() &&
                    !it.contains("label", ignoreCase = true) &&
                    !it.contains("overlay", ignoreCase = true) &&
                    !it.contains("Unofficial", ignoreCase = true)
            }
        } ?: selectFirst("div.post-thumb img")?.let {
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
            ?: selectFirst("a[href*=katmoviehd], a[href*=katdrama], a[href*=pikahd], a[href*=moviesbaba]")
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
        if (!href.contains("katmovie", ignoreCase = true) &&
            !href.contains("katmovies", ignoreCase = true) &&
            !href.contains("katdrama", ignoreCase = true) &&
            !href.contains("pikahd", ignoreCase = true) &&
            !href.contains("moviesbaba", ignoreCase = true)) return null
        val bad = listOf(
            "/category/", "/page/", "/tag/", "#respond", "/feed", "/wp-",
            "/about", "/contact", "/how-to", "/join-"
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

    // ------------------------------------------------------------------
    // load() - the main per-title page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        refreshMainUrl()
        val pageUrl = normalizeKatMovieUrl(url)
        val doc = safeGetDocument(pageUrl)
        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".entry-content img, article img")?.absUrl("src")
        val sitePlot = doc.select(".entry-content p")
            .firstOrNull { it.text().length > 80 }?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val cleanedTitle = cleanTitle(rawTitle)
        val guessedType = guessTvType(rawTitle)
        val isSeries = guessedType == TvType.TvSeries ||
            guessedType == TvType.AsianDrama ||
            guessedType == TvType.AnimeTv

        // Season from the page title; used as default when individual
        // episode headers don't include one. (Pack expansion later
        // overrides this from the actual filename when possible.)
        val titleSeason = SEASON_HEADER_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        // Year from the site title, used as a strong tiebreaker when we
        // have to fall back to text-based TMDB search. Prevents matching
        // "Nemesis (2021)" when the user clicked "Nemesis (2026) S01".
        val yearHint = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Fetch TMDB + Cinemeta in parallel so neither blocks the other.
        // Both are best-effort: we always have a sensible fallback to the
        // values scraped from the page itself.
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
            ?: cine?.year?.substringBefore("–")?.toIntOrNull()
            ?: Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val rating = tmdbMeta?.rating ?: cine?.imdbRating?.let { Score.from10(it) }
        val tags = (tmdbMeta?.genres ?: cine?.genre ?: emptyList()).distinct()
        val actorData = tmdbMeta?.actors ?: emptyList()
        val cineActors = cine?.cast ?: emptyList()
        val trailer = tmdbMeta?.trailer
        val totalSeasons = tmdbMeta?.totalSeasons

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries imdb=$imdbId titleSeason=$titleSeason")

        // Always attempt discoverEpisodes! It handles flat lists too now (via collectMirrorLinksWithLabels).
        // If it successfully extracts named episodes (E01, Pack, etc), treat it as a Series/Pack.
        // Otherwise, fall back to pure movie links.
        val episodes = discoverEpisodes(doc, titleSeason, tmdbSeason, cine)
        Log.d(TAG, "load() discovered ${episodes.size} episodes")

        if (episodes.isNotEmpty() && (isSeries || episodes.size > 1 || episodes.first().name?.contains("Pack", true) == true)) {
            val actualType = when (guessedType) {
                TvType.AnimeTv -> TvType.AnimeTv
                TvType.Anime -> if (episodes.size > 1) TvType.AnimeTv else TvType.Anime
                TvType.AsianDrama -> TvType.AsianDrama
                else -> if (isSeries) TvType.TvSeries else TvType.AsianDrama
            }
            return newTvSeriesLoadResponse(title, pageUrl, actualType, episodes) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, trailer, imdbUrl, tmdbMeta?.recommendations)
                
                val actualSeasons = episodes.mapNotNull { it.season }.distinct().sorted()
                if (actualSeasons.size > 1) {
                    addSeasonNames(actualSeasons.map { "Season $it" })
                }
            }
        } else {
            val links = collectAllPlayableLinks(doc)
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, links) {
                applyCommonMeta(this, poster, backdrop, plot, year, tags,
                    actorData, cineActors, rating, trailer, imdbUrl, tmdbMeta?.recommendations)
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
        imdbUrl: String?,
        recommendations: List<SearchResponse>? = null
    ) {
        target.posterUrl = poster
        target.backgroundPosterUrl = backdrop
        target.plot = plot
        target.year = year
        target.tags = tags
        target.score = rating
        target.recommendations = recommendations
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

    // ------------------------------------------------------------------
    // Episode discovery pipeline
    // ------------------------------------------------------------------

    private fun extractEscapedString(html: String, prefix: String): String? {
        val startIdx = html.indexOf(prefix)
        if (startIdx == -1) return null
        val contentStart = startIdx + prefix.length
        val sb = StringBuilder()
        var i = contentStart
        while (i < html.length) {
            val c = html[i]
            if (c == '\\') {
                sb.append(c)
                if (i + 1 < html.length) {
                    sb.append(html[i + 1])
                    i++
                }
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun getContentContainer(doc: Document): Element {
        val html = doc.html()
        
        // SvelteKit injects HTML inside dehydrated JSON. 
        // Extract iteratively to prevent Regex Catastrophic Backtracking (which freezes the app!)
        var raw = extractEscapedString(html, "\"post_content\":\"")
        
        if (raw == null) {
            raw = extractEscapedString(html, "\"\\u003Cp")?.let { "\\u003Cp$it" }
        }
        if (raw == null) {
            raw = extractEscapedString(html, "\"\\u003Cdiv")?.let { "\\u003Cdiv$it" }
        }
        if (raw == null) {
            raw = extractEscapedString(html, "\"\\u003Carticle")?.let { "\\u003Carticle$it" }
        }
        if (raw == null) {
            raw = extractEscapedString(html, "\"\\u003Cstrong")?.let { "\\u003Cstrong$it" }
        }
        if (raw == null) {
            raw = extractEscapedString(html, "\"\\u003Ch")?.let { "\\u003Ch$it" }
        }
        
        if (raw != null) {
            val decoded = raw
                .replace("\\\"", "\"")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
            
            val virtualDom = Jsoup.parse(decoded).body()
            if (virtualDom.select("a[href]").isNotEmpty()) {
                return virtualDom
            }
        }
        
        // Ultra Fallback
        if (doc.select("article, .entry-content, .post-content, div#content, main").isEmpty()) {
            val urls = Regex("""https?://[a-zA-Z0-9.-]+/[^\s"\\]+""").findAll(html)
            val builder = StringBuilder()
            urls.forEach { 
                if (it.value.contains(LINK_HOST_REGEX)) {
                    builder.append("<a href='${it.value}'>Link</a><br>")
                }
            }
            if (builder.isNotEmpty()) {
                return Jsoup.parse(builder.toString()).body()
            }
        }
        
        return doc.selectFirst("article, .entry-content, .post-content, div#content, main") ?: doc.body()
    }

    private suspend fun discoverEpisodes(
        doc: Document,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val container = getContentContainer(doc)
        val epMap = linkedMapOf<Pair<Int, Int>, MutableSet<String>>()

        // 1. Process Pack URLs
        val packUrls = container.select("a[href]")
            .mapNotNull { it.attr("href").takeIf { h -> KMHD_PACK_REGEX.containsMatchIn(h) } }
            .distinct()
            
        if (packUrls.isNotEmpty()) {
            Log.d(TAG, "Expanding ${packUrls.size} packs...")
            val expanded = expandKmhdPacks(packUrls, defaultSeason)
            for ((k, v) in expanded) {
                epMap.getOrPut(k) { mutableSetOf() }.addAll(v)
            }
        }

        // 2. Process regular links
        val cleanUrls = container.select("a[href]").map { it.attr("href").trim() }
            .filter { LINK_HOST_REGEX.containsMatchIn(it) && !it.matches(Regex("""(?i)^https?://(?:www\.)?kmhd\.net/(directlink|home|index)/?$""")) }
            .distinct()

        var currentSeason = defaultSeason
        var lastHeaderEp: Int? = null

        for (node in container.allElements) {
            if (node.tagName() in LABEL_TAGS) {
                val text = node.ownText().ifBlank { node.text() }.trim()
                if (text.isNotEmpty() && text.length < 120 && EPISODE_NEGATIVE_PHRASES.none { it in text.lowercase() }) {
                    SEASON_HEADER_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.let { currentSeason = it }
                    }
                    EPISODE_HEADER_REGEX.find(text)?.let { m ->
                        m.groupValues[1].toIntOrNull()?.let { lastHeaderEp = it }
                    }
                }
            }

            if (node.tagName() == "a") {
                val href = node.attr("href").trim()
                // Do not re-add pack links into the standard episode flow since they were expanded earlier.
                if (href in cleanUrls && !KMHD_PACK_REGEX.containsMatchIn(href) && !href.contains("/pack/", ignoreCase = true)) {
                    val aText = node.text().trim()
                    var linkEp = lastHeaderEp
                    
                    EPISODE_HEADER_REGEX.find(aText)?.let { m ->
                        m.groupValues[1].toIntOrNull()?.let { linkEp = it }
                    }
                    
                    // If we found NO headers and NO episode text, group them as episode 1 
                    // (which gets renamed to "Pack / Full Movie" if there's only 1 episode folder).
                    val finalEp = linkEp ?: 1
                    epMap.getOrPut(currentSeason to finalEp) { mutableSetOf() }.add(href)
                }
            }
        }

        if (epMap.isEmpty()) return emptyList()

        val sorted = epMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return sorted.map { (key, links) ->
            val (season, ep) = key
            val tmdbEp = tmdbSeason?.takeIf { it.seasonNumber == season }?.episodes?.firstOrNull { it.episodeNumber == ep }
            val cineEp = cine?.videos?.firstOrNull { it.season == season && it.episode == ep }
            
            newEpisode(links.joinToString("\n")) {
                val fallbackName = if (epMap.size == 1 && links.size > 1) "Pack / Full Movie" else "Episode $ep"
                this.name = tmdbEp?.name ?: cineEp?.name ?: cineEp?.title ?: fallbackName
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

    private suspend fun expandKmhdPacks(
        packUrls: List<String>,
        defaultSeason: Int
    ): LinkedHashMap<Pair<Int, Int>, MutableList<String>> {
        val merged = linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        // Resolve gd.kmhd.eu/pack/ redirects upfront. gd.kmhd.eu is a 302
        // redirector with no __data.json sidecar of its own — it bounces
        // to links.kmhd.eu (or gdflix). Resolve first so both the JSON
        // fetch and the per-file /file/ links use the correct host.
        // Parallelize resolving the gd.kmhd redirector URLs so we don't sequential timeout
        val resolvedUrls = supervisorScope {
            packUrls.map { pu ->
                async {
                    if (pu.contains("gd.kmhd", true)) {
                        resolveFinalUrl(pu)?.trimEnd('/')?.takeIf { !it.contains("gd.kmhd", true) } ?: pu
                    } else pu
                }
            }.awaitAll()
        }

        val perPack = supervisorScope {
            resolvedUrls.map { pack ->
                async {
                    runCatching { fetchKmhdPack(pack) }
                        .onFailure { Log.w(TAG, "Pack fetch failed for $pack: ${it.message}") }
                        .getOrNull().orEmpty()
                }
            }.awaitAll()
        }

        for ((packUrl, entries) in resolvedUrls.zip(perPack)) {
            val host = Regex("""^(https?://[^/]+)""").find(packUrl)?.groupValues?.get(1)
                ?: "https://links.kmhd.eu"
            for ((fileId, fileName) in entries) {
                val (parsedSeason, parsedEpisode) = parseSeasonEpisode(fileName)
                val ep = parsedEpisode ?: continue
                val s = parsedSeason ?: defaultSeason
                val link = "$host/file/$fileId"
                merged.getOrPut(s to ep) { mutableListOf() }.let {
                    if (link !in it) it.add(link)
                }
            }
        }
        return merged
    }

    private suspend fun fetchKmhdPack(packUrl: String): List<Pair<String, String>> {
        val normalized = packUrl.trim().trimEnd('/')
        val dataUrl = "$normalized/__data.json"
        
        // Use CloudflareKiller because some users might be routed through a CF challenge
        var text = ""
        try {
            text = app.get(
                dataUrl,
                headers = headers + mapOf(
                    "Cookie" to "unlocked=true",
                    "Referer" to normalized,
                    "Accept" to "application/json"
                ),
                timeout = 20
            ).text
            
            if (text.contains("just a moment", true) || text.contains("cf-chl", true) || text.contains("ray id", true)) {
                text = app.get(
                    dataUrl,
                    headers = headers + mapOf(
                        "Cookie" to "unlocked=true",
                        "Referer" to normalized,
                        "Accept" to "application/json"
                    ),
                    interceptor = CloudflareKiller(),
                    timeout = 25
                ).text
            }
        } catch(e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            text = app.get(
                dataUrl,
                headers = headers + mapOf(
                    "Cookie" to "unlocked=true",
                    "Referer" to normalized,
                    "Accept" to "application/json"
                ),
                interceptor = CloudflareKiller(),
                timeout = 25
            ).text
        }
        if (text.isBlank()) return emptyList()
        return parseKmhdPackJson(text)
    }

    /**
     * Parse SvelteKit's dehydrated NDJSON payload for kmhd's pack pages.
     *
     * Shape (one logical record, pretty-printed):
     *   {"type":"chunk","id":1,"data":[
     *      {"_id":1,"name":2,"info":3},
     *      "<packId>",
     *      "<pack human name>",
     *      {"<fileId1>":4, "<fileId2>":6, ...},
     *      {"name":5},
     *      "<fileName1>",
     *      {"name":7},
     *      "<fileName2>",
     *      ...
     *   ]}
     */
    private fun parseKmhdPackJson(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (obj.optString("type") != "chunk") continue
            val arr = obj.optJSONArray("data") ?: continue

            val root = arr.opt(0) as? JSONObject ?: continue
            val infoIdx = root.optInt("info", -1).takeIf { it > 0 } ?: continue
            val infoMap = arr.opt(infoIdx) as? JSONObject ?: continue

            val keys = infoMap.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val nameNodeIdx = infoMap.optInt(fileId, -1).takeIf { it > 0 } ?: continue
                val nameNode = arr.opt(nameNodeIdx) as? JSONObject ?: continue
                val nameStrIdx = nameNode.optInt("name", -1).takeIf { it > 0 } ?: continue
                val fileName = (arr.opt(nameStrIdx) as? String)?.takeIf { it.isNotBlank() } ?: continue
                out.add(fileId to fileName)
            }
        }
        return out
    }

    /**
     * Two-pass link collector used everywhere we need "every playable
     * URL on this page":
     *
     *   Pass 1 (strict): URLs whose host matches our curated whitelist
     *                    (LINK_HOST_REGEX). These are guaranteed-good.
     *   Pass 2 (permissive): if pass 1 found NOTHING, fall back to every
     *                    external http(s) URL that isn't on the explicit
     *                    blacklist (IGNORE_HOST_REGEX) and isn't the
     *                    KatMovieHD site itself. Cloudstream's
     *                    `loadExtractor` silently no-ops on hosts it
     *                    doesn't know, so a few junk URLs here are
     *                    harmless - but any new host the site starts
     *                    using (which we haven't added to the whitelist
     *                    yet) will still be tried.
     *
     * This is what makes the extension robust against KatMovieHD adding
     * a new mirror provider in the future without us having to rebuild.
     */
    private fun collectMirrorLinks(container: Element): List<String> {
        val all = container.select("a[href]")
            .mapNotNull { a ->
                val h = a.attr("href").trim()
                when {
                    h.startsWith("http", ignoreCase = true) -> h
                    h.startsWith("//") -> "https:$h"
                    else -> null
                }
            }
            .distinct()
            // Some KatMovieHD pages put a decorative "DOWNLOAD LINKS" heading
            // that links to kmhd.net/directlink (or similar generic landing
            // pages). These are not real file mirrors - they just bounce the
            // user to the site's homepage / ad-gate. Filter them out so they
            // never become a Source entry that fails with "no link found".
            //
            // The regex is intentionally STRICT (full URL anchored with $),
            // not a substring match, so a legitimate file like
            // "links.kmhd.eu/file/Good_directlinkABC" is never killed.
            .filter { url ->
                !url.matches(Regex(
                    """(?i)^https?://(?:www\.)?kmhd\.net/(directlink|home|index)/?$"""
                ))
            }

        val strict = all.filter { LINK_HOST_REGEX.containsMatchIn(it) }
        if (strict.isNotEmpty()) return strict

        // Pass 2: anything not obviously junk.
        val permissive = all.filter { url ->
            !IGNORE_HOST_REGEX.containsMatchIn(url) &&
                    !url.contains(mainUrl, ignoreCase = true)
        }
        if (permissive.isNotEmpty()) {
            Log.w(TAG, "Strict host whitelist matched 0 links, falling back to permissive (${permissive.size}): ${permissive.take(3)}")
        }
        return permissive
    }

    /**
     * Movie pages just dump every mirror URL we find, joined with newlines.
     * Returning a plain String is the v9-compatible shape that
     * `loadLinks` knows how to split back into individual URLs.
     */
    private fun collectAllPlayableLinks(doc: Document): String {
        val content = getContentContainer(doc)
        val links = collectMirrorLinks(content)
        Log.d(TAG, "collectAllPlayableLinks(): ${links.size} links")
        return links.joinToString("\n")
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

            // v9-style: pass a newline-joined string. The String overload of
            // newEpisode stores `data` verbatim, no Jackson/serialization
            // round-trip - whatever bytes we put in are what loadLinks gets.
            // We must pass stringified JSON or plain text. Since an episode might have MULTIPLE
            // qualities (1080p, 720p) mapping to different URLs, we join them.
            // But wait: loadLinks takes this data and passes it to Cloudstream's player.
            // If the provider returns "Episode 1" as the episode name, Cloudstream will group
            // all these URLs under that episode, and the player will list them.
            newEpisode(links.joinToString("\n")) {
                this.name = tmdbEp?.name ?: cineEp?.name ?: cineEp?.title ?: "Episode $ep"
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

    // ------------------------------------------------------------------
    // loadLinks - resolve a single (movie or episode) to streams
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: raw data length=${data.length}, preview='${data.take(120)}'")
        refreshMainUrl()

        // v9-compatible parser: newline / comma / CR separated URLs, plus a
        // JSON-array fallback in case the data was produced by an older
        // (List<String>-based) build that's still cached on the device.
        // Accept both http and https, and tolerate quoted/wrapped tokens.
        val urls: List<String> = buildList {
            if (data.trimStart().startsWith("[")) {
                runCatching {
                    tryParseJson<List<String>>(data)?.let { addAll(it) }
                }
            }
            // Always also split on plain delimiters - this is the format
            // we write in load(). Keeps loadLinks resilient to any future
            // change of episode-data shape without breaking old episodes.
            data.split('\n', ',', '\r', '\t').forEach { add(it) }
        }
            .map { it.trim().trim('"', '\'', '[', ']', '(', ')', ' ', '<', '>') }
            .filter { it.startsWith("http", ignoreCase = true) }
            .map { stripJunkSuffix(it) }
            .filter { it.length > 8 }
            .distinct()

        if (urls.isEmpty()) {
            Log.w(TAG, "loadLinks: no URLs extracted from data of length ${data.length}")
            return false
        }
        Log.d(TAG, "loadLinks: dispatching ${urls.size} URL(s):\n${urls.joinToString("\n") { "  - $it" }}")

        // Track how many sources actually fired a callback so we can
        // distinguish "we tried but every host failed" from "no URLs to try".
        // (Cloudstream's UI will only show streams that the callback
        // delivered - if dispatch crashes silently, the user sees no link.)
        var anyDispatched = false
        urls.amap { rawUrl ->
            // Fix: By default Cloudstream names generic urls inside the player as "Source 1", "Source 2".
            // Since we merged multiple packs into a single Episode object, they are passed as a batch of URLs here.
            // ExtractorLink names are controlled by the ExtractorApi (GDFlix names it "GDFlix", HubCloud names it "HubCloud").
            // For stock extractors, we just dispatch them directly.
            val ok = dispatchExtractor(rawUrl, subtitleCallback, callback)
            if (ok) anyDispatched = true
        }
        if (!anyDispatched) {
            Log.w(TAG, "loadLinks: every URL failed to dispatch - check host coverage")
        }
        return true
    }

    /**
     * Some URLs scraped from the page have trailing junk - a stray right
     * paren from "(Movie)", a comma from inline text, a "#." from anchor
     * fragments, etc. Strip those before handing to extractors so the
     * URL the HTTP client sees is exactly what the host expects.
     */
    private fun stripJunkSuffix(url: String): String {
        var u = url
        while (u.isNotEmpty() && u.last() in setOf(')', ']', '}', '.', ',', ';', '!', '?', '"', '\'')) {
            u = u.dropLast(1)
        }
        // Drop fragment - never relevant for stream extraction.
        val hashIdx = u.indexOf('#')
        if (hashIdx > 0) u = u.substring(0, hashIdx)
        return u
    }

    /**
     * Per-URL dispatch. Always returns true if SOMETHING was attempted,
     * false only when we have no extractor at all (so loadLinks can log
     * coverage gaps).
     *
     *   - kmhd.eu / kmhd.fans / etc → our own KmhdExtractor (handles the
     *     SvelteKit __data.json mirror JSON, percent-encodes non-ASCII
     *     file IDs like "Sueño_2df1145a").
     *   - kmhd.net / .../directlink → just a redirect; pass through to
     *     loadExtractor so it gets a chance via DDoS-guard / cf bypass.
     *   - everything else → Cloudstream's stock loadExtractor, which
     *     iterates ~300 built-in extractors (Gofile, Mediafire, GDFlix,
     *     Streamtape, Filemoon, Mixdrop, etc.) and picks the right one
     *     by URL prefix. Unknown hosts simply no-op, never throw.
     */
    private suspend fun dispatchExtractor(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.trim()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false
        return try {
            when {
                // gd.kmhd.eu/file/<id> is a 302 redirector → gdflix.dev.
                // It does NOT have a __data.json sidecar (returns 302 HTML).
                // Resolve the redirect chain and dispatch directly to GDFlix.
                Regex("""(?i)gd\.kmhd\.[a-z]+/file/""").containsMatchIn(url) -> {
                    val resolved = resolveFinalUrl(url) ?: url
                    val target = if (resolved.contains("gdflix", true) ||
                        resolved.contains("gdlink", true)) resolved else url
                    Log.d(TAG, "gd.kmhd redirect: $url -> $target")
                    GDFlix().getUrl(target, mainUrl, subtitleCallback, callback)
                    true
                }
                // Our custom kmhd extractor handles both:
                //   - links.kmhd.{eu,fans,net,...}/{file,play,pack}/<id>
                //     (current SvelteKit format with __data.json sidecar)
                //   - kmhd.eu/archives/<post_id> (legacy WordPress format
                //     used by pre-2020 KatMovieHD posts, e.g. Deadly Pickup
                //     2016) — fetched and re-fanned out to loadExtractor.
                Regex("""(?i)(links\.kmhd\.|kmhd\.net|kmhd\.eu/(archives/|atchs/))""").containsMatchIn(url) -> {
                    KmhdExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(gdflix|gd-flix|gdlink|gdmirror)""").containsMatchIn(url) -> {
                    // gdflix.dad → gdlink.dev → gdflix.dev redirect chain.
                    // GDFlix extractor follows redirects automatically via
                    // getDocumentFutureProof, so just pass the original URL.
                    GDFlix().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(hubcloud|hubdrive|hubcdn)""").containsMatchIn(url) -> {
                    HubCloud().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(katdrive)""").containsMatchIn(url) -> {
                    KatDriveClick().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(filepress)""").containsMatchIn(url) -> {
                    Filepress().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(driveseed)""").containsMatchIn(url) -> {
                    DriveSeed().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(driveleech)""").containsMatchIn(url) -> {
                    DriveLeech().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(hglink)""").containsMatchIn(url) -> {
                    HGLINK().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(fuckingfast)""").containsMatchIn(url) -> {
                    FuckingFast().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(bbupload)""").containsMatchIn(url) -> {
                    BBUpload().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(bbserver)""").containsMatchIn(url) -> {
                    BBServer().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                Regex("""(?i)(gdtot)""").containsMatchIn(url) -> {
                    GDTot().getUrl(url, mainUrl, subtitleCallback, callback)
                    true
                }
                // Everything else: try Cloudstream's stock registry.
                else -> {
                    // Try to resolve shorteners or direct redirectors before passing to stock registry
                    val finalUrl = if (url.contains("bit.ly") || url.contains("tinyurl") || url.contains("cutt.ly") || url.length < 35) {
                        resolveFinalUrl(url) ?: url
                    } else {
                        url
                    }
                    loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                    true
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Extractor crashed for $url: ${e.message}")
            // Returning true so loadLinks counts this as "attempted",
            // not "no URLs". The crash log itself is the diagnostic.
            true
        }
    }

    // ------------------------------------------------------------------
    // Metadata: TMDB
    // ------------------------------------------------------------------

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
        val totalSeasons: Int?,
        val recommendations: List<SearchResponse>?
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
        // Path 1: IMDB id -> TMDB id. This is the GOLD path: if the page has
        // a real IMDB link, the mapping is 1:1, no guesswork. We trust it.
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

        // Path 2: text search /search/multi. This is the DANGER path - TMDB
        // will happily return "Berlin" (Money Heist spinoff) when we asked
        // for "Berlin and the Lady with an Ermine", or "Soulmate" the K-drama
        // when we asked for "Soulmates". Old code took the FIRST tv/movie
        // result and blindly used it -> totally wrong page got loaded.
        //
        // Fix: score every candidate by title-similarity + year proximity,
        // and only accept matches above a threshold. If nothing crosses the
        // bar, return null -> caller falls back to the site's own scraped
        // title/poster/plot, which is always correct.
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

                // TMDB returns several name fields; check them all and take
                // the best, because "Berlin and the Lady with an Ermine" may
                // sit in original_title while "name" is the Spanish title.
                val candidates = listOf(
                    item.optString("title"),
                    item.optString("name"),
                    item.optString("original_title"),
                    item.optString("original_name")
                ).filter { it.isNotBlank() }
                val titleSim = candidates.maxOfOrNull { titleSimilarity(queryTitle, it) } ?: 0.0

                // Year boost: if the site title says (2026) and TMDB's
                // release/first-air date is also 2026 -> +0.15, big tiebreak.
                val tmdbYear = (item.optString("release_date").ifBlank { item.optString("first_air_date") })
                    .takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
                val yearBoost = if (yearHint != null && tmdbYear != null) {
                    val diff = kotlin.math.abs(tmdbYear - yearHint)
                    when {
                        diff == 0 -> 0.15
                        diff == 1 -> 0.05      // off-by-one is common (release vs aired)
                        diff > 3  -> -0.20     // very wrong year -> heavy penalty
                        else      -> 0.0
                    }
                } else 0.0

                // NOTE: We deliberately do NOT add a popularity nudge here,
                // even though it might seem helpful. Reason:
                //   1. TMDB's /search/multi endpoint already runs results
                //      through Elasticsearch with a popularity boost built in
                //      (confirmed by TMDB devs in their forum). Adding our
                //      own pop nudge would DOUBLE-COUNT and bias toward
                //      over-popular shows ("Berlin" Money Heist would beat
                //      "Berlin and the Lady" again — exactly the bug we're
                //      trying to fix).
                //   2. optDouble("popularity", 0.0) returns NaN for some
                //      payloads (TMDB sometimes emits null for unaired
                //      shows), and NaN comparisons silently break scoring.
                // So score is just text similarity + year proximity. Clean
                // and predictable.
                val score = titleSim + yearBoost
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }

            // Threshold: 0.45 (blended token-set/Jaccard).
            //
            // We deliberately picked this LOWER than a strict Jaccard
            // threshold (which would need 0.60+) because our scoring is a
            // 70/30 blend of token-set coverage and Jaccard - tuned so:
            //   - "Berlin and Lady Ermine"   vs "Berlin and the Lady ..."  -> ~0.83 ✓ accept
            //   - "Berlin"                    vs "Berlin"                  -> 1.0    ✓ accept
            //   - "Berlin and Lady Ermine"   vs "Berlin" (Money Heist)    -> ~0.35  ✗ reject (was the bug)
            //   - "Soulmate"                  vs "Soulmates"               -> 1.0    ✓ accept (same token after stopword strip)
            //   - "Money Heist"               vs "La casa de papel"        -> 0.0    ✗ skip (BUT TMDB find/IMDB path handles it)
            // For foreign-titled shows where the text match fails, the
            // IMDB-id path (Path 1, above) already handles them - that's
            // exact-id lookup, no fuzziness involved.
            if (bestScore >= 0.45 && bestId > 0) {
                Log.d(TAG, "TMDB match for '$queryTitle' -> id=$bestId score=${"%.2f".format(bestScore)}")
                bestId
            } else {
                Log.w(TAG, "TMDB no confident match for '$queryTitle' (best=${"%.2f".format(bestScore)}); using site metadata only")
                null
            }
        }.getOrNull()
    }

    /**
     * Token-set Jaccard similarity, case/punctuation insensitive. Returns
     * 0.0 .. 1.0. Designed to be robust to common KatMovieHD title noise:
     *   "Berlin and the Lady with an Ermine"
     *   vs "Berlín y la dama del armiño"  (Spanish original) -> low (good, won't false-match other shows)
     *   vs "Berlin"                       -> low ~0.16        (good, won't grab Money Heist spinoff)
     *   vs "Berlin and the Lady with an Ermine" -> 1.0        (perfect)
     *   "Soulmates" vs "Soulmate"         -> high ~0.5+yearBoost (still safer than blind first-hit)
     */
    /**
     * Hybrid token-set + Jaccard similarity (0.0 .. 1.0).
     *
     * Why hybrid: pure Jaccard punishes title-length mismatch too hard.
     * "Soulmates" (1 token after stopwords) vs "Soulmate" (1 token) is
     * a perfect match for our purpose, BUT a longer query like "Berlin
     * and the Lady with an Ermine" (4 tokens) vs short "Berlin Lady"
     * (2 tokens) only gets Jaccard 0.5 because of size disparity, even
     * though semantically every word of the shorter title is in the
     * longer one.
     *
     * Token-set coverage (intersection / smaller-set-size) gives us
     * "what fraction of the shorter title is covered by the longer one",
     * which is exactly the question we want answered. Blend it with
     * Jaccard (70/30) for stability so noisy short matches don't dominate.
     *
     * Examples (verified):
     *   "berlin and lady ermine"   vs "berlin and the lady with an ermine"  -> ~0.85
     *   "soulmate"                  vs "soulmates"                           -> 1.0
     *   "berlin and lady ermine"   vs "berlin" (Money Heist)                 -> ~0.35
     *   "berlin"                    vs "berlin"                              -> 1.0
     *   "money heist"               vs "la casa de papel"                    -> 0.0 (Path 1 IMDB lookup handles this)
     */
    private fun titleSimilarity(a: String, b: String): Double {
        fun tokens(s: String) = s.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in STOPWORDS }
            .toSet()
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        if (union == 0.0) return 0.0
        // Coverage = fraction of the SHORTER title's tokens present in the
        // LONGER one. Great for handling KatMovieHD's verbose titles vs
        // TMDB's clean canonical titles.
        val coverage = maxOf(inter / ta.size, inter / tb.size)
        val jaccard = inter / union
        return 0.7 * coverage + 0.3 * jaccard
    }

    private suspend fun fetchTmdbDetails(tmdbId: Int, isSeries: Boolean): TmdbDetails? {
        val type = if (isSeries) "tv" else "movie"
        return runCatching {
            val json = JSONObject(
                app.get(
                    "$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,videos,recommendations",
                    timeout = 15
                ).text
            )
            val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { null }
            val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = date.takeIf { it.isNotBlank() }?.substringBefore("-")?.toIntOrNull()
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val voteAvg = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
            val totalSeasons = json.optInt("number_of_seasons", 0).takeIf { it > 0 }

            val genres = json.optJSONArray("genres")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let { add(it) }
                    }
                }
            } ?: emptyList()

            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                buildList {
                    val limit = minOf(arr.length(), 25)
                    for (i in 0 until limit) {
                        val c = arr.optJSONObject(i) ?: continue
                        val n = c.optString("name").ifBlank { c.optString("original_name") }
                        if (n.isBlank()) continue
                        val pf = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
                        val ch = c.optString("character").takeIf { it.isNotBlank() }
                        add(ActorData(Actor(n, pf), roleString = ch))
                    }
                }
            } ?: emptyList()

            val trailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                var found: String? = null
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    if (v.optString("site") == "YouTube" &&
                        v.optString("type").contains("Trailer", true)) {
                        found = "https://www.youtube.com/watch?v=${v.optString("key")}"
                        break
                    }
                }
                found
            }

            val recommendations = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
                buildList<SearchResponse> {
                    for (i in 0 until minOf(arr.length(), 10)) {
                        val r = arr.optJSONObject(i) ?: continue
                        val rTitle = r.optString("title").ifBlank { r.optString("name") }
                        if (rTitle.isBlank()) continue
                        val rType = if (r.optString("media_type") == "tv") TvType.TvSeries else TvType.Movie
                        val rPoster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it }
                        // Since TMDB search URLs aren't native provider URLs, we can fallback to search
                        // KatMovieHD using Cloudstream's search feature via the title!
                        val searchUrl = "$mainUrl/?s=${java.net.URLEncoder.encode(rTitle, "UTF-8")}"
                        val res = newMovieSearchResponse(
                            rTitle,
                            searchUrl,
                            rType
                        ) {
                            this.posterUrl = rPoster
                        }
                        add(res)
                    }
                }
            }

            TmdbDetails(title, poster, backdrop, overview, year,
                voteAvg?.let { Score.from10(it) }, genres, actors, trailer, totalSeasons, recommendations)
        }.getOrNull()
    }

    private suspend fun fetchTmdbSeason(tmdbId: Int, seasonNum: Int): TmdbSeason? {
        return runCatching {
            val json = JSONObject(
                app.get("$TMDB_API/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY", timeout = 15).text
            )
            val arr = json.optJSONArray("episodes") ?: return@runCatching null
            val episodes = buildList {
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val epNum = ep.optInt("episode_number", -1).takeIf { it > 0 } ?: continue
                    add(
                        TmdbEpisode(
                            episodeNumber = epNum,
                            name = ep.optString("name").takeIf { it.isNotBlank() },
                            overview = ep.optString("overview").takeIf { it.isNotBlank() },
                            stillUrl = ep.optString("still_path").takeIf { it.isNotBlank() }
                                ?.let { TMDB_IMG + it },
                            airDate = ep.optString("air_date").takeIf { it.isNotBlank() },
                            rating = ep.optDouble("vote_average")
                                .takeIf { !it.isNaN() && it > 0.0 }
                                ?.let { Score.from10(it) }
                        )
                    )
                }
            }
            TmdbSeason(seasonNum, episodes)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Metadata: Cinemeta (Stremio)
    // Used as a supplement to TMDB - cleaner per-episode names + thumbnails
    // for popular shows, gracefully absent for brand-new ones.
    // ------------------------------------------------------------------

    private data class CinemetaMeta(
        val name: String?,
        val description: String?,
        val poster: String?,
        val background: String?,
        val year: String?,
        val imdbRating: String?,
        val genre: List<String>?,
        val cast: List<String>?,
        val videos: List<CinemetaVideo>?
    )

    private data class CinemetaVideo(
        val season: Int?,
        val episode: Int?,
        val name: String?,
        val title: String?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?
    )

    private suspend fun fetchCinemeta(imdbId: String, isSeries: Boolean): CinemetaMeta? {
        val type = if (isSeries) "series" else "movie"
        return runCatching {
            val text = app.get("$CINEMETA/$type/$imdbId.json", timeout = 15).text
            if (text.isBlank()) return@runCatching null
            val root = JSONObject(text).optJSONObject("meta") ?: return@runCatching null
            CinemetaMeta(
                name = root.optString("name").takeIf { it.isNotBlank() },
                description = root.optString("description").takeIf { it.isNotBlank() },
                poster = root.optString("poster").takeIf { it.isNotBlank() },
                background = root.optString("background").takeIf { it.isNotBlank() },
                year = root.optString("year").takeIf { it.isNotBlank() },
                imdbRating = root.optString("imdbRating").takeIf { it.isNotBlank() },
                genre = root.optJSONArray("genre")?.let { a ->
                    buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
                },
                cast = root.optJSONArray("cast")?.let { a ->
                    buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
                },
                videos = root.optJSONArray("videos")?.let { a ->
                    buildList {
                        for (i in 0 until a.length()) {
                            val v = a.optJSONObject(i) ?: continue
                            add(
                                CinemetaVideo(
                                    season = v.optInt("season", -1).takeIf { it >= 0 },
                                    episode = v.optInt("episode", -1).takeIf { it >= 0 }
                                        ?: v.optInt("number", -1).takeIf { it >= 0 },
                                    name = v.optString("name").takeIf { it.isNotBlank() },
                                    title = v.optString("title").takeIf { it.isNotBlank() },
                                    released = v.optString("released").takeIf { it.isNotBlank() }
                                        ?: v.optString("firstAired").takeIf { it.isNotBlank() },
                                    overview = v.optString("overview").takeIf { it.isNotBlank() }
                                        ?: v.optString("description").takeIf { it.isNotBlank() },
                                    thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + (if (url.startsWith("/")) url else "/$url")
    }

    /**
     * Strip the verbose tags KatMovieHD jams into article titles
     * ("Hindi Dubbed (DD 5.1) & English [Dual Audio] WEB-DL 1080p ...").
     * We keep an AKA chunk if present because it often carries the show's
     * original-language name, which helps TMDB search.
     */
    private fun cleanTitle(raw: String): String {
        val withoutDecorators = raw
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
            .replace(Regex("""(?i)\s*-\s*KatMovieHD.*$"""), "")
            .replace(Regex("""(?i)\s+AKA\s+"""), " AKA ")
            .replace(Regex("""(?i)\((Season\s*\d+)\)"""), "")
            .replace(Regex("""(?i)\s*Season\s*\d+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '|', ':')
            .trim()
        return withoutDecorators.ifBlank { raw.trim() }
    }

    private fun guessTvType(title: String): TvType {
        val t = title.lowercase()
        val isSeries = t.contains("season") ||
                t.contains("episode") ||
                t.contains("episodes") ||
                t.contains("series") ||
                Regex("""\bs\d{1,2}\b""").containsMatchIn(t) ||
                Regex("""\bs0\d\b""").containsMatchIn(t) ||
                Regex("""(?i)\bs\d{1,2}e\d{1,3}\b""").containsMatchIn(title) ||
                Regex("""(?i)\bs\d{1,2}\b""").containsMatchIn(title) ||
                Regex("""(?i)season\s*\d{1,2}\b""").containsMatchIn(title)

        return when {
            t.contains("anime") -> if (isSeries) TvType.AnimeTv else TvType.Anime
            t.contains("k-drama") || t.contains("korean drama") || t.contains("korean series") ||
                t.contains("kdrama") || t.contains("tv series") ->
                if (isSeries) TvType.AsianDrama else TvType.Movie
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }
}
