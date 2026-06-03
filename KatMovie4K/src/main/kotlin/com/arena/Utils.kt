package com.arena

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Feature #1: Multi-layer redirect/obfuscation resolver
 *
 * Professional extensions like HDHub4U use JavaScript obfuscation on their
 * redirect pages. This function replicates the deobfuscation:
 *
 *   1. Fetch the redirect page HTML
 *   2. Extract base64 fragments using regex: s('o','...') and ck('_wp_http_N','...')
 *   3. Concatenate them → triple-decode: base64Decode(pen(base64Decode(base64Decode(combined))))
 *   4. Parse the resulting JSON for keys o, data, blog_url
 *   5. Decode o via base64 → the direct link
 *   6. If empty, hit blog_url?re=<data> to get a final redirect link
 *
 * Also handles simpler redirect patterns found on KatMovie4K mirror pages.
 */
suspend fun getRedirectLinks(url: String): String? {
    val tag = "RedirectResolver"
    return try {
        val doc = app.get(url, timeout = 15L).toString()

        // Strategy 1: HDHub4U-style JS obfuscation (s('o','...') / ck('_wp_http_...','...'))
        val regex = Regex("""s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)""")
        val combinedString = buildString {
            regex.findAll(doc).forEach { matchResult ->
                val extractedValue = matchResult.groups[1]?.value ?: matchResult.groups[2]?.value
                if (!extractedValue.isNullOrEmpty()) append(extractedValue)
            }
        }

        if (combinedString.isNotBlank()) {
            try {
                val decodedString = base64Decode(pen(base64Decode(base64Decode(combinedString))))
                val jsonObject = JSONObject(decodedString)
                val encodedUrl = base64Decode(jsonObject.optString("o", "")).trim()
                val data = androidBase64Encode(jsonObject.optString("data", "")).trim()
                val wphttp1 = jsonObject.optString("blog_url", "").trim()

                if (encodedUrl.isNotBlank()) {
                    Log.d(tag, "Resolved via JS deobfuscation: $url -> $encodedUrl")
                    return encodedUrl
                }

                if (wphttp1.isNotBlank() && data.isNotBlank()) {
                    val directLink = runCatching {
                        app.get("$wphttp1?re=$data".trim()).document.select("body").text().trim()
                    }.getOrDefault("").trim()

                    if (directLink.startsWith("http")) {
                        Log.d(tag, "Resolved via blog_url fallback: $url -> $directLink")
                        return directLink
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "JS deobfuscation failed for $url: ${e.message}")
            }
        }

        // Strategy 2: Simple meta-refresh redirect
        val metaRefresh = Regex("""(?i)<meta[^>]*http-equiv=["']?refresh["']?[^>]*content=["']?\d+;\s*url=([^"'>\s]+)""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!metaRefresh.isNullOrBlank() && metaRefresh.startsWith("http")) {
            Log.d(tag, "Resolved via meta-refresh: $url -> $metaRefresh")
            return metaRefresh
        }

        // Strategy 3: JavaScript window.location redirect
        val jsRedirect = Regex("""(?i)(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!jsRedirect.isNullOrBlank() && jsRedirect.startsWith("http")) {
            Log.d(tag, "Resolved via JS redirect: $url -> $jsRedirect")
            return jsRedirect
        }

        // Strategy 4: KatMovie4K-specific "var url = '...'" pattern
        val varUrl = Regex("""var\s+url\s*=\s*'([^']+)'""")
            .find(doc)?.groupValues?.get(1)?.trim()
        if (!varUrl.isNullOrBlank()) {
            val resolved = if (varUrl.startsWith("http")) varUrl
                           else getBaseUrl(url) + varUrl
            Log.d(tag, "Resolved via var url: $url -> $resolved")
            return resolved
        }

        // Strategy 5: Follow HTTP redirects
        val finalUrl = resolveFinalUrl(url)
        if (finalUrl != null && finalUrl != url) {
            Log.d(tag, "Resolved via HTTP redirect: $url -> $finalUrl")
            return finalUrl
        }

        Log.d(tag, "Could not resolve redirect for $url")
        null
    } catch (e: Exception) {
        Log.e(tag, "Redirect resolution failed for $url: ${e.message}")
        null
    }
}

/** ROT13 cipher — used by HDHub4U-style JS obfuscation */
fun pen(value: String): String {
    return value.map {
        when (it) {
            in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> it
        }
    }.joinToString("")
}

/** Android Base64 encode (for the blog_url?re= parameter) */
fun androidBase64Encode(value: String): String {
    return String(Base64.decode(value, Base64.DEFAULT))
}

/** Resolve final URL by following HTTP redirects (HEAD method) */
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

/** Extract base URL (scheme + host) from a full URL */
fun getBaseUrl(url: String): String {
    return try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { url }
}

/** Feature #9: Enhanced quality index extraction from header text */
fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") || lowerStr.contains("uhd") -> 2160
        lowerStr.contains("2k") -> 1440
        lowerStr.contains("hdr") || lowerStr.contains("dolby") -> 2160
        else -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }
}

/** Feature #9: Enhanced search quality detection (HDHub4U-style detailed patterns) */
fun detectSearchQualityPro(check: String?): com.lagradost.cloudstream3.SearchQuality? {
    val s = check ?: return null
    val u = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("""\b(4k|ds4k|uhd|2160p)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.FourK,
        Regex("""\b(hdts|hdcam|hdtc)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.HdCam,
        Regex("""\b(camrip|cam[- ]?rip)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.CamRip,
        Regex("""\b(cam)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.Cam,
        Regex("""\b(web[- ]?dl|webrip|webdl)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.WebRip,
        Regex("""\b(bluray|bdrip|blu[- ]?ray)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.BlueRay,
        Regex("""\b(1080p|fullhd)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.HD,
        Regex("""\b(720p)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.SD,
        Regex("""\b(hdrip|hdtv)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.HD,
        Regex("""\b(dvd)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.DVD,
        Regex("""\b(remux)\b""", RegexOption.IGNORE_CASE) to com.lagradost.cloudstream3.SearchQuality.BlueRay,
    )
    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}

/**
 * Feature #10: Load extractor with custom source name tag
 * (HDHub4U's loadSourceNameExtractor pattern)
 */
suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    quality: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "${link.source} $source",
                    "${link.source} $source",
                    link.url,
                ) {
                    this.quality = quality ?: link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

/** Clean title for display — extract codec/source tags like HDHub4U */
fun cleanTitlePro(title: String): String {
    val name = title.replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")
    val normalized = name
        .replace(Regex("""WEB[-_. ]?DL""", RegexOption.IGNORE_CASE), "WEB-DL")
        .replace(Regex("""WEB[-_. ]?RIP""", RegexOption.IGNORE_CASE), "WEBRIP")
        .replace(Regex("""H[ .]?265""", RegexOption.IGNORE_CASE), "H265")
        .replace(Regex("""H[ .]?264""", RegexOption.IGNORE_CASE), "H264")
    val parts = normalized.split(" ", "_", ".")
    val sourceTags = setOf("WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP")
    val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
    val filtered = parts.mapNotNull { part ->
        val p = part.uppercase()
        when {
            sourceTags.contains(p) -> p
            codecTags.contains(p) -> p
            p in setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DDP", "EAC3") -> p
            p in setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION") -> p
            else -> null
        }
    }
    return filtered.distinct().joinToString(" ")
}
