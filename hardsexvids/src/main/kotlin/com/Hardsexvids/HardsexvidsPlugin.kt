package com.Hardsexvids

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HardsexvidsPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hardsexvids())
    }
}
