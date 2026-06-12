package com.Shameless

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShamelessPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shameless())
    }
}
