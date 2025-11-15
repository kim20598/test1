package com.movizland

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovizlandProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movizland())
    }
}
