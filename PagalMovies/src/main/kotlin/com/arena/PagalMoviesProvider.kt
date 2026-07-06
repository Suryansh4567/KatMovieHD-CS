package com.arena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class PagalMoviesAlpha : MainAPI() {
    override var mainUrl = "https://www.pagalmovies.boutique"
    override var name = "PagalMovies Alpha-Omega"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val tmdbApiKey = "a96013620f4c029df4f78326e7925c48"

    override val client = app.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            chain.proceed(request)
        }
        .build()

    private suspend fun getWorkingDomain(): String {
        val mirrors = listOf(
            "https://www.pagalmovies.boutique",
            "https://www.pagalmovies.com.co",
            "https://www.pagalmovies.top"
        )
        for (mirror in mirrors) {
            try {
                if (app.get(mirror, timeout = 3).isSuccessful) return mirror
            } catch (e: Exception) { continue }
        }
        return mainUrl
    }

    override val mainPage = mainPageOf(
        "/movielist/12/Hollywood_hindi_dubbed_movies/default/" to "Hollywood Hindi Dubbed",
        "/movielist/4/Bollywood_hindi_full_movies/default/" to "Bollywood Movies",
        "/movielist/14/South_indian_hindi_dubbed_movies/default/" to "South Hindi Dubbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = getWorkingDomain()
        val doc = app.get("$mainUrl${request.data}$page.html").document
        val home = doc.select("a:has(img[src*=thumb])").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "utf-8")
        val doc = app.get("$mainUrl/search.php?search=$encodedQuery").document
        return doc.select("a:has(img[src*=thumb])").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifEmpty { this.select("img").attr("alt") } ?: return null
        val href = this.attr("href") ?: return null
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrl(this@toSearchResult.select("img").attr("src"))
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: ""
        val cleanTitle = rawTitle.replace(Regex("(?i)\\(.*\\)|Full Movie|Hindi Dubbed"), "").trim()
        
        val sitePoster = fixUrl(doc.selectFirst("img[src*=files/images/]")?.attr("src") ?: "")
        val sitePlot = doc.select("b:contains(Description) + i").text()
        
        val tmdbSearch = try {
            val res = app.get("https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$cleanTitle").text
            JSONObject(res)
        } catch(e: Exception) { null }

        val result = tmdbSearch?.optJSONArray("results")?.optJSONObject(0)
        val posterPath = result?.optString("poster_path")
        val poster = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else sitePoster
        val plot = result?.optString("overview") ?: sitePlot

        val fileLinks = doc.select("a[href*=file/]").map { it.attr("href") }

        return newMovieLoadResponse(rawTitle, url, TvType.Movie, fileLinks.joinToString("###")) {
            this.posterUrl = poster
            this.plot = plot
            this.year = result?.optString("release_date")?.take(4)?.toIntOrNull()
            addTrailer(cleanTitle)
            result?.optInt("id", -1)?.let { id -> 
                if (id > 0) addActors(getActors(id)) 
            }
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (link in data.split("###")) {
            if (link.isBlank()) continue
            try {
                val filePageDoc = app.get(fixUrl(link)).document
                val serverUrl = fixUrl(filePageDoc.select("a:contains(Click Here to Go to Download Page)").attr("href"))
                
                if (serverUrl.isNotBlank()) {
                    val serverPageDoc = app.get(serverUrl).document
                    for (it in serverPageDoc.select("a[href*=/server/], a[href*=/download/], a:contains(Server)")) {
                        val href = it.attr("href") ?: continue
                        val finalRes = app.get(fixUrl(href), allowRedirects = true)
                        val finalUrl = finalRes.url
                        
                        if (finalUrl.contains(".mp4") || finalUrl.contains(".mkv") || finalUrl.contains(".webm")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = it.text().ifEmpty { "High Speed Server" },
                                    url = finalUrl,
                                    referer = mainUrl,
                                    quality = if (finalUrl.contains("720p")) Qualities.P720.value else Qualities.P480.value
                                )
                            )
                        }
                    }
                }
            } catch(e: Exception) { continue }
        }
        return true
    }

    private suspend fun getActors(tmdbId: Int): List<ActorData>? {
        return try {
            val res = app.get("https://api.themoviedb.org/3/movie/$tmdbId/credits?api_key=$tmdbApiKey").text
            val cast = JSONObject(res).optJSONArray("cast") ?: return null
            val actors = mutableListOf<ActorData>()
            for (i in 0 until minOf(cast.length(), 5)) {
                val actor = cast.getJSONObject(i)
                actors.add(ActorData(Actor(actor.getString("name"), "https://image.tmdb.org/t/p/w200${actor.optString("profile_path")}")))
            }
            actors
        } catch(e: Exception) { null }
    }
}
