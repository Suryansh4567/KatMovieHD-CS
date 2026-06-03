package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class KmhdExtractor : ExtractorApi() {
    override val name = "KMHD"
    override val mainUrl = "https://links.kmhd.eu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl invoked: $url")

        if (Regex("""(?i)kmhd\.eu/archives/\d+""").containsMatchIn(url)) {
            handleArchivePage(url, subtitleCallback, callback)
            return
        }

        handleSvelteKitFileOrPlay(url, subtitleCallback, callback)
    }

    private suspend fun fetchWithFallbacks(path: String, candidateHosts: List<String>): Pair<String, String>? {
        for (host in candidateHosts) {
            val fullUrl = "$host$path"
            Log.d(TAG, "Fetching: $fullUrl")
            try {
                val res = app.get(
                    fullUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Cookie" to "unlocked=true",
                        "Referer" to "$host$path",
                    ),
                    timeout = 30
                )
                if (res.code == 200 && res.text.isNotBlank()) {
                    return res.text to host
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed for $fullUrl: ${e.message}")
            }
        }
        return null
    }

    private suspend fun handleArchivePage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "Archive page handler: $url")
        val html = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Cookie" to "unlocked=true",
                    "Referer" to "https://kmhd.eu/"
                ),
                timeout = 30
            ).text
        }.getOrElse {
            Log.e(TAG, "Archive page fetch failed: ${it.message}")
            return
        }
        if (html.isBlank()) return

        val doc = Jsoup.parse(html)
        val anchors = doc.select("a[href]")
            .mapNotNull { it.attr("href").trim().takeIf { h -> h.startsWith("http") } }
            .filter { href -> !ARCHIVE_IGNORE_REGEX.containsMatchIn(href) }
            .distinct()

        if (anchors.isEmpty()) return

        anchors.amap { mirrorUrl ->
            dispatchArchiveMirror(url, mirrorUrl, subtitleCallback, callback)
        }
    }

    private suspend fun dispatchArchiveMirror(
        archivePageUrl: String,
        mirrorUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val lower = mirrorUrl.lowercase()
            when {
                lower.contains("gd.kmhd.eu/file/") -> {
                    val fileId = mirrorUrl.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    val finalUrl = resolveFinalUrl(mirrorUrl)
                        ?.takeIf { it.contains("gdflix", ignoreCase = true) }
                        ?: "https://new18.gdflix.net/file/$fileId"
                    GDFlixNet().getUrl(finalUrl, archivePageUrl, subtitleCallback, callback)
                }
                lower.contains("gdflix") || lower.contains("gd-flix") -> {
                    GDFlix().getUrl(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }
                lower.contains("hubcloud.") || lower.contains("hubdrive") -> {
                    HubCloud().getUrl(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }
                lower.contains("katdrive.") -> {
                    handleKatdriveArchive(mirrorUrl, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun handleKatdriveArchive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 30).document
        val hubUrl = doc.select("a[href]").mapNotNull { a -> a.absUrl("href").ifBlank { a.attr("href") }.takeIf { it.contains("hubcloud", ignoreCase = true) } }.firstOrNull()
        if (!hubUrl.isNullOrBlank()) HubCloud().getUrl(hubUrl, url, subtitleCallback, callback)
        else loadExtractor(url, mainUrl, subtitleCallback, callback)
    }

    private suspend fun handleSvelteKitFileOrPlay(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val rawId = Regex("""/(?:file|play)[/?](?:id=)?([^/?&#]+)""").find(url)?.groupValues?.get(1)?.trim()
            if (rawId.isNullOrBlank()) return

            val id = java.net.URLEncoder.encode(rawId, "UTF-8").replace("+", "%20")
            val isPlay = url.contains("/play", ignoreCase = true)
            val path = if (isPlay) "/play?id=$id" else "/file/$id"

            val incomingHost = Regex("""^https?://[^/]+""").find(url)?.value
            val candidateHosts = listOfNotNull(
                incomingHost?.takeIf { it.contains("kmhd", ignoreCase = true) },
                "https://links.kmhd.eu",
                "https://links.kmhd.net",
                "https://gd.kmhd.net"
            ).distinct()

            val jsonData = fetchWithFallbacks("$path/__data.json", candidateHosts)
            var mirrors = if (jsonData != null) {
                val parsed = parseSvelteKitData(jsonData.first)
                if (parsed.isEmpty()) salvageMirrorsByRegex(jsonData.first) else parsed
            } else emptyList()

            if (mirrors.isEmpty()) {
                val htmlData = fetchWithFallbacks(path, candidateHosts)
                if (htmlData != null) mirrors = salvageMirrorsByRegex(htmlData.first)
            }

            if (mirrors.isEmpty()) return

            mirrors.amap { mirror ->
                runCatching { dispatchMirror(mirror, subtitleCallback, callback) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "KMHD extractor outer failure for $url: ${e.message}")
        }
    }

    private suspend fun dispatchMirror(
        mirror: Mirror,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (mirror.url.contains(Regex("(?i)(gd\\.kmhd|kmhd\\.net|katdrive)"))) {
            resolveFinalUrl(mirror.url) ?: mirror.url
        } else mirror.url

        when {
            finalUrl.contains("gdflix", ignoreCase = true) -> GDFlix().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            finalUrl.contains("hubcloud", ignoreCase = true) -> HubCloud().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            else -> loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
        }
    }

    private data class Mirror(val host: String, val url: String)

    private fun salvageMirrorsByRegex(rawJson: String): List<Mirror> {
        val urlRegex = Regex("""https?://[^\s"'\\<>]+""")
        return urlRegex.findAll(rawJson)
            .map { it.value.trimEnd('\\', ',', '"', '\'') }
            .filter { KNOWN_MIRROR_REGEX.containsMatchIn(it) }
            .filter { !AD_HOST_REGEX.containsMatchIn(it) }
            .filter { it.substringAfter("://").substringAfter("/", "").isNotBlank() }
            .distinct()
            .map { Mirror(host = it.substringAfter("://").substringBefore("/"), url = it) }
            .toList()
    }

    private fun parseSvelteKitData(dataText: String): List<Mirror> {
        val mirrors = mutableListOf<Mirror>()
        for (line in dataText.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (obj.optString("type") != "chunk") continue
            val arr = obj.optJSONArray("data") ?: continue
            val root = arr.optJSONObject(0) ?: continue
            val linksIdx = root.optInt("links", -1)
            if (linksIdx <= 0) continue
            val linksMap = arr.opt(linksIdx) as? JSONObject ?: continue
            val keyIter = linksMap.keys()
            while (keyIter.hasNext()) {
                val key = keyIter.next()
                val nodeIdx = linksMap.optInt(key, -1)
                if (nodeIdx <= 0) continue
                val node = arr.opt(nodeIdx) as? JSONObject ?: continue
                val linkIdx = node.optInt("link", -1)
                if (linkIdx <= 0) continue
                val baseUrl = arr.opt(linkIdx) as? String ?: continue
                if (baseUrl.isBlank() || !baseUrl.startsWith("http")) continue
                val appendId = findAppendId(arr, key)
                if (appendId.isNullOrBlank()) continue
                mirrors.add(Mirror(host = key, url = baseUrl + appendId))
            }
        }
        return mirrors.distinctBy { it.url }
    }

    private fun findAppendId(arr: JSONArray, key: String): String? {
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) as? JSONObject ?: continue
            if (!item.has(key)) continue
            val resIdx = item.optInt(key, -1)
            if (resIdx <= 0) continue
            val token = arr.opt(resIdx) as? String ?: continue
            if (token.equals("None", ignoreCase = true) || token.isBlank()) continue
            return token
        }
        return null
    }

    companion object {
        private const val TAG = "KmhdExtractor"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val KNOWN_MIRROR_REGEX = Regex("""(?i)(gd\.kmhd|hubcloud|hubdrive|katdrive|send\.cm|fuckingfast|1fichier|streamtape|hglink|streamwish|gdflix)""")
        private val AD_HOST_REGEX = Regex("""(?i)(cathaytrash|al5sm|catimages|so-gr3at3|gstatic|google|doubleclick|popads|propeller)""")
        private val ARCHIVE_IGNORE_REGEX = Regex("""(?i)(^https?://(?:www\.)?kmhd\.eu(?:/|$)|imdb\.com|themoviedb\.org|wikipedia|youtube\.com|youtu\.be|facebook\.com|twitter\.com|instagram\.com|t\.me|telegram\.|whatsapp\.|pinterest\.|reddit\.com|pichub|catimages|imgur|postimg|imgbox|wp-content|wp-includes|wp-json|xmlrpc|\.(?:png|jpe?g|gif|webp|svg|ico|css|woff2?)(?:\?|$))""")
    }
}
