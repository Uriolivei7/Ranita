package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

private val extractorHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "Accept" to "*/*",
    "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Brave\";v=\"146\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\"",
    "sec-fetch-dest" to "empty",
    "sec-fetch-mode" to "cors",
    "sec-fetch-site" to "same-site",
    "sec-gpc" to "1"
)

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
        val embedUrl = getEmbedUrl(url)
        Log.d("LaMovie", "Vimeos: Obteniendo embed URL -> $embedUrl")

        val response = app.get(embedUrl, headers = extractorHeaders, referer = "https://vimeos.net/")
        Log.d("LaMovie", "Vimeos: Status ${response.code}")
        val res = response.text

        val unpackedJs = unpackJs(response.document) ?: res

        extractSubsFromUnpacked(unpackedJs, subtitleCallback)

        val m3u8Match = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(unpackedJs)
        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1]
            val cleanLink = link.replace("\\/", "/")
            val finalLink = if (cleanLink.startsWith("http")) cleanLink else "https:$cleanLink"
            Log.d("LaMovie", "Vimeos: M3U8 encontrado -> ${finalLink.take(100)}...")

            callback.invoke(
                newExtractorLink(
                    this.name, this.name,
                    finalLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://vimeos.net/"
                    this.quality = Qualities.Unknown.value
                    this.headers = extractorHeaders + mapOf("Referer" to "https://vimeos.net/")
                }
            )
        } else {
            Log.e("LaMovie", "Vimeos: M3U8 NO encontrado en respuesta")
        }
    }

    private fun unpackJs(document: org.jsoup.nodes.Document): String? {
        return document.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }
            ?.data()?.let { getAndUnpack(it) }
    }

    private fun getEmbedUrl(url: String): String {
        return if (!url.contains("/embed-")) "$mainUrl/embed-${url.substringAfterLast("/")}" else url
    }

    private suspend fun extractSubsFromUnpacked(
        js: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val vttRegex = Regex("""["'](https?://[^"']+\.vtt)["']""")
        vttRegex.findAll(js).forEach { match ->
            val rawUrl = match.groupValues[1].replace("\\/", "/")
            Log.d("LaMovie", "Logs: VTT encontrado en JS desempaquetado -> $rawUrl")

            val label = when {
                rawUrl.endsWith("_spa.vtt") -> "Spanish"
                rawUrl.endsWith("_sli.vtt") -> "Spanish (Subtitles)"
                rawUrl.endsWith("_eng.vtt") -> "English"
                else -> "Subtitle"
            }

            invokeSubtitle(label, rawUrl, subtitleCallback)
        }
    }
}

class GoodstreamExtractor : ExtractorApi() {
    override var name = "Goodstream"
    override val mainUrl = "https://goodstream.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("LaMovie", "Goodstream: Iniciando con URL -> $url")

        val pageRes = app.get(url, headers = extractorHeaders, referer = "https://la.movie/")
        Log.d("LaMovie", "Goodstream: Status ${pageRes.code}")
        val res = pageRes.text

        val m3u8Match = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(res)
        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1]
            val cleanLink = link.replace("\\/", "/")
            val finalLink = if (cleanLink.startsWith("http")) cleanLink else "https:$cleanLink"
            Log.d("LaMovie", "Goodstream: M3U8 encontrado -> ${finalLink.take(100)}...")

            callback.invoke(
                newExtractorLink(
                    this.name, this.name,
                    finalLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://la.movie/"
                    this.quality = Qualities.Unknown.value
                    this.headers = extractorHeaders + mapOf("Referer" to "https://la.movie/")
                }
            )
        } else {
            Log.e("LaMovie", "Goodstream: M3U8 NO encontrado en respuesta")
        }
    }
}

private suspend fun invokeSubtitle(label: String, url: String, callback: (SubtitleFile) -> Unit) {
    val subFile = newSubtitleFile(label, url) {
        this.headers = extractorHeaders + mapOf("Referer" to "https://vimeos.net/")
    }
    Log.d("LaMovie", "LOG: Registrando Sub -> $label: $url")
    callback.invoke(subFile)
}