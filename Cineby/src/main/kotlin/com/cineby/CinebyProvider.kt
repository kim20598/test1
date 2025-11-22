package com.cineby

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinebyProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cineby())
    }
}