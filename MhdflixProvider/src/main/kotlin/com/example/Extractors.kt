package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import okhttp3.FormBody

class ByseExtractor : ExtractorApi() {
    override var mainUrl = "https://bysedikamoum.com"
    override var name = "Byse"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("SoloLatino", "[Byse] URL: $url")

        val videoId = Regex("""/e/(\w+)""").find(url)?.groupValues?.get(1) ?: return

        try {
            val apiRes = app.post(
                "$mainUrl/api/source",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to url,
                    "Origin" to mainUrl,
                ),
                referer = url,
                requestBody = FormBody.Builder()
                    .add("r", "")
                    .add("s", videoId)
                    .build()
            )
            Log.d("SoloLatino", "[Byse] API response: ${apiRes.text}")

            val fileUrl = Regex("""file["']\s*:\s*["']([^"']+)["']""")
                .find(apiRes.text)?.groupValues?.get(1)
                ?: Regex("""url["']\s*:\s*["']([^"']+)["']""")
                    .find(apiRes.text)?.groupValues?.get(1)
                ?: return

            callback.invoke(
                newExtractorLink(name, name, fileUrl) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        } catch (e: Exception) {
            Log.e("SoloLatino", "[Byse] Error: ${e.message}")
        }
    }
}

class MhdflixCubeembed : VidStack() {
    override var name = "Cubeembed"
    override var mainUrl = "https://cubeembed.rpmvid.com"
}

class Sendvid : ExtractorApi() {
    override var name = "Sendvid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = false
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val urlString = doc.select("head meta[property=og:video:secure_url]").attr("content")
        if (urlString.contains("m3u8")) {
            generateM3u8(
                name,
                urlString,
                mainUrl,
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = urlString,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
