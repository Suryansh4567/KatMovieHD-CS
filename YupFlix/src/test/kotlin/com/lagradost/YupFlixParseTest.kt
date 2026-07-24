package com.lagradost

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Offline, fixture-driven JUnit tests for YupFlix.
 *
 * Fixtures in src/test/resources are REAL responses captured live from
 * jolly-mouse-f41c.annierane.workers.dev on 2026-07-24.
 *
 * Run with: ./gradlew :YupFlix:test
 */
class YupFlixParseTest {

    // parseSearch / parseHomepage build SearchResponse objects via
    // newMovieSearchResponse, which needs a MainAPI receiver, so they are
    // instance methods and exercised through a real YupFlix() instance.
    private val yf = YupFlix()

    private fun res(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(name)
            ?: throw IllegalStateException("missing test resource: $name")
        return stream.bufferedReader(StandardCharsets.UTF_8).readText()
    }

    @Test
    fun search_parsesBothMoviesAndSeries() {
        val json = res("search.json")
        val tree = ObjectMapper().readTree(json)
        val movies = tree.get("movies")?.size() ?: 0
        val series = tree.get("series")?.size() ?: 0
        val actors = tree.get("actors")?.size() ?: 0

        val result = yf.parseSearch(json)

        // actors must NOT leak into results (CS3 doesn't render person cards)
        assertEquals("actors must be excluded from search results", movies + series, result.size)
        assertTrue("should contain at least one movie", result.any { it.type == TvType.Movie })
        assertTrue("should contain at least one series", result.any { it.type == TvType.TvSeries })
        assertTrue("no blank titles", result.none { it.name.isBlank() })
        // sanity: we actually parsed something
        assertTrue("non-empty result", result.isNotEmpty())
    }

    @Test
    fun movieDetail_parsesStreamingLinks() {
        val d = YupFlix.parseMovieDetail(res("movie_detail.json"))
        assertEquals("The Strangers", d.title)
        assertEquals(2, d.streamingLinks.size)

        val links = runBlocking { YupFlix.toExtractorLinks(d.streamingLinks) }
        assertEquals(2, links.size)
        assertTrue("all links are HLS", links.all { it.type == ExtractorLinkType.M3U8 })

        val p720 = links.first { it.quality == Qualities.P720.value }
        assertTrue("720p url carries the expected CDN path", p720.url.contains("142c39b165ec"))
        assertEquals(ExtractorLinkType.M3U8, p720.type)

        val p480 = links.first { it.quality == Qualities.P480.value }
        assertTrue("480p url carries the expected CDN path", p480.url.contains("14993572c573"))
    }

    @Test
    fun movieDetail_respectsIsActiveFlag() {
        val links = listOf(
            StreamingLink(quality = "720p", url = "https://cdn/x/720.m3u8", type = "hls", isActive = false),
            StreamingLink(quality = "480p", url = "https://cdn/x/480.m3u8", type = "hls", isActive = true),
        )
        val out = runBlocking { YupFlix.toExtractorLinks(links) }
        assertEquals(1, out.size)
        assertEquals("https://cdn/x/480.m3u8", out[0].url)
    }

    @Test
    fun movieDetail_skipsDrmAndEmbed() {
        val links = listOf(
            StreamingLink(quality = "720p", url = "https://cdn/x/a.m3u8", type = "hls", drm = true),
            StreamingLink(quality = "480p", url = "https://cdn/x/b.m3u8", type = "hls", embed = true),
        )
        val out = runBlocking { YupFlix.toExtractorLinks(links) }
        assertEquals(0, out.size)
    }

    @Test
    fun seriesDetail_flattensEpisodes() {
        val d = YupFlix.parseSeriesDetail(res("series_detail.json"))
        assertEquals("Stranger", d.title)
        assertEquals("episode count should equal sum across seasons", 16, d.episodes.size)

        val first = d.episodes[0]
        assertEquals(1, first.season)
        assertEquals(1, first.episode)
        assertTrue("first episode has streaming links", first.streamingLinks.isNotEmpty())
        assertEquals(ExtractorLinkType.M3U8, runBlocking { YupFlix.toExtractorLinks(first.streamingLinks) }[0].type)
    }

    @Test
    fun homepage_mapsSections() {
        val lists = yf.parseHomepage(res("home_sec.json"))
        assertTrue("at least one section produced", lists.isNotEmpty())
        assertTrue("every produced list has items", lists.all { it.list.isNotEmpty() })

        // count non-empty sections directly in the raw fixture
        val tree = ObjectMapper().readTree(res("home_sec.json"))
        val nonEmpty = tree.get("data")?.count {
            (it.get("items")?.size() ?: 0) > 0
        } ?: 0
        assertEquals("produced lists == non-empty sections in fixture", nonEmpty, lists.size)
    }

    @Test
    fun urlParser_extractsIdFromDetailUrl() {
        val r = YupFlix.extractId("https://watch.yupflix.org/detail/movie/6a452eda5f5543dc5c4d42bf")
        assertEquals("movie", r?.first)
        assertEquals("6a452eda5f5543dc5c4d42bf", r?.second)
    }

    @Test
    fun urlParser_extractsIdFromWatchUrl() {
        val r = YupFlix.extractId("https://watch.yupflix.org/watch/series/6a4d547d5f5543dc5c807e3b")
        assertEquals("series", r?.first)
        assertEquals("6a4d547d5f5543dc5c807e3b", r?.second)
    }

    @Test
    fun urlParser_returnsNullOnGarbage() {
        assertNull(YupFlix.extractId("https://example.com/"))
        assertNull(YupFlix.extractId("https://watch.yupflix.org/detail/movie/notahexid"))
        assertNull(YupFlix.extractId("https://watch.yupflix.org/watch/foo/12345"))
        assertNull(YupFlix.extractId(""))
    }

    // FIX D4: retryOn429 must retry on 429 then return the first non-429
    // response, and must exhaust (return null) after `times` retries.
    @Test
    fun retryOn429_retriesThenSucceeds() = runBlocking {
        val seq = listOf(
            RateLimitedResponse(429, "rate limited #1"),
            RateLimitedResponse(429, "rate limited #2"),
            RateLimitedResponse(200, "real body"),
        )
        var calls = 0
        val r = YupFlix.retryOn429(times = 2, delay = {}) { attempt -> calls++; seq[attempt] }
        assertEquals("should surface the 200 after two 429s", 200, r?.code)
        assertEquals("body should be the 200 response", "real body", r?.body)
        assertEquals("must have attempted all three times", 3, calls)
    }
}
