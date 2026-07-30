package com.olamovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class OlaMoviesPlugin: BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(OlaMoviesProvider())
    }
}
