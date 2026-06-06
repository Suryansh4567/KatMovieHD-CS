package com.moviescounter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesCounterPlugin : Plugin() {
    override fun load() {
        // Register the main provider
        registerMainAPI(MoviesCounterProvider())

        // Register custom extractors — same pattern as phisher98/HDhub4u
        // These integrate with CloudStream's extractor framework for proper
        // error handling, timeouts, and playback support.
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HdStream4u())
    }
}
