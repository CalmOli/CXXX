package com.Javbangers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavbangersPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Javbangers())
    }
}
