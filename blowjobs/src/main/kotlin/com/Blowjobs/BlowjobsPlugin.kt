package com.Blowjobs

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BlowjobsPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Blowjobs())
    }
}
