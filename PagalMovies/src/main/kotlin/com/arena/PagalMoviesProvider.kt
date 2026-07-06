package com.arena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class PagalMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.pagalmovies.boutique"
    override var name = "PagalMovies Alpha-Omega"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val tmdbApiKey = "a96013620f4c029df4f78326e7925c48"
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    private suspend fun getWorkingDomain(): String {
        val mirrors = listOf(
            "https://www.pagalmovies.boutique",
            "https://www.pagalmovies.com.co",
            "https://www.pagalmovies.top",
            "https://www.pagalmovies.icu"
        )
        for (mirror in mirrors) {
            try {
                val res = app.get(mirror, timeout = 3)
                if (res.isSuccessful) return mirror.trimEnd('/')
            } catch (e: Exception) { continue }
        }
        return mainUrl
    }

    override val mainPage = mainPageOf(
        "/movielist/12/Hollywood_hindi_dubbed_movies/default/" to "Hollywood Hindi Dubbed",
        "/movielist/4/Bollywood_hindi_full_movies/default/" to "Bollywood Movies",
        "/movielist/14/South_indian_hindi_dubbed_movies/default/" to "South Hindi Dubbed",
        "/movielist/21/Hindi_web_series/default/" to "Hindi Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getWorkingDomain()
        mainUrl = base
        val url = if (request.data.startsWith("http")) request.data else "$base${request.data}"
        val doc = app.get("$url$page.html", headers = mapOf("User-Agent" to userAgent)).document
        val home = doc.select("a:has(img[src*=thumb])").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getWorkingDomain()
        val doc = app.get("$base/search.php?search=${URLEncoder.encode(query, "utf-8")}", headers = mapOf("User-Agent" to userAgent)).document
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
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: ""
        val cleanTitle = rawTitle.replace(Regex("(?i)\\(.*\\)|Full Movie|Hindi Dubbed|Hollywood|Bollywood"), "").trim()
        
        val tmdbSearch = try {
            JSONObject(app.get("https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=${URLEncoder.encode(cleanTitle, "UTF-8")}").text)
        } catch(e: Exception) { null }

        val result = tmdbSearch?.optJSONArray("results")?.optJSONObject(0)
        val poster = result?.optString("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" } ?: fixUrl(doc.selectFirst("img[src*=files/images/]")?.attr("src") ?: "")
        val plot = result?.optString("overview") ?: doc.select("b:contains(Description) + i").text()

        val fileLinks = doc.select("a[href*=file/]").map { it.attr("href") }

        return newMovieLoadResponse(rawTitle, url, TvType.Movie, fileLinks.joinToString("###")) {
            this.posterUrl = poster
            this.plot = plot
            this.year = result?.optString("release_date")?.take(4)?.toIntOrNull()
            addTrailer(cleanTitle)
            result?.optInt("id", -1)?.let { id -> 
                if (id > 0) {
                    val actors = getActors(id)
                    if (actors != null) addActors(actors)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split("###")
        for (link in links) {
            if (link.isBlank()) continue
            try {
                val filePageUrl = fixUrl(link)
                val filePageDoc = app.get(filePageUrl, headers = mapOf("User-Agent" to userAgent)).document
                val dPageUrl = fixUrl(filePageDoc.select("a:contains(Click Here to Go to Download Page), a.download-btn").attr("href"))
                
                if (dPageUrl.isNotBlank()) {
                    val serverPageDoc = app.get(dPageUrl, headers = mapOf("User-Agent" to userAgent), referer = filePageUrl).document
                    val servers = serverPageDoc.select("a[href*=/server/], a[href*=/download/], a:contains(Server)")
                    
                    for (it in servers) {
                        val serverHref = it.attr("href") ?: continue
                        val finalRes = app.get(fixUrl(serverHref), headers = mapOf("User-Agent" to userAgent), allowRedirects = true)
                        val finalUrl = finalRes.url
                        
                        if (finalUrl.contains(".mp4") || finalUrl.contains(".mkv") || finalUrl.contains(".webm") || finalRes.code == 200) {
                            val quality = if (finalUrl.contains("720p")) Qualities.P720.value else Qualities.P480.value
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    it.text().ifEmpty { "Elite Stream" },
                                    finalUrl
                                ) {
                                    this.quality = quality
                                    this.referer = dPageUrl
                                }
                            )
                        }
                    }
                }
            } catch(e: Exception) { continue }
        }
        return true
    }

    private suspend fun getActors(tmdbId: Int): List<Actor>? {
        return try {
            val cast = JSONObject(app.get("https://api.themoviedb.org/3/movie/$tmdbId/credits?api_key=$tmdbApiKey").text).optJSONArray("cast") ?: return null
            val actors = mutableListOf<Actor>()
            for (i in 0 until minOf(cast.length(), 10)) {
                val a = cast.getJSONObject(i)
                actors.add(Actor(a.getString("name"), "https://image.tmdb.org/t/p/w200${a.optString("profile_path")}"))
            }
            actors
        } catch(e: Exception) { null }
    }
}
