package com.Xxxtube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XxxtubePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xxxtube())
    }
}
