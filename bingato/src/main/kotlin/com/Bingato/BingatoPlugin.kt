package com.Bingato

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BingatoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Bingato())
    }
}
