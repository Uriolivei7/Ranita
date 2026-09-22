package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MhdflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MhdflixProvider())
        registerExtractorAPI(ByseExtractor())
        registerExtractorAPI(MhdflixCubeembed())
        registerExtractorAPI(MhdflixVidHide())
        registerExtractorAPI(MhdflixStreamWish())
        registerExtractorAPI(MhdflixVoe())
        registerExtractorAPI(MhdflixVoeYipsu())
        registerExtractorAPI(MhdflixVoeDonald())
        registerExtractorAPI(MhdflixVoeCharles())
        registerExtractorAPI(MhdflixVoeTubeless())
        registerExtractorAPI(MhdflixVoeSimplum())
        registerExtractorAPI(MhdflixVoeUroch())
        registerExtractorAPI(MhdflixVoeNathan())
        registerExtractorAPI(MhdflixVoeMetagnath())
        registerExtractorAPI(MhdflixVoePamela())
        registerExtractorAPI(Sendvid())
    }
}