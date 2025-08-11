package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
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
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import android.net.Uri


class AnimeioProvider : MainAPI() {
    // URL principal actualizada a Animeio
    override var mainUrl = "https://animeio.com"
    override var name = "Animeio"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Función para construir la URL del póster, ya que a veces es relativa
    private fun fixPosterUrl(url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }

    // La función extractAnimeItem es casi idéntica, pero adaptada a las clases consistentes
    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img")?.attr("data-src")?.ifEmpty { element.selectFirst("img")?.attr("src") }
        val yearText = element.selectFirst("div._2y8kd")?.text()?.trim()
        val year = yearText?.split(" - ")?.firstOrNull()?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = fixPosterUrl(posterUrl)
                this.year = year
            }
        }
        return null
    }

    // Adaptada la función para buscar animes, usando el nuevo formato
    private fun extractSearchItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img")?.attr("src")?.ifEmpty { element.selectFirst("img")?.attr("data-src") }
        val yearText = element.selectFirst("div._2y8kd")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = fixPosterUrl(posterUrl)
                this.year = year
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
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
        for (i in 0 until retries) {
            try {
                val res = app.get(url, timeout = timeoutMs, headers = mapOf("User-Agent" to userAgent))
                if (res.isSuccessful) return res.text
            } catch (e: Exception) {
                Log.e("AnimeioProvider", "safeAppGet error for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = mainUrl
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        // Secciones principales de la homepage, adaptadas a los nuevos selectores.
        doc.select("div#content-full").forEach { container ->
            val heading = container.selectFirst("h3.carousel.t")?.text()?.trim()
            val animes = container.select("div#article-div > div").mapNotNull {
                when (heading) {
                    "Capítulos recientes" -> {
                        val titleElement = it.selectFirst("div._2NNxg a")
                        val link = titleElement?.attr("href")
                        val title = titleElement?.text()?.trim()
                        val posterUrl = it.selectFirst("img")?.attr("data-src")

                        if (title != null && link != null) {
                            newAnimeSearchResponse(
                                title,
                                fixUrl(link)
                            ) {
                                this.type = TvType.Anime
                                this.posterUrl = fixPosterUrl(posterUrl)
                            }
                        } else null
                    }
                    "Animes recientes", "Animes más populares" -> extractAnimeItem(it)
                    else -> null
                }
            }
            if (animes.isNotEmpty()) {
                val listName = when (heading) {
                    "Capítulos recientes" -> "Capítulos recientes"
                    "Animes recientes" -> "Animes recientes"
                    "Animes más populares" -> "Animes más populares"
                    "Películas recientes" -> "Películas recientes"
                    "Películas más populares" -> "Películas más populares"
                    else -> null
                }
                if (listName != null) {
                    items.add(HomePageList(listName, animes))
                }
            }
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        // Selectores de búsqueda actualizados
        return doc.select("div._135yj._2FQAt.full._2mJki").mapNotNull {
            extractSearchItem(it)
        }
    }

    // Se elimina la lógica de las data classes porque los episodios ahora se extraen del HTML
    data class EpisodeData (
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("AnimeioProvider", "Iniciando load para URL: $url")
        val response = app.get(url)
        val html = response.text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.comics-title.ajp")?.text()?.trim() ?: doc.selectFirst("h3.comics-alt")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div#animeinfo img")?.attr("data-src").let {
            if (it.isNullOrBlank()) {
                doc.selectFirst("div#animeinfo img")?.attr("src")
            } else {
                it
            }
        } ?: ""
        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text() }
        val yearText = doc.selectFirst("div.details-by")?.text()?.substringAfter("•")?.trim()
        val year = yearText?.take(4)?.toIntOrNull()
        val status = parseStatus(doc.selectFirst("span#estado")?.text()?.trim() ?: "")

        val isMovie = doc.selectFirst("span#ranking.estado")?.text()?.lowercase() == "película"

        val recommendations = doc.select("div#slidebar-anime div._type3.np").mapNotNull { element ->
            val recLink = element.selectFirst("a._1A2Dc._38LRT")?.attr("href")
            val recTitle = element.selectFirst("div._2NNxg a._2uHIS")?.text()?.trim()
            val recPoster = element.selectFirst("img")?.attr("data-src").let {
                if (it.isNullOrBlank()) {
                    element.selectFirst("img")?.attr("src")
                } else {
                    it
                }
            }
            val recYearText = element.selectFirst("div._2y8kd")?.text()?.trim()
            val recYear = recYearText?.split(" - ")?.firstOrNull()?.toIntOrNull()

            if (recLink != null && recTitle != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.posterUrl = fixPosterUrl(recPoster)
                    this.year = recYear
                }
            } else null
        }

        if (isMovie) {
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = fixPosterUrl(poster)
                this.backgroundPosterUrl = fixPosterUrl(poster)
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                //this.status = status
            }
        } else {
            val allEpisodes = ArrayList<Episode>()
            val scriptContent = doc.select("script").find { it.html().contains("const allEpisodes =") }?.html()
            if (scriptContent != null) {
                val episodesJsonString = scriptContent
                    .substringAfter("const allEpisodes = ")
                    .substringBefore(";")
                    .trim()

                try {
                    val episodesData = tryParseJson<List<EpisodeData>>(episodesJsonString)
                    if (episodesData != null) {
                        allEpisodes.addAll(episodesData.mapNotNull { episode ->
                            val epUrl = "$url/episodio-${episode.episode}"
                            val epNum = episode.episode?.toIntOrNull()
                            if (epUrl.isNotBlank() && epNum != null) {
                                newEpisode(epUrl) {
                                    this.name = episode.title ?: "Episodio $epNum"
                                    this.episode = epNum
                                }
                            } else null
                        })
                    }
                } catch (e: Exception) {
                    Log.e("AnimeioProvider", "Fallo al parsear el JSON de episodios: ${e.message}", e)
                }
            }
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.Anime,
                episodes = allEpisodes.reversed()
            ) {
                this.posterUrl = fixPosterUrl(poster)
                this.backgroundPosterUrl = fixPosterUrl(poster)
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                //this.status = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // La URL del episodio es el 'data'
        val episodeUrl = data
        val response = app.get(episodeUrl)
        val doc = response.document

        // Selectores de reproductores actualizados
        val players = doc.select("ul.ul-drop.dropcaps li a.play-video.cap")
        var linksFound = false

        if (players.isNotEmpty()) {
            players.apmap { player ->
                val playerPayload = player.attr("data-player")
                val playerName = player.attr("data-player-name")

                if (playerPayload.isNotBlank()) {
                    try {
                        Log.d("AnimeioProvider", "Procesando reproductor: $playerName con payload: $playerPayload")
                        // Algunos jugadores usan una URL de iframe directa, otros solo el hash.
                        // La URL base del reproductor es la misma del sitio.
                        val finalUrl = when(playerName.lowercase()) {
                            "san" -> "$mainUrl/sanplayer/um?e=$playerPayload"
                            else -> playerPayload
                        }

                        // Llamamos a loadExtractor y dependemos de él para manejar la extracción
                        if (loadExtractor(finalUrl, episodeUrl, subtitleCallback, callback)) {
                            linksFound = true
                            Log.d("AnimeioProvider", "Enlaces encontrados por loadExtractor para $playerName")
                        }

                    } catch (e: Exception) {
                        Log.e("AnimeioProvider", "Error al procesar $playerName: ${e.message}")
                    }
                }
            }
        }

        Log.d("AnimeioProvider", "Finalizando loadLinks. ¿Se encontraron enlaces? $linksFound")
        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado", "concluido" -> ShowStatus.Completed
            "en emisión", "en emision" -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }
}