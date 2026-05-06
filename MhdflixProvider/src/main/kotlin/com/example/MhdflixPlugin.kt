package com.example

import android.content.Context
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MhdflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MhdflixProvider())
        registerExtractorAPI(ByseExtractor())
        registerExtractorAPI(MhdflixCubeembed())
    }
}

class MhdflixCubeembed : VidStack() {
    override var name = "Cubeembed"
    override var mainUrl = "https://cubeembed.rpmvid.com"
}
