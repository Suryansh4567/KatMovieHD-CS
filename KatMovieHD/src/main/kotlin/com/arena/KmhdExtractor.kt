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
import java.util.Base64

/**
 * Extractor for `https://links.kmhd.eu/file/<id>` and `/play?id=<id>` URLs.
 *
 * KatMovieHD wraps every download link in a SvelteKit-based "Click to Unlock
 * Links" interstitial. We bypass it entirely with two HTTP calls:
 *
 *   1) POST  /locked?/unlock&redirect=<base64 path>
 *           → server sets cookie `unlocked=true`
 *
 *   2) GET   /file/<id>/__data.json   (with the cookie)
 *           → returns a denormalized JSON blob with every mirror URL.
 *
 * The JSON uses SvelteKit's index-based denormalization. Example shape:
 *
 *   { "type":"chunk", "data":[
 *       { "val":1, "links":12, "ads":39 },          ← root reference indexes
 *       { "_id":2, "name":3, "upload_links":4, ...},
 *       "From_0168dce2",                            ← _id    (index 2)
 *       "From S04E01 480p WEB-DL ... mkv",          ← name   (index 3)
 *       { "gdflix_res":..., "streamtape_res":..., ...},
 *       "None", "?e550490oizmntt16ia3j", "xKTzph5QgCx7JJG",
 *       ...,
 *       { "gdflix_res":13, "hubdrive_res":17, "katdrive_res":20, ...},
 *       { "mx":14, "link":15, "img":16 },           ← gdflix node
 *       40000000000, "https://gd.kmhd.eu/file/", "<image>",
 *       { "mx":14, "link":18, "img":19 },           ← hubcloud node
 *       "https://hubcloud.foo/drive/", "<image>",
 *       ...
 *   ]}
 *
 * Each mirror "node" has a `link` index pointing to its base URL (e.g.
 * `https://gd.kmhd.eu/file/`). The full URL is built as
 * `base + (linkId or streamtapeRes)`.
 */
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
            // Extract the file/play id from the URL.
            val id = Regex("""/(?:file|play)[/?](?:id=)?([^/?&#]+)""")
                .find(url)?.groupValues?.get(1)?.trim()
                ?: run {
                    Log.e(TAG, "Could not extract id from $url")
                    return
                }

            val isPlay = url.contains("/play", ignoreCase = true)
            val path   = if (isPlay) "/play?id=$id" else "/file/$id"
            val redirectB64 = base64Encode(path)

            // Step 1 – POST to unlock. The server replies with a small JSON
            // body and sets the `unlocked=true` cookie. We don't actually
            // need to parse the response; we just need the cookie back.
            val unlockResp = app.post(
                "$mainUrl/locked?/unlock&redirect=$redirectB64",
                headers = mapOf(
                    "User-Agent"   to UA,
                    "Origin"       to mainUrl,
                    "Referer"      to "$mainUrl/locked?redirect=$redirectB64",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "x-sveltekit-action" to "true"
                ),
                data = mapOf<String, String>()
            )
            val unlockedCookie = unlockResp.cookies["unlocked"] ?: "true"

            // Step 2 – fetch SvelteKit's data JSON for the actual file page.
            val dataUrl = "$mainUrl$path/__data.json"
            val dataText = app.get(
                dataUrl,
                headers = mapOf("User-Agent" to UA),
                cookies = mapOf("unlocked" to unlockedCookie)
            ).text

            val mirrors = parseSvelteKitData(dataText)
            if (mirrors.isEmpty()) {
                Log.w(TAG, "No mirrors returned from $dataUrl")
                return
            }
            Log.d(TAG, "KMHD: ${mirrors.size} mirrors for $id → " +
                    mirrors.joinToString { it.host })

            // Step 3 – forward every mirror URL to the built-in extractors.
            mirrors.amap { mirror ->
                runCatching {
                    loadExtractor(mirror.url, mainUrl, subtitleCallback, callback)
                }.onFailure { Log.e(TAG, "loadExtractor failed for ${mirror.url}: ${it.message}") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "KMHD extractor failed for $url: ${e.message}")
        }
    }

    // -----------------------------------------------------------------
    // SvelteKit "chunk" data parser
    // -----------------------------------------------------------------
    private data class Mirror(val host: String, val url: String)

    /**
     * The data file is a stream of JSON objects, one per line:
     *   {"type":"data", ...}
     *   {"type":"chunk", "data":[ ... ]}
     *   ...
     *
     * The chunk we care about has shape (root index 0):
     *   [ {"val":1, "links":12, "ads":39}, ... ]
     * `links` is the index of a map of mirror nodes. Each mirror node looks
     * like `{ "mx":..., "link":N, "img":... }`. The element at index N is
     * the base URL string (e.g. `"https://gd.kmhd.eu/file/"`). The actual
     * file ID lives at the top of the chunk (we read it from the
     * `upload_links` map for streamtape, otherwise we use the global "val"
     * id at index 7-ish — but a simpler approach is: every base URL ending
     * with `/` or `?` gets appended with the global file id we discover
     * earlier in the same chunk).
     */
    private fun parseSvelteKitData(dataText: String): List<Mirror> {
        val mirrors = mutableListOf<Mirror>()
        for (line in dataText.split('\n').filter { it.isNotBlank() }) {
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (obj.optString("type") != "chunk") continue

            val arr = obj.optJSONArray("data") ?: continue

            // The root is the first entry: { "val":1, "links":12, "ads":39 }
            val root = arr.optJSONObject(0) ?: continue
            val linksIdx = root.optInt("links", -1).takeIf { it > 0 } ?: continue

            val linksMap = arr.opt(linksIdx) as? JSONObject ?: continue

            // Walk every mirror entry inside linksMap.
            val keys = linksMap.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val nodeIdx = linksMap.optInt(key, -1).takeIf { it > 0 } ?: continue
                val node = arr.opt(nodeIdx) as? JSONObject ?: continue

                // Each node has at least a "link" index pointing to the base URL.
                val linkIdx = node.optInt("link", -1).takeIf { it > 0 } ?: continue
                val baseUrl = arr.opt(linkIdx) as? String ?: continue
                if (baseUrl.isBlank() || !baseUrl.startsWith("http")) continue

                // Find a usable ID to append. KatMovieHD's `*_res` map points
                // to a shared string index — if that index resolves to "None"
                // it means the mirror isn't actually available for this file
                // (placeholder slot). Skip those.
                val appendId = findAppendId(arr, key)
                if (appendId.isNullOrBlank()) continue
                val finalUrl = baseUrl + appendId

                mirrors.add(Mirror(host = key, url = finalUrl))
            }
        }
        return mirrors.distinctBy { it.url }
    }

    /**
     * For a given mirror key like "gdflix_res", search the chunk array for
     * the matching ID string. KatMovieHD's denormalized JSON puts every
     * `*_res` value as a string somewhere in the array.
     *
     * The trick: the file's main ID (`xKTzph5QgCx7JJG` shape) and the
     * streamtape token (`?e550490oizmntt16ia3j` shape) are *different
     * strings* in different array positions. We look through the upload_links
     * sub-object to find the right one.
     */
    private fun findAppendId(arr: JSONArray, key: String): String? {
        // Find any sub-object that has our key (e.g. "gdflix_res": <idx>)
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) as? JSONObject ?: continue
            if (!item.has(key)) continue
            val resIdx = item.optInt(key, -1).takeIf { it > 0 } ?: continue
            val token = arr.opt(resIdx) as? String ?: continue
            // "None" placeholder means this mirror is not available.
            if (token.equals("None", ignoreCase = true) || token.isBlank()) continue
            return token
        }
        return null
    }

    // -----------------------------------------------------------------
    private fun base64Encode(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray()).trimEnd('=').let {
            // KatMovieHD uses padded base64 with '=' — restore them.
            val pad = (4 - (it.length % 4)) % 4
            it + "=".repeat(pad)
        }

    companion object {
        private const val TAG = "KmhdExtractor"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
