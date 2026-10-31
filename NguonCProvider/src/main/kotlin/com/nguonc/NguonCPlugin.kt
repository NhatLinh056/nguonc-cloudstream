package com.nguonc

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NguonCPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NguonCProvider())
    }
}

