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

        // Branch 1: legacy WordPress archive pages -
        //   https://kmhd.eu/archives/<post_id>
        // These predate the SvelteKit links.kmhd.eu/file/<id> system and
        // are used by old KatMovieHD posts (e.g. pre-2020 movies like
        // "Deadly Pickup 2016"). The page contains a flat list of mirror
        // links (acefile, drive.tv21, openload, mp4upload, etc.). We
        // fetch the page, scrape every external anchor, and re-dispatch
        // each through Cloudstream's stock extractor registry.
        if (Regex("""(?i)kmhd\.eu/archives/\d+""").containsMatchIn(url)) {
            handleArchivePage(url, subtitleCallback, callback)
            return
        }

        // Branch 2: SvelteKit /file/<id> or /play?id=<id> URLs (current
        // KatMovieHD format). Hit the page's /__data.json sidecar and
        // parse the dehydrated mirror map.
        handleSvelteKitFileOrPlay(url, subtitleCallback, callback)
    }

    /**
     * Legacy archive page handler. Pure HTML scrape + dispatch.
     *
     * Logs every URL we find for easy diagnostics, then fans them out
     * in parallel through Cloudstream's loadExtractor (which has built-in
     * support for most 2016-era hosts: openload, mp4upload, vidbob,
     * uptobox, clicknupload, userscloud, etc).
     */
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
        if (html.isBlank()) {
            Log.w(TAG, "Archive page returned empty body: $url")
            return
        }

        val doc = Jsoup.parse(html)
        val anchors = doc.select("a[href]")
            .mapNotNull { it.attr("href").trim().takeIf { h -> h.startsWith("http") } }
            .filter { href ->
                // Skip navigation, self-references, social media, image hosts,
                // and other obvious non-stream URLs. Anything else is a candidate.
                !ARCHIVE_IGNORE_REGEX.containsMatchIn(href)
            }
            .distinct()

        Log.d(TAG, "Archive page yielded ${anchors.size} candidate mirrors")
        if (anchors.isEmpty()) return

        anchors.amap { mirrorUrl ->
            dispatchArchiveMirror(url, mirrorUrl, subtitleCallback, callback)
        }
    }

    /**
     * Archive pages contain already-expanded mirror URLs. Some of KatMovieHD's
     * own legacy mirrors are redirector domains (gd.kmhd.eu / katdrive.eu)
     * that Cloudstream's generic loadExtractor will not recognise by prefix.
     * Route those through our known extractors first, then fall back to the
     * stock registry for normal hosts like send.cm / 1fichier / streamtape.
     */
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
                    val fileId = mirrorUrl.substringAfterLast("/")
                        .substringBefore("?")
                        .substringBefore("#")
                    val finalUrl = resolveFinalUrl(mirrorUrl)
                        ?.takeIf { it.contains("gdflix", ignoreCase = true) }
                        ?: "https://new18.gdflix.net/file/$fileId"

                    Log.d(TAG, "Archive GD-KMHD → GDFlix: $mirrorUrl -> $finalUrl")
                    GDFlixNet().getUrl(finalUrl, archivePageUrl, subtitleCallback, callback)
                }

                lower.contains("gdflix") || lower.contains("gd-flix") -> {
                    Log.d(TAG, "Archive GDFlix dispatch → $mirrorUrl")
                    GDFlix().getUrl(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }

                lower.contains("hubcloud.") || lower.contains("hubdrive") -> {
                    Log.d(TAG, "Archive HubCloud dispatch → $mirrorUrl")
                    HubCloud().getUrl(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }

                lower.contains("katdrive.") -> {
                    Log.d(TAG, "Archive KatDrive dispatch → $mirrorUrl")
                    handleKatdriveArchive(mirrorUrl, subtitleCallback, callback)
                }

                else -> {
                    Log.d(TAG, "Archive loadExtractor dispatch → $mirrorUrl")
                    loadExtractor(mirrorUrl, archivePageUrl, subtitleCallback, callback)
                }
            }
        }.onFailure {
            Log.w(TAG, "Archive mirror failed $mirrorUrl: ${it.message}")
        }
    }

    /** KatDrive pages usually expose a HubCloud /video/ link. */
    private suspend fun handleKatdriveArchive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to UA),
            timeout = 30
        ).document

        val hubUrl = doc.select("a[href]")
            .mapNotNull { a ->
                a.absUrl("href").ifBlank { a.attr("href") }
                    .takeIf { it.contains("hubcloud", ignoreCase = true) }
            }
            .firstOrNull()

        if (!hubUrl.isNullOrBlank()) {
            Log.d(TAG, "KatDrive → HubCloud: $hubUrl")
            HubCloud().getUrl(hubUrl, url, subtitleCallback, callback)
        } else {
            Log.w(TAG, "KatDrive page had no HubCloud link, falling back: $url")
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
    }

    /**
     * Current-format SvelteKit handler. Pulls the dehydrated mirror map
     * from /__data.json and dispatches each mirror via the per-host
     * helper below.
     */
    private suspend fun handleSvelteKitFileOrPlay(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val rawId = Regex("""/(?:file|play)[/?](?:id=)?([^/?&#]+)""")
                .find(url)?.groupValues?.get(1)?.trim()
            if (rawId.isNullOrBlank()) {
                Log.e(TAG, "Could not extract id from $url")
                return
            }

            // Some kmhd file IDs contain non-ASCII characters (e.g. the
            // Spanish-language release "Sueño_2df1145a" - note the ñ).
            // Concatenating those into a URL string raw makes OkHttp throw
            // (or silently send a corrupt request), which surfaces in the
            // app as a movie page with "no links". Percent-encoding the
            // id fixes it for every show we tested and is a no-op for
            // plain ASCII IDs. Letters/digits/"-_.~" pass through; space
            // (which URLEncoder turns into "+") becomes "%20".
            val id = java.net.URLEncoder.encode(rawId, "UTF-8").replace("+", "%20")

            val isPlay = url.contains("/play", ignoreCase = true)
            val path = if (isPlay) "/play?id=$id" else "/file/$id"
            val dataUrl = "$mainUrl$path/__data.json"

            Log.d(TAG, "Fetching: $dataUrl")

            val dataText = try {
                app.get(
                    dataUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Cookie" to "unlocked=true",
                        "Referer" to "$mainUrl$path",
                        "Accept" to "application/json"
                    ),
                    timeout = 30
                ).text
            } catch (e: Exception) {
                Log.e(TAG, "HTTP fetch failed: ${e.message}")
                return
            }

            Log.d(TAG, "Got dataText length: ${dataText.length}")

            if (dataText.isBlank()) {
                Log.w(TAG, "Empty response from $dataUrl")
                return
            }

            var mirrors = parseSvelteKitData(dataText)
            Log.d(TAG, "Parsed ${mirrors.size} mirrors: ${mirrors.map { it.host }}")

            // Robustness net: SvelteKit periodically changes its dehydrated
            // payload shape (e.g. switching the "chunk" wrapper, renaming the
            // "links"/"link" index keys). When that happens the structured
            // parser above silently returns 0 mirrors and the user sees
            // "no links". As a safety net we then scrape the RAW json text
            // for any known mirror host URL with a regex - it won't recover
            // the per-file ids the structured parser builds, but it does
            // recover fully-qualified mirror URLs that appear verbatim in
            // the payload, so playback keeps working through a format change.
            if (mirrors.isEmpty()) {
                val salvaged = salvageMirrorsByRegex(dataText)
                if (salvaged.isNotEmpty()) {
                    Log.w(TAG, "Structured parse found 0 mirrors; regex salvage recovered ${salvaged.size}")
                    mirrors = salvaged
                }
            }

            if (mirrors.isEmpty()) {
                Log.w(TAG, "No mirrors parsed from JSON for $url")
                return
            }

            mirrors.amap { mirror ->
                runCatching {
                    dispatchMirror(mirror, subtitleCallback, callback)
                }.onFailure {
                    Log.e(TAG, "Mirror ${mirror.host} dispatch failed: ${it.message}")
                }
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
        } else {
            mirror.url
        }

        Log.d(TAG, "Mirror ${mirror.host}: ${mirror.url} -> $finalUrl")

        when {
            finalUrl.contains("gdflix", ignoreCase = true) -> {
                Log.d(TAG, "Dispatching to GDFlix: $finalUrl")
                GDFlix().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            }
            finalUrl.contains("hubcloud", ignoreCase = true) -> {
                Log.d(TAG, "Dispatching to HubCloud: $finalUrl")
                HubCloud().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            }
            else -> {
                Log.d(TAG, "Dispatching to loadExtractor: $finalUrl")
                loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
            }
        }
    }

    private data class Mirror(val host: String, val url: String)

    /**
     * Fallback recovery: pull complete mirror URLs straight out of the raw
     * SvelteKit JSON text when the structured parser can't (format drift).
     * Only matches hosts we actually know how to extract, and strips the
     * advertiser/analytics domains the page injects (cathaytrash, al5sm,
     * catimages, so-gr3at3, etc.) so we never hand junk to an extractor.
     */
    private fun salvageMirrorsByRegex(rawJson: String): List<Mirror> {
        val urlRegex = Regex("""https?://[^\s"'\\<>]+""")
        return urlRegex.findAll(rawJson)
            .map { it.value.trimEnd('\\', ',', '"', '\'') }
            .filter { KNOWN_MIRROR_REGEX.containsMatchIn(it) }
            .filter { !AD_HOST_REGEX.containsMatchIn(it) }
            // A bare host root like "https://send.cm/" carries no file id and
            // is useless, so require something after the host.
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
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /**
         * The set of mirror hosts KatMovieHD actually serves inside its
         * file/__data.json (verified live against the current site):
         * gd.kmhd.eu, hubcloud, katdrive, send.cm, fuckingfast, 1fichier,
         * streamtape, hglink/streamwish. Used only by the regex salvage
         * fallback.
         */
        private val KNOWN_MIRROR_REGEX = Regex(
            """(?i)(gd\.kmhd|hubcloud|hubdrive|katdrive|send\.cm|""" +
                    """fuckingfast|1fichier|streamtape|hglink|streamwish|gdflix)"""
        )

        /** Advertiser / analytics hosts the page injects - never streams. */
        private val AD_HOST_REGEX = Regex(
            """(?i)(cathaytrash|al5sm|catimages|so-gr3at3|gstatic|google|""" +
                    """doubleclick|popads|propeller)"""
        )

        /**
         * Anchors on a legacy archives page that are NEVER stream sources -
         * site navigation, social, images, WP internals, etc. Anything
         * that doesn't match here is forwarded to loadExtractor.
         */
        private val ARCHIVE_IGNORE_REGEX = Regex(
            """(?i)(""" +
                    """^https?://(?:www\.)?kmhd\.eu(?:/|$)|""" +
                    """imdb\.com|themoviedb\.org|wikipedia|""" +
                    """youtube\.com|youtu\.be|""" +
                    """facebook\.com|twitter\.com|instagram\.com|t\.me|telegram\.|whatsapp\.|""" +
                    """pinterest\.|reddit\.com|""" +
                    """pichub|catimages|imgur|postimg|imgbox|""" +
                    """wp-content|wp-includes|wp-json|xmlrpc|""" +
                    """\.(?:png|jpe?g|gif|webp|svg|ico|css|woff2?)(?:\?|$)""" +
                    """)"""
        )
    }
}
