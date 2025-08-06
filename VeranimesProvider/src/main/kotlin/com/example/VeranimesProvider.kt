package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class VerAnimesProvider : MainAPI() {
    override var mainUrl = "https://wwv.veranimes.net"
    override var name = "VerAnimes"
    val referer = "$mainUrl/"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a")
        val linkHref = linkElement?.attr("href")

        val titleText = element.selectFirst("h3.h a")?.text()?.trim()
            ?: element.selectFirst("span.n b")?.text()?.trim()

        val posterElement = element.selectFirst("figure.i img")
        val img = fixUrl(posterElement?.attr("data-src") ?: posterElement?.attr("src"))

        val releaseYear = null

        if (titleText != null && linkHref != null) {
            val finalAnimeUrl: String
            val episodeMatch = Regex("""(.+?\/ver\/[^\/]+?)(?:-episodio-\d+)?(?:-\d+)?\/?$""").find(linkHref)
            if (episodeMatch != null) {
                finalAnimeUrl = episodeMatch.groupValues[1]
            } else {
                finalAnimeUrl = linkHref
            }

            Log.d("VerAnimesProvider", "extractAnimeItem - Título: $titleText, Enlace Original: $linkHref, Enlace BASE Anime: $finalAnimeUrl, Póster: $img")

            val fixedFinalAnimeUrl = fixUrl(finalAnimeUrl) ?: return null

            return newAnimeSearchResponse(
                titleText,
                fixedFinalAnimeUrl
            ) {
                this.type = TvType.Anime
                this.posterUrl = img
                this.year = releaseYear
            }
        }
        return null
    }

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("VerAnimesProvider", "safeAppGet - Éxito al obtener URL: $url")
                    return res.text
                } else {
                    Log.w("VerAnimesProvider", "safeAppGet - Falló la URL: $url con código: ${res.code}")
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "safeAppGet - Error al obtener URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("VerAnimesProvider", "safeAppGet - Reintento ${i + 1}/${retries} para URL: $url")
                delay(delayMs)
            }
        }
        Log.e("VerAnimesProvider", "safeAppGet - Falló después de $retries reintentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = mainUrl
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)
        Log.d("VerAnimesProvider", "getMainPage - HTML cargado para $url. Extrayendo listas de la página principal.")

        doc.selectFirst("div.th:has(h2.h:contains(Nuevos episodios agregados)) + div.ul.episodes")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Nuevos Episodios Agregados", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Nuevos Episodios Agregados.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Nuevos Episodios Agregados'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor para 'Nuevos episodios agregados' (div.ul.episodes) no encontrado.")

        doc.selectFirst("div.th:has(h2.h:contains(Nuevos Animes Agregados)) + div.ul.x6")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Nuevos Animes Agregados", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Nuevos Animes Agregados.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Nuevos Animes Agregados'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor 'Nuevos Animes Agregados' no encontrado.")

        Log.d("VerAnimesProvider", "getMainPage - Completado, total de listas: ${items.size}")
        return HomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/animes?buscar=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)
        Log.d("VerAnimesProvider", "search - Buscando '$query'. HTML cargado.")

        return doc.select("div.ul.x5 article.li").mapNotNull {
            val title = it.selectFirst("h3.h a")?.text()?.trim()
            val link = it.selectFirst("a")?.attr("href")
            val img = fixUrl(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))

            Log.d("VerAnimesProvider", "search - Resultado: Título: $title, Link: $link, Póster: $img")

            if (title != null && link != null) {
                val finalLink = fixUrl(link) ?: return@mapNotNull null
                newAnimeSearchResponse(
                    title,
                    finalLink
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else {
                Log.w("VerAnimesProvider", "search - Elemento de búsqueda incompleto (título o link nulo): ${it.outerHtml().take(100)}")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"(?:,"title":"[^"]+")?\}""").find(url)
        if (urlJsonMatch != null) cleanUrl = urlJsonMatch.groupValues[1]
        else if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "https://" + cleanUrl.removePrefix("//")

        val finalUrlToFetch: String? = if (Regex("""(?i)\/(?:ver|anime)\/([^\/]+)(?:-\d+)?\/?$""").find(cleanUrl)?.let { it.groupValues[1] } != null) {
            val slug = Regex("""(?i)\/(?:ver|anime)\/([^\/]+)(?:-\d+)?\/?$""").find(cleanUrl)!!.groupValues[1]
            "${mainUrl}/anime/$slug" // Aseguramos que sea la URL de información del anime (ej: /anime/wotaku-ni-koi-wa-muzukashii)
        } else {
            cleanUrl
        }

        Log.d("VerAnimesProvider", "load - URL limpia inicial: $url, URL procesada: $cleanUrl, URL base final a obtener: $finalUrlToFetch")

        val fixedFinalUrlToFetch = fixUrl(finalUrlToFetch)
        if (fixedFinalUrlToFetch.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "load - URL final para obtener está en blanco o es nula después de fixUrl: $fixedFinalUrlToFetch")
            return null
        }

        val html = app.get(fixedFinalUrlToFetch).text
        if (html.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "load - Falló la obtención del HTML para: $fixedFinalUrlToFetch")
            return null
        }
        Log.d("VerAnimesProvider", "load - HTML recibido para $fixedFinalUrlToFetch (primeros 500 caracteres): ${html.take(500)}")

        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.ti h1 strong")?.text() ?: ""
        val poster = fixUrl(doc.selectFirst("div.sc div.l figure.i img")?.attr("data-src") ?: doc.selectFirst("div.sc div.l figure.i img")?.attr("src"))
        val description = doc.selectFirst("div.tx p")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        Log.d("VerAnimesProvider", "load - Título extraído: '$title', Póster extraído: '$poster', Descripción extraída: '${description.take(50)}...'")
        if (poster.isNullOrBlank()) {
            Log.w("VerAnimesProvider", "load - ¡ADVERTENCIA! No se encontró póster con selector 'div.sc div.l figure.i img' para $fixedFinalUrlToFetch")
        }

        val localTags = doc.select("ul.gn li a").map { it.text().trim() }
        val year = doc.selectFirst("div.ti div span.a")?.text()?.trim()?.toIntOrNull()
        val localStatus = parseStatus(doc.selectFirst("div.ti div span.fi")?.text()?.trim() ?: "")

        Log.d("VerAnimesProvider", "load - Géneros/Tags: $localTags, Año: $year, Estado: $localStatus")

        val allEpisodes = ArrayList<Episode>()

        val dataSl = doc.selectFirst("div.info")?.attr("data-sl")
        val dataZr = doc.selectFirst("ul.u.sp span[data-zr]")?.attr("data-zr")
        val totalEpisodesString = doc.selectFirst("ul.u.sp span[data-ep]")?.attr("data-ep")
        val totalEpisodes = totalEpisodesString?.toIntOrNull()

        Log.d("VerAnimesProvider", "load - data-sl encontrado: $dataSl, data-zr encontrado: $dataZr, Total de episodios (data-ep): $totalEpisodes")


        var foundEpisodesFromScript = false
        if (dataSl != null) {
            val scriptContent = doc.select("script").map { it.html() }.firstOrNull { it.contains("var eps = [") }

            if (scriptContent != null) {
                val epsRegex = "var eps = \\[([^\\]]+)\\];".toRegex()
                val matchResult = epsRegex.find(scriptContent)

                if (matchResult != null) {
                    val epsString = matchResult.groupValues[1]
                    val epsArray = epsString.split(",").mapNotNull { it.trim().removeSurrounding("\"").toIntOrNull() }

                    Log.d("VerAnimesProvider", "load - `eps` array extraído del script: ${epsArray.joinToString(", ")}, data-sl: $dataSl")

                    val sortedEpsArray = epsArray.sorted()

                    for (epn in sortedEpsArray) {
                        val episodeTitle = "Episodio $epn"
                        val episodeUrl = "$mainUrl/ver/$dataSl-$epn"

                        allEpisodes.add(
                            newEpisode(EpisodeLoadData(episodeTitle, episodeUrl).toJson()) {
                                this.episode = epn
                                this.posterUrl = poster
                                this.name = episodeTitle
                                this.description = episodeTitle
                            }
                        )
                    }
                    foundEpisodesFromScript = true
                } else {
                    Log.w("VerAnimesProvider", "load - Se encontró script que contiene 'var eps', pero no se pudo parsear el array para $fixedFinalUrlToFetch.")
                }
            } else {
                Log.w("VerAnimesProvider", "load - No se encontró ningún script que contenga 'var eps = [' en el HTML para $fixedFinalUrlToFetch. Esto es normal si la página no lo incluye.")
            }
        } else {
            Log.w("VerAnimesProvider", "load - data-sl es nulo, no se puede intentar extraer episodios del script.")
        }

        if (!foundEpisodesFromScript && dataSl != null && totalEpisodes != null && totalEpisodes > 0) {
            Log.d("VerAnimesProvider", "load - Generando episodios con data-ep como fallback. Total: $totalEpisodes")
            for (epn in 1..totalEpisodes) {
                val episodeTitle = "Episodio $epn"
                val episodeUrl = "$mainUrl/ver/$dataSl-$epn"

                allEpisodes.add(
                    newEpisode(EpisodeLoadData(episodeTitle, episodeUrl).toJson()) {
                        this.episode = epn
                        this.posterUrl = poster
                        this.name = episodeTitle
                        this.description = episodeTitle
                    }
                )
            }
        } else if (!foundEpisodesFromScript && (dataSl == null || totalEpisodes == null || totalEpisodes <= 0)) {
            Log.w("VerAnimesProvider", "load - Fallback con data-ep no se pudo usar (data-sl nulo, o totalEpisodes inválido/cero). No se generarán episodios en este caso.")
        }

        Log.d("VerAnimesProvider", "load - Total de episodios encontrados: ${allEpisodes.size}")

        val recommendations = doc.select("aside#r div.ul.x2 article.li").mapNotNull { element ->
            val recTitle = element.selectFirst("h3.h a")?.text()?.trim()
            val recLink = element.selectFirst("a")?.attr("href")
            val recImg = fixUrl(element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src"))

            if (recTitle != null && recLink != null) {
                val finalRecLink = fixUrl(recLink) ?: return@mapNotNull null
                newAnimeSearchResponse(
                    recTitle,
                    finalRecLink
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = recImg
                }
            } else {
                Log.w("VerAnimesProvider", "load - Recomendación incompleta (título o link nulo): ${element.outerHtml().take(100)}")
                null
            }
        }
        Log.d("VerAnimesProvider", "load - Total de recomendaciones encontradas: ${recommendations.size} ")

        val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
        if (allEpisodes.isNotEmpty()) {
            episodesMap[DubStatus.Subbed] = allEpisodes
        }

        return newAnimeLoadResponse(
            name = title,
            url = fixedFinalUrlToFetch,
            type = TvType.Anime
        ) {
            episodes = episodesMap
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            tags = localTags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data) ?: return false

        Log.d("VerAnimesProvider", "loadLinks - URL a cargar: $targetUrl")

        if (targetUrl.isBlank()) {
            Log.e("VerAnimesProvider", "loadLinks - targetUrl está en blanco para data: $data")
            return false
        }

        val episodeHtml = try {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
                    "Referer" to referer
                )
            ).text
        } catch (e: Exception) {
            Log.e("VerAnimesProvider", "loadLinks - Falló la obtención del HTML de la página del episodio: $targetUrl", e)
            return false
        }

        val episodeDoc = Jsoup.parse(episodeHtml)

        val dataEncryptValue = episodeDoc.selectFirst(".opt")?.attr("data-encrypt")

        if (dataEncryptValue.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "loadLinks - No se pudo extraer data-encrypt del elemento .opt de la página del episodio: $targetUrl")
            return false
        }

        Log.d("VerAnimesProvider", "loadLinks - data-encrypt extraído de la página del episodio: $dataEncryptValue")

        val postData = "acc=opt&i=$dataEncryptValue"
        Log.d("VerAnimesProvider", "loadLinks - Datos POST calculados para /process: $postData")

        val headersForPost = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to targetUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Content-Length" to postData.length.toString()
        )

        val playerLinksHtml = try {
            app.post(
                url = "$mainUrl/process",
                requestBody = RequestBody.create("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull(), postData),
                headers = headersForPost,
                interceptor = cfKiller
            ).text
        } catch (e: Exception) {
            Log.e("VerAnimesProvider", "loadLinks - Falló la petición POST a /process para obtener reproductores: ${e.message}", e)
            return false
        }

        if (playerLinksHtml.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "loadLinks - Respuesta HTML de /process está en blanco.")
            return false
        }

        Log.d("VerAnimesProvider", "loadLinks - HTML de la respuesta POST recibido (primeros 500 caracteres): ${playerLinksHtml.take(500)}")

        val playerDoc = Jsoup.parse(playerLinksHtml)
        var linksFound = false

        val optionsList = playerDoc.select("li[encrypt]")

        if (optionsList.isNotEmpty()) {
            Log.d("VerAnimesProvider", "loadLinks - ENCONTRADAS ${optionsList.size} opciones de REPRODUCCIÓN en la respuesta POST.")
            optionsList.forEach { liElement ->
                val encryptedUrlHex = liElement.attr("encrypt")
                val serverName = liElement.select("span").firstOrNull()?.text()?.trim()
                    ?.ifBlank { liElement.select("span").getOrNull(1)?.text()?.trim()?.ifBlank { liElement.attr("title").ifBlank { "Servidor Desconocido" } } }
                    ?: liElement.attr("title").ifBlank { "Servidor Desconocido" }

                if (encryptedUrlHex.isNotBlank()) {
                    try {
                        val decryptedUrl = hex2a(encryptedUrlHex)
                        val fixedDecryptedUrl = fixUrl(decryptedUrl)

                        if (fixedDecryptedUrl != null && fixedDecryptedUrl.isNotBlank()) {
                            Log.d("VerAnimesProvider", "loadLinks - Extractor de link desencriptado para '$serverName': $fixedDecryptedUrl")
                            loadExtractor(fixedDecryptedUrl, targetUrl, subtitleCallback, callback)
                            linksFound = true
                        } else {
                            Log.w("VerAnimesProvider", "loadLinks - URL decodificada vacía o nula para '$serverName'. Original HEX: '$encryptedUrlHex', Decodificada: '$decryptedUrl'")
                        }
                    } catch (e: Exception) {
                        Log.e("VerAnimesProvider", "loadLinks - Error al decodificar URL hex para '$serverName' ('$encryptedUrlHex'): ${e.message}", e)
                    }
                } else {
                    Log.w("VerAnimesProvider", "loadLinks - Opción para '$serverName' no tiene atributo 'encrypt'.")
                }
            }
        } else {
            Log.w("VerAnimesProvider", "loadLinks - NO SE ENCONTRÓ ninguna opción de REPRODUCCIÓN 'li[encrypt]' en la respuesta POST. Esto es un problema.")
        }

        val downloadDataElement = episodeDoc.selectFirst("a.d[data-dwn]")
        downloadDataElement?.attr("data-dwn")?.let { downloadData ->
            Log.d("VerAnimesProvider", "loadLinks - Botón de DESCARGA encontrado en HTML inicial, data-dwn: '$downloadData'")
            try {
                val jsonArray = tryParseJson<List<List<Any>>>(downloadData)
                jsonArray?.forEach { entry ->
                    if (entry.size >= 3 && entry[2] is String) {
                        val downloadUrl = entry[2] as String
                        val downloadServerName = entry[0] as? String ?: "Enlace de Descarga (data-dwn)"
                        val fixedDownloadUrl = fixUrl(downloadUrl)
                        if (fixedDownloadUrl != null && fixedDownloadUrl.isNotBlank()) {
                            Log.d("VerAnimesProvider", "loadLinks - Extractor del enlace de DESCARGA '$downloadServerName': $fixedDownloadUrl")
                            loadExtractor(fixedDownloadUrl, targetUrl, subtitleCallback, callback)
                            linksFound = true
                        } else {
                            Log.w("VerAnimesProvider", "loadLinks - URL de descarga vacía o nula para servidor '$downloadServerName' (data-dwn).")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "loadLinks - Error al parsear los datos de descarga (data-dwn): ${e.message}", e)
            }
        } ?: Log.w("VerAnimesProvider", "loadLinks - Botón de DESCARGA con 'data-dwn' no encontrado en el HTML inicial.")

        val playerIframe = episodeDoc.selectFirst("div.ply iframe")
        if (playerIframe != null) {
            var iframeSrc = playerIframe.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                if (iframeSrc.contains("/reproductor/v.php")) {
                    Log.w("VerAnimesProvider", "loadLinks - Iframe principal encontrado con URL de reproductor interno: '$iframeSrc'. Requiere manejo adicional para extraer enlaces directos.")
                } else {
                    val fixedIframeSrc = fixUrl(iframeSrc)
                    if (fixedIframeSrc != null) {
                        Log.d("VerAnimesProvider", "loadLinks - Extrayendo del iframe principal (URL directa): $fixedIframeSrc")
                        loadExtractor(fixedIframeSrc, targetUrl, subtitleCallback, callback)
                        linksFound = true
                    } else {
                        Log.w("VerAnimesProvider", "loadLinks - URL de iframe principal es nula después de fixUrl.")
                    }
                }
            } else {
                Log.w("VerAnimesProvider", "loadLinks - El src del iframe del reproductor principal es nulo/vacío.")
            }
        } else {
            Log.w("VerAnimesProvider", "loadLinks - No se encontró el iframe del reproductor principal con el selector 'div.ply iframe' en el HTML inicial.")
        }

        Log.d("VerAnimesProvider", "loadLinks - Finalizado, enlaces encontrados: $linksFound")
        return linksFound
    }


    private fun hex2a(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val charCode = hex.substring(i, i + 2).toInt(16)
            sb.append(charCode.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun parseStatus(statusString: String?): Int? {
        return when (statusString?.lowercase()) {
            "En Emisión" -> 1
            "finalizado" -> 2
            else -> null
        }
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("./")) {
            mainUrl + url.substring(1)
        } else if (url.startsWith("/")) {
            mainUrl + url
        } else {
            "$mainUrl/$url"
        }
    }

    private fun String.hexToString(): String {
        return chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    }
}