package com.arena

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PagalMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PagalMoviesAlpha())
    }
}
