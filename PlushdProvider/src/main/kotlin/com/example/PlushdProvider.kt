package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import android.util.Base64

class PlushdProvider :MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "PlusHD"
    override var lang = "es"
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
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}")
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/doramas"),

            )

        try {
            urls.apmap { (name, url) ->
                Log.d("PlushdProvider", "DEBUG: Obteniendo datos para la lista: $name de $url")
                val doc = app.get(url).document
                val home = doc.select(".articlesList article").map { article ->
                    val title = article.selectFirst("a h2")?.text()
                    val link = article.selectFirst("a.itemA")?.attr("href")
                    // Fallback para 'src' si 'data-src' no está presente
                    val img = article.selectFirst("picture img")?.attr("data-src") ?: article.selectFirst("picture img")?.attr("src")

                    Log.d("PlushdProvider", "DEBUG: Elemento principal - Título: $title, Link: $link, Imagen: $img")

                    // Nota: Aquí se mantiene la lógica original que usa '!!' y no diferencia entre Movie y TvSeries
                    // Esto puede causar Type Mismatch si el link es de película y se intenta construir TvSeriesSearchResponse
                    // o Null Pointer Exception si title/link son nulos.
                    TvSeriesSearchResponse(
                        title!!,
                        link!!,
                        this.name,
                        TvType.TvSeries, // Puede ser incorrecto para películas
                        img,
                    )
                }
                items.add(HomePageList(name, home))
            }
            Log.d("PlushdProvider", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
            return HomePageResponse(items)
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
            return doc.select("article.item").map { article ->
                val title = article.selectFirst("a h2")?.text()
                val link = article.selectFirst("a.itemA")?.attr("href")
                // Fallback para 'src' si 'data-src' no está presente
                val img = article.selectFirst("picture img")?.attr("data-src") ?: article.selectFirst("picture img")?.attr("src")

                Log.d("PlushdProvider", "DEBUG: Resultado de búsqueda - Título: $title, Link: $link, Imagen: $img")

                // Nota: Mismos comentarios que en getMainPage.
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    TvType.TvSeries, // Puede ser incorrecto para películas
                    img,
                )
            }
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR en search para query '$query': ${e.message}", e)
            return emptyList()
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) : HashMap<String, List<MainTemporadaElement>>(elements)
    data class MainTemporadaElement (
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )
    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando load para URL: $url")
        try {
            val doc = app.get(url).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para load() de URL: $url")
            val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
            Log.d("PlushdProvider", "DEBUG: Tipo detectado para URL $url: $tvType")

            val title = doc.selectFirst(".slugh1")?.text() ?: ""
            Log.d("PlushdProvider", "DEBUG: Título extraído: $title")

            // Usar '?' y Elvis operator para manejar nulos de forma segura
            val backimage = doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""
            Log.d("PlushdProvider", "DEBUG: Imagen de fondo extraída: $backimage")

            val poster = backimage.replace("original", "w500")
            Log.d("PlushdProvider", "DEBUG: Póster derivado: $poster")

            // Usar '?' y Elvis operator para manejar nulos de forma segura
            val description = doc.selectFirst("div.description")?.text() ?: ""
            Log.d("PlushdProvider", "DEBUG: Descripción extraída (primeros 100 chars): ${description.take(100)}")

            val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
            Log.d("PlushdProvider", "DEBUG: Tags extraídos: $tags")

            val epi = ArrayList<Episode>()
            if (tvType == TvType.TvSeries) {
                Log.d("PlushdProvider", "DEBUG: Contenido es TvSeries. Buscando temporadas/episodios.")
                val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
                if(!script.isNullOrEmpty()){
                    Log.d("PlushdProvider", "DEBUG: Script 'seasonsJson' encontrado.")

                    // *** INICIO DE LA MODIFICACIÓN CLAVE PARA EXTRAER JSON ***
                    // Regex para capturar el objeto JSON completo después de 'seasonsJson = '
                    // Busca el "{", luego cualquier cosa que no sea un ";", hasta el "}" final, seguido de ";"
                    val jsonRegex = Regex("seasonsJson\\s*=\\s*(\\{[^;]*\\});")
                    val match = jsonRegex.find(script)

                    var jsonscript: String? = null
                    if (match != null) {
                        jsonscript = match.groupValues[1] // Captura el contenido del grupo 1 (el JSON)
                        Log.d("PlushdProvider", "DEBUG: JSON de temporadas extraído con Regex (primeros 200 chars): ${jsonscript.take(200)}") // Log más caracteres
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: Regex 'seasonsJson' no encontró el patrón esperado. Cayendo a substringAfter/Before.")
                        // Fallback al método antiguo si la regex falla (aunque el antiguo ya fallaba)
                        jsonscript = script.substringAfter("seasonsJson = ").substringBefore(";")
                        Log.w("PlushdProvider", "ADVERTENCIA: JSON con substringAfter/Before (primeros 200 chars): ${jsonscript.take(200)}")
                    }

                    if (!jsonscript.isNullOrEmpty()){
                        try {
                            // Eliminamos el intento de escapar comillas dobles por ahora.
                            // Si el problema es una truncación, el escape no ayuda y puede introducir nuevos errores.
                            // Nos enfocamos en la extracción correcta del JSON completo.

                            val json = parseJson<MainTemporada>(jsonscript)
                            Log.d("PlushdProvider", "DEBUG: JSON de temporadas parseado exitosamente.")
                            json.values.map { list ->
                                list.map { info ->
                                    val epTitle = info.title
                                    val seasonNum = info.season
                                    val epNum = info.episode
                                    val img = info.image
                                    val realimg = if (img.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                                    val epurl = "$url/season/$seasonNum/episode/$epNum"
                                    Log.d("PlushdProvider", "DEBUG: Añadiendo episodio: S:$seasonNum E:$epNum Título: $epTitle, URL: $epurl, Imagen: $realimg")
                                    epi.add(
                                        Episode(
                                            epurl,
                                            epTitle,
                                            seasonNum,
                                            epNum,
                                            realimg,
                                        ))
                                }
                            }
                            Log.d("PlushdProvider", "DEBUG: Total de episodios añadidos: ${epi.size}")
                        } catch (e: Exception) {
                            Log.e("PlushdProvider", "ERROR al parsear JSON de temporadas: ${e.message}", e)
                            Log.e("PlushdProvider", "JSON que causó el error (posiblemente truncado): ${jsonscript?.take(500)}") // Log el JSON problemático completo
                        }
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: jsonscript vacío después de la extracción para URL: $url")
                    }
                } else {
                    Log.w("PlushdProvider", "ADVERTENCIA: Script 'seasonsJson' no encontrado o vacío para TvSeries en URL: $url")
                }
            }

            Log.d("PlushdProvider", "DEBUG: Devolviendo LoadResponse para tipo: $tvType")
            return when(tvType)
            {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title,
                        url, tvType, epi,){
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, tvType, url){
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                else -> {
                    Log.e("PlushdProvider", "ERROR: Tipo de contenido no soportado o desconocido para URL: $url")
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
        try {
            val doc = app.get(data).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para loadLinks de data: $data")
            val serversFound = doc.select("div ul.subselect li")
            if (serversFound.isEmpty()) {
                Log.w("PlushdProvider", "ADVERTENCIA: No se encontraron elementos 'div ul.subselect li' en loadLinks para data: $data")
            } else {
                Log.d("PlushdProvider", "DEBUG: Se encontraron ${serversFound.size} servidores.")
            }

            serversFound.apmap { serverLi ->
                val serverData = serverLi.attr("data-server")
                Log.d("PlushdProvider", "DEBUG: Procesando servidor con data-server: $serverData")
                val encodedOne = serverData.toByteArray()
                val encodedTwo = base64Encode(encodedOne)
                val playerUrl = "$mainUrl/player/$encodedTwo"
                Log.d("PlushdProvider", "DEBUG: URL del reproductor generada: $playerUrl")

                try {
                    val text = app.get(playerUrl).text
                    val linkRegex = Regex("window\\.location\\.href\\s*=\\s*'(.*)'")
                    val link = linkRegex.find(text)?.destructured?.component1()

                    if (link != null) {
                        Log.d("PlushdProvider", "DEBUG: Enlace extraído del reproductor: $link")
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                        Log.d("PlushdProvider", "DEBUG: loadExtractor llamado para: $link")
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: No se pudo extraer el enlace del reproductor de $playerUrl. Contenido (primeros 200 chars): ${text.take(200)}")
                    }
                } catch (e: Exception) {
                    Log.e("PlushdProvider", "ERROR al procesar URL del reproductor $playerUrl: ${e.message}", e)
                }
            }
            Log.d("PlushdProvider", "DEBUG: loadLinks finalizado.")
            return true
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR GENERAL en loadLinks para data '$data': ${e.message}", e)
            return false
        }
    }

}