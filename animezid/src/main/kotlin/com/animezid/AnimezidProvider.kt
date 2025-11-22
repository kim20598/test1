package com.animezid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimezidProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Animezid())
    }
}