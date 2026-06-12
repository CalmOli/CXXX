package com.Neporn

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NepornPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Neporn())
    }
}
