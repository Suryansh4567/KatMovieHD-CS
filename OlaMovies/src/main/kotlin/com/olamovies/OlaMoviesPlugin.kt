package com.olamovies
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class OlaMoviesPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(OlaMoviesProvider())
    }
}
