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

class KmhdExtractor : ExtractorApi() {
    override val name = "KMHD"
    override val mainUrl = "https://links.kmhd.eu"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("""/(?:file|play)[/?](?:id=)?([^/?&#]+)""")
                .find(url)?.groupValues?.get(1)?.trim()
            if (id.isNullOrBlank()) {
                Log.e(TAG, "Could not extract id from $url")
                return
            }

            val isPlay = url.contains("/play", ignoreCase = true)
            val path = if (isPlay) "/play?id=$id" else "/file/$id"

            val dataUrl = "$mainUrl$path/__data.json"
            val dataText = app.get(
                dataUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Cookie" to "unlocked=true",
                    "Referer" to "$mainUrl$path"
                )
            ).text

            val mirrors = parseSvelteKitData(dataText)
            if (mirrors.isEmpty()) {
                Log.w(TAG, "No mirrors found for $url")
                return
            }

            Log.d(TAG, "KMHD: forwarding ${mirrors.size} mirrors")

            mirrors.amap { mirror ->
                runCatching {
                    dispatchMirror(mirror, subtitleCallback, callback)
                }.onFailure {
                    Log.e(TAG, "Mirror ${mirror.host} failed: ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "KMHD extractor failed for $url: ${e.message}")
        }
    }

    private suspend fun dispatchMirror(
        mirror: Mirror,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (mirror.url.contains(Regex("(?i)(gd\\.kmhd|kmhd\\.net|katdrive)"))) {
            resolveFinalUrl(mirror.url) ?: mirror.url
        } else {
            mirror.url
        }

        Log.d(TAG, "Mirror ${mirror.host}: ${mirror.url} -> $finalUrl")

        when {
            finalUrl.contains("gdflix", ignoreCase = true) -> {
                GDFlix().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            }
            finalUrl.contains("hubcloud", ignoreCase = true) -> {
                HubCloud().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
            }
        }
    }

    private data class Mirror(val host: String, val url: String)

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
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
