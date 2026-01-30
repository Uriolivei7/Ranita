package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class LamovieProvider : MainAPI() {
    override var mainUrl = "https://la.movie"
    override var name = "La.Movie"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon)

    private val apiBase = "$mainUrl/wp-api/v1"
    private val TAG = "LaMovie"
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun fixImg(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cleanUrl = url.replace("&quot;", "").replace("\"", "").replace("'", "").trim()

        return when {
            cleanUrl.startsWith("http") -> {
                if (cleanUrl.contains("tmdb.org")) cleanUrl.replace(Regex("/t/p/w\\d+/"), "/t/p/original/")
                else cleanUrl
            }
            cleanUrl.startsWith("/") && (cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".png")) && !cleanUrl.contains("backdrops") -> {
                "https://image.tmdb.org/t/p/original$cleanUrl"
            }
            cleanUrl.startsWith("/") -> {
                "$mainUrl/wp-content/uploads${cleanUrl}"
            }
            else -> "$mainUrl/$cleanUrl"
        }
    }

    override val mainPage = mainPageOf(
        "$apiBase/listing/tvshows?postType=tvshows" to "Series",
        "$apiBase/listing/animes?postType=animes" to "Animes",
        "$apiBase/listing/movies?postType=movies" to "Películas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page&postsPerPage=12&orderBy=latest&order=DESC"
        val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        val json = try { parseJson<ApiResponse>(res) } catch (e: Exception) { null }
        val home = json?.data?.posts?.map { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(HomePageList(request.name, home), home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiBase/search?postType=any&q=${query.replace(" ", "+")}&postsPerPage=26"
        val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        val json = try { parseJson<ApiResponse>(res) } catch (e: Exception) { null }
        return json?.data?.posts?.map { it.toSearchResult() } ?: emptyList()
    }

    private fun Post.toSearchResult(): SearchResponse {
        val poster = fixImg(images?.poster ?: this.poster)
        val cleanTitle = title?.replace(Regex("\\(\\d{4}\\)$"), "")?.trim() ?: ""

        val typeStr = type ?: "movies"
        val tvType = if (typeStr == "movies") TvType.Movie else if (typeStr == "animes") TvType.Anime else TvType.TvSeries

        val path = when (tvType) {
            TvType.Movie -> "peliculas"
            TvType.Anime -> "animes"
            else -> "series"
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(cleanTitle, "$mainUrl/$path/$slug", tvType) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(cleanTitle, "$mainUrl/$path/$slug", tvType) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').split("/").last()
        val type = when {
            url.contains("/series/") -> "tvshows"
            url.contains("/animes/") -> "animes"
            else -> "movies"
        }

        val apiRes = app.get(
            "$apiBase/single/$type?slug=$slug&postType=$type",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text

        val responseObj = try {
            parseJson<SinglePostResponse>(apiRes)
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error: Falló parseo de JSON en load")
            null
        }

        val postData = responseObj?.data ?: return null
        val id = postData.id ?: return null

        val galleryImgs = postData.gallery?.split("\n")?.filter { it.isNotBlank() }
        val posterImg = fixImg(postData.images?.poster ?: postData.poster)
        val bigImg = fixImg(
            galleryImgs?.firstOrNull()?.trim() ?: postData.images?.backdrop ?: postData.backdrop
        )

        val cleanTitle = postData.title?.replace(Regex("\\(\\d{4}\\)$"), "")?.trim() ?: ""
        val realYear = postData.release_date?.split("-")?.firstOrNull()?.toIntOrNull()

        val response = if (type == "movies") {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, id.toString()) {
                this.posterUrl = posterImg
                this.backgroundPosterUrl = bigImg
                this.plot = postData.overview
                this.year = realYear
                this.duration = postData.runtime?.substringBefore(".")?.toIntOrNull()
                this.tags = listOfNotNull("Película", postData.certification)
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            try {
                val firstSeasonUrl = "$apiBase/single/episodes/list?_id=$id&season=1&page=1&postsPerPage=50"
                val firstSeasonRes = app.get(firstSeasonUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                val firstSeasonData = try { parseJson<EpisodeListResponse>(firstSeasonRes) } catch (e: Exception) { null }

                val finalSeasons = firstSeasonData?.data?.seasons ?: listOf("1")
                finalSeasons.forEach { sNumStr ->
                    val sNum = sNumStr.toIntOrNull() ?: return@forEach
                    val epRes = if (sNum == 1) firstSeasonRes else {
                        app.get(
                            "$apiBase/single/episodes/list?_id=$id&season=$sNum&page=1&postsPerPage=50",
                            headers = mapOf("User-Agent" to USER_AGENT)
                        ).text
                    }
                    val epData = try { parseJson<EpisodeListResponse>(epRes) } catch (e: Exception) { null }
                    epData?.data?.posts?.forEach { epItem ->
                        episodesList.add(newEpisode(epItem.id.toString()) {
                            this.name = epItem.title?.replace(Regex("^.*?Temporada"), "Temporada")?.trim()
                            this.season = sNum
                            this.episode = epItem.episode_number
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logs Error: Falló carga de episodios")
            }

            newTvSeriesLoadResponse(
                cleanTitle,
                url,
                if (type == "animes") TvType.Anime else TvType.TvSeries,
                episodesList
            ) {
                this.posterUrl = posterImg
                this.backgroundPosterUrl = bigImg
                this.plot = postData.overview
                this.year = realYear
                this.tags = listOfNotNull(
                    if (type == "animes") "Anime" else "Serie",
                    postData.certification
                )
            }
        }

        return response.apply {
            try {
                val relatedUrl = "$apiBase/single/related?postId=$id&page=1&tab=connections&postsPerPage=12"
                Log.d(TAG, "Logs: Consultando Recomendaciones en: $relatedUrl")

                val headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest"
                )

                val relatedRes = app.get(relatedUrl, headers = headers).text

                val relatedData = try {
                    parseJson<ApiResponse>(relatedRes)
                } catch (e: Exception) {
                    Log.e(TAG, "Logs Error: Fallo al parsear JSON de recomendaciones: ${e.message}")
                    null
                }

                val recs = relatedData?.data?.posts?.map { it.toSearchResult() } ?: emptyList()
                this.recommendations = recs

                Log.d(TAG, "Logs: Recomendaciones cargadas con éxito: ${recs.size}")

            } catch (e: Exception) {
                Log.e(TAG, "Logs Error Recs: ${e.message}")
                this.recommendations = emptyList()
            }

            Log.d(TAG, "Logs: Load Finalizado: $cleanTitle | ID: $id")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanId = data.substringAfterLast("/").trim()
        val playerUrl = "$apiBase/player?postId=$cleanId&demo=0"

        Log.d(TAG, "Logs: Cargando enlaces ID $cleanId")

        val res = try {
            app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) { return false }

        val response = try { parseJson<PlayerResponse>(res) } catch (e: Exception) { null }

        response?.data?.embeds?.forEach { embed ->
            val embedUrl = embed.url ?: return@forEach
            if (embedUrl.contains("la.movie/embed.html")) {
                try {
                    val realUrl = app.get(embedUrl, referer = "$mainUrl/").document.select("iframe").attr("src")
                    if (realUrl.isNotBlank()) loadExtractor(realUrl, embedUrl, subtitleCallback, callback)
                } catch (e: Exception) { }
            } else {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        response?.data?.downloads?.forEach { download ->
            download.url?.let { loadExtractor(it, "$mainUrl/", subtitleCallback, callback) }
        }

        return true
    }

    data class PlayerResponse(val data: PlayerData?)
    data class PlayerData(val embeds: List<EmbedItem>?, val downloads: List<EmbedItem>?)
    data class EmbedItem(val url: String?, val server: String? = null, val lang: String? = null)

    data class ApiResponse(val data: DataContainer?)
    data class DataContainer(val posts: List<Post>?)
    data class SinglePostResponse(val data: Post?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Post(
        @JsonProperty("_id") val id: Int?,
        val title: String?,
        val slug: String?,
        val type: String?,
        val overview: String?,
        val images: Images?,
        val poster: String?,
        val backdrop: String?,
        val gallery: String?,
        val rating: String?,
        val runtime: String?,
        val release_date: String?,
        val certification: String?,
        val recommendations: List<Post>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(val poster: String?, val backdrop: String? = null)

    data class EpisodeListResponse(val data: EpisodeListData?)
    data class EpisodeListData(val posts: List<EpisodePostItem>?, val seasons: List<String>?)
    data class EpisodePostItem(@JsonProperty("_id") val id: Int?, val title: String?, val episode_number: Int?)
}