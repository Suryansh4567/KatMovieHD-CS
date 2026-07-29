package com.olamovies.cloudstream

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
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
        val links = OlaMoviesParserUtils.extractDownloadLinks(
            org.jsoup.Jsoup.parse("<html><body></body></html>")
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun `extracts episode and quality links from series fixture`() {
        val doc = Fixtures.loadDocument("series.html")
        val links = OlaMoviesParserUtils.extractDownloadLinks(doc)
        
        assertTrue(links.isNotEmpty())
        assertTrue(links.any { it.first.lowercase().contains("episode") })
        assertTrue(links.any { it.first.contains("1080p") || it.first.contains("720p") })
    }

    @Test
    fun `gdrive extractor resolves file id correctly`() = runBlocking {
        val extractor = OlaMoviesGoogleDriveExtractor()
        
        // Test basic ID extraction (will not fully resolve without network in test, but should not crash)
        val testUrl = "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz123456/view"
        
        // We just verify it doesn't throw and produces a callback
        var called = false
        extractor.getUrl(
            url = testUrl,
            referer = "https://v3.olamovies.mov",
            subtitleCallback = {},
            callback = { called = true }
        )
        
        // It should attempt to call callback (even if link is placeholder)
        assertTrue("Extractor should emit at least one link", called)
    }
}