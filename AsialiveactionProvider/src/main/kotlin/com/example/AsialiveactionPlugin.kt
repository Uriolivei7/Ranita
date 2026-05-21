package com.example

import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.OkRuSSL
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AsialiveactionPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AsialiveactionProvider())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(MomoEzplayer())
        registerExtractorAPI(SuUpnOne())
        registerExtractorAPI(MoaRpmvip())
        registerExtractorAPI(Bysejikuar())
    }
}
