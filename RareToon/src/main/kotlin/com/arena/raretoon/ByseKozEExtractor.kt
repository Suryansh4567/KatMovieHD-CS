package com.arena.raretoon

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor for bysekoze.com.
 *
 * The site returns a JSON object containing an encrypted "playback" blob. The blob holds
 * an AES-256-GCM ciphertext plus 30 base64url key parts. The "version" field selects two
 * of those parts (version and 31-version) which, concatenated, form the 32-byte key.
 */
class ByseKozE : ExtractorApi() {
    override val name = "ByseKozE"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = extractCode(url) ?: run {
                Log.e(TAG, "Could not extract bysekoze code from $url")
                return
            }
            val apiUrl = "$mainUrl/api/videos/$code"
            val response = app.get(
                apiUrl,
                headers = headers,
                referer = referer ?: url,
                timeout = 20
            )
            if (!response.isSuccessful) {
                Log.e(TAG, "ByseKozE API returned ${response.code} for $code")
                return
            }

            val json = JSONObject(response.text)
            val title = json.optString("title").ifBlank { code }
            val playback = json.optJSONObject("playback") ?: run {
                Log.e(TAG, "No playback object for $code")
                return
            }

            val decrypted = decryptPlayback(playback) ?: run {
                Log.e(TAG, "Failed to decrypt playback for $code")
                return
            }

            emitSubtitles(decrypted, subtitleCallback)
            emitSubtitles(json, subtitleCallback)

            val sources = decrypted.optJSONArray("sources") ?: run {
                Log.e(TAG, "No sources in decrypted playback for $code")
                return
            }

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val streamUrl = source.optString("url")
                if (streamUrl.isBlank()) continue

                val label = source.optString("label").ifBlank { source.optString("quality") }
                val height = source.optInt("height", 0)
                val quality = when {
                    height > 0 -> height
                    label.contains("1080", true) -> 1080
                    label.contains("720", true) -> 720
                    label.contains("480", true) -> 480
                    label.contains("360", true) -> 360
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${label.ifBlank { title }}",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.referer = referer ?: mainUrl
                        // Some CDN edge nodes return 404 if no User-Agent is present.
                        this.headers = mapOf("User-Agent" to headers["User-Agent"].orEmpty())
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $url: ${e.message}")
        }
    }

    private fun extractCode(url: String): String? = Regex(
        """(?i)bysekoze\.[a-z]+/(?:d|e|download|dwn)/([a-z0-9]+)"""
    ).find(url)?.groupValues?.getOrNull(1)

    private suspend fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracks = json.optJSONArray("tracks") ?: return
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val url = track.optString("url").ifBlank { track.optString("file") }
            if (url.isBlank()) continue
            val lang = track.optString("language")
                .ifBlank { track.optString("title") }
                .ifBlank { "Subtitle" }
            subtitleCallback.invoke(newSubtitleFile(lang, url))
        }
    }

    private fun decryptPlayback(playback: JSONObject): JSONObject? {
        val parts = playback.optJSONArray("key_parts") ?: return null
        val version = playback.optString("version")
        val selected = keyIndexes(version, parts.length()).ifEmpty { (1..parts.length()).toList() }
        val key = selected.flatMap { idx ->
            val part = parts.optString(idx - 1)
            if (part.isBlank()) emptyList() else b64url(part).toList()
        }.toByteArray()
        if (key.size != 32) {
            Log.e(TAG, "Invalid key size: ${key.size}")
            return null
        }

        val iv = b64url(playback.optString("iv"))
        val payload = b64url(playback.optString("payload"))
        if (iv.isEmpty() || payload.size <= 16) {
            Log.e(TAG, "Invalid IV or payload")
            return null
        }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(payload)
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM decryption failed: ${e.message}")
            null
        }
    }

    private fun keyIndexes(version: String, size: Int): List<Int> {
        val n = version.trim().toIntOrNull() ?: return emptyList()
        if (n !in 1..30) return emptyList()
        val indexes = listOf(n, 31 - n)
        return indexes.filter { it in 1..size }
    }

    private fun b64url(input: String): ByteArray {
        if (input.isBlank()) return ByteArray(0)
        return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "ByseKozE"
    }
}
