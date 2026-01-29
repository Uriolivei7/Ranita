package com.example

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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
        Log.i("LaMovie", "LOG: Cargando Vimeos -> $embedUrl")

        val res = app.get(embedUrl, referer = "https://la.movie/")
        val doc = res.document
        val unpackedJs = unpackJs(doc) ?: ""

        val videoUrl = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)
        val subData = Regex("""["']([^"']+\.vtt[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)

        if (!subData.isNullOrBlank()) {
            subData.split(",").forEach { rawSub ->
                try {
                    val langLabel = Regex("""\[([^\]]+)\]""").find(rawSub)?.groupValues?.get(1) ?: "Español"
                    val cleanSubUrl = if (rawSub.contains("]")) rawSub.substringAfter("]").trim() else rawSub.trim()
                    if (cleanSubUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile(lang = langLabel, url = cleanSubUrl))
                    }
                } catch (e: Exception) { }
            }
        }

        if (videoUrl != null) {
            Log.i("LaMovie", "LOG: Generando enlaces fluidos para Vimeos...")
            M3u8Helper.generateM3u8(
                this.name,
                fixUrl(videoUrl),
                "$mainUrl/",
            ).forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = link.quality
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
            }
        }
    }

    private fun unpackJs(doc: Document): String? {
        val script = doc.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }
        return if (script != null) getAndUnpack(script.data()) else doc.select("script").joinToString("\n") { it.data() }
    }

    private fun getEmbedUrl(url: String): String {
        return if (!url.contains("/embed-")) "$mainUrl/embed-${url.substringAfterLast("/")}" else url
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
        val res = app.get(url)
        val doc = res.document

        doc.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("file:")) {
                val urlRegex = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                urlRegex.find(data)?.groupValues?.get(1)?.let { link ->
                    Log.i("LaMovie", "LOG: Generando enlaces fluidos para Goodstream")
                    M3u8Helper.generateM3u8(
                        this.name,
                        fixUrl(link),
                        "$mainUrl/",
                    ).forEach { m3uLink ->
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = m3uLink.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = m3uLink.quality
                                this.referer = "$mainUrl/"
                                this.headers = mapOf("Origin" to mainUrl)
                            }
                        )
                    }
                }
            }
        }
    }
}