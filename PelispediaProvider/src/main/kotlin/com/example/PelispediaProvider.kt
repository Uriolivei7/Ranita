package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log

class PelispediaProvider:MainAPI() {
    override var mainUrl = "https://pelispedia.is"
    override var name = "Pelispedia"
    override var lang = "mx"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse? {
        Log.d("PelispediaProvider", "DEBUG: Iniciando getMainPage, página: $page")
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas","$mainUrl/cartelera-peliculas/"),
            Pair("Series","$mainUrl/cartelera-series/"),
        )
        try {
            urls.map { (name, url) ->
                Log.d("PelispediaProvider", "DEBUG: Obteniendo datos para la lista: $name de $url")
                val doc = app.get(url).document
                val home =  doc.select("section.movies article").mapNotNull { article ->
                    val title = article.selectFirst("h2.entry-title")?.text()
                    val img = article.selectFirst("img")?.attr("src")
                    val link = article.selectFirst("a.lnk-blk")?.attr("href")

                    if (title == null || img == null || link == null) {
                        Log.w("PelispediaProvider", "WARN: Elemento principal con título/img/link nulo, saltando. Título: $title, Link: $link")
                        return@mapNotNull null
                    }

                    val contentType = if (name == "Películas") TvType.Movie else TvType.TvSeries

                    Log.d("PelispediaProvider", "DEBUG: Elemento principal - Título: $title, Tipo: $contentType, Link: $link, Imagen: $img")

                    when (contentType) {
                        TvType.Movie -> {
                            newMovieSearchResponse(name = title, url = link, type = contentType) {
                                this.posterUrl = fixUrl(img)
                            }
                        }
                        TvType.TvSeries -> {
                            newTvSeriesSearchResponse(name = title, url = link, type = contentType) {
                                this.posterUrl = fixUrl(img)
                            }
                        }
                        else -> {
                            Log.w("PelispediaProvider", "WARN: Tipo de contenido no soportado en getMainPage: $contentType para link: $link")
                            null
                        }
                    }
                }
                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                }
            }
        } catch (e: Exception) {
            Log.e("PelispediaProvider", "ERROR en getMainPage: ${e.message}", e)
            return null
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("PelispediaProvider", "DEBUG: Iniciando search para query: $query")
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        Log.d("PelispediaProvider", "DEBUG: Documento de búsqueda obtenido para query: $query")
        return doc.select("section.movies article").mapNotNull { article ->
            val title = article.selectFirst("h2.entry-title")?.text()
            val img = article.selectFirst("img")?.attr("src")
            val link = article.selectFirst("a.lnk-blk")?.attr("href")

            if (title == null || img == null || link == null) {
                Log.w("PelispediaProvider", "WARN: Resultado de búsqueda con título/img/link nulo, saltando. Título: $title, Link: $link")
                return@mapNotNull null
            }

            val contentType = if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

            Log.d("PelispediaProvider", "DEBUG: Resultado de búsqueda - Título: $title, Tipo: $contentType, Link: $link, Imagen: $img")

            when (contentType) {
                TvType.Movie -> {
                    newMovieSearchResponse(name = title, url = link, type = contentType) {
                        this.posterUrl = fixUrl(img)
                    }
                }
                TvType.TvSeries -> {
                    newTvSeriesSearchResponse(name = title, url = link, type = contentType) {
                        this.posterUrl = fixUrl(img)
                    }
                }
                else -> {
                    Log.w("PelispediaProvider", "WARN: Tipo de contenido no soportado en search: $contentType para link: $link")
                    null
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PelispediaProvider", "DEBUG: Iniciando load para URL: $url")
        val doc = app.get(url).document
        Log.d("PelispediaProvider", "DEBUG: Documento obtenido para load() de URL: $url")

        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        Log.d("PelispediaProvider", "DEBUG: Tipo detectado para URL $url: $tvType")

        val poster = doc.selectFirst(".alg-ss img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/")
        val backimage = doc.selectFirst(".bghd  img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/") ?: poster

        val title = doc.selectFirst("h1.entry-title")?.text()
        if (title.isNullOrEmpty()) {
            Log.e("PelispediaProvider", "ERROR: Título no encontrado para URL: $url")
            return null
        }
        Log.d("PelispediaProvider", "DEBUG: Título extraído: $title")

        val plot = doc.selectFirst(".description > p:nth-child(2)")?.text() ?: doc.selectFirst(".description > p")?.text()
        Log.d("PelispediaProvider", "DEBUG: Descripción extraída (primeros 100 chars): ${plot?.take(100)}")

        val tags = doc.select("span.genres a").map { it.text() }
        Log.d("PelispediaProvider", "DEBUG: Tags extraídos: $tags")

        val yearrr = doc.selectFirst("span.year.fa-calendar.far")?.text()?.toIntOrNull()
        Log.d("PelispediaProvider", "DEBUG: Año extraído: $yearrr")

        val duration = doc.selectFirst("span.duration.fa-clock.far")?.text()
        Log.d("PelispediaProvider", "DEBUG: Duración extraída: $duration")

        val seasonsdoc = doc.select("div.choose-season li a").map {
            val seriesid = it.attr("data-post")
            val dataseason = it.attr("data-season")
            Pair(seriesid, dataseason)
        }
        val epi = ArrayList<Episode>()
        if (tvType == TvType.TvSeries) {
            Log.d("PelispediaProvider", "DEBUG: Contenido es TvSeries. Buscando temporadas/episodios.")
            seasonsdoc.forEach {(serieid, data) -> // Reemplazado apmap con forEach
                if (serieid.isNullOrEmpty() || data.isNullOrEmpty()) {
                    Log.w("PelispediaProvider", "WARN: ID de serie o dato de temporada nulo/vacío, saltando.")
                    return@forEach
                }

                val seasonsrequest = app.post("https://pelispedia.is/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "action_select_season",
                        "season" to data,
                        "post" to serieid,
                    )
                ).document
                seasonsrequest.select("li article.episodes").mapNotNull { li ->
                    val href = li.selectFirst("a.lnk-blk")?.attr("href")
                    if (href.isNullOrEmpty()) {
                        Log.w("PelispediaProvider", "WARN: Link de episodio nulo o vacío en temporada $data, saltando.")
                        return@mapNotNull null
                    }
                    val seasonregex = Regex("temporada-(\\d+)-capitulo-(\\d+)")
                    val match = seasonregex.find(href)
                    val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                    Log.d("PelispediaProvider", "DEBUG: Añadiendo episodio: S:$season E:$episode URL: $href")
                    epi.add(
                        newEpisode(href) {
                            this.season = season
                            this.episode = episode
                            this.runTime = null
                        }
                    )
                }
            }
            Log.d("PelispediaProvider", "DEBUG: Total de episodios añadidos: ${epi.size}")
        }


        val recs = doc.select("article.movies").mapNotNull { rec ->
            val recTitle = rec.selectFirst(".entry-title")?.text()
            val recImg = rec.selectFirst("img")?.attr("src")
            val recLink = rec.selectFirst("a")?.attr("href")

            if (recTitle == null || recImg == null || recLink == null) {
                Log.w("PelispediaProvider", "WARN: Recomendación con título/img/link nulo, saltando. Título: $recTitle, Link: $recLink")
                return@mapNotNull null
            }
            val recTvType = if (recLink.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

            when (recTvType) {
                TvType.Movie -> {
                    newMovieSearchResponse(recTitle, recLink, recTvType) {
                        this.posterUrl = fixUrl(recImg)
                    }
                }
                TvType.TvSeries -> {
                    newTvSeriesSearchResponse(recTitle, recLink, recTvType) {
                        this.posterUrl = fixUrl(recImg)
                    }
                }
                else -> {
                    Log.w("PelispediaProvider", "WARN: Tipo de recomendación no soportado: $recTvType para link: $recLink")
                    null
                }
            }
        }
        Log.d("PelispediaProvider", "DEBUG: Total de recomendaciones añadidas: ${recs.size}")


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, tvType, epi) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                        ?: ""
                    this.backgroundPosterUrl = backimage?.let { fixUrl(it) }
                        ?: ""
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                        ?: ""
                    this.backgroundPosterUrl = backimage?.let { fixUrl(it) }
                        ?: ""
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
                }
            }
            else -> {
                Log.e("PelispediaProvider", "ERROR: Tipo de contenido no soportado o desconocido para URL: $url")
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PelispediaProvider", "DEBUG: Iniciando loadLinks para data: $data")
        try {
            val doc = app.get(data).document
            Log.d("PelispediaProvider", "DEBUG: Documento obtenido para loadLinks de data: $data")
            doc.select(".player iframe").forEach { iframe ->
                val trembedlink = iframe.attr("data-src")
                if (trembedlink.isNullOrEmpty()) {
                    Log.w("PelispediaProvider", "WARN: data-src nulo o vacío para iframe en $data, saltando.")
                    return@forEach
                }

                try {
                    val tremrequest = app.get(trembedlink).document
                    val link = tremrequest.selectFirst("div.Video iframe")?.attr("src")
                    if (link != null && link.isNotEmpty()) {
                        Log.d("PelispediaProvider", "DEBUG: Enlace final para extractor: $link")
                        loadExtractor(link, data, subtitleCallback, callback)
                    } else {
                        Log.w("PelispediaProvider", "WARN: Enlace de extractor nulo o vacío de $trembedlink.")
                    }
                } catch (e: Exception) {
                    Log.e("PelispediaProvider", "ERROR al procesar trembedlink $trembedlink: ${e.message}", e)
                }
            }
            Log.d("PelispediaProvider", "DEBUG: loadLinks finalizado.")
            return true
        } catch (e: Exception) {
            Log.e("PelispediaProvider", "ERROR GENERAL en loadLinks para data '$data': ${e.message}", e)
            return false
        }
    }
}