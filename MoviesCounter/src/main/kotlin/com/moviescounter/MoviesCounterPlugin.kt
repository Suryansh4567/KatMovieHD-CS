package com.moviescounter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesCounterPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MoviesCounterProvider())

        // Register custom extractors — mainUrl matches actual URL domains
        // so loadExtractor() can route URLs correctly
        registerExtractorAPI(Hubdrive())      // mainUrl = hubdrive.space
        registerExtractorAPI(HubCloud())      // mainUrl = hubcloud.foo
        registerExtractorAPI(HUBCDN())        // mainUrl = hubcdn.fans
        registerExtractorAPI(Mclinks())       // mainUrl = mclinks.xyz
        registerExtractorAPI(Hblinks())       // mainUrl = hblinks.dad
        registerExtractorAPI(HdStream4u())    // mainUrl = hdstream4u.com
    }
}
