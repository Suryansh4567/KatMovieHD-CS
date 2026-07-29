package com.olamovies.cloudstream

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.*
import org.junit.Test

class ExtractorTest {

    @Test
    fun `quality parser works correctly`() {
        assertEquals(Qualities.P1080.value, OlaMoviesParserUtils.parseQuality("1080p [2.92GB]"))
        assertEquals(Qualities.P720.value, OlaMoviesParserUtils.parseQuality("720p"))
        assertEquals(Qualities.P2160.value, OlaMoviesParserUtils.parseQuality("2160p 4K"))
        assertEquals(Qualities.Unknown.value, OlaMoviesParserUtils.parseQuality("Unknown quality"))
    }

    @Test
    fun `gdrive link extraction does not crash on empty`() {
        val links = OlaMoviesParserUtils.extractGoogleDriveLinks(
            org.jsoup.Jsoup.parse("<html><body></body></html>")
        )
        assertTrue(links.isEmpty())
    }
}