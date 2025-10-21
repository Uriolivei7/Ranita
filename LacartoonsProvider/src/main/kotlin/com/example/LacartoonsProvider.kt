package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.net.URI
import org.jsoup.nodes.Document
import android.util.Base64 // Importar Base64 de Android
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class LacartoonsProvider : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun logLongString(tag: String, message: String) {
        val chunkSize = 4000
        var i = 0
        while (i < message.length) {
            val endIndex = min(i + chunkSize, message.length)
            println("$tag: ${message.substring(i, endIndex)}")
            i += chunkSize
        }
    }
    private fun Document.toSearchResult(): List<SearchResponse> {
        return this.select(".categorias .conjuntos-series a").map {
            val title = it.selectFirst("p.nombre-serie")?.text()
            val href = fixUrl(it.attr("href"))
            val img = fixUrl(it.selectFirst("img")!!.attr("src"))
            newTvSeriesSearchResponse(title!!, href) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(mainUrl).document
        val home = soup.toSearchResult()
        items.add(HomePageList("Series", home))
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?utf8=✓&Titulo=$query").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2.text-center")?.text()
        val description =
            doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()
                ?.substringAfter("Reseña:")?.trim()
        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")
        val episodes = doc.select("ul.listas-de-episodion li").map {
            val regexep = Regex("Capitulo.(\\d+)|Capitulo.(\\d+)\\-")
            val href = it.selectFirst("a")?.attr("href")
            val name = it.selectFirst("a")?.text()?.replace(regexep, "")?.replace("-", "")
            val seasonnum = href?.substringAfter("t=")
            val epnum = regexep.find(name.toString())?.destructured?.component1()

            val actualEpnum = epnum?.toIntOrNull()

            newEpisode(fixUrl(href!!)) {
                this.name = name
                this.season = seasonnum?.toIntOrNull()
                this.episode = actualEpnum
                this.runTime = null
            }
        }

        val recommendations = doc.select("div.series-recomendadas a").mapNotNull { element ->
            val recTitle = element.selectFirst("p.nombre-serie")?.text()
            val recLink = element.attr("href")
            val recImg = element.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recImg != null) {
                newTvSeriesSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.posterUrl = fixUrl(recImg)
                    this.type = TvType.Cartoon
                }
            } else {
                null
            }
        }

        return newTvSeriesLoadResponse(title!!, url, TvType.Cartoon, episodes) {
            this.posterUrl = fixUrl(poster!!)
            this.backgroundPosterUrl = fixUrl(backposter!!)
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst(".serie-video-informacion iframe")?.attr("src")

        if (iframeSrc == null) {
            println("${name}: No se encontró iframe para el episodio: $data")
            return false
        }

        if (iframeSrc.contains("sendvid.com/embed/")) {
            println("${name}: Detectado iframe de sendvid.com. Intentando extracción manual.")
            val sendvidEmbedUrl = iframeSrc

            val embedDoc = try {
                app.get(sendvidEmbedUrl).document
            } catch (e: Exception) {
                println("${name}: SENDVID_ERROR - No se pudo obtener el documento del embed de Sendvid: $sendvidEmbedUrl. ${e.message}")
                return false
            }

            val videoUrl = embedDoc.selectFirst("video#video-js-video source#video_source")?.attr("src")
                ?: embedDoc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: embedDoc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")

            if (!videoUrl.isNullOrBlank()) {
                println("${name}: SENDVID_SUCCESS - URL de video encontrada para Sendvid: $videoUrl")
                callback.invoke(
                    ExtractorLink(
                        source = "Sendvid",
                        name = "Sendvid",
                        url = videoUrl,
                        referer = sendvidEmbedUrl,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = mapOf("User-Agent" to "Mozilla/50 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                    )
                )
                return true
            } else {
                println("${name}: SENDVID_WARN - No se encontró la URL del video en la página de Sendvid: $sendvidEmbedUrl")
                return false
            }
        }

        else if (iframeSrc.contains("dhtpre.com")) {
            println("${name}: Detectado iframe de dhtpre.com, usando loadExtractor.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } else {
            println("${name}: Tipo de iframe desconocido: $iframeSrc. Intentando con loadExtractor por defecto.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
    }
}