package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import android.util.Log // Aseguramos la importación

class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://ww3.monoschinos3.com"
    override var name = "MonosChinos3"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "" to "Últimos capítulos 🔥",
        "" to "Series recientes ⛩"
    )

    private fun getTvType(text: String): TvType {
        return when {
            text.contains("pelicula", ignoreCase = true) -> TvType.AnimeMovie
            text.contains("anime", ignoreCase = true) -> TvType.Anime
            text.contains("ova", ignoreCase = true) -> TvType.Anime
            text.contains("especial", ignoreCase = true) -> TvType.Anime
            else -> TvType.Anime
        }
    }

    private fun getStatus(text: String): ShowStatus? {
        return when (text.lowercase()) {
            "en emisión", "en emision" -> ShowStatus.Ongoing
            "finalizado" -> ShowStatus.Completed
            else -> null
        }
    }

    private fun String.base64Decode(): String {
        return try {
            String(android.util.Base64.decode(this, android.util.Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            Log.e("Base64", "Error al decodificar: $this", e)
            ""
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null

        val href = fixUrl(a.attr("href"))

        val title = a.selectFirst("h3")?.text() ?: a.selectFirst("img")?.attr("alt") ?: ""

        val posterUrl = fixUrlNull(a.selectFirst("div.tarjeta img")?.attr("src") ?: a.selectFirst("img")?.attr("src"))

        val spanText = a.selectFirst("span.text-muted")?.text() ?: ""
        val type = getTvType(spanText)

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null

        val episodeHref = fixUrl(a.attr("href"))

        val seriesPath = episodeHref.substringBefore("/episodio")
        val seriesHref = fixUrl(seriesPath.replace("/ver/", "/anime/"))

        val title = a.selectFirst("h3")?.text() ?: a.selectFirst("img")?.attr("alt") ?: ""

        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))

        val type = TvType.Anime

        return newAnimeSearchResponse(title, seriesHref, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = if (page <= 1) mainUrl else "$mainUrl/${request.data}?pagina=$page"

        Log.d("Monoschinos3", "Solicitando URL: $requestUrl para ${request.name}")

        val document = app.get(requestUrl).document
        Log.d("Monoschinos3", "Documento cargado. Tamaño: ${document.html().length}")

        val list: List<SearchResponse> = when (request.name) {
            "Series recientes ⛩" -> {
                // Selector basado en el H2 que viste en el HTML
                document.select("section:has(h2:contains(Series recientes)) article").mapNotNull {
                    it.toSearchResult()
                }
            }
            "Últimos capítulos 🔥" -> {
                document.select("section:has(h2:contains(Últimos capítulos)) article").mapNotNull {
                    it.toEpisodeSearchResult()
                }
            }
            else -> {
                document.select("ul[role=list] li article, li.ficha_efecto").mapNotNull { it.toSearchResult() }
            }
        }

        Log.d("Monoschinos3", "${request.name} encontrados: ${list.size}")

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.btn.btn-outline-primary").text().contains("Siguiente")
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/animes?q=$query").document

        val results = document.select("li.ficha_efecto").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2.titulo-post")?.text()
            ?: document.selectFirst("h2.fs-5.text-light")?.text() ?: "Unknown"

        val poster = fixUrlNull(document.selectFirst("div.mt-5 img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("section img")?.attr("src")) ?: ""

        val description = document.selectFirst("p#synopsis")?.text()

        val infoDl = document.selectFirst("dl")

        val rawtype = infoDl?.select("dt:contains(Tipo:) + dd")?.text() ?: ""
        val type = getTvType(rawtype)

        val yearText = infoDl?.select("dt:contains(Fecha de emisión:) + dd")?.text()
        val year = yearText?.toIntOrNull()

        val statusText = document.selectFirst("div.d-flex.gap-2.lh-sm:contains(Estado) div:last-child")?.text()
        val showStatus = statusText?.let { getStatus(it) }

        val tags = document.select("div.lh-lg a span.badge").map { it.text() }

        val recommendations = emptyList<SearchResponse>()

        if (type == TvType.Anime || type == TvType.AnimeMovie) {
            val episodes = mutableListOf<Episode>()

            document.select("div#home-tab-pane li article a").map {
                val epHref = fixUrl(it.attr("href") ?: return@map)
                val titleElement = it.selectFirst("h2")?.text() ?: ""

                val epNumber = Regex("""(\d+)""").find(titleElement)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epHref.substringAfterLast("-").toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = titleElement
                        this.episode = epNumber
                        this.posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
                    }
                )
            }

            val sortedEpisodes = episodes.sortedBy { it.episode }

            return newAnimeLoadResponse(title, url, type) {
                addEpisodes(DubStatus.Subbed, sortedEpisodes)
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = showStatus
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val href = fixUrl(document.selectFirst("a[href*='/ver/']:contains(Ver Ahora)")?.attr("href") ?: "")

            return newMovieLoadResponse(title, url, type, href) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Monoschinos3", "loadLinks iniciado con URL: $data")

        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e("Monoschinos3", "Error al cargar documento: ${e.message}")
            return false
        }

        val serverLinks = mutableListOf<Pair<String, String>>()

        document.select("ul.nav-tabs button.play-video").forEach { element ->
            val serverName = element.attr("data-title")
            val base64Url = element.attr("data-video")

            if (base64Url.isNotEmpty()) {
                try {
                    val decodedUrl = base64Url.base64Decode()

                    if (decodedUrl.startsWith("http")) {
                        serverLinks.add(serverName to decodedUrl)
                    }
                } catch (e: Exception) {
                    Log.e("Monoschinos3", "Error decodificando Base64 para ${serverName}: ${e.message}")
                }
            }
        }

        if (serverLinks.isEmpty()) {
            Log.e("Monoschinos3", "No se encontraron enlaces de servidores.")
            return false
        }

        val jobs = mutableListOf<Job>()
        coroutineScope {
            serverLinks.forEach { (server, url) ->
                jobs += launch(Dispatchers.IO) {
                    Log.d("Monoschinos3", "Cargando extractor para: $server - $url")
                    try {
                        loadExtractor(
                            url = url,
                            referer = mainUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    } catch (e: Exception) {
                        Log.e("Monoschinos3", "Error procesando $server: ${e.message}, URL: $url")
                    }
                }
            }
        }

        jobs.joinAll()

        return serverLinks.isNotEmpty().also {
            Log.i("Monoschinos3", "Procesamiento de loadLinks completado, Éxito: $it")
        }
    }
}