package com.uhdmovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UHDMoviesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(UHDMoviesProvider())
    }
}
