package com.Laidhub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LaidhubPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Laidhub())
    }
}
