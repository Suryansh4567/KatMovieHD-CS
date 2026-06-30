package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLDecoder

private const val EXTRACTOR_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val EXTRACTOR_HEADERS = mapOf("User-Agent" to EXTRACTOR_UA, "Accept-Language" to "en-US,en;q=0.9")

private fun absolutize(baseUrl: String, link: String): String = when {
    link.startsWith("http", ignoreCase = true) -> link
    link.startsWith("//") -> "https:$link"
    link.startsWith("/") -> baseUrl.trimEnd('/') + link
    else -> baseUrl.trimEnd('/') + "/" + link
}

private fun isCfBlock(doc: Document): Boolean {
    val title = doc.selectFirst("title")?.text()?.lowercase().orEmpty()
    val html = doc.html().take(20000).lowercase()
    return title.contains("just a moment") || title.contains("attention required") || title.contains("cloudflare") || title.contains("verify you are human") || html.contains("cf-chl") || html.contains("ray id") || html.contains("challenge-running")
}

private suspend fun getDocumentFutureProof(url: String, referer: String? = null): Document {
    var lastError: Exception? = null
    for (i in 0..1) { // 1 retry
        try {
            val direct = runCatching { app.get(url, headers = EXTRACTOR_HEADERS, referer = referer ?: "", timeout = 20).document }.getOrNull()
            if (direct != null && !isCfBlock(direct)) return direct
            return app.get(url, headers = EXTRACTOR_HEADERS, referer = referer ?: "", interceptor = CloudflareKiller(), timeout = 25).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastError = e
        }
    }
    throw lastError ?: Exception("Failed to fetch document")
}

suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 8
    while (loopCount < maxRedirects) {
        try {
            // Use GET instead of HEAD — many CDN redirectors (gd.kmhd.eu,
            // gdflix.dad, gdlink.dev) return 405/empty on HEAD. GET with
            // allowRedirects=false lets us trace the chain manually.
            val res = app.get(currentUrl, headers = EXTRACTOR_HEADERS, allowRedirects = false, timeout = 10)
            val code = res.code
            if (code == 200) break          // final destination reached
            if (code in 300..399) {
                val location = res.headers["Location"]
                if (location.isNullOrEmpty()) break
                currentUrl = when {
                    location.startsWith("http", ignoreCase = true) -> location
                    location.startsWith("//") -> "https:$location"
                    location.startsWith("/") -> getBaseUrl(currentUrl) + location
                    else -> getBaseUrl(currentUrl) + "/" + location
                }
            } else break                    // non-redirect, non-200 — stop
            loopCount++
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            break 
        }
    }
    return currentUrl
}

fun getBaseUrl(url: String): String = try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private fun isDirectVideoUrlForExtractor(url: String): Boolean {
    val clean = url.substringBefore('?').lowercase()
    return clean.endsWith(".mp4") || clean.endsWith(".mkv") ||
        clean.endsWith(".webm") || clean.endsWith(".avi") ||
        clean.endsWith(".mov") || clean.endsWith(".m3u8") ||
        url.contains("googleusercontent", ignoreCase = true) ||
        url.contains("busycdn", ignoreCase = true) ||
        url.contains("workers.dev", ignoreCase = true)
}

private fun extractQueryParameter(url: String, key: String): String? {
    val query = url.substringAfter('?', "")
    if (query.isBlank()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val name = part.substringBefore('=')
        if (name == key) URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") else null
    }
}

private suspend fun resolveBusyCdnDirectUrl(url: String): String? {
    val res = app.get(url, headers = EXTRACTOR_HEADERS, referer = "https://new1.gdflix.io/", allowRedirects = false, timeout = 12)
    val location = res.headers["Location"] ?: res.headers["location"] ?: return null
    val target = when {
        location.startsWith("http", ignoreCase = true) -> location
        location.startsWith("//") -> "https:$location"
        location.startsWith("/") -> getBaseUrl(url) + location
        else -> getBaseUrl(url) + "/" + location
    }
    return extractQueryParameter(target, "url") ?: target.takeIf { isDirectVideoUrlForExtractor(it) }
}

// HubCloud
open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val baseUrl = getBaseUrl(url)
            val doc = getDocumentFutureProof(url, referer)
            var link = if (url.contains("/video/")) {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            } else {
                val scriptTag = doc.select("script").mapNotNull { it.data().takeIf { d -> d.contains("var url = '") } }.firstOrNull() ?: ""
                var parsedLink = Regex("""var url = '([^']*)'""").find(scriptTag)?.groupValues?.get(1) ?: ""
                if (parsedLink.isBlank()) {
                    parsedLink = doc.selectFirst("a#download, a[href*='gamerxyt.com'], a[href*='hubcloud.php']")?.attr("href") ?: ""
                }
                parsedLink
            }
            if (link.isBlank()) link = doc.selectFirst("a[href*='download'], a.btn-success, a.btn-primary")?.attr("href") ?: ""
            if (link.isBlank()) return
            if (!link.startsWith("http")) link = absolutize(baseUrl, link)
            val document = getDocumentFutureProof(link, url)
            val header = document.select("div.card-header, title").text()
            val size = document.select("i#size, li.list-group-item:contains(Size)").text().substringAfter("Size : ").substringBefore(" | ")
            val quality = getIndexQuality(header)
            val btns = document.select("h2 a.btn, a.btn, .btn-success, a[href*=download], a#fsl, a[id^=pxl]")
            btns.amap {
                val btnLink = it.attr("href")
                val text = it.text().lowercase()
                when {
                    text.contains("fsl") -> callback.invoke(newExtractorLink("$name[FSL]", "$name[FSL] $header[$size]", btnLink) { this.quality = quality })
                    text.contains("pixel") -> callback.invoke(newExtractorLink("$name[Pixel]", "$name[Pixel] $header[$size]", btnLink) { this.quality = quality })
                    text.contains("buzz") -> callback.invoke(newExtractorLink("$name[Buzz]", "$name[Buzz] $header[$size]", btnLink) { this.quality = quality })
                    text.contains("mega") -> callback.invoke(newExtractorLink("$name[Mega]", "$name[Mega] $header[$size]", btnLink) { this.quality = quality })
                    text.contains("download") || text.contains("direct") -> callback.invoke(newExtractorLink("$name[Direct]", "$name[Direct] $header[$size]", btnLink) { this.quality = quality })
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("HubCloud", e.toString()) }
    }
}

// GDFlix Family
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val baseUrl = getBaseUrl(url)
            val document = getDocumentFutureProof(url, referer)
            val fileName = document.select("ul > li.list-group-item:contains(Name), meta[property=og:description], title")
                .text().substringAfter("Download ").substringBefore(" - ").substringAfter("Name : ").orEmpty()
            val fileSize = document.select("ul > li.list-group-item:contains(Size), meta[property=og:description]")
                .text().substringAfter("Size : ").substringBefore(" | ").ifBlank {
                    document.selectFirst("meta[property=og:description]")?.attr("content")?.substringAfterLast(" - ")?.orEmpty() ?: ""
                }
            val quality = getIndexQuality(fileName)
            val anchors = document.select("div.text-center a, a.btn, a[href], a[href*=download]")
                .filter { a ->
                    val href = a.attr("href")
                    val text = a.text().lowercase()
                    href.startsWith("http") || href.startsWith("/") ||
                        text.contains("instant") || text.contains("direct") || text.contains("cloud") || text.contains("zipdisk")
                }
            anchors.amap { anchor ->
                val text = anchor.text().lowercase()
                val link = anchor.attr("href")
                val absLink = absolutize(baseUrl, link)
                when {
                    // These GDFlix buttons are download-container pages, not video containers.
                    // CloudStream's player reports ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)
                    // if it receives them as plain ExtractorLink URLs. Do not emit them directly.
                    // Keep the external dispatch for GoFile/MultiUp below, and prefer the real
                    // Instant direct CDN URL when GDFlix exposes it in the page.
                    text.contains("instant") || absLink.contains("instant.") || absLink.contains("busycdn") -> {
                        if (absLink.contains("busycdn", ignoreCase = true)) {
                            val direct = runCatching { resolveBusyCdnDirectUrl(absLink) }.getOrNull()
                            val playable = direct?.takeIf { isDirectVideoUrlForExtractor(it) }
                            if (!playable.isNullOrBlank()) {
                                callback.invoke(newExtractorLink("$name[Instant]", "$name[Instant] $fileName[$fileSize]", playable) {
                                    this.quality = quality
                                })
                            }
                        }
                    }
                    text.contains("direct") && isDirectVideoUrlForExtractor(absLink) -> callback.invoke(newExtractorLink("$name[Direct]", "$name[Direct] $fileName[$fileSize]", absLink) { this.quality = quality })
                    text.contains("fsl") && isDirectVideoUrlForExtractor(absLink) -> callback.invoke(newExtractorLink("$name[FSL]", "$name[FSL] $fileName[$fileSize]", absLink) { this.quality = quality })
                    text.contains("zipdisk") || absLink.contains("/zfile/") -> {
                        try {
                            val zipDoc = getDocumentFutureProof(absLink, url)
                            val btn = zipDoc.selectFirst("a[href*=workers.dev], a[href*=download], a[href*=cdn], a.btn-success, a.btn-primary")?.attr("href")
                            val direct = btn?.let { absolutize(getBaseUrl(absLink), it) }
                            if (!direct.isNullOrBlank() && isDirectVideoUrlForExtractor(direct)) {
                                callback.invoke(newExtractorLink("$name[ZipDisk]", "$name[ZipDisk] $fileName[$fileSize]", direct) { this.quality = quality })
                            }
                        } catch(e: Exception) { Log.e("GDFlix", "ZipDisk error: ${e.message}") }
                    }
                    text.contains("gofile") || text.contains("goflix") || absLink.contains("goflix.sbs") -> loadExtractor(absLink, baseUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("GDFlix", e.toString()) }
    }
}

class GDFlixNet : GDFlix() { override val mainUrl = "https://gdflix.net" }
class GDFlixNew1 : GDFlix() { override val mainUrl = "https://new1.gdflix.net" }
class GDFlixNew17 : GDFlix() { override val mainUrl = "https://new17.gdflix.net" }
class GDFlixNew18 : GDFlix() { override val mainUrl = "https://new18.gdflix.net" }
class GDFlixDotDev : GDFlix() { override val mainUrl = "https://gdflix.dev" }
class GDFlixNewIO : GDFlix() { override val mainUrl = "https://new.gdflix.io" }
class GDFlixDad : GDFlix() { override val mainUrl = "https://new.gdflix.dad" }
class GDLinkDev : GDFlix() { override val mainUrl = "https://gdlink.dev" }

// Filepress
class Filepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.store"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val link = doc.selectFirst("a[href*=download], a.btn-success")?.attr("href")
            if (!link.isNullOrBlank()) callback.invoke(newExtractorLink(name, name, if (link.startsWith("http")) link else absolutize(getBaseUrl(url), link)))
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("Filepress", e.toString()) }
    }
}

// DriveSeed
open class DriveSeed : ExtractorApi() {
    override val name = "DriveSeed"
    override val mainUrl = "https://driveseed.org"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val dlLink = doc.selectFirst("a[href*=download], a.btn")?.attr("href")
            if (!dlLink.isNullOrBlank()) callback.invoke(newExtractorLink(name, name, if (dlLink.startsWith("http")) dlLink else absolutize(getBaseUrl(url), dlLink)))
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("DriveSeed", e.toString()) }
    }
}

class DriveLeech : DriveSeed() { override val mainUrl = "https://driveleech.org" }

// HGLINK (Updated)
class HGLINK : ExtractorApi() {
    override val name = "HGLINK"
    override val mainUrl = "https://hglink.to"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val link = doc.selectFirst("a[href*=download], a[href*=file], a[href*=e/]")?.attr("href")
            if (!link.isNullOrBlank()) callback.invoke(newExtractorLink(name, name, if (link.startsWith("http")) link else absolutize(getBaseUrl(url), link)))
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("HGLINK", e.toString()) }
    }
}

class FuckingFast : ExtractorApi() {
    override val name = "FuckingFast"
    override val mainUrl = "https://fuckingfast.net"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val link = doc.selectFirst("a[href*=download]")?.attr("href")
            if (!link.isNullOrBlank()) callback.invoke(newExtractorLink(name, name, if (link.startsWith("http")) link else absolutize(getBaseUrl(url), link)))
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("FuckingFast", e.toString()) }
    }
}

// BBUpload (download.bbupload.to) — reverse-engineered AJAX API.
// URL pattern: https://download.bbupload.to/download?v=SLUG
// API:         GET /ajax.php?action=getdownload&data=SLUG
//   → {"status":"ok","data":{"downloadUrl":"https://...","FileName":"...","filesize":"..."}}
// The downloadUrl is a direct streamable link (supports Range).
class BBUpload : ExtractorApi() {
    override val name = "BBUpload"
    override val mainUrl = "https://download.bbupload.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            // Extract the slug from ?v=SLUG
            val slug = url.substringAfter("v=")
                .substringBefore("&")
                .substringBefore("#")
                .trim()
            if (slug.isBlank()) return

            val dynamicBase = getBaseUrl(url)
            val apiUrl = "$dynamicBase/ajax.php?action=getdownload&data=$slug"
            val response = app.get(
                apiUrl,
                headers = EXTRACTOR_HEADERS + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = url,
                timeout = 15
            ).text
            if (response.isBlank()) return

            val json = JSONObject(response)
            if (json.optString("status") != "ok") return
            val data = json.optJSONObject("data") ?: return

            val downloadUrl = data.optString("downloadUrl").takeIf { it.isNotBlank() } ?: return
            val fileName = data.optString("FileName").ifBlank { data.optString("filename") }
            val fileSize = data.optString("filesize")
            val quality = getIndexQuality(fileName)

            callback.invoke(
                newExtractorLink(name, "$name $fileName[$fileSize]", downloadUrl) {
                    this.quality = quality
                }
            )
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("BBUpload", e.toString()) }
    }
}

// Legacy BBServer — kept for backward compat with older posts.
class BBServer : ExtractorApi() {
    override val name = "BBServer"
    override val mainUrl = "https://bbserver.in"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val link = doc.selectFirst("a[href*=download], a.btn")?.attr("href")
            if (!link.isNullOrBlank()) callback.invoke(newExtractorLink(name, name, link))
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("BBServer", e.toString()) }
    }
}

class GDTot : ExtractorApi() {
    override val name = "GDTot"
    override val mainUrl = "https://gdtot.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val link = doc.selectFirst("a[href*=gdflix], a[href*=download]")?.attr("href")
            if (!link.isNullOrBlank()) GDFlix().getUrl(link, url, subtitleCallback, callback)
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("GDTot", e.toString()) }
    }
}

// KatDriveClick
class KatDriveClick : ExtractorApi() {
    override val name = "KatDrive"
    override val mainUrl = "https://katdrive.click"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = getDocumentFutureProof(url, referer)
            val hubUrl = doc.select("a[href]").mapNotNull { a -> a.attr("href").takeIf { it.contains(Regex("""hubcloud|hubdrive|hubcdn""", RegexOption.IGNORE_CASE)) } }.firstOrNull()
            if (!hubUrl.isNullOrBlank()) {
                val finalHub = if (hubUrl.startsWith("http")) hubUrl else absolutize(getBaseUrl(url), hubUrl)
                HubCloud().getUrl(finalHub, url, subtitleCallback, callback)
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; Log.e("KatDriveClick", e.toString()) }
    }
}
