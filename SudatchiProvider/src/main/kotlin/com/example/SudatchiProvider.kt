package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder

class SudatchiProvider : MainAPI() {
    override var mainUrl = "https://sudatchi.com"
    private val apiUrl = "https://sudatchi.com/api"
    override var name = "Sudatchi"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val TAG = "Sudatchi"

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val apiHeaders = mapOf(
        "Accept" to "*/*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "Accept-Language" to "es-ES,es;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "$apiUrl/series?page=1&sort=POPULARITY_DESC" to "Más Populares",
        "$apiUrl/series?page=1&sort=TRENDING_DESC" to "En Tendencia"
    )

    private fun getImageUrl(node: com.fasterxml.jackson.databind.JsonNode?): String? {
        if (node == null || node.isNull) return null
        return if (node.isObject) {
            node.get("extraLarge")?.asText() ?: node.get("large")?.asText()
        } else {
            node.asText()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val response = app.get(request.data, headers = apiHeaders).text
            val homeItems = mutableListOf<SearchResponse>()

            val data: SeriesDto = mapper.readValue(response)
            data.results?.forEach { anime ->
                val title = anime.title?.english ?: anime.title?.romaji ?: "Unknown"
                homeItems.add(newAnimeSearchResponse(title, "$mainUrl/anime/${anime.id}") {
                    this.posterUrl = getImageUrl(anime.coverImage)
                })
            }

            newHomePageResponse(listOf(HomePageList(request.name, homeItems)), true)
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en getMainPage: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/")
        return try {
            val response = app.get("$apiUrl/anime/$id", headers = apiHeaders).text
            val data: AnimeDetailDto = mapper.readValue(response)
            val title = data.title?.english ?: data.title?.romaji ?: "Unknown"

            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = data.coverImage?.extraLarge ?: data.bannerImage
                this.backgroundPosterUrl = data.bannerImage
                this.year = data.year
                this.plot = data.description
                this.tags = data.genres

                val statusEnum = when (data.status?.uppercase()) {
                    "RELEASING" -> ShowStatus.Ongoing
                    "FINISHED" -> ShowStatus.Completed
                    else -> ShowStatus.Ongoing
                }
                this.showStatus = statusEnum

                val episodesList = data.episodes?.map { ep ->
                    val subsToSerialize = ep.subtitles ?: emptyList<SubtitleDto>()
                    val subsJson = mapper.writeValueAsString(subsToSerialize)
                    val encodedSubs = URLEncoder.encode(subsJson, "UTF-8")
                    val videoData = "$apiUrl/streams?episodeId=${ep.id ?: 0}&subs=$encodedSubs"

                    newEpisode(videoData) {
                        this.name = ep.title
                        this.episode = ep.number
                        this.posterUrl = if (ep.imgUrl.isNullOrBlank()) null else "$mainUrl${ep.imgUrl}"
                    }
                } ?: emptyList()

                addEpisodes(DubStatus.Subbed, episodesList)

                this.recommendations = data.recommendations?.map { rec ->
                    newAnimeSearchResponse(
                        rec.title?.english ?: rec.title?.romaji ?: "Unknown",
                        "$mainUrl/anime/${rec.id}"
                    ) {
                        this.posterUrl = getImageUrl(rec.coverImage)
                    }
                }

                data.nextAiring?.let {
                    Log.d(TAG, "Logs: Próximo episodio ${it.ep} en timestamp: ${it.at}")
                }

                Log.d(TAG, "Logs: Load finalizado para $title - Status: $statusEnum - Recs: ${this.recommendations?.size ?: 0}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error crítico en load: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Logs: Data recibida en loadLinks: $data")

        val encodedSubs = data.substringAfter("&subs=", "")
        if (encodedSubs.isNotEmpty()) {
            try {
                val decodedSubs = java.net.URLDecoder.decode(encodedSubs, "UTF-8")
                val subs: List<SubtitleDto> = mapper.readValue(decodedSubs)

                subs.forEach { sub ->
                    val cleanHash = sub.url.removePrefix("/ipfs/").removePrefix("/")
                    val subUrl = "$mainUrl/api/proxy/$cleanHash"

                    val label = sub.subtitlesName?.name ?: sub.subtitlesName?.language ?: "Sub"

                    Log.d(TAG, "Logs: Cargando subtítulo: $label en $subUrl")
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logs: Error parseando subs en loadLinks: ${e.message}")
            }
        }

        val cleanVideoUrl = data.substringBefore("&subs=")
        callback.invoke(
            newExtractorLink(this.name, "Sudatchi", cleanVideoUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = apiHeaders
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get("$apiUrl/series?search=$query&page=1", headers = apiHeaders).text
            val data: SeriesDto = mapper.readValue(response)
            data.results?.map { anime ->
                newAnimeSearchResponse(anime.title?.english ?: anime.title?.romaji ?: "Unknown", "$mainUrl/anime/${anime.id}") {
                    this.posterUrl = getImageUrl(anime.coverImage)
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesDto(val results: List<AnimeDto>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDto(
        val id: String? = null,
        val title: TitleDto? = null,
        val coverImage: com.fasterxml.jackson.databind.JsonNode? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NextAiringDto(
        val ep: Int? = null,
        val at: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TitleDto(val romaji: String? = null, val english: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetailDto(
        val title: TitleDto? = null,
        val description: String? = null,
        val coverImage: CoverDto? = null,
        val bannerImage: String? = null,
        val genres: List<String>? = null,
        val year: Int? = null,
        val status: String? = null,
        val episodes: List<EpisodeDto>? = null,
        val recommendations: List<AnimeDto>? = null,
        val nextAiring: NextAiringDto? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeDto(
        val id: Int? = null,
        val number: Int? = null,
        val title: String? = null,
        val imgUrl: String? = null,
        @JsonProperty("Subtitles") val subtitles: List<SubtitleDto>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleDto(
        val url: String,
        @JsonProperty("SubtitlesName") val subtitlesName: SubtitlesNameDto? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitlesNameDto(
        val name: String? = null,
        val language: String? = null,
        val id: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverDto(val extraLarge: String? = null)
}