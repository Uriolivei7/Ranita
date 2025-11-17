package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.delay
import java.net.URL

class PlushdProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "PlusHD"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}")
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/doramas"),
        )

        try {
            urls.map { (name, url) ->
                Log.d("PlushdProvider", "DEBUG: Obteniendo datos para la lista: $name de $url")
                val doc = app.get(url).document
                val home = doc.select(".articlesList article").mapNotNull { article ->
                    val title = article.selectFirst("a h2")?.text()
                    val link = article.selectFirst("a.itemA")?.attr("href")
                    val img = article.selectFirst("picture img")?.attr("data-src") ?: article.selectFirst("picture img")?.attr("src")

                    if (title.isNullOrEmpty() || link.isNullOrEmpty()) {
                        Log.w("PlushdProvider", "WARN: Elemento principal con título o link nulo/vacío, saltando. Título: $title, Link: $link")
                        return@mapNotNull null
                    }

                    Log.d("PlushdProvider", "DEBUG: Elemento principal - Título: $title, Link: $link, Imagen: $img")

                    val searchType = when {
                        link.contains("/serie") -> TvType.TvSeries
                        link.contains("/pelicula") -> TvType.Movie
                        link.contains("/anime") -> TvType.Anime
                        link.contains("/dorama") -> TvType.AsianDrama
                        else -> {
                            Log.w("PlushdProvider", "WARN: Tipo de TV desconocido para link: $link, asumiendo TvSeries.")
                            TvType.TvSeries
                        }
                    }

                    when (searchType) {
                        TvType.Movie -> {
                            newMovieSearchResponse(name = title, url = link, type = searchType) {
                                this.posterUrl = img
                            }
                        }
                        TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> {
                            newTvSeriesSearchResponse(name = title, url = link, type = searchType) {
                                this.posterUrl = img
                            }
                        }
                        else -> null
                    }
                }
                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                } else {
                    Log.w("PlushdProvider", "WARN: La lista '$name' está vacía después de filtrar elementos nulos.")
                }
            }
            Log.d("PlushdProvider", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
            if (items.isEmpty()) {
                throw ErrorLoadingException("No se pudieron cargar elementos en la página principal.")
            }
            return newHomePageResponse(items)
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR en getMainPage: ${e.message}", e)
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("PlushdProvider", "DEBUG: Iniciando search para query: $query")
        val url = "$mainUrl/api/search/$query"
        try {
            val doc = app.get(url).document
            Log.d("PlushdProvider", "DEBUG: Documento de búsqueda obtenido para query: $query")
            return doc.select("article.item").mapNotNull { article ->
                val title = article.selectFirst("a h2")?.text()
                val link = article.selectFirst("a.itemA")?.attr("href")
                val img = article.selectFirst("picture img")?.attr("data-src") ?: article.selectFirst("picture img")?.attr("src")

                if (title.isNullOrEmpty() || link.isNullOrEmpty()) {
                    Log.w("PlushdProvider", "WARN: Resultado de búsqueda con título o link nulo/vacío, saltando. Título: $title, Link: $link")
                    return@mapNotNull null
                }

                Log.d("PlushdProvider", "DEBUG: Resultado de búsqueda - Título: $title, Link: $link, Imagen: $img")

                val searchType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/serie") -> TvType.TvSeries
                    link.contains("/anime") -> TvType.Anime
                    link.contains("/dorama") -> TvType.AsianDrama
                    else -> {
                        Log.w("PlushdProvider", "WARN: Tipo de TV desconocido para link de búsqueda: $link, asumiendo TvSeries.")
                        TvType.TvSeries
                    }
                }

                when (searchType) {
                    TvType.Movie -> {
                        newMovieSearchResponse(name = title, url = link, type = searchType) {
                            this.posterUrl = img
                        }
                    }
                    TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> {
                        newTvSeriesSearchResponse(name = title, url = link, type = searchType) {
                            this.posterUrl = img
                        }
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR en search para query '$query': ${e.message}", e)
            return emptyList()
        }
    }

    data class MainTemporadaElement (
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando load para URL: $url")
        try {
            val doc = app.get(url).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para load() de URL: $url")

            val tvType = when {
                url.contains("/pelicula") -> TvType.Movie
                url.contains("/serie") -> TvType.TvSeries
                url.contains("/anime") -> TvType.Anime
                url.contains("/dorama") -> TvType.AsianDrama
                else -> {
                    Log.w("PlushdProvider", "WARN: Tipo de URL desconocido para load(): $url, asumiendo TvSeries.")
                    TvType.TvSeries
                }
            }
            Log.d("PlushdProvider", "DEBUG: Tipo detectado para URL $url: $tvType")

            val title = doc.selectFirst(".slugh1")?.text()
            if (title.isNullOrEmpty()) {
                Log.e("PlushdProvider", "ERROR: Título no encontrado para URL: $url")
                return null
            }
            Log.d("PlushdProvider", "DEBUG: Título extraído: $title")

            val backimage = doc.selectFirst("head meta[property=og:image]")?.attr("content")
            Log.d("PlushdProvider", "DEBUG: Imagen de fondo extraída: $backimage")

            val poster = backimage?.replace("original", "w500")
            Log.d("PlushdProvider", "DEBUG: Póster derivado: $poster")

            val description = doc.selectFirst("div.description")?.text()
            Log.d("PlushdProvider", "DEBUG: Descripción extraída (primeros 100 chars): ${description?.take(100)}")

            val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
            Log.d("PlushdProvider", "DEBUG: Tags extraídos: $tags")

            val epi = ArrayList<Episode>()
            if (tvType == TvType.TvSeries || tvType == TvType.Anime || tvType == TvType.AsianDrama) {
                Log.d("PlushdProvider", "DEBUG: Contenido es TvSeries/Anime/AsianDrama. Buscando temporadas/episodios.")
                val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
                if (!script.isNullOrEmpty()){
                    Log.d("PlushdProvider", "DEBUG: Script 'seasonsJson' encontrado.")

                    val jsonRegex = Regex("seasonsJson\\s*=\\s*(\\{[^;]*\\});")
                    val match = jsonRegex.find(script)
                    val jsonscript: String? = match?.groupValues?.get(1)

                    if (!jsonscript.isNullOrEmpty()){
                        try {
                            val seasonsMap = parseJson<Map<String, List<MainTemporadaElement>>>(jsonscript)
                            Log.d("PlushdProvider", "DEBUG: JSON de temporadas parseado exitosamente como Map<String, List<MainTemporadaElement>>.")

                            seasonsMap.forEach { (seasonKey, episodesInSeason) ->
                                Log.d("PlushdProvider", "DEBUG: Procesando temporada clave: $seasonKey con ${episodesInSeason.size} episodios.")
                                episodesInSeason.forEach { info ->
                                    val epTitle = info.title
                                    val seasonNum = info.season
                                    val epNum = info.episode
                                    val img = info.image
                                    val realimg = if (img.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                                    val epurl = "$url/season/$seasonNum/episode/$epNum"

                                    if (epTitle.isNullOrEmpty() || seasonNum == null || epNum == null) {
                                        Log.w("PlushdProvider", "WARN: Datos de episodio incompletos o nulos, saltando. Título: $epTitle, Temporada: $seasonNum, Episodio: $epNum")
                                        return@forEach
                                    }

                                    Log.d("PlushdProvider", "DEBUG: Añadiendo episodio: S:$seasonNum E:$epNum Título: $epTitle, URL: $epurl, Imagen: $realimg")
                                    epi.add(
                                        newEpisode(epurl) {
                                            this.name = epTitle
                                            this.season = seasonNum
                                            this.episode = epNum
                                            this.posterUrl = realimg
                                            this.description = null
                                            this.runTime = null
                                        }
                                    )
                                }
                            }
                            Log.d("PlushdProvider", "DEBUG: Total de episodios añadidos: ${epi.size}")
                        } catch (e: Exception) {
                            Log.e("PlushdProvider", "ERROR al parsear JSON de temporadas o procesar episodios: ${e.message}", e)
                            Log.e("PlushdProvider", "JSON que causó el error (posiblemente truncado): ${jsonscript.take(1000)}")
                        }
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: jsonscript vacío después de la extracción para URL: $url")
                    }
                } else {
                    Log.w("PlushdProvider", "ADVERTENCIA: Script 'seasonsJson' no encontrado o vacío para TvSeries en URL: $url")
                }
            }

            Log.d("PlushdProvider", "DEBUG: Devolviendo LoadResponse para tipo: $tvType")
            return when(tvType) {
                TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> {
                    newTvSeriesLoadResponse(title, url, tvType, epi) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, tvType, url) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                else -> {
                    Log.e("PlushdProvider", "ERROR: Tipo de contenido no soportado o desconocido para URL: $url después del fallback.")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR GENERAL en load() para URL $url: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlushdProvider", "DEBUG: Iniciando loadLinks para data: $data")
        var linksFound = false
        try {
            val doc = app.get(data).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para loadLinks de data: $data")

            val serversFound = doc.select("div ul.subselect li")

            if (serversFound.isEmpty()) {
                Log.w("PlushdProvider", "ADVERTENCIA: No se encontraron elementos 'div ul.subselect li' en loadLinks para data: $data")
            } else {
                Log.d("PlushdProvider", "DEBUG: Se encontraron ${serversFound.size} servidores.")
            }

            val serverList = serversFound.toList()

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to data
            )

            serverList.forEach { serverLi ->
                val serverData = serverLi.attr("data-server")
                if (serverData.isNullOrEmpty()) {
                    Log.w("PlushdProvider", "WARN: data-server es nulo o vacío para elemento: $serverLi, saltando.")
                    return@forEach
                }
                Log.d("PlushdProvider", "DEBUG: Procesando servidor con data-server: $serverData")

                try {
                    val encodedOne = serverData.toByteArray()
                    val encodedTwo = base64Encode(encodedOne)
                    val playerUrl = "$mainUrl/player/$encodedTwo"
                    Log.d("PlushdProvider", "DEBUG: URL del reproductor generada: $playerUrl")

                    val text = app.get(playerUrl, headers = headers).text

                    if (text.contains("bloqueo temporal")) {
                        Log.w("PlushdProvider", "ADVERTENCIA: Bloqueo temporal detectado para el servidor: $serverData. Saltando servidor.")
                        return@forEach
                    }

                    val linkRegex = Regex("window\\.location\\.href\\s*=\\s*'([^']*)'")
                    val link = linkRegex.find(text)?.destructured?.component1()

                    if (!link.isNullOrEmpty()) {
                        Log.d("PlushdProvider", "DEBUG: Enlace extraído del reproductor: $link")

                        val host = URL(link).host

                        if (host.contains("vidhideplus.com") || host.contains("earnvids.com") || host.contains("dinisglows.com")) {
                            Log.d("PlushdProvider", "DEBUG: Enlace detectado como Vidhide/Earnvids/Dinisglows. Confiando en el extractor nativo para subtítulos.")
                        }

                        loadExtractor(link, playerUrl, subtitleCallback, callback)
                        linksFound = true
                        Log.d("PlushdProvider", "DEBUG: loadExtractor llamado para: $link con Referer: $playerUrl")
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: No se pudo extraer el enlace del reproductor de $playerUrl. Contenido (primeros 200 chars): ${text.take(200)}")
                    }
                } catch (e: Exception) {
                    Log.e("PlushdProvider", "ERROR al procesar URL del reproductor $serverData: ${e.message}", e)
                }

                // delay(1500L)
            }

            Log.d("PlushdProvider", "DEBUG: loadLinks finalizado. Enlaces encontrados: $linksFound")
            return linksFound
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR GENERAL en loadLinks para data '$data': ${e.message}", e)
            return false
        }
    }
}