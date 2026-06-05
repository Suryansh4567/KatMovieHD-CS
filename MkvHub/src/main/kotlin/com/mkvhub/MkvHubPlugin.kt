package com.mkvhub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MkvHubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MkvHubProvider())
    }
}
