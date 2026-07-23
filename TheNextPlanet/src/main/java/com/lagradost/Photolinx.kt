package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * Photolinx (W4Cloudex) Extractor — resolves
 * `https://photolinx.beauty/download/<uid>` URLs to direct .mkv
 * stream URLs.
 *
 * Algorithm (verified end-to-end on the live server, 2026-07-23):
 *
 *   1. GET `/download/<uid>` with a real browser User-Agent
 *      → 200 OK, ~8KB HTML containing a `<section id="generate_url">`
 *      with `data-token` (server-encrypted base64) and `data-uid`
 *      (the same uid).
 *   2. POST `https://photolinx.beauty/action` with body
 *      `{"type":"DOWNLOAD_GENERATE","payload":{"uid":...,"access_token":...}}`,
 *      headers `Content-Type: application/json` +
 *      `X-Requested-With: XMLHttpRequest` + same UA + same session
 *      cookie + matching `Referer`.
 *   3. Response: `{"status":true,"download_url":"https://<cf-worker>/download/<uid>"}`
 *      (a Cloudflare Worker URL that serves the actual .mkv).
 *   4. Emit an `ExtractorLink` with the `download_url` as the source URL.
 *
 * Critical constraints (also verified live):
 *
 *   - The `access_token` is server-encrypted and bound to (session
 *     cookie, User-Agent, IP). Same UA must be used for GET and POST,
 *     otherwise the server returns `{"error":"Invalid access token"}`.
 *   - If the POST fails for ANY reason (network error, expired token,
 *     rate limit, etc.) we MUST skip the link cleanly — the brief is
 *     explicit that we must never emit a broken Source. We catch
 *     all throwables, log a `Log.w` with the failure reason, and
 *     return without calling the callback.
 *
 * Reference: see `TheNextPlanet/SOURCE_COVERAGE_AUDIT_PHASE5.md`
 * for the full probe data and the live response that informed
 * this implementation.
 */
class Photolinx : ExtractorApi() {
    override val name = "Photolinx"
    override val mainUrl = "https://photolinx.beauty"
    override val requiresReferer = false

    companion object {
        private const val TAG = "TheNextPlanet:Photolinx"

        // A real browser User-Agent. The server-side access_token is
        // bound to the UA, so we MUST send the same UA on the GET
        // and the POST.  Mismatched UA returns
        // `{"error":"Invalid access token"}`.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val ENDPOINT_ACTION = "https://photolinx.beauty/action"
        private const val DEBUG = false

        private fun dbg(msg: String) {
            if (DEBUG) android.util.Log.i(TAG, msg)
        }

        /**
         * Parse a Photolinx download URL like
         *   `https://photolinx.beauty/download/_fgDSPwWx8k`
         * and return the trailing `uid` (e.g. `_fgDSPwWx8k`), or
         * null if the URL is not a Photolinx download URL.
         *
         * Pure function so it can be unit-tested without network.
         */
        fun parseUid(url: String): String? {
            // Match the trailing /download/<uid> path. The uid is
            // the last path segment (alphanumeric, underscore,
            // hyphen — observed real uids like _fgDSPwWx8k and
            // adwrASfa1AS; this regex is permissive enough for
            // both).
            val match = Regex("""photolinx\.beauty/download/([A-Za-z0-9_-]+)""")
                .find(url)
            return match?.groupValues?.getOrNull(1)
        }

        /**
         * Parse the JSON response from POST /action and extract the
         * `download_url` field. Returns null on any parse error or
         * if the response doesn't carry a `download_url` (which
         * means the server returned an error or a non-success
         * payload).
         *
         * Pure function so it can be unit-tested with the real
         * probe response captured on 2026-07-23.
         *
         * Note: the Photolinx server returns `download_url` with
         * JSON-escaped forward slashes (`\/`), e.g.
         *   "https:\/\/winter-silence-9c49.ejohnsoncraig.workers.dev\/download\/_fgDSPwWx8k"
         * which is valid JSON.  We unescape `\/` → `/` here so the
         * emitted `ExtractorLink.url` is a real `https://...` URL
         * that ExoPlayer can resolve.  Without this unescape the
         * resulting URL would have literal backslashes in it and
         * the player would fail to load it.  This was caught by
         * JUnit test `photolinx_parseDownloadUrl_realSuccessResponse`.
         */
        fun parseDownloadUrl(responseBody: String): String? {
            // We hand-parse the JSON rather than pulling in a
            // JSON library — the response is small and the
            // pattern is well-known. A real response looks like:
            //   {"status":true,"download_url":"https:\/\/winter-silence..."}
            // An error looks like:
            //   {"error":"Invalid access token"}
            val match = Regex(""""download_url"\s*:\s*"([^"]+)"""")
                .find(responseBody)
                ?: return null
            val raw = match.groupValues[1]
            // Unescape JSON-escaped forward slashes (and any other
            // common escapes) so the result is a real URL.
            return raw.replace("\\/", "/").replace("\\\\", "\\").replace("\\\"", "\"")
        }

        /**
         * Manually parse the PHPSESSID from a Set-Cookie header.
         * Returns only the value part (e.g. "abc123xyz").
         */
        fun parseSetCookie(header: String?): String? {
            if (header == null) return null
            val match = Regex("""PHPSESSID=([^;]+)""").find(header)
            return match?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uid = parseUid(url)
        if (uid == null) {
            android.util.Log.w(
                TAG,
                "Skipping: URL does not match photolinx.beauty/download/<uid> pattern — url=$url"
            )
            return
        }

        // We intentionally use try/catch around the WHOLE flow
        // and never emit a broken Source on failure.  This is the
        // brief's explicit instruction: if /action fails, skip
        // cleanly, do not emit.
        try {
            // ── Step 1: GET the download page to extract the
            // server-encrypted data-token and the data-uid.
            // The token is bound to the (cookie, UA, IP) tuple,
            // so we must (a) send our fixed UA, (b) retain the
            // session cookie, (c) use the same UA in step 2.
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            )
            val phpsessid = parseSetCookie(pageResponse.headers["Set-Cookie"])
            dbg("[DBG] GET page done: phpsessid=$phpsessid")

            val doc = Jsoup.parse(pageResponse.text)

            val generateSection = doc.selectFirst("section#generate_url")
                ?: doc.selectFirst("#generate_url")
            if (generateSection == null) {
                android.util.Log.w(
                    TAG,
                    "Skipping: no #generate_url section in page — url=$url"
                )
                return
            }

            val token = generateSection.attr("data-token")
            val pageUid = generateSection.attr("data-uid")
            if (token.isBlank() || pageUid.isBlank()) {
                android.util.Log.w(
                    TAG,
                    "Skipping: empty data-token or data-uid — url=$url token='$token' uid='$pageUid'"
                )
                return
            }

            // ── Step 2: POST /action to get the download URL.
            // We use the same session cookies as the GET
            // (the `app` client doesn't auto-carry them, so we pass
            // explicitly), the same UA, and a Referer matching the GET URL.
            val postJson = mapOf(
                "type" to "DOWNLOAD_GENERATE",
                "payload" to mapOf(
                    "uid" to pageUid,
                    "access_token" to token
                )
            )
            val postResponse = app.post(
                ENDPOINT_ACTION,
                json = postJson,
                cookies = mapOf("PHPSESSID" to (phpsessid ?: "")),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                    "Origin" to "https://photolinx.beauty",
                    "Accept" to "application/json, text/plain, */*"
                )
            )

            val responseBody = postResponse.text
            dbg("[DBG] POST response: $responseBody")
            val downloadUrl = parseDownloadUrl(responseBody)
            if (downloadUrl == null) {
                // The server returned an error payload (e.g. invalid
                // token, rate limit, expired session). Log and
                // SKIP — do not emit a Source.
                android.util.Log.w(
                    TAG,
                    "Skipping: /action returned no download_url — response=$responseBody url=$url"
                )
                return
            }

            // ── Step 3: emit the ExtractorLink. The downloadUrl
            // is the final CF Worker URL that serves the .mkv
            // (or .mp4) directly. We do NOT set a Referer here
            // because the CF Worker doesn't validate it (it
            // serves the file).  Quality is left at Unknown
            // because the Photolinx page doesn't expose quality
            // info — the per-link resolution/codec comes from
            // the unlock-page <details> group which the calling
            // TheNextPlanet.loadLinks() code has already parsed
            // and passes in via `groupMeta`.  That groupMeta
            // integration lives in the calling code (see the
            // GDFlix branch in TheNextPlanet.resolveUrl() for
            // the same pattern).
            callback(
                newExtractorLink(
                    downloadUrl,  // source slot
                    downloadUrl,  // name slot
                    downloadUrl,  // url
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (t: Throwable) {
            // Defensive: ANY failure (network error, JSON parse
            // error, CF 5xx, anything) → skip with a clear log
            // line.  Never emit a broken Source.
            android.util.Log.w(
                TAG,
                "Skipping: extractor failed for $url: ${t.javaClass.simpleName}: ${t.message}"
            )
            return
        }
    }
}
