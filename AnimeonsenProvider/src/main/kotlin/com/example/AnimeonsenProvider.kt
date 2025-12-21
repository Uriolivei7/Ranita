package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

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
        "Reparto Adulto" to "adult-cast",
        "Aventura" to "adventure",
        "Comedia" to "comedy",
        "Drama" to "drama",
        "Fantasía" to "fantasy",
        "Gore" to "gore",
        "Isekai" to "isekai",
        "Misterio" to "mystery",
        "Escolar" to "school",
        "Ciencia Ficción" to "sci-fi",
        "Shounen" to "shounen",
        "Recuentos de la vida" to "slice-of-life",
        "Super Poderes" to "super-power",
        "Supernatural" to "supernatural",
        "Romance" to "romance",
        "Suspenso" to "suspense",
    )

    private suspend fun getAuthToken(): String? {
        if (accessToken != null) return accessToken
        return try {
            val bodyString = """{"client_id":"f296be26-28b5-4358-b5a1-6259575e23b7","client_secret":"349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235","grant_type":"client_credentials"}"""
            val responseText = app.post(
                "https://auth.animeonsen.xyz/oauth/token",
                headers = mapOf("user-agent" to userAgent),
                requestBody = bodyString.toRequestBody("application/json".toMediaType())
            ).text
            val json = AppUtils.parseJson<Map<String, String>>(responseText)
            accessToken = json["access_token"]
            accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Error Token: ${e.message}")
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
            Log.e(TAG, "Error en Latest: ${e.message}")
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
                    Log.e(TAG, "Error cargando género $name: ${e.message}")
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
        } catch (e: Exception) { emptyList() }
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

        val details = app.get(
            "$apiUrl/content/$contentId/extensive",
            headers = mapOf("Authorization" to "Bearer $token")
        ).parsed<AnimeDetailsDto>()

        val displayTitle = details.content_title_en ?: details.content_title ?: "Anime"

        val epResponse = app.get("$apiUrl/content/$contentId/episodes", headers = mapOf("Authorization" to "Bearer $token"))
        val epRes = AppUtils.parseJson<Map<String, EpisodeDto>>(epResponse.text)
        val episodesList = epRes.map { (epNum, item) ->
            newEpisode("$contentId/video/$epNum") {
                this.name = item.name ?: "Episode $epNum"
                this.episode = epNum.toIntOrNull()
            }
        }.sortedBy { it.episode }

        val recommendedAnimes = try {
            val recJson = app.get("$apiUrl/content/$contentId/related", headers = mapOf("Authorization" to "Bearer $token")).text
            val recData = AppUtils.parseJson<List<AnimeListItem>>(recJson)
            recData.map { it.toSearchResponse() }
        } catch (e: Exception) {
            emptyList<SearchResponse>()
        }

        return newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
            this.posterUrl = "$apiUrl/image/210x300/$contentId"
            this.plot = details.mal_data?.synopsis
            this.tags = details.content_genres
            this.year = details.content_year
            this.showStatus = if (details.mal_data?.status == "finished_airing") ShowStatus.Completed else ShowStatus.Ongoing
            this.recommendations = recommendedAnimes
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanPath = if (data.contains("animeonsen.xyz/")) data.substringAfter("animeonsen.xyz/") else data
        val token = getAuthToken() ?: return false
        return try {
            val response = app.get(
                "$apiUrl/content/$cleanPath",
                headers = mapOf("Authorization" to "Bearer $token", "Referer" to mainUrl, "User-Agent" to userAgent)
            )
            val res = AppUtils.parseJson<VideoDataDto>(response.text)
            val videoUrl = res.uri.stream
            if (videoUrl.isNotEmpty()) {
                res.uri.subtitles.forEach { (langPrefix, subUrl) ->
                    val langName = res.metadata.subtitles[langPrefix] ?: langPrefix
                    subtitleCallback(newSubtitleFile(langName, subUrl))
                }
                callback(newExtractorLink(this.name, "AnimeOnsen", videoUrl) {
                    this.referer = mainUrl
                    this.quality = Qualities.P720.value
                })
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
    @Serializable data class SearchResponseDto(val result: List<AnimeListItem>? = null)

    @Serializable
    data class AnimeListResponse(
        val content: List<AnimeListItem>? = null,
        val result: List<AnimeListItem>? = null
    )

    @Serializable
    data class AnimeListItem(
        val content_id: String,
        val content_title: String? = null,
        val content_title_en: String? = null
    )
    @Serializable data class EpisodeDto(
        val name: String? = null
    )
    @Serializable data class VideoDataDto(val metadata: MetaDataDto, val uri: StreamDataDto)
    @Serializable data class MetaDataDto(val subtitles: Map<String, String>)
    @Serializable data class StreamDataDto(val stream: String, val subtitles: Map<String, String>)

    @Serializable data class AnimeDetailsDto(
        val content_id: String,
        val content_title: String? = null,
        val content_title_en: String? = null,
        val content_genres: List<String>? = null,
        val content_year: Int? = null,
        val mal_data: MalDataDto? = null
    )
    @Serializable data class MalDataDto(val synopsis: String? = null, val status: String? = null)
}