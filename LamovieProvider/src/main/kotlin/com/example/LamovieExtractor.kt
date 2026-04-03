package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val response = app.get(embedUrl, referer = "https://vimeos.net/")
        val res = response.text

        val unpackedJs = unpackJs(response.document) ?: res

        extractSubsFromUnpacked(unpackedJs, subtitleCallback)

        Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(unpackedJs)?.groupValues?.get(1)?.let { link ->
            val cleanLink = link.replace("\\/", "/")
            val finalLink = if (cleanLink.startsWith("http")) cleanLink else "https:$cleanLink"
            M3u8Helper.generateM3u8(this.name, finalLink, "$mainUrl/").forEach(callback)
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
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url)
        val res = response.text

        Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(res)?.groupValues?.get(1)?.let { link ->
            val cleanLink = link.replace("\\/", "/")
            val finalLink = if (cleanLink.startsWith("http")) cleanLink else "https:$cleanLink"

            M3u8Helper.generateM3u8(this.name, finalLink, "$mainUrl/").forEach(callback)
        }
    }
}

private suspend fun invokeSubtitle(label: String, url: String, callback: (SubtitleFile) -> Unit) {
    val subFile = newSubtitleFile(label, url) {
        this.headers = mapOf(
            "Referer" to "https://vimeos.net/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Brave\";v=\"146\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )
    }
    Log.d("LaMovie", "LOG: Registrando Sub -> $label: $url")
    callback.invoke(subFile)
}