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
import kotlinx.coroutines.*

class AnimeioProvider : MainAPI() {
    override var mainUrl = "https://animeio.com"
    override var name = "AnimeIO"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img")?.attr("data-src").let {
            if (it.isNullOrBlank()) {
                element.selectFirst("img")?.attr("src")
            } else {
                it
            }
        }
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

    private fun extractSearchItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img")?.attr("data-src").let {
            if (it.isNullOrBlank()) {
                element.selectFirst("img")?.attr("src")
            } else {
                it
            }
        }
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

        doc.select("div#content-full").forEach { section ->
            val heading = section.selectFirst("h3.carousel")?.text()?.trim() ?: return@forEach

            val animes = when {
                heading.contains("Capítulos recientes") -> {
                    section.select("div#article-div > div._135yj").mapNotNull { element ->
                        val titleElement = element.selectFirst("div._2NNxg a")
                        val title = titleElement?.text()?.trim()
                        val animeLink = titleElement?.attr("href")

                        val posterUrl = element.selectFirst("img")?.let { img ->
                            img.attr("src").ifEmpty { img.attr("data-src") }
                        }

                        val episodeLink = element.selectFirst("a._38LRT")?.attr("href")
                        val episodeText = element.selectFirst("span._2y8kd.etag")?.text()

                        if (title != null && animeLink != null && posterUrl != null) {
                            val episode = episodeText?.replace("Capítulo ", "")?.toIntOrNull()

                            newAnimeSearchResponse(
                                title,
                                fixUrl(animeLink)
                            ) {
                                this.type = TvType.Anime
                                this.posterUrl = fixPosterUrl(posterUrl)
                                if (episode != null) {
                                    addDubStatus(DubStatus.Subbed, episode)
                                }
                            }
                        } else null
                    }
                }

                heading.contains("Animes recientes") ||
                        heading.contains("Animes más populares") -> {
                    section.select("div#article-div > div._135yj").mapNotNull { element ->
                        val titleElement = element.selectFirst("div._2NNxg a")
                        val title = titleElement?.text()?.trim()
                        val link = titleElement?.attr("href")

                        val posterUrl = element.selectFirst("img")?.let { img ->
                            img.attr("src").ifEmpty { img.attr("data-src") }
                        }

                        val status = element.selectFirst("div._2y8kd:not(.etag)")?.text()

                        if (title != null && link != null && posterUrl != null) {
                            newAnimeSearchResponse(
                                title,
                                fixUrl(link)
                            ) {
                                this.type = TvType.Anime
                                this.posterUrl = fixPosterUrl(posterUrl)
                            }
                        } else null
                    }
                }

                heading.contains("Películas recientes") ||
                        heading.contains("Películas más populares") -> {
                    section.select("div#article-div > div._135yj").mapNotNull { element ->
                        val titleElement = element.selectFirst("div._2NNxg a")
                        val title = titleElement?.text()?.trim()
                        val link = titleElement?.attr("href")

                        val posterUrl = element.selectFirst("img")?.let { img ->
                            img.attr("src").ifEmpty { img.attr("data-src") }
                        }

                        if (title != null && link != null && posterUrl != null) {
                            newAnimeSearchResponse(
                                title,
                                fixUrl(link)
                            ) {
                                this.type = TvType.AnimeMovie
                                this.posterUrl = fixPosterUrl(posterUrl)
                            }
                        } else null
                    }
                }

                else -> emptyList()
            }

            if (animes.isNotEmpty()) {
                items.add(HomePageList(heading, animes))
            }
        }

        return if (items.isNotEmpty()) newHomePageResponse(items) else null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div._135yj._2FQAt.full._2mJki").mapNotNull {
            extractSearchItem(it)
        }
    }

    data class EpisodeData (
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("image2") val image2: String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("AnimeioProvider", "URL: $url")

        try {
            val response = app.get(url)
            Log.d("AnimeioProvider", "Respuesta obtenida, código: ${response.code}")

            val html = response.text
            val doc = Jsoup.parse(html)
            Log.d("AnimeioProvider", "HTML parseado correctamente")

            val baseUrl = "https://animeio.com"

            val title = doc.selectFirst("h1.comics-title.ajp")?.text()?.trim()
                ?: doc.selectFirst("h3.comics-alt")?.text()?.trim()
                ?: "Sin título"
            Log.d("AnimeioProvider", "Título: $title")

            val poster = doc.selectFirst("div#animeinfo img")?.attr("data-src").let {
                if (it.isNullOrBlank()) {
                    doc.selectFirst("div#animeinfo img")?.attr("src")
                } else {
                    it
                }
            } ?: ""
            Log.d("AnimeioProvider", "Poster principal: $poster")

            val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
            val tags = doc.select("div.anime-genres a").map { it.text() }
            val yearText = doc.selectFirst("div.details-by")?.text()?.substringAfter("•")?.trim()
            val year = yearText?.take(4)?.toIntOrNull()
            val status = parseStatus(doc.selectFirst("span#estado")?.text()?.trim() ?: "")

            val rankingElement = doc.selectFirst("span#ranking.estado")
            val rankingText = rankingElement?.text()?.lowercase() ?: ""
            Log.d("AnimeioProvider", "Ranking text: '$rankingText'")

            val isMovie = rankingText == "película"
            Log.d("AnimeioProvider", "Es película: $isMovie")

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
            Log.d("AnimeioProvider", "Recomendaciones encontradas: ${recommendations.size}")

            if (isMovie) {
                Log.d("AnimeioProvider", "Procesando como película")
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
                }
            } else {
                Log.d("AnimeioProvider", "Procesando como serie")


                val allEpisodes = ArrayList<Episode>()

                Log.d("AnimeioProvider", "Buscando scripts con 'const allEpisodes'")
                val scripts = doc.select("script")
                Log.d("AnimeioProvider", "Total de scripts encontrados: ${scripts.size}")

                val scriptContent = scripts.find { it.html().contains("const allEpisodes =") }?.html()

                if (scriptContent != null) {
                    Log.d("AnimeioProvider", "Script de episodios encontrado, longitud: ${scriptContent.length}")

                    val startIndex = scriptContent.indexOf("const allEpisodes = ")
                    val endIndex = scriptContent.indexOf("];", startIndex)

                    if (startIndex != -1 && endIndex != -1) {
                        val episodesJsonString = scriptContent
                            .substring(startIndex + "const allEpisodes = ".length, endIndex + 1)
                            .trim()

                        Log.d("AnimeioProvider", "JSON extraído, longitud: ${episodesJsonString.length}")
                        Log.d("AnimeioProvider", "JSON (primeros 300 chars): ${episodesJsonString.take(300)}")

                        try {
                            val episodesData = tryParseJson<List<EpisodeData>>(episodesJsonString)
                            if (episodesData != null) {
                                Log.d("AnimeioProvider", "✓ Episodios parseados correctamente: ${episodesData.size}")

                                var episodiosConImagen = 0
                                var episodiosSinImagen = 0

                                episodesData.forEachIndexed { index, episode ->
                                    val epNum = episode.episode?.toIntOrNull()

                                    if (index < 5) {
                                        Log.d("AnimeioProvider", "Episodio ${episode.episode}")
                                        Log.d("AnimeioProvider", "  Título: ${episode.title}")
                                        Log.d("AnimeioProvider", "  Imagen RAW: '${episode.image}'")
                                        Log.d("AnimeioProvider", "  Imagen2 RAW: '${episode.image2}'")
                                    }

                                    val posterUrl = when {
                                        !episode.image.isNullOrBlank() -> {
                                            episodiosConImagen++
                                            when {
                                                episode.image.startsWith("http://") || episode.image.startsWith("https://") -> {
                                                    if (index < 5) Log.d("AnimeioProvider", "URL completa: ${episode.image}")
                                                    episode.image
                                                }
                                                episode.image.startsWith("//") -> {
                                                    val fullUrl = "https:${episode.image}"
                                                    if (index < 5) Log.d("AnimeioProvider", "URL sin protocolo: $fullUrl")
                                                    fullUrl
                                                }
                                                else -> {
                                                    val fullUrl = "$baseUrl/${episode.image}"
                                                    if (index < 5) Log.d("AnimeioProvider", "URL relativa: $fullUrl")
                                                    fullUrl
                                                }
                                            }
                                        }
                                        !episode.image2.isNullOrBlank() -> {
                                            episodiosConImagen++
                                            when {
                                                episode.image2.startsWith("http://") || episode.image2.startsWith("https://") -> {
                                                    if (index < 5) Log.d("AnimeioProvider", "URL completa (image2): ${episode.image2}")
                                                    episode.image2
                                                }
                                                episode.image2.startsWith("//") -> {
                                                    val fullUrl = "https:${episode.image2}"
                                                    if (index < 5) Log.d("AnimeioProvider", "URL sin protocolo (image2): $fullUrl")
                                                    fullUrl
                                                }
                                                else -> {
                                                    val fullUrl = "$baseUrl/${episode.image2}"
                                                    if (index < 5) Log.d("AnimeioProvider", "URL relativa (image2): $fullUrl")
                                                    fullUrl
                                                }
                                            }
                                        }
                                        else -> {
                                            episodiosSinImagen++
                                            if (index < 5) Log.d("AnimeioProvider", "Sin imagen")
                                            null
                                        }
                                    }

                                    if (epNum != null) {
                                        val epUrl = "$url/episodio-${episode.episode}"

                                        if (index < 5) {
                                            Log.d("AnimeioProvider", "  Episode URL: $epUrl")
                                            Log.d("AnimeioProvider", "  Poster URL final: $posterUrl")
                                        }

                                        val newEp = newEpisode(epUrl) {
                                            this.name = episode.title ?: "Episodio $epNum"
                                            this.episode = epNum
                                            this.season = episode.season?.toIntOrNull() ?: 1
                                            if (!posterUrl.isNullOrBlank()) {
                                                this.posterUrl = posterUrl
                                                if (index < 5) {
                                                    Log.d("AnimeioProvider", "posterUrl asignado correctamente")
                                                }
                                            } else {
                                                if (index < 5) {
                                                    Log.w("AnimeioProvider", "posterUrl es null o vacío")
                                                }
                                            }
                                        }
                                        allEpisodes.add(newEp)
                                    }
                                }

                                Log.i("AnimeioProvider", "Total episodios: ${allEpisodes.size}")
                                Log.i("AnimeioProvider", "CON imagen: $episodiosConImagen")
                                Log.i("AnimeioProvider", "SIN imagen: $episodiosSinImagen")

                                if (allEpisodes.isNotEmpty()) {
                                    val sampleSize = minOf(3, allEpisodes.size)
                                    val sampleEpisodes = allEpisodes.shuffled().take(sampleSize)
                                    Log.d("AnimeioProvider", "Muestra aleatoria de episodios:")
                                    sampleEpisodes.forEach { ep ->
                                        Log.d("AnimeioProvider", "  Ep ${ep.episode}: posterUrl=${ep.posterUrl}")
                                    }
                                }

                            } else {
                                Log.e("AnimeioProvider", "episodesData es null después del parseo")
                            }
                        } catch (e: Exception) {
                            Log.e("AnimeioProvider", "Error al parsear JSON: ${e.message}")
                            Log.e("AnimeioProvider", "Stack trace:", e)
                        }
                    } else {
                        Log.e("AnimeioProvider", "No se pudo extraer el JSON correctamente")
                        Log.d("AnimeioProvider", "startIndex: $startIndex, endIndex: $endIndex")
                    }
                } else {
                    Log.e("AnimeioProvider", "No se encontró script con 'const allEpisodes ='")
                    scripts.forEach { script ->
                        if (script.html().contains("allEpisodes")) {
                            Log.d("AnimeioProvider", "Script que contiene 'allEpisodes' (primeros 200 chars): ${script.html().take(200)}")
                        }
                    }
                }

                Log.d("AnimeioProvider", "Creando TvSeriesLoadResponse con ${allEpisodes.size} episodios")

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
                    this.showStatus = status
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeioProvider", "✗✗✗ ERROR GENERAL EN LOAD: ${e.message}")
            Log.e("AnimeioProvider", "Stack trace completo:", e)
            return null
        }
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fixed = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://animeio.com$url"
            else -> "https://animeio.com/$url"
        }
        Log.d("AnimeioProvider", "fixPosterUrl: '$url' -> '$fixed'")
        return fixed
    }

    private fun parseStatus(status: String): ShowStatus {
        return when (status.lowercase()) {
            "concluido", "finalizado" -> ShowStatus.Completed
            "en emisión", "en emision" -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val episodeUrl = data
        val response = app.get(episodeUrl)
        val doc = response.document

        val players = doc.select("ul.ul-drop.dropcaps li a.play-video.cap")
        var linksFound = false

        if (players.isNotEmpty()) {

            val jobs = players.map { player ->
                async {
                    val playerPayload = player.attr("data-player")
                    val playerName = player.attr("data-player-name")

                    if (playerPayload.isNotBlank()) {
                        try {
                            Log.d("AnimeioProvider", "Procesando reproductor: $playerName con payload: $playerPayload")
                            val finalUrl = when(playerName.lowercase()) {
                                "san" -> "$mainUrl/sanplayer/um?e=$playerPayload"
                                else -> playerPayload
                            }

                            loadExtractor(finalUrl, episodeUrl, subtitleCallback, callback)

                        } catch (e: Exception) {
                            Log.e("AnimeioProvider", "Error al procesar $playerName: ${e.message}")
                            false
                        }
                    } else false
                }
            }

            linksFound = jobs.awaitAll().any { it }
        }

        Log.d("AnimeioProvider", "Finalizando loadLinks. ¿Se encontraron enlaces? $linksFound")
        return@coroutineScope linksFound
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }
}