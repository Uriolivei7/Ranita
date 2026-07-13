package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class Atalayas21Provider : MainAPI() {
    companion object {
        private const val BASE_URL = "https://atalayas21.com"
    }

    override var mainUrl = BASE_URL
    override var name = "Atalayas21"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries, TvType.Cartoon
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponse<T>(
        @JsonProperty("success") var success: Boolean = false,
        @JsonProperty("message") var message: String? = null,
        @JsonProperty("data") var data: T? = null,
        @JsonProperty("seasons") var seasons: List<Int>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SerieItem(
        @JsonProperty("id") var id: Long? = null,
        @JsonProperty("titulo") var titulo: String? = null,
        @JsonProperty("nombre_categoria") var nombreCategoria: String? = null,
        @JsonProperty("thumb") var thumb: String? = null,
        @JsonProperty("imagen_url") var imagenUrl: String? = null,
        @JsonProperty("portada_url") var portadaUrl: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("temporadas") var temporadas: Int? = null,
        @JsonProperty("total") var total: Int? = null,
        @JsonProperty("episodios") var episodios: Int? = null,
        @JsonProperty("descripcion") var descripcion: String? = null,
        @JsonProperty("status") var status: String? = null,
        @JsonProperty("finalizada") var finalizada: Boolean? = null,
        @JsonProperty("año_estreno") var añoEstreno: Int? = null,
        @JsonProperty("año") var año: Int? = null,
        @JsonProperty("popularidad") var popularidad: Double? = null,
        @JsonProperty("url") var url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("id") var id: Long? = null,
        @JsonProperty("titulo") var titulo: String? = null,
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("numero_episodio") var numeroEpisodio: Int? = null,
        @JsonProperty("numero") var numero: Int? = null,
        @JsonProperty("temporada") var temporada: Int? = null,
        @JsonProperty("serie_id") var serieId: Long? = null,
        @JsonProperty("duracion") var duracion: String? = null,
        @JsonProperty("imagen_url") var imagenUrl: String? = null,
        @JsonProperty("descripcion") var descripcion: String? = null,
        @JsonProperty("video_url") var videoUrl: String? = null,
        @JsonProperty("link_video") var linkVideo: String? = null,
        @JsonProperty("embed_url") var embedUrl: String? = null,
        @JsonProperty("url_video") var urlVideo: String? = null,
        @JsonProperty("vistas") var vistas: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchData(
        @JsonProperty("series") var series: List<SearchItem>? = null,
        @JsonProperty("episodes") var episodes: List<SearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchItem(
        @JsonProperty("id") var id: Long? = null,
        @JsonProperty("titulo") var titulo: String? = null,
        @JsonProperty("imagen_url") var imagenUrl: String? = null,
        @JsonProperty("tipo") var tipo: String? = null,
        @JsonProperty("url") var url: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("episodios") var episodios: Int? = null,
        @JsonProperty("descripcion") var descripcion: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeLinkData(
        @JsonProperty("url") var url: String? = null,
        @JsonProperty("type") var type: String? = null
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$BASE_URL/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val popularJson = app.get("$BASE_URL/api/series.php?popular=true&limit=50&lang=es", headers = apiHeaders).text
        val popularResp = parseJson<ApiResponse<List<SerieItem>>>(popularJson)
        val popularItems = popularResp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        val recentJson = app.get("$BASE_URL/api/series.php?recent=true&limit=50&lang=es", headers = apiHeaders).text
        val recentResp = parseJson<ApiResponse<List<SerieItem>>>(recentJson)
        val recentItems = recentResp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        return newHomePageResponse(
            listOf(
                HomePageList("Populares", popularItems),
                HomePageList("Recientes", recentItems)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val json = app.get("$BASE_URL/api/search-advanced.php?query=${query.encodeURI()}&lang=es", headers = apiHeaders).text
        val resp = parseJson<ApiResponse<SearchData>>(json)
        val series = resp.data?.series?.filter { it.tipo == "serie" }?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return series
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("?id=").substringBefore("&")
        val json = app.get("$BASE_URL/api/series.php?id=$id&lang=es", headers = apiHeaders).text
        val resp = parseJson<ApiResponse<List<SerieItem>>>(json)
        val serie = resp.data?.firstOrNull() ?: throw ErrorLoadingException("Serie no encontrada")

        val epJson = app.get("$BASE_URL/api/episodios.php?serie_id=$id&limit=500", headers = apiHeaders).text
        val epResp = parseJson<ApiResponse<List<EpisodeItem>>>(epJson)
        val allEps = epResp.data ?: emptyList()
        val seasons = allEps.mapNotNull { it.temporada }.distinct().sorted()
            .ifEmpty { epResp.seasons?.ifEmpty { listOf(1) } ?: listOf(1) }

        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val seasonEps = allEps.filter { (it.temporada ?: 1) == season }
                .sortedBy { it.numeroEpisodio ?: it.numero ?: 0 }
            for (ep in seasonEps) {
                val epNum = ep.numeroEpisodio ?: ep.numero ?: 0
                val epTitle = cleanEpisodeTitle(ep.titulo ?: ep.title ?: "")
                episodes.add(
                    newEpisode("$BASE_URL/api/episodios.php?id=${ep.id}") {
                        this.name = epTitle.ifEmpty { "Episodio $epNum" }
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = ep.imagenUrl?.let { fixUrl(it) }
                    }
                )
            }
        }

        val poster = serie.imagenUrl ?: serie.thumb
        val showStatus = when {
            serie.finalizada == true -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        return newTvSeriesLoadResponse(
            serie.titulo ?: "Serie",
            url,
            TvType.AsianDrama,
            episodes
        ) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = serie.descripcion
            this.year = serie.añoEstreno ?: serie.año
            this.showStatus = showStatus
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val apiId = data.substringAfter("?id=").trim()

        val epJson = app.get("$BASE_URL/api/episodios.php?id=$apiId", headers = apiHeaders).text
        val epResp = parseJson<ApiResponse<List<EpisodeItem>>>(epJson)
        val ep = epResp.data?.firstOrNull()

        val videoUrl = ep?.videoUrl ?: ep?.linkVideo ?: ep?.urlVideo ?: ep?.embedUrl

        if (videoUrl != null) {
            val cleanUrl = videoUrl.replace("\\/", "/")
            found = handleVideoUrl(cleanUrl, data, subtitleCallback, callback) || found
            if (found) return true
        }

        try {
            val linkJson = app.get("$BASE_URL/api/get-episode-link.php?episode_id=$apiId", headers = apiHeaders).text
            val linkResp = parseJson<ApiResponse<EpisodeLinkData>>(linkJson)
            val linkUrl = linkResp.data?.url
            if (linkUrl != null) {
                val cleanUrl = linkUrl.replace("\\/", "/")
                found = handleVideoUrl(cleanUrl, data, subtitleCallback, callback) || found
            }
        } catch (e: Exception) {
            // ignore
        }

        return found
    }

    private fun cleanEpisodeTitle(raw: String): String {
        val parts = raw.split(" - ")
        val last = if (parts.size > 1) parts.last().trim() else raw.trim()
        if (last.isEmpty() || last.matches(Regex("""\d+x\d+"""))) return ""
        return last
    }

    private suspend fun handleVideoUrl(
        url: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.endsWith(".m3u8") || url.endsWith(".mp4") || url.endsWith(".mpd") -> {
                callback.invoke(
                    newExtractorLink(name, name, url) {
                        this.referer = "$BASE_URL/"
                        this.quality = getQualityFromName("720p")
                    }
                )
                true
            }
            url.contains("ok.ru") || url.contains("vk.com") || url.contains("youtube.com") || url.contains("youtu.be") || url.contains("vimeo.com") || url.contains("dailymotion.com") -> {
                loadExtractor(url, data, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
    }

    private fun SerieItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val poster = this.imagenUrl ?: this.thumb
        return newMovieSearchResponse(
            this.titulo ?: "Sin título",
            "$BASE_URL/api/series.php?id=$id&lang=es",
            TvType.AsianDrama
        ) {
            this.posterUrl = when {
                poster?.startsWith("http") == true -> fixUrl(poster)
                poster != null -> "$BASE_URL/$poster"
                else -> null
            }
            this.year = this@toSearchResponse.añoEstreno
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        return newMovieSearchResponse(
            this.titulo ?: "Sin título",
            "$BASE_URL/api/series.php?id=$id&lang=es",
            TvType.AsianDrama
        ) {
            this.posterUrl = this@toSearchResponse.imagenUrl?.let {
                if (it.startsWith("http")) fixUrl(it) else "$BASE_URL/$it"
            }
        }
    }

    private fun String.encodeURI(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
