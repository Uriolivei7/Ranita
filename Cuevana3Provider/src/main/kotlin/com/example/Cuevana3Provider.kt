package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// Importar ExtractorLinkType - ¡IMPORTANTE!
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class Cuevana3Provider : MainAPI() {
    override var mainUrl = "https://ww8.cuevana3.to/"
    override var name = "Cuevana3"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val doc = app.get(mainUrl).document

        // --- Sección de Películas Online ---
        val moviesSection = doc.selectFirst("section.home-movies")
        Log.d("Cuevana3Provider", "DEBUG_MAINPAGE_MOVIES - Movies section found: ${moviesSection != null}")
        val moviesItems = moviesSection?.select("ul.MovieList li.TPostMv")?.mapNotNull { item ->
            val linkElement = item.selectFirst("a")
            val link = linkElement?.attr("href")?.trim().orEmpty()
            val title = linkElement?.selectFirst("h2.Title")?.text()?.trim().orEmpty()
            val img = item.selectFirst("div.Image img")?.attr("data-src")
                ?: item.selectFirst("div.Image img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                newTvSeriesSearchResponse( // Usar newTvSeriesSearchResponse es correcto aquí
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Movie
                    this.posterUrl = fixUrl(img)
                }
            } else {
                null
            }
        }
        Log.d("Cuevana3Provider", "DEBUG_MAINPAGE_MOVIES - Movies items parsed: ${moviesItems?.size ?: 0}")
        if (!moviesItems.isNullOrEmpty()) {
            items.add(HomePageList("Películas Online", moviesItems))
        }

        // --- Sección de Series Online ---
        val seriesContainer = doc.selectFirst("div#tabserie-1")
        Log.d("Cuevana3Provider", "DEBUG_MAINPAGE_SERIES - Series container (div#tabserie-1) found: ${seriesContainer != null}")

        val seriesItems = seriesContainer?.select("ul.MovieList li.TPostMv")?.mapNotNull { item ->
            val linkElement = item.selectFirst("a")
            val link = linkElement?.attr("href")?.trim().orEmpty()
            val title = linkElement?.selectFirst("h2.Title")?.text()?.trim().orEmpty()
            val img = item.selectFirst("div.Image img")?.attr("data-src")
                ?: item.selectFirst("div.Image img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries // ¡CRÍTICO! Asegurarse de que el tipo sea TvSeries
                    this.posterUrl = fixUrl(img)
                }
            } else {
                null
            }
        }
        Log.d("Cuevana3Provider", "DEBUG_MAINPAGE_SERIES - Series items parsed: ${seriesItems?.size ?: 0}")
        if (!seriesItems.isNullOrEmpty()) {
            items.add(HomePageList("Series Online", seriesItems))
        } else {
            Log.d("Cuevana3Provider", "DEBUG_MAINPAGE_SERIES - 'Series Online' list is empty or null, not added to HomePage.")
        }

        Log.d("Cuevana3Provider", "Final number of HomePageLists: ${items.size}")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        Log.d("Cuevana3Provider", "SEARCH_URL - URL de búsqueda: $searchUrl")

        val doc = app.get(searchUrl).document
        Log.d("Cuevana3Provider", "SEARCH_DOC_HTML - (Primeros 1000 chars) ${doc.html().take(1000)}")

        return doc.select("ul.MovieList li.TPostMv").mapNotNull { item ->
            val linkElement = item.selectFirst("a")
            val link = linkElement?.attr("href")?.trim().orEmpty()
            val title = linkElement?.selectFirst("h2.Title")?.text()?.trim().orEmpty()
            val img = item.selectFirst("div.Image img")?.attr("data-src")
                ?: item.selectFirst("div.Image img")?.attr("src")?.trim().orEmpty()

            val tvType = when {
                link.contains("/series/") -> TvType.TvSeries
                link.contains("/pelicula/") || link.contains("/peliculas/") -> TvType.Movie
                link.contains("/anime/") -> TvType.Anime
                else -> TvType.Movie // Default a Movie si no se puede determinar
            }

            if (title.isNotBlank() && link.isNotBlank()) {
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Cuevana3Provider", "LOAD_START - URL de entrada: $url")
        val doc = app.get(url).document
        Log.d("Cuevana3Provider", "LOAD_DOC_HTML - (Primeros 1000 chars) ${doc.html().take(1000)}")

        val title = doc.selectFirst("h1.Title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("article.TPost.movtv-info.cont div.Image img")?.attr("data-src")
            ?: doc.selectFirst("article.TPost.movtv-info.cont div.Image img")?.attr("src")?.trim().orEmpty()
        val plot = doc.selectFirst("div.Description p")?.text()?.trim().orEmpty()
        val tags = doc.select("ul.InfoList li strong:contains(Genero) + a, ul.InfoList li strong:contains(Genero) + span a").map { it.text().trim() }
        val year = doc.selectFirst("span.Year")?.text()?.trim()?.toIntOrNull()
            ?: doc.selectFirst("p.meta span:last-child")?.text()?.trim()?.toIntOrNull()

        val actors = doc.select("ul.InfoList li.loadactor a").mapNotNull {
            val actorName = it.text().trim()
            if (actorName.isNotBlank()) {
                ActorData(actor = Actor(actorName))
            } else {
                null
            }
        }
        val directors = doc.select("ul.InfoList li strong:contains(Director) + span a").mapNotNull {
            it.text().trim().orEmpty()
        }

        val isSeries = url.contains("/serie/", ignoreCase = true) || url.contains("/series/", ignoreCase = true)
        Log.d("Cuevana3Provider", "LOAD_DEBUG_URL: $url, isSeries: $isSeries")

        val episodesList = ArrayList<Episode>()

        if (isSeries) {
            val seasonOptions = doc.select("select#select-season option")
            Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG - Number of season options found: ${seasonOptions.size}")

            if (seasonOptions.isNotEmpty()) {
                seasonOptions.apmapIndexed { index, seasonOption ->
                    val seasonNumber = seasonOption.attr("value").toIntOrNull() ?: (index + 1)
                    val seasonTitle = seasonOption.text().trim()
                    Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG - Processing Season: $seasonNumber ($seasonTitle)")

                    val episodesInSection = doc.select("ul#season-$seasonNumber li.TPostMv")
                    Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG - Episodes in season $seasonNumber found: ${episodesInSection.size}")

                    episodesInSection.mapNotNull { item ->
                        val linkElement = item.selectFirst("a")
                        val epUrl = linkElement?.attr("href")?.trim().orEmpty()
                        val epTitleFull = linkElement?.selectFirst("h2.Title")?.text()?.trim().orEmpty()
                        val epImage = item.selectFirst("div.Image img")?.attr("data-src")
                            ?: item.selectFirst("div.Image img")?.attr("src")?.trim().orEmpty()

                        val episodeNumberFromSpan = item.selectFirst("span.Year")?.text()?.trim()?.lowercase()
                        val episodeNumberMatch = if (episodeNumberFromSpan != null && (episodeNumberFromSpan.contains("x") || episodeNumberFromSpan.matches(Regex("\\d+")))) {
                            episodeNumberFromSpan.split("x").lastOrNull()?.toIntOrNull()
                                ?: episodeNumberFromSpan.replace(Regex("[^0-9]"), "").toIntOrNull()
                        } else {
                            epTitleFull.lowercase().split("x").lastOrNull()?.toIntOrNull()
                                ?: epTitleFull.lowercase().replace(Regex(".*episodio\\s*"), "").replace(Regex("[^0-9].*"), "").toIntOrNull()
                        }

                        val episodeName = if (epTitleFull.contains("episodio", ignoreCase = true)) {
                            epTitleFull.substringAfter("episodio", "").trim().removePrefix(".").trim()
                        } else if (epTitleFull.contains("x")) {
                            epTitleFull.substringAfterLast("x").trim()
                        } else {
                            epTitleFull
                        }

                        Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG_EPISODE_PARSE - Title: '$epTitleFull', Span.Year: '$episodeNumberFromSpan', Parsed Episode Num: $episodeNumberMatch, Name: '$episodeName', URL: '$epUrl'")

                        if (epUrl.isNotBlank() && episodeNumberMatch != null) {
                            newEpisode(
                                EpisodeLoadData(epTitleFull, epUrl).toJson()
                            ) {
                                this.name = episodeName
                                this.season = seasonNumber
                                this.episode = episodeNumberMatch
                                this.posterUrl = fixUrl(epImage)
                                if (this.posterUrl.isNullOrBlank()) {
                                    this.posterUrl = fixUrl(poster)
                                }
                            }
                        } else {
                            Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG_EPISODE_SKIP - Skipping episode due to missing URL ($epUrl) or number ($episodeNumberMatch) for title: $epTitleFull")
                            null
                        }
                    }
                }.flatten().also { episodesList.addAll(it) }
            } else {
                Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG - No explicit season options, trying direct episode list.")
                val directEpisodes = doc.select("ul.all-episodes li.TPostMv").mapNotNull { item ->
                    val linkElement = item.selectFirst("a")
                    val epUrl = linkElement?.attr("href")?.trim().orEmpty()
                    val epTitleFull = linkElement?.selectFirst("h2.Title")?.text()?.trim().orEmpty()
                    val epImage = item.selectFirst("div.Image img")?.attr("data-src")
                        ?: item.selectFirst("div.Image img")?.attr("src")?.trim().orEmpty()

                    val episodeNumberFromSpan = item.selectFirst("span.Year")?.text()?.trim()?.lowercase()
                    val episodeNumberMatch = if (episodeNumberFromSpan != null && (episodeNumberFromSpan.contains("x") || episodeNumberFromSpan.matches(Regex("\\d+")))) {
                        episodeNumberFromSpan.split("x").lastOrNull()?.toIntOrNull()
                            ?: episodeNumberFromSpan.replace(Regex("[^0-9]"), "").toIntOrNull()
                    } else {
                        epTitleFull.lowercase().split("x").lastOrNull()?.toIntOrNull()
                            ?: epTitleFull.lowercase().replace(Regex(".*episodio\\s*"), "").replace(Regex("[^0-9].*"), "").toIntOrNull()
                    }

                    val episodeName = if (epTitleFull.contains("episodio", ignoreCase = true)) {
                        epTitleFull.substringAfter("episodio", "").trim().removePrefix(".").trim()
                    } else if (epTitleFull.contains("x")) {
                        epTitleFull.substringAfterLast("x").trim()
                    } else {
                        epTitleFull
                    }

                    Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG_DIRECT_EPISODE_PARSE - Title: '$epTitleFull', Span.Year: '$episodeNumberFromSpan', Parsed Episode Num: $episodeNumberMatch, Name: '$episodeName', URL: '$epUrl'")

                    if (epUrl.isNotBlank() && episodeNumberMatch != null) {
                        newEpisode(
                            EpisodeLoadData(epTitleFull, epUrl).toJson()
                        ) {
                            this.name = episodeName
                            this.season = 1 // Asumir temporada 1
                            this.episode = episodeNumberMatch
                            this.posterUrl = fixUrl(epImage)
                            if (this.posterUrl.isNullOrBlank()) {
                                this.posterUrl = fixUrl(poster)
                            }
                        }
                    } else {
                        Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG_DIRECT_EPISODE_SKIP - Skipping direct episode due to missing URL ($epUrl) or number ($episodeNumberMatch) for title: $epTitleFull")
                        null
                    }
                }
                episodesList.addAll(directEpisodes)
            }
            Log.d("Cuevana3Provider", "LOAD_SERIES_DEBUG - Total episodes collected for series: ${episodesList.size}")

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodesList
            ) {
                this.posterUrl = fixUrl(poster)
                this.backgroundPosterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actors
                if (directors.isNotEmpty()) {
                    this.plot = (this.plot ?: "") + "\n\nDirectores: " + directors.joinToString(", ")
                }
            }
        } else {
            // Lógica para Películas
            Log.d("Cuevana3Provider", "LOAD_DEBUG_TREATED_AS_MOVIE - URL: $url")
            val movieEpisodes = ArrayList<Episode>()
            movieEpisodes.add(
                newEpisode(
                    EpisodeLoadData(title, url).toJson()
                ) {
                    this.name = title
                    this.posterUrl = fixUrl(poster)
                }
            )

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = EpisodeLoadData(title, url).toJson()
            ) {
                this.posterUrl = fixUrl(poster)
                this.backgroundPosterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actors
                if (directors.isNotEmpty()) {
                    this.plot = (this.plot ?: "") + "\n\nDirectores: " + directors.joinToString(", ")
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Cuevana3Provider", "LOADLINKS_START - Data de entrada: $data")

        val parsedData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedData?.url ?: data
        Log.d("Cuevana3Provider", "LOADLINKS_TARGET_URL - URL objetivo: $targetUrl")

        // *** CORRECCIÓN CLAVE AQUÍ: Usar fixUrl para obtener la URL completa ***
        val doc = app.get(fixUrl(targetUrl)).document
        Log.d("Cuevana3Provider", "LOADLINKS_DOC_HTML - (Primeros 1000 chars) ${doc.html().take(1000)}")

        var foundLinks = false

        val playerIframes = doc.select("div.TPlayerTb iframe")
        Log.d("Cuevana3Provider", "LOADLINKS_IFRAMES - Found ${playerIframes.size} iframes.")
        for (iframe in playerIframes) {
            val iframeSrc = iframe.attr("src").orEmpty()
            val dataSrc = iframe.attr("data-src").orEmpty()

            val actualLink = if (dataSrc.isNotBlank()) dataSrc else iframeSrc

            if (actualLink.isNotBlank()) {
                Log.d("Cuevana3Provider", "LOADLINKS_IFRAME_SRC - Iframe encontrado: $actualLink")
                if (loadExtractor(actualLink, targetUrl, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        val downloadButtons = doc.select("a.btn-download, a[href*=.mp4]")
        Log.d("Cuevana3Provider", "LOADLINKS_DOWNLOAD_BUTTONS - Found ${downloadButtons.size} download buttons (check selector).")
        for (button in downloadButtons) {
            val downloadUrl = button.attr("href").orEmpty()
            val linkName = button.text().trim().orEmpty()
            if (downloadUrl.isNotBlank()) {
                Log.d("Cuevana3Provider", "LOADLINKS_DOWNLOAD - Enlace de descarga encontrado: $downloadUrl")
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Descarga ($linkName)",
                        url = downloadUrl
                    ) {
                        this.type = if (downloadUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
                foundLinks = true
            }
        }

        Log.d("Cuevana3Provider", "LOADLINKS_END - Enlaces encontrados: $foundLinks")
        return foundLinks
    }
}