package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PlushdPlugin: Plugin() {
    override fun load(context: Context) {
        PlushdProvider.pluginContext = context.applicationContext
        registerMainAPI(PlushdProvider())
        registerExtractorAPI(PelisplusUpnsPro())
        registerExtractorAPI(PelisplusUpnsPro2())
        registerExtractorAPI(PelisplusUpnsPro3())
        registerExtractorAPI(PelisplusRpmstreamLive())
    }
}