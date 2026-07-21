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
}
