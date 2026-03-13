package com.example

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.*
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import okhttp3.Request

class AnimeonsenProvider : MainAPI() {
    override var mainUrl = "https://animeonsen.xyz"
    override var name = "AnimeOnsen"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val apiUrl = "https://api.animeonsen.xyz/v4"
    private var accessToken: String? = null
    private val userAgent = "Aniyomi/App (mobile)"
    private val TAG = "AnimeOnsen"

    private val homeGenres = listOf(
        "Acción" to "action",
        "Aventura" to "adventure",
        "Comedia" to "comedy",
        "Shounen" to "shounen",
        "Recuentos de la vida" to "slice-of-life",
        "Super Poderes" to "super-power",
    )

    private suspend fun getAuthToken(): String? {
        if (accessToken != null) return accessToken
        return try {
            val bodyString = """{"client_id":"f296be26-28b5-4358-b5a1-6259575e23b7","client_secret":"349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235","grant_type":"client_credentials"}"""

            val response = app.post(
                "https://auth.animeonsen.xyz/oauth/token",
                headers = mapOf("User-Agent" to userAgent),
                data = mapOf(
                    "client_id" to "f296be26-28b5-4358-b5a1-6259575e23b7",
                    "client_secret" to "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235",
                    "grant_type" to "client_credentials"
                )
            ).text

            val json = AppUtils.parseJson<Map<String, String>>(response)
            accessToken = json["access_token"]
            accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Token: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val token = getAuthToken()
        val pages = mutableListOf<HomePageList>()

        try {
            val response = app.get(
                "$apiUrl/content/index?start=${(page - 1) * 20}&limit=20",
                headers = mapOf("Authorization" to "Bearer $token")
            ).text

            val latestRes = AppUtils.parseJson<AnimeListResponse>(response)
            val items = latestRes.content ?: latestRes.result

            if (!items.isNullOrEmpty()) {
                pages.add(HomePageList("Todos los Animes", items.map { it.toSearchResponse() }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error en MainPage: ${e.message}")
        }

        if (page == 1) {
            homeGenres.forEach { (name, slug) ->
                try {
                    val genreUrl = "$apiUrl/content/index/genre/$slug?start=0&limit=20"
                    val genreResponse = app.get(
                        genreUrl,
                        headers = mapOf("Authorization" to "Bearer $token")
                    ).text

                    val genreRes = AppUtils.parseJson<AnimeListResponse>(genreResponse)
                    val items = genreRes.result ?: genreRes.content

                    if (!items.isNullOrEmpty()) {
                        pages.add(HomePageList(name, items.map { it.toSearchResponse() }))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Logs Error cargando género $name")
                }
            }
        }

        return newHomePageResponse(pages, pages.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val token = getAuthToken() ?: return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            val response = app.get("$apiUrl/search/$encodedQuery", headers = mapOf("Authorization" to "Bearer $token"))
            if (response.code == 404) return emptyList()
            val res = AppUtils.parseJson<SearchResponseDto>(response.text)
            res.result?.map { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Search: ${e.message}")
            emptyList()
        }
    }

    private fun AnimeListItem.toSearchResponse(): SearchResponse {
        val title = this.content_title_en ?: this.content_title ?: "Unknown"
        return newAnimeSearchResponse(title, this.content_id) {
            this.posterUrl = "https://api.animeonsen.xyz/v4/image/210x300/${this@toSearchResponse.content_id}"
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val token = getAuthToken()
        val contentId = if (url.startsWith("http")) url.split("/").last() else url

        Log.d(TAG, "Logs: Iniciando carga de Anime ID: $contentId")

        val detailsRes = app.get(
            "$apiUrl/content/$contentId/extensive",
            headers = mapOf("Authorization" to "Bearer $token")
        ).text

        val details = AppUtils.parseJson<AnimeDetailsDto>(detailsRes)
        val displayTitle = details.content_title_en ?: details.content_title ?: "Anime"

        val posterImg = "$apiUrl/image/210x300/$contentId"

        val episodesList = try {
            val epResponse = app.get("$apiUrl/content/$contentId/episodes", headers = mapOf("Authorization" to "Bearer $token")).text
            val epMap = AppUtils.parseJson<Map<String, EpisodeDto>>(epResponse)

            epMap.map { (epKey, item) ->
                newEpisode("$contentId/video/$epKey") {
                    this.name = item.contentTitle_episode_en ?: item.contentTitle_episode_jp ?: "Episode $epKey"
                    this.episode = epKey.toIntOrNull()
                    this.posterUrl = posterImg
                }
            }.sortedBy { it.episode }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error: Falló carga de episodios: ${e.message}")
            emptyList()
        }

        val combinedRecs = mutableListOf<SearchResponse>()

        details.previous_season?.takeIf { it.isNotBlank() && it != "null" }?.let { prevId ->
            combinedRecs.add(newAnimeSearchResponse("⏪ Previous Season", prevId, TvType.Anime) {
                this.posterUrl = "$apiUrl/image/210x300/$prevId"
            })
        }

        details.next_season?.takeIf { it.isNotBlank() && it != "null" }?.let { nextId ->
            combinedRecs.add(newAnimeSearchResponse("⏩ Next Season", nextId, TvType.Anime) {
                this.posterUrl = "$apiUrl/image/210x300/$nextId"
            })
        }

        try {
            val recJson = app.get("$apiUrl/content/$contentId/related", headers = mapOf("Authorization" to "Bearer $token")).text
            val recData = AppUtils.parseJson<List<AnimeListItem>>(recJson)
            recData.forEach { item ->
                combinedRecs.add(item.toSearchResponse())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en recomendados")
        }

        return newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
            this.posterUrl = posterImg
            this.plot = details.mal_data?.synopsis
            this.score = Score.from10(details.mal_data?.mean_score)

            val tagsList = mutableListOf<String>()
            details.mal_data?.rating?.let { tagsList.add(it.uppercase()) }
            details.mal_data?.genres?.forEach { genre -> tagsList.add(genre.name) }
            this.tags = tagsList

            this.year = details.content_year

            this.showStatus = when (details.mal_data?.status) {
                "finished_airing" -> ShowStatus.Completed
                "currently_airing" -> ShowStatus.Ongoing
                else -> null
            }

            this.recommendations = combinedRecs
            addEpisodes(DubStatus.Subbed, episodesList)

            Log.d(TAG, "Logs: Load completo para $displayTitle. Score: ${details.mal_data?.mean_score}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanPath = if (data.contains("animeonsen.xyz/"))
            data.substringAfter("animeonsen.xyz/") else data
        val token = getAuthToken() ?: return false

        return try {
            val client = OkHttpClient.Builder().build()

            val request = Request.Builder()
                .url("$apiUrl/content/$cleanPath")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Referer", "https://www.animeonsen.xyz/")
                .addHeader("Origin", "https://www.animeonsen.xyz")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val rawText = client.newCall(request).execute().use { response ->
                Log.d(TAG, "Logs: Code: ${response.code}, Encoding: ${response.header("content-encoding")}")
                response.body?.string() ?: return false
            }

            Log.d(TAG, "Logs: Primeros chars: ${rawText.take(50)}")

            val res = AppUtils.parseJson<VideoDataDto>(rawText)
            val videoUrl = res.uri.stream

            if (videoUrl.isNotEmpty()) {
                val cdnHeaders = mapOf(
                    "Authorization" to "Bearer $token",
                    "Referer" to "https://www.animeonsen.xyz/",
                    "Origin" to "https://www.animeonsen.xyz",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Connection" to "keep-alive"
                )

                res.uri.subtitles?.forEach { (langPrefix, subUrl) ->
                    val langName = res.metadata.subtitles?.get(langPrefix) ?: langPrefix
                    val finalSubUrl = "${subUrl}?format=vtt&t=${System.currentTimeMillis()}"
                    subtitleCallback.invoke(
                        newSubtitleFile(langName, finalSubUrl) {
                            this.headers = mapOf(
                                "Authorization" to "Bearer $token",
                                "Origin" to "https://www.animeonsen.xyz",
                                "Referer" to "https://www.animeonsen.xyz/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                    Log.d(TAG, "Logs: Subtítulo: $langName")
                }

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://www.animeonsen.xyz/"
                        this.headers = cdnHeaders
                    }
                )

                Log.d(TAG, "Logs: Stream DASH: $videoUrl")
                true
            } else {
                Log.e(TAG, "Logs: videoUrl vacío")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Crítico loadLinks: ${e.message}")
            false
        }
    }

    @Serializable data class SearchResponseDto(val result: List<AnimeListItem>? = null)
    @Serializable data class AnimeListResponse(val content: List<AnimeListItem>? = null, val result: List<AnimeListItem>? = null)
    @Serializable data class AnimeListItem(val content_id: String, val content_title: String? = null, val content_title_en: String? = null)
    @Serializable data class EpisodeDto(val contentTitle_episode_en: String? = null, val contentTitle_episode_jp: String? = null)
    @Serializable data class AnimeDetailsDto(
        val content_id: String,
        val content_title: String? = null,
        val content_title_en: String? = null,
        val content_genres: List<String>? = null,
        val content_year: Int? = null,
        val previous_season: String? = null,
        val next_season: String? = null,
        val mal_data: MalDataDto? = null
    )
    @Serializable data class MalDataDto(
        val synopsis: String? = null,
        val status: String? = null,
        val mean_score: Double? = null,
        val rating: String? = null,
        val genres: List<Genre>? = null
    )
    @Serializable data class Genre(val name: String)

    @Serializable
    data class VideoDataDto(
        val metadata: MetaDataDto,
        val uri: StreamDataDto
    )

    @Serializable
    data class MetaDataDto(
        val content_title: String? = null,
        val mal_id: Int? = null,
        val subtitles: Map<String, String>? = null,
        val episode: List<@Contextual Any>? = null
    )

    @Serializable
    data class StreamDataDto(
        val stream: String,
        val subtitles: Map<String, String>? = null
    )
}