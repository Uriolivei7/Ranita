package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.google.gson.annotations.SerializedName
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlin.text.RegexOption

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "mx"

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

        val homePageLists = urls.map { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                else -> TvType.Others
            }
            val html = safeAppGet(url)
            if (html == null) {
                Log.e("SoloLatino", "getMainPage - No se pudo obtener HTML para $url")
                return@map null
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

    data class DataLinkEntry(
        @SerializedName("file_id") val fileId: String? = null,
        @SerializedName("video_language") val videoLanguage: String? = null,
        @SerializedName("sortedEmbeds") val sortedEmbeds: List<SortedEmbeds>? = null
    )
    data class SortedEmbeds(
        @SerializedName("servername") val servername: String? = null,
        @SerializedName("link") val link: String? = null,
        @SerializedName("download") val download: String? = null
    )

    data class DecryptedLink(
        @SerializedName("index") val index: Int,
        @SerializedName("link") val link: String?
    )

    data class DecryptionResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("links") val links: List<DecryptedLink>?,
        @SerializedName("reason") val reason: String?
    )

    data class DecryptRequestBody(
        @SerializedName("links") val links: List<String>
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d(
                "SoloLatino",
                "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData"
            )
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d(
                "SoloLatino",
                "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl"
            )
        }

        if (targetUrl.isBlank()) {
            Log.e(
                "SoloLatino",
                "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'."
            )
            return@coroutineScope false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e(
                "SoloLatino",
                "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl"
            )
            return@coroutineScope false
        }
        val doc = Jsoup.parse(initialHtml)

        val initialIframeSrc = doc.selectFirst("iframe#iframePlayer")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (initialIframeSrc.isNullOrBlank()) {
            Log.d(
                "SoloLatino",
                "No se encontró iframe del reproductor principal con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal."
            )
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches =
                directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                val jobs = directMatches.map { directUrl ->
                    async {
                        Log.d(
                            "SoloLatino",
                            "Encontrado enlace directo en script de página principal: $directUrl"
                        )
                        loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                    }
                }
                return@coroutineScope jobs.awaitAll().any { it }
            }
            Log.d(
                "SoloLatino",
                "No se encontraron enlaces directos en scripts de la página principal."
            )
            return@coroutineScope false
        }

        Log.d("SoloLatino", "Iframe principal encontrado: $initialIframeSrc")

        var finalIframeSrc: String = initialIframeSrc

        if (initialIframeSrc.contains("ghbrisk.com")) {
            Log.d(
                "SoloLatino",
                "loadLinks - Detectado ghbrisk.com iframe intermediario: $initialIframeSrc. Buscando iframe anidado."
            )
            val ghbriskHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (ghbriskHtml == null) {
                Log.e(
                    "SoloLatino",
                    "loadLinks - No se pudo obtener HTML del iframe de ghbrisk.com: $initialIframeSrc"
                )
                return@coroutineScope false
            }
            val nestedIframeSrc =
                Jsoup.parse(ghbriskHtml).selectFirst("iframe.metaframe.rptss")?.attr("src")
                    ?: Jsoup.parse(ghbriskHtml).selectFirst("iframe")?.attr("src")
            if (nestedIframeSrc.isNullOrBlank()) {
                Log.e(
                    "SoloLatino",
                    "No se encontró un iframe anidado (posiblemente embed69.org) dentro de ghbrisk.com."
                )
                return@coroutineScope false
            }
            Log.d("SoloLatino", "Iframe anidado encontrado en ghbrisk.com: $nestedIframeSrc")
            finalIframeSrc = nestedIframeSrc
        } else if (initialIframeSrc.contains("xupalace.org")) {
            Log.d(
                "SoloLatino",
                "loadLinks - Detectado Xupalace.org iframe intermediario/directo: $initialIframeSrc."
            )
            val xupalaceHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (xupalaceHtml == null) {
                Log.e(
                    "SoloLatino",
                    "loadLinks - No se pudo obtener HTML del iframe de Xupalace: $initialIframeSrc"
                )
                return@coroutineScope false
            }
            val xupalaceDoc = Jsoup.parse(xupalaceHtml)
            val nestedIframeSrc = xupalaceDoc.selectFirst("iframe#IFR")?.attr("src")

            if (!nestedIframeSrc.isNullOrBlank()) {
                Log.d(
                    "SoloLatino",
                    "Iframe anidado (playerwish.com) encontrado en Xupalace.org: $nestedIframeSrc"
                )
                finalIframeSrc = nestedIframeSrc
            } else {
                Log.w(
                    "SoloLatino",
                    "No se encontró un iframe anidado (playerwish.com) dentro de Xupalace.org. Intentando buscar enlaces directos 'go_to_playerVast'."
                )
                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val foundLinks = Jsoup.parse(xupalaceHtml).select("*[onclick*='go_to_playerVast']")
                    .mapNotNull { element ->
                        regexPlayerUrl.find(element.attr("onclick"))?.groupValues?.get(
                            1
                        )
                    }

                if (foundLinks.isNotEmpty()) {
                    val jobs = foundLinks.map { playerUrl ->
                        async {
                            Log.d(
                                "SoloLatino",
                                "Cargando extractor para link de Xupalace (go_to_playerVast): $playerUrl"
                            )
                            loadExtractor(
                                fixUrl(playerUrl),
                                initialIframeSrc,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                    return@coroutineScope jobs.awaitAll().any { it }
                } else {
                    Log.e(
                        "SoloLatino",
                        "No se encontraron elementos con 'go_to_playerVast' ni iframe 'IFR' en xupalace.org."
                    )
                    return@coroutineScope false
                }
            }
        }

        if (finalIframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d(
                "SoloLatino",
                "loadLinks - Detectado re.sololatino.net/embed.php iframe: $finalIframeSrc"
            )
            val embedHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (embedHtml == null) {
                Log.e(
                    "SoloLatino",
                    "loadLinks - No se pudo obtener HTML del iframe de re.sololatino.net: $finalIframeSrc"
                )
                return@coroutineScope false
            }
            val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
            val foundLinks = Jsoup.parse(embedHtml).select("*[onclick*='go_to_player']")
                .mapNotNull { element ->
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)
                    val videoUrl = matchPlayerUrl?.groupValues?.get(1)
                    if (videoUrl != null) {
                        val serverName =
                            element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                        Log.d(
                            "SoloLatino",
                            "re.sololatino.net: Encontrado servidor '$serverName' con URL: $videoUrl"
                        )
                        videoUrl
                    } else null
                }

            if (foundLinks.isNotEmpty()) {
                val jobs = foundLinks.map { playerUrl ->
                    async {
                        Log.d(
                            "SoloLatino",
                            "Cargando extractor para link de re.sololatino.net: $playerUrl"
                        )
                        loadExtractor(
                            fixUrl(playerUrl),
                            initialIframeSrc,
                            subtitleCallback,
                            callback
                        )
                    }
                }
                return@coroutineScope jobs.awaitAll().any { it }
            } else {
                Log.d(
                    "SoloLatino",
                    "No se encontraron enlaces de video de re.sololatino.net/embed.php."
                )
                return@coroutineScope false
            }
        }

        else if (finalIframeSrc.contains("embed69.org")) {
            Log.d(
                "SoloLatino",
                "loadLinks - Detectado embed69.org. Usando nueva lógica de descifrado API."
            )

            val embedHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (embedHtml == null) {
                Log.e(
                    "SoloLatino",
                    "loadLinks - No se pudo obtener HTML del iframe de embed69.org."
                )
                return@coroutineScope false
            }

            val doc = Jsoup.parse(embedHtml)
            var scriptContent: String? = null

            for (script in doc.select("script")) {
                val content = script.html()
                if (content.contains("let dataLink =") || content.contains("var dataLink =")) {
                    scriptContent = content
                    break
                }
            }

            if (scriptContent.isNullOrBlank()) {
                Log.e("SoloLatino", "ERROR: No se encontró el script que define 'dataLink' iterando en todos los scripts.")
                return@coroutineScope false
            }

            val dataLinkRegex = Regex("""dataLink\s*=\s*(\[.*?\])\s*;""")

            val jsonMatch = dataLinkRegex.find(scriptContent!!)

            if (jsonMatch == null) {
                val end = Math.min(scriptContent.length, 200)
                val snippet = scriptContent.substring(0, end)
                Log.e("SoloLatino", "ERROR: No se pudo extraer la variable dataLink JSON del script. Script snippet: $snippet")
                return@coroutineScope false
            }

            val dataLinkJson = jsonMatch.groupValues[1]

            val logEnd = Math.min(dataLinkJson.length, 100)
            Log.d("SoloLatino", "dataLink JSON extraído: ${dataLinkJson.substring(0, logEnd)}...")

            val files = tryParseJson<List<DataLinkEntry>>(dataLinkJson)

            if (files.isNullOrEmpty()) {
                Log.e("SoloLatino", "ERROR: dataLink JSON se extrajo, pero no se pudo parsear a la lista de DataLinkEntry.")
                return@coroutineScope false
            }

            val encryptedLinks = files.flatMap { file ->
                (file.sortedEmbeds ?: emptyList()).flatMap { embed ->
                    listOfNotNull(embed.link, embed.download)
                }
            }.distinct()

            if (encryptedLinks.isEmpty()) {
                Log.e("SoloLatino", "ERROR: No se encontraron enlaces cifrados (JWT) en dataLink.")
                return@coroutineScope false
            }

            val decryptUrl = "https://embed69.org/api/decrypt"
            val body = DecryptRequestBody(encryptedLinks).toJson()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val decryptedRes = app.post(
                decryptUrl,
                requestBody = body,
                interceptor = cfKiller
            )

            if (!decryptedRes.isSuccessful) {
                Log.e(
                    "SoloLatino",
                    "ERROR: API de descifrado fallida con código ${decryptedRes.code}. Cuerpo: ${decryptedRes.text}"
                )
                return@coroutineScope false
            }

            val decryptedData =
                tryParseJson<DecryptionResponse>(decryptedRes.text)
            val finalLinks = decryptedData?.links?.mapNotNull { it.link }

            if (decryptedData?.success != true || finalLinks.isNullOrEmpty()) {
                Log.e(
                    "SoloLatino",
                    "ERROR: Respuesta de descifrado inválida o vacía. Razón: ${decryptedData?.reason}"
                )
                return@coroutineScope false
            }

            val jobs = finalLinks.map { decryptedLink ->
                async {
                    Log.d("SoloLatino", "Cargando extractor para enlace descifrado: $decryptedLink")
                    loadExtractor(
                        decryptedLink.replace("`", "").trim(),
                        targetUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
            return@coroutineScope jobs.awaitAll().any { it }
        }

        else if (finalIframeSrc.contains("playerwish.com") ||
            finalIframeSrc.contains("fembed.com") ||
            finalIframeSrc.contains("streamlare.com") ||
            finalIframeSrc.contains("player.sololatino.net")
        ) {

            Log.d(
                "SoloLatino",
                "loadLinks - Intentando cargar iframe/reproductor directamente: $finalIframeSrc"
            )
            return@coroutineScope loadExtractor(
                fixUrl(finalIframeSrc),
                targetUrl,
                subtitleCallback,
                callback
            )
        }

        else {
            Log.w("SoloLatino", "Tipo de iframe desconocido o no manejado: $finalIframeSrc")
            return@coroutineScope false
        }
    }
}