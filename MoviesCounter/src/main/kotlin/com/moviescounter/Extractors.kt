package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Custom ExtractorApi classes for the Hubdrive → HubCloud → Server chain.
 * Copied from phisher98/HDhub4u — the professional standard for Hindi
 * movie/series CloudStream extensions.
 *
 * Chain: hubdrive.space → hubcloud.foo → FSL/BuzzServer/PixelDrain/S3/Mega
 */

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

/**
 * Hubdrive extractor — follows hubdrive.space/file/XXX → hubcloud link.
 * This replaces the crash-prone 3-hop HTTP chain from v20 and earlier.
 * CloudStream's ExtractorApi framework handles timeouts and errors properly.
 */
class Hubdrive : ExtractorApi() {
    override val name = "HubDrive"
    override var mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.w("HubDrive", "Failed to fetch $url: ${e.message}")
            return
        }

        val href = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcloud]")?.attr("href")
            ?: doc.selectFirst("a.btn[href*=hubcdn]")?.attr("href")
            ?: doc.selectFirst("a[href].btn")?.attr("href")

        if (href.isNullOrBlank()) {
            Log.w("HubDrive", "No link found on hubdrive page: $url")
            return
        }

        val fullHref = if (href.startsWith("http")) href else {
            val base = URI(url)
            "${base.scheme}://${base.host}${if (href.startsWith("/")) href else "/$href"}"
        }

        when {
            fullHref.contains("hubcloud", ignoreCase = true) ->
                HubCloud().getUrl(fullHref, name, subtitleCallback, callback)
            fullHref.contains("hubcdn", ignoreCase = true) ->
                HUBCDN().getUrl(fullHref, name, subtitleCallback, callback)
            else -> loadExtractor(fullHref, name, subtitleCallback, callback)
        }
    }
}

/**
 * HubCloud extractor — the main download resolver.
 * Extracts quality/size from card header, then resolves server buttons:
 * FSL Server, Download File, BuzzServer, PixelDrain, S3 Server, FSLv2, Mega Server.
 */
class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override var mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = referer.orEmpty().ifBlank { name }

        val uri = try { URI(url) } catch (e: Exception) {
            Log.e(tag, "Invalid URL: ${e.message}")
            return
        }
        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        // Resolve the #download href from hubcloud page
        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl, timeout = 15000L).document
                    .selectFirst("#download")?.attr("href").orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract href: ${e.message}")
            return
        }

        if (href.isBlank()) return

        val document = try {
            app.get(href, timeout = 15000L).document
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch download page: ${e.message}")
            return
        }

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        document.select("a.btn").forEach { element ->
            val link = element.attr("href")
            val text = element.ownText()
            val label = text.lowercase()

            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "download file" in label -> {
                    callback(
                        newExtractorLink(
                            ref,
                            "$ref $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label -> {
                    try {
                        val resp = app.get("$link/download", referer = link, allowRedirects = false)
                        val dlink = resp.headers["hx-redirect"]
                            ?: resp.headers["HX-Redirect"].orEmpty()

                        if (dlink.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    "$ref [BuzzServer]",
                                    "$ref [BuzzServer] $labelExtras",
                                    dlink
                                ) { this.quality = quality }
                            )
                        }
                    } catch (_: Exception) {}
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl = if ("download" in link) link
                    else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "$ref Pixeldrain",
                            "$ref Pixeldrain $labelExtras",
                            finalUrl
                        ) { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [S3 Server]",
                            "$ref [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "fslv2" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSLv2]",
                            "$ref [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [Mega Server]",
                            "$ref [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "gofile" in label -> {
                    loadExtractor(link, ref, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) { "" }
    }

    private fun cleanTitle(title: String): String {
        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")

        val parts = normalized.split(" ", "_", ".")
        val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "HDTV", "BRRIP")
        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AV1", "10BIT")
        val audioTags = setOf("AAC", "AC3", "DTS", "DD", "DDP", "EAC3", "ATMOS", "DD5.1", "DDP5.1")
        val hdrTags = setOf("SDR", "HDR", "HDR10", "DV", "DOLBYVISION")
        val platformTags = setOf("NF", "AMZN", "DSNP", "HMAX", "APTV", "HULU", "CC")

        val filtered = parts.mapNotNull { part ->
            val p = part.uppercase()
            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } || audioTags.contains(p) -> p
                hdrTags.contains(p) -> p
                platformTags.contains(p) -> p
                else -> null
            }
        }

        return filtered.distinct().joinToString(" ")
    }
}

/**
 * HUBCDN extractor — decodes base64 reurl parameter to get actual URL.
 */
class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.fans"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // First try: extract from ?link= parameter (instant CDN)
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            callback(
                newExtractorLink(name, name, decoded, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Second try: fetch page and decode reurl from script
        try {
            val doc = app.get(url, timeout = 10000L).document
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()
                ?: doc.selectFirst("script:containsData(reurl)")?.data()

            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")

            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

            if (decodedUrl != null) {
                callback(
                    newExtractorLink(name, name, decodedUrl, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Fallback: try redirect header
            try {
                val res = app.get(url, allowRedirects = false, timeout = 10000L)
                val location = res.headers["Location"]
                if (!location.isNullOrEmpty() && location.startsWith("http")) {
                    callback(
                        newExtractorLink(name, name, location, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.w("HUBCDN", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * Hblinks aggregator — extracts links from intermediary pages and routes
 * them to the correct extractor (Hubdrive/HubCloud/HUBCDN).
 */
class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override var mainUrl = "https://hblinks.dad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.w("Hblinks", "Failed to fetch $url: ${e.message}")
            return
        }

        doc.select("h3 a, h5 a, div.entry-content p a, div.entry-content a").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            when {
                "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                else -> loadExtractor(href, name, subtitleCallback, callback)
            }
        }
    }
}
