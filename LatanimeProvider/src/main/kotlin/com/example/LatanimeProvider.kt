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
            Pair(
                "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                "Peliculas"
            ),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        try {
            urls.map { (url, name) ->
                val doc = appGetChildMainUrl(url).document
                delay(2000)
                val home = doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").mapNotNull { article ->
                    val itemLink = article.selectFirst("a")
                    val title = itemLink?.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
                    val itemUrl = itemLink?.attr("href")

                    if (itemUrl == null) {
                        Log.w("LatanimePlugin", "WARN: itemUrl es nulo para un elemento en getMainPage.")
                        return@mapNotNull null
                    }

                    val posterElement = article.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
                    val src = posterElement?.attr("src") ?: ""
                    val dataSrc = posterElement?.attr("data-src") ?: ""
                    val poster = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

                    // Usar newAnimeSearchResponse y el lambda
                    newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                        this.posterUrl = poster
                        addDubStatus(getDubStatus(title))
                        this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
                    }
                }
                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                }
            }
        } catch (e: Exception) {
            Log.e("LatanimePlugin", "ERROR en getMainPage: ${e.message}", e)
            throw ErrorLoadingException("Error al cargar la página principal: ${e.message}")
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }
        return newHomePageResponse(items)
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

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("p.description")?.text()?.trim() ?: ""

        Log.d("LatanimeProvider", "Título: $title")

        val episodeElements = document.select("div.row a[href*=episodio]")
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

                if (index < 3) {
                    Log.d("LatanimeProvider", "Episodio $epNum:")
                    Log.d("LatanimeProvider", "  - URL: $epUrl")
                    Log.d("LatanimeProvider", "  - Título: $epTitle")
                    Log.d("LatanimeProvider", "  - Imagen raw: $epPosterRaw")
                }

                val epPoster = when {
                    epPosterRaw.isNullOrBlank() -> null
                    epPosterRaw.startsWith("http") -> epPosterRaw
                    epPosterRaw.startsWith("//") -> "https:$epPosterRaw"
                    epPosterRaw.startsWith("/") -> "https://latanime.org$epPosterRaw"
                    else -> "https://latanime.org/$epPosterRaw"
                }

                if (index < 3) {
                    Log.d("LatanimeProvider", "  - Imagen final: $epPoster")
                }

                if (epUrl.isNotBlank() && epNum != null) {
                    val fullUrl = if (epUrl.startsWith("http")) epUrl else "https://latanime.org$epUrl"

                    newEpisode(fullUrl) {
                        this.name = epTitle.ifBlank { "Episodio $epNum" }
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }
                } else {
                    Log.w("LatanimeProvider", "Episodio inválido: URL=$epUrl, Num=$epNum")
                    null
                }
            } catch (e: Exception) {
                Log.e("LatanimeProvider", "Error procesando episodio $index: ${e.message}")
                null
            }
        }

        Log.d("LatanimeProvider", "Total episodios procesados: ${episodes.size}")

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
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