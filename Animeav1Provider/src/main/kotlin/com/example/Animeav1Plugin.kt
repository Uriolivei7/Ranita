package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Animeav1Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Animeav1())
        registerExtractorAPI(Animeav1upn())
        registerExtractorAPI(Zilla())
    }
}