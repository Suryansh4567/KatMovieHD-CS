package com.lagradost

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class OneFlex : MainAPI() {
    override var name = "1Flex"
    override var mainUrl = "https://www.1flex.org"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "https://db.1flex.org/trending/all/week?page=1" to "Trending Now",
        "https://db.1flex.org/movie/popular?page=1" to "Popular Movies",
        "https://db.1flex.org/tv/popular?page=1" to "Popular TV Shows",
        "https://db.1flex.org/movie/top_rated?page=1" to "Top Rated Movies",
        "https://db.1flex.org/tv/top_rated?page=1" to "Top Rated TV Shows",
        "https://db.1flex.org/movie/upcoming?page=1" to "Upcoming Movies"
    )

    private val mapper = jacksonObjectMapper()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbLoadData(
        val id: String,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null
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
        val url = "https://db.1flex.org/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1"
        val headers = mapOf("Referer" to "https://www.1flex.org/")
        val response = app.get(url, headers = headers).text
        val parsed = mapper.readValue<TmdbSearchResponse>(response)

        return parsed.results?.mapNotNull { item ->
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

            val year = item.releaseDate?.take(4)?.toIntOrNull()
            val duration = item.runtime
            val plot = item.overview

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterPath
                this.backgroundPosterUrl = backdropPath
                this.year = year
                this.duration = duration
                this.plot = plot
                addImdbId(item.imdbId)
            }
        } else {
            val detailsUrl = "https://db.1flex.org/tv/$tmdbId"
            val response = app.get(detailsUrl, headers = headers).text
            val item = mapper.readValue<TmdbItem>(response)

            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "Unknown TV Show"
            val posterPath = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdropPath = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

            val year = item.firstAirDate?.take(4)?.toIntOrNull()
            val plot = item.overview

            val episodesList = mutableListOf<Episode>()
            val numSeasons = item.numberOfSeasons ?: 1

            for (seasonNum in 1..numSeasons) {
                try {
                    val seasonUrl = "https://db.1flex.org/tv/$tmdbId/season/$seasonNum"
                    val seasonResponse = app.get(seasonUrl, headers = headers).text
                    val seasonDetails = mapper.readValue<TmdbSeasonDetails>(seasonResponse)

                    seasonDetails.episodes?.forEach { ep ->
                        val epData = TmdbLoadData(tmdbId, "tv", seasonNum, ep.episodeNumber)
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
                this.year = year
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

        try {
            // Step 1: Encrypt the TMDB ID via helper API
            val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
            val encResponse = app.get(encUrl).text
            val encRes = mapper.readValue<EncryptResponse>(encResponse)
            val encrypted = encRes.result ?: return false

            // Step 2: Fetch the playable direct links from Vidlink
            val apiUrl = if (loadData.type == "movie") {
                "https://vidlink.pro/api/b/movie/$encrypted"
            } else {
                val seasonNum = loadData.season ?: 1
                val episodeNum = loadData.episode ?: 1
                "https://vidlink.pro/api/b/tv/$encrypted/$seasonNum/$episodeNum"
            }

            val headers = mapOf(
                "Referer" to "https://vidlink.pro/",
                "Origin" to "https://vidlink.pro"
            )

            val streamResponse = app.get(apiUrl, headers = headers).text
            val streamRes = mapper.readValue<VidlinkResponse>(streamResponse)

            // Extract playable qualities
            streamRes.stream?.qualities?.forEach { (resKey, qualityData) ->
                val streamUrl = qualityData.url ?: return@forEach
                val qualityValue = when (resKey) {
                    "360" -> Qualities.P360.value
                    "480" -> Qualities.P480.value
                    "720" -> Qualities.P720.value
                    "1080" -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    newExtractorLink(
                        source = "Vidlink",
                        name = "Vidlink (${resKey}p)",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidlink.pro/"
                        this.quality = qualityValue
                    }
                )
            }

            // Extract subtitles/captions
            streamRes.stream?.captions?.forEach { caption ->
                val captionUrl = caption.url ?: return@forEach
                val language = caption.language ?: "Unknown"
                subtitleCallback(
                    newSubtitleFile(
                        language,
                        captionUrl
                    )
                )
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }
}
