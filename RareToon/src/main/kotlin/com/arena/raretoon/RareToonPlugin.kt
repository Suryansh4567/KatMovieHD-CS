package com.arena.raretoon

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RareToonPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RareToonIndiaProvider())
        registerExtractorAPI(ByseKozE())
    }
}
