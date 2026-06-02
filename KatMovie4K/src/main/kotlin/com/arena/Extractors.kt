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
                doc.selectFirst("div.vd > center > a")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { a ->
                        val href = a.attr("href")
                        val text = a.text()
                        href.contains("hubcloud.php", ignoreCase = true) ||
                                text.contains("generate direct", ignoreCase = true) ||
                                text.contains("direct download", ignoreCase = true)
                    }?.attr("href")
                    ?: ""
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

            val btns = document.select("h2 a.btn, a.btn, .btn-success, a[href*=download]")
            Log.d("HubCloud", "Found ${btns.size} button candidates")

            btns.amap {
                val btnLink = it.attr("href")
                val text = it.text().lowercase()

                when {
                    text.contains("fsl") -> pushLink(btnLink, "[FSL]")
                    text.contains("mega") -> pushLink(btnLink, "[Mega]")
                    text.contains("buzz") -> {
                        val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                            .headers["hx-redirect"] ?: ""
                        if (dlink.isNotBlank()) {
                            pushLink(getBaseUrl(btnLink) + dlink, "[Buzz]")
                        }
                    }
                    btnLink.contains("pixeldra") -> {
                        val pixelLink = extractPxlUrl(document.toString()) ?: btnLink
                        val pxBase = getBaseUrl(pixelLink)
                        val finalUrl = if (pixelLink.contains("download", true)) pixelLink
                        else "$pxBase/api/file/${pixelLink.substringAfterLast("/")}?download"
                        pushLink(finalUrl, "[Pixeldrain]")
                    }
                    text.contains("10gbps") || text.contains("server : 10") -> {
                        var redirectUrl = resolveFinalUrl(btnLink) ?: btnLink
                        if (redirectUrl.contains("link=")) {
                            redirectUrl = redirectUrl.substringAfter("link=")
                        }
                        pushLink(redirectUrl, "[10Gbps]")
                    }
                    text.contains("download") || text.contains("direct") -> {
                        pushLink(btnLink, "[Direct]")
                    }
                    else -> Log.d("HubCloud", "Skipped button: $text")
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
                .substringAfter("Size : ").substringBefore(" | ").orEmpty()
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

            val anchors = document.select("div.text-center a")
            Log.d("GDFlix", "Found ${anchors.size} GDFlix buttons for $url")

            anchors.amap { anchor ->
                val originalText = anchor.text()
                val text = originalText.lowercase()
                val link = anchor.attr("href")
                val absLink = if (link.startsWith("http")) link else baseUrl + link

                Log.d("GDFlix", "Button: $originalText -> $link")

                when {
                    text.contains("login") || link.contains("/login") -> {
                        Log.d("GDFlix", "Skipping login button")
                    }
                    text.contains("telegram") -> {
                        Log.d("GDFlix", "Skipping Telegram button")
                    }
                    text.contains("instant dl") || text.contains("instant download") -> {
                        try {
                            if (link.startsWith("http")) {
                                pushLink(link, "[Instant 10GBPS]")
                            } else {
                                val instantLink = app.get(absLink, allowRedirects = false)
                                    .headers["location"]?.let { loc ->
                                        if (loc.contains("url=")) loc.substringAfter("url=") else loc
                                    }.orEmpty()
                                if (instantLink.isNotBlank()) pushLink(instantLink, "[Instant 10GBPS]")
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix-Instant", e.toString())
                        }
                    }
                    text.contains("cloud download") || text.contains("[r2]") -> {
                        pushLink(absLink, "[Cloud R2]")
                    }
                    text.contains("direct") -> {
                        pushLink(absLink, "[Direct]")
                    }
                    text.contains("fsl") -> {
                        pushLink(absLink, "[FSL V2]")
                    }
                    text.contains("fast cloud") || text.contains("zipdisk") -> {
                        try {
                            val dlink = app.get(absLink)
                                .document
                                .select("div.card-body a, a.btn-success, a.btn")
                                .firstOrNull()?.attr("href").orEmpty()
                            if (dlink.isNotBlank()) {
                                val finalDlink = if (dlink.startsWith("http")) dlink else baseUrl + dlink
                                pushLink(finalDlink, "[Fast Cloud]")
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix-FastCloud", e.toString())
                        }
                    }
                    text.contains("pixeldrain") || link.contains("pixeldra") -> {
                        val pxBase = getBaseUrl(absLink)
                        val finalUrl = when {
                            absLink.contains("/u/") -> {
                                val id = absLink.substringAfterLast("/u/").substringBefore("?")
                                "$pxBase/api/file/$id?download"
                            }
                            absLink.contains("download", true) -> absLink
                            else -> "$pxBase/api/file/${absLink.substringAfterLast("/")}?download"
                        }
                        pushLink(finalUrl, "[Pixeldrain]")
                    }
                    text.contains("gofile") -> {
                        try {
                            app.get(absLink).document.select("a[href*=gofile.io]").amap { goAnchor ->
                                val goLink = goAnchor.attr("href")
                                if (goLink.contains("gofile")) {
                                    loadExtractor(goLink, "", subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix-GoFile", e.toString())
                        }
                    }
                    else -> {
                        if (link.startsWith("http") && !link.contains("/login")) {
                            pushLink(absLink, "[${originalText.take(15)}]")
                        } else {
                            Log.d("GDFlix", "Skipped unknown button: $originalText")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Failed for $url: ${e.message}")
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

// ====================================================================
// KatMovie4K-specific extractor variants.
//
// These hosts appear on katmovie4k.mov pages and need registered
// ExtractorApi subclasses so CloudStream's prefix-based router can
// dispatch correctly.
//
// Redirect chains (verified live 2026-06-02):
//   ziddiflix.com/*/file/<id>     302→ gdflix.dev/file/<id> 302→ new18.gdflix.net/file/<id>
//   new3.gdflix.dad/file/<id>    302→ gdlink.dev/file/<id>  302→ gdflix.dev → new18.gdflix.net
//   new4.gdflix.dad/file/<id>    302→ same chain
//   vifix.site/gdflix/<id>        JS challenge → ww1.vifix.site (ad-gate PARKED domain)
//   driveleech.org/file/<id>      intermittently down (empty response)
// ====================================================================

/** new3.gdflix.dad → 302 to gdlink.dev/file/<id>. */
class GDFlixDad3 : GDFlix() {
    override val mainUrl = "https://new3.gdflix.dad"
}

/** new4.gdflix.dad → same redirect chain. */
class GDFlixDad4 : GDFlix() {
    override val mainUrl = "https://new4.gdflix.dad"
}

/** gdlink.dev — post-redirect target of new3/new4.gdflix.dad. */
class GDLinkDev : GDFlix() {
    override val mainUrl = "https://gdlink.dev"
}

/** ziddiflix.com — kmhd's 4K-only redirector. */
class Ziddiflix : GDFlix() {
    override val mainUrl = "https://ziddiflix.com"
}

/**
 * vifix.site — v3 FIX (2026-06-02).
 *
 * This host serves a JavaScript challenge page that redirects to
 * ww1.vifix.site, a PARKED ad-gate domain. The base GDFlix.getUrl()
 * never reaches a real file page, causing "no link found" on every
 * 4K mirror that uses vifix.site (which is most of them).
 *
 * Fix: Extract the gdflix file id from the vifix URL (/gdflix/<id>)
 * and route directly to new18.gdflix.net/file/<id>, bypassing the
 * JavaScript gate entirely. Verified against Daredevil: Born Again
 * S01 — the same file id yields a working GDFlix page with real
 * Instant DL / Fast Cloud buttons.
 */
class Vifix : GDFlix() {
    override val mainUrl = "https://vifix.site"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extract the gdflix file id from /gdflix/<id>
            val fileId = Regex("""/gdflix/([a-zA-Z0-9]+)""")
                .find(url)?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                val gdflixUrl = "https://new18.gdflix.net/file/$fileId"
                Log.d(name, "Vifix v3: routing $url → $gdflixUrl")
                GDFlixNet().getUrl(gdflixUrl, referer, subtitleCallback, callback)
                return
            }
            // Fallback: try as-is through base GDFlix parser
            Log.w(name, "Vifix: unexpected URL format, fallback: $url")
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(name, "Vifix v3 failed for $url: ${e.message}")
        }
    }
}

/**
 * driveleech.org — Driveseed-family file host for HDR/DV mirrors.
 * As of 2026-06-02 this host is intermittently unreachable (empty
 * response), causing base GDFlix parser to fail silently.
 *
 * Fix: Try the original URL first; if the response is empty/short,
 * extract the file id and fall back to new18.gdflix.net. This is a
 * best-effort recovery — the file ids are often the same across
 * gdflix mirrors.
 */
class Driveleech : GDFlix() {
    override val mainUrl = "https://driveleech.org"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Try the original driveleech URL first
            val response = app.get(url, timeout = 15)
            val body = response.text
            if (body.isBlank() || body.length < 100) {
                Log.w(name, "Driveleech: empty/short response (${body.length} bytes), server may be down")

                // Attempt fallback: extract file id and try new18.gdflix.net
                val fileId = Regex("""/file/([a-zA-Z0-9]+)""")
                    .find(url)?.groupValues?.get(1)
                if (!fileId.isNullOrBlank()) {
                    val gdflixUrl = "https://new18.gdflix.net/file/$fileId"
                    Log.d(name, "Driveleech fallback: $url → $gdflixUrl")
                    GDFlixNet().getUrl(gdflixUrl, referer, subtitleCallback, callback)
                    return
                }
            }
            // If we got a real response, parse with base GDFlix logic
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(name, "Driveleech failed: ${e.message}")
            // Try fallback on any error
            val fileId = Regex("""/file/([a-zA-Z0-9]+)""")
                .find(url)?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                try {
                    val gdflixUrl = "https://new18.gdflix.net/file/$fileId"
                    Log.d(name, "Driveleech error fallback: $url → $gdflixUrl")
                    GDFlixNet().getUrl(gdflixUrl, referer, subtitleCallback, callback)
                } catch (_: Exception) { }
            }
        }
    }
}

/** driveleech.pro mirror — delegates to Driveleech fallback logic. */
class DriveleechPro : GDFlix() {
    override val mainUrl = "https://driveleech.pro"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Driveleech().getUrl(url, referer, subtitleCallback, callback)
    }
}

/** driveleech.net mirror — delegates to Driveleech fallback logic. */
class DriveleechNet : GDFlix() {
    override val mainUrl = "https://driveleech.net"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Driveleech().getUrl(url, referer, subtitleCallback, callback)
    }
}
