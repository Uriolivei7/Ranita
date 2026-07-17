package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SendvidExtractor : ExtractorApi() {
    override val name = "Sendvid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RetrotveProvider", "Sendvid-START: $url")
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
            )
            
            val embedUrl = if (url.contains("/embed/")) url else url.replace("/?", "/embed/?")
            val response = app.get(embedUrl, headers = headers, timeout = 30L)
            val pageHtml = response.text
            
            Log.d("RetrotveProvider", "Sendvid: code=${response.code}, len=${pageHtml.length}")
            
            val patterns = listOf(
                """video_url\s*:\s*["']([^"']+)["']""".toRegex(),
                """file\s*:\s*["']([^"']+\.mp4[^"']*)["']""".toRegex(),
                """src\s*=\s*["']([^"']+\.mp4[^"']*)["']""".toRegex(),
                """https?://[^"'\s]+\.mp4[^"'\s]*""".toRegex()
            )
            
            for (pattern in patterns) {
                val match = pattern.find(pageHtml)
                if (match != null) {
                    val videoUrl = match.groupValues.getOrNull(1)?.replace("\\/", "/") 
                        ?: match.value.replace("\\/", "/")
                    if (videoUrl.startsWith("http")) {
                        Log.d("RetrotveProvider", "Sendvid: Found $videoUrl")
                        callback.invoke(
                            newExtractorLink(name, name, videoUrl) {
                                this.referer = embedUrl
                                this.type = ExtractorLinkType.VIDEO
                            }
                        )
                        return
                    }
                }
            }
            Log.d("RetrotveProvider", "Sendvid: No URL found")
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "Sendvid: Error ${e.message}")
        }
    }
}
