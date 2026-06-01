package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 7

    while (loopCount < maxRedirects) {
        try {
            val res = app.head(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code == 200 || res.code in 300..399) {
                val location = res.headers["Location"]
                if (location.isNullOrEmpty()) break
                currentUrl = location
            } else {
                return null
            }
            loopCount++
        } catch (e: Exception) {
            return null
        }
    }
    return currentUrl
}

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value

    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }

    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = app.get(url).document

            var link = if (url.contains("/video/")) {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            } else {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            }

            if (link.isBlank()) {
                Log.w("HubCloud", "No download link found on $url")
                return
            }
            if (!link.startsWith("https://")) link = baseUrl + link

            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            suspend fun pushLink(downloadLink: String, server: String = "") {
                callback.invoke(
                    newExtractorLink(
                        "$name$server",
                        "$name$server $header[$size]",
                        downloadLink,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
            }

            document.select("h2 a.btn").amap {
                val btnLink = it.attr("href")
                val text = it.text()

                when {
                    text.contains("FSL Server") -> pushLink(btnLink, "[FSL Server]")
                    text.contains("FSLv2") -> pushLink(btnLink, "[FSLv2 Server]")
                    text.contains("Mega Server") -> pushLink(btnLink, "[Mega Server]")
                    text.contains("Download File") -> pushLink(btnLink)
                    text.contains("BuzzServer") -> {
                        val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                            .headers["hx-redirect"] ?: ""
                        if (dlink.isNotBlank()) {
                            pushLink(getBaseUrl(btnLink) + dlink, "[BuzzServer]")
                        }
                    }
                    btnLink.contains("pixeldra") -> {
                        val pixelLink = extractPxlUrl(document.toString()) ?: return@amap
                        val pxBase = getBaseUrl(pixelLink)
                        val finalUrl = if (pixelLink.contains("download", true)) pixelLink
                        else "$pxBase/api/file/${pixelLink.substringAfterLast("/")}?download"
                        pushLink(finalUrl, "[Pixeldrain]")
                    }
                    text.contains("Server : 10Gbps") -> {
                        var redirectUrl = resolveFinalUrl(btnLink) ?: return@amap
                        if (redirectUrl.contains("link=")) {
                            redirectUrl = redirectUrl.substringAfter("link=")
                        }
                        pushLink(redirectUrl, "[10Gbps]")
                    }
                    else -> Log.d("HubCloud", "No matching server for: $text")
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Failed: ${e.message}")
        }
    }
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    private suspend fun cfType(url: String): List<String> {
        val types = listOf("1", "2")
        val downloadLinks = mutableListOf<String>()

        types.amap { t ->
            try {
                val document = app.get("$url?type=$t").document
                val links = document.select("a.btn-success").mapNotNull { it.attr("href") }
                downloadLinks.addAll(links)
            } catch (e: Exception) {
                Log.d("GDFlix", "CFType err: $e")
            }
        }
        return downloadLinks
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val baseUrl = getBaseUrl(url)
            val document = app.get(url).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").orEmpty()
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").orEmpty()
            val quality = getIndexQuality(fileName)

            suspend fun pushLink(link: String, server: String = "") {
                callback.invoke(
                    newExtractorLink(
                        "$name$server",
                        "$name$server $fileName[$fileSize]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
            }

            document.select("div.text-center a").amap { anchor ->
                val text = anchor.text()
                val link = anchor.attr("href")

                when {
                    text.contains("FSL V2") -> pushLink(link, "[FSL V2]")
                    text.contains("DIRECT DL") -> pushLink(link, "[Direct]")
                    text.contains("DIRECT SERVER") -> pushLink(link, "[Direct]")
                    text.contains("CLOUD DOWNLOAD [R2]") -> pushLink(link, "[Cloud]")
                    text.contains("FAST CLOUD") -> {
                        val dlink = app.get(baseUrl + link)
                            .document
                            .select("div.card-body a")
                            .attr("href")
                        if (dlink.isNotBlank()) pushLink(dlink, "[FAST CLOUD]")
                    }
                    link.contains("pixeldra") -> {
                        val pxBase = getBaseUrl(link)
                        val finalUrl = if (link.contains("download", true)) link
                        else "$pxBase/api/file/${link.substringAfterLast("/")}?download"
                        pushLink(finalUrl, "[Pixeldrain]")
                    }
                    text.contains("Instant DL") -> {
                        try {
                            val instantLink = app.get(link, allowRedirects = false)
                                .headers["location"]?.substringAfter("url=").orEmpty()
                            if (instantLink.isNotBlank()) pushLink(instantLink, "[Instant]")
                        } catch (e: Exception) {
                            Log.d("GDFlix-Instant", e.toString())
                        }
                    }
                    text.contains("GoFile") -> {
                        try {
                            app.get(link).document.select(".row .row a").amap { goAnchor ->
                                val goLink = goAnchor.attr("href")
                                if (goLink.contains("gofile")) {
                                    loadExtractor(goLink, "", subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix-GoFile", e.toString())
                        }
                    }
                    else -> Log.d("GDFlix", "No matching server: $text")
                }
            }

            try {
                val sources = cfType(url.replace("file", "wfile"))
                sources.amap { source ->
                    val redirectUrl = resolveFinalUrl(source) ?: return@amap
                    pushLink(redirectUrl, "[CF]")
                }
            } catch (e: Exception) {
                Log.d("GDFlix-CF", e.toString())
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Failed: ${e.message}")
        }
    }
}

class GDFlixNet : GDFlix() {
    override val mainUrl = "https://new18.gdflix.net"
}

class GDFlixNew1 : GDFlix() {
    override val mainUrl = "https://new1.gdflix.dev"
}

class GDFlixNew17 : GDFlix() {
    override val mainUrl = "https://new17.gdflix.net"
}

class GDFlixDotDev : GDFlix() {
    override val mainUrl = "https://gdflix.dev"
}
