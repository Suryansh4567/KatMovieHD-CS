package com.lagradost

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/** CloudStream entry point for the KMMovies provider. */
@CloudstreamPlugin
class KMMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KMMovies())
    }
}
