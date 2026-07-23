package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-source label-format helpers added to TheNextPlanet.
 * Covers:
 *   - parseGroupSummary()   — unlock-page <summary> text → LinkMeta
 *   - bucketToMeta()        — /get-doods bucket name → LinkMeta
 *   - mergeMeta()           — rich primary over sparse fallback
 *   - buildLabel()          — final "SourceName • res • print • lang • codec • size" format
 *
 * These run as a regular JVM unit test (not on-device) via
 *   ./gradlew :TheNextPlanet:testDebugUnitTest
 *
 * The companion-object nested types ([LinkMeta], [parseGroupSummary] etc.)
 * are accessed via `TheNextPlanet.Companion.X` because in this CS3
 * version, the cloudstream3 `cloudstream` configuration is not on the
 * test classpath.  We therefore only test pure-Kotlin helpers — the
 * heavy DOM/HTTP work is exercised separately in the smoke test
 * documented in the follow-up report.
 */
class TheNextPlanetLabelTest {

    // ─── parseGroupSummary ─────────────────────────────────────────────

    @Test fun parseGroupSummary_720pHEVC() {
        val m = TheNextPlanet.parseGroupSummary("720p HEVC Download Links")
        assertEquals("720p", m.resolution)
        assertEquals("HEVC", m.print)
    }

    @Test fun parseGroupSummary_1080p() {
        val m = TheNextPlanet.parseGroupSummary("1080p Download Links")
        assertEquals("1080p", m.resolution)
        assertEquals(null, m.print)
    }

    @Test fun parseGroupSummary_480p() {
        val m = TheNextPlanet.parseGroupSummary("480p Download Links")
        assertEquals("480p", m.resolution)
    }

    @Test fun parseGroupSummary_empty() {
        val m = TheNextPlanet.parseGroupSummary("")
        assertEquals(null, m.resolution)
        assertEquals(null, m.print)
        assertEquals(null, m.language)
    }

    @Test fun parseGroupSummary_null() {
        val m = TheNextPlanet.parseGroupSummary(null)
        assertEquals(null, m.resolution)
    }

    @Test fun parseGroupSummary_4kNormalised() {
        val m = TheNextPlanet.parseGroupSummary("4k Download Links")
        assertEquals("2160p", m.resolution)
    }

    @Test fun parseGroupSummary_WEB_DL() {
        val m = TheNextPlanet.parseGroupSummary("720p WEB-DL Download Links")
        assertEquals("720p", m.resolution)
        assertEquals("WEB-DL", m.print)
    }

    @Test fun parseGroupSummary_dualAudio() {
        val m = TheNextPlanet.parseGroupSummary("720p Dual Audio")
        assertEquals("720p", m.resolution)
        assertEquals("Dual Audio", m.language)
    }

    // ─── bucketToMeta ──────────────────────────────────────────────────

    @Test fun bucketToMeta_FHD() {
        val m = TheNextPlanet.bucketToMeta("FHD")
        assertEquals("1080p", m.resolution)
    }

    @Test fun bucketToMeta_HEVC() {
        val m = TheNextPlanet.bucketToMeta("HEVC")
        assertEquals("HEVC", m.print)
        assertEquals("x265", m.codec)
    }

    @Test fun bucketToMeta_LQ() {
        val m = TheNextPlanet.bucketToMeta("LQ")
        assertEquals(null, m.resolution)
        assertEquals(null, m.print)
    }

    @Test fun bucketToMeta_HD() {
        val m = TheNextPlanet.bucketToMeta("HD")
        assertEquals("720p", m.resolution)
    }

    // ─── mergeMeta ─────────────────────────────────────────────────────

    @Test fun mergeMeta_primaryWins() {
        val primary = TheNextPlanet.Companion.LinkMeta(
            resolution = "720p", print = "WEB-DL",
            codec = "x265", language = "Dual Audio", size = "843MB"
        )
        val fallback = TheNextPlanet.Companion.LinkMeta(
            resolution = "1080p", print = "HEVC", language = "Hindi"
        )
        val merged = TheNextPlanet.mergeMeta(primary, fallback)
        assertEquals("720p", merged.resolution)   // primary wins
        assertEquals("WEB-DL", merged.print)      // primary wins
        assertEquals("x265", merged.codec)
        assertEquals("Dual Audio", merged.language)
        assertEquals("843MB", merged.size)
    }

    @Test fun mergeMeta_fallbackFillsNulls() {
        val primary = TheNextPlanet.Companion.LinkMeta()  // all null
        val fallback = TheNextPlanet.Companion.LinkMeta(
            resolution = "720p", print = "HEVC"
        )
        val merged = TheNextPlanet.mergeMeta(primary, fallback)
        assertEquals("720p", merged.resolution)
        assertEquals("HEVC", merged.print)
    }

    @Test fun mergeMeta_nullFallback() {
        val primary = TheNextPlanet.Companion.LinkMeta(resolution = "1080p")
        val merged = TheNextPlanet.mergeMeta(primary, null)
        assertEquals("1080p", merged.resolution)
    }

    // ─── buildLabel ────────────────────────────────────────────────────

    @Test fun buildLabel_mediafireInGroup() {
        val m = TheNextPlanet.parseGroupSummary("720p HEVC Download Links")
        val label = TheNextPlanet.buildLabel("TheNextPlanet [Mediafire]", m)
        assertEquals("TheNextPlanet [Mediafire] • 720p • HEVC", label)
    }

    @Test fun buildLabel_photolinxNoGroup() {
        val m = TheNextPlanet.Companion.LinkMeta()
        val label = TheNextPlanet.buildLabel("TheNextPlanet [Photolinx]", m)
        assertEquals("TheNextPlanet [Photolinx]", label)
    }

    @Test fun buildLabel_fastilinks480p() {
        val m = TheNextPlanet.parseGroupSummary("480p Download Links")
        val label = TheNextPlanet.buildLabel("TheNextPlanet [Fastilinks]", m)
        assertEquals("TheNextPlanet [Fastilinks] • 480p", label)
    }

    @Test fun buildLabel_watchOnlineHEVC() {
        val m = TheNextPlanet.bucketToMeta("HEVC")
        val label = TheNextPlanet.buildLabel("TheNextPlanet [Watch Online]", m)
        // bucketToMeta("HEVC") -> print=HEVC, codec=x265, in that order
        assertEquals("TheNextPlanet [Watch Online] • HEVC • x265", label)
    }

    @Test fun buildLabel_watchOnlineFHD() {
        val m = TheNextPlanet.bucketToMeta("FHD")
        val label = TheNextPlanet.buildLabel("TheNextPlanet [Watch Online]", m)
        assertEquals("TheNextPlanet [Watch Online] • 1080p", label)
    }

    // ─── parseFilename (existing) ──────────────────────────────────────

    @Test fun parseFilename_kantara_GDFlixNameIsRich() {
        val name = "Kantara.2022.720p.HEVC.[Hin-Kan].WEB-DL._World4ufree.Com.mkv"
        val frag = TheNextPlanet.parseFilename(name)
        // The GDFlix-side filename has 720p, WEB-DL, Dual Audio (in brackets -> "hin-kan" -> not "dual" but
        // actually there's no "dual" string in the filename; the language detection is on bare keywords).
        // Just assert it's non-empty and contains the resolution.
        assertTrue("parseFilename should be non-empty for GDFlix slug: got '$frag'",
            frag.isNotBlank())
        assertTrue("parseFilename should contain 720p: got '$frag'", frag.contains("720p"))
        assertTrue("parseFilename should contain WEB-DL: got '$frag'", frag.contains("WEB-DL"))
    }

    @Test fun parseFilename_kantara_mediafireSlugIsEmpty() {
        // The Mediafire URL slug `Kntra72pHV_World4ufree.com.mkv` has no
        // parseable quality/print info — confirms the per-source labelling
        // problem this fix addresses.
        val name = "Kntra72pHV_World4ufree.com.mkv"
        val frag = TheNextPlanet.parseFilename(name)
        assertEquals("", frag)
    }

    // ─── shouldSkip (v4 audit follow-up) ───────────────────────────────
    //
    // The SOURCE_COVERAGE_AUDIT.md identified 5 hosters the plugin
    // deliberately does not support (Gap A/B/C).  These tests pin down
    // the shouldSkip() decisions so a future refactor can't accidentally
    // re-enable a broken code path (e.g. fastmkv.sbs emitting a Source
    // with a non-stream wrapper URL, or filepress.* silently falling
    // through to loadExtractor() and back).
    //
    // Live sample URLs (from /tmp/audit/unlock_2441_from-s01.html and
    // /tmp/audit/unlock_v3_3063.html — 22-title crawl, 2026-07-22):

    @Test fun shouldSkip_fastmkv_sbs() {
        val reason = TheNextPlanet.shouldSkip("https://fastmkv.sbs/file/giigof5dyjxmlhp")
        assertTrue("fastmkv.sbs must be skipped (Gap B): got $reason", reason != null)
        assertTrue("reason must mention the host: got $reason", reason!!.contains("fastmkv.sbs"))
    }

    @Test fun shouldSkip_gdtot_dad() {
        val reason = TheNextPlanet.shouldSkip("https://new6.gdtot.dad/file/1929787440")
        assertTrue("gdtot.dad must be skipped (Gap B): got $reason", reason != null)
        assertTrue("reason must mention the host: got $reason", reason!!.contains("gdtot.dad"))
    }

    @Test fun shouldSkip_fastilinks() {
        val reason = TheNextPlanet.shouldSkip("https://fastilinks.beauty/view/XGE5epQEAS")
        assertTrue("fastilinks.beauty must be skipped (Gap A): got $reason", reason != null)
        assertTrue("reason must mention the host: got $reason", reason!!.contains("fastilinks.beauty"))
    }

    @Test fun shouldSkip_filepress_subdomains() {
        // All three observed subdomains must be caught by a single
        // "filepress" substring check (the new2.filepress.baby one
        // surfaced via the CF challenge cZone field on filepress.wiki).
        val cloud = TheNextPlanet.shouldSkip("https://new4.filepress.cloud/file/6966957f7587707fbfa6e677")
        val wiki  = TheNextPlanet.shouldSkip("https://new1.filepress.wiki/file/699bf8de4b1ecb5acf533121")
        val baby  = TheNextPlanet.shouldSkip("https://new2.filepress.baby/file/abc123")
        assertTrue("filepress.cloud must be skipped (Gap C)",  cloud != null)
        assertTrue("filepress.wiki must be skipped (Gap C)",   wiki  != null)
        assertTrue("filepress.baby must be skipped (Gap C)",   baby  != null)
        assertTrue(cloud!!.contains("filepress"))
        assertTrue(wiki!!.contains("filepress"))
        assertTrue(baby!!.contains("filepress"))
    }

    // v5: photolinx is NO LONGER in the skip list — it now has a
    // working extractor (Photolinx.kt).  This test pins that decision
    // so a future refactor can't re-introduce the skip and break
    // the v5 working-source path.

    @Test fun shouldSkip_photolinx_NOT_skipped() {
        val reason = TheNextPlanet.shouldSkip("https://photolinx.beauty/download/_fgDSPwWx8k")
        assertEquals("photolinx.beauty must NOT be skipped (v5 added a working extractor)", null, reason)
    }

    // Negative cases — hosters we DO support must not be skipped:

    @Test fun shouldSkip_mediafire_passes() {
        val reason = TheNextPlanet.shouldSkip("https://www.mediafire.com/file/abc123/Kntra72pHV.mkv")
        assertEquals("mediafire.com must NOT be skipped (CS3 built-in extractor)", null, reason)
    }

    @Test fun shouldSkip_gdflix_passes() {
        val reason = TheNextPlanet.shouldSkip("https://gdflix.dev/file/uqsNtqApN62085U")
        assertEquals("gdflix.dev must NOT be skipped (our custom GDFlix class)", null, reason)
    }

    @Test fun shouldSkip_voe_passes() {
        val reason = TheNextPlanet.shouldSkip("https://voe.sx/e/abc123")
        assertEquals("voe.sx must NOT be skipped (CS3 built-in Voe extractor)", null, reason)
    }

    @Test fun shouldSkip_vidhide_passes() {
        val reason = TheNextPlanet.shouldSkip("https://vidhide.com/v/abc123")
        assertEquals("vidhide.com must NOT be skipped (CS3 built-in VidhideExtractor)", null, reason)
    }

    @Test fun shouldSkip_substringDoesNotMatchUnrelated() {
        // Regression guard: a "contains" check could in theory match a
        // host like "notphotolinx.com" or "fakefastmkv.io".  The current
        // implementation uses the full ".<tld>" boundary via the
        // substring "photolinx.beauty" / "fastmkv.sbs" / "gdtot.dad" /
        // "fastilinks.beauty" / "filepress" — verify the negatives stay
        // unskipped.
        val reason = TheNextPlanet.shouldSkip("https://notphotolinx.com/foo")
        assertEquals("unrelated 'notphotolinx.com' must NOT be skipped", null, reason)
        val reason2 = TheNextPlanet.shouldSkip("https://my-fastmkv-mirror.io/foo")
        assertEquals("unrelated 'my-fastmkv-mirror.io' must NOT be skipped", null, reason2)
    }

    // ─── Photolinx extractor (v5) ──────────────────────────────────────
    //
    // The two pure functions below are the parsing primitives the
    // Photolinx extractor uses; they are tested with real data captured
    // on 2026-07-23 from the live server (see
    // TheNextPlanet/SOURCE_COVERAGE_AUDIT_PHASE5.md).

    @Test fun photolinx_parseUid_realUrl() {
        // The URL format is /download/<uid>.  Real observed uids:
        //   _fgDSPwWx8k   (Starman 480p)
        //   adwrASfa1AS   (Starman 720p)
        //   tzfG-c-TL6G   (earlier audit sample)
        //   qEXQkTWT-eg   (Starman 1080p)
        val u1 = Photolinx.parseUid("https://photolinx.beauty/download/_fgDSPwWx8k")
        assertEquals("_fgDSPwWx8k", u1)
        val u2 = Photolinx.parseUid("https://photolinx.beauty/download/adwrASfa1AS")
        assertEquals("adwrASfa1AS", u2)
        val u3 = Photolinx.parseUid("https://photolinx.beauty/download/tzfG-c-TL6G")
        assertEquals("tzfG-c-TL6G", u3)
        val u4 = Photolinx.parseUid("https://photolinx.beauty/download/qEXQkTWT-eg")
        assertEquals("qEXQkTWT-eg", u4)
    }

    @Test fun photolinx_parseUid_nonMatchingUrl() {
        // Non-photolinx URLs must return null so the extractor's
        // getUrl() early-returns and logs a Skipping warning.
        assertEquals(null, Photolinx.parseUid("https://example.com/foo/bar"))
        assertEquals(null, Photolinx.parseUid("https://photolinx.beauty/"))
        assertEquals(null, Photolinx.parseUid("https://photolinx.beauty/download/"))  // no uid segment
        assertEquals(null, Photolinx.parseUid(""))
    }

    @Test fun photolinx_parseDownloadUrl_realSuccessResponse() {
        // Captured live on 2026-07-23 from the Starman photolinx link.
        val response = """{"status":true,"download_url":"https:\/\/winter-silence-9c49.ejohnsoncraig.workers.dev\/download\/_fgDSPwWx8k"}"""
        val result = Photolinx.parseDownloadUrl(response)
        assertEquals(
            "https://winter-silence-9c49.ejohnsoncraig.workers.dev/download/_fgDSPwWx8k",
            result
        )
    }

    @Test fun photolinx_parseDownloadUrl_realErrorResponse() {
        // Captured live: when the User-Agent doesn't match between GET
        // and POST, the server returns this error payload.  We must
        // NOT extract a download_url from this; parseDownloadUrl()
        // must return null so the extractor skips cleanly.
        val response = """{"error":"Invalid access token"}"""
        val result = Photolinx.parseDownloadUrl(response)
        assertEquals(null, result)
    }

    @Test fun photolinx_parseDownloadUrl_malformed() {
        // Garbage / empty responses must return null, not crash.
        assertEquals(null, Photolinx.parseDownloadUrl(""))
        assertEquals(null, Photolinx.parseDownloadUrl("not json at all"))
        assertEquals(null, Photolinx.parseDownloadUrl("{}"))
        // A response with status:false and no download_url must
        // return null too.
        assertEquals(null, Photolinx.parseDownloadUrl("""{"status":false,"message":"something"}"""))
    }

    @Test fun photolinx_parseSetCookie_simple() {
        val header = "PHPSESSID=abc123xyz"
        val result = Photolinx.parseSetCookie(header)
        assertEquals("abc123xyz", result)
    }

    @Test fun photolinx_parseSetCookie_complex() {
        val header = "PHPSESSID=abc123xyz; expires=Thu, 23-Jul-2026 12:00:00 GMT; path=/; domain=photolinx.beauty; HttpOnly; SameSite=Lax"
        val result = Photolinx.parseSetCookie(header)
        assertEquals("abc123xyz", result)
    }

    @Test fun photolinx_parseSetCookie_missing() {
        val header = "other_cookie=123"
        val result = Photolinx.parseSetCookie(header)
        assertEquals(null, result)
        assertEquals(null, Photolinx.parseSetCookie(null))
    }
}
