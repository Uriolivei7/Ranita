package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Vimeos : ExtractorApi() {
    override val name = "Vimeos"
    override val mainUrl = "https://vimeos.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (!url.contains("/embed-")) "$mainUrl/embed-${url.substringAfterLast("/")}" else url
        Log.i("LaMovie", "LOG: Cargando Vimeos -> $embedUrl")

        val res = app.get(embedUrl, referer = "https://la.movie/").text
        val unpackedJs = JsUnpacker(res).unpack() ?: res

        val videoUrl = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)
        val subData = Regex("""["']([^"']+\.vtt[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)

        subData?.split(",")?.forEach { rawSub ->
            try {
                val langLabel = Regex("""\[([^\]]+)\]""").find(rawSub)?.groupValues?.get(1) ?: "Español"
                val cleanSubUrl = rawSub.substringAfter("]").trim()
                if (cleanSubUrl.startsWith("http")) {
                    subtitleCallback.invoke(newSubtitleFile(langLabel, cleanSubUrl))
                }
            } catch (e: Exception) { }
        }

        videoUrl?.let { m3u8 ->
            Log.i("LaMovie", "LOG: Generando enlaces fluidos para Vimeos...")

            val headerMap = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            M3u8Helper.generateM3u8(
                this.name,
                fixUrl(m3u8),
                "$mainUrl/",
                headers = headerMap
            ).forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        link.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = link.quality
                        this.referer = "$mainUrl/"
                        this.headers = headerMap
                    }
                )
            }
        }
    }
}

class GoodstreamExtractor : ExtractorApi() {
    override var name = "Goodstream"
    override val mainUrl = "https://goodstream.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i("LaMovie", "LOG: Extrayendo de Goodstream -> $url")
        val res = app.get(url, referer = "https://la.movie/").text

        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)

        m3u8?.let { link ->
            Log.i("LaMovie", "LOG: Generando enlaces fluidos para Goodstream")

            val headerMap = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )

            M3u8Helper.generateM3u8(
                this.name,
                fixUrl(link),
                "$mainUrl/",
                headers = headerMap
            ).forEach { m3uLink ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        m3uLink.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = m3uLink.quality
                        this.referer = "$mainUrl/"
                        this.headers = headerMap
                    }
                )
            }
        }
    }
}