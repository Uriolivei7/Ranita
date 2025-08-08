package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
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
import java.net.URLDecoder
import android.util.Base64 as AndroidBase64


class KatanimeProvider : MainAPI() {
    override var mainUrl = "https://katanime.net"
    override var name = "Katanime"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // El nuevo sitio no parece usar Cloudflare.
    // private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img.lozad")?.attr("data-src")
        val yearText = element.selectFirst("div._2y8kd")?.text()?.trim()
        val year = yearText?.takeLast(4)?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = posterUrl
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
        val posterUrl = element.selectFirst("img")?.attr("src")
        val yearText = element.selectFirst("div._2y8kd:not(.etag)")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = posterUrl
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
        for (i in 0 until retries) {
            try {
                val res = app.get(url, timeout = timeoutMs)
                if (res.isSuccessful) return res.text
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "safeAppGet error for URL: $url: ${e.message}", e)
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

        // Capítulos recientes
        doc.selectFirst("div#content-left div#article-div")?.let { container ->
            val animes = container.select("div._135yj._2FQAt.chap").mapNotNull {
                val linkElement = it.selectFirst("a._1A2Dc._38LRT")
                val link = linkElement?.attr("href")
                val titleElement = it.selectFirst("div._2NNxg a._2uHIS")
                val title = titleElement?.text()?.trim()
                val posterUrl = it.selectFirst("img.lozad")?.attr("data-src")
                if (title != null && link != null) {
                    val animeUrl = fixUrl(link).substringBefore("/capitulo/", "")
                    newAnimeSearchResponse(
                        title,
                        animeUrl
                    ) {
                        this.type = TvType.Anime
                        this.posterUrl = posterUrl
                    }
                } else null
            }
            if (animes.isNotEmpty()) items.add(HomePageList("Capítulos recientes", animes))
        }

        // Animes populares (widget derecho)
        doc.selectFirst("div.content-right div#widget")?.let { container ->
            val animes = container.select("div._type3").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Animes populares", animes))
        }

        // Animes recientes (sección completa)
        doc.selectFirst("div#content-full div#article-div.recientes")?.let { container ->
            val animes = container.select("div._135yj._2FQAt.extra").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Animes recientes", animes))
        }

        return HomePageResponse(items)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div._135yj._2FQAt.full._2mJki").mapNotNull {
            extractSearchItem(it)
        }
    }

    data class EpisodeLoadData(
        val episodeUrl: String,
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("KatanimeProvider", "Iniciando load para URL: $url")
        val html = safeAppGet(url) ?: run {
            Log.e("KatanimeProvider", "Fallo al obtener HTML para la URL: $url")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.comics-title.ajp")?.text()?.trim() ?: doc.selectFirst("h3.comics-alt")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div#animeinfo img")?.attr("data-src") ?: ""
        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text() }
        val yearText = doc.selectFirst("div.details-by")?.text()?.substringAfter("•")?.trim()
        val year = yearText?.takeLast(4)?.toIntOrNull()
        val status = parseStatus(doc.selectFirst("span#estado")?.text()?.trim() ?: "")

        val allEpisodes = ArrayList<Episode>()

        val token = doc.selectFirst("input[name=_token]")?.attr("value")
        val episodeListUrlElement = doc.selectFirst("div#c_list")
        val episodeListApiUrl = episodeListUrlElement?.attr("data-url")

        if (!episodeListApiUrl.isNullOrBlank() && !token.isNullOrBlank()) {
            Log.d("KatanimeProvider", "Token y URL de API encontrados. Token: $token, API URL: $episodeListApiUrl")
            val headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to mainUrl
            )

            val data = mapOf(
                "_token" to token,
                "pagina" to "1"
            )

            val episodesHtml = try {
                app.post(episodeListApiUrl, headers = headers, data = data).text
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "Fallo al obtener los episodios: ${e.message}")
                null
            }

            if (episodesHtml != null) {
                val episodesDoc = Jsoup.parse(episodesHtml)
                val episodeElements = episodesDoc.select("div.anime-box")
                Log.d("KatanimeProvider", "Se encontraron ${episodeElements.size} elementos de episodios.")

                episodeElements.mapNotNull { element ->
                    val epLinkElement = element.selectFirst("a._1A2Dc._38LRT")
                    val epUrl = fixUrl(epLinkElement?.attr("href") ?: "")
                    val epNumText = element.selectFirst("span._2y8kd.etag")?.text()?.replace("Capítulo", "")?.trim() ?: ""
                    val epNum = epNumText.toIntOrNull()
                    val epTitle = element.selectFirst("div._2NNxg a._2uHIS")?.text()?.trim() ?: ""

                    if (epUrl.isNotBlank() && epNum != null) {
                        val episodeData = EpisodeLoadData(epUrl)
                        allEpisodes.add(newEpisode(episodeData.toJson()) {
                            this.name = epTitle
                            this.episode = epNum
                        })
                    } else {
                        Log.e("KatanimeProvider", "Fallo al extraer episodio. URL: $epUrl, Número: $epNum, Título: $epTitle")
                    }
                }
            } else {
                Log.e("KatanimeProvider", "Fallo al obtener el HTML de los episodios desde la API: $episodeListApiUrl")
            }
        }

        val recommendations = doc.select("div#slidebar-anime div._type3.np").mapNotNull { element ->
            val recLink = element.selectFirst("a._1A2Dc._38LRT")?.attr("href")
            val recTitle = element.selectFirst("div._2NNxg a._2uHIS")?.text()?.trim()
            val recPoster = element.selectFirst("img.lozad")?.attr("data-src")
            val recYearText = element.selectFirst("div._2y8kd")?.text()?.trim()
            val recYear = recYearText?.split(" - ")?.firstOrNull()?.toIntOrNull()

            if (recLink != null && recTitle != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.posterUrl = recPoster
                    this.year = recYear
                }
            } else null
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = allEpisodes.reversed()
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
            //this.status = status // Descomentado y corregido
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("KatanimeProvider", "Iniciando loadLinks para data: $data")
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val episodeUrl = parsedEpisodeData?.episodeUrl ?: data

        Log.d("KatanimeProvider", "loadLinks - URL a cargar: $episodeUrl")

        val html = safeAppGet(episodeUrl) ?: run {
            Log.e("KatanimeProvider", "loadLinks - Fallo al obtener HTML para: $episodeUrl")
            return false
        }

        val doc = Jsoup.parse(html)
        val serversElement = doc.selectFirst("ul.nav.nav-tabs.list-server") ?: run {
            Log.e("KatanimeProvider", "loadLinks - No se encontraron servidores.")
            return false
        }
        val servers = serversElement.select("li a")

        var linksFound = false
        servers.amap { serverLink ->
            val serverId = serverLink.attr("data-id")
            val serverName = serverLink.text().trim()
            val serverUrl = "$mainUrl/ajax/server/$serverId"
            Log.d("KatanimeProvider", "loadLinks - Procesando servidor: $serverName, URL: $serverUrl")

            val sourceResponse = try {
                app.get(serverUrl, referer = episodeUrl).text
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "Error fetching source for server $serverName: ${e.message}")
                null
            }

            if (sourceResponse.isNullOrBlank()) {
                Log.e("KatanimeProvider", "Respuesta vacía para el servidor: $serverName")
                return@amap
            }

            try {
                val sourceJson = tryParseJson<Map<String, String>>(sourceResponse)
                val iframeUrl = sourceJson?.get("src")
                if (!iframeUrl.isNullOrBlank()) {
                    Log.d("KatanimeProvider", "loadLinks - Found source: $iframeUrl from server: $serverName")

                    val decodedUrl = if (iframeUrl.startsWith("http")) iframeUrl else String(AndroidBase64.decode(iframeUrl, AndroidBase64.DEFAULT))

                    loadExtractor(decodedUrl, episodeUrl, subtitleCallback, callback)
                    linksFound = true
                } else {
                    Log.e("KatanimeProvider", "No se encontró URL de iframe en la respuesta del servidor $serverName.")
                }
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "Error parsing source JSON from server $serverName: ${e.message}")
            }
        }
        Log.d("KatanimeProvider", "Finalizando loadLinks. Enlaces encontrados: $linksFound")
        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emision" -> ShowStatus.Ongoing
            "en emision - " -> ShowStatus.Ongoing
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