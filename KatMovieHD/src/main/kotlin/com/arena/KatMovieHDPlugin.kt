package com.arena

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Entry point. CloudStream calls [load] once when the plugin is installed.
 *
 * We register:
 *  - The main provider that scrapes the KatMovieHD WordPress site.
 *  - A custom extractor that knows how to bypass the `links.kmhd.eu`
 *    "click to unlock" interstitial via SvelteKit's `__data.json` endpoint
 *    (no UI interaction required — pure HTTP).
 */
@CloudstreamPlugin
class KatMovieHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KatMovieHDProvider())
        registerExtractorAPI(KmhdExtractor())
    }
}
