package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SeriesdonghuaPlugin: Plugin() {
    override fun load(context: Context) {
        SeriesdonghuaProvider.pluginContext = context
        registerMainAPI(SeriesdonghuaProvider())
        registerExtractorAPI(RumbleExtractor())
    }
}
