package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class AnimeParadiseProvider : MainAPI() {
    override var mainUrl = "https://www.animeparadise.moe"
    private val apiUrl = "https://api.animeparadise.moe"
    override var name = "AnimeParadise"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val TAG = "AnimeParadise"

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val apiHeaders = mapOf(
        "accept" to "*/*",
        "accept-language" to "es-ES,es;q=0.9,en;q=0.8",
        "origin" to mainUrl,
        "referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Brave\";v=\"146\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-site",
        "sec-gpc" to "1",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(TAG, "Logs: --- INICIANDO MAIN PAGE ---")
        return try {
            val recentRes = app.get("$apiUrl/ep/recently-added?v=1", headers = apiHeaders)
            val popularRes = app.get("$apiUrl/search?sort=POPULARITY&limit=15&v=1", headers = apiHeaders)

            val recentData = parseNextJsJson<AnimeListResponse>(recentRes.text)
            val popularData = parseNextJsJson<AnimeListResponse>(popularRes.text)

            val homePages = mutableListOf<HomePageList>()
            popularData?.data?.let { list ->
                homePages.add(HomePageList("Populares", list.map { it.toSearchResponse() }))
            }

            newHomePageResponse(homePages, false)
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en getMainPage: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Logs: --- BUSCANDO (API): $query ---")
        return try {
            val searchUrl = "$apiUrl/search?q=${query.encodeUri()}&limit=25"
            val response = app.get(searchUrl, headers = apiHeaders)

            val list = try {
                mapper.readValue<AnimeListResponse>(response.text).data
            } catch (e: Exception) {
                parseNextJsJson<AnimeListResponse>(response.text)?.data
            }

            list?.map { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en search API: ${e.message}")
            emptyList()
        }
    }

    private inline fun <reified T> parseNextJsJson(input: String): T? {
        return try {
            val startIndex = input.indexOf("{\"success\":true")
            if (startIndex == -1) return null
            var braceCount = 0
            var endIndex = -1
            for (i in startIndex until input.length) {
                if (input[i] == '{') braceCount++
                else if (input[i] == '}') braceCount--
                if (braceCount == 0) { endIndex = i + 1; break }
            }
            mapper.readValue<T>(input.substring(startIndex, endIndex))
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error parseando JSON: ${e.message}")
            null
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "$mainUrl$url" else "$mainUrl/$url"
    }

    private fun getEnglishTitle(title: String?, altTitle: AlternativeTitle?): String {
        return altTitle?.english ?: title ?: "Unknown"
    }

    private fun AnimeObject.toSearchResponse(): SearchResponse {
        val rawImage = this.image ?: this.posterImage?.large ?: this.posterImage?.original
        val rawLink = this.link ?: ""
        val cleanSlug = rawLink.trim('/').split('/').lastOrNull() ?: this.id ?: ""
        val displayTitle = getEnglishTitle(this.title, this.alternativeTitle)
        return newAnimeSearchResponse(
            displayTitle,
            "$mainUrl/anime/$cleanSlug",
            TvType.Anime
        ) {
            this.posterUrl = fixImageUrl(rawImage)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        if (slug.isBlank() || slug == "null") return null

        return try {
            val detailRes = app.get("$apiUrl/anime/$slug?v=1", headers = apiHeaders).text
            val animeData: AnimeDetailResponse = mapper.readValue(detailRes)
            val data = animeData.data ?: throw Exception("Data de anime nula")
            val internalId = data._id ?: data.id ?: throw Exception("ID interno no encontrado")

            val epData: EpisodeListResponse = mapper.readValue(
                app.get("$apiUrl/anime/$internalId/episode", headers = apiHeaders).text
            )
            val simData: SimilarResponse = mapper.readValue(
                app.get("$apiUrl/anime/similar?animeId=$internalId", headers = apiHeaders).text
            )

            val episodes = epData.data?.mapNotNull { ep ->
                val uid = ep.uid ?: return@mapNotNull null
                newEpisode("$uid|$internalId") {
                    this.episode = ep.number?.toIntOrNull() ?: 0
                    this.name = ep.title ?: "Episodio ${ep.number}"
                    this.posterUrl = ep.image
                }
            }?.sortedBy { it.episode } ?: emptyList()

            val recommendations = simData.data?.map { sim ->
                newAnimeSearchResponse(getEnglishTitle(sim.title, sim.alternativeTitle), "$mainUrl/anime/${sim.link}") {
                    this.posterUrl = sim.posterImage?.large
                }
            }

            newAnimeLoadResponse(getEnglishTitle(data.title, data.alternativeTitle), url, TvType.Anime) {
                this.posterUrl = data.posterImage?.original ?: data.posterImage?.large
                this.plot = data.synopsis ?: data.synopsys
                this.tags = if (data.tags?.isNotEmpty() == true) data.tags else data.genres
                this.year = data.animeSeason?.year
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val uid = parts.getOrNull(0)?.substringAfterLast("/") ?: return false
        val origin = parts.getOrNull(1)?.substringAfterLast("/") ?: return false

        return try {
            Log.d(TAG, "Logs: --- CARGANDO LINKS (API): uid=$uid origin=$origin ---")

            val epRes = app.get("$apiUrl/ep/$uid?origin=$origin&v=1", headers = apiHeaders)
            val epData = mapper.readValue<EpisodeDetailResponse>(epRes.text)
            val episode = epData.data?.episode ?: return false

            val streamLink = episode.streamLink
            if (streamLink != null) {
                val proxyUrl = "https://stream.animeparadise.moe/m3u8?url=${streamLink.encodeUri()}"

                callback.invoke(
                    newExtractorLink(
                        this.name, "AnimeParadise",
                        proxyUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = apiHeaders
                    }
                )
            }

            episode.subData?.forEach { sub ->
                val label = sub.label ?: "Unknown"
                val src = sub.src ?: return@forEach
                val type = sub.type ?: "vtt"
                try {
                    when (type.lowercase()) {
                        "vtt" -> subtitleCallback.invoke(newSubtitleFile(label, src))
                        "ass" -> subtitleCallback.invoke(newSubtitleFile(label, "$apiUrl/stream/file/$src"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Logs: Error sub $label: ${e.message}")
                }
            }

            streamLink != null
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en loadLinks: ${e.message}")
            false
        }
    }
}

data class AnimeObject(
    @JsonProperty("_id") val id: String? = null,
    val title: String? = null,
    val link: String? = null,
    val status: String? = null,
    @JsonAlias("synopsys", "synopsis") val synopsis: String? = null,
    val genres: List<String>? = null,
    val posterImage: ImageInfo? = null,
    val image: String? = null,
    val alternativeTitle: AlternativeTitle? = null
)
data class ImageInfo(val original: String? = null, val large: String? = null)
data class AlternativeTitle(val english: String? = null, val native: String? = null, val romaji: String? = null)
data class AnimeListResponse(val data: List<AnimeObject>? = null)
data class AnimeDetailResponse(val success: Boolean?, val data: AnimeData?)
data class AnimeData(
    val _id: String?,
    @JsonProperty("id") val id: String?,
    val title: String?,
    val link: String?,
    val synopsis: String?,
    val synopsys: String?,
    val genres: List<String>?,
    val tags: List<String>?,
    val posterImage: PosterImages?,
    val animeSeason: SeasonInfo?,
    val alternativeTitle: AlternativeTitle? = null
)
data class SeasonInfo(val season: String?, val year: Int?)
data class PosterImages(val large: String?, val original: String?)
data class SimilarResponse(val success: Boolean?, val data: List<SimilarAnime>?)
data class SimilarAnime(val title: String?, val link: String?, val posterImage: PosterImages?, val alternativeTitle: AlternativeTitle? = null)
data class EpisodeListResponse(val success: Boolean?, val data: List<EpisodeData>?)
data class EpisodeData(
    @JsonProperty("_id") val id: String?,
    val uid: String?,
    val title: String?,
    val number: String?,
    val image: String?
)
data class EpisodeDetailResponse(val success: Boolean?, val error: Any?, val data: EpisodeDetailData?)
data class EpisodeDetailData(
    val episode: EpisodeStreamData?,
    val episodeList: List<EpisodeData>?
)
data class EpisodeStreamData(
    @JsonProperty("_id") val id: String?,
    val image: String?,
    val title: String?,
    val uid: String?,
    val number: String?,
    val origin: String?,
    val summary: String?,
    val streamLink: String?,
    val subData: List<SubData>?,
    val thumbnail: String?,
    val skipData: SkipData?
)
data class SkipData(val intro: TimeRange?, val outro: TimeRange?)
data class TimeRange(val start: Int?, val end: Int?)
data class SubData(val src: String?, val label: String?, val type: String?)
