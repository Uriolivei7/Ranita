package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TvporinternetProvider : MainAPI() {
    override var mainUrl = "https://www.tvporinternet2.com"
    override var name = "TvporInternet"

    override val supportedTypes = setOf(
        TvType.Live
    )

    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()
    private val nowAllowed = listOf("Red Social", "Donacion")

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        additionalHeaders: Map<String, String>? = null,
        referer: String? = null
    ): String? {
        val requestHeaders = (additionalHeaders ?: emptyMap()).toMutableMap()
        if (!requestHeaders.containsKey("User-Agent")) {
            requestHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            Log.d("TvporInternet", "safeAppGet - Añadido User-Agent por defecto: ${requestHeaders["User-Agent"]}")
        } else {
            Log.d("TvporInternet", "safeAppGet - Usando User-Agent proporcionado: ${requestHeaders["User-Agent"]}")
        }
        if (!referer.isNullOrBlank() && !requestHeaders.containsKey("Referer")) {
            requestHeaders["Referer"] = referer
            Log.d("TvporInternet", "safeAppGet - Añadido Referer: ${requestHeaders["Referer"]}")
        }

        for (i in 0 until retries) {
            try {
                Log.d("TvporInternet", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                if (res.isSuccessful) {
                    Log.d("TvporInternet", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("TvporInternet", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("TvporInternet", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                delay(delayMs)
            }
        }
        Log.e("TvporInternet", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    private fun extractBestSrcset(srcsetAttr: String?): String? {
        if (srcsetAttr.isNullOrBlank()) return null
        val sources = srcsetAttr.split(",").map { it.trim().split(" ") }
        var bestUrl: String? = null
        var bestMetric = 0

        for (source in sources) {
            if (source.size >= 2) {
                val currentUrl = source[0]
                val descriptor = source[1]
                val widthMatch = Regex("""(\d+)w""").find(descriptor)
                val densityMatch = Regex("""(\d+)x""").find(descriptor)

                if (widthMatch != null) {
                    val width = widthMatch.groupValues[1].toIntOrNull()
                    if (width != null && width > bestMetric) {
                        bestMetric = width
                        bestUrl = currentUrl
                    }
                } else if (densityMatch != null) {
                    val density = densityMatch.groupValues[1].toIntOrNull()
                    if (density != null && density * 100 > bestMetric) {
                        bestMetric = density * 100
                        bestUrl = currentUrl
                    }
                }
            } else if (source.isNotEmpty() && source.size == 1) {
                if (bestUrl == null || bestMetric == 0) {
                    bestUrl = source[0]
                    bestMetric = 1
                }
            }
        }
        return bestUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("TvporInternet", "getMainPage: Iniciando carga de página principal.")

        val html = safeAppGet(mainUrl)
        if (html == null) {
            Log.e("TvporInternet", "getMainPage: Falló la carga del HTML de la página principal.")
            return null
        }
        val doc = Jsoup.parse(html)

        val channelItems = doc.select("div.carousel.owl-carousel > div.p-2.rounded.bg-slate-200.border, div.channels > div.p-2.rounded.bg-slate-200.border").mapNotNull { channelDiv ->
            val linkElement = channelDiv.selectFirst("a.channel-link")
            val link = linkElement?.attr("href")

            val imgElement = linkElement?.selectFirst("img")
            val title = imgElement?.attr("alt") ?: linkElement?.selectFirst("p.des")?.text()

            var img = imgElement?.attr("src")
            if (!img.isNullOrBlank()) {
                img = fixUrl(img)
            } else {
                Log.w("TvporInternet", "getMainPage: Imagen de canal nula o vacía para título: $title")
            }

            if (title != null && link != null) {
                Log.d("TvporInternet", "getMainPage: Canal encontrado - Título: $title, Link: $link, Imagen: $img")
                newTvSeriesSearchResponse(
                    name = title.replace("Ver ", "").replace(" en vivo", "").trim(),
                    url = fixUrl(link)
                ) {
                    this.type = TvType.Live
                    this.posterUrl = img
                }
            } else {
                Log.w("TvporInternet", "getMainPage: Elemento de canal incompleto (título o link nulo).")
                null
            }
        }

        val homePageList = HomePageList("Canales en vivo", channelItems)

        Log.d("TvporInternet", "getMainPage: Finalizado. ${channelItems.size} canales encontrados.")
        return newHomePageResponse(listOf(homePageList), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("TvporInternet", "search: Iniciando búsqueda para query: '$query'")

        val url = mainUrl
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("TvporInternet", "search: No se pudo obtener HTML para la búsqueda.")
            return emptyList()
        }
        val doc = Jsoup.parse(html)

        val results = doc.select("div.p-2.rounded.bg-slate-200.border").filterNot { element ->
            val text = element.selectFirst("p.des")?.text() ?: ""
            nowAllowed.any {
                text.contains(it, ignoreCase = true)
            } || text.isBlank()
        }.filter { element ->
            val title = element.selectFirst("p.des")?.text() ?: ""
            title.contains(query, ignoreCase = true)
        }.mapNotNull { it ->
            val titleRaw = it.selectFirst("p.des")?.text()
            val linkRaw = it.selectFirst("a")?.attr("href")
            val imgRaw = it.selectFirst("a img.w-28")?.attr("src")

            if (titleRaw != null && linkRaw != null && imgRaw != null) {
                val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
                val link = fixUrl(linkRaw)
                val img = fixUrl(imgRaw)
                Log.d("TvporInternet", "search: Resultado encontrado - Título: $title, Link: $link, Imagen: $img")

                newLiveSearchResponse(
                    name = title,
                    url = link,
                    type = TvType.Live
                ) {
                    this.posterUrl = img
                }
            } else {
                Log.w("TvporInternet", "search: Elemento de búsqueda incompleto (título, link o imagen nulo).")
                null
            }
        }
        if (results.isEmpty()) {
            Log.d("TvporInternet", "search: No se encontraron resultados para la query: '$query'")
        } else {
            Log.d("TvporInternet", "search: Búsqueda finalizada. ${results.size} resultados encontrados.")
        }
        return results
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    data class SortedEmbed(
        val servername: String,
        val link: String,
        val type: String
    )

    data class DataLinkEntry(
        val file_id: String,
        val video_language: String,
        val sortedEmbeds: List<SortedEmbed>
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("TvporInternet", "load: Iniciando carga de página de canal - URL: $url")

        val html = safeAppGet(url)
        if (html == null) {
            Log.e("TvporInternet", "load: Falló la carga del HTML para la URL del canal: $url")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.text-3xl.font-bold")?.text()?.replace(" EN VIVO", "")?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.replace(" EN VIVO", "")?.trim()
            ?: "Canal Desconocido"
        Log.d("TvporInternet", "load: Título del canal extraído: $title")

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img[alt*='logo'][src]")?.attr("src")
            ?: ""
        Log.d("TvporInternet", "load: Póster/Imagen extraída: $poster")

        val description = doc.selectFirst("div.info.text-sm.leading-relaxed")?.text() ?: ""
        Log.d("TvporInternet", "load: Descripción extraída: ${if (description.isNotBlank()) "OK" else "Vacía"}")

        val episodes = listOf(
            newEpisode(
                data = url
            ) {
                this.name = "En Vivo"
                this.season = 1
                this.episode = 1
                this.posterUrl = fixUrl(poster)
            }
        )
        Log.d("TvporInternet", "load: Episodios (en vivo) preparados para el canal.")

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Live,
            episodes = episodes
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
        }
    }

    private fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? {
        try {
            val encryptedBytes = Base64.decode(encryptedLinkBase64, Base64.DEFAULT)
            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ivSpec = IvParameterSpec(ivBytes)
            val cipherTextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val keySpec = SecretKeySpec(secretKey.toByteArray(UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherTextBytes)
            return String(decryptedBytes, UTF_8)
        } catch (e: Exception) {
            Log.e("TvporInternet", "decryptLink: Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = fixUrl(data)
        Log.d("TvporInternet", "loadLinks: Procesando URL: $targetUrl")

        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        try {
            val mainPageResponse = app.get(targetUrl, headers = baseHeaders)
            val doc = Jsoup.parse(mainPageResponse.text)

            val playerIframeSrc = doc.selectFirst("iframe[name=\"player\"]")?.attr("src")
                ?: doc.selectFirst("iframe[src*=\"/live/\"]")?.attr("src")

            if (playerIframeSrc.isNullOrBlank()) {
                return false
            }

            val playerUrl = fixUrl(playerIframeSrc)
            Log.d("TvporInternet", "Player URL: $playerUrl")

            val playerHeaders = baseHeaders + mapOf("Referer" to targetUrl)
            val playerResponse = app.get(playerUrl, headers = playerHeaders, cookies = mainPageResponse.cookies)
            val playerDoc = Jsoup.parse(playerResponse.text)

            val saohgdasIframeSrc = playerDoc.selectFirst("iframe[src*='saohgdasregions.fun']")?.attr("src")

            if (saohgdasIframeSrc.isNullOrBlank()) {
                return false
            }

            val saohgdasUrl = fixUrl(saohgdasIframeSrc)
            Log.d("TvporInternet", "Saohgdasregions URL: $saohgdasUrl")

            val saohgdasHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Referer" to playerUrl,
                "Origin" to "https://www.tvporinternet2.com",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )

            val saohgdasResponse = app.get(saohgdasUrl, headers = saohgdasHeaders)
            val saohgdasHtml = saohgdasResponse.text

            Log.d("TvporInternet", "Response code: ${saohgdasResponse.code}")
            Log.d("TvporInternet", "HTML length: ${saohgdasHtml.length}")

            val m3u8Url = findM3u8Url(saohgdasHtml)

            if (m3u8Url == null) {
                Log.d("TvporInternet", "Buscando m3u8 en todo el HTML...")
                val allM3u8Regex = Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)")
                val m3u8Match = allM3u8Regex.find(saohgdasHtml)
                if (m3u8Match != null) {
                    val foundUrl = m3u8Match.value
                    Log.d("TvporInternet", "M3U8 encontrado: $foundUrl")

                    createExtractorLink(foundUrl, callback)
                    return true
                }

                Log.e("TvporInternet", "No se encontró URL del stream")
                Log.d("TvporInternet", "HTML preview: ${saohgdasHtml.take(2000)}")
                return false
            }

            Log.d("TvporInternet", "Stream URL encontrada: $m3u8Url")
            createExtractorLink(m3u8Url, callback)
            return true

        } catch (e: Exception) {
            Log.e("TvporInternet", "Error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun findM3u8Url(html: String): String? {
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script").joinToString(" ") { it.html() }

        val patterns = listOf(
            """source\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """url\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """hls\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """sources\s*:\s*\[\s*{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """src:\s*["']([^"']+\.m3u8[^"']*)["']""",
            """clip\s*:\s*{\s*sources\s*:\s*\[\s*{\s*src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""
        )

        for (pattern in patterns) {
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(scripts)
                if (match != null && match.groupValues.size > 1) {
                    return match.groupValues[1]
                }
            } catch (e: Exception) {
                Log.e("TvporInternet", "Error con pattern: $pattern")
            }
        }

        doc.select("[data-src*='.m3u8'], [data-source*='.m3u8'], [data-file*='.m3u8']").forEach { element ->
            val dataSrc = element.attr("data-src") + element.attr("data-source") + element.attr("data-file")
            if (dataSrc.contains(".m3u8")) {
                val m3u8Regex = Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)")
                val match = m3u8Regex.find(dataSrc)
                if (match != null) {
                    return match.value
                }
            }
        }

        return null
    }

    private fun createExtractorLink(streamUrl: String, callback: (ExtractorLink) -> Unit) {
        val streamHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Origin" to "https://live.saohgdasregions.fun",
            "Pragma" to "no-cache",
            "Referer" to "https://live.saohgdasregions.fun/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        )

        callback(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
                referer = "https://live.saohgdasregions.fun/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = streamHeaders
            )
        )
    }
}