package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/* ==========================================================================
 * Feature #3:  Enhanced Custom Extractor Classes
 * Feature #4:  HubCloud Multi-Server Handling (FSL, Buzz, PixelDrain, S3, etc.)
 * Feature #12: Subtitle Support (VidStack)
 * Feature #13: AES Decryption (VidStack / hubstream)
 * ==========================================================================*/

/** Browser-like headers for HubCloud/Hubdrive/Hblinks — many hosts block bare requests */
private val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to "https://hubcloud.lol/"
)

// ─── HubCloud Extractor (Enhanced Multi-Server) ──────────────────────────────

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false

    /** Extract PixelDrain API URL from page JS */
    fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val tag = name
            val ref = referer.orEmpty()
            val baseUrl = getBaseUrl(url)
            val uri = runCatching { URI(url) }.getOrElse { return }
            val realUrl = uri.toString()

            // Step 1: Resolve to the hubcloud download page
            val doc = app.get(url, headers = browserHeaders).document
            var link = if (url.contains("/video/")) {
                doc.selectFirst("div.vd > center > a")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { a ->
                        val h = a.attr("href")
                        val t = a.text()
                        h.contains("hubcloud.php", true) ||
                            t.contains("generate direct", true) ||
                            t.contains("direct download", true)
                    }?.attr("href")
                    ?: ""
            } else {
                // Feature #4: Follow #download href (HDHub4U pattern)
                val raw = doc.selectFirst("#download")?.attr("href").orEmpty()
                if (raw.startsWith("http", true)) raw
                else if (raw.isNotBlank()) baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
                else {
                    Regex("""var\s+url\s*=\s*'([^']*)'""")
                        .find(doc.selectFirst("script:containsData(url)")?.toString() ?: "")
                        ?.groupValues?.get(1) ?: ""
                }
            }

            if (link.isBlank()) {
                // Fallback: try getRedirectLinks
                link = getRedirectLinks(url) ?: ""
            }
            if (link.isBlank()) return
            if (!link.startsWith("https://")) link = baseUrl + link

            val document = app.get(link, headers = browserHeaders).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)
            val headerDetails = cleanTitlePro(header)

            val labelExtras = buildString {
                if (headerDetails.isNotEmpty()) append("[$headerDetails]")
                if (size.isNotEmpty()) append("[$size]")
            }

            // Step 2: Multi-server dispatch (Feature #4)
            document.select("a.btn, .btn-success, a[href*=download]").amap { element ->
                val btnLink = element.attr("href")
                val text = element.ownText().lowercase()
                val label = element.ownText()

                when {
                    // FSL Server — direct download
                    text.contains("fsl server") || text.contains("fslv2") -> {
                        val serverTag = if (text.contains("fslv2")) "[FSLv2]" else "[FSL Server]"
                        callback.invoke(
                            newExtractorLink(
                                "$ref $serverTag",
                                "$ref $serverTag $labelExtras",
                                btnLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // Download File / Direct Download
                    text.contains("download file") || text.contains("direct") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$ref [Direct]",
                                "$ref [Direct] $labelExtras",
                                btnLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // BuzzServer — follow link/download with HX-Redirect header
                    text.contains("buzzserver") || text.contains("buzz") -> {
                        try {
                            val resp = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                            val dlink = resp.headers["hx-redirect"]
                                ?: resp.headers["HX-Redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$ref [BuzzServer]",
                                        "$ref [BuzzServer] $labelExtras",
                                        dlink,
                                        ExtractorLinkType.VIDEO
                                    ) { this.quality = quality }
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "BuzzServer: No redirect - ${e.message}")
                        }
                    }
                    // PixelDrain
                    text.contains("pixeldra") || text.contains("pixelserver") || text.contains("pixel server") -> {
                        val pxBase = getBaseUrl(btnLink)
                        val finalUrl = when {
                            btnLink.contains("download", true) -> btnLink
                            btnLink.contains("/u/") -> "$pxBase/api/file/${btnLink.substringAfterLast("/u/").substringBefore("?")}?download"
                            else -> "$pxBase/api/file/${btnLink.substringAfterLast("/")}?download"
                        }
                        callback.invoke(
                            newExtractorLink(
                                "$ref [PixelDrain]",
                                "$ref [PixelDrain] $labelExtras",
                                finalUrl,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // S3 Server
                    text.contains("s3 server") || text.contains("s3server") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$ref [S3 Server]",
                                "$ref [S3 Server] $labelExtras",
                                btnLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // Mega Server
                    text.contains("mega") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$ref [Mega Server]",
                                "$ref [Mega Server] $labelExtras",
                                btnLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // 10Gbps / Instant
                    text.contains("10gbps") || text.contains("server : 10") -> {
                        val resolved = resolveFinalUrl(btnLink)
                        val finalLink = resolved?.let { r ->
                            if (r.contains("link=")) r.substringAfter("link=") else r
                        } ?: btnLink
                        callback.invoke(
                            newExtractorLink(
                                "$ref [10Gbps]",
                                "$ref [10Gbps] $labelExtras",
                                finalLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // Cloud R2 / Fast Cloud
                    text.contains("cloud") || text.contains("r2") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$ref [Cloud R2]",
                                "$ref [Cloud R2] $labelExtras",
                                btnLink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                    // Everything else — fall through to loadExtractor
                    else -> {
                        if (btnLink.startsWith("http")) {
                            loadExtractor(btnLink, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Failed: ${e.message}")
        }
    }
}

// ─── Hubdrive Extractor ─────────────────────────────────────────────────────

/** Feature #3: Hubdrive extractor — follows .btn-success1 link to HubCloud */
class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val href = app.get(url, headers = browserHeaders, timeout = 5000L).document
                .select(".btn.btn-primary.btn-user.btn-success1.m-1")
                .attr("href")
            when {
                href.contains("hubcloud", ignoreCase = true) ->
                    HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
                href.isNotBlank() ->
                    loadExtractor(href, "HubDrive", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("Hubdrive", "Failed: ${e.message}")
        }
    }
}

// ─── HUBCDN Extractor ───────────────────────────────────────────────────────

/** Feature #3: HUBCDN extractor — extracts var reurl from JS, base64-decodes */
class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")
            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")
            if (decodedUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        decodedUrl,
                        INFER_TYPE,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("HUBCDN", "Failed: ${e.message}")
        }
    }
}

// ─── Hubcdn Regex Extractor ─────────────────────────────────────────────────

/** Feature #3: Hubcdnn — extracts r=<base64> param from URL */
class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            app.get(url).document.toString().let {
                val encoded = Regex("""r=([A-Za-z0-9+/=]+)""").find(it)?.groups?.get(1)?.value
                if (!encoded.isNullOrEmpty()) {
                    val m3u8 = base64Decode(encoded).substringAfterLast("link=")
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = m3u8,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Hubcdnn", "Failed: ${e.message}")
        }
    }
}

// ─── VidStack Extractor with AES Decryption ─────────────────────────────────

/** Feature #12+13: VidStack — AES-128-CBC decryption + subtitle extraction */
open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
        )
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getBaseUrl(url)

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                AesHelper.decryptAES(encoded, key, iv)
            } catch (_: Exception) {
                null
            }
        } ?: throw Exception("Failed to decrypt with all IVs")

        val m3u8 = Regex(""""source":"(.*?)"""").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""

        // Feature #12: Subtitle extraction
        val subtitlePattern = Regex(""""([^"]+)":\s*"([^"]+)"""")
        val subtitleSection = Regex(""""subtitle":\{(.*?)\}""").find(decryptedText)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            subtitlePattern.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8.replace("https", "http"),
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf(
                    "referer" to url,
                    "Origin" to url.substringAfterLast("/")
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

/** Hubstream is a VidStack variant */
class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.*"
}

// ─── AES Helper ─────────────────────────────────────────────────────────────

object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"

    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

// ─── PixelDrainDev ──────────────────────────────────────────────────────────

/** Feature #3: PixelDrainDev variant */
class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

// ─── Hblinks (recursive resolver) ───────────────────────────────────────────

/** Feature #3: Hblinks — scrapes links and dispatches to Hubdrive/HubCloud/HUBCDN */
open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url, headers = browserHeaders).document.select("h3 a,h5 a,div.entry-content p a").map {
            val href = it.absUrl("href").ifBlank { it.attr("href") }
            when {
                "hubdrive" in href -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in href -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in href -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                else -> loadSourceNameExtractor(name, href, "", Qualities.Unknown.value, subtitleCallback, callback)
            }
        }
    }
}

class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.*"
}

// ─── HdStream4u (VidHidePro variant) ────────────────────────────────────────

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ─── GDFlix family (existing + preserved) ───────────────────────────────────

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
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try { if (app.get(url, timeout = 15).text.length > 200) super.getUrl(url, referer, subtitleCallback, callback) } catch (e: Exception) {}
    }
}

class DriveleechPro : GDFlix() { override val mainUrl = "https://driveleech.pro"; override suspend fun getUrl(u: String, r: String?, s: (SubtitleFile) -> Unit, c: (ExtractorLink) -> Unit) { Driveleech().getUrl(u, r, s, c) } }
class DriveleechNet : GDFlix() { override val mainUrl = "https://driveleech.net"; override suspend fun getUrl(u: String, r: String?, s: (SubtitleFile) -> Unit, c: (ExtractorLink) -> Unit) { Driveleech().getUrl(u, r, s, c) } }
