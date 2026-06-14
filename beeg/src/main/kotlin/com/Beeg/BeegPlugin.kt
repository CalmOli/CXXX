package com.Beeg

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BeegProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Beeg())
    }
}
