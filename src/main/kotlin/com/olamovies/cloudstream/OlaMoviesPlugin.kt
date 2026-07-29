package com.olamovies.cloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OlaMoviesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(OlaMoviesProvider())
    }
}