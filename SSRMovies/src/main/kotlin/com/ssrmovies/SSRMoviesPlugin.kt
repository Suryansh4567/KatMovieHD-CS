package com.ssrmovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SSRMoviesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SSRMoviesProvider())
    }
}
