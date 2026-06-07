package com.moviescounter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesCounterPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MoviesCounterProvider())

        // Register custom extractors — mainUrl must match actual URL domains
        // so CloudStream's loadExtractor() can auto-route URLs
        registerExtractorAPI(Hubdrive())      // mainUrl = hubdrive.space
        registerExtractorAPI(HubCloud())      // mainUrl = hubcloud.foo
        registerExtractorAPI(HUBCDN())        // mainUrl = hubcdn.sbs
        registerExtractorAPI(Mclinks())       // mainUrl = mclinks.xyz
        registerExtractorAPI(Hblinks())       // mainUrl = hblinks.dad
        registerExtractorAPI(HdStream4u())    // mainUrl = hdstream4u.com
    }
}
