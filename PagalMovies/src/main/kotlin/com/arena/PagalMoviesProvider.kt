package com.arena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class PagalMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.pagalmovies.boutique"
    override var name = "PagalMovies Elite (Arena)"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true // Refined: Enabling QuickSearch for better UX
    
    // Skill Level: Advanced. Using a dynamic list to handle future categories.
    override val mainPage = mainPageOf(
        "$mainUrl/movielist/12/Hollywood_hindi_dubbed_movies/default/" to "Hollywood Hindi Dubbed",
        "$mainUrl/movielist/4/Bollywood_hindi_full_movies/default/" to "Bollywood Movies",
        "$mainUrl/movielist/14/South_indian_hindi_dubbed_movies/default/" to "South Hindi Dubbed",
        "$mainUrl/movielist/21/Hindi_web_series/default/" to "Hindi Web Series"
    )

    // Skill Level: High Performance. Fetching with efficient selectors.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page.html").document
        val home = doc.select("div.main-container a:has(img), a:has(img[src*=thumb])").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Refined Search: Handles both Search & QuickSearch
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?search=$query"
        val doc = app.get(searchUrl).document
        return doc.select("a:has(img[src*=thumb])").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifEmpty { this.select("img").attr("alt") } ?: return null
        val href = fixUrl(this.attr("href"))
        val poster = fixUrl(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // Skill Level: Data Integrity. Extracting multiple quality variants if available.
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = fixUrl(doc.selectFirst("img[src*=files/images/]")?.attr("src") ?: "")
        val description = doc.select("b:contains(Description) + i").text()
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Endless Possibilities: We look for all possible file links, not just one.
        val fileLinks = doc.select("a[href*=file/]")
        val data = fileLinks.joinToString("###") { it.attr("href") }

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            // Adding a search for trailer on YouTube automatically
            addTrailer(title)
        }
    }

    // Skill Level: The "Bypass Master". 
    // Combines Phisher's scraping, CNC's extractor logic, and Megarix's speed.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Splitting multiple quality files if stored in data
        data.split("###").forEach { fileLink ->
            val filePageUrl = fixUrl(fileLink)
            val filePage = app.get(filePageUrl).document
            
            // PagalMovies Redirection Trap Bypass
            val downloadPageUrl = fixUrl(filePage.select("a:contains(Click Here to Go to Download Page), a.download-btn").attr("href"))
            if (downloadPageUrl.isEmpty()) return@forEach

            val serverPage = app.get(downloadPageUrl).document
            
            // Endless Possibilities: Smart Filtering for Servers
            serverPage.select("a[href*=/server/], a[href*=/download/], a:contains(Server)").forEach {
                val serverLink = fixUrl(it.attr("href"))
                
                // Using allowRedirects=true to catch the final .mp4 stream after 3+ jumps
                val finalResponse = app.get(serverLink, allowRedirects = true, timeout = 15)
                val finalUrl = finalResponse.url

                if (finalUrl.contains(".mp4") || finalUrl.contains(".mkv") || finalUrl.contains(".webm")) {
                    val quality = when {
                        finalUrl.contains("720p") -> Qualities.P720.value
                        finalUrl.contains("1080p") -> Qualities.P1080.value
                        else -> Qualities.P480.value
                    }

                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            it.text().ifEmpty { "Elite Stream" },
                            finalUrl,
                            referer = mainUrl,
                            quality = quality
                        )
                    )
                }
            }
        }
        return true
    }
}
