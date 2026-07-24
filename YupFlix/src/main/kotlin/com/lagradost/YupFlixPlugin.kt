package com.lagradost

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YupFlixPlugin : BasePlugin() {
    override fun load() {
        // YupFlix resolves direct HLS streams from its own JSON API — no
        // per-source extractor sub-classes are required.
        registerMainAPI(YupFlix())
    }
}
