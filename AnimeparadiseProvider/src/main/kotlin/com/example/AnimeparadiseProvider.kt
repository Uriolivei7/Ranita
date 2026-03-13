package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

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
        "origin" to mainUrl,
        "referer" to "$mainUrl/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(TAG, "Logs: --- INICIANDO MAIN PAGE ---")
        return try {
            val recentRes = app.get("$apiUrl/ep/recently-added?v=1", headers = apiHeaders)
            val popularRes = app.get("$apiUrl/search?sort=POPULARITY&limit=15&v=1", headers = apiHeaders)

            Log.d(TAG, "Logs: API Status - Recent: ${recentRes.code}, Popular: ${popularRes.code}")

            val recentData = parseNextJsJson<AnimeListResponse>(recentRes.text)
            val popularData = parseNextJsJson<AnimeListResponse>(popularRes.text)

            val homePages = mutableListOf<HomePageList>()
            recentData?.data?.let { list ->
                Log.d(TAG, "Logs: Agregando ${list.size} items a Recientes")
                homePages.add(HomePageList("Recién Agregados", list.map { it.toSearchResponse() }))
            }
            popularData?.data?.let { list ->
                Log.d(TAG, "Logs: Agregando ${list.size} items a Populares")
                homePages.add(HomePageList("Populares", list.map { it.toSearchResponse() }))
            }

            newHomePageResponse(homePages, false)
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en getMainPage: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Logs: --- BUSCANDO: $query ---")
        return try {
            val searchHeaders = mapOf(
                "accept" to "text/x-component",
                "next-action" to "70e67558d075165016c3b05cb0bba485a351870862",
                "content-type" to "text/plain;charset=UTF-8",
                "origin" to mainUrl,
                "referer" to "$mainUrl/search"
            )

            val requestBody = "[\"$query\",{\"genres\":[],\"year\":null,\"season\":null,\"page\":1,\"limit\":25,\"sort\":null},\"\$undefined\"]"

            val response = app.post(
                "$mainUrl/search",
                headers = searchHeaders,
                requestBody = requestBody.toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
            )

            Log.d(TAG, "Logs: Search Status: ${response.code}")
            val cleanJson = parseNextJsJson<AnimeListResponse>(response.text)
            val results = cleanJson?.data?.map { it.toSearchResponse() } ?: emptyList()
            Log.d(TAG, "Logs: Encontrados ${results.size} resultados")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en search: ${e.message}")
            emptyList()
        }
    }

    private inline fun <reified T> parseNextJsJson(input: String): T? {
        return try {
            val startIndex = input.indexOf("{\"success\":true")
            if (startIndex == -1) {
                Log.e(TAG, "Logs: No se encontró JSON de éxito en la respuesta")
                return null
            }

            var braceCount = 0
            var endIndex = -1
            for (i in startIndex until input.length) {
                if (input[i] == '{') braceCount++
                else if (input[i] == '}') braceCount--
                if (braceCount == 0) {
                    endIndex = i + 1
                    break
                }
            }
            val json = input.substring(startIndex, endIndex)
            mapper.readValue<T>(json)
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

    private fun AnimeObject.toSearchResponse(): SearchResponse {
        val rawImage = this.image ?: this.posterImage?.large ?: this.posterImage?.original
        val rawLink = this.link ?: ""
        val cleanSlug = rawLink.trim('/').split('/').lastOrNull() ?: this.id ?: ""

        return newAnimeSearchResponse(
            this.title ?: "Sin título",
            "$mainUrl/anime/$cleanSlug",
            TvType.Anime
        ) {
            this.posterUrl = fixImageUrl(rawImage)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        Log.d(TAG, "Logs: --- CARGANDO DETALLES: $slug ---")
        if (slug.isBlank() || slug == "null") return null

        return try {
            val detailRes = app.get("$apiUrl/anime/$slug?v=1", headers = apiHeaders).text
            val animeData: AnimeDetailResponse = mapper.readValue(detailRes)
            val data = animeData.data ?: throw Exception("Data de anime nula")

            val internalId = data._id ?: data.id ?: throw Exception("ID interno no encontrado")

            val epResponse = app.get("$apiUrl/anime/$internalId/episode", headers = apiHeaders).text
            val simResponse = app.get("$apiUrl/anime/similar?animeId=$internalId", headers = apiHeaders).text

            val epData: EpisodeListResponse = mapper.readValue(epResponse)
            val simData: SimilarResponse = mapper.readValue(simResponse)

            val episodes = epData.data?.mapNotNull { ep ->
                val uid = ep.uid ?: return@mapNotNull null
                Log.d(TAG, "Logs: EP ${ep.number} - uid: $uid")

                newEpisode("$uid|$internalId") {
                    this.episode = ep.number?.toIntOrNull() ?: 0
                    this.name = ep.title ?: "Episodio ${ep.number}"
                    this.posterUrl = ep.image
                }
            }?.sortedBy { it.episode } ?: emptyList()

            val recommendations = simData.data?.map { sim ->
                newAnimeSearchResponse(sim.title ?: "", "$mainUrl/anime/${sim.link}") {
                    this.posterUrl = sim.posterImage?.large
                }
            }

            Log.d(TAG, "Logs: ${episodes.size} episodios encontrados")

            newAnimeLoadResponse(data.title ?: "Anime", url, TvType.Anime) {
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

        Log.d(TAG, "Logs: uid: $uid | origin: $origin")

        return try {
            val actionHeaders = mapOf(
                "accept" to "text/x-component",
                "content-type" to "text/plain;charset=UTF-8",
                "next-action" to "603712faba47e30723d32819533284371173c10bbd",
                "origin" to mainUrl,
                "referer" to "$mainUrl/watch/$uid?origin=$origin",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                "next-router-state-tree" to """["",{"children":["watch",{"children":[["id","$uid","d"],{"children":["__PAGE__",{},null,null]}]}]}]"""
            )

            val resText = app.post(
                "$mainUrl/watch/$uid?origin=$origin",
                headers = actionHeaders,
                requestBody = "[\"$uid\",\"$origin\"]"
                    .toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
            ).text.replace("\\/", "/")

            val videoUrl = Regex("""\"streamLink\"\s*:\s*\"(https?://[^\"]+)""")
                .find(resText)?.groupValues?.getOrNull(1)

            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        this.name, "AnimeParadise",
                        "https://stream.animeparadise.moe/m3u8?url=${videoUrl.encodeUri()}",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
            }

            val subDataJson = Regex(""""subData"\s*:\s*(\[.*?](?=\s*[,}]))""")
                .find(resText)?.groupValues?.getOrNull(1)

            Log.d(TAG, "Logs: subData: $subDataJson")

            if (subDataJson != null) {
                val subList = mapper.readValue<List<SubData>>(subDataJson)
                Log.d(TAG, "Logs: ${subList.size} subtítulos encontrados")

                subList.forEach { sub ->
                    val label = sub.label ?: "Unknown"
                    val src = sub.src ?: return@forEach
                    val type = sub.type ?: "vtt"

                    try {
                        when (type.lowercase()) {
                            "vtt" -> {
                                subtitleCallback.invoke(newSubtitleFile(label, src))
                                Log.d(TAG, "Logs: Sub VTT: $label -> $src")
                            }
                            "ass" -> {
                                val assUrl = "$apiUrl/stream/file/$src"
                                subtitleCallback.invoke(newSubtitleFile(label, assUrl))
                                Log.d(TAG, "Logs: Sub ASS directo: $label -> $assUrl")
                            }

                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Logs: Error sub $label: ${e.message}")
                    }
                }
            }

            videoUrl != null
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
    val image: String? = null
)
data class ImageInfo(val original: String? = null, val large: String? = null)

data class AnimeListResponse(val data: List<AnimeObject>? = null)

data class AnimeDetailResponse(
    val success: Boolean?,
    val data: AnimeData?
)

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
    val animeSeason: SeasonInfo?
)

data class SeasonInfo(
    val season: String?,
    val year: Int?
)

data class PosterImages(
    val large: String?,
    val original: String?
)

data class SimilarResponse(
    val success: Boolean?,
    val data: List<SimilarAnime>?
)

data class SimilarAnime(
    val title: String?,
    val link: String?,
    val posterImage: PosterImages?
)

data class EpisodeListResponse(
    val success: Boolean?,
    val data: List<EpisodeData>?
)

data class EpisodeData(
    @JsonProperty("_id") val id: String?,
    val uid: String?,
    val title: String?,
    val number: String?,
    val image: String?
)

data class SubData(
    val src: String?,
    val label: String?,
    val type: String?
)