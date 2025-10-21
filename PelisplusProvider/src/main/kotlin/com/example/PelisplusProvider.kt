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
import com.google.gson.annotations.SerializedName
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable

class PelisplusProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "PelisPlus"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/generos/dorama"),
        )

        val homePageLists = coroutineScope {
            urls.map { (name, url) ->
                async {
                    Log.d("Pelisplus", "getMainPage - Procesando categoría: $name desde URL: $url")

                    val tvType = when (name) {
                        "Películas" -> TvType.Movie
                        "Series" -> TvType.TvSeries
                        "Animes" -> TvType.Anime
                        "Doramas" -> TvType.TvSeries
                        else -> TvType.Others
                    }
                    val doc = try {
                        app.get(url).document
                    } catch (e: Exception) {
                        Log.e("Pelisplus", "getMainPage - ERROR al obtener documento de $url: ${e.message}", e)
                        return@async null
                    }

                    Log.d("Pelisplus", "getMainPage - Documento obtenido para $name. Intentando seleccionar posters con nuevo selector.")
                    val homeItems = doc.select("div.Posters a.Posters-link").mapNotNull { element ->
                        val title = element.attr("data-title")
                        val link = element.attr("href")
                        val img = element.selectFirst("img.Posters-img")?.attr("src")

                        if (title.isNullOrBlank() || link.isNullOrBlank()) {
                            Log.d("Pelisplus", "getMainPage - Elemento de poster sin título o link. HTML: ${element.html()}")
                            null
                        } else {
                            val fixedLink = fixUrl(link)
                            val fixedImg = fixUrl(img ?: "")

                            Log.d("Pelisplus", "getMainPage - Encontrado: Título=$title, Link=$fixedLink, Img=$fixedImg")
                            newAnimeSearchResponse(
                                title,
                                fixedLink
                            ) {
                                this.type = tvType
                                this.posterUrl = fixedImg
                            }
                        }
                    }
                    if (homeItems.isEmpty()) {
                        Log.w("Pelisplus", "getMainPage - No se encontraron items para la categoría $name en la URL $url con el nuevo selector.")
                    } else {
                        Log.d("Pelisplus", "getMainPage - Encontrados ${homeItems.size} items para la categoría $name.")
                    }
                    HomePageList(name, homeItems)
                }
            }.awaitAll().filterNotNull()
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val doc = app.get(url).document

        return doc.select("div.Posters a.Posters-link").mapNotNull {
            val title = it.attr("data-title")
            val link = it.attr("href")
            val img = it.selectFirst("img.Posters-img")?.attr("src")

            if (title.isNullOrBlank() || link.isNullOrBlank()) {
                Log.d("Pelisplus", "Search - Elemento de poster sin título o link en búsqueda. HTML: ${it.html()}")
                null
            } else {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
                    this.posterUrl = img
                }
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Pelisplus", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Pelisplus", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Pelisplus", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Pelisplus", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Pelisplus", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = if (cleanUrl.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("h1.m-b-5")?.text() ?: ""
        val poster = doc.selectFirst("img.img-fluid")?.attr("src") ?: ""
        val description = doc.selectFirst("div.text-large")?.text() ?: ""
        val tags = doc.select("a[title^=Películas del Genero]").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div.tab-content div.tab-pane a.btn.btn-primary.btn-block").mapNotNull { element ->
                val epurl = fixUrl(element.attr("href") ?: "")
                val fullEpTitle = element.text() ?: ""

                var seasonNumber: Int? = null
                var episodeNumber: Int? = null
                var epTitle: String = fullEpTitle

                val regex = Regex("""T(\d+)\s*-\s*E(\d+):\s*(.*)""")
                val match = regex.find(fullEpTitle)

                if (match != null) {
                    seasonNumber = match.groupValues[1].toIntOrNull()
                    episodeNumber = match.groupValues[2].toIntOrNull()
                    epTitle = match.groupValues[3].trim()
                } else {
                    val simpleEpRegex = Regex("""Episodio\s*(\d+)""")
                    val simpleMatch = simpleEpRegex.find(fullEpTitle)
                    if (simpleMatch != null) {
                        episodeNumber = simpleMatch.groupValues[1].toIntOrNull()
                    }
                    if (seasonNumber == null) {
                        val activeSeasonTab = doc.selectFirst("ul.tbVideoNv.nav-tabs li a.nav-link.active")
                        val seasonText = activeSeasonTab?.text()
                        val seasonRegex = Regex("""TEMPORADA\s*(\d+)""")
                        val seasonMatch = seasonRegex.find(seasonText ?: "")
                        seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    }
                }

                val realimg = poster

                if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                    Log.d("Pelisplus", "load (Series) - Encontrado episodio: Título=$epTitle, URL=$epurl, Temporada=$seasonNumber, Episodio=$episodeNumber")
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = realimg
                    }
                } else {
                    Log.d("Pelisplus", "load (Series) - Elemento de episodio sin URL o título. HTML: ${element.html()}")
                    null
                }
            }
        } else listOf()

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
                }
            }

            else -> null
        }
    }

    @Serializable
    data class SortedEmbed(
        @SerializedName("type") val type: String? = null,
        @SerializedName("link") val link: String? = null, // Enlace cifrado
        @SerializedName("servername") val servername: String? = null,
        @SerializedName("download") val download: String? = null
    )

    @Serializable
    data class DataLinkEntry(
        @SerializedName("serverName") val serverName: String? = null,
        @SerializedName("sortedEmbeds") val sortedEmbeds: List<SortedEmbed>? = emptyList()
    )

    // Estructuras para la solicitud (Request) a la API de descifrado
    @Serializable
    data class DecryptRequestBody(
        @SerializedName("links") val links: List<String> // Lista de enlaces cifrados (JWTs)
    )

    // Estructuras para la respuesta (Response) de la API de descifrado
    @Serializable
    data class DecryptedLink(
        @SerializedName("id") val id: Int? = null,
        @SerializedName("link") val link: String? = null // Enlace descifrado final (ej. moevideo.net)
    )

    @Serializable
    data class DecryptionResponse(
        @SerializedName("success") val success: Boolean? = false,
        @SerializedName("reason") val reason: String? = null,
        @SerializedName("links") val links: List<DecryptedLink>? = emptyList()
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        Log.d("Pelisplus", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Pelisplus", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Pelisplus", "loadLinks - Regex inicial no encontró coincidencia. Usando data original/ajustada: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Pelisplus", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Pelisplus", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Pelisplus", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return@coroutineScope false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("Pelisplus", "loadLinks - Error al obtener documento de la URL principal: ${e.message}")
            return@coroutineScope false
        }

        val playerUrls = mutableListOf<String>()

        doc.selectFirst("iframe")?.attr("src")?.let { src ->
            if (src.isNotBlank()) {
                playerUrls.add(src)
                Log.d("Pelisplus", "Iframe encontrado directamente en el HTML: $src")
            }
        }

        val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

        val embed69SrcRegex = Regex("""src=["']([^"']+)embed69\.org([^"']*)["']""")
        embed69SrcRegex.findAll(scriptContent).forEach { matchResult ->
            val src = matchResult.groupValues[1] + "embed69.org" + matchResult.groupValues[2]
            playerUrls.add(src.replace("\\/", "/"))
            Log.d("Pelisplus", "Found embed69.org URL in script (src='...'): ${src.replace("\\/", "/")}")
        }

        val xupalaceSrcRegex = Regex("""src=["']([^"']+)xupalace\.org([^"']*)["']""")
        xupalaceSrcRegex.findAll(scriptContent).forEach { matchResult ->
            val src = matchResult.groupValues[1] + "xupalace.org" + matchResult.groupValues[2]
            playerUrls.add(src.replace("\\/", "/"))
            Log.d("Pelisplus", "Found xupalace.org URL in script (src='...'): ${src.replace("\\/", "/")}")
        }

        val videoVarRegex = Regex("""video\[(\d+)\]\s*=\s*['"](https?:\/\/[^"']+)['"]""")
        videoVarRegex.findAll(scriptContent).forEach { matchResult ->
            val index = matchResult.groupValues[1]
            val url = matchResult.groupValues[2]
            playerUrls.add(url)
            Log.d("Pelisplus", "Found video[$index] URL in script: $url")
        }

        if (playerUrls.isEmpty()) {
            Log.d("Pelisplus", "FINALMENTE: No se encontró ninguna URL de reproductor inicial en Pelisplus.")
            return@coroutineScope false
        }

        Log.d("Pelisplus", "URLs de reproductores iniciales encontradas: $playerUrls")

        var linksFound = false
        val jobs = playerUrls.map { playerUrl ->
            async {
                Log.d("Pelisplus", "Procesando URL de reproductor: $playerUrl")

                if (playerUrl.contains("embed69.org")) {
                    Log.d("Pelisplus", "loadLinks - Detectado embed69.org. Usando lógica de API.")

                    val embedRes = try {
                        app.get(fixUrl(playerUrl), referer = targetUrl)
                    } catch (e: Exception) {
                        Log.e("Pelisplus", "Error al obtener contenido de embed69.org: ${e.message}")
                        return@async false
                    }

                    if (!embedRes.isSuccessful) {
                        Log.e("Pelisplus", "Fallo al cargar la página de embed69.org. Código: ${embedRes.code}")
                        return@async false
                    }

                    val frameDoc = Jsoup.parse(embedRes.text)
                    var scriptContent: String? = null

                    for (script in frameDoc.select("script")) {
                        val content = script.html()
                        if (content.contains("let dataLink =") || content.contains("var dataLink =")) {
                            scriptContent = content
                            break
                        }
                    }

                    if (scriptContent.isNullOrBlank()) {
                        Log.e("Pelisplus", "ERROR: No se encontró el script que define 'dataLink'.")
                        return@async false
                    }

                    val dataLinkRegex = Regex("""dataLink\s*=\s*(\[.*?\])\s*;""")
                    val jsonMatch = dataLinkRegex.find(scriptContent)

                    if (jsonMatch == null) {
                        Log.e("Pelisplus", "ERROR: No se pudo extraer la variable dataLink JSON del script.")
                        return@async false
                    }

                    val dataLinkJson = jsonMatch.groupValues[1]
                    val files = tryParseJson<List<DataLinkEntry>>(dataLinkJson)

                    if (files.isNullOrEmpty()) {
                        Log.e("Pelisplus", "ERROR: dataLink JSON se extrajo, pero no se pudo parsear a la lista de DataLinkEntry.")
                        return@async false
                    }

                    val encryptedLinks = files.flatMap { file ->
                        (file.sortedEmbeds ?: emptyList()).flatMap { embed ->
                            listOfNotNull(embed.link, embed.download)
                        }
                    }.distinct()

                    if (encryptedLinks.isEmpty()) {
                        Log.e("Pelisplus", "ERROR: No se encontraron enlaces cifrados (JWT) en dataLink.")
                        return@async false
                    }

                    val decryptUrl = "https://embed69.org/api/decrypt"
                    val body = DecryptRequestBody(encryptedLinks).toJson()
                        .toRequestBody("application/json".toMediaTypeOrNull())

                    val decryptedRes = try {
                        app.post(decryptUrl, requestBody = body)
                    } catch (e: Exception) {
                        Log.e("Pelisplus", "Error en la llamada a la API de descifrado: ${e.message}")
                        return@async false
                    }

                    if (!decryptedRes.isSuccessful) {
                        Log.e(
                            "Pelisplus",
                            "ERROR: API de descifrado fallida con código ${decryptedRes.code}. Cuerpo: ${decryptedRes.text}"
                        )
                        return@async false
                    }

                    val decryptedData = tryParseJson<DecryptionResponse>(decryptedRes.text)
                    val finalLinks = decryptedData?.links?.mapNotNull { it.link }

                    if (decryptedData?.success != true || finalLinks.isNullOrEmpty()) {
                        Log.e(
                            "Pelisplus",
                            "ERROR: Respuesta de descifrado inválida o vacía. Razón: ${decryptedData?.reason}"
                        )
                        return@async false
                    }

                    return@async coroutineScope {
                        finalLinks.map { decryptedLink ->
                            async {
                                Log.d("Pelisplus", "Cargando extractor para enlace descifrado: $decryptedLink")
                                loadExtractor(
                                    fixUrl(decryptedLink.replace("`", "").trim()),
                                    playerUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }.awaitAll().any { it }
                    }

                } else if (playerUrl.contains("xupalace.org/video/")) {
                    Log.d("Pelisplus", "loadLinks - Detectado xupalace.org/video/ URL: $playerUrl")
                    val xupalaceDoc = try {
                        app.get(fixUrl(playerUrl), referer = targetUrl).document
                    } catch (e: Exception) {
                        Log.e("Pelisplus", "Error al obtener el contenido del reproductor de Xupalace ($playerUrl): ${e.message}")
                        return@async false
                    }

                    val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                    val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                    if (elementsWithOnclick.isEmpty()) {
                        Log.w("Pelisplus", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org/video/.")
                        return@async false
                    }

                    val xupalaceJobs = elementsWithOnclick.mapNotNull { element ->
                        val onclickAttr = element.attr("onclick")
                        val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                        if (matchPlayerUrl != null) {
                            val videoUrlFromXupalace = matchPlayerUrl.groupValues[1]
                            Log.d("Pelisplus", "Xupalace/video: Encontrado URL: $videoUrlFromXupalace")

                            async {
                                if (videoUrlFromXupalace.isNotBlank()) {
                                    if (videoUrlFromXupalace.contains("uqload.com", ignoreCase = true)) {
                                        Log.d("Pelisplus", "Xupalace/video: PRIORIDAD - Encontrado Uqload URL: $videoUrlFromXupalace")
                                    }
                                    loadExtractor(fixUrl(videoUrlFromXupalace), playerUrl, subtitleCallback, callback)
                                } else false
                            }
                        } else null
                    }
                    return@async xupalaceJobs.awaitAll().any { it }

                } else if (playerUrl.contains("xupalace.org/uqlink.php?id=")) {
                    Log.d("Pelisplus", "loadLinks - Detectado xupalace.org/uqlink.php URL (Manejo de Iframe Directo): $playerUrl")

                    val uqlinkDoc = try {
                        app.get(fixUrl(playerUrl), referer = targetUrl).document
                    } catch (e: Exception) {
                        Log.e("Pelisplus", "Error al obtener el contenido de uqlink.php ($playerUrl): ${e.message}")
                        return@async false
                    }

                    val uqloadIframeSrc = uqlinkDoc.selectFirst("iframe")?.attr("src")

                    if (!uqloadIframeSrc.isNullOrBlank() && uqloadIframeSrc.contains("uqload.io", ignoreCase = true)) {
                        Log.d("Pelisplus", "Uqlink.php: Encontrado iframe de Uqload: $uqloadIframeSrc")
                        return@async loadExtractor(fixUrl(uqloadIframeSrc), playerUrl, subtitleCallback, callback)
                    } else {
                        Log.w("Pelisplus", "Uqlink.php: No se encontró iframe de Uqload válido en $playerUrl.")
                        return@async false
                    }
                }
                else {
                    Log.w("Pelisplus", "Tipo de reproductor desconocido o no manejado directamente en Pelisplus: $playerUrl. Intentando loadExtractor genérico.")
                    return@async loadExtractor(fixUrl(playerUrl), targetUrl, subtitleCallback, callback)
                }
            }
        }

        linksFound = jobs.awaitAll().any { it }

        return@coroutineScope linksFound
    }
}