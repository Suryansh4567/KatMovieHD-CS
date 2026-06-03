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
import java.net.URLDecoder

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
            } else return null
            loopCount++
        } catch (e: Exception) { return null }
    }
    return currentUrl
}

fun getBaseUrl(url: String): String {
    return try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when { lowerStr.contains("8k") -> 4320; lowerStr.contains("4k") -> 2160; lowerStr.contains("2k") -> 1440; else -> Qualities.Unknown.value }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false
    fun extractPxlUrl(html: String): String? = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = app.get(url).document
            var link = if (url.contains("/video/")) { doc.selectFirst("div.vd > center > a")?.attr("href") ?: doc.select("a[href]").firstOrNull { a -> val h = a.attr("href"); val t = a.text(); h.contains("hubcloud.php", true) || t.contains("generate direct", true) || t.contains("direct download", true) }?.attr("href") ?: "" } else { Regex("var url = '([^']*)'").find(doc.selectFirst("script:containsData(url)")?.toString() ?: "")?.groupValues?.get(1) ?: "" }
            if (link.isBlank()) return
            if (!link.startsWith("https://")) link = baseUrl + link
            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)
            suspend fun push(dl: String, s: String = "") { callback.invoke(newExtractorLink("$name$s", "$name$s $header[$size]", dl, ExtractorLinkType.VIDEO) { this.quality = quality }) }
            document.select("a.btn, .btn-success, a[href*=download]").amap {
                val btnLink = it.attr("href")
                val text = it.text().lowercase()
                when {
                    text.contains("fsl") -> push(btnLink, "[FSL]")
                    text.contains("mega") -> push(btnLink, "[Mega]")
                    text.contains("buzz") -> app.get("$btnLink/download", referer = btnLink, allowRedirects = false).headers["hx-redirect"]?.let { d -> push(getBaseUrl(btnLink) + d, "[Buzz]") }
                    btnLink.contains("pixeldra") -> push(extractPxlUrl(document.toString())?.let { p -> if (p.contains("download", true)) p else "${getBaseUrl(p)}/api/file/${p.substringAfterLast("/")}?download" } ?: btnLink, "[Pixeldrain]")
                    text.contains("10gbps") || text.contains("server : 10") -> resolveFinalUrl(btnLink)?.let { r -> push(if (r.contains("link=")) r.substringAfter("link=") else r, "[10Gbps]") }
                    text.contains("download") || text.contains("direct") -> push(btnLink, "[Direct]")
                }
            }
        } catch (e: Exception) { Log.e("HubCloud", "Failed: ${e.message}") }
    }
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, referer = referer)
            val document = response.document
            val baseUrl = getBaseUrl(response.url).ifBlank { "https://new18.gdflix.net" }
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").substringBefore(" | ")
            val quality = getIndexQuality(fileName)
            suspend fun push(l: String, s: String = "") { callback.invoke(newExtractorLink("$name$s", "$name$s $fileName[$fileSize]", l, ExtractorLinkType.VIDEO) { this.quality = quality }) }
            document.select("a.btn, .btn, a[href*='/file/'], a[href*='busycdn'], a[href*='zfile'], a[href*='goflix'], a[href*='mirror']").amap { anchor ->
                val originalText = anchor.text(); val text = originalText.lowercase(); val link = anchor.attr("href"); val absLink = if (link.startsWith("http")) link else baseUrl + link
                when {
                    text.contains("login") || text.contains("telegram") -> {}
                    text.contains("instant dl") || text.contains("instant download") -> {
                        try {
                            if (link.contains("busycdn.xyz") || link.contains("instant.")) {
                                val loc = app.get(absLink, referer = baseUrl, allowRedirects = false, timeout = 15).headers["Location"].orEmpty()
                                val cdn = Regex("""[?&]url=([^&]+)""").find(loc)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                                val f = when { !cdn.isNullOrBlank() && cdn.startsWith("http") -> cdn; loc.startsWith("http") && !loc.contains("busycdn", true) -> loc; else -> null }
                                if (!f.isNullOrBlank()) push(f, "[Instant 10GBPS]")
                            } else if (absLink.startsWith("http")) push(absLink, "[Instant DL]")
                        } catch (e: Exception) {}
                    }
                    text.contains("cloud download") || text.contains("[r2]") -> push(absLink, "[Cloud R2]")
                    text.contains("direct") -> push(absLink, "[Direct]")
                    text.contains("fsl") -> push(absLink, "[FSL V2]")
                    text.contains("fast cloud") || text.contains("zipdisk") -> try { val d = app.get(absLink, referer = baseUrl).document.select("a[href]").map { it.attr("href") }.firstOrNull { h -> Regex("""(?i)(workers\.dev|awscdn|googleusercontent|busycdn|\.mkv|\.mp4|/download)""").containsMatchIn(h) }.orEmpty(); if (d.isNotBlank()) push(if (d.startsWith("http")) d else baseUrl + d, "[Fast Cloud]") } catch (e: Exception) {}
                    text.contains("pixeldrain") || link.contains("pixeldra") -> push(when { absLink.contains("/u/") -> "${getBaseUrl(absLink)}/api/file/${absLink.substringAfterLast("/u/").substringBefore("?")}?download"; absLink.contains("download", true) -> absLink; else -> "${getBaseUrl(absLink)}/api/file/${absLink.substringAfterLast("/")}?download" }, "[Pixeldrain]")
                    text.contains("gofile") || link.contains("multiup") || link.contains("mirror") -> try { val h = app.get(absLink, referer = baseUrl).text; Regex("""https?://(?:gofile\.io/d/|validate\.multiup2\.workers\.dev/)[A-Za-z0-9]+""").findAll(h).map { it.value }.distinct().forEach { g -> if (g.contains("multiup2")) resolveFinalUrl(g)?.let { f -> loadExtractor(f, absLink, subtitleCallback, callback) } else loadExtractor(g, absLink, subtitleCallback, callback) } } catch (e: Exception) {}
                    else -> if (link.startsWith("http")) push(absLink, "[${originalText.take(15)}]")
                }
            }
        } catch (e: Exception) { Log.e("GDFlix", "Failed for $url: ${e.message}") }
    }
}

class GDFlixNet : GDFlix() { override val mainUrl = "https://new18.gdflix.net" }
class GDFlixNew1 : GDFlix() { override val mainUrl = "https://new1.gdflix.dev" }
class GDFlixNew17 : GDFlix() { override val mainUrl = "https://new17.gdflix.net" }
class GDFlixDotDev : GDFlix() { override val mainUrl = "https://gdflix.dev" }
class GDFlixDad : GDFlix() { override val mainUrl = "https://new.gdflix.dad" }
class GDFlixDad3 : GDFlix() { override val mainUrl = "https://new3.gdflix.dad" }
class GDFlixDad4 : GDFlix() { override val mainUrl = "https://new4.gdflix.dad" }
class GDFlixRest : GDFlix() { override val mainUrl = "https://gdflix.rest" }
class GDFlixCfd5 : GDFlix() { override val mainUrl = "https://new5.gdflix.cfd" }
class GDTotCfd : GDFlix() { override val mainUrl = "https://new10.gdtot.cfd" }
class GDLinkDev : GDFlix() { override val mainUrl = "https://gdlink.dev" }
class Ziddiflix : GDFlix() { override val mainUrl = "https://ziddiflix.com" }
class Appdrive : GDFlix() { override val mainUrl = "https://appdrive.lol" }

class Vifix : GDFlix() {
    override val mainUrl = "https://vifix.site"
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/gdflix/([a-zA-Z0-9_]+)""").find(url)?.groupValues?.get(1)
        if (!id.isNullOrBlank()) GDFlix().getUrl("https://gdflix.dev/file/$id", referer ?: url, subtitleCallback, callback)
        else super.getUrl(url, referer, subtitleCallback, callback)
    }
}

class Driveleech : GDFlix() {
    override val mainUrl = "https://driveleech.org"
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { try { if (app.get(url, timeout = 15).text.length > 200) super.getUrl(url, referer, subtitleCallback, callback) } catch (e: Exception) {} }
}
class DriveleechPro : GDFlix() { override val mainUrl = "https://driveleech.pro"; override suspend fun getUrl(u: String, r: String?, s: (SubtitleFile) -> Unit, c: (ExtractorLink) -> Unit) { Driveleech().getUrl(u, r, s, c) } }
class DriveleechNet : GDFlix() { override val mainUrl = "https://driveleech.net"; override suspend fun getUrl(u: String, r: String?, s: (SubtitleFile) -> Unit, c: (ExtractorLink) -> Unit) { Driveleech().getUrl(u, r, s, c) } }
