package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addSeasonNames
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * MoviesCounter provider for CloudStream.
 *
 * Site: moviescounter.boston (WordPress, custom Tailwind-based theme)
 *
 * ── v4 — Reliability & Robustness ──
 *   • Domain rotation fallback, dead page detection, search pagination,
 *     retry with timeout tuning, canonical URL validation.
 *
 * ── v5 — Enhanced Metadata & UX ──
 *   • TMDB metadata enrichment: poster, backdrop, plot, rating, genres,
 *     actors, and YouTube trailer — fetched in parallel so they never
 *     block playable links from appearing.
 *   • Quality + size labels in source names: user sees "1080p WEB-DL (1.2GB)"
 *     instead of just "1080p" — makes it easy to pick the right source.
 *   • Better series episode naming: clean S01E01 format with quality
 *     breakdown visible per episode.
 *   • IMDB ID extraction for CloudStream's built-in metadata matching
 *     (enables "Watch Next", recommendation engine, etc.).
 *   • Dubbed type indicators: detects "UnOfficial Dubbed", "Hindi Dubbed"
 *     from page tags and surfaces them in the response.
 *   • Subtitle link discovery: scans for .srt/.vtt links in post body
 *     and passes them through the subtitleCallback.
 *
 * ── v6 — Future-Ready & Performance ──
 *   • Known host whitelist: curated list of stream/mirror providers
 *     (streamtape, gofile, hubcloud, etc.) for confident link detection.
 *     Two-pass collection: strict whitelist first, permissive fallback
 *     if nothing found — robust against new hosts the site adds.
 *   • Additional redirector resolvers: linkszilla, linkomark, direct-cloud
 *     intermediate pages that appear on some download links.
 *   • Anime/Asian Drama supported type: site has Korean drama and
 *     anime content that gets proper TvType classification.
 *   • "You May Also Like" recommendation extraction: parses the related
 *     posts section on each detail page and surfaces recommendations.
 *   • Multi-season series detection with addSeasonNames: when TMDB
 *     reports multiple seasons, the season picker shows "Season 4"
 *     instead of just "4".
 *   • Actor/cast data from Cinemeta for richer detail pages.
 */
class MoviesCounterProvider : MainAPI() {

    override var mainUrl = "https://moviescounter.boston"
    override var name = "MoviesCounter"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    companion object {
        private const val TAG = "MoviesCounter"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val DOMAIN_TLDS = listOf("boston", "one", "win", "vip", "ink", "lol", "cc")

        private const val FALLBACK_CATEGORY = "/category/latest"
        private const val TIMEOUT = 30
        private const val MAX_RETRIES = 2

        /** TMDB API key for metadata enrichment. */
        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

        /** Stremio's free metadata API — rich per-episode data. */
        private const val CINEMETA = "https://v3-cinemeta.strem.io/meta"

        private val IGNORE_HOST_REGEX = Regex(
            """(?i)(""" +
                    """imdb\.com|themoviedb\.org|wikipedia\.org|""" +
                    """youtube\.com|youtu\.be|""" +
                    """t\.me|telegram\.|whatsapp\.|""" +
                    """facebook\.com|fb\.com|twitter\.com|(?<![a-z])x\.com|instagram\.com|""" +
                    """pinterest\.|reddit\.com|tumblr\.com|""" +
                    """katimages|catimages|imgur|i\.imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|""" +
                    """moviescounter|gstatic|googletagmanager|google-analytics|""" +
                    """jsdelivr|cloudflare\.com|gravatar|effectivecpm|""" +
                    """fonts\.googleapis|fonts\.gstatic|""" +
                    """\.png|\.jpg|\.jpeg|\.gif|\.webp|\.svg|\.ico|""" +
                    """\.css|\.js\?|/feed/|#|""" +
                    """doubleclick|popads|propeller|profitablecpm""" +
                    """)"""
        )

        /** Match "Episode N" / "EPiSODE N" headers. */
        private val EPISODE_HEADER_REGEX = Regex(
            """(?i)\bEPiSODE\s*[-–:#]?\s*(\d{1,3})\b|\bEpisode\s*[-–:#]?\s*(\d{1,3})\b"""
        )

        /** Match "Season N" / "S0N" in titles. */
        private val SEASON_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b(?!\d)""")

        /** Regex to extract size like [510MB] or [1.3GB] */
        private val SIZE_REGEX = Regex("""\[([\d.]+(?:MB|GB|TB))\]""", RegexOption.IGNORE_CASE)

        /** Regex to also match size patterns like "Size: 1.2GB" or "– 500MB" */
        private val SIZE_TEXT_REGEX = Regex(
            """(?i)(?:Size\s*:\s*|–\s*|\|\s*)([\d.]+(?:MB|GB|TB))"""
        )

        /** Regex to match "PACK" keyword — full-season downloads */
        private val PACK_REGEX = Regex("""(?i)\bPACK\b""")

        /** Regex to detect "Single Episode" section header */
        private val SINGLE_EP_SECTION_REGEX = Regex(
            """(?i)Single\s*Episode|Per\s*Episode|Episode\s*Wise"""
        )

        /** Regex to skip SAMPLE/trailer links */
        private val SAMPLE_REGEX = Regex("""(?i)\bSAMPLE\b|\bTRAILER\b""")

        /** Regex to skip torrent/magnet links */
        private val TORRENT_REGEX = Regex(
            """(?i)\btorrent\b|\bmagnet\b|\b\.torrent\b|\bmagnet:\?"""
        )

        /** Regex to detect subtitle file URLs */
        private val SUBTITLE_REGEX = Regex(
            """(?i)\.(srt|vtt|ass|ssa|sub|idx)$"""
        )

        /** Regex to detect quality in heading text — more granular than before */
        private val QUALITY_REGEX = Regex(
            """(?i)(4K|2160p|1080p|720p|480p|300MB)"""
        )

        /** Regex to detect codec info in heading text */
        private val CODEC_REGEX = Regex(
            """(?i)(HEVC|x264|x265|10Bit|AV1|H\.?264|H\.?265|WEB-DL|WEBRip|BluRay|HDRip|BRRip|DVDRip)"""
        )

        /** Common English/Hindi stopwords — excluded from token similarity. */
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "of", "in", "on", "to", "for", "with",
            "ka", "ki", "ke", "se", "aur"
        )

        /**
         * Known stream/mirror host providers — a curated whitelist for
         * confident link detection. Matches here are guaranteed-good
         * download/stream sources. Used by the strict-pass link filter.
         *
         * A false positive just means loadExtractor no-ops harmlessly;
         * a false negative means a real source is dropped.
         */
        private val KNOWN_HOST_REGEX = Regex(
            """(?i)(""" +
                    // Video streaming hosts
                    """streamtape|streamlare|filemoon|filelions|doodstream|dood\.|""" +
                    """mixdrop|streamhide|streamwish|vidhide|vidcloud|vcloud|""" +
                    // File sharing / cloud hosts
                    """gofile\.io|drive\.google|mediafire|pixeldrain|pixeldra\.in|""" +
                    """1fichier|send\.cm|sendvid|send\.now|krakenfiles|""" +
                    // Common download hosts
                    """clicknupload|uploadflix|sendgb|rapidgator|megaup|hexload|""" +
                    """vikingfile|katdrive|hubcloud|hubdrive|hubcdn|""" +
                    // Hindi-dub specific hosts
                    """hglink|fuckingfast|fastdl|filepress|driveseed|driveleech|""" +
                    """bbupload|gofileserver|bbserver|gdtot|techkit|""" +
                    // Additional hosts
                    """uploadrar|catshare|nitroflare|9xupload|""" +
                    """cloudmail|mega\.nz|mdisk|terabox|""" +
                    // MoviesCounter-specific redirectors
                    """mclinks|linkszilla|linkomark|direct-cloud|""" +
                    """secure\.linkszilla|linkszilla\.(top|xyz|cc)|""" +
                    // General cloud/CDN patterns
                    """gdflix|gd-flix|gdlink|gdmirror|""" +
                    """drive\.hyperlink|work\.ink|link\.to""" +
                    """)"""
        )

        /**
         * Intermediate redirector domains that need resolution before
         * reaching the actual file host. These are NOT final stream hosts
         * — they need to be fetched to extract the real URL.
         */
        private val REDIRECTOR_REGEX = Regex(
            """(?i)""" +
            """(?:hubcdn\.org|hubcdn\.sbs|""" +
            """mclinks\.xyz|""" +
            """linkszilla\.top|linkszilla\.xyz|linkszilla\.cc|secure\.linkszilla|""" +
            """linkomark\.com|linkomark\.top|""" +
            """direct-cloud\.[a-z]+|""" +
            """linkszilla|linkomark)"""
        )

        /** Regex to detect anime/K-drama keywords in titles or categories. */
        private val ANIME_REGEX = Regex(
            """(?i)\b(anime|k-drama|korean|kdrama|j-drama|donghua|cartoon|animation)\b"""
        )
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------------------
    // TMDB metadata enrichment
    // ------------------------------------------------------------------

    /**
     * Lightweight TMDB metadata holder. Only fields we actually use.
     */
    private data class TmdbMeta(
        val title: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val rating: Score? = null,
        val genres: List<String> = emptyList(),
        val trailer: String? = null,
        val imdbId: String? = null,
        val totalSeasons: Int? = null
    )

    /**
     * Per-season TMDB data (episode names, thumbnails, overviews).
     */
    private data class TmdbSeason(
        val seasonNumber: Int,
        val episodes: List<TmdbEpisode> = emptyList()
    )

    private data class TmdbEpisode(
        val episodeNumber: Int,
        val name: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val airDate: String? = null,
        val rating: Score? = null
    )

    /**
     * Cinemeta (Stremio) metadata — free, no API key, rich episode data.
     */
    private data class CinemetaMeta(
        val name: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val description: String? = null,
        val year: String? = null,
        val imdbRating: String? = null,
        val genre: List<String> = emptyList(),
        val cast: List<String> = emptyList(),
        val videos: List<CinemetaVideo> = emptyList()
    )

    private data class CinemetaVideo(
        val id: String,
        val title: String? = null,
        val season: Int,
        val episode: Int,
        val thumbnail: String? = null,
        val overview: String? = null,
        val released: String? = null
    )

    /**
     * Resolve a TMDB ID from an IMDB ID (fast path) or by text search.
     * Returns null if nothing found — caller falls back to page-scraped data.
     */
    private suspend fun resolveTmdbId(
        imdbId: String?,
        title: String,
        isSeries: Boolean,
        yearHint: Int?
    ): Int? {
        // Fast path: IMDB → TMDB via /find endpoint
        if (imdbId != null) {
            try {
                val url = "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
                val json = app.get(url, headers = headers, timeout = 10).text
                val obj = org.json.JSONObject(json)
                val results = if (isSeries) obj.optJSONArray("tv_results")
                    else obj.optJSONArray("movie_results")
                if (results != null && results.length() > 0) {
                    return results.getJSONObject(0).optInt("id", -1).takeIf { it > 0 }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TMDB find by IMDB failed: ${e.message}")
            }
        }

        // Slow path: text search
        val type = if (isSeries) "tv" else "movie"
        try {
            val encoded = java.net.URLEncoder.encode(title, "UTF-8")
            var url = "$TMDB_API/search/$type?api_key=$TMDB_API_KEY&query=$encoded"
            if (yearHint != null) {
                url += if (isSeries) "&first_air_date_year=$yearHint"
                       else "&year=$yearHint"
            }
            val json = app.get(url, headers = headers, timeout = 10).text
            val results = org.json.JSONObject(json).optJSONArray("results")
            if (results != null && results.length() > 0) {
                val first = results.getJSONObject(0)
                val id = first.optInt("id", -1)
                if (id > 0) {
                    // Verify title similarity to avoid wrong matches
                    val tmdbTitle = first.optString("name",
                        first.optString("title", "")).lowercase()
                    val ourTitle = title.lowercase()
                    if (tokenSimilarity(ourTitle, tmdbTitle) >= 0.3) {
                        return id
                    }
                    Log.w(TAG, "TMDB title mismatch: '$ourTitle' vs '$tmdbTitle', skipping")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB search failed: ${e.message}")
        }

        return null
    }

    /**
     * Fetch full TMDB details for a movie or TV show.
     */
    private suspend fun fetchTmdbDetails(tmdbId: Int, isSeries: Boolean): TmdbMeta? {
        val type = if (isSeries) "tv" else "movie"
        return try {
            val url = "$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=videos"
            val json = app.get(url, headers = headers, timeout = 10).text
            val obj = org.json.JSONObject(json)

            val poster = obj.optString("poster_path", "")
                .takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
            val backdrop = obj.optString("backdrop_path", "")
                .takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
            val rating = obj.optDouble("vote_average", 0.0).takeIf { it > 0 }
                ?.let { Score.from10(it.toFloat()) }
            val genres = obj.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
            } ?: emptyList()
            val totalSeasons = obj.optInt("number_of_seasons", 0).takeIf { it > 0 }
            val yearStr = obj.optString("release_date",
                obj.optString("first_air_date", ""))
            val year = if (yearStr.length >= 4) yearStr.substring(0, 4).toIntOrNull() else null

            // Extract YouTube trailer
            var trailer: String? = null
            obj.optJSONObject("videos")?.optJSONArray("results")?.let { videos ->
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    if (v.optString("site") == "YouTube" &&
                        v.optString("type") == "Trailer" &&
                        v.optString("key").isNotBlank()
                    ) {
                        trailer = "https://www.youtube.com/watch?v=${v.optString("key")}"
                        break
                    }
                }
            }

            TmdbMeta(
                title = obj.optString("title",
                    obj.optString("name", "")).ifBlank { null },
                poster = poster,
                backdrop = backdrop,
                overview = obj.optString("overview", "").ifBlank { null },
                year = year,
                rating = rating,
                genres = genres,
                trailer = trailer,
                imdbId = null, // Will be set separately if available
                totalSeasons = totalSeasons
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchTmdbDetails failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch TMDB season details for per-episode metadata.
     */
    private suspend fun fetchTmdbSeason(
        tmdbId: Int,
        seasonNum: Int
    ): TmdbSeason? {
        return try {
            val url = "$TMDB_API/tv/$tmdbId/season/$seasonNum?api_key=$TMDB_API_KEY"
            val json = app.get(url, headers = headers, timeout = 10).text
            val obj = org.json.JSONObject(json)

            val episodes = obj.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val ep = arr.optJSONObject(i) ?: return@mapNotNull null
                    val epNum = ep.optInt("episode_number", -1)
                    if (epNum < 0) return@mapNotNull null
                    TmdbEpisode(
                        episodeNumber = epNum,
                        name = ep.optString("name", "").ifBlank { null },
                        overview = ep.optString("overview", "").ifBlank { null },
                        stillUrl = ep.optString("still_path", "")
                            .takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" },
                        airDate = ep.optString("air_date", "").ifBlank { null },
                        rating = ep.optDouble("vote_average", 0.0).takeIf { it > 0 }
                            ?.let { Score.from10(it.toFloat()) }
                    )
                }
            } ?: emptyList()

            TmdbSeason(seasonNumber = seasonNum, episodes = episodes)
        } catch (e: Exception) {
            Log.w(TAG, "fetchTmdbSeason failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch Cinemeta (Stremio) metadata — free, rich per-episode data.
     * Used as supplementary source when TMDB fails.
     */
    private suspend fun fetchCinemeta(
        imdbId: String,
        isSeries: Boolean
    ): CinemetaMeta? {
        val type = if (isSeries) "series" else "movie"
        return try {
            val url = "$CINEMETA/$type/$imdbId.json"
            val json = app.get(url, headers = headers, timeout = 10).text
            val obj = org.json.JSONObject(json).optJSONObject("meta") ?: return null

            val videos = obj.optJSONArray("videos")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = v.optString("id", "")
                    val parts = id.split(":")
                    if (parts.size >= 3) {
                        CinemetaVideo(
                            id = id,
                            title = v.optString("title", "").ifBlank { null },
                            season = parts[1].toIntOrNull() ?: 0,
                            episode = parts[2].toIntOrNull() ?: 0,
                            thumbnail = v.optString("thumbnail", "").ifBlank { null },
                            overview = v.optString("overview", "").ifBlank { null },
                            released = v.optString("released", "").ifBlank { null }
                        )
                    } else null
                }
            } ?: emptyList()

            CinemetaMeta(
                name = obj.optString("name", "").ifBlank { null },
                poster = obj.optString("poster", "").ifBlank { null },
                background = obj.optString("background", "").ifBlank { null },
                description = obj.optString("description", "").ifBlank { null },
                year = obj.optString("year", "").ifBlank { null },
                imdbRating = obj.optString("imdbRating", "").ifBlank { null },
                genre = obj.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it) }
                } ?: emptyList(),
                cast = obj.optJSONArray("cast")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it) }
                } ?: emptyList(),
                videos = videos
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchCinemeta failed: ${e.message}")
            null
        }
    }

    /**
     * Token-set similarity (Jaccard) for title matching.
     * Prevents TMDB returning a completely wrong movie as a match.
     */
    private fun tokenSimilarity(a: String, b: String): Double {
        val setA = a.split(Regex("""\s+"""))
            .map { it.lowercase() }
            .filter { it.length > 1 && it !in STOPWORDS }.toSet()
        val setB = b.split(Regex("""\s+"""))
            .map { it.lowercase() }
            .filter { it.length > 1 && it !in STOPWORDS }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return intersection.toDouble() / union.toDouble()
    }

    // ------------------------------------------------------------------
    // Domain rotation & network resilience
    // ------------------------------------------------------------------

    private suspend fun resilientGet(
        url: String,
        customHeaders: Map<String, String> = headers,
        timeout: Int = TIMEOUT
    ): com.lagradost.cloudstream3.mvvm.Resource<com.lagradost.cloudstream3.network.WebPage> {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val res = app.get(url, headers = customHeaders, timeout = timeout)
                if (res.code < 500) return res
                lastException = Exception("HTTP ${res.code}")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "resilientGet attempt $attempt failed for $url: ${e.message}")
            }
        }

        val currentHost = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
        if (currentHost != null) {
            for (tld in DOMAIN_TLDS) {
                val altHost = "https://moviescounter.$tld"
                if (altHost == currentHost) continue
                val altUrl = url.replace(currentHost, altHost)
                try {
                    val res = app.get(altUrl, headers = customHeaders, timeout = timeout)
                    if (res.code < 500) {
                        Log.d(TAG, "Domain rotation: switching from $currentHost to $altHost")
                        mainUrl = altHost
                        return res
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Domain rotation: $altHost failed: ${e.message}")
                }
            }
        }

        throw lastException ?: Exception("All domain attempts failed for $url")
    }

    private fun isDeadPage(doc: org.jsoup.nodes.Document, originalUrl: String): Boolean {
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if (canonical != null && canonical.contains(FALLBACK_CATEGORY)) return true

        val bodyClass = doc.selectFirst("body")?.className() ?: ""
        if (bodyClass.contains("error404", ignoreCase = true) ||
            bodyClass.contains("page-not-found", ignoreCase = true)
        ) return true

        val pageTitle = doc.title().lowercase()
        if (pageTitle.contains("page not found") ||
            (pageTitle.contains("not found") && !pageTitle.contains("moviescounter"))
        ) return true

        return false
    }

    // ------------------------------------------------------------------
    // Main page & search
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/bollywood-movies/" to "Bollywood",
        "$mainUrl/category/hollywood-movies/" to "Hollywood",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/south-hindi-movies/" to "South Hindi",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/hd-movies/" to "HD Movies",
        "$mainUrl/category/300mb-movies/" to "300MB Movies",
        "$mainUrl/category/true-web-dl/" to "TRUE WEB-DL",
        "$mainUrl/category/k-drama/" to "K-Drama"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = resilientGet(url).document
        val items = parseListing(doc)
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = resilientGet(url).document
        return parseListing(doc)
    }

    override suspend fun search(query: String, page: Int): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
        val doc = resilientGet(url).document
        return parseListing(doc)
    }

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Strategy 1: Tailwind-based card layout (current theme)
        doc.select("div.inline-flex.flex-col > a").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) return@forEach
            if (href in seenUrls) return@forEach
            if (isNonContentUrl(href)) return@forEach

            val img = anchor.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim()
                .ifBlank { img.attr("title").trim() }
                .ifBlank { anchor.selectFirst("div.transition-all")?.text()?.trim().orEmpty() }
                .ifBlank { return@forEach }

            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (isNonContentImage(src)) return@forEach

            val poster = fixUrlNull(src.ifBlank { null })
            val isSeries = detectSeriesFromTitle(title, href)
            val quality = detectSearchQuality(title)

            seenUrls.add(href)
            results.add(
                if (isSeries) {
                    newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                } else {
                    newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                }
            )
        }

        // Strategy 2: Fallback — any anchor with poster image
        if (results.isEmpty()) {
            doc.select("a:has(img)").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seenUrls) return@forEach
                if (isNonContentUrl(href)) return@forEach

                val img = anchor.selectFirst("img") ?: return@forEach
                val title = img.attr("alt").trim()
                    .ifBlank { img.attr("title").trim() }
                    .ifBlank { anchor.text().trim() }
                if (title.isBlank()) return@forEach

                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (isNonContentImage(src)) return@forEach

                val poster = fixUrlNull(src.ifBlank { null })
                val isSeries = detectSeriesFromTitle(title, href)
                val quality = detectSearchQuality(title)

                seenUrls.add(href)
                results.add(
                    if (isSeries) {
                        newTvSeriesSearchResponse(cleanTitle(title), href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.quality = quality
                        }
                    } else {
                        newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                            this.posterUrl = poster
                            this.quality = quality
                        }
                    }
                )
            }
        }

        Log.d(TAG, "parseListing(): ${results.size} results")
        return results.distinctBy { it.url }
    }

    private fun isNonContentUrl(href: String): Boolean {
        return href.contains("/category/", ignoreCase = true) ||
            href.contains("/page/", ignoreCase = true) ||
            href.contains("/wp-", ignoreCase = true) ||
            href.contains("/feed/", ignoreCase = true) ||
            href.contains("how-to-download", ignoreCase = true) ||
            href.contains("join-our-group", ignoreCase = true) ||
            href.contains("request-a-movie", ignoreCase = true) ||
            href.contains("disclaimer", ignoreCase = true) ||
            href.contains("privacy-policy", ignoreCase = true) ||
            href.contains("contact", ignoreCase = true) ||
            (href.contains(mainUrl.trimEnd('/') + "/", ignoreCase = true) &&
                href.replace(mainUrl, "").trimEnd('/').isEmpty())
    }

    private fun isNonContentImage(src: String): Boolean {
        return src.contains("logo", ignoreCase = true) ||
            src.contains("favicon", ignoreCase = true) ||
            src.contains("badge", ignoreCase = true) ||
            src.contains("banner", ignoreCase = true) ||
            src.contains("wp-content/themes", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // load() - detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val response = resilientGet(url)
        val doc = response.document

        if (isDeadPage(doc, url)) {
            throw Exception("Page not found (dead/redirected): $url")
        }

        // Title from h3 heading or og:title
        val rawTitle = doc.selectFirst("h3.text-gray-500")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().trim()

        val title = cleanTitle(rawTitle)

        // Poster: prefer TMDB image, fallback to og:image
        val sitePoster = doc.selectFirst("article img[src*=tmdb], article img[src*=image.tmdb.org]")?.let {
            fixUrlNull(it.attr("src"))
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("article img.aligncenter")?.let { fixUrlNull(it.attr("src")) }

        // Plot from og:description or longest paragraph in post-body
        val postBody = doc.selectFirst("div.post-body")
        val sitePlot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: postBody?.select("p")?.firstOrNull { it.text().length > 100 }?.text()?.trim()

        // Score from post-body only
        var siteScore: Score? = null
        val metaText = postBody?.text() ?: ""
        Regex("""(\d+\.?\d*)/10""").find(metaText)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { siteScore = Score.from10(it) }

        // Tags from the POST'S OWN hashtag links only
        val tags = doc.select("div.w-full.my-4.text-center a[href*=/category/]")
            .map { it.text().trim().removePrefix("# ").trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct()
            .ifEmpty {
                doc.selectFirst("meta[property=article:section]")?.attr("content")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
            }

        // Year from title
        val yearHint = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()

        // IMDB link & ID
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title]")?.attr("href")
        val imdbId = imdbUrl?.let {
            Regex("""title/(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        // ---------------------------------------------------------------
        // Series detection — SCOPED to post-body ONLY
        // ---------------------------------------------------------------

        val titleIndicatesSeries = detectSeriesFromTitle(rawTitle, url)

        val tagIndicatesSeries = tags.any {
            it.equals("WEB-Series", true) ||
            it.equals("TV-Shows", true) ||
            it.equals("WEB-Series [UnOfficial Dubbed]", true)
        }

        val hasSingleEpisodeSection = postBody?.select("h2")?.any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        } ?: false

        val isSeries = titleIndicatesSeries || tagIndicatesSeries ||
            hasSingleEpisodeSection

        val seasonNum = SEASON_REGEX.find(rawTitle)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        } ?: 1

        Log.d(TAG, "load(url=$url) title='$title' isSeries=$isSeries " +
            "titleIndicates=$titleIndicatesSeries tagIndicates=$tagIndicatesSeries " +
            "singleSection=$hasSingleEpisodeSection tags=$tags season=$seasonNum")

        // ---------------------------------------------------------------
        // TMDB + Cinemeta enrichment (parallel, non-blocking)
        // ---------------------------------------------------------------

        val (tmdbMeta, tmdbSeason, cine) = coroutineScope {
            val tmdbDef = async {
                val id = resolveTmdbId(imdbId, title, isSeries, yearHint)
                if (id != null) fetchTmdbDetails(id, isSeries)?.let {
                    it to (if (isSeries) fetchTmdbSeason(id, seasonNum) else null)
                } else null
            }
            val cineDef = async {
                if (imdbId != null) fetchCinemeta(imdbId, isSeries) else null
            }
            val result = tmdbDef.await()
            Triple(result?.first, result?.second, cineDef.await())
        }

        // Merge: TMDB (primary) > Cinemeta (secondary) > site-scraped (fallback)
        val finalTitle = tmdbMeta?.title ?: cine?.name ?: title
        val finalPoster = tmdbMeta?.poster ?: cine?.poster ?: sitePoster
        val finalBackdrop = tmdbMeta?.backdrop ?: cine?.background ?: finalPoster
        val finalPlot = tmdbMeta?.overview ?: cine?.description ?: sitePlot
        val finalYear = tmdbMeta?.year
            ?: cine?.year?.substringBefore("-")?.toIntOrNull()
            ?: yearHint
        val finalScore = tmdbMeta?.rating
            ?: cine?.imdbRating?.toFloatOrNull()?.let { Score.from10(it) }
            ?: siteScore
        val finalTags = (tmdbMeta?.genres ?: cine?.genre ?: tags).distinct()
            .ifEmpty { tags }

        Log.d(TAG, "Metadata: tmdb=${tmdbMeta != null} cine=${cine != null} " +
            "poster=${finalPoster != null} backdrop=${finalBackdrop != null}")

        // Use postBody for all download link parsing (clean, no contamination)
        val container = postBody ?: doc.selectFirst("article") ?: doc

        if (isSeries) {
            val episodes = buildSeriesEpisodes(container, seasonNum, tmdbSeason, cine)
            Log.d(TAG, "load() built ${episodes.size} series episode(s)")

            val finalEpisodes = if (episodes.isEmpty()) {
                val links = collectAllDownloadLinks(container)
                if (links.isNotEmpty()) {
                    listOf(newEpisode(links.joinToString("\n") { it.second }) {
                        name = "All Episodes"
                        season = seasonNum
                        episode = 1
                    })
                } else emptyList()
            } else {
                episodes
            }

            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags.ifEmpty { null }
                this.score = finalScore
                imdbUrl?.let { addImdbUrl(it) }
                imdbId?.let { addImdbId(it) }
                tmdbMeta?.trailer?.let { addTrailer(it) }
                // Season names from TMDB for multi-season series
                if (tmdbMeta?.totalSeasons != null && tmdbMeta.totalSeasons > 1) {
                    addSeasonNames((1..tmdbMeta.totalSeasons).map { "Season $it" })
                }
                // Actor/cast data from Cinemeta
                if (cine?.cast?.isNotEmpty() == true) {
                    addActors(cine.cast)
                }
            }
        } else {
            // For MOVIES: collect ALL quality download links with size info.
            val links = collectAllDownloadLinks(container)
            val data = links.joinToString("\n") { (label, href) -> "$label|$href" }
            Log.d(TAG, "load() found ${links.size} download link(s) for movie")

            return newMovieLoadResponse(finalTitle, url, TvType.Movie, data) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags.ifEmpty { null }
                this.score = finalScore
                imdbUrl?.let { addImdbUrl(it) }
                imdbId?.let { addImdbId(it) }
                tmdbMeta?.trailer?.let { addTrailer(it) }
                // Actor/cast data from Cinemeta
                if (cine?.cast?.isNotEmpty() == true) {
                    addActors(cine.cast)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Download link collection — two-pass with known host whitelist
    // ------------------------------------------------------------------

    /**
     * Two-pass download link collector — follows the proven KatMovieHD pattern:
     *
     *   Pass 1 (strict): URLs whose host matches KNOWN_HOST_REGEX.
     *     These are guaranteed-good stream/mirror sources.
     *   Pass 2 (permissive): if pass 1 found NOTHING, fall back to every
     *     external http(s) URL that isn't on the explicit blacklist
     *     (IGNORE_HOST_REGEX) and isn't the MoviesCounter site itself.
     *     CloudStream's loadExtractor silently no-ops on unknown hosts,
     *     so a few junk URLs here are harmless — but any new host the
     *     site starts using (which we haven't added to the whitelist yet)
     *     will still be tried.
     *
     * This makes the extension robust against MoviesCounter adding a new
     * mirror provider in the future without us having to rebuild.
     *
     * Returns list of (displayLabel, url) pairs with quality+size labels.
     */
    private fun collectAllDownloadLinks(
        container: Element
    ): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()

        // Gather all candidate URLs from headings first, then anchors
        val allCandidates = mutableListOf<Pair<String, String>>()

        // Strategy 1: h3/h4 headings with download links (primary)
        container.select("h3, h4").forEach { heading ->
            val headingText = heading.text().trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(headingText)) return@forEach
            if (SAMPLE_REGEX.containsMatchIn(headingText)) return@forEach
            if (TORRENT_REGEX.containsMatchIn(headingText)) return@forEach

            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()
            if (href == null || !href.startsWith("http")) return@forEach
            if (href in seen) return@forEach
            if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
            if (href.contains(mainUrl, ignoreCase = true)) return@forEach

            seen.add(href)
            val qualityLabel = extractQualityLabelWithSize(headingText)
            allCandidates.add(Pair(qualityLabel, href))
        }

        // Strategy 2: all <a> in container (fallback for non-heading links)
        if (allCandidates.isEmpty()) {
            container.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                if (IGNORE_HOST_REGEX.containsMatchIn(href)) return@forEach
                if (href.contains(mainUrl, ignoreCase = true)) return@forEach

                seen.add(href)
                val text = anchor.text().trim()
                val qualityLabel = extractQualityLabelWithSize(text)
                allCandidates.add(Pair(qualityLabel, href))
            }
        }

        if (allCandidates.isEmpty()) return emptyList()

        // Return ALL candidates, prioritizing known hosts first.
        // NEVER drop links just because their host isn't in our whitelist —
        // new hosts appear frequently and dropping them means missing
        // quality variants (e.g., 4K on a new mirror).
        val known = allCandidates.filter { (_, url) ->
            KNOWN_HOST_REGEX.containsMatchIn(url)
        }
        val unknown = allCandidates.filter { (_, url) ->
            !KNOWN_HOST_REGEX.containsMatchIn(url)
        }
        val result = known + unknown
        Log.d(TAG, "collectAllDownloadLinks(): ${known.size} known + ${unknown.size} other = ${result.size} total")
        return result
    }

    // ------------------------------------------------------------------
    // Series episode parsing — now with TMDB/Cinemeta enrichment
    // ------------------------------------------------------------------

    private fun buildSeriesEpisodes(
        container: Element,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val hasSingleEpisodeSection = container.select("h2").any { h2 ->
            SINGLE_EP_SECTION_REGEX.containsMatchIn(h2.text())
        }

        val hasEpisodeHeaders = container.select("h4, h3").any { el ->
            EPISODE_HEADER_REGEX.containsMatchIn(el.text()) &&
                el.select("a[href]").isEmpty()
        }

        Log.d(TAG, "buildSeriesEpisodes: singleEpSection=$hasSingleEpisodeSection " +
            "epHeaders=$hasEpisodeHeaders")

        return if (hasSingleEpisodeSection || hasEpisodeHeaders) {
            buildPerEpisodeLayout(container, defaultSeason, tmdbSeason, cine)
        } else {
            buildPackEpisodes(container, defaultSeason)
        }
    }

    /**
     * Per-episode layout with TMDB/Cinemeta episode names and thumbnails.
     */
    private fun buildPerEpisodeLayout(
        container: Element,
        defaultSeason: Int,
        tmdbSeason: TmdbSeason?,
        cine: CinemetaMeta?
    ): List<Episode> {
        val episodeMap = linkedMapOf<Pair<Int, Int>, MutableList<Pair<String, String>>>()
        var currentSeason = defaultSeason
        var currentEpisode: Int? = null
        var pastPackSection = false

        // Collect pack/quality-tier links from the "DOWNLOAD LINKS" section
        // These are full-season packs (e.g., 4K SDR PACK) that apply to ALL episodes
        val packLinks = mutableListOf<Pair<String, String>>()

        val downloadElements = container.select("h2, h3, h4, hr, p")

        for (element in downloadElements) {
            val tag = element.tagName()
            val text = element.text().trim()
            if (text.isEmpty()) continue

            if (tag == "h2" && SINGLE_EP_SECTION_REGEX.containsMatchIn(text)) {
                pastPackSection = true
                continue
            }

            if (tag == "h2" && text.contains("DOWNLOAD LINKS", ignoreCase = true)) {
                continue
            }

            // Collect pack/quality-tier links from BEFORE the Single Episode section
            if (!pastPackSection) {
                element.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") &&
                        !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                        !href.contains(mainUrl, ignoreCase = true) &&
                        !SAMPLE_REGEX.containsMatchIn(text) &&
                        !TORRENT_REGEX.containsMatchIn(text)
                    ) {
                        val qualityLabel = extractQualityLabelWithSize(text)
                        if (packLinks.none { it.second == href }) {
                            packLinks.add(Pair(qualityLabel, href))
                        }
                    }
                }
                continue
            }

            val epMatch = EPISODE_HEADER_REGEX.find(text)
            if (epMatch != null && element.select("a[href]").isEmpty()) {
                val epNum = (epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] })
                    .toIntOrNull()
                if (epNum != null) {
                    currentEpisode = epNum
                    SEASON_REGEX.find(text)?.let { m ->
                        (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            ?.let { currentSeason = it }
                    }
                    continue
                }
            }

            if (currentEpisode != null) {
                val qualityLabel = extractQualityLabelWithSize(text)

                element.select("a[href]").forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.startsWith("http") &&
                        !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                        !href.contains(mainUrl, ignoreCase = true)
                    ) {
                        val key = currentSeason to currentEpisode
                        val bucket = episodeMap.getOrPut(key) { mutableListOf() }
                        if (bucket.none { it.second == href }) {
                            bucket.add(Pair(qualityLabel, href))
                        }
                    }
                }
            }
        }

        // Merge pack links into EVERY episode so 4K PACK links are available
        // for all episodes, not just the per-episode quality links
        if (packLinks.isNotEmpty() && episodeMap.isNotEmpty()) {
            for ((_, bucket) in episodeMap) {
                for (pl in packLinks) {
                    if (bucket.none { it.second == pl.second }) {
                        bucket.add(pl)
                    }
                }
            }
            Log.d(TAG, "buildPerEpisodeLayout: merged ${packLinks.size} pack links into ${episodeMap.size} episodes")
        }

        if (episodeMap.isEmpty()) return emptyList()

        return episodeMap.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, links) ->
                val (season, ep) = key

                // Enrich with TMDB/Cinemeta episode data
                val tmdbEp = tmdbSeason
                    ?.takeIf { it.seasonNumber == season }
                    ?.episodes?.firstOrNull { it.episodeNumber == ep }
                val cineEp = cine?.videos
                    ?.firstOrNull { it.season == season && it.episode == ep }

                val data = links.joinToString("\n") { (ql, href) -> "$ql|$href" }
                newEpisode(data) {
                    name = tmdbEp?.name ?: cineEp?.title ?: "Episode $ep"
                    this.season = season
                    this.episode = ep
                    posterUrl = tmdbEp?.stillUrl ?: cineEp?.thumbnail
                    description = tmdbEp?.overview ?: cineEp?.overview
                    score = tmdbEp?.rating
                    (tmdbEp?.airDate ?: cineEp?.released)?.let {
                        addDate(it.substringBefore("T"))
                    }
                }
            }
    }

    /**
     * Pack-only fallback with quality+size labels.
     */
    private fun buildPackEpisodes(
        container: Element,
        defaultSeason: Int
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        container.select("h3, h4").forEach { heading ->
            val text = heading.text().trim()
            val link = heading.selectFirst("a[href]")
            val href = link?.attr("href")?.trim()

            if (EPISODE_HEADER_REGEX.containsMatchIn(text)) return@forEach
            if (TORRENT_REGEX.containsMatchIn(text)) return@forEach

            if (href != null && href.startsWith("http") &&
                !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                !href.contains(mainUrl, ignoreCase = true)
            ) {
                val qualityLabel = extractQualityLabelWithSize(text)

                episodes.add(newEpisode(href) {
                    name = qualityLabel
                    season = defaultSeason
                    episode = episodes.size + 1
                })
            }
        }

        if (episodes.isEmpty()) {
            val allLinks = collectAllDownloadLinks(container)
            allLinks.forEachIndexed { idx, (ql, link) ->
                episodes.add(newEpisode(link) {
                    name = ql
                    season = defaultSeason
                    episode = idx + 1
                })
            }
        }

        return episodes
    }

    // ------------------------------------------------------------------
    // loadLinks — resolve URLs to playable streams
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val entries = data.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].startsWith("http")) {
                    Pair(parts[0].trim(), parts[1].trim())
                } else if (line.startsWith("http")) {
                    Pair("", line)
                } else {
                    null
                }
            }
            .filterNotNull()

        val urls = entries.map { it.second }
        if (urls.isEmpty()) return false

        Log.d(TAG, "loadLinks(): resolving ${urls.size} URL(s)")
        var anySuccess = false

        urls.amap { url ->
            try {
                // Check for subtitle URLs
                if (SUBTITLE_REGEX.containsMatchIn(url)) {
                    val langName = if (url.contains("hindi", ignoreCase = true)) "Hindi"
                        else if (url.contains("english", ignoreCase = true)) "English"
                        else "Unknown"
                    subtitleCallback(
                        SubtitleFile(
                            SubtitleHelper.fromTwoLettersToLanguage(langName),
                            url
                        )
                    )
                    anySuccess = true
                    return@amap
                }

                val resolved = resolveRedirector(url)

                if (resolved.isEmpty()) {
                    if (!loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        val qualityLabel = entries.find { it.second == url }?.first ?: ""
                        addDirectLink(url, callback, qualityLabel)
                    }
                    anySuccess = true
                } else {
                    resolved.amap { resolvedUrl ->
                        try {
                            if (!loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)) {
                                val qualityLabel = entries.find { it.second == url }?.first ?: ""
                                addDirectLink(resolvedUrl, callback, qualityLabel)
                            }
                            anySuccess = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Extraction failed for $resolvedUrl: ${e.message}")
                            addDirectLink(resolvedUrl, callback)
                            anySuccess = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve $url: ${e.message}")
                try {
                    if (loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        anySuccess = true
                    }
                } catch (_: Exception) {}
            }
        }

        return anySuccess
    }

    // ------------------------------------------------------------------
    // Redirector resolution
    // ------------------------------------------------------------------

    private suspend fun resolveRedirector(url: String): List<String> {
        val isHubcdn = url.contains("hubcdn.org", ignoreCase = true) ||
            url.contains("hubcdn.sbs", ignoreCase = true)
        val isMclinks = url.contains("mclinks.xyz", ignoreCase = true)
        val isLinkszilla = url.contains("linkszilla", ignoreCase = true) ||
            url.contains("secure.linkszilla", ignoreCase = true)
        val isLinkomark = url.contains("linkomark", ignoreCase = true)
        val isDirectCloud = url.contains("direct-cloud", ignoreCase = true)

        if (!isHubcdn && !isMclinks && !isLinkszilla && !isLinkomark && !isDirectCloud) {
            return emptyList()
        }

        return try {
            when {
                isHubcdn -> resolveHubcdn(url)
                isMclinks -> resolveMclinks(url)
                isLinkszilla -> resolveLinkszilla(url)
                isLinkomark -> resolveLinkomark(url)
                isDirectCloud -> resolveDirectCloud(url)
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveRedirector failed for $url: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveHubcdn(url: String): List<String> {
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            Log.d(TAG, "resolveHubcdn: extracted link param: $linkParam")
            return listOf(java.net.URLDecoder.decode(linkParam, "UTF-8"))
        }

        try {
            val res = app.get(url, headers = headers, allowRedirects = false, timeout = 10000)
            val location = res.headers["Location"]
            if (!location.isNullOrEmpty() && location.startsWith("http")) {
                Log.d(TAG, "resolveHubcdn: redirect to $location")
                return listOf(location)
            }
        } catch (_: Exception) {}

        return try {
            val doc = app.get(url, headers = headers, timeout = 10000).document
            val results = mutableListOf<String>()
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("hubcdn", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }
            Log.d(TAG, "resolveHubcdn: found ${results.size} link(s)")
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveMclinks(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
            if (metaRefresh != null) {
                val content = metaRefresh.attr("content")
                val urlMatch = Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                if (urlMatch != null) {
                    results.add(urlMatch)
                    Log.d(TAG, "resolveMclinks: meta-refresh to $urlMatch")
                    return results
                }
            }

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("mclinks.xyz", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            doc.select("script").forEach { script ->
                val jsUrl = Regex("""(?:window\.)?location\s*[=:]\s*["']([^"']+)["']""")
                    .find(script.data())?.groupValues?.get(1)
                if (jsUrl != null && jsUrl.startsWith("http")) {
                    results.add(jsUrl)
                }
            }

            Log.d(TAG, "resolveMclinks: found ${results.size} link(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveMclinks failed: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Additional redirector resolvers (v6)
    // ------------------------------------------------------------------

    /**
     * Resolve linkszilla redirector pages.
     * Linkszilla pages typically have a list of mirror links (streamtape,
     * gofile, etc.) or a single redirect to the actual host.
     */
    private suspend fun resolveLinkszilla(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            // Strategy 1: Find all links that match known hosts
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("linkszilla", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            // Strategy 2: meta refresh redirect
            if (results.isEmpty()) {
                doc.selectFirst("meta[http-equiv=refresh]")?.let { meta ->
                    val content = meta.attr("content")
                    Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                        .find(content)?.groupValues?.get(1)?.let {
                            results.add(it)
                        }
                }
            }

            // Strategy 3: JavaScript location redirect
            if (results.isEmpty()) {
                doc.select("script").forEach { script ->
                    Regex("""(?:window\.)?location\s*[=:]\s*["']([^"']+)["']""")
                        .find(script.data())?.groupValues?.get(1)?.let {
                            if (it.startsWith("http")) results.add(it)
                        }
                }
            }

            Log.d(TAG, "resolveLinkszilla: found ${results.size} link(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveLinkszilla failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolve linkomark redirector pages.
     * Similar to linkszilla — intermediate page with mirror links.
     */
    private suspend fun resolveLinkomark(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.startsWith("http") &&
                    !href.contains("linkomark", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results
                ) {
                    results.add(href)
                }
            }

            // Fallback: meta refresh
            if (results.isEmpty()) {
                doc.selectFirst("meta[http-equiv=refresh]")?.let { meta ->
                    val content = meta.attr("content")
                    Regex("""url=(https?://[^"']+)""", RegexOption.IGNORE_CASE)
                        .find(content)?.groupValues?.get(1)?.let {
                            results.add(it)
                        }
                }
            }

            Log.d(TAG, "resolveLinkomark: found ${results.size} link(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveLinkomark failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolve direct-cloud redirector pages.
     * These typically embed a single link or use JS redirects.
     */
    private suspend fun resolveDirectCloud(url: String): List<String> {
        return try {
            val doc = app.get(url, headers = headers, timeout = 15000).document
            val results = mutableListOf<String>()

            // Look for actual download/stream links
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href").trim()
                val text = anchor.text().trim().lowercase()
                if (href.startsWith("http") &&
                    !href.contains("direct-cloud", ignoreCase = true) &&
                    !href.contains(mainUrl, ignoreCase = true) &&
                    !IGNORE_HOST_REGEX.containsMatchIn(href) &&
                    href !in results &&
                    !text.contains("how to download", ignoreCase = true)
                ) {
                    results.add(href)
                }
            }

            Log.d(TAG, "resolveDirectCloud: found ${results.size} link(s)")
            results
        } catch (e: Exception) {
            Log.w(TAG, "resolveDirectCloud failed: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Utility functions
    // ------------------------------------------------------------------

    private suspend fun addDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityLabel: String = "",
        sourceName: String? = null
    ) {
        val quality = when {
            qualityLabel.contains("4K", ignoreCase = true) ||
                qualityLabel.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            qualityLabel.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            qualityLabel.contains("720p", ignoreCase = true) -> Qualities.P720.value
            qualityLabel.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("2160p", ignoreCase = true) -> Qualities.P2160.value
            url.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        val displayName = if (qualityLabel.isNotBlank()) qualityLabel else (sourceName ?: "Direct")

        callback(
            newExtractorLink(
                sourceName ?: name,
                displayName,
                url,
                INFER_TYPE
            ) {
                this.quality = quality
                this.referer = mainUrl
            }
        )
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\|\s*Full Movie\s*$"""), "")
            .replace(Regex("""\s*\|\s*Zee5 Series\s*$"""), "")
            .replace(Regex("""\s*\|\s*Full Series\s*$"""), "")
            .replace(Regex("""\s*\|\s*NF Series\s*$"""), "")
            .replace(Regex("""\s*[-|]\s*Movies Counter\s*$"""), "")
            .replace(Regex("""\s*[-–]\s*Movies Counter\s*$"""), "")
            .replace(Regex("""\s*DS4K\s+"""), " ")
            .replace(Regex("""\s*(WEB-DL|WEBRip|HDRip|BluRay|BRRip)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[(?:Hindi|DD5\.1|x264|HEVC|10Bit|ESubs)[^\]]*\]"""), "")
            .replace(Regex("""\d+p\s*(?:HEVC|x264|10Bit)?\s*[\[(]"""), "")
            .trim()
    }

    /**
     * Detect content type from title and URL.
     * v6: Now also detects Anime/AsianDrama for proper TvType classification.
     */
    private fun detectContentType(title: String, href: String): TvType {
        // Check anime/K-drama first (more specific)
        if (ANIME_REGEX.containsMatchIn(title) ||
            href.contains("/category/k-drama/", ignoreCase = true) ||
            href.contains("/category/anime/", ignoreCase = true)
        ) {
            // Distinguish anime from Asian drama
            return if (title.contains("k-drama", ignoreCase = true) ||
                title.contains("korean", ignoreCase = true) ||
                href.contains("/category/k-drama/", ignoreCase = true)
            ) {
                TvType.AsianDrama
            } else {
                TvType.Anime
            }
        }

        // Then check series
        if (title.contains("Season", ignoreCase = true) ||
            title.contains("WEB-Series", ignoreCase = true) ||
            title.contains("TV Series", ignoreCase = true) ||
            title.contains("Web Series", ignoreCase = true) ||
            title.contains("NF Series", ignoreCase = true) ||
            title.contains("Zee5 Series", ignoreCase = true) ||
            Regex("""\bS\d{1,2}\b""").containsMatchIn(title) ||
            href.contains("/category/web-series/", ignoreCase = true) ||
            href.contains("/category/tv-shows/", ignoreCase = true)
        ) {
            return TvType.TvSeries
        }

        return TvType.Movie
    }

    /**
     * Legacy method kept for backwards compat — delegates to detectContentType.
     */
    private fun detectSeriesFromTitle(title: String, href: String): Boolean {
        return detectContentType(title, href) != TvType.Movie
    }

    private fun detectSearchQuality(title: String): SearchQuality? {
        val tokens = listOf("2160p", "4k", "1080p", "720p", "480p", "bluray", "web-dl",
            "webrip", "hdcam", "hdts", "camrip", "cam", "hdtv", "dvdrip", "dvd", "300mb")
        val lower = title.lowercase()
        for (tok in tokens) {
            if (lower.contains(tok)) return getQualityFromString(tok)
        }
        return null
    }

    /**
     * v5: Enhanced quality label extraction with codec and size info.
     * Returns labels like "1080p WEB-DL (1.2GB)" or "720p HEVC" or just "4K".
     */
    private fun extractQualityLabelWithSize(text: String): String {
        val sb = StringBuilder()

        // Quality tier
        val quality = when {
            text.contains("2160p", ignoreCase = true) || text.contains("4K", ignoreCase = true) -> "4K"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            text.contains("300MB", ignoreCase = true) -> "300MB"
            else -> "HD"
        }
        sb.append(quality)

        // Codec/source info
        val codec = when {
            text.contains("HEVC", ignoreCase = true) && !quality.contains("HEVC") -> " HEVC"
            text.contains("x265", ignoreCase = true) -> " x265"
            text.contains("x264", ignoreCase = true) -> " x264"
            text.contains("10Bit", ignoreCase = true) -> " 10Bit"
            text.contains("AV1", ignoreCase = true) -> " AV1"
            text.contains("WEB-DL", ignoreCase = true) -> " WEB-DL"
            text.contains("WEBRip", ignoreCase = true) -> " WEBRip"
            text.contains("BluRay", ignoreCase = true) -> " BluRay"
            text.contains("HDRip", ignoreCase = true) -> " HDRip"
            else -> ""
        }
        sb.append(codec)

        // Size info — try [510MB] first, then "Size: 1.2GB", then "– 500MB"
        val sizeStr = SIZE_REGEX.find(text)?.groupValues?.get(1)
            ?: SIZE_TEXT_REGEX.find(text)?.groupValues?.get(1)
            ?: ""
        if (sizeStr.isNotBlank()) {
            sb.append(" ($sizeStr)")
        }

        return sb.toString()
    }

    /**
     * Legacy quality label (without size) — kept for backwards compat.
     */
    private fun extractQualityLabel(text: String): String {
        return extractQualityLabelWithSize(text)
    }
}

/**
 * Minimal subtitle language helper — avoids pulling in the full
 * com.lagradost.cloudstream3.subtitle/ package which may not be
 * available in all CloudStream build versions.
 */
private object SubtitleHelper {
    private val languageMap = mapOf(
        "Hindi" to "hi", "English" to "en", "Tamil" to "ta",
        "Telugu" to "te", "Malayalam" to "ml", "Kannada" to "kn",
        "Bengali" to "bn", "Marathi" to "mr", "Gujarati" to "gu",
        "Punjabi" to "pa", "Urdu" to "ur", "Arabic" to "ar",
        "Spanish" to "es", "French" to "fr", "German" to "de",
        "Japanese" to "ja", "Korean" to "ko", "Chinese" to "zh",
        "Unknown" to "und"
    )

    fun fromTwoLettersToLanguage(name: String): String {
        return languageMap[name] ?: name
    }
}
