package com.arena

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException

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

            val activeBase = getBaseUrl(url).trimEnd('/')
            val isPlay = url.contains("/play", ignoreCase = true)
            // /play routes use query params (?id=X), so __data.json sits at
            // /play/__data.json?id=X — NOT /play?id=X/__data.json (which
            // returns the HTML app shell, not JSON). /file routes use the
            // conventional /file/<id>/__data.json path.
            val pageReferer = if (isPlay) "$activeBase/play?id=$id" else "$activeBase/file/$id"
            val dataUrl = if (isPlay) {
                "$activeBase/play/__data.json?id=$id"
            } else {
                "$pageReferer/__data.json"
            }

            Log.d(TAG, "Fetching: $dataUrl")

            val dataText = try {
                app.get(
                    dataUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Cookie" to "unlocked=true",
                        "Referer" to pageReferer,
                        "Accept" to "application/json"
                    ),
                    timeout = 30
                ).text
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                // Diagnostic dump: include a payload preview so when this
                // happens in the wild we can tell at a glance whether the
                // site is returning the locked/CF gate page (looks like
                // HTML) or whether SvelteKit changed its payload schema
                // (looks like JSON but unfamiliar shape).
                val preview = dataText.take(200).replace("\n", "\\n")
                Log.w(TAG, "No mirrors parsed from JSON for $url. " +
                    "Payload preview (${dataText.length} bytes): $preview")
                if (dataText.contains("locked", ignoreCase = true) ||
                    dataText.contains("redirect", ignoreCase = true)) {
                    Log.w(TAG, "Looks like the 'unlocked=true' cookie was " +
                        "rejected by the site (locked/redirect gate hit). " +
                        "Check that CloudStream's HTTP client is sending the " +
                        "Cookie header and not stripping it through CF DNS.")
                }
                return
            }

            mirrors.amap { mirror ->
                runCatching {
                    dispatchMirror(mirror, pageReferer, subtitleCallback, callback)
                }.onFailure {
                    Log.e(TAG, "Mirror ${mirror.host} dispatch failed: ${it.message}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "KMHD extractor outer failure for $url: ${e.message}")
        }
    }

    private suspend fun dispatchMirror(
        mirror: Mirror,
        pageReferer: String,
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
            finalUrl.contains("gdflix", ignoreCase = true) ||
            finalUrl.contains("gdlink", ignoreCase = true) -> {
                Log.d(TAG, "Dispatching to GDFlix: $finalUrl")
                GDFlix().getUrl(finalUrl, pageReferer, subtitleCallback, callback)
            }
            finalUrl.contains("hubcloud", ignoreCase = true) -> {
                Log.d(TAG, "Dispatching to HubCloud: $finalUrl")
                HubCloud().getUrl(finalUrl, pageReferer, subtitleCallback, callback)
            }
            else -> {
                Log.d(TAG, "Dispatching to loadExtractor: $finalUrl")
                loadExtractor(finalUrl, pageReferer, subtitleCallback, callback)
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

        // Collect ALL chunk data arrays. SvelteKit payloads can have
        // multiple chunk lines — /play pages have 2: one carrying the
        // resolver tokens (upload_links), another carrying the links
        // map with base URLs. We must search across all of them.
        val allChunks = mutableListOf<JSONArray>()
        for (line in dataText.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (obj.optString("type") != "chunk") continue
            val arr = obj.optJSONArray("data") ?: continue
            allChunks.add(arr)
        }
        if (allChunks.isEmpty()) return emptyList()

        val seenUrls = mutableSetOf<String>()

        for (arr in allChunks) {
            val root = arr.optJSONObject(0) ?: continue

            // Build the list of links-map candidates for THIS chunk:
            //
            //   /file format: root = {"val":1, "links":12, ...}
            //     → root["links"] is an index into data[] pointing to the
            //       actual links map ({"gdflix_res":13, "hubdrive_res":17,...})
            //
            //   /play format: root = {"gdflix_res":1, "hubdrive_res":5,...}
            //     → root IS the links map directly (no "links" wrapper).
            //       Detect by checking if any key ends with "_res".
            val linksMapCandidates = mutableListOf<JSONObject>()

            // /file: follow the "links" index
            root.optInt("links", -1).takeIf { it > 0 }?.let { linksIdx ->
                (arr.opt(linksIdx) as? JSONObject)?.let { linksMapCandidates.add(it) }
            }

            // /play: root itself has *_res keys
            val hasResKeys = root.keys().asSequence().any { it.endsWith("_res") }
            if (hasResKeys) linksMapCandidates.add(root)

            for (linksMap in linksMapCandidates) {
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

                    // Token may live in a DIFFERENT chunk than the base
                    // URL (e.g. /play puts tokens in chunk[0], base URLs
                    // in chunk[1]). Search across ALL collected chunks.
                    val appendId = findAppendIdAcrossChunks(allChunks, key)
                    if (appendId.isNullOrBlank()) continue

                    val fullUrl = baseUrl + appendId
                    if (fullUrl in seenUrls) continue
                    seenUrls.add(fullUrl)
                    mirrors.add(Mirror(host = key, url = fullUrl))
                }
            }
        }
        return mirrors.distinctBy { it.url }
    }

    /**
     * Search ALL chunk data arrays for a resolver node containing `key`,
     * then return the token string it points to.
     *
     * On /play pages the tokens live in a separate chunk from the links
     * map (chunk[0] has {"name":2, "streamtape_res":5, ...}, chunk[1] has
     * the base URLs). The old single-array findAppendId() would miss
     * cross-chunk tokens entirely, returning 0 mirrors for /play.
     */
    private fun findAppendIdAcrossChunks(chunks: List<JSONArray>, key: String): String? {
        for (arr in chunks) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) as? JSONObject ?: continue
                if (!item.has(key)) continue
                val resIdx = item.optInt(key, -1)
                if (resIdx <= 0) continue
                val token = arr.opt(resIdx) as? String ?: continue
                if (token.equals("None", ignoreCase = true) || token.isBlank()) continue
                return token
            }
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
                    """fuckingfast|1fichier|streamtape|hglink|streamwish|gdflix|gdlink|gofile|vgembed|bbupload)"""
        )

        /** Advertiser / analytics hosts the page injects - never streams. */
        private val AD_HOST_REGEX = Regex(
            """(?i)(cathaytrash|al5sm|catimages|so-gr3at3|gstatic|google|""" +
                    """doubleclick|popads|propeller|vifix)"""
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
