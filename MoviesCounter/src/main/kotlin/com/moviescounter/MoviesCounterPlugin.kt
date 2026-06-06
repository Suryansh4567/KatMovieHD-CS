package com.moviescounter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesCounterPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MoviesCounterProvider())

        // Register custom extractors for the Hubdrive → HubCloud → CDN chain
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HdStream4u())
    }
}
