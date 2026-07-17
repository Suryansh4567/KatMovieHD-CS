package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class CineHD : MainAPI() {
    override var name = "CineHD"
    override var mainUrl = "https://cinehd.app"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "https://db.1flex.org/trending/all/week?page=1" to "Trending Now",
        "https://db.1flex.org/movie/popular?page=1" to "Popular Movies",
        "https://db.1flex.org/tv/popular?page=1" to "Popular TV Shows",
        "https://db.1flex.org/movie/top_rated?page=1" to "Top Rated Movies",
        "https://db.1flex.org/tv/top_rated?page=1" to "Top Rated TV Shows"
    )

    private val mapper = jacksonObjectMapper()

    companion object {
        private const val TAG = "CineHD"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbLoadData(
        val id: String,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: String? = null,
        val imdbId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinehdSearchResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: List<TmdbItem>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearchResponse(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("results") val results: List<TmdbItem>?,
        @JsonProperty("total_pages") val totalPages: Int?,
        @JsonProperty("total_results") val totalResults: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int?,
        @JsonProperty("imdb_id") val imdbId: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonDetails(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("air_date") val airDate: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EncryptResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkResponse(
        @JsonProperty("sourceId") val sourceId: String?,
        @JsonProperty("stream") val stream: VidlinkStream?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkStream(
        @JsonProperty("id") val id: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("qualities") val qualities: Map<String, VidlinkQuality>?,
        @JsonProperty("captions") val captions: List<VidlinkCaption>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkQuality(
        @JsonProperty("type") val type: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkCaption(
        @JsonProperty("id") val id: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("language") val language: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("hasCorsRestrictions") val hasCorsRestrictions: Boolean?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideasySeedResponse(
        @JsonProperty("seed") val seed: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideasyDecryptedResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: VideasyResult?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideasyResult(
        @JsonProperty("sources") val sources: List<VideasySource>?,
        @JsonProperty("subtitles") val subtitles: List<VideasySubtitle>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideasySource(
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideasySubtitle(
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("language") val language: String?,
        @JsonProperty("url") val url: String?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("page=1", "page=$page")
        val headers = mapOf("Referer" to "https://www.1flex.org/")
        val response = app.get(url, headers = headers).text
        val parsed = mapper.readValue<TmdbSearchResponse>(response)

        val isMovie = url.contains("/movie") || (url.contains("/trending") && parsed.results?.firstOrNull()?.mediaType == "movie")

        val items = parsed.results?.mapNotNull { item ->
            val isItemMovie = item.mediaType?.equals("movie", true) ?: isMovie
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
            val id = item.id.toString()
            val posterPath = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            val type = if (isItemMovie) TvType.Movie else TvType.TvSeries
            val tmdbData = TmdbLoadData(id, if (isItemMovie) "movie" else "tv")
            val serialized = mapper.writeValueAsString(tmdbData)

            newMovieSearchResponse(title, serialized, type) {
                this.posterUrl = posterPath
            }
        }.orEmpty()

        return newHomePageResponse(request.name, items, hasNext = parsed.totalPages?.let { page < it } ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://cinehd.app/api/search/suggest?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url).text
        val parsed = mapper.readValue<CinehdSearchResponse>(response)

        return parsed.data?.mapNotNull { item ->
            val isItemMovie = item.mediaType?.equals("movie", true) ?: true
            if (!isItemMovie && !item.mediaType.equals("tv", true)) return@mapNotNull null

            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
            val id = item.id.toString()
            val posterPath = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            val type = if (isItemMovie) TvType.Movie else TvType.TvSeries
            val tmdbData = TmdbLoadData(id, if (isItemMovie) "movie" else "tv")
            val serialized = mapper.writeValueAsString(tmdbData)

            newMovieSearchResponse(title, serialized, type) {
                this.posterUrl = posterPath
            }
        }.orEmpty()
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = mapper.readValue<TmdbLoadData>(url)
        val tmdbId = loadData.id
        val headers = mapOf("Referer" to "https://www.1flex.org/")

        if (loadData.type == "movie") {
            val detailsUrl = "https://db.1flex.org/movie/$tmdbId"
            val response = app.get(detailsUrl, headers = headers).text
            val item = mapper.readValue<TmdbItem>(response)

            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "Unknown Movie"
            val posterPath = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdropPath = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

            val year = item.releaseDate?.take(4)
            val duration = item.runtime

            val updatedLoadData = TmdbLoadData(
                id = tmdbId,
                type = "movie",
                title = title,
                year = year,
                imdbId = item.imdbId
            )
            val serialized = mapper.writeValueAsString(updatedLoadData)

            return newMovieLoadResponse(title, serialized, TvType.Movie, serialized) {
                this.posterUrl = posterPath
                this.backgroundPosterUrl = backdropPath
                this.year = year?.toIntOrNull()
                this.duration = duration
                this.plot = item.overview
                addImdbId(item.imdbId)
            }
        } else {
            val detailsUrl = "https://db.1flex.org/tv/$tmdbId"
            val response = app.get(detailsUrl, headers = headers).text
            val item = mapper.readValue<TmdbItem>(response)

            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "Unknown TV Show"
            val posterPath = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdropPath = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

            val year = item.firstAirDate?.take(4)
            val plot = item.overview

            val episodesList = mutableListOf<Episode>()
            val numSeasons = item.numberOfSeasons ?: 1

            for (seasonNum in 1..numSeasons) {
                try {
                    val seasonUrl = "https://db.1flex.org/tv/$tmdbId/season/$seasonNum"
                    val seasonResponse = app.get(seasonUrl, headers = headers).text
                    val seasonDetails = mapper.readValue<TmdbSeasonDetails>(seasonResponse)

                    seasonDetails.episodes?.forEach { ep ->
                        val epData = TmdbLoadData(
                            id = tmdbId,
                            type = "tv",
                            season = seasonNum,
                            episode = ep.episodeNumber,
                            title = title,
                            year = year,
                            imdbId = item.imdbId
                        )
                        val epSerialized = mapper.writeValueAsString(epData)
                        val epTitle = ep.name ?: "Episode ${ep.episodeNumber}"
                        val epPoster = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }

                        episodesList.add(
                            newEpisode(epSerialized) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = ep.episodeNumber
                                this.posterUrl = epPoster
                                this.description = ep.overview
                                addDate(ep.airDate)
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Fail-safe per-season parsing
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = posterPath
                this.backgroundPosterUrl = backdropPath
                this.year = year?.toIntOrNull()
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
        val loadData = mapper.readValue<TmdbLoadData>(data)
        val tmdbId = loadData.id

        Log.d(TAG, "loadLinks initiated: id=$tmdbId, type=${loadData.type}, season=${loadData.season}, episode=${loadData.episode}")

        // 1. Vidlink resolution
        val resolvedVidlink = runCatching { resolveVidlink(loadData, callback, subtitleCallback) }.getOrElse {
            Log.e(TAG, "Vidlink resolution exception: ${it.message}", it)
            false
        }

        // 2. Videasy resolution
        val resolvedVideasy = runCatching { resolveVideasy(loadData, callback, subtitleCallback) }.getOrElse {
            Log.e(TAG, "Videasy resolution exception: ${it.message}", it)
            false
        }

        // 3. 2Embed resolution
        val resolved2Embed = runCatching { resolve2Embed(loadData, callback) }.getOrElse {
            Log.e(TAG, "2Embed resolution exception: ${it.message}", it)
            false
        }

        // Phase 1.5 Indian languages resolution (Hindi, Tamil, Telugu)
        val resolvedHindi = runCatching { resolveNxsha(loadData, "hindi", "Hindi", callback) }.getOrElse {
            Log.e(TAG, "Hindi resolution exception: ${it.message}", it)
            false
        }

        val resolvedTamil = runCatching { resolveNxsha(loadData, "tamil", "Tamil", callback) }.getOrElse {
            Log.e(TAG, "Tamil resolution exception: ${it.message}", it)
            false
        }

        val resolvedTelugu = runCatching { resolveNxsha(loadData, "telugu", "Telugu", callback) }.getOrElse {
            Log.e(TAG, "Telugu resolution exception: ${it.message}", it)
            false
        }

        return resolvedVidlink || resolvedVideasy || resolved2Embed || resolvedHindi || resolvedTamil || resolvedTelugu
    }

    private suspend fun resolveVidlink(
        loadData: TmdbLoadData,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val tmdbId = loadData.id
        Log.d(TAG, "[Vidlink] Resolution started for ID: $tmdbId")

        // Step 1: Encrypt the TMDB ID via helper API
        val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
        Log.d(TAG, "[Vidlink] Requesting encryption: $encUrl")
        val encResponse = app.get(encUrl).text
        val encRes = mapper.readValue<EncryptResponse>(encResponse)
        val encrypted = encRes.result
        if (encrypted.isNullOrBlank()) {
            Log.w(TAG, "[Vidlink] Encryption returned blank result.")
            return false
        }
        Log.d(TAG, "[Vidlink] Encrypted ID: $encrypted")

        // Step 2: Fetch stream links
        val apiUrl = if (loadData.type == "movie") {
            "https://vidlink.pro/api/b/movie/$encrypted"
        } else {
            val seasonNum = loadData.season ?: 1
            val episodeNum = loadData.episode ?: 1
            "https://vidlink.pro/api/b/tv/$encrypted/$seasonNum/$episodeNum"
        }
        Log.d(TAG, "[Vidlink] Fetching stream links from: $apiUrl")

        val headers = mapOf(
            "Referer" to "https://vidlink.pro/",
            "Origin" to "https://vidlink.pro"
        )
        val streamResponse = app.get(apiUrl, headers = headers).text
        Log.d(TAG, "[Vidlink] Response snippet: ${streamResponse.take(300)}")

        val streamRes = mapper.readValue<VidlinkResponse>(streamResponse)
        val stream = streamRes.stream ?: return false
        val qualities = stream.qualities
        if (qualities.isNullOrEmpty()) {
            Log.w(TAG, "[Vidlink] No qualities found.")
            return false
        }

        qualities.forEach { (resKey, qualityData) ->
            val streamUrl = qualityData.url ?: return@forEach
            val qualityValue = when (resKey) {
                "360" -> Qualities.P360.value
                "480" -> Qualities.P480.value
                "720" -> Qualities.P720.value
                "1080" -> Qualities.P1080.value
                else -> Qualities.Unknown.value
            }

            val isM3u8Url = streamUrl.contains(".m3u8", ignoreCase = true)
            val linkType = if (isM3u8Url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            Log.d(TAG, "[Vidlink] Emitting ExtractorLink: quality=$resKey, type=$linkType, url=$streamUrl")
            callback(
                newExtractorLink(
                    source = "Vidlink",
                    name = "Vidlink (${resKey}p)",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = "https://vidlink.pro/"
                    this.quality = qualityValue
                }
            )
        }

        stream.captions?.forEach { caption ->
            val captionUrl = caption.url ?: return@forEach
            val language = caption.language ?: "Unknown"
            Log.d(TAG, "[Vidlink] Emitting subtitle: lang=$language, url=$captionUrl")
            subtitleCallback(
                newSubtitleFile(
                    language,
                    captionUrl
                )
            )
        }

        return true
    }

    private suspend fun resolveVideasy(
        loadData: TmdbLoadData,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val tmdbId = loadData.id
        val title = loadData.title ?: return false
        val year = loadData.year ?: ""
        val imdbId = loadData.imdbId ?: ""

        Log.d(TAG, "[Videasy] Resolution started for ID: $tmdbId, title=$title, year=$year, imdbId=$imdbId")

        // 1. Fetch seed
        val seedUrl = "https://api.wingsdatabase.com/seed?mediaId=$tmdbId"
        Log.d(TAG, "[Videasy] Fetching seed: $seedUrl")
        val seedResponse = app.get(
            seedUrl,
            headers = mapOf(
                "Referer" to "https://player.videasy.to/",
                "Origin" to "https://player.videasy.to"
            )
        ).text
        Log.d(TAG, "[Videasy] Seed response raw: $seedResponse")
        val seedRes = mapper.readValue<VideasySeedResponse>(seedResponse)
        val seed = seedRes.seed
        if (seed.isNullOrBlank()) {
            Log.w(TAG, "[Videasy] Seed is null or blank.")
            return false
        }

        // 2. Fetch encrypted sources from wingsdatabase (try cdn, fall back to jett if empty)
        val encTitle = URLEncoder.encode(URLEncoder.encode(title, "UTF-8"), "UTF-8")
        val servers = listOf("cdn", "jett")
        var encData = ""
        var usedServer = ""

        for (srv in servers) {
            val srvUrl = if (loadData.type == "movie") {
                "https://api.wingsdatabase.com/$srv/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
            } else {
                val seasonNum = loadData.season ?: 1
                val episodeNum = loadData.episode ?: 1
                "https://api.wingsdatabase.com/$srv/sources-with-title?title=$encTitle&mediaType=tv&year=$year&episodeId=$episodeNum&seasonId=$seasonNum&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
            }

            Log.d(TAG, "[Videasy] Fetching encrypted payload from server ($srv): $srvUrl")
            try {
                val res = app.get(
                    srvUrl,
                    headers = mapOf(
                        "Referer" to "https://player.videasy.to/",
                        "Origin" to "https://player.videasy.to"
                    )
                ).text
                if (res.isNotBlank() && !res.contains("error", true) && !res.contains("Cannot GET", true)) {
                    encData = res
                    usedServer = srv
                    Log.d(TAG, "[Videasy] Successfully retrieved encrypted payload from server ($srv). snippet: ${encData.take(50)}")
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Videasy] Server ($srv) request failed: ${e.message}")
            }
        }

        if (encData.isBlank()) {
            Log.w(TAG, "[Videasy] Failed to retrieve encrypted payload from any wingsdatabase server.")
            return false
        }

        // 3. Decrypt via enc-dec.app
        val decUrl = "https://enc-dec.app/api/dec-videasy"
        Log.d(TAG, "[Videasy] Requesting decryption from helper: $decUrl")
        val decPayload = mapOf(
            "text" to encData,
            "id" to tmdbId,
            "seed" to seed
        )
        val decResponse = app.post(
            decUrl,
            json = decPayload
        ).text
        Log.d(TAG, "[Videasy] Decrypted response snippet: ${decResponse.take(500)}")

        val decRes = mapper.readValue<VideasyDecryptedResponse>(decResponse)
        val result = decRes.result
        if (result == null) {
            Log.w(TAG, "[Videasy] Decrypted result is null.")
            return false
        }

        // Extract sources
        var emittedAny = false
        result.sources?.forEach { source ->
            val streamUrl = source.url ?: return@forEach
            val rawQuality = source.quality ?: "Auto"
            val qualityValue = when (rawQuality.lowercase()) {
                "360p" -> Qualities.P360.value
                "480p" -> Qualities.P480.value
                "720p" -> Qualities.P720.value
                "1080p" -> Qualities.P1080.value
                "4k" -> Qualities.P2160.value
                else -> Qualities.Unknown.value
            }

            val isM3u8Url = streamUrl.contains(".m3u8", ignoreCase = true)
            val linkType = if (isM3u8Url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            Log.d(TAG, "[Videasy] Emitting ExtractorLink: quality=$rawQuality (${qualityValue}), type=$linkType, url=$streamUrl")
            callback(
                newExtractorLink(
                    source = "Videasy",
                    name = "Videasy (${usedServer.uppercase()} • $rawQuality)",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = "https://player.videasy.to/"
                    this.quality = qualityValue
                }
            )
            emittedAny = true
        }

        // Extract subtitles
        result.subtitles?.forEach { subtitle ->
            val subtitleUrl = subtitle.url ?: return@forEach
            val language = subtitle.lang ?: subtitle.language ?: "Unknown"
            Log.d(TAG, "[Videasy] Emitting subtitle: lang=$language, url=$subtitleUrl")
            subtitleCallback(
                newSubtitleFile(
                    language,
                    subtitleUrl
                )
            )
        }

        return emittedAny
    }

    private suspend fun resolve2Embed(
        loadData: TmdbLoadData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = loadData.id
        Log.d(TAG, "[2Embed] Resolution started for ID: $tmdbId")

        val embedUrl = if (loadData.type == "movie") {
            "https://www.2embed.stream/embed/movie/$tmdbId"
        } else {
            val seasonNum = loadData.season ?: 1
            val episodeNum = loadData.episode ?: 1
            "https://www.2embed.stream/embed/tv/$tmdbId/$seasonNum/$episodeNum"
        }

        Log.d(TAG, "[2Embed] Emitting ExtractorLink: url=$embedUrl")
        callback(
            newExtractorLink(
                source = "2Embed",
                name = "2Embed",
                url = embedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.2embed.stream/"
            }
        )

        return true
    }

    private suspend fun resolveNxsha(
        loadData: TmdbLoadData,
        lang: String,
        labelName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = loadData.id
        Log.d(TAG, "[$labelName] Resolution started: id=$tmdbId, lang=$lang")

        val url = if (loadData.type == "movie") {
            "https://web.nxsha.app/embed/movie/$tmdbId?lang=$lang&autoplay=true"
        } else {
            val seasonNum = loadData.season ?: 1
            val episodeNum = loadData.episode ?: 1
            "https://web.nxsha.app/embed/tv/$tmdbId/$seasonNum/$episodeNum?lang=$lang&autoplay=true"
        }

        Log.d(TAG, "[$labelName] Requesting URL via WebViewResolver: $url")
        try {
            val response = app.get(
                url,
                interceptor = WebViewResolver(Regex(".*\\.m3u8.*|.*\\.mp4.*"))
            )
            val streamUrl = response.url
            if (streamUrl.isNotBlank() && streamUrl != url) {
                Log.d(TAG, "[$labelName] Intercepted stream link successfully: $streamUrl")
                val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback(
                    newExtractorLink(
                        source = labelName,
                        name = "$labelName (HLS)",
                        url = streamUrl,
                        type = linkType
                    ) {
                        this.referer = "https://web.nxsha.app/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            } else {
                Log.w(TAG, "[$labelName] WebViewResolver finished but failed to intercept any media request. Returned URL: $streamUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$labelName] WebViewResolver exception: ${e.message}", e)
        }
        return false
    }
}
