package com.olamovies.cloudstream

import org.junit.Assert.*
import org.junit.Test

class SearchParserTest {

    @Test
    fun `search cards parse from fixture`() {
        val doc = Fixtures.loadDocument("search.html")
        val results = OlaMoviesParserUtils.parseSearchCards(doc, OlaMoviesConstants.BASE_URL)
        
        assertTrue("Should have results", results.isNotEmpty())
        assertTrue("Results should have urls", results.all { it.url.startsWith("http") })
        assertTrue("Results should have titles", results.none { it.name.isBlank() })
        assertTrue("At least 5 results expected", results.size >= 5)
    }

    @Test
    fun `homepage cards parse correctly`() {
        val doc = Fixtures.loadDocument("home.html")
        val results = OlaMoviesParserUtils.parseSearchCards(doc, OlaMoviesConstants.BASE_URL)
        
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name.contains("Iron Man", ignoreCase = true) || it.url.contains("iron-man") })
    }

    @Test
    fun `relative urls resolve to absolute`() {
        val doc = Fixtures.loadDocument("home.html")
        val results = OlaMoviesParserUtils.parseSearchCards(doc, OlaMoviesConstants.BASE_URL)
        assertTrue(results.all { it.url.startsWith("https://") })
    }
}