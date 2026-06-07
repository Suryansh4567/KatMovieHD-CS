package com.moviescounter

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Custom Extractors — v34
 *
 * Built from scratch by tracing actual link chains:
 *
 * 1. HubDrive: hubdrive.space → button → hubcloud.foo link
 *    - Button class: "btn btn-primary btn-user btn-success1 m-1"
 *    - Only visible when logged in, but HubCloud link is shown
 *
 * 2. HubCloud: hubcloud.foo/drive/{id}
 *    → a#download → gamerxyt.com/hubcloud.php?...
 *    → gamerxyt page has buttons: FSLv2, FSL, PixelServer, BuzzServer, etc.
 *
 * 3. HUBCDN: hubcdn.sbs/dl/?link=hub.obsession.buzz/{hash}
 *    → obsession.buzz/{hash} → direct MKV/MP4 video file
 *
 * 4. Mclinks: mclinks.xyz/archives/{id}
 *    → Image links in entry-content → hubcloud/hubdrive/hubcdn
 *
 * 5. Hblinks: hblinks.dad → same as mclinks
 *
 * 6. HdStream4u: hdstream4u.com → VidHidePro
 */

// ======================================================================
// HdStream4u — built-in VidHidePro
// ======================================================================

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

// ======================================================================
// Mclinks — extracts links from mclinks.xyz intermediary pages
//
// Actual HTML structure (confirmed):
// <div class="entry-content">
//   <p><a href="hubcloud.foo/drive/..."><img src="Cloud-Logo.png" /></a></p>
//   <p><a href="hubdrive.space/file/..."><img src="Hubdrive2.png" /></a></p>
//   <p><a href="multicloudlinks.com/view/..."><img src="gofile.png" /></a></p>
// </div>
//
// KEY: Links are image-based, not text-based. Select on p a, not h3 a.
// ======================================================================

class Mclinks : ExtractorApi() {
    override val name = "Mclinks"
    override var mainUrl = "https://mclinks.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15_000L).document
        } catch (e: Exception) {
            Log.w("Mclinks", "Failed to fetch $url: ${e.message}")
            return
        }

        // Select ALL links in entry-content (image links + text links)
        val selectors = listOf(
            "div.entry-content a[href]",
            "div.entry-content p a[href]",
            "h3 a[href]",
            "h5 a[href]"
        )

        val seen = mutableSetOf<String>()
        for (selector in selectors) {
            doc.select(selector).forEach { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                seen.add(href)

                val lower = href.lowercase()
                try {
                    when {
                        "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                        "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                        "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        "obsession.buzz" in lower || "noirspy.buzz" in lower -> {
                            callback(newExtractorLink(name, "Direct [CDN]", href) {
                                this.quality = Qualities.Unknown.value
                            })
                        }
                        "mclinks" in lower -> return@forEach
                        "wordpress.org" in lower || "michaelvandenberg" in lower -> return@forEach
                        "multicloud" in lower -> loadExtractor(href, "", subtitleCallback, callback)
                        "gofile" in lower -> loadExtractor(href, "", subtitleCallback, callback)
                        else -> loadExtractor(href, "", subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.w("Mclinks", "Failed for $href: ${e.message}")
                }
            }
        }
    }
}

// ======================================================================
// HubDrive — hubdrive.space/file/{id}
//
// Actual HTML (confirmed):
// <h5><a class="btn btn-primary btn-user btn-success1 m-1"
//        href="https://hubcloud.foo/drive/...">
//   [HubCloud Server]</a></h5>
//
// Also shows: GDrive [Login] button (needs auth, skip)
// ======================================================================

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

        // Strategy 1: Look for the HubCloud button (primary pattern)
        // Class: btn btn-primary btn-user btn-success1 m-1
        val hubCloudBtn = doc.selectFirst("a.btn.btn-primary.btn-user.btn-success1.m-1")
            ?: doc.selectFirst("a.btn-success1[href*=hubcloud]")
            ?: doc.selectFirst("a[href*=hubcloud]")

        if (hubCloudBtn != null) {
            val href = hubCloudBtn.attr("href").trim()
            if (href.startsWith("http") && href.contains("hubcloud", ignoreCase = true)) {
                Log.d("HubDrive", "Found HubCloud link: $href")
                HubCloud().getUrl(href, name, subtitleCallback, callback)
                return
            }
        }

        // Strategy 2: Look for any link to hubcloud or hubcdn
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) return@forEach

            val lower = href.lowercase()
            when {
                "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
            }
        }

        // Strategy 3: Check if page says "cannot be found" (dead link)
        val pageText = doc.text().lowercase()
        if (pageText.contains("cannot be found")) {
            Log.w("HubDrive", "File not found on server: $url")
        }
    }
}

// ======================================================================
// HubCloud — hubcloud.foo/drive/{id}
//
// Chain (confirmed from live HTML):
// 1. hubcloud.foo/drive/{id}
//    → <a id="download" href="gamerxyt.com/hubcloud.php?host=hubcloud&id=...&token=...">
//
// 2. gamerxyt.com page has download buttons:
//    - FSLv2 Server: cdn.fsl-buckets.work/...mkv (direct video)
//    - FSL Server: hub.obsession.buzz/{hash}?token=... (redirect → video)
//    - PixelServer: pixel.hubcloud.cx/?id=...
//    - PixelDrain: pixeldrain.dev/u/...
//    - S3 Server: direct video URL
// ======================================================================

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

        // Step 1: Get the #download href from hubcloud page
        val downloadHref = try {
            if (url.contains("hubcloud.php")) {
                url  // Already the download page URL
            } else {
                val doc = app.get(url, timeout = 15000L).document
                val raw = doc.selectFirst("#download")?.attr("href").orEmpty()
                if (raw.startsWith("http", true)) raw
                else {
                    val base = try {
                        URI(url).let { "${it.scheme}://${it.host}" }
                    } catch (_: Exception) { "" }
                    base.trimEnd('/') + "/" + raw.trimStart('/')
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get download href: ${e.message}")
            return
        }

        if (downloadHref.isBlank()) {
            Log.w(tag, "No #download href found on $url")
            return
        }

        Log.d(tag, "Step 1: $url -> $downloadHref")

        // Step 2: Fetch the download page (gamerxyt.com)
        val document = try {
            app.get(downloadHref, timeout = 15000L).document
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

        Log.d(tag, "Step 2: quality=$quality header='$header' size='$size' buttons=${document.select("a.btn").size}")

        // Step 3: Process each download button
        document.select("a.btn").forEach { element ->
            val link = element.attr("href").trim()
            val text = element.ownText()
            val label = text.lowercase()

            if (link.isBlank() || !link.startsWith("http")) return@forEach

            try {
                when {
                    // FSLv2 Server — direct video CDN URL
                    "fslv2" in label || "cdn.fsl" in link.lowercase() -> {
                        callback(
                            newExtractorLink(
                                "$ref [FSLv2]",
                                "$ref [FSLv2] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    // FSL Server — usually obsession.buzz redirect
                    "fsl server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [FSL Server]",
                                "$ref [FSL Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    // Download File — generic
                    "download file" in label -> {
                        callback(
                            newExtractorLink(
                                ref,
                                "$ref $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    // BuzzServer
                    "buzzserver" in label || "buzz server" in label -> {
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

                    // PixelDrain / PixelServer
                    "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                        val base = try {
                            URI(link).let { "${it.scheme}://${it.host}" }
                        } catch (_: Exception) { "" }
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

                    // S3 Server
                    "s3 server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [S3 Server]",
                                "$ref [S3 Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    // Mega Server
                    "mega server" in label -> {
                        callback(
                            newExtractorLink(
                                "$ref [Mega Server]",
                                "$ref [Mega Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    // Gofile
                    "gofile" in label -> {
                        loadExtractor(link, "", subtitleCallback, callback)
                    }

                    // HUBCDN
                    "hubcdn" in link.lowercase() -> {
                        HUBCDN().getUrl(link, ref, subtitleCallback, callback)
                    }

                    // Everything else — try loadExtractor
                    else -> {
                        loadExtractor(link, "", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed button '$label': ${e.message}")
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("""(\d{3,4})[pP]""")
            .find(str.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun cleanTitle(title: String): String {
        val name = title.replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")
        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")
        val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP")
        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")
        val hdrTags = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        return parts.mapNotNull { part ->
            val p = part.uppercase()
            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                hdrTags.contains(p) -> if (p in setOf("DV", "DOLBYVISION")) "DOLBYVISION" else p
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }.distinct().joinToString(" ")
    }
}

// ======================================================================
// HUBCDN — hubcdn.sbs/dl/?link=hub.obsession.buzz/{hash}
//
// Chain (confirmed from live testing):
// 1. hubcdn.sbs/dl/?link=https://hub.obsession.buzz/{hash}
// 2. hub.obsession.buzz/{hash} → direct MKV/MP4 file (binary data)
//
// KEY: obsession.buzz URLs return DIRECT VIDEO FILES, not HTML pages!
// Don't try to parse them as HTML — use as direct links.
// ======================================================================

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override var mainUrl = "https://hubcdn.sbs"  // Match actual site domain
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Strategy 1: Extract ?link= parameter
        val linkParam = Regex("""[?&]link=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
        if (linkParam != null) {
            val decoded = java.net.URLDecoder.decode(linkParam, "UTF-8")
            Log.d("HUBCDN", "?link= -> $decoded")

            // obsession.buzz URLs are DIRECT video files — use immediately
            if (decoded.contains("obsession.buzz") || decoded.contains("noirspy.buzz")) {
                callback(
                    newExtractorLink(name, name, decoded, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try to fetch and parse as HTML (for hubcdn pages with reurl script)
            try {
                val doc = app.get(decoded, timeout = 10000L).document
                // Check for a#vd link
                val vdLink = doc.selectFirst("a#vd")?.attr("href")?.trim()
                if (!vdLink.isNullOrEmpty() && vdLink.startsWith("http")) {
                    callback(newExtractorLink(name, name, vdLink, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
                // Check for reurl script
                val scriptText = doc.selectFirst("script:containsData(reurl)")?.data()
                val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                    .find(scriptText ?: "")
                    ?.groupValues?.get(1)
                    ?.substringAfter("?r=")
                val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")
                if (decodedUrl != null) {
                    callback(newExtractorLink(name, name, decodedUrl, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                    })
                    return
                }
            } catch (_: Exception) {}

            // Fallback: use decoded URL as direct link
            callback(newExtractorLink(name, name, decoded, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
            return
        }

        // Strategy 2: Fetch hubcdn page and decode reurl from script
        try {
            val doc = app.get(url, timeout = 10000L).document

            val vdLink = doc.selectFirst("a#vd")?.attr("href")?.trim()
            if (!vdLink.isNullOrEmpty() && vdLink.startsWith("http")) {
                callback(newExtractorLink(name, name, vdLink, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            val scriptText = doc.selectFirst("script:containsData(reurl)")?.data()
            val encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText ?: "")
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")
            val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")

            if (decodedUrl != null) {
                callback(newExtractorLink(name, name, decodedUrl, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                })
                return
            }
        } catch (e: Exception) {
            Log.w("HUBCDN", "Failed to resolve $url: ${e.message}")
        }
    }
}

// ======================================================================
// Hblinks — hblinks.dad → hubcloud/hubdrive/hubcdn
// Same pattern as Mclinks
// ======================================================================

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

        val selectors = listOf(
            "div.entry-content a[href]",
            "div.entry-content p a[href]",
            "h3 a[href]",
            "h5 a[href]"
        )

        val seen = mutableSetOf<String>()
        for (selector in selectors) {
            doc.select(selector).forEach { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                if (href in seen) return@forEach
                seen.add(href)

                val lower = href.lowercase()
                try {
                    when {
                        "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                        "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                        "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        "obsession.buzz" in lower || "noirspy.buzz" in lower -> {
                            callback(newExtractorLink(name, "Direct [CDN]", href) {
                                this.quality = Qualities.Unknown.value
                            })
                        }
                        "gofile" in lower -> loadExtractor(href, "", subtitleCallback, callback)
                        "wordpress.org" in lower || "michaelvandenberg" in lower -> return@forEach
                        else -> loadExtractor(href, "", subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.w("Hblinks", "Failed for $href: ${e.message}")
                }
            }
        }
    }
}
