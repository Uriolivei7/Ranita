package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatinoProvider())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(VoeYipsu())
        registerExtractorAPI(VoeDonaldlineelse())
        registerExtractorAPI(VoeCharlestoughrace())
        registerExtractorAPI(VoeTubeless())
        registerExtractorAPI(VoeSimplum())
        registerExtractorAPI(VoeUroch())
        registerExtractorAPI(VoeNathan())
        registerExtractorAPI(VoeMetagnath())
        registerExtractorAPI(VoePamelachangemission())
        registerExtractorAPI(SoloStreamWish())
        registerExtractorAPI(SoloVidHide())
        registerExtractorAPI(SoloFileMoon())
    }
}
