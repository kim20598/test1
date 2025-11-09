package com.fajrshow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FajrshowProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Fajrshow())
    }
}