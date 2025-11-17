package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import java.util.*

class LatanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }

        private fun base64Decode(encoded: String): String {
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.e("LatanimePlugin", "Error decoding Base64: ${e.message}")
                ""
            }
        }
    }

    override var mainUrl = "https://latanime.org"
    override var name = "LatAnime"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        return app.get(url, interceptor = cloudflareKiller, headers = cloudflareKiller.getCookieHeaders(mainUrl).toMap())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair("$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película", "Películas"),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()

        try {
            // Primero, obtener el carousel/slider de la página principal
            val mainDoc = app.get(mainUrl).document
            val carouselItems = mainDoc.select("div.carousel-item a[href*=/anime/], div.carousel-item a[href*=/pelicula/]").mapNotNull { element ->
                val itemUrl = element.attr("href")
                val title = element.selectFirst("span.span-slider")?.text()?.trim() ?: ""
                val description = element.selectFirst("p.p-slider")?.text()?.trim()

                // Obtener imagen del carousel (primero intenta data-src, luego src)
                val posterElement = element.selectFirst("img.preview-image, img.d-block")
                val poster = posterElement?.attr("data-src")?.ifBlank {
                    posterElement.attr("src")
                } ?: ""

                if (itemUrl.isNotBlank() && title.isNotBlank()) {
                    newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title))
                    }
                } else null
            }

            if (carouselItems.isNotEmpty()) {
                items.add(HomePageList("Destacados", carouselItems))
            }

            // Luego obtener las otras secciones
            urls.forEach { (url, name) ->
                val doc = app.get(url).document

                // Selector más general para encontrar los animes
                val home = doc.select("div.col-md-4, div.col-lg-3, div.col-xl-2, div.col-6").mapNotNull { article ->
                    val linkElement = article.selectFirst("a[href*=/anime/], a[href*=/pelicula/]")
                    if (linkElement == null) return@mapNotNull null

                    val itemUrl = linkElement.attr("href")
                    val title = linkElement.selectFirst("h3.my-1, h3")?.text()?.trim()
                        ?: linkElement.selectFirst("div.seriedetails h3")?.text()?.trim()
                        ?: linkElement.attr("title").trim()

                    if (itemUrl.isBlank() || title.isBlank()) {
                        Log.w("LatanimeProvider", "Elemento sin URL o título")
                        return@mapNotNull null
                    }

                    // Buscar imagen con varios selectores posibles
                    val imgElement = linkElement.selectFirst("img.img-fluid2, img.img-fluid, img")
                    val poster = imgElement?.let {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }?.let { imgUrl ->
                        when {
                            imgUrl.startsWith("http") -> imgUrl
                            imgUrl.startsWith("//") -> "https:$imgUrl"
                            imgUrl.startsWith("/") -> "$mainUrl$imgUrl"
                            else -> "$mainUrl/$imgUrl"
                        }
                    } ?: ""

                    newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                        this.posterUrl = poster
                        addDubStatus(getDubStatus(title))
                    }
                }.filter { it.name.isNotBlank() }

                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                }
            }

        } catch (e: Exception) {
            Log.e("LatanimeProvider", "ERROR en getMainPage: ${e.message}", e)
            throw ErrorLoadingException("Error al cargar la página principal: ${e.message}")
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }

        return newHomePageResponse(items)
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun getDubStatus(title: String): DubStatus {
        return when {
            title.contains("Latino", ignoreCase = true) -> DubStatus.Dubbed
            title.contains("Castellano", ignoreCase = true) -> DubStatus.Dubbed
            title.contains("Subtitulado", ignoreCase = true) -> DubStatus.Subbed
            else -> DubStatus.Subbed
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = appGetChildMainUrl("$mainUrl/buscar?q=$query").document
        delay(2000)
        return doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").mapNotNull { article ->
            val itemLink = article.selectFirst("a")
            val title = itemLink?.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
            val href = itemLink?.attr("href")

            if (href == null) {
                Log.w("LatanimePlugin", "WARN: href es nulo para un elemento en search.")
                return@mapNotNull null
            }

            val imageElement = article.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
            val src = imageElement?.attr("src") ?: ""
            val dataSrc = imageElement?.attr("data-src") ?: ""
            val image = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

            newAnimeSearchResponse(title, fixUrl(href)) {
                this.type = TvType.Anime
                this.posterUrl = image
                addDubStatus(getDubStatus(title))
                this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("LatanimeProvider", "Cargando: $url")

        val document = app.get(url).document

        val title = document.selectFirst("div.col-lg-9 h2")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: ""

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.col-lg-3 img")?.attr("src")
            ?: ""

        val description = document.selectFirst("p.my-2.opacity-75")?.text()?.trim()
            ?: document.selectFirst("div.col-lg-9 p:contains(Los cazadores)")?.text()?.trim()
            ?: ""

        val dateText = document.selectFirst("span.span-tiempo")?.text() ?: ""
        val year = Regex("\\b(\\d{4})\\b").find(dateText)?.value?.toIntOrNull()

        val totalEpisodesText = document.selectFirst("p:contains(Episodios:)")?.text() ?: ""
        val totalEpisodes = Regex("\\d+").find(totalEpisodesText)?.value?.toIntOrNull()

        val tags = document.select("a[href*=/genero/] div.btn").map {
            it.text().trim()
        }.filter { it.isNotBlank() }

        val status = when {
            document.html().contains("En emisión", ignoreCase = true) -> ShowStatus.Ongoing
            document.html().contains("Finalizado", ignoreCase = true) -> ShowStatus.Completed
            totalEpisodes != null && totalEpisodes > 0 -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        Log.d("LatanimeProvider", "Título: $title")
        Log.d("LatanimeProvider", "Descripción: ${description.take(100)}...")
        Log.d("LatanimeProvider", "Año: $year")
        Log.d("LatanimeProvider", "Tags: $tags")
        Log.d("LatanimeProvider", "Total episodios: $totalEpisodes")

        // Extraer episodios
        val episodeElements = document.select("div[style*='max-height: 400px'] a[href*=episodio]")
        Log.d("LatanimeProvider", "Elementos de episodios encontrados: ${episodeElements.size}")

        val episodes = episodeElements.mapIndexedNotNull { index, element ->
            try {
                val epUrl = element.attr("href")
                val capLayout = element.selectFirst("div.cap-layout")
                val epTitle = capLayout?.text()?.trim() ?: ""

                val epNum = Regex("(?:episodio|capitulo)[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epUrl + epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val imgElement = element.selectFirst("img")
                val epPosterRaw = imgElement?.attr("data-src")?.ifBlank {
                    imgElement.attr("src")
                }

                val epPoster = when {
                    epPosterRaw.isNullOrBlank() -> null
                    epPosterRaw.startsWith("http") -> epPosterRaw
                    epPosterRaw.startsWith("//") -> "https:$epPosterRaw"
                    epPosterRaw.startsWith("/") -> "https://latanime.org$epPosterRaw"
                    else -> "https://latanime.org/$epPosterRaw"
                }

                if (epUrl.isNotBlank() && epNum != null) {
                    val fullUrl = if (epUrl.startsWith("http")) epUrl else "https://latanime.org$epUrl"

                    newEpisode(fullUrl) {
                        this.name = epTitle.ifBlank { "Episodio $epNum" }
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }
                } else null
            } catch (e: Exception) {
                Log.e("LatanimeProvider", "Error procesando episodio $index: ${e.message}")
                null
            }
        }

        Log.d("LatanimeProvider", "Total episodios procesados: ${episodes.size}")

        val tvType = when {
            url.contains("/pelicula/") -> TvType.Movie
            tags.contains("OVA") -> TvType.OVA
            else -> TvType.Anime
        }

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(
                name = title,
                url = url,
                type = tvType,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = tvType,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.showStatus = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LatanimePlugin", "loadLinks called with data: $data")

        var foundLinks = false
        val doc = appGetChildMainUrl(data).document
        try {
            doc.select("ul.cap_repro li#play-video").forEach { playerElement ->
                Log.d("LatanimePlugin", "Found player element: ${playerElement.outerHtml()}")

                val encodedUrl = playerElement.selectFirst("a.play-video")?.attr("data-player")
                Log.d("LatanimePlugin", "Encoded URL found: $encodedUrl")

                if (encodedUrl.isNullOrEmpty()) {
                    Log.w("LatanimePlugin", "Encoded URL is null or empty for $data. Could not find player data-player attribute.")
                    return@forEach
                }

                val urlDecoded = base64Decode(encodedUrl)
                Log.d("LatanimePlugin", "Decoded URL (Base64): $urlDecoded")

                val url = urlDecoded.replace("https://monoschinos2.com/reproductor?url=", "")
                    .replace("https://sblona.com", "https://watchsb.com")
                Log.d("LatanimePlugin", "Final URL for Extractor: $url")

                if (url.isNotEmpty()) {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } else {
                    Log.w("LatanimePlugin", "WARN: URL final para el extractor está vacía después de decodificar y reemplazar.")
                }
            }
        } catch (e: Exception) {
            Log.e("LatanimePlugin", "Error in loadLinks for data '$data': ${e.message}", e)
        }

        Log.d("LatanimePlugin", "loadLinks finished for data: $data with foundLinks: $foundLinks")
        return foundLinks
    }
}