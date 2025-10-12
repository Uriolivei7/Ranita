package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import kotlin.collections.ArrayList
import android.util.Log


class DoramasytProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.Movie
            else TvType.TvSeries
        }
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }

        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
    }

    override var mainUrl = "https://doramasyt.com"
    override var name = "DoramasYT"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
    )

    private suspend fun getToken(url: String): Map<String, String> {
        val maintas = app.get(url, headers = mapOf(
                        "Host" to "www.doramasyt.com",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to "https://www.doramasyt.com/buscar?q=",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl,
                        "DNT" to "1",
                        "Alt-Used" to "www.doramasyt.com",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "TE" to "trailers"
                ))
        val token = maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
        val cookies = maintas.cookies
        latestToken = token
        latestCookie = cookies
        return latestCookie
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("Doramasyt_MainPage", "Iniciando getMainPage para la página $page")

        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair("$mainUrl/doramas", "Doramas"),
            Pair("$mainUrl/doramas?categoria=pelicula", "Peliculas")
        )
        val items = ArrayList<HomePageList>()
        var isHorizontal = true

        Log.d("Doramasyt", "URLs configuradas: ${urls.joinToString { "${it.first} - ${it.second}" }}")

        Log.d("Doramasyt", "Procesando sección de capítulos actualizados")
        try {
            val response = app.get(mainUrl, timeout = 120)
            val updatedChaptersList: List<AnimeSearchResponse> = response.document.select("div.container section ul.row li.col article").mapNotNull { element ->
                try {
                    val title = element.selectFirst("h3")?.text()?.trim()
                    if (title.isNullOrEmpty()) {
                        Log.w("Doramasyt", "Título no encontrado, omitiendo elemento")
                        return@mapNotNull null
                    }

                    val poster = element.selectFirst("img")?.attr("data-src")?.trim() ?: ""
                    val linkElement = element.selectFirst("a")
                    if (linkElement == null) {
                        Log.w("Doramasyt", "Enlace no encontrado para $title")
                        return@mapNotNull null
                    }

                    val epRegex = Regex("episodio-(\\d+)")
                    val url = linkElement.attr("href")
                        .replace("ver/", "dorama/")
                        .replace(epRegex, "sub-espanol")

                    val epNum = title.substringAfter("Capítulo", "")
                        .trim()
                        .toIntOrNull()

                    Log.d("Doramasyt", "Capítulo procesado: Título = $title, URL = $url, Poster = $poster, Episodio = $epNum")

                    newAnimeSearchResponse(title, fixUrl(url)) {
                        this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
                        addDubStatus(getDubStatus(title), epNum)
                    }
                } catch (e: Exception) {
                    Log.e("Doramasyt", "Error al procesar capítulo: ${e.message}", e)
                    null
                }
            }

            Log.d("Doramasyt", "Número de capítulos procesados: ${updatedChaptersList.size}")

            if (updatedChaptersList.isNotEmpty()) {
                items.add(HomePageList("Capítulos actualizados", updatedChaptersList, isHorizontal))
            }
        } catch (e: Exception) {
            Log.e("Doramasyt", "Error al obtener capítulos actualizados: ${e.message}", e)
        }

        Log.d("Doramasyt", "Procesando URLs adicionales")
        urls.apmap { (url, name) ->
            Log.d("Doramasyt", "Procesando URL: $url - $name")
            try {
                val response = app.get(url)
                val homeList: List<AnimeSearchResponse> = response.document.select("li.col").mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h3")?.text()?.trim()
                        if (title.isNullOrEmpty()) {
                            Log.w("Doramasyt", "Título no encontrado en $name, omitiendo elemento")
                            return@mapNotNull null
                        }

                        val poster = element.selectFirst("img")?.attr("data-src")?.trim() ?: ""
                        val linkElement = element.selectFirst("a")
                        if (linkElement == null) {
                            Log.w("Doramasyt", "Enlace no encontrado para $title en $name")
                            return@mapNotNull null
                        }

                        val animeUrl = fixUrl(linkElement.attr("href"))

                        Log.d("Doramasyt", "Elemento en $name: Título = $title, Poster = $poster, URL = $animeUrl")

                        newAnimeSearchResponse(title, animeUrl) {
                            this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
                            addDubStatus(getDubStatus(title))
                        }
                    } catch (e: Exception) {
                        Log.e("Doramasyt", "Error al procesar elemento en $url: ${e.message}", e)
                        null
                    }
                }

                Log.d("Doramasyt", "Número de elementos procesados en $name: ${homeList.size}")

                if (homeList.isNotEmpty()) {
                    items.add(HomePageList(name, homeList))
                }
            } catch (e: Exception) {
                Log.e("Doramasyt", "Error al obtener datos de la URL $url: ${e.message}", e)
            }
        }

        if (items.isEmpty()) {
            Log.e("Doramasyt", "No se encontraron elementos para la página principal")
            throw ErrorLoadingException("No se encontraron elementos para la página principal")
        }

        Log.d("Doramasyt", "Finalizando getMainPage. Número de secciones: ${items.size}")
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select("li.col").map {
            val title = it.selectFirst("h3")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("img")!!.attr("data-src")
            newAnimeSearchResponse(title, href, TvType.TvSeries){
                this. posterUrl = image
                this.dubStatus = if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed)
            }
        }
    }

    data class CapList(
            @JsonProperty("eps")val eps: List<Ep>,
    )

    data class Ep(
            val num: Int?,
    )

    override suspend fun load(url: String): LoadResponse {
        getToken(url)
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst("img.rounded-3")?.attr("data-src") ?: ""
        val backimage = doc.selectFirst("img.w-100")?.attr("data-src") ?: ""
        //val backimageregex = Regex("url\\((.*)\\)")
        //val backimage = backimageregex.find(backimagedoc)?.destructured?.component1() ?: ""
        val title = doc.selectFirst(".fs-2")?.text() ?: ""
        val type = doc.selectFirst("div.bg-transparent > dl:nth-child(1) > dd")?.text() ?: ""
        val description = doc.selectFirst("div.mb-3")?.text()?.replace("Ver menos", "") ?: ""
        val genres = doc.select(".my-4 > div a span").map { it.text() }
        val status = when (doc.selectFirst("div.col:nth-child(1) > div:nth-child(1) > div")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val caplist = doc.selectFirst(".caplist")?.attr("data-ajax") ?: throw ErrorLoadingException("Intenta de nuevo")

        val capJson = app.post(caplist,
                headers = mapOf(
                        "Host" to "www.doramasyt.com",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to url,
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl,
                        "DNT" to "1",
                        "Alt-Used" to "www.doramasyt.com",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "TE" to "trailers"
                ),
                cookies = latestCookie,
                data = mapOf("_token" to latestToken)).parsed<CapList>()
        val epList = capJson.eps.map { epnum ->
            val epUrl = "${url.replace("-sub-espanol","").replace("/dorama/","/ver/")}-episodio-${epnum.num}"
            newEpisode(
                    epUrl
            ){
                this.episode = epnum.toString().toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, epList)
            showStatus = status
            plot = description
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Doramasyt", "loadLinks iniciado - data: $data, isCasting: $isCasting")

        try {
            val response = app.get(data)
            Log.d("Doramasyt", "Respuesta obtenida de: $data")

            val buttons = response.document.select("#myTab button.play-video")
            Log.d("Doramasyt", "Número de botones encontrados: ${buttons.size}")

            if (buttons.isEmpty()) {
                Log.w("Doramasyt", "No se encontraron botones con el selector '#myTab button.play-video'")
                val alternativeButtons = response.document.select("button[data-player]")
                Log.d("Doramasyt", "Botones alternativos encontrados: ${alternativeButtons.size}")

                if (alternativeButtons.isNotEmpty()) {
                    buttons.addAll(alternativeButtons)
                }
            }

            buttons.forEachIndexed { index, button ->
                try {
                    val serverName = button.text()
                    Log.d("Doramasyt", "Procesando servidor $index: $serverName")

                    val encodedurl = button.attr("data-player")
                    val useApi = button.attr("data-usa-api")

                    Log.d("Doramasyt", "URL codificada del servidor $serverName: $encodedurl")
                    Log.d("Doramasyt", "Usa API: $useApi")

                    if (encodedurl.isEmpty()) {
                        Log.w("Doramasyt", "URL codificada vacía para el servidor $serverName")
                        return@forEachIndexed
                    }

                    val urlDecoded = try {
                        base64Decode(encodedurl)
                    } catch (e: Exception) {
                        Log.e("Doramasyt", "Error al decodificar base64 para $serverName: ${e.message}", e)
                        return@forEachIndexed
                    }

                    Log.d("Doramasyt", "URL decodificada del servidor $serverName: $urlDecoded")

                    if (useApi == "1") {
                        Log.d("Doramasyt", "Servidor $serverName usa API, obteniendo iframe")

                        val iframeUrl = "$mainUrl/reproductor?video=$encodedurl"
                        Log.d("Doramasyt", "Obteniendo iframe de: $iframeUrl")

                        try {
                            val iframeResponse = app.get(iframeUrl)
                            val iframeSrc = iframeResponse.document.selectFirst("iframe")?.attr("src")

                            if (iframeSrc != null) {
                                Log.d("Doramasyt", "iframe src encontrado para $serverName: $iframeSrc")
                                customLoadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                            } else {
                                Log.w("Doramasyt", "No se encontró iframe para el servidor $serverName")
                                val ifplayContent = iframeResponse.document.select(".ifplay iframe").attr("src")
                                if (ifplayContent.isNotEmpty()) {
                                    Log.d("Doramasyt", "iframe encontrado en .ifplay: $ifplayContent")
                                    customLoadExtractor(ifplayContent, mainUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Doramasyt", "Error al obtener iframe para $serverName: ${e.message}", e)
                        }
                    } else {
                        if (urlDecoded.startsWith("http")) {
                            Log.d("Doramasyt", "URL directa para $serverName: $urlDecoded")
                            customLoadExtractor(urlDecoded, mainUrl, subtitleCallback, callback)
                        } else {
                            Log.w("Doramasyt", "URL decodificada no es HTTP para $serverName: $urlDecoded")
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Doramasyt", "Error al procesar servidor $index: ${e.message}", e)
                }
            }

            Log.d("Doramasyt", "loadLinks completado")
            return true

        } catch (e: Exception) {
            Log.e("Doramasyt", "Error general en loadLinks: ${e.message}", e)
            return false
        }
    }

    suspend fun customLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        loadExtractor(url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com","https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            , referer, subtitleCallback, callback)
    }
}