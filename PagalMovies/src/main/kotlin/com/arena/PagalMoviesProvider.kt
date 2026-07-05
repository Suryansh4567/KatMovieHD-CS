package com.arena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PagalMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.pagalmovies.boutique"
    override var name = "PagalMovies Elite"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    
    override val mainPage = mainPageOf(
        "$mainUrl/movielist/12/Hollywood_hindi_dubbed_movies/default/" to "Hollywood Hindi Dubbed",
        "$mainUrl/movielist/4/Bollywood_hindi_full_movies/default/" to "Bollywood Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page.html").document
        val home = doc.select("a:has(img[src*=thumb])").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title") ?: return null
        val href = fixUrl(this.attr("href"))
        val poster = fixUrl(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = fixUrl(doc.selectFirst("img[src*=files/images/]")?.attr("src") ?: "")
        val description = doc.select("b:contains(Description) + i").text()
        
        val fileUrl = fixUrl(doc.select("a[href*=file/]").attr("href"))

        return newMovieLoadResponse(title, url, TvType.Movie, fileUrl) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val filePage = app.get(data).document
        val serverUrl = fixUrl(filePage.select("a:contains(Click Here to Go to Download Page)").attr("href"))

        val serverPage = app.get(serverUrl).document
        
        serverPage.select("a[href*=/download/], a:contains(Server)").forEach {
            val finalRedirectUrl = fixUrl(it.attr("href"))
            val finalRes = app.get(finalRedirectUrl, allowRedirects = true)
            val streamUrl = finalRes.url

            if (streamUrl.contains(".mp4") || streamUrl.contains(".mkv")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "High Speed Server",
                        streamUrl,
                        referer = mainUrl,
                        quality = Qualities.P480.value
                    )
                )
            }
        }
        return true
    }
}
