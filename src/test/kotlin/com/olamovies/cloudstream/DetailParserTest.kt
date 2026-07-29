package com.olamovies.cloudstream

import org.junit.Assert.*
import org.junit.Test

class DetailParserTest {

    @Test
    fun `movie detail parses correctly from fixture`() {
        val doc = Fixtures.loadDocument("movie.html")
        val load = OlaMoviesParserUtils.parseMovieLoadResponse(doc, "https://v3.olamovies.mov/iron-man-3-2013/", OlaMoviesConstants.BASE_URL)

        assertNotNull(load)
        assertTrue(load!!.name.contains("Iron Man 3"))
        assertTrue(load.posterUrl?.contains("Iron-Man-3") == true || load.posterUrl?.contains("uploads") == true)
    }

    @Test
    fun `series detail parses with multiple episodes from fixture`() {
        val doc = Fixtures.loadDocument("series.html")
        val load = OlaMoviesParserUtils.parseTvSeriesLoadResponse(doc, "https://v3.olamovies.mov/musafir-cafe-2026/", OlaMoviesConstants.BASE_URL)

        assertNotNull(load)
        assertTrue(load!!.name.contains("Musafir Cafe", ignoreCase = true))
        
        // FIX 3: Should now detect multiple episodes
        assertTrue("Should have multiple episodes", load.episodes.size > 1)
        assertTrue(load.episodes.any { it.episode >= 1 && it.episode <= 8 })
        assertEquals(1, load.episodes.first().season)
    }

    @Test
    fun `extracts download links including episodes and packs`() {
        val doc = Fixtures.loadDocument("series.html")
        val links = OlaMoviesParserUtils.extractDownloadLinks(doc)
        
        assertTrue("Should find episode links", links.any { it.first.lowercase().contains("episode") })
        assertTrue("Should find quality packs", links.any { it.first.contains("1080p") || it.first.contains("720p") })
    }
}