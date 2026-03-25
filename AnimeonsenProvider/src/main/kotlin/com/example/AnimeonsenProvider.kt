package com.example

import android.util.Log
import androidx.coordinatorlayout.R.id.async
import androidx.core.R.id.async
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
        if (accessToken != null) {
            Log.d(TAG, "Logs: Usando Token almacenado")
            return accessToken
        }

        return try {
            Log.d(TAG, "Logs: Solicitando nuevo token a auth.animeonsen.xyz...")

            val bodyJson = """{
            "client_id":"f296be26-28b5-4358-b5a1-6259575e23b7",
            "client_secret":"349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235",
            "grant_type":"client_credentials"
        }""".trimIndent()

            val response = app.post(
                "https://auth.animeonsen.xyz/oauth/token",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to userAgent
                ),
                requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                timeout = 30
            )

            Log.d(TAG, "Logs: Respuesta Auth Code: ${response.code}")

            if (response.code == 200 && response.text.startsWith("{")) {
                val json = AppUtils.parseJson<TokenResponse>(response.text)
                accessToken = json.access_token
                Log.d(TAG, "Logs: Token obtenido correctamente")
                accessToken
            } else {
                Log.e(TAG, "Logs Error: El servidor no envió JSON o dio error. Code: ${response.code}")
                if (response.text.contains("cloudflare")) {
                    Log.e(TAG, "Logs: Detectado bloqueo o timeout de Cloudflare (Error 522/524)")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Token Crítico: ${e.localizedMessage}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val token = getAuthToken()
        val pages = java.util.concurrent.CopyOnWriteArrayList<HomePageList>()

        val requestHeaders = mapOf(
            "Authorization" to "Bearer $token",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Origin" to "https://www.animeonsen.xyz",
            "Referer" to "https://www.animeonsen.xyz/",
            "Accept" to "application/json, text/plain, */*"
        )

        try {
            val response = app.get(
                "$apiUrl/content/index?start=${(page - 1) * 30}&limit=30",
                headers = requestHeaders,
                timeout = 30
            ).text

            val latestRes = AppUtils.parseJson<AnimeListResponse>(response)
            val items = latestRes.content ?: latestRes.result

            if (!items.isNullOrEmpty()) {
                pages.add(HomePageList("Recién agregados", items.map { it.toSearchResponse() }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Principal: ${e.message}")
        }

        if (page == 1) {
            homeGenres.amap { (name, slug) ->
                try {
                    val genreUrl = "$apiUrl/content/index/genre/$slug?start=0&limit=20"
                    val genreRes = app.get(genreUrl, headers = requestHeaders, timeout = 20).parsed<AnimeListResponse>()
                    val items = genreRes.result ?: genreRes.content

                    if (!items.isNullOrEmpty()) {
                        pages.add(HomePageList(name, items.map { it.toSearchResponse() }))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Logs Error Género $name: ${e.message}")
                }
            }
        }

        return newHomePageResponse(pages.toList(), pages.isNotEmpty())
    }

    private fun AnimeListItem.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(this.getTitle(), this.content_id) {
            this.posterUrl = "https://api.animeonsen.xyz/v4/image/210x300/${this@toSearchResponse.content_id}"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val token = getAuthToken() ?: return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            val response = app.get("$apiUrl/search/$encodedQuery", headers = mapOf(
                "Authorization" to "Bearer $token"))
            if (response.code == 404) return emptyList()
            val res = AppUtils.parseJson<SearchResponseDto>(response.text)
            res.result?.map { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Logs: Obteniendo token...")
        val token = getAuthToken()

        val contentId = if (url.startsWith("http")) url.split("/").last() else url
        Log.d(TAG, "Logs: === Iniciando Load para ID: $contentId ===")

        val requestHeaders = mapOf(
            "Authorization" to "Bearer $token",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Origin" to "https://www.animeonsen.xyz",
            "Referer" to "https://www.animeonsen.xyz/"
        )

        val details = try {
            val res = app.get("$apiUrl/content/$contentId/extensive", headers = requestHeaders, timeout = 30)
            Log.d(TAG, "Logs: Detalles obtenidos. Code: ${res.code}")
            AppUtils.parseJson<AnimeDetailsDto>(res.text)
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error Crítico en detalles: ${e.message}")
            throw Exception("Error en detalles: ${e.message}")
        }

        val displayTitle = details.getTitle()
        val posterImg = "https://api.animeonsen.xyz/v4/image/210x300/$contentId"

        val episodesList = try {
            Log.d(TAG, "Logs: Pidiendo episodios para $contentId")
            val epResponse = app.get("$apiUrl/content/$contentId/episodes", headers = requestHeaders, timeout = 25).text
            val epMap = AppUtils.parseJson<Map<String, EpisodeDto>>(epResponse)

            epMap.map { (epKey, item) ->
                newEpisode("$contentId/video/$epKey") {
                    this.name = item.contentTitle_episode_en ?: item.contentTitle_episode_jp ?: "Episode $epKey"
                    this.episode = epKey.toIntOrNull()
                    this.posterUrl = posterImg
                }
            }.sortedBy { it.episode }
        } catch (e: Exception) {
            Log.e(TAG, "Logs Error en episodios: ${e.message}")
            emptyList<Episode>()
        }

        return newAnimeLoadResponse(displayTitle, url, TvType.Anime) {
            this.posterUrl = posterImg
            this.plot = details.mal_data?.synopsis
            this.score = Score.from10(details.mal_data?.mean_score)
            this.year = details.content_year

            val seasons = mutableListOf<SearchResponse>()

            details.previous_season?.takeIf { it.isNotBlank() && it != "null" }?.let { prevId ->
                seasons.add(newAnimeSearchResponse("⏪ Temporada Anterior", prevId, TvType.Anime) {
                    this.posterUrl = "https://api.animeonsen.xyz/v4/image/210x300/$prevId"
                })
            }

            details.next_season?.takeIf { it.isNotBlank() && it != "null" }?.let { nextId ->
                seasons.add(newAnimeSearchResponse("⏩ Siguiente Temporada", nextId, TvType.Anime) {
                    this.posterUrl = "https://api.animeonsen.xyz/v4/image/210x300/$nextId"
                })
            }
            this.recommendations = seasons

            val tagsList = mutableListOf<String>()
            details.mal_data?.rating?.let { tagsList.add(it.uppercase()) }
            details.mal_data?.genres?.forEach { genre: Genre ->
                tagsList.add(genre.name)
            }
            this.tags = tagsList

            this.showStatus = when (details.mal_data?.status) {
                "finished_airing" -> ShowStatus.Completed
                "currently_airing" -> ShowStatus.Ongoing
                else -> null
            }

            addEpisodes(DubStatus.Subbed, episodesList)
            Log.d(TAG, "Logs: === Load completo para $displayTitle ===")
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
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
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
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
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

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val token_type: String? = null,
        val expires_in: Int? = null
    )

    @Serializable
    data class SearchResponseDto(val result: List<AnimeListItem>? = null)

    @Serializable
    data class AnimeListResponse(
        val content: List<AnimeListItem>? = null,
        val result: List<AnimeListItem>? = null
    )

    @Serializable
    data class AnimeListItem(
        val content_id: String,
        val content_title: @Contextual Any? = null,
        val content_title_en: String? = null,
        val total_episodes: Int? = null
    )

    private fun AnimeListItem.getTitle(): String {
        val title = this.content_title
        return when (title) {
            is String -> title
            is List<*> -> title.firstOrNull()?.toString() ?: "Unknown"
            else -> this.content_title_en ?: "Unknown"
        }
    }

    private fun AnimeDetailsDto.getTitle(): String {
        val title = this.content_title
        return when (title) {
            is String -> title
            is List<*> -> title.firstOrNull()?.toString() ?: "Unknown"
            else -> this.content_title_en ?: "Unknown"
        }
    }

    @Serializable
    data class EpisodeDto(
        val contentTitle_episode_en: String? = null,
        val contentTitle_episode_jp: String? = null
    )

    @Serializable
    data class AnimeDetailsDto(
        val content_id: String,
        val content_title: @Contextual Any? = null,
        val content_title_en: String? = null,
        val content_year: Int? = null,
        val previous_season: String? = null,
        val next_season: String? = null,
        val mal_data: MalDataDto? = null
    )

    @Serializable
    data class MalDataDto(
        val synopsis: String? = null,
        val status: String? = null,
        val mean_score: Double? = null,
        val rating: String? = null,
        val genres: List<Genre>? = null
    )

    @Serializable
    data class Genre(val name: String)

    @Serializable
    data class VideoDataDto(
        val metadata: MetaDataDto,
        val uri: StreamDataDto
    )

    @Serializable
    data class MetaDataDto(
        val content_title: @Contextual Any? = null,
        val subtitles: Map<String, String>? = null
    )

    @Serializable
    data class StreamDataDto(
        val stream: String,
        val subtitles: Map<String, String>? = null
    )
}