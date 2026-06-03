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
            // CRITICAL (v4 fix): GDFlix rotates its canonical host (new1 →
            // new17 → new18 → …). Hard-coding "new18.gdflix.net" breaks the
            // day it bumps to new19, and is also wrong for the redirector
            // chains (ziddiflix → gdflix.dev → newNN.gdflix.net).  Instead we
            // derive the base from the FINAL resolved URL after redirects, so
            // relative buttons like /zfile/<id> always resolve to whatever host
            // actually served the page. Falls back to new18 only if parsing
            // somehow yields nothing.
            val response = app.get(url, referer = referer)
            val document = response.document
            val baseUrl = getBaseUrl(response.url).ifBlank { "https://new18.gdflix.net" }
            
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").orEmpty()
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").substringBefore(" | ").orEmpty()
            val quality = getIndexQuality(fileName)

            // Warn if page is login-walled (no useful buttons)
            val btnCount = document.select("div.text-center a.btn").size
            if (btnCount <= 1) {
                Log.w(name, "Page may be login-walled ($btnCount btn(s) found). Trying anyway...")
            }

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
            Log.d("GDFlix", "Found ${anchors.size} anchors for $url")

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
                    // INSTANT DL: busycdn.xyz → 302 → fastcdn-dl/?url=<CDN>
                    //
                    // v4 fix: the OLD code pushed `absLink` (the bare
                    // instant.busycdn.xyz token URL) as a VIDEO link whenever it
                    // could not parse a redirect. That token endpoint frequently
                    // answers HTTP 500 with a JSON error body (verified live:
                    // {"error":"Cannot read properties of undefined ..."}), so
                    // CloudStream received a "link" that is not a video at all →
                    // it shows as a Source that fails to play, and when it is the
                    // ONLY source the user sees "No Links Found".
                    //
                    // New behaviour: follow the redirect with GET (the token is
                    // single-use; HEAD sometimes 500s where GET succeeds), pull
                    // the real CDN url= param, and ONLY push it if it is a real
                    // http(s) media URL. If we can't recover a usable CDN URL we
                    // push NOTHING from this button and let the other mirrors
                    // (GoFile / PixelDrain / Fast Cloud) carry playback.
                    text.contains("instant dl") || text.contains("instant download") -> {
                        try {
                            if (link.contains("busycdn.xyz") || link.contains("instant.")) {
                                // Try redirect Location first (no body), then a
                                // full GET (some tokens only 302 on GET).
                                val location = runCatching {
                                    app.get(absLink, referer = baseUrl, allowRedirects = false, timeout = 15)
                                        .headers["Location"]
                                }.getOrNull().orEmpty()

                                val cdnFromLoc = Regex("""[?&]url=([^&]+)""")
                                    .find(location)?.groupValues?.get(1)
                                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

                                val finalCdn = when {
                                    !cdnFromLoc.isNullOrBlank() && cdnFromLoc.startsWith("http") -> cdnFromLoc
                                    location.startsWith("http") &&
                                        !location.contains("busycdn", true) -> location
                                    else -> null
                                }

                                if (!finalCdn.isNullOrBlank()) {
                                    Log.d(name, "Instant DL → CDN: ${finalCdn.take(80)}")
                                    pushLink(finalCdn, "[Instant 10GBPS]")
                                } else {
                                    Log.w(name, "Instant DL produced no usable CDN url; skipping dead token URL")
                                }
                            } else if (absLink.startsWith("http")) {
                                pushLink(absLink, "[Instant DL]")
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix-Instant", e.toString())
                            // Do NOT push absLink on failure — see note above.
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
                            val zdoc = app.get(absLink, referer = baseUrl).document
                            // The real download anchor on a /zfile/ page is a
                            // worker / awscdn / google CDN link. The site's nav
                            // also uses .btn (GDFlix logo, "Log in", "Join
                            // Telegram"), so picking firstOrNull() of a generic
                            // ".btn" selector grabs the GDFlix HOME link, not a
                            // file. Restrict to anchors that actually point at a
                            // downloadable host.
                            val dlink = zdoc.select("a[href]")
                                .map { it.attr("href") }
                                .firstOrNull { h ->
                                    Regex("""(?i)(workers\.dev|awscdn|googleusercontent|busycdn|\.mkv|\.mp4|/download)""")
                                        .containsMatchIn(h)
                                }
                                .orEmpty()
                            if (dlink.isNotBlank()) {
                                val finalDlink = if (dlink.startsWith("http")) dlink else baseUrl + dlink
                                pushLink(finalDlink, "[Fast Cloud]")
                            } else {
                                // v4 fix: the /zfile/ landing page is JS/CAPTCHA
                                // walled — pushing its HTML URL as a VIDEO link
                                // (old behaviour) created a dead Source that
                                // failed to play. Skip it; companion mirrors
                                // (GoFile/PixelDrain/Instant) handle playback.
                                Log.w(name, "Fast Cloud /zfile/ had no usable CDN link; skipping")
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
                            // The "GoFile [Multiup]" button points at a
                            // goflix.sbs mirror page that embeds the real
                            // gofile.io/d/<id> link. v4 fix: select via attribute
                            // contains, and if the anchor selector misses (markup
                            // drift) fall back to a raw-HTML regex so we still
                            // recover the gofile link. CloudStream has a built-in
                            // GoFile extractor that resolves /d/<id> to a stream.
                            val mirrorHtml = app.get(absLink, referer = baseUrl).text
                            val goLinks = Regex("""https?://gofile\.io/d/[A-Za-z0-9]+""")
                                .findAll(mirrorHtml).map { it.value }.distinct().toList()
                            if (goLinks.isEmpty()) {
                                Log.w(name, "GoFile mirror page had no gofile.io link: $absLink")
                            }
                            goLinks.amap { goLink ->
                                loadExtractor(goLink, absLink, subtitleCallback, callback)
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
// KatMovie4K-specific extractors.
//
// Each redirector host (ziddiflix, new3/new4.gdflix.dad, gdlink.dev)
// 302-redirects to new18.gdflix.net/file/<id> with cookies set along
// the way.  We let the base GDFlix.getUrl() handle the redirect chain
// (cookie collection) + page parsing.
//
// vifix.site is the exception: it serves a JS challenge, not a 302.
// We extract the file id from /gdflix/<id> and try new18.gdflix.net
// directly (cookies may be missing → login wall possible).
//
// driveleech.org is a separate system (Driveseed), not gdflix-compatible.
// ====================================================================

class GDFlixDad3 : GDFlix() {
    override val mainUrl = "https://new3.gdflix.dad"
}

class GDFlixDad4 : GDFlix() {
    override val mainUrl = "https://new4.gdflix.dad"
}

class GDLinkDev : GDFlix() {
    override val mainUrl = "https://gdlink.dev"
}

class Ziddiflix : GDFlix() {
    override val mainUrl = "https://ziddiflix.com"
}

class Vifix : GDFlix() {
    override val mainUrl = "https://vifix.site"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // vifix.site is now a parked/JS-challenge domain (verified live: it
        // 302s to ww1.vifix.site, a parking page — NOT a stream). The gdflix
        // file id embedded in the /gdflix/<id> path is still valid on the live
        // GDFlix system though, so we bypass vifix entirely.
        //
        // v4 fix: route through the rotation-proof entry "https://gdflix.dev/
        // file/<id>" instead of a hard-coded "new18" host. gdflix.dev 302s to
        // whatever the current canonical host is (new18 today, new19 tomorrow),
        // and GDFlix.getUrl() now derives its base from the resolved URL.
        val fileId = Regex("""/gdflix/([a-zA-Z0-9_]+)""")
            .find(url)?.groupValues?.get(1)
        if (!fileId.isNullOrBlank()) {
            val gdflixUrl = "https://gdflix.dev/file/$fileId"
            Log.d(name, "Vifix JS bypass: $url → $gdflixUrl")
            GDFlix().getUrl(gdflixUrl, referer ?: url, subtitleCallback, callback)
            return
        }
        Log.w(name, "Vifix: unexpected URL format: $url")
        super.getUrl(url, referer, subtitleCallback, callback)
    }
}

/**
 * driveleech.org — Driveseed-family file host, NOT gdflix-compatible.
 * File IDs do not transfer between systems.  Server is frequently down.
 *
 * Each KatMovie4K episode page links both a driveleech AND a gdflix/kmhd
 * mirror — so if driveleech is down, the companion link still works.
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
            val response = app.get(url, timeout = 15)
            val body = response.text
            if (body.isBlank() || body.length < 200) {
                Log.w(name, "Driveleech down (${body.length} bytes). Companion gdflix/kmhd mirror should work.")
                return
            }
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.w(name, "Driveleech unreachable: ${e.message}")
        }
    }
}

class DriveleechPro : GDFlix() {
    override val mainUrl = "https://driveleech.pro"
    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) { Driveleech().getUrl(url, referer, subtitleCallback, callback) }
}

class DriveleechNet : GDFlix() {
    override val mainUrl = "https://driveleech.net"
    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) { Driveleech().getUrl(url, referer, subtitleCallback, callback) }
}
