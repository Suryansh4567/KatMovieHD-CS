package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

class TheNextPlanet : MainAPI() {
    override var name = "TheNextPlanet"
    override var mainUrl = "https://www.thenextplanet-official.space"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "https://www.thenextplanet-official.space/Bollywood/?page=1" to "Bollywood Movies",
        "https://www.thenextplanet-official.space/Hollywood/?page=1" to "Hollywood Movies",
        "https://www.thenextplanet-official.space/south-movies/?page=1" to "South Indian Movies",
        "https://www.thenextplanet-official.space/Webseries/?page=1" to "Web & TV Series"
    )

    private val mapper = jacksonObjectMapper()

    companion object {
        const val TAG = "TheNextPlanet"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlanetLoadData(
        val url: String,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""(?i)\b(download|hindi|dubbed|dual|audio|web-dl|webdl|bluray|cam-rip|camrip|hevc|hd-tc|hdtc|full|movie|season|episode|episodes|webseries|web-series|series)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        // Select ONLY cards from the main movies table, filtering out header/footer/sidebar listings
        val articles = doc.select("table#movies_table article")
        Log.d(TAG, "parseCards found ${articles.size} article tags inside table#movies_table")

        return articles.mapNotNull { art ->
            val anchor = art.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href")

            // Block explicit/adult paths from home and search results
            if (href.contains("adult", ignoreCase = true) || href.contains("hitclit", ignoreCase = true)) {
                return@mapNotNull null
            }

            val url = absoluteUrl(href)
            val pName = art.selectFirst("p.movie_name")?.text()?.trim()
            val rawTitle = pName ?: anchor.attr("title").trim()

            if (url.isBlank() || rawTitle.isBlank()) return@mapNotNull null

            val cleanName = cleanTitle(rawTitle)
            val posterImg = art.selectFirst("img")?.attr("src")
            val posterUrl = posterImg?.let { absoluteUrl(it) }

            val isSeries = rawTitle.contains("Season", ignoreCase = true) || 
                           rawTitle.contains("S0", ignoreCase = true) || 
                           rawTitle.contains("S1", ignoreCase = true) ||
                           rawTitle.contains("Episodes", ignoreCase = true)

            val type = if (isSeries) TvType.TvSeries else TvType.Movie
            val loadData = PlanetLoadData(url, if (isSeries) "tv" else "movie")
            val serialized = mapper.writeValueAsString(loadData)

            Log.d(TAG, "Card parsed: title=$cleanName, type=$type, serialized=$serialized")

            newMovieSearchResponse(cleanName, serialized, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http")) return url
        val clean = url.trimStart('/')
        return "$mainUrl/$clean"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("page=1", "page=$page")
        Log.d(TAG, "getMainPage request: url=$url")
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/searchmovie?name=${URLEncoder.encode(query, "UTF-8")}"
        Log.d(TAG, "search initiated: query=$query, url=$url")
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)
        val results = parseCards(doc)
        Log.d(TAG, "search finished, found ${results.size} filtered results.")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load initiated: rawPayload=$url")
        val loadData = mapper.readValue<PlanetLoadData>(url)
        val pageUrl = loadData.url

        val headers = mapOf("Referer" to "$mainUrl/")
        val response = app.get(pageUrl, headers = headers).text
        val doc = Jsoup.parse(response)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"

        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { absoluteUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()

        // Correct TV Series Detection based on actual details page headings & title context
        val pageTitle = doc.title()
        val pageHeading = doc.selectFirst("h1")?.text() ?: ""
        val isSeries = pageTitle.contains("Season", ignoreCase = true) || 
                       pageTitle.contains("Episodes", ignoreCase = true) ||
                       pageTitle.contains("Web Series", ignoreCase = true) ||
                       pageHeading.contains("Season", ignoreCase = true) ||
                       pageHeading.contains("Episodes", ignoreCase = true) ||
                       pageHeading.contains("Web Series", ignoreCase = true)

        Log.d(TAG, "load parsing complete: title=$title, isSeries=$isSeries, year=$year")

        if (isSeries) {
            // Find episode counts inside the page context (such as: "1 to 08 episode(s)")
            val epsMatch = Regex("""(?i)Episode\s*\d+\s*to\s*(\d+)""").find(doc.html())
                ?: Regex("""(?i)(\d+)\s*episodes""").find(doc.html())
            val numEps = epsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            Log.d(TAG, "TV Series detected, found $numEps episodes.")

            val episodesList = (1..numEps).map { epNum ->
                val epData = PlanetLoadData(pageUrl, "tv", season = 1, episode = epNum)
                val epSerialized = mapper.writeValueAsString(epData)

                newEpisode(epSerialized) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = poster
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
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
        val loadData = mapper.readValue<PlanetLoadData>(data)
        val pageUrl = loadData.url
        val headers = mapOf("Referer" to "$mainUrl/")

        Log.d(TAG, "loadLinks initiated: url=$pageUrl, type=${loadData.type}, season=${loadData.season}, episode=${loadData.episode}")

        try {
            // Step 1: Load detail page to find the /galaxy/ link
            val response = app.get(pageUrl, headers = headers).text
            val doc = Jsoup.parse(response)
            val galaxyHref = doc.selectFirst("a[href*=/galaxy/]")?.attr("href") ?: return false
            val galaxyUrl = absoluteUrl(galaxyHref)
            Log.d(TAG, "Galaxy page link extracted: $galaxyUrl")

            // Step 2: Fetch /galaxy/ page to find the /unlock-links/ link
            val galaxyResponse = app.get(galaxyUrl, headers = headers).text
            val galaxyDoc = Jsoup.parse(galaxyResponse)
            val shortenerHref = galaxyDoc.selectFirst("a[href*=solution-hub.com]")?.attr("href") 
                ?: galaxyDoc.selectFirst("a[href*=/unlock-links/]")?.attr("href") 
                ?: return false

            // Extract the direct unlock-links URL from the shortener link if present
            val unlockUrl = if (shortenerHref.contains("link=")) {
                val encodedUrl = shortenerHref.substringAfter("link=")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                absoluteUrl(shortenerHref)
            }
            Log.d(TAG, "Unlock page link extracted: $unlockUrl")

            // Step 3: Fetch the /unlock-links/ page
            val unlockResponse = app.get(unlockUrl, headers = headers).text
            val unlockDoc = Jsoup.parse(unlockResponse)

            // Step 4: Extract and resolve direct hoster links (with quality/type and Stream Priorities)
            var foundAny = false
            val linkElements = unlockDoc.select("a[href*=/depisode/]")
            Log.d(TAG, "Found ${linkElements.size} raw link elements on unlock page.")

            // Prioritize Playable stream mirrors (GDFlix and Mediafire) over other download buttons!
            val sortedElements = linkElements.sortedByDescending { el ->
                val href = el.attr("href")
                href.contains("gdflix", ignoreCase = true) || href.contains("mediafire", ignoreCase = true)
            }

            for (el in sortedElements) {
                val href = el.attr("href")
                if (!href.contains("url=")) continue
                
                val finalUrl = href.substringAfter("url=")
                val decodedUrl = URLDecoder.decode(finalUrl, "UTF-8")
                val label = el.text().trim()

                // Filter out non-streamable file paths
                if (decodedUrl.contains(".jpg", ignoreCase = true) || decodedUrl.contains(".png", ignoreCase = true)) {
                    continue
                }

                val isGdflix = decodedUrl.contains("gdflix", ignoreCase = true)
                val isMediafire = decodedUrl.contains("mediafire", ignoreCase = true)

                // Label and style links to prioritize playable streams clearly
                val sourceName = when {
                    isGdflix -> "GdFlix Stream (Playable)"
                    isMediafire -> "Mediafire Stream (Playable)"
                    decodedUrl.contains("photolinx", ignoreCase = true) || decodedUrl.contains("photon", ignoreCase = true) -> "Photon Stream (Playable)"
                    else -> "TheNextPlanet Stream"
                }

                Log.d(TAG, "Extracting link: label='$label', url=$decodedUrl, sourceName=$sourceName")

                if (isGdflix) {
                    // Resolve natively via GDFlix Extractor class
                    runCatching {
                        Log.d(TAG, "Invoking GDFlix extractor natively for URL: $decodedUrl")
                        GDFlix().getUrl(decodedUrl, referer = unlockUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                } else if (isMediafire) {
                    // Resolve natively via loadExtractor
                    runCatching {
                        loadExtractor(decodedUrl, referer = unlockUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                } else {
                    // Expose directly
                    val isM3u8 = decodedUrl.contains(".m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val quality = when {
                        decodedUrl.contains("1080") -> Qualities.P1080.value
                        decodedUrl.contains("720") -> Qualities.P720.value
                        decodedUrl.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = if (label.isNotBlank()) "$sourceName ($label)" else sourceName,
                            url = decodedUrl,
                            type = linkType
                        ) {
                            this.referer = unlockUrl
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
            }

            Log.d(TAG, "loadLinks resolution finished. Emit success=$foundAny")
            return foundAny
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks execution exception: ${e.message}", e)
            return false
        }
    }
}

// Custom GDFlix Extractor to bypass gdflix.io / gdflix.dev landing pages and return final media stream links
class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://*.gdflix.*"
    override val requiresReferer = false

    private fun getIndexQuality(str: String?): Int {
        return when {
            str == null -> Qualities.Unknown.value
            "2160" in str || "4k" in str -> Qualities.P2160.value
            "1440" in str || "2k" in str -> Qualities.P1440.value
            "1080" in str -> Qualities.P1080.value
            "720" in str -> Qualities.P720.value
            "480" in str -> Qualities.P480.value
            "360" in str -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GDFlix", "GDFlix getUrl started: $url")
        val newUrl = try {
            app.get(url)
                .document
                .selectFirst("meta[http-equiv=refresh]")
                ?.attr("content")
                ?.substringAfter("url=")
        } catch (e: Exception) {
            Log.e("GDFlix", "Failed to fetch redirect: ${e.localizedMessage}")
            return
        } ?: url

        Log.d("GDFlix", "Redirect followed, landing page: $newUrl")
        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()
            Log.d("GDFlix", "Found button link text='$text', href='${anchor.attr("href")}'")

            when {
                text.contains("DIRECT DL", ignoreCase = true) -> {
                    val link = anchor.attr("href")
                    Log.d("GDFlix", "Emitting Direct DL link: $link")
                    callback.invoke(
                        newExtractorLink("GDFlix [Direct]", "GDFlix [Direct] [$fileSize]", link) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("DRIVEBOT", ignoreCase = true) -> {
                    try {
                        val driveLink = anchor.attr("href")
                        val id = driveLink.substringAfter("id=").substringBefore("&")
                        val doId = driveLink.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://drivebot.cfd")

                        baseUrls.amap { baseUrl ->
                            val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                            val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                            if (indexbotResponse.isSuccessful) {
                                val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                val indexbotDoc = indexbotResponse.document

                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val requestBody = FormBody.Builder()
                                    .add("token", token)
                                    .build()

                                val headers = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$baseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = headers,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                Log.d("GDFlix", "Emitting DriveBot link: $downloadLink")
                                callback.invoke(
                                    newExtractorLink("GDFlix [DriveBot]", "GDFlix [DriveBot] [$fileSize]", downloadLink) {
                                        this.referer = baseUrl
                                        this.quality = getIndexQuality(fileName)
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GDFlix", "DriveBot extraction exception: ${e.message}", e)
                    }
                }

                text.contains("Instant DL", ignoreCase = true) -> {
                    try {
                        val instantLink = anchor.attr("href")
                        val link = app.get(instantLink, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        Log.d("GDFlix", "Emitting Instant DL link: $link")
                        callback.invoke(
                            newExtractorLink("GDFlix [Instant Download]", "GDFlix [Instant Download] [$fileSize]", link) {
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("GDFlix", "Instant DL extraction exception: ${e.message}", e)
                    }
                }

                text.contains("GoFile", ignoreCase = true) -> {
                    try {
                        val gofileUrl = anchor.attr("href")
                        Log.d("GDFlix", "Following GoFile redirect url: $gofileUrl")
                        app.get(gofileUrl).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Log.d("GDFlix", "Invoking Gofile extractor for link: $link")
                                    loadExtractor(link, referer = "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.e("GDFlix", "Gofile redirect exception: ${e.message}", e)
                    }
                }

                text.contains("PixelDrain", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> {
                    val pixelUrl = anchor.attr("href")
                    Log.d("GDFlix", "Invoking Pixeldrain extractor for link: $pixelUrl")
                    runCatching {
                        loadExtractor(pixelUrl, referer = "", subtitleCallback, callback)
                    }
                }

                else -> {
                    Log.d("GDFlix", "No specific server matched for button text: $text")
                }
            }
        }

        // Cloudflare backup links
        try {
            val types = listOf("type=1", "type=2")
            types.map { type ->
                val sourceurl = app.get("${newUrl.replace("file", "wfile")}?$type")
                    .document.select("a.btn-success").attr("href")

                if (sourceurl.isNotEmpty()) {
                    Log.d("GDFlix", "Emitting Cloudflare Backup link ($type): $sourceurl")
                    callback.invoke(
                        newExtractorLink("GDFlix [CF]", "GDFlix [CF] [$fileSize]", sourceurl) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "CF backup extraction exception: ${e.message}", e)
        }
    }
}
