package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class RumbleExtractor : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://donghualife.com/"),
            "Accept-Language" to "es-ES,es;q=0.9"
        )

        val response = app.get(url, headers = headers)
        val html = response.text.replace("\\/", "/")

        val hlsRegex = Regex("""https?://rumble\.com/hls-vod/[^"']+playlist\.m3u8""")
        val hlsUrl = hlsRegex.find(html)?.value

        if (hlsUrl != null) {
            callback.invoke(
                newExtractorLink(this.name, this.name, hlsUrl) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = url
                }
            )
        }
    }
}