package com.Shyfap

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShyfapPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shyfap())
    }
}
