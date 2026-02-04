package com.example

import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HenaojaraPlugin: Plugin() {
    override fun load() {
        registerMainAPI(HenaojaraProvider())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(Voe())
    }
}