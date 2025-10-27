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

        try {
            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none"
            )

            val mainPageResponse = try {
                app.get(targetUrl, headers = mainHeaders, timeout = 20L)
            } catch (e: Exception) {
                Log.e("TvporInternet", "Error al cargar página principal: ${e.message}")
                return false
            }
            val cookies = mainPageResponse.cookies
            val mainHtml = mainPageResponse.text
            Log.d("TvporInternet", "HTML principal (length: ${mainHtml.length}): ${mainHtml.take(1000)}")

            if (mainHtml.isBlank() || mainHtml.contains("�") || mainHtml.length < 100) {
                Log.e("TvporInternet", "HTML principal corrupto o vacío")
                return false
            }

            val doc = Jsoup.parse(mainHtml)

            val playerIframeSrc = doc.selectFirst("iframe[name=\"player\"], iframe[src*=\"live/\"], iframe[src*=\"saohgdasregions\"], iframe")?.attr("src")
            val optionLinks = doc.select("a[target=\"player\"], a[href*=\"live/\"], a[href*=\"live2/\"], a[href*=\"live3/\"], a[href*=\"live4/\"], a[href*=\"live5/\"], a[href*=\"live6/\"")
                .mapNotNull { it.attr("href") }
                .distinct()
            Log.d("TvporInternet", "Iframe src: $playerIframeSrc, Opciones: $optionLinks")

            val playerUrls = mutableListOf<String>()
            playerIframeSrc?.let { playerUrls.add(it) }
            playerUrls.addAll(optionLinks)

            if (playerUrls.isEmpty()) {
                Log.e("TvporInternet", "No se encontraron URLs de reproductores")
                return false
            }

            var success = false
            playerUrls.forEachIndexed { index, rawPlayerUrl ->
                val playerUrl = fixUrl(rawPlayerUrl)
                Log.d("TvporInternet", "Procesando Player URL [$index]: $playerUrl")

                val playerHeaders = mainHeaders + mapOf(
                    "Referer" to targetUrl,
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin"
                )

                val playerResponse = try {
                    app.get(playerUrl, headers = playerHeaders, cookies = cookies, timeout = 20L)
                } catch (e: Exception) {
                    Log.e("TvporInternet", "Error al cargar Player URL [$index]: ${e.message}")
                    return@forEachIndexed
                }
                val playerHtml = playerResponse.text
                Log.d("TvporInternet", "HTML del player [$index]: ${playerHtml.take(500)}")

                val embedPattern = Regex("""(https?://[^"']+saohgdasregions\.fun[^"']+)""")
                val embedMatch = embedPattern.find(playerHtml)
                if (embedMatch == null) {
                    Log.w("TvporInternet", "No se encontró URL de embed en Player URL [$index]")
                    return@forEachIndexed
                }

                val embedUrl = embedMatch.value
                Log.d("TvporInternet", "Embed URL [$index]: $embedUrl")

                val embedHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Referer" to playerUrl,
                    "Origin" to "https://live.saohgdasregions.fun",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )

                val embedResponse = try {
                    app.get(embedUrl, headers = embedHeaders, cookies = cookies, timeout = 20L)
                } catch (e: Exception) {
                    Log.e("TvporInternet", "Error al cargar Embed URL [$index]: ${e.message}")
                    return@forEachIndexed
                }
                val embedHtml = embedResponse.text
                Log.d("TvporInternet", "HTML del embed [$index]: ${embedHtml.take(500)}")

                var m3u8Url = extractM3u8FromHtml(embedHtml)
                if (m3u8Url == null) {
                    Log.w("TvporInternet", "No se encontró URL m3u8 en Embed URL [$index]")
                    return@forEachIndexed
                }

                m3u8Url = m3u8Url
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\u003D", "=")
                    .trim()

                if (!m3u8Url.startsWith("http")) {
                    m3u8Url = if (m3u8Url.startsWith("//")) "https:$m3u8Url" else "https://live.saohgdasregions.fun$m3u8Url"
                }
                Log.d("TvporInternet", "Stream final URL [$index]: $m3u8Url")

                val streamResponse = try {
                    app.get(m3u8Url, headers = embedHeaders, timeout = 15L)
                } catch (e: Exception) {
                    Log.e("TvporInternet", "Error al verificar M3U8 [$index]: ${e.message}")
                    return@forEachIndexed
                }
                if (!streamResponse.isSuccessful) {
                    Log.e("TvporInternet", "Fallo al cargar M3U8 [$index]: Código ${streamResponse.code}")
                    return@forEachIndexed
                }

                val qualityOptions = if (android.os.Build.MANUFACTURER.contains("Microsoft", ignoreCase = true) ||
                    android.os.Build.MODEL.contains("Windows", ignoreCase = true) ||
                    android.os.Build.DEVICE.contains("tablet", ignoreCase = true)) {
                    listOf(Pair("SD", Qualities.P480.value))
                } else {
                    listOf(
                        Pair("HD", Qualities.P720.value),
                        Pair("SD", Qualities.P480.value)
                    )
                }

                qualityOptions.forEach { (qualityName, qualityValue) ->
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "${this.name} - $qualityName (Opción $index)",
                            url = m3u8Url,
                            referer = "https://live.saohgdasregions.fun/",
                            quality = qualityValue,
                            type = ExtractorLinkType.M3U8,
                            headers = embedHeaders
                        )
                    )
                    Log.d("TvporInternet", "ExtractorLink añadido: ${this.name} - $qualityName (Opción $index)")
                }
                success = true
            }

            return success
        } catch (e: Exception) {
            Log.e("TvporInternet", "Error en loadLinks: ${e.message}", e)
            return false
        }
    }

    private fun extractM3u8FromHtml(html: String): String? {
        val doc = Jsoup.parse(html)

        val source = doc.selectFirst("video > source, source[src]")
        if (source != null && source.attr("src").contains(".m3u8")) {
            Log.d("TvporInternet", "M3U8 encontrado en <source>: ${source.attr("src")}")
            return source.attr("src")
        }

        val patterns = listOf(
            """(https?://[^"'\s]+\.m3u8[^"'\s]*)""",
            """(https?:\\\/\\\/[^"'\s]+\.m3u8[^"'\s]*)""",
            """["'](https?:(?:\\\/\\\/|\/\/)[^"']+\.m3u8[^"']*)["']""",
            """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """src\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """hls\.src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
            """url\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""
        )

        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                val url = match.groupValues.getOrNull(1) ?: match.value
                Log.d("TvporInternet", "M3U8 encontrado con patrón $pattern: $url")
                return url
            }
        }

        val dataSource = doc.selectFirst("[data-src], [data-url], [data-source], [data-hls]")
        if (dataSource != null) {
            val url = dataSource.attr("data-src") ?: dataSource.attr("data-url") ?: dataSource.attr("data-source")
            ?: dataSource.attr("data-hls")
            if (url.contains(".m3u8")) {
                Log.d("TvporInternet", "M3U8 encontrado en data-*: $url")
                return url
            }
        }

        Log.e("TvporInternet", "No se encontró URL m3u8 en el HTML")
        return null
    }
}