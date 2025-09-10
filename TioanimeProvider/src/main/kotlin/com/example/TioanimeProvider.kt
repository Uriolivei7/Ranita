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

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        Log.d("TioanimeProvider", "DEBUG: Iniciando getMainPage, página: $page")
        val urls = listOf(
            Pair("$mainUrl/directorio?year=1950%2C2022&status=2&sort=recent", "Animes"),
            Pair("$mainUrl/directorio?year=1950%2C2022&status=1&sort=recent", "En Emisión"),
            Pair("$mainUrl/directorio?type[]=1&year=1950%2C2022&status=2&sort=recent", "Películas"),
        )
        val items = ArrayList<HomePageList>()

        val latestEpisodes = app.get(mainUrl).document.select("ul.episodes li article").mapNotNull { article ->
            val titleRaw = article.selectFirst("h3.title")?.text()
            val title = titleRaw?.replace(Regex("((\\d+)\$)"),"")
            val poster = article.selectFirst("figure img")?.attr("src")
            val epRegex = Regex("(-(\\d+)\$)")
            val urlRaw = article.selectFirst("a")?.attr("href")

            if (title.isNullOrEmpty() || poster.isNullOrEmpty() || urlRaw.isNullOrEmpty()) {
                Log.w("TioanimeProvider", "WARN: Elemento de últimos episodios con datos nulos/vacíos, saltando. Título: $titleRaw, URL: $urlRaw")
                return@mapNotNull null
            }

            val url = urlRaw.replace(epRegex,"")?.replace("ver/","anime/")
            val epNum = epRegex.findAll(urlRaw).map {
                it.value.replace("-","")
            }.firstOrNull()?.toIntOrNull()

            val dubstat = if (titleRaw.contains("Latino") || titleRaw.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed

            newAnimeSearchResponse(title, fixUrl(url!!)) {
                this.posterUrl = fixUrl(poster)
                addDubStatus(dubstat, epNum)
            }
        }
        items.add(HomePageList("Últimos episodios", latestEpisodes))

        urls.forEach { (url, name) ->
            Log.d("TioanimeProvider", "DEBUG: Obteniendo datos para la lista: $name de $url")
            val doc = app.get(url).document
            val home = doc.select("ul.animes li article").mapNotNull { article ->
                val title = article.selectFirst("h3.title")?.text()
                val poster = article.selectFirst("figure img")?.attr("src")
                val link = article.selectFirst("a")?.attr("href")

                if (title.isNullOrEmpty() || poster.isNullOrEmpty() || link.isNullOrEmpty()) {
                    Log.w("TioanimeProvider", "WARN: Elemento de directorio con datos nulos/vacíos, saltando. Título: $title, Link: $link")
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
            }
        }
        if (items.isEmpty()) throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
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
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach { chunk ->
                    chunk.split(",").mapNotNull { epNumStr ->
                        val epNum = epNumStr.toIntOrNull()
                        if (epNum == null) {
                            Log.w("TioanimeProvider", "WARN: Número de episodio inválido: '$epNumStr' en URL: $url")
                            return@mapNotNull null
                        }

                        val link = url.replace("/anime/","/ver/")+"-$epNum"
                        newEpisode(link) {
                            this.name = "Capítulo $epNum"
                            this.posterUrl = null
                            this.episode = epNum
                            this.runTime = null
                        }
                    }
                        .let { episodes.addAll(it) }
                }
            }
        }
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