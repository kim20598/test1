package com.moviztime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovizTimeProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovizTime())
    }
}