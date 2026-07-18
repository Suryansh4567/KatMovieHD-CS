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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // Central Filename Parser to extract resolution, source, language, codec, and size cleanly
        fun parseFilename(filename: String): String {
            val cleanName = filename.replace("_", ".").replace(" ", ".")
            Log.d(TAG, "Parsing filename: raw='$filename', cleaned='$cleanName'")

            val resolution = when {
                "2160p" in cleanName || "2160" in cleanName || "4k" in cleanName.lowercase() -> "2160p"
                "1080p" in cleanName || "1080" in cleanName -> "1080p"
                "720p" in cleanName || "720" in cleanName -> "720p"
                "480p" in cleanName || "480" in cleanName -> "480p"
                else -> ""
            }

            val source = when {
                "web-dl" in cleanName.lowercase() || "webdl" in cleanName.lowercase() -> "WEB-DL"
                "webrip" in cleanName.lowercase() || "web-rip" in cleanName.lowercase() -> "WEBRip"
                "bluray" in cleanName.lowercase() || "blu-ray" in cleanName.lowercase() -> "BluRay"
                "hdrip" in cleanName.lowercase() || "hd-rip" in cleanName.lowercase() -> "HDRip"
                "dvdrip" in cleanName.lowercase() -> "DVDRip"
                "remux" in cleanName.lowercase() -> "REMUX"
                "cam" in cleanName.lowercase() || "tc" in cleanName.lowercase() -> "CAM"
                else -> ""
            }

            val codec = when {
                "x265" in cleanName.lowercase() || "hevc" in cleanName.lowercase() -> "x265"
                "x264" in cleanName.lowercase() || "h264" in cleanName.lowercase() -> "x264"
                "10bit" in cleanName.lowercase() -> "10Bit"
                else -> ""
            }

            val language = when {
                "dual" in cleanName.lowercase() -> "Dual Audio"
                "multi" in cleanName.lowercase() -> "Multi Audio"
                "hindi" in cleanName.lowercase() -> "Hindi"
                "english" in cleanName.lowercase() || "eng" in cleanName.lowercase() -> "English"
                "tamil" in cleanName.lowercase() -> "Tamil"
                "telugu" in cleanName.lowercase() -> "Telugu"
                "malayalam" in cleanName.lowercase() -> "Malayalam"
                else -> ""
            }

            val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*(?:GB|MB))\b""", RegexOption.IGNORE_CASE).find(cleanName)
            val size = sizeMatch?.groupValues?.get(1)?.uppercase() ?: ""

            val parts = listOf(resolution, source, language, codec, size).filter { it.isNotBlank() }
            val parsedLabel = parts.joinToString(" • ")
            Log.d(TAG, "Generated label from filename: '$parsedLabel'")
            return parsedLabel
        }

        fun generateLabel(sourceName: String, fileName: String, fileSize: String?): String {
            val parsedMeta = parseFilename(fileName)
            val cleanSize = fileSize?.takeIf { it.isNotBlank() } ?: ""

            return buildString {
                append(sourceName)
                if (parsedMeta.isNotBlank()) {
                    append(" • ")
                    append(parsedMeta)
                }
                if (cleanSize.isNotBlank() && !parsedMeta.contains(cleanSize, ignoreCase = true)) {
                    append(" • ")
                    append(cleanSize)
                }
            }
        }
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

                // Decode and enrich labels with our Filename Parser
                val cleanFilename = decodedUrl.split("/").lastOrNull()?.substringBefore("?") ?: label
                val descriptiveName = generateLabel("MediaFire", cleanFilename, null)

                Log.d(TAG, "Extracting link: label='$label', url=$decodedUrl")

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
                        loadExtractor(decodedUrl, referer = unlockUrl, subtitleCallback) { link ->
                            CoroutineScope(Dispatchers.IO).launch {
                                callback(
                                    newExtractorLink(
                                        source = descriptiveName,
                                        name = descriptiveName,
                                        url = link.url,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = unlockUrl
                                        this.quality = link.quality
                                    }
                                )
                            }
                        }
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

                    val rawSourceName = when {
                        decodedUrl.contains("photolinx", ignoreCase = true) || decodedUrl.contains("photon", ignoreCase = true) -> "Photon"
                        decodedUrl.contains("fastilinks", ignoreCase = true) -> "Fastilinks"
                        else -> "TheNextPlanet"
                    }
                    val finalSourceName = generateLabel(rawSourceName, cleanFilename, null)

                    callback(
                        newExtractorLink(
                            source = finalSourceName,
                            name = finalSourceName,
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

    companion object {
        private const val EXT_TAG = "TheNextPlanet:GDFlix"
    }

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

    // Verify Content-Type via fast HEAD or GET requests and skip unplayable pages
    private suspend fun isPlayableStream(url: String): Boolean {
        return try {
            val response = app.head(url, timeout = 3000)
            val contentType = response.headers["Content-Type"] ?: response.headers["content-type"] ?: ""
            Log.d(EXT_TAG, "Verifying content-type for URL ($url): contentType=$contentType")
            
            contentType.contains("video/", ignoreCase = true) || 
            contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) || 
            contentType.contains("application/x-mpegurl", ignoreCase = true) ||
            contentType.contains("application/octet-stream", ignoreCase = true)
        } catch (e: Exception) {
            try {
                val response = app.get(url, timeout = 3000)
                val contentType = response.headers["Content-Type"] ?: response.headers["content-type"] ?: ""
                Log.d(EXT_TAG, "Fallback GET content-type for URL ($url): contentType=$contentType")
                contentType.contains("video/", ignoreCase = true) || 
                contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) || 
                contentType.contains("application/x-mpegurl", ignoreCase = true) ||
                contentType.contains("application/octet-stream", ignoreCase = true)
            } catch (e2: Exception) {
                // Default to true as a safe fail-soft fallback on network timeouts
                true
            }
        }
    }

    // Helper to check content-type and generate enriched descriptive labels before emitting link
    private suspend fun emitLink(
        sourceName: String, 
        rawFilename: String, 
        finalStreamUrl: String, 
        fileSize: String, 
        fileName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isPlayableStream(finalStreamUrl)) {
            val finalLabel = TheNextPlanet.generateLabel(sourceName, rawFilename, fileSize)
            Log.d("GDFlix", "Generated label: '$finalLabel' for URL: $finalStreamUrl")
            callback.invoke(
                newExtractorLink(finalLabel, finalLabel, finalStreamUrl) {
                    this.quality = getIndexQuality(fileName)
                }
            )
        } else {
            Log.w("GDFlix", "Discarded non-playable stream: $finalStreamUrl (returned text/html or failed check)")
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

        val anchorElements = document.select("div.text-center a")
        Log.d("GDFlix", "Found ${anchorElements.size} raw anchor elements inside landing page.")

        // Sort anchor elements according to priority list:
        // 1. PixelDrain, 2. CLOUD DOWNLOAD [R2], 3. GoFile, 4. Instant DL, 5. DIRECT DL
        val sortedAnchors = anchorElements.sortedWith(compareByDescending { anchor ->
            val text = anchor.text()
            when {
                text.contains("PixelDrain", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> 5
                text.contains("CLOUD DOWNLOAD [R2]", ignoreCase = true) -> 4
                text.contains("GoFile", ignoreCase = true) -> 3
                text.contains("Instant DL", ignoreCase = true) -> 2
                text.contains("DIRECT DL", ignoreCase = true) -> 1
                else -> 0
            }
        })

        sortedAnchors.amap { anchor ->
            val text = anchor.text()
            val href = anchor.attr("href")
            Log.d("GDFlix", "Processing sorted anchor: text='$text', href='$href'")

            when {
                text.contains("PixelDrain", ignoreCase = true) || text.contains("Pixel", ignoreCase = true) -> {
                    Log.d("GDFlix", "Found PixelDrain button: text='$text', href='$href'")
                    runCatching {
                        // Bypassing Pixeldrain redirects to get actual file URL
                        val finalPixelUrl = app.get(href, allowRedirects = false).headers["location"] ?: href
                        Log.d("GDFlix", "Resolved PixelDrain URL: $finalPixelUrl")

                        loadExtractor(finalPixelUrl, referer = "", subtitleCallback) { link ->
                            CoroutineScope(Dispatchers.IO).launch {
                                emitLink("PixelDrain", fileName, link.url, fileSize, fileName, callback)
                            }
                        }
                    }.getOrElse {
                        Log.e("GDFlix", "PixelDrain skipped because of error: ${it.message}", it)
                    }
                }

                text.contains("CLOUD DOWNLOAD [R2]", ignoreCase = true) -> {
                    try {
                        val decodedLink = URLDecoder.decode(href.substringAfter("url="), "UTF-8")
                        Log.d("GDFlix", "Found R2 Cloud download: $decodedLink")
                        emitLink("GDFlix [R2 Cloud]", fileName, decodedLink, fileSize, fileName, callback)
                    } catch (e: Exception) {
                        Log.e("GDFlix", "R2 Cloud extraction exception: ${e.message}", e)
                    }
                }

                text.contains("GoFile", ignoreCase = true) -> {
                    try {
                        Log.d("GDFlix", "Following GoFile redirect url: $href")
                        app.get(href).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Log.d("GDFlix", "Invoking Gofile extractor for link: $link")
                                    loadExtractor(link, referer = "", subtitleCallback) { extLink ->
                                        CoroutineScope(Dispatchers.IO).launch {
                                            emitLink("GoFile", fileName, extLink.url, fileSize, fileName, callback)
                                        }
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        Log.e("GDFlix", "Gofile redirect exception: ${e.message}", e)
                    }
                }

                text.contains("Instant DL", ignoreCase = true) -> {
                    try {
                        val link = app.get(href, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        Log.d("GDFlix", "Emitting Instant DL link: $link")
                        emitLink("GDFlix [Instant Download]", fileName, link, fileSize, fileName, callback)
                    } catch (e: Exception) {
                        Log.e("GDFlix", "Instant DL extraction exception: ${e.message}", e)
                    }
                }

                text.contains("DIRECT DL", ignoreCase = true) -> {
                    Log.d("GDFlix", "Emitting Direct DL link: $href")
                    emitLink("GDFlix [Direct]", fileName, href, fileSize, fileName, callback)
                }

                text.contains("DRIVEBOT", ignoreCase = true) -> {
                    try {
                        val id = href.substringAfter("id=").substringBefore("&")
                        val doId = href.substringAfter("do=").substringBefore("==")
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
                                emitLink("GDFlix [DriveBot]", fileName, downloadLink, fileSize, fileName, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GDFlix", "DriveBot extraction exception: ${e.message}", e)
                    }
                }

                else -> {
                    Log.d("GDFlix", "No specific server matched for button text: $text")
                }
            }
        }

        // Cloudflare backup links (kept as a last-resort fallback)
        try {
            val types = listOf("type=1", "type=2")
            types.map { type ->
                val sourceurl = app.get("${newUrl.replace("file", "wfile")}?$type")
                    .document.select("a.btn-success").attr("href")

                if (sourceurl.isNotEmpty()) {
                    Log.d("GDFlix", "Emitting Cloudflare Backup link ($type) fallback: $sourceurl")
                    emitLink("GDFlix [CF]", fileName, sourceurl, fileSize, fileName, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "CF backup extraction exception: ${e.message}", e)
        }
    }
}
