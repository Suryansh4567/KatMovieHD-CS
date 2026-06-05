package com.moviescounter

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesCounterPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MoviesCounterProvider())
    }
}
