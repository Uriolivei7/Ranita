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

class TvporinternetProvider : MainAPI() {
    override var mainUrl = "https://www.tvporinternet2.com"
    override var name = "TvporInternet"

    override val supportedTypes = setOf(
        TvType.Live
    )

    override var lang = "es"

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
        Log.d("TvporInternet", "loadLinks: Iniciando extracción de enlaces de reproducción para URL: $data")

        val targetUrl = fixUrl(data)
        if (targetUrl.isBlank()) {
            Log.e("TvporInternet", "loadLinks: ERROR - URL objetivo (página del canal) está en blanco.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl, referer = mainUrl)
        if (initialHtml == null) {
            Log.e("TvporInternet", "loadLinks: Falló la carga del HTML para la URL del canal: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
        val saohgdasregionsRegex = """(https?:\/\/[^'"]*saohgdasregions\.fun[^'"]*)""".toRegex()
        val directSaohgdasRegionsMatches = saohgdasregionsRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

        if (directSaohgdasRegionsMatches.isNotEmpty()) {
            Log.d("TvporInternet", "loadLinks: Encontrado enlace saohgdasregions.fun directamente en script de la página inicial. Procesando...")
            directSaohgdasRegionsMatches.apmap { directUrl ->
                Log.d("TvporInternet", "loadLinks: Encontrado directo en script: $directUrl")
                loadExtractor(fixUrl(directUrl), targetUrl, subtitleCallback, callback)
            }
            return true
        } else {
            Log.d("TvporInternet", "loadLinks: No se encontró enlace saohgdasregions.fun directo en scripts de la página inicial.")
        }

        val playerLinks = mutableListOf<String>()

        val mainIframeSrc = doc.selectFirst("iframe[name=\"player\"]")?.attr("src")
        if (!mainIframeSrc.isNullOrBlank()) {
            playerLinks.add(fixUrl(mainIframeSrc))
            Log.d("TvporInternet", "loadLinks: Iframe principal encontrado: $mainIframeSrc")
        } else {
            Log.d("TvporInternet", "loadLinks: No se encontró iframe principal con name='player'.")
        }

        doc.select("div.flex.flex-wrap.gap-3 a.bg-[#626262]").forEach { optionLinkElement ->
            val optionHref = optionLinkElement.attr("href")
            if (!optionHref.isNullOrBlank()) {
                playerLinks.add(fixUrl(optionHref))
                Log.d("TvporInternet", "loadLinks: Opción de reproductor encontrada: $optionHref")
            }
        }

        if (playerLinks.isEmpty()) {
            Log.e("TvporInternet", "loadLinks: No se encontraron enlaces de reproductores en la página del canal: $targetUrl")
            val genericDirectRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val genericDirectMatches = genericDirectRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (genericDirectMatches.isNotEmpty()) {
                Log.d("TvporInternet", "loadLinks: Intentando encontrar enlaces directos genéricos en scripts como fallback.")
                genericDirectMatches.apmap { directUrl ->
                    Log.d("TvporInternet", "loadLinks: Encontrado enlace directo genérico en script: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("TvporInternet", "loadLinks: No se encontraron enlaces directos genéricos en scripts de la página del canal como fallback.")
            return false
        }

        playerLinks.apmap { playerUrl ->
            Log.d("TvporInternet", "loadLinks: Procesando enlace de reproductor: $playerUrl")

            var currentProcessingUrl = playerUrl

            if (currentProcessingUrl.contains("tvporinternet2.com/live/") && currentProcessingUrl.endsWith(".php")) {
                Log.d("TvporInternet", "loadLinks: Detectado iframe PHP intermedio: $currentProcessingUrl. Buscando iframe anidado.")
                val phpIframeHtml = safeAppGet(fixUrl(currentProcessingUrl), referer = targetUrl)
                if (phpIframeHtml != null) {
                    val phpIframeDoc = Jsoup.parse(phpIframeHtml)
                    val nestedIframeSrc = phpIframeDoc.selectFirst("iframe[src*='saohgdasregions.fun']")?.attr("src")

                    if (!nestedIframeSrc.isNullOrBlank()) {
                        currentProcessingUrl = nestedIframeSrc
                        Log.d("TvporInternet", "loadLinks: Iframe anidado en PHP encontrado: $currentProcessingUrl")

                        Log.d("TvporInternet", "loadLinks: Siguiendo potencial redirección de saohgdasregions.fun y buscando URL de stream.")
                        val finalPlayerHtml = safeAppGet(fixUrl(currentProcessingUrl), referer = playerUrl)
                        if (finalPlayerHtml != null) {
                            val finalPlayerDoc = Jsoup.parse(finalPlayerHtml)
                            val scriptContentFinal = finalPlayerDoc.select("script").map { it.html() }.joinToString("\n")

                            val clapprSourceRegex = """source:\s*['"](https?:\/\/[^'"]*\.(?:m3u8|mpd|mp4)[^'"]*)['"]""".toRegex()
                            val streamUrlMatch = clapprSourceRegex.find(scriptContentFinal)

                            if (streamUrlMatch != null) {
                                val finalStreamUrl = streamUrlMatch.groupValues[1]
                                Log.d("TvporInternet", "loadLinks: ¡URL de stream final encontrada en Clappr.js!: $finalStreamUrl")
                                callback(
                                    ExtractorLink(
                                        this.name,
                                        this.name,
                                        finalStreamUrl,
                                        referer = "https://live.saohgdasregions.fun/",
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = mapOf(
                                            "Origin" to "https://live.saohgdasregions.fun"
                                        )
                                    )
                                )
                                return@apmap
                            } else {
                                Log.e("TvporInternet", "loadLinks: No se encontró la URL del stream en el script de Clappr.js para: $currentProcessingUrl")
                            }
                        } else {
                            Log.e("TvporInternet", "loadLinks: Falló la carga del HTML final del reproductor desde: $currentProcessingUrl")
                        }
                    } else {
                        Log.e("TvporInternet", "loadLinks: No se encontró un iframe anidado con 'saohgdasregions.fun' en el archivo PHP: $currentProcessingUrl")
                        val allIframes = phpIframeDoc.select("iframe")
                        if (allIframes.isNotEmpty()) {
                            Log.e("TvporInternet", "loadLinks: Otros iframes encontrados en PHP, pero no coincidieron con el selector 'saohgdasregions.fun':")
                            allIframes.forEachIndexed { index, element ->
                                Log.e("TvporInternet", "loadLinks:   Iframe ${index + 1}: src='${element.attr("src")}', id='${element.attr("id")}', name='${element.attr("name")}'")
                            }
                        }
                        val allScripts = phpIframeDoc.select("script")
                        if (allScripts.isNotEmpty()) {
                            Log.d("TvporInternet", "loadLinks: Se encontraron scripts en PHP, podría generarse con JS (pero el iframe principal ya fue encontrado).")
                        }
                    }
                } else {
                    Log.e("TvporInternet", "loadLinks: No se pudo obtener HTML del iframe PHP: $currentProcessingUrl")
                }
            } else {
                if (currentProcessingUrl.isNotBlank() && !currentProcessingUrl.contains("saohgdasregions.fun")) {
                    Log.d("TvporInternet", "loadLinks: Pasando al extractor general de CloudStream con URL final: $currentProcessingUrl")
                    loadExtractor(fixUrl(currentProcessingUrl), targetUrl, subtitleCallback, callback)
                } else {
                    Log.w("TvporInternet", "loadLinks: El enlace final del reproductor está en blanco o fue manejado como intermediario, sin extractor final: $currentProcessingUrl")
                }
            }
        }

        return true
    }
}