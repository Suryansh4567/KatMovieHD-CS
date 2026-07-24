package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Centralised Log wrapper (FIX D3).
//
// Under a real Android runtime android.util.Log.* can never throw. But under
// the bare JVM unit-test runner the android.jar Log stub can throw
// RuntimeException("Stub!") inconsistently, which used to be swallowed ad-hoc
// with `runCatching { Log.i(...) }` at every call site (a code smell). The
// swallow now lives in exactly one place.
// ─────────────────────────────────────────────────────────────────────────────
internal object YupFlixLog {
    fun i(tag: String, msg: String) { try { Log.i(tag, msg) } catch (_: Throwable) {} }
    fun w(tag: String, msg: String) { try { Log.w(tag, msg) } catch (_: Throwable) {} }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        try { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) } catch (_: Throwable) {}
    }
}

// Minimal holder so retryOn429 (FIX D4) is pure and unit-testable without a
// network. Production passes the real HttpResponse code/body/Retry-After.
data class RateLimitedResponse(
    val code: Int,
    val body: String,
    val retryAfterHeader: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Pure, serialisable model types (also used directly by the unit tests).
// ─────────────────────────────────────────────────────────────────────────────

/** One entry of the API's `streamingLinks[]` array. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamingLink(
    val quality: String = "",
    val url: String = "",
    val type: String = "hls",
    val language: String = "",
    val headers: String = "",
    val userAgent: String = "",
    val drm: Boolean = false,
    val embed: Boolean = false,
    val isActive: Boolean = true,
)

data class MovieDetail(
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Int?,
    val plot: String?,
    val tags: List<String>,
    val duration: Int?,
    val actors: List<ActorData>,
    val streamingLinks: List<StreamingLink>,
)

data class EpisodeData(
    val name: String,
    val season: Int,
    val episode: Int,
    val stillUrl: String?,
    val overview: String?,
    val runtime: Int?,
    val streamingLinks: List<StreamingLink>,
)

data class SeriesDetail(
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Int?,
    val plot: String?,
    val tags: List<String>,
    val actors: List<ActorData>,
    val episodes: List<EpisodeData>,
)

class YupFlix : MainAPI() {

    override var name = "YupFlix"
    override var mainUrl = "https://watch.yupflix.org"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true
    override var hasQuickSearch = false
    override var hasChromecastSupport = true
    override var hasDownloadSupport = false
    override var lang = "en"

    companion object {
        private val mapper = jacksonObjectMapper()

        private const val TAG = "YupFlix"

        // TODO(v2): the API base rotates monthly per the site notice; v2 should
        // auto-discover it from the watch.yupflix.org JS bundle. Hardcoded for v1.
        private const val API_BASE = "https://jolly-mouse-f41c.annierane.workers.dev"
        private const val SITE_URL = "https://watch.yupflix.org"

        private val API_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
            "Origin" to SITE_URL,
            "Referer" to "$SITE_URL/",
        )

        // Mongo-style ObjectIds are 24 hex chars (confirmed from real API data).
        private val ID_REGEX = Regex("""/(?:detail|watch)/(movie|series)/([a-f0-9]{24})""")

        // ── pure helpers (unit-tested, no CS3 runtime needed) ──────────────

        fun extractId(url: String): Pair<String, String>? {
            val m = ID_REGEX.find(url) ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }

        fun qualityFromString(quality: String): Int = when (quality.lowercase(Locale.ROOT)) {
            "4k", "2160p" -> Qualities.P2160.value
            "1440p", "2k" -> Qualities.P1440.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        private fun toStreamingLink(n: JsonNode?): StreamingLink? {
            if (n == null || n.isNull) return null
            return StreamingLink(
                quality = n.get("quality")?.asText().orEmpty(),
                url = n.get("url")?.asText().orEmpty(),
                type = n.get("type")?.asText().orEmpty(),
                language = n.get("language")?.asText().orEmpty(),
                headers = n.get("headers")?.asText().orEmpty(),
                userAgent = n.get("userAgent")?.asText().orEmpty(),
                drm = n.get("drm")?.asBoolean() ?: false,
                embed = n.get("embed")?.asBoolean() ?: false,
                isActive = n.get("isActive")?.asBoolean() ?: true,
            )
        }

        private fun JsonNode?.strArray(field: String): List<String> {
            val node = this?.get(field)
            if (node !is ArrayNode) return emptyList()
            val out = mutableListOf<String>()
            for (c in node) {
                val v = c.get("name")?.asText()
                if (!v.isNullOrBlank()) out.add(v)
            }
            return out
        }

        private fun JsonNode?.actorArray(field: String): List<ActorData> {
            val node = this?.get(field)
            if (node !is ArrayNode) return emptyList()
            val out = mutableListOf<ActorData>()
            for (c in node) {
                val nm = c.get("name")?.asText() ?: continue
                out.add(ActorData(Actor(nm, c.get("profilePath")?.asText()), roleString = c.get("character")?.asText()))
            }
            return out
        }

        fun parseMovieDetail(json: String): MovieDetail {
            val n = mapper.readTree(json)
            val links = n.get("streamingLinks")
            val streamingLinks = if (links is ArrayNode) {
                val out = mutableListOf<StreamingLink>()
                for (l in links) {
                    val s = toStreamingLink(l)
                    if (s != null) out.add(s)
                }
                out
            } else {
                emptyList()
            }
            return MovieDetail(
                title = n.get("title")?.asText().orEmpty(),
                posterUrl = n.get("posterPath")?.asText(),
                backdropUrl = n.get("backdropPath")?.asText(),
                year = n.get("releaseDate")?.asText()?.take(4)?.toIntOrNull(),
                rating = (n.get("voteAverage")?.asDouble()?.times(1000))?.toInt(),
                plot = n.get("overview")?.asText(),
                tags = n.strArray("genres"),
                duration = n.get("runtime")?.asInt(),
                actors = n.actorArray("cast"),
                streamingLinks = streamingLinks,
            )
        }

        fun parseSeriesDetail(json: String): SeriesDetail {
            val n = mapper.readTree(json)
            val episodes = mutableListOf<EpisodeData>()
            val seasons = n.get("seasons")
            if (seasons is ArrayNode) {
                for (season in seasons) {
                    val seasonNumber = season.get("seasonNumber")?.asInt() ?: 1
                    val eps = season.get("episodes")
                    if (eps is ArrayNode) {
                        for (ep in eps) {
                            val links = ep.get("streamingLinks")
                            val streamingLinks = if (links is ArrayNode) {
                                val out = mutableListOf<StreamingLink>()
                                for (l in links) {
                                    val s = toStreamingLink(l)
                                    if (s != null) out.add(s)
                                }
                                out
                            } else {
                                emptyList()
                            }
                            episodes.add(
                                EpisodeData(
                                    name = ep.get("name")?.asText().orEmpty(),
                                    season = seasonNumber,
                                    episode = ep.get("episodeNumber")?.asInt() ?: 1,
                                    stillUrl = ep.get("stillPath")?.asText(),
                                    overview = ep.get("overview")?.asText(),
                                    runtime = ep.get("runtime")?.asInt(),
                                    streamingLinks = streamingLinks,
                                )
                            )
                        }
                    }
                }
            }
            return SeriesDetail(
                title = n.get("title")?.asText().orEmpty(),
                posterUrl = n.get("posterPath")?.asText(),
                backdropUrl = n.get("backdropPath")?.asText(),
                year = n.get("firstAirDate")?.asText()?.take(4)?.toIntOrNull()
                    ?: n.get("releaseDate")?.asText()?.take(4)?.toIntOrNull(),
                rating = (n.get("voteAverage")?.asDouble()?.times(1000))?.toInt(),
                plot = n.get("overview")?.asText(),
                tags = n.strArray("genres"),
                actors = n.actorArray("cast"),
                episodes = episodes,
            )
        }

        /** Convert API streamingLinks -> CS3 ExtractorLinks, applying all skip
         *  rules (inactive / drm / embed / blank url) and header merging.
         *
         *  FIX D1: built with the `newExtractorLink { }` builder (forward-
         *  compatible with CS3 SDK bumps). The previous 9-arg `ExtractorLink`
         *  constructor is deprecated and will break on future SDKs. */
        suspend fun toExtractorLinks(links: List<StreamingLink>): List<ExtractorLink> {
            val out = mutableListOf<ExtractorLink>()
            for (link in links) {
                if (!link.isActive) continue
                if (link.drm) continue
                if (link.embed) {
                    YupFlixLog.i(TAG, "skipping embed link url=${link.url}")
                    continue
                }
                if (link.url.isBlank()) continue

                val type = when (link.type.lowercase(Locale.ROOT)) {
                    "hls" -> ExtractorLinkType.M3U8
                    "mp4" -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8 // default
                }

                val extra = mutableMapOf<String, String>(
                    "Referer" to "$SITE_URL/",
                    "Origin" to SITE_URL,
                )
                if (link.userAgent.isNotBlank()) extra["User-Agent"] = link.userAgent
                if (link.headers.isNotBlank()) {
                    for (line in link.headers.lines()) {
                        val idx = line.indexOf(": ")
                        if (idx > 0) {
                            val k = line.substring(0, idx).trim()
                            val v = line.substring(idx + 2).trim()
                            if (k.isNotBlank()) extra[k] = v
                        }
                    }
                }

                val name = if (link.language.isNotBlank()) "YupFlix • ${link.language}" else "YupFlix"

                out.add(
                    newExtractorLink("YupFlix", name, link.url, type) {
                        this.quality = qualityFromString(link.quality)
                        this.referer = "$SITE_URL/"
                        this.headers = extra
                    }
                )
            }
            return out
        }

        /** FIX D4: retry a network block on HTTP 429 with linear backoff
         *  (1000ms, 2000ms) and honor the `Retry-After` header (capped at 5s).
         *  Returns null once `times` retries are exhausted so callers can
         *  degrade gracefully (never throw to CS3). Pure/testable: the `block`
         *  produces a [RateLimitedResponse] and `delay` is injectable. */
        suspend fun retryOn429(
            times: Int = 2,
            delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
            block: suspend (Int) -> RateLimitedResponse,
        ): RateLimitedResponse? {
            for (attempt in 0..times) {
                val r = block(attempt)
                if (r.code != 429) return r
                if (attempt < times) {
                    val secs = r.retryAfterHeader?.toLongOrNull()?.coerceAtMost(5) ?: (attempt + 1).toLong()
                    delay(secs * 1000L)
                }
            }
            return null
        }
    }

    // parseSearch / parseHomepage build SearchResponse objects via
    // newMovieSearchResponse, which needs a MainAPI receiver — hence they are
    // instance methods (not companion members).
    fun parseSearch(json: String): List<SearchResponse> {
        val tree = mapper.readTree(json)
        val out = mutableListOf<SearchResponse>()
        val movies = tree.get("movies")
        if (movies is ArrayNode) {
            for (n in movies) {
                val id = n.get("_id")?.asText().orEmpty()
                val title = n.get("title")?.asText().orEmpty()
                out.add(
                    newMovieSearchResponse(title, "$SITE_URL/detail/movie/$id", TvType.Movie) {
                        this.posterUrl = n.get("posterPath")?.asText()
                    }
                )
            }
        }
        val series = tree.get("series")
        if (series is ArrayNode) {
            for (n in series) {
                val id = n.get("_id")?.asText().orEmpty()
                val title = n.get("title")?.asText().orEmpty()
                out.add(
                    newMovieSearchResponse(title, "$SITE_URL/detail/series/$id", TvType.TvSeries) {
                        this.posterUrl = n.get("posterPath")?.asText()
                    }
                )
            }
        }
        return out
    }

    fun parseHomepage(json: String): List<HomePageList> {
        val tree = mapper.readTree(json)
        val data = tree.get("data")
        if (data !is ArrayNode) return emptyList()
        val out = mutableListOf<HomePageList>()
        for (section in data) {
            val title = section.get("title")?.asText() ?: continue
            val items = section.get("items")
            if (items !is ArrayNode || items.size() == 0) continue
            val list = mutableListOf<SearchResponse>()
            for (item in items) {
                val id = item.get("_id")?.asText().orEmpty()
                val t = item.get("type")?.asText().orEmpty()
                val name = item.get("title")?.asText().orEmpty()
                val type = if (t == "series") TvType.TvSeries else TvType.Movie
                list.add(
                    newMovieSearchResponse(name, "$SITE_URL/detail/$t/$id", type) {
                        this.posterUrl = item.get("posterPath")?.asText()
                    }
                )
            }
            if (list.isNotEmpty()) out.add(HomePageList(title, list))
        }
        return out
    }

    override val mainPage = mainPageOf(
        "${Companion.API_BASE}/api/views/homepage/sections" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val r = Companion.retryOn429(2) { _ ->
                val resp = app.get(request.data, headers = Companion.API_HEADERS)
                RateLimitedResponse(resp.code, resp.text, resp.headers["Retry-After"])
            }
            if (r == null) {
                YupFlixLog.w(TAG, "getMainPage rate-limited after 3 attempts; returning empty")
                newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
            } else {
                newHomePageResponse(parseHomepage(r.body), hasNext = false)
            }
        } catch (t: Throwable) {
            YupFlixLog.w(TAG, "getMainPage failed: ${t.message}")
            newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val url = "${Companion.API_BASE}/api/search?q=$q"
            val r = Companion.retryOn429(2) { _ ->
                val resp = app.get(url, headers = Companion.API_HEADERS)
                RateLimitedResponse(resp.code, resp.text, resp.headers["Retry-After"])
            }
            if (r == null) {
                YupFlixLog.w(TAG, "search rate-limited after 3 attempts; returning empty")
                emptyList()
            } else {
                parseSearch(r.body)
            }
        } catch (t: Throwable) {
            YupFlixLog.e(TAG, "search failed for '$query': ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val (type, id) = Companion.extractId(url) ?: run {
                YupFlixLog.w(TAG, "load: cannot parse id from $url")
                return null
            }
            val apiUrl = "${Companion.API_BASE}/api/${type}s/public/$id"
            val r = Companion.retryOn429(2) { _ ->
                val resp = app.get(apiUrl, headers = Companion.API_HEADERS)
                RateLimitedResponse(resp.code, resp.text, resp.headers["Retry-After"])
            }
            if (r == null) {
                YupFlixLog.w(TAG, "load rate-limited after 3 attempts for $apiUrl; returning null")
                return null
            }
            val text = r.body

            if (type == "movie") {
                val d = Companion.parseMovieDetail(text)
                val data = mapper.writeValueAsString(d.streamingLinks)
                newMovieLoadResponse(d.title, url, TvType.Movie, data) {
                    this.posterUrl = d.posterUrl
                    this.backgroundPosterUrl = d.backdropUrl
                    this.plot = d.plot
                    this.year = d.year
                    this.score = Score.from(d.rating ?: 0, 10000)
                    this.tags = d.tags
                    this.duration = d.duration
                    this.actors = d.actors
                }
            } else {
                val d = Companion.parseSeriesDetail(text)
                val episodes: List<Episode> = d.episodes.map { ep ->
                    newEpisode(mapper.writeValueAsString(ep.streamingLinks)) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = ep.stillUrl
                        this.description = ep.overview
                        this.runTime = ep.runtime
                    }
                }
                newTvSeriesLoadResponse(d.title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = d.posterUrl
                    this.backgroundPosterUrl = d.backdropUrl
                    this.plot = d.plot
                    this.year = d.year
                    this.score = Score.from(d.rating ?: 0, 10000)
                    this.tags = d.tags
                    this.actors = d.actors
                }
            }
        } catch (t: Throwable) {
            YupFlixLog.e(TAG, "load failed for $url: ${t.message}", t)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val links: List<StreamingLink> = mapper.readValue<List<StreamingLink>>(data)
            val extracted = Companion.toExtractorLinks(links)
            extracted.forEach { callback(it) }
            extracted.isNotEmpty()
        } catch (t: Throwable) {
            YupFlixLog.e(TAG, "loadLinks failed: ${t.message}", t)
            false
        }
    }
}
