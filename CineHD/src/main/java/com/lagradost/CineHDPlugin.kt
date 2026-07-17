package com.lagradost

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CineHD())
    }
}
