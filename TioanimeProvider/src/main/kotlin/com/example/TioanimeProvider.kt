package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.*
import kotlin.collections.ArrayList
import android.util.Log

class TioanimeProvider:MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }
    }
    override var mainUrl = "https://tioanime.com"
    override var name = "TioAnime"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TioanimeProvider", "Iniciando getMainPage")

        val items = ArrayList<HomePageList>()

        try {
            val mainDoc = app.get(mainUrl).document
            Log.d("TioanimeProvider", "Página principal cargada")

            val episodesSection = mainDoc.select("section:has(h1.title:contains(Últimos Episodios)) ul.episodes li article")
            Log.d("TioanimeProvider", "Episodios encontrados: ${episodesSection.size}")

            val latestEpisodes = episodesSection.mapNotNull { article ->
                try {
                    val titleRaw = article.selectFirst("h3.title")?.text()
                    val title = titleRaw?.replace(Regex("\\s+\\d+$"), "")?.trim()
                    val poster = article.selectFirst("figure img")?.attr("src")
                    val urlRaw = article.selectFirst("a")?.attr("href")

                    if (title.isNullOrEmpty() || poster.isNullOrEmpty() || urlRaw.isNullOrEmpty()) {
                        return@mapNotNull null
                    }

                    val epNum = Regex("\\d+$").find(titleRaw ?: "")?.value?.toIntOrNull()
                    val animeUrl = urlRaw.replace(Regex("-\\d+$"), "").replace("/ver/", "/anime/")

                    val dubstat = if (title.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed
                    else DubStatus.Subbed

                    newAnimeSearchResponse(title, fixUrl(animeUrl)) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(dubstat, epNum)
                    }
                } catch (e: Exception) {
                    Log.e("TioanimeProvider", "Error procesando episodio: ${e.message}")
                    null
                }
            }

            if (latestEpisodes.isNotEmpty()) {
                items.add(HomePageList("Últimos Episodios", latestEpisodes))
            }

            val animesSection = mainDoc.select("section:has(h2.title:contains(Últimos Animes)) ul li article.anime")
            Log.d("TioanimeProvider", "Animes encontrados: ${animesSection.size}")

            val latestAnimes = animesSection.mapNotNull { article ->
                try {
                    val title = article.selectFirst("h3.title")?.text()
                    val poster = article.selectFirst("figure img")?.attr("src")
                    val link = article.selectFirst("a")?.attr("href")

                    if (title.isNullOrEmpty() || poster.isNullOrEmpty() || link.isNullOrEmpty()) {
                        return@mapNotNull null
                    }

                    val dubStatusSet = if (title.contains("Latino") || title.contains("Castellano"))
                        EnumSet.of(DubStatus.Dubbed)
                    else EnumSet.of(DubStatus.Subbed)

                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.type = TvType.Anime
                        this.posterUrl = fixUrl(poster)
                        this.dubStatus = dubStatusSet
                    }
                } catch (e: Exception) {
                    Log.e("TioanimeProvider", "Error procesando anime: ${e.message}")
                    null
                }
            }

            if (latestAnimes.isNotEmpty()) {
                items.add(HomePageList("Últimos Animes", latestAnimes))
            }

            val directoryUrls = listOf(
                Pair("$mainUrl/directorio?year=1950%2C2025&status=1&sort=recent", "En Emisión"),
                Pair("$mainUrl/directorio?year=1950%2C2025&status=2&sort=recent", "Finalizados")
            )

            directoryUrls.forEach { (url, name) ->
                try {
                    val doc = app.get(url).document
                    val home = doc.select("ul.animes li article").mapNotNull { article ->
                        val title = article.selectFirst("h3.title")?.text()
                        val poster = article.selectFirst("figure img")?.attr("src")
                        val link = article.selectFirst("a")?.attr("href")

                        if (title.isNullOrEmpty() || poster.isNullOrEmpty() || link.isNullOrEmpty()) {
                            return@mapNotNull null
                        }

                        val dubStatusSet = if (title.contains("Latino") || title.contains("Castellano"))
                            EnumSet.of(DubStatus.Dubbed)
                        else EnumSet.of(DubStatus.Subbed)

                        newAnimeSearchResponse(title, fixUrl(link)) {
                            this.type = TvType.Anime
                            this.posterUrl = fixUrl(poster)
                            this.dubStatus = dubStatusSet
                        }
                    }

                    if (home.isNotEmpty()) {
                        items.add(HomePageList(name, home))
                        Log.d("TioanimeProvider", "$name: ${home.size} elementos")
                    }
                } catch (e: Exception) {
                    Log.e("TioanimeProvider", "Error obteniendo $name: ${e.message}")
                }
            }

            Log.d("TioanimeProvider", "Total listas creadas: ${items.size}")

        } catch (e: Exception) {
            Log.e("TioanimeProvider", "Error fatal en getMainPage: ${e.message}")
            throw ErrorLoadingException("Error cargando página principal: ${e.message}")
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }

        return newHomePageResponse(items)
    }

    data class SearchObject (
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String?,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("TioanimeProvider", "DEBUG: Iniciando search para query: $query")
        val response = app.post("https://tioanime.com/api/search",
            data = mapOf(Pair("value",query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        return json.mapNotNull { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/portadas/${searchr.id}.jpg"

            if (title.isNullOrEmpty() || href.isNullOrEmpty() || image.isNullOrEmpty()) {
                Log.w("TioanimeProvider", "WARN: Resultado de búsqueda con datos nulos/vacíos, saltando. Título: ${searchr.title}, Slug: ${searchr.slug}")
                return@mapNotNull null
            }

            val dubStatusSet = if (title.contains("Latino") || title.contains("Castellano"))
                EnumSet.of(DubStatus.Dubbed)
            else EnumSet.of(DubStatus.Subbed)

            val inferredType = when (searchr.type.lowercase()) {
                "ova" -> TvType.OVA
                "pelicula" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, fixUrl(href)) {
                this.type = inferredType
                this.posterUrl = fixUrl(image)
                this.dubStatus = dubStatusSet
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("TioanimeProvider", "DEBUG: Iniciando load para URL: $url")
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()
        val title = doc.selectFirst("h1.Title")?.text()
            ?: throw ErrorLoadingException("Título no encontrado para URL: $url")
        val poster = doc.selectFirst("div.thumb img")?.attr("src")
        Log.d("TioanimeProvider", "DEBUG: Poster principal encontrado: '$poster'")

        val description = doc.selectFirst("p.sinopsis")?.text()
        val typeStr = doc.selectFirst("span.anime-type-peli")?.text()
        val type = if (typeStr != null) getType(typeStr) else TvType.Anime
        val status = when (doc.selectFirst("div.thumb a.btn.status i")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("p.genres a")
            .map { it?.text()?.trim().toString() }
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()

        doc.select("script").forEach { script ->
            if (script.data().contains("var episodes = [")) {
                Log.d("TioanimeProvider", "DEBUG: Script de episodios encontrado")
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                Log.d("TioanimeProvider", "DEBUG: Datos de episodios extraídos: '$data'")

                data.split("],").forEach { chunk ->
                    Log.d("TioanimeProvider", "DEBUG: Procesando chunk: '$chunk'")
                    chunk.split(",").mapNotNull { epNumStr ->
                        val cleanEpNum = epNumStr.trim().replace("[", "")
                        val epNum = cleanEpNum.toIntOrNull()

                        if (epNum == null) {
                            Log.w("TioanimeProvider", "WARN: Número de episodio inválido: '$epNumStr' (limpio: '$cleanEpNum') en URL: $url")
                            return@mapNotNull null
                        }

                        Log.d("TioanimeProvider", "DEBUG: Episodio $epNum procesado")
                        val link = url.replace("/anime/","/ver/")+"-$epNum"
                        Log.d("TioanimeProvider", "DEBUG: Link del episodio: '$link'")

                        newEpisode(link) {
                            this.name = "Capítulo $epNum"
                            this.posterUrl = fixUrl(poster ?: "")
                            this.episode = epNum
                            this.runTime = null
                        }
                    }
                        .let {
                            episodes.addAll(it)
                            Log.d("TioanimeProvider", "DEBUG: Episodios agregados. Total actual: ${episodes.size}")
                        }
                }
            }
        }

        Log.d("TioanimeProvider", "DEBUG: Total final de episodios: ${episodes.size}")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = fixUrl(poster ?: "")
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TioanimeProvider", "DEBUG: Iniciando loadLinks para data: $data")
        var foundLinks = false
        val doc = app.get(data).document
        doc.select("script").forEach { script ->
            if (script.data().contains("var videos =") || script.data().contains("var anime_id =") || script.data().contains("server")) {
                val videosRaw = script.data().replace("\\/", "/")
                val videoUrls = fetchUrls(videosRaw).mapNotNull { url ->
                    url.replace("https://embedsb.com/e/","https://watchsb.com/e/")
                        .replace("https://ok.ru","http://ok.ru")
                }.toList()

                videoUrls.forEach { videoLink ->
                    try {
                        loadExtractor(videoLink, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        Log.e("TioanimeProvider", "ERROR: Fallo al cargar extractor para $videoLink: ${e.message}", e)
                    }
                }
            }
        }
        Log.d("TioanimeProvider", "DEBUG: loadLinks finalizado para data: $data con foundLinks: $foundLinks")
        return foundLinks
    }
}