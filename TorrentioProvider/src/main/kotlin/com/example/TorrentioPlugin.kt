package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI

@CloudstreamPlugin
class TorrentioPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TorrentioProvider())
    }
}
