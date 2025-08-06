package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("SoloLatino", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("SoloLatino", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("SoloLatino", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("SoloLatino", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("SoloLatino", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("SoloLatino", "safeAppGet - Fallaron todos los intentos para URL: $url")
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
        Log.d("SoloLatino", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}")
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Animes", "$mainUrl/animes")
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                else -> TvType.Others
            }
            val html = safeAppGet(url)
            if (html == null) {
                Log.e("SoloLatino", "getMainPage - No se pudo obtener HTML para $url")
                return@apmap null
            }
            val doc = Jsoup.parse(html)
            val homeItems = doc.select("div.items article.item").mapNotNull { article ->
                val title = article.selectFirst("a div.data h3")?.text()
                val link = article.selectFirst("a")?.attr("href")

                val imgElement = article.selectFirst("div.poster img.lazyload")
                val srcsetAttr = imgElement?.attr("data-srcset")
                var img = extractBestSrcset(srcsetAttr)

                if (img.isNullOrBlank()) {
                    img = imgElement?.attr("src")
                    Log.d("SoloLatino", "DEBUG: Fallback a src para título: $title, img: $img")
                }

                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else {
                    Log.w("SoloLatino", "ADVERTENCIA: Elemento de inicio incompleto (título o link nulo) para URL: $url")
                    null
                }
            }
            HomePageList(name, homeItems)
        }.filterNotNull()

        items.addAll(homePageLists)

        Log.d("SoloLatino", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("SoloLatino", "DEBUG: Iniciando search para query: $query")
        val url = "$mainUrl/?s=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("SoloLatino", "search - No se pudo obtener HTML para la búsqueda: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)
        return doc.select("div.items article.item").mapNotNull { article ->
            val title = article.selectFirst("a div.data h3")?.text()
            val link = article.selectFirst("a")?.attr("href")

            val imgElement = article.selectFirst("div.poster img.lazyload")
            val srcsetAttr = imgElement?.attr("data-srcset")
            var img = extractBestSrcset(srcsetAttr)

            if (img.isNullOrBlank()) {
                img = imgElement?.attr("src")
                Log.d("SoloLatino", "DEBUG: Fallback a src para resultado de búsqueda: $title, img: $img")
            }

            if (title != null && link != null) {
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
                    this.posterUrl = img
                }
            } else {
                Log.w("SoloLatino", "ADVERTENCIA: Resultado de búsqueda incompleto (título o link nulo) para query: $query")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("SoloLatino", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("SoloLatino", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("SoloLatino", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("SoloLatino", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SoloLatino", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val html = safeAppGet(cleanUrl)
        if (html == null) {
            Log.e("SoloLatino", "load - No se pudo obtener HTML para la URL principal: $cleanUrl")
            return null
        }
        val doc = Jsoup.parse(html)

        val tvType = if (cleanUrl.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val numerandoText = element.selectFirst("div.episodiotitle div.numerando")?.text()
                    val seasonNumber = numerandoText?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = numerandoText?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else null
                }
            }
        } else listOf()

        val recommendations = doc.select("div#single_relacionados article").mapNotNull {
            val recLink = it.selectFirst("a")?.attr("href")
            val recImgElement = it.selectFirst("a img.lazyload") ?: it.selectFirst("a img")
            val recImg = recImgElement?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: recImgElement?.attr("src")
            val recTitle = recImgElement?.attr("alt")

            if (recTitle != null && recLink != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.posterUrl = recImg
                    this.type = if (recLink.contains("/peliculas/")) TvType.Movie else TvType.TvSeries
                }
            } else {
                null
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    dataUrl = cleanUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            else -> null
        }
    }

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
            Log.e("SoloLatino", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SoloLatino", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("SoloLatino", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SoloLatino", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val initialIframeSrc = doc.selectFirst("iframe#iframePlayer")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (initialIframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe del reproductor principal con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("SoloLatino", "Encontrado enlace directo en script de página principal: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("SoloLatino", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("SoloLatino", "Iframe principal encontrado: $initialIframeSrc")

        var finalIframeSrc: String = initialIframeSrc

        if (initialIframeSrc.contains("ghbrisk.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado ghbrisk.com iframe intermediario: $initialIframeSrc. Buscando iframe anidado.")
            val ghbriskHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (ghbriskHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de ghbrisk.com: $initialIframeSrc")
                return false
            }
            val ghbriskDoc = Jsoup.parse(ghbriskHtml)

            val nestedIframeSrc = ghbriskDoc.selectFirst("iframe.metaframe.rptss")?.attr("src")
                ?: ghbriskDoc.selectFirst("iframe")?.attr("src")

            if (nestedIframeSrc.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró un iframe anidado (posiblemente embed69.org) dentro de ghbrisk.com.")
                return false
            }
            Log.d("SoloLatino", "Iframe anidado encontrado en ghbrisk.com: $nestedIframeSrc")
            finalIframeSrc = nestedIframeSrc
        }
        else if (initialIframeSrc.contains("xupalace.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado Xupalace.org iframe intermediario/directo: $initialIframeSrc.")
            val xupalaceHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (xupalaceHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de Xupalace: $initialIframeSrc")
                return false
            }
            val xupalaceDoc = Jsoup.parse(xupalaceHtml)

            val nestedIframeSrc = xupalaceDoc.selectFirst("iframe#IFR")?.attr("src")

            if (!nestedIframeSrc.isNullOrBlank()) {
                Log.d("SoloLatino", "Iframe anidado (playerwish.com) encontrado en Xupalace.org: $nestedIframeSrc")
                finalIframeSrc = nestedIframeSrc
            } else {
                Log.w("SoloLatino", "No se encontró un iframe anidado (playerwish.com) dentro de Xupalace.org. Intentando buscar enlaces directos 'go_to_playerVast'.")
                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.e("SoloLatino", "No se encontraron elementos con 'go_to_playerVast' ni iframe 'IFR' en xupalace.org.")
                    return false
                }

                val foundXupalaceLinks = mutableListOf<String>()
                for (element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                        Log.d("SoloLatino", "Xupalace: Encontrado servidor '$serverName' con URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            foundXupalaceLinks.add(videoUrl)
                        }
                    } else {
                        Log.w("SoloLatino", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }

                if (foundXupalaceLinks.isNotEmpty()) {
                    foundXupalaceLinks.apmap { playerUrl ->
                        Log.d("SoloLatino", "Cargando extractor para link de Xupalace (go_to_playerVast): $playerUrl")
                        loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                    }
                    return true
                } else {
                    Log.d("SoloLatino", "No se encontraron enlaces de video de Xupalace.org (go_to_playerVast).")
                    return false
                }
            }
        }

        if (finalIframeSrc.contains("playerwish.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado playerwish.com. Pasando al extractor general de CloudStream: $finalIframeSrc")
            loadExtractor(fixUrl(finalIframeSrc), finalIframeSrc, subtitleCallback, callback)
            return true
        }
        else if (finalIframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d("SoloLatino", "loadLinks - Detectado re.sololatino.net/embed.php iframe: $finalIframeSrc")
            val embedHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (embedHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de re.sololatino.net: $finalIframeSrc")
                return false
            }
            val embedDoc = Jsoup.parse(embedHtml)
            val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
            val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")
            if (elementsWithOnclick.isEmpty()) {
                Log.w("SoloLatino", "No se encontraron elementos con 'go_to_player' en re.sololatino.net/embed.php con el selector general.")
                return false
            }
            val foundReSoloLatinoLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)
                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SoloLatino", "re.sololatino.net: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundReSoloLatinoLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SoloLatino", "re.sololatino.net: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }
            if (foundReSoloLatinoLinks.isNotEmpty()) {
                foundReSoloLatinoLinks.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link de re.sololatino.net: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video de re.sololatino.net/embed.php.")
                return false
            }
        }
        else if (finalIframeSrc.contains("embed69.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado embed69.org iframe: $finalIframeSrc")

            val frameHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (frameHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de embed69.org: $finalIframeSrc")
                return false
            }
            val frameDoc = Jsoup.parse(frameHtml)

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

            val dataLinkRegex = """const\s+dataLink\s*=\s*(\[.*?\]);""".toRegex()
            val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

            if (dataLinkJsonString.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró la variable dataLink en el script de embed69.org con la regex. Contenido del script (primeras 500 chars): ${scriptContent.take(500)}...")
                return false
            }

            Log.d("SoloLatino", "dataLink JSON string encontrado: $dataLinkJsonString")

            val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

            if (dataLinkEntries.isNullOrEmpty()) {
                Log.e("SoloLatino", "Error al parsear dataLink JSON o está vacío.")
                return false
            }

            val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

            var linksFound = false
            for (entry in dataLinkEntries) {
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") {
                        val decryptedLink = decryptLink(embed.link, secretKey)
                        if (decryptedLink != null) {
                            Log.d("SoloLatino", "Link desencriptado para ${embed.servername}: $decryptedLink")
                            val success = loadExtractor(fixUrl(decryptedLink), initialIframeSrc, subtitleCallback) { link ->
                                linksFound = true
                                callback(link)
                            }
                            if (!success) {
                                Log.w("SoloLatino", "loadExtractor falló para el enlace de ${embed.servername}.")
                            }
                        } else {
                            Log.e("SoloLatino", "Falló la desencriptación para ${embed.servername} con enlace: ${embed.link}")
                        }
                    } else {
                        Log.d("SoloLatino", "Ignorando embed de tipo no video: ${embed.servername} (${embed.type})")
                    }
                }
            }

            return linksFound
        }
        else if (finalIframeSrc.contains("fembed.com") || finalIframeSrc.contains("streamlare.com") || finalIframeSrc.contains("player.sololatino.net")) {
            Log.d("SoloLatino", "loadLinks - Detectado reproductor directo (Fembed/Streamlare/player.sololatino.net): $finalIframeSrc")
            loadExtractor(fixUrl(finalIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        }
        else {
            Log.w("SoloLatino", "Tipo de iframe desconocido o no manejado: $finalIframeSrc")
            return false
        }
    }
}