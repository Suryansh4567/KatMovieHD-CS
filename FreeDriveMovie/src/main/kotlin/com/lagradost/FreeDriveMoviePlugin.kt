package com.lagradost

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Plugin entry point for the FreeDriveMovie provider.
 *
 * The site is a Dooplay (WordPress) install whose primary content is offered as
 * "download links". [FreeDriveMovie] resolves that link chain to direct-playable
 * MKV/MP4 mirrors, so no separate per-host extractor sub-classes are registered.
 */
@CloudstreamPlugin
class FreeDriveMoviePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FreeDriveMovie())
    }
}
