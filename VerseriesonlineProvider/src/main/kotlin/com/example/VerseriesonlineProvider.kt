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
import kotlinx.coroutines.coroutineScope

class VerseriesonlineProvider : MainAPI() {
    override var mainUrl = "https://www.veronline.tax"
    override var name = "Veronline"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon
    )

    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val mainPageDoc = app.get(mainUrl).document

        val sliderItems = mainPageDoc.select("div#owl-slider div.owl-item div.shortstory").mapNotNull {
            val tvType = TvType.TvSeries
            val title = it.selectFirst("h4.short-link a")?.text()?.trim().orEmpty()
            val link = it.selectFirst("h4.short-link a")?.attr("href")?.trim().orEmpty()
            val img = it.selectFirst("div.short-images a img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Veronline", "Missing title or link for a slider item. Title: $title, Link: $link")
                null
            }
        }
        Log.d("Veronline", "Total Slider Items: ${sliderItems.size}")

        if (sliderItems.isNotEmpty()) {
            items.add(0, HomePageList("Destacadas", sliderItems))
        }

        val tvType = TvType.TvSeries
        val latestSeriesItems = mainPageDoc.select("div.block-site-b div.shortstory-in").mapNotNull {
            val title = it.selectFirst("h4.short-link a")?.text()?.trim().orEmpty()
            val link = it.selectFirst("h4.short-link a")?.attr("href")?.trim().orEmpty()
            val img = it.selectFirst("div.short-images img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                Log.d("Veronline", "Latest Series Item found: $title, Link: $link, Img: $img")
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Veronline", "Missing title or link for latest series item. Title: $title, Link: $link")
                null
            }
        }
        Log.d("Veronline", "Total 'Últimas Series Agregadas' Items: ${latestSeriesItems.size}")

        if (latestSeriesItems.isNotEmpty()) {
            items.add(HomePageList("Últimas Series Agregadas", latestSeriesItems))
        }

        Log.d("Veronline", "Final number of HomePageLists: ${items.size}")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/recherche?q=$query"
        val doc = app.get(url).document
        Log.d("Veronline", "SEARCH_DOC_HTML - (Primeros 1000 chars) ${doc.html().take(1000)}")

        return doc.select("div.shortstory-in").mapNotNull {
            val tvType = TvType.TvSeries

            val titleElement = it.selectFirst("h4.short-link a")
            val title = titleElement?.text()?.trim().orEmpty()
            val link = titleElement?.attr("href")?.trim().orEmpty()

            val img = it.selectFirst("div.short-images img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                Log.d("Veronline", "SEARCH_ITEM_FOUND: Title=$title, Link=$link, Img=$img")
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Veronline", "SEARCH_ITEM_SKIPPED: Missing info. Title=$title, Link=$link")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Veronline", "LOAD_START - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Veronline", "LOAD_URL - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Veronline", "LOAD_URL - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Veronline", "LOAD_URL - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Veronline", "LOAD_ERROR - URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("Veronline", "LOAD_ERROR - No se pudo obtener el documento principal de: $cleanUrl. ${e.message}", e)
            return null
        }
        val tvType = TvType.TvSeries

        val title = doc.selectFirst("div.fstory-infos h1.fstory-h1")?.text()?.replace("ver serie ", "")?.replace(" Online gratis HD", "")?.trim().orEmpty()
        val poster = doc.selectFirst("div.fstory-poster-in img")?.attr("src")?.trim().orEmpty()
        val description = doc.selectFirst("div.block-infos p")?.text()?.trim().orEmpty()
        val tags = doc.select("div.finfo-block a[href*='/series-online/']").map { it.text().trim().orEmpty() }

        val actors = doc.select("div.finfo-block:has(span:contains(Actores)) a[href*='/series-online/actor/']").mapNotNull {
            val actorName = it.text().trim()
            if (actorName.isNotBlank()) {
                ActorData(actor = Actor(actorName))
            } else {
                null
            }
        }
        Log.d("Veronline", "LOAD_ACTORS - Extracted ${actors.size} actors.")

        val directors = doc.select("div.finfo-block:has(span:contains(director)) a[href*='/series-online/director/']").mapNotNull {
            it.text().trim().orEmpty()
        }
        Log.d("Veronline", "LOAD_DIRECTORS - Extracted ${directors.size} directors.")

        val allEpisodes = ArrayList<Episode>()

        val seasonElements = doc.select("div#full-video div#serie-seasons div.shortstory-in")
        Log.d("Veronline", "LOAD_EPISODES - Encontradas ${seasonElements.size} temporadas.")

        if (seasonElements.isEmpty() && doc.select("div#serie-episodes").isNotEmpty()) {
            Log.d("Veronline", "LOAD_EPISODES - No se encontraron elementos de temporada, intentando extraer episodios directamente de la página principal.")
            val defaultSeasonNumber = 1
            val mainPageEpisodes = doc.select("div#serie-episodes div.episode-list div.saision_LI2").mapNotNull { element ->
                val epurl = fixUrl(element.selectFirst("a")?.attr("href")?.trim().orEmpty())
                val epTitle = element.selectFirst("a span")?.text()?.trim().orEmpty()
                val episodeNumber = epTitle.replace(Regex("(Capítulo|Episodio)\\s*"), "").toIntOrNull()

                if (epurl.isNotBlank() && epTitle.isNotBlank() && episodeNumber != null) {
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = defaultSeasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = fixUrl(poster)
                    }
                } else {
                    Log.w("Veronline", "LOAD_EPISODES - Saltando episodio directo incompleto. Título: $epTitle, URL: $epurl, Número: $episodeNumber")
                    null
                }
            }
            allEpisodes.addAll(mainPageEpisodes)
            Log.d("Veronline", "LOAD_EPISODES - Total de episodios extraídos directamente: ${mainPageEpisodes.size}")
        } else {
            coroutineScope {
                seasonElements.map { seasonElement ->
                    val seasonTitle = seasonElement.selectFirst("h4.short-link a")?.text()?.trim().orEmpty()
                    val seasonLink = seasonElement.selectFirst("h4.short-link a")?.attr("href")?.trim().orEmpty()
                    val seasonPoster = seasonElement.selectFirst("div.short-images a img")?.attr("src")?.trim().orEmpty()

                    Log.d("Veronline", "LOAD_SEASON - Procesando elemento de temporada: Título='$seasonTitle', Enlace='$seasonLink'")

                    if (seasonLink.isNotBlank() && seasonTitle.isNotBlank()) {
                        val fullSeasonUrl = fixUrl(seasonLink)
                        val seasonNumber = seasonTitle.replace(Regex(".*Temporada\\s*"), "").toIntOrNull()
                        Log.d("Veronline", "LOAD_SEASON - Extracted Season Number: $seasonNumber (from '$seasonTitle')")

                        if (seasonNumber == null) {
                            Log.w("Veronline", "LOAD_SEASON - No se pudo extraer el número de temporada de: $seasonTitle. Saltando esta temporada.")
                            return@map emptyList<Episode>()
                        }

                        Log.d("Veronline", "LOAD_SEASON - Cargando página de temporada: $fullSeasonUrl")
                        val seasonDoc = try {
                            app.get(fullSeasonUrl).document
                        } catch (e: Exception) {
                            Log.e("Veronline", "LOAD_SEASON_ERROR - No se pudo obtener la página de la temporada $seasonTitle ($fullSeasonUrl): ${e.message}", e)
                            return@map emptyList<Episode>()
                        }

                        Log.d("Veronline", "LOAD_SEASON_DOC - Contenido de seasonDoc (primeros 500 chars): ${seasonDoc.html().take(500)}")

                        val episodesInSeason = seasonDoc.select("div#serie-episodes div.episode-list div.saision_LI2").mapNotNull { element ->
                            val epurl = fixUrl(element.selectFirst("a")?.attr("href")?.trim().orEmpty())
                            val epTitle = element.selectFirst("a span")?.text()?.trim().orEmpty()
                            val episodeNumber = epTitle.replace(Regex("(Capítulo|Episodio)\\s*"), "").toIntOrNull()

                            if (epurl.isNotBlank() && epTitle.isNotBlank() && episodeNumber != null) {
                                newEpisode(
                                    EpisodeLoadData(epTitle, epurl).toJson()
                                ) {
                                    this.name = epTitle
                                    this.season = seasonNumber
                                    this.episode = episodeNumber
                                    this.posterUrl = fixUrl(seasonPoster)
                                }
                            } else {
                                Log.w("Veronline", "LOAD_EPISODE_DETAIL - Saltando episodio incompleto. Título: $epTitle, URL: $epurl, T: $seasonNumber, E: $episodeNumber")
                                null
                            }
                        }
                        Log.d("Veronline", "LOAD_SEASON - Temporada $seasonTitle: Encontrados ${episodesInSeason.size} episodios.")
                        episodesInSeason
                    } else {
                        Log.w("Veronline", "LOAD_SEASON_WARN - Elemento de temporada incompleto: Título='$seasonTitle', Enlace='$seasonLink'. Saltando.")
                        emptyList<Episode>()
                    }
                }.flatten().also { allEpisodes.addAll(it) }
            }
        }

        Log.d("Veronline", "LOAD_END - Total de episodios recolectados: ${allEpisodes.size}")

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = allEpisodes,
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
            this.tags = tags
            this.actors = actors
            if (directors.isNotEmpty()) {
                this.plot = this.plot + "\n\nDirectores: " + directors.joinToString(", ")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Veronline", "LOADLINKS_START - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(cleanedData)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Veronline", "LOADLINKS_URL - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Veronline", "LOADLINKS_URL - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Veronline", "LOADLINKS_URL - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Veronline", "LOADLINKS_URL - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Veronline", "LOADLINKS_ERROR - URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("Veronline", "LOADLINKS_ERROR - No se pudo obtener el documento del episodio: $targetUrl. ${e.message}", e)
            return false
        }

        Log.d("Veronline", "LOADLINKS_DOC - Contenido de la página del episodio (primeros 500 chars): ${doc.html().take(500)}")

        val playerDivs = doc.select("div.player[data-url]")
        Log.d("Veronline", "LOADLINKS_PLAYERS - Encontrados ${playerDivs.size} elementos 'div.player' con 'data-url'.")

        val results = coroutineScope {
            playerDivs.map { playerDivElement ->
                val encodedUrl = playerDivElement.attr("data-url").orEmpty()
                val serverName = playerDivElement.selectFirst("span[id^=player_v_DIV_5]")?.text()?.trim().orEmpty()
                Log.d("Veronline", "LOADLINKS_PLAYERS - Procesando servidor: $serverName, encodedUrl: $encodedUrl")

                if (encodedUrl.isNotBlank() && (encodedUrl.startsWith("/streamer/") || encodedUrl.startsWith("/telecharger/"))) {
                    try {
                        val base64Part = if (encodedUrl.startsWith("/streamer/")) {
                            encodedUrl.substringAfter("/streamer/")
                        } else {
                            encodedUrl.substringAfter("/telecharger/")
                        }

                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)

                        val fullIframeUrl = if (decodedUrl.startsWith("http")) {
                            decodedUrl
                        } else {
                            "$mainUrl$decodedUrl"
                        }

                        Log.d("Veronline", "LOADLINKS_PLAYERS - Decoded URL for $serverName: $fullIframeUrl")

                        Log.d("Veronline", "LOADLINKS_DELEGATING - Delegando a CloudStream's loadExtractor: $fullIframeUrl")
                        loadExtractor(fullIframeUrl, targetUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("Veronline", "LOADLINKS_PLAYERS_ERROR - Error al decodificar o procesar data-url para $serverName ($encodedUrl): ${e.message}", e)
                        false
                    }
                } else {
                    Log.w("Veronline", "LOADLINKS_PLAYERS_WARN - data-url vacía o no comienza con '/streamer/' o '/telecharger/' para servidor $serverName: $encodedUrl")
                    false
                }
            }
        }

        val finalLinksFound = results.any { it }

        if (finalLinksFound) {
            Log.d("Veronline", "LOADLINKS_END - Se encontraron y procesaron enlaces de video.")
            return true
        }

        Log.w("Veronline", "LOADLINKS_WARN - No se encontraron enlaces de video válidos después de todas las comprobaciones.")
        return false
    }
}