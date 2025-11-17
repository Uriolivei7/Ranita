package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PelisplushdPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Pelisplushd())
        /*
        registerExtractorAPI(FileMoonlink())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(StreamwishHG())
        */
    }
}