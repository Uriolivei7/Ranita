package com.example

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File

class UniqueStreamProvider : MainAPI() {
    override var mainUrl = "https://anime.uniquestream.net"
    override var name = "AnimeStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val apiUrl = "https://anime.uniquestream.net/api/v1"
    private val TAG = "UniqueStream"

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json"
    )

    private fun SeriesItem.toSearchResponse(): SearchResponse {
        val isMovie = this.type == "movie"
        val resp = if (isMovie) {
            newAnimeSearchResponse(this.title, this.content_id, TvType.AnimeMovie)
        } else {
            newAnimeSearchResponse(this.title, this.content_id)
        }
        resp.posterUrl = image?.replace("posters/60x90/", "posters/480x720/")
        return resp
    }

    private fun String?.upgradePoster(): String? =
        this?.replace("posters/60x90/", "posters/480x720/")

    private fun localeLabel(locale: String): String = when (locale) {
        "en-US" -> "English (US)"
        "es-419" -> "Español (LATAM)"
        "es-ES" -> "Español (España)"
        "ja-JP" -> "日本語"
        "pt-BR" -> "Português (BR)"
        "de-DE" -> "Deutsch"
        "fr-FR" -> "Français"
        "it-IT" -> "Italiano"
        "zh-CN" -> "中文"
        "zh-HK" -> "中文 (HK)"
        "ko-KR" -> "한국어"
        "ru-RU" -> "Русский"
        "ar-SA" -> "العربية"
        "id-ID" -> "Indonesia"
        "ms-MY" -> "Melayu"
        "th-TH" -> "ไทย"
        "vi-VN" -> "Tiếng Việt"
        else -> locale
    }

    private val keyRegex = Regex("/([0-9a-f]{32})_[^/]+/master\\.m3u8")

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val linkUrl = extractorLink.url
        val mediaId = keyRegex.find(linkUrl)?.groupValues?.get(1)
            ?: Regex("/([0-9a-f]{32})_[^/]+/").find(linkUrl)?.groupValues?.get(1)

        val realKey: ByteArray? = try {
            if (mediaId != null && mediaId.length == 32) {
                mediaId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "getVideoInterceptor: media_id inválido $mediaId")
            null
        }

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()

                if (realKey != null && url.contains("keys/") && url.contains("key.bin")) {
                    Log.d(TAG, "Interceptando key.bin -> ${realKey.toHex()}")
                    return Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/octet-stream")
                        .body(ResponseBody.create("application/octet-stream".toMediaTypeOrNull(), realKey))
                        .build()
                }
                return chain.proceed(request)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            Log.d(TAG, "Cargando MainPage...")

            val sections = listOf(
                Triple("Nuevos", "$apiUrl/videos/new?slider=1&limit=10", 0),
                Triple("Populares", "$apiUrl/videos/popular?slider=1&limit=10", 1),
                Triple("Películas", "$apiUrl/videos/movies?limit=6&sort=popular", 2),
                Triple("Acción", "$apiUrl/browse?categories=action,popular&limit=20&type=all&slider=1", 3),
                Triple("Aventura", "$apiUrl/browse?categories=adventure,popular&limit=20&type=all&slider=1", 4),
                Triple("Comedia", "$apiUrl/browse?categories=comedy,popular&limit=20&type=all&slider=1", 5),
                Triple("Drama", "$apiUrl/browse?categories=drama,popular&limit=20&type=all&slider=1", 6),
                Triple("Fantasía", "$apiUrl/browse?categories=fantasy,popular&limit=20&type=all&slider=1", 7),
                Triple("Sci-Fi", "$apiUrl/browse?categories=sci-fi,popular&limit=20&type=all&slider=1", 8),
            )

            val homeItems = mutableListOf<HomePageList>()

            for (section in sections) {
                try {
                    val response = app.get(
                        section.second,
                        headers = baseHeaders,
                        timeout = 30L
                    ).text

                    val items = AppUtils.parseJson<List<SeriesItem>>(response)
                    val list = items.map { it.toSearchResponse() }
                    if (list.isNotEmpty()) {
                        homeItems.add(HomePageList(section.first, list))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sección '${section.first}' falló: ${e.message}")
                }
            }

            Log.d(TAG, "Secciones cargadas: ${homeItems.size}")
            newHomePageResponse(homeItems, homeItems.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Error en getMainPage: ${e.message}")
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(
                "$apiUrl/search?query=$query",
                headers = baseHeaders,
                timeout = 30L
            ).text

            val data = AppUtils.parseJson<SearchRoot>(response)
            data.series?.map { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error en search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanId = url.split("/").lastOrNull { it.isNotBlank() } ?: url
        Log.d(TAG, "Cargando serie con ID: $cleanId")

        val seriesResponse = app.get(
            "$apiUrl/series/$cleanId",
            headers = baseHeaders,
            timeout = 30L
        ).text

        val details = AppUtils.parseJson<DetailsResponse>(seriesResponse)
        val processedSeasonIds = mutableSetOf<String>()

        val orderedSeasons = (details.seasons ?: emptyList())
            .filter { processedSeasonIds.add(it.content_id) }
            .sortedWith(
                compareBy<SeasonItem> { it.season_seq_number ?: it.season_number }
                    .thenBy { it.season_number }
            )

        val seasonResults = mutableListOf<Pair<Int, List<EpisodeItem>>>()

        coroutineScope {
            val jobs = orderedSeasons.mapIndexed { index, season ->
                async {
                    val displaySeason = index + 1
                    val eps = loadSeasonEpisodes(season)
                    displaySeason to eps
                }
            }
            jobs.forEach { job ->
                seasonResults.add(job.await())
            }
        }

        val episodesList = mutableListOf<Episode>()
        seasonResults.sortedBy { it.first }.forEach { (displaySeason, eps) ->
            eps.forEach { ep ->
                episodesList.add(newEpisode(ep.content_id) {
                    this.name = ep.title
                    this.episode = ep.episode_number?.toInt()
                    this.season = displaySeason
                    this.posterUrl = ep.image
                })
            }
        }

        Log.d(TAG, "Total episodios cargados: ${episodesList.size}")

        val audioText = details.audio_locales?.joinToString(", ") { localeLabel(it) }
        val subText = details.subtitle_locales?.joinToString(", ") { localeLabel(it) }
        val fullPlot = buildString {
            append(details.description ?: "")
            if (!audioText.isNullOrBlank() || !subText.isNullOrBlank()) {
                append("\n\n")
                if (!audioText.isNullOrBlank()) append(" -- Audio: $audioText")
                if (!audioText.isNullOrBlank() && !subText.isNullOrBlank()) append("\n")
                if (!subText.isNullOrBlank()) append(" -- Subtítulos: $subText")
            }
        }

        return newAnimeLoadResponse(details.title ?: "Sin Título", url, TvType.Anime) {
            this.posterUrl = details.images?.find { it.type == "poster_tall" }?.url?.upgradePoster()
            this.plot = fullPlot
            this.tags = (details.audio_locales ?: emptyList()) + (details.subtitle_locales ?: emptyList())
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    private suspend fun loadSeasonEpisodes(season: SeasonItem): List<EpisodeItem> {
        val episodeCount = season.episode_count ?: 0
        val totalPages = if (episodeCount > 0) {
            (episodeCount + 19) / 20
        } else {
            1
        }

        val pageResults = mutableListOf<List<EpisodeItem>>()

        coroutineScope {
            val jobs = (1..totalPages).map { page ->
                async {
                    try {
                        val seasonUrl = "$apiUrl/season/${season.content_id}/episodes?page=$page&limit=20&order_by=asc"
                        val response = app.get(seasonUrl, headers = baseHeaders, timeout = 30L).text
                        if (response.trim().startsWith("[")) {
                            AppUtils.parseJson<List<EpisodeItem>>(response)
                        } else {
                            emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en página $page: ${e.message}")
                        emptyList()
                    }
                }
            }
            jobs.forEach { job ->
                pageResults.add(job.await())
            }
        }

        val allEps = pageResults.flatten().toMutableList()

        if (episodeCount <= 0) {
            var extraPage = totalPages + 1
            var keepLoading = true
            while (keepLoading) {
                try {
                    val seasonUrl = "$apiUrl/season/${season.content_id}/episodes?page=$extraPage&limit=20&order_by=asc"
                    val response = app.get(seasonUrl, headers = baseHeaders, timeout = 30L).text
                    if (response.trim().startsWith("[")) {
                        val eps = AppUtils.parseJson<List<EpisodeItem>>(response)
                        if (eps.isEmpty()) {
                            keepLoading = false
                        } else {
                            allEps.addAll(eps)
                            extraPage++
                            if (eps.size < 20) keepLoading = false
                        }
                    } else {
                        keepLoading = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en paginación extra: ${e.message}")
                    keepLoading = false
                }
            }
        }

        return allEps
            .distinctBy { it.content_id }
            .filter { it.is_clip != true }
            .sortedBy { it.episode_number ?: 0.0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = if (data.contains("/")) {
            data.substringAfterLast("/").trim()
        } else {
            data.trim()
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "Episode ID: $episodeId")
        Log.d(TAG, "========================================")

        return try {
            val locales = listOf("es-419", "en-US", "ja-JP")
            var linksEnviados = 0

            for (locale in locales) {
                try {
                    val mediaUrl = "$apiUrl/episode/$episodeId/media/dash/$locale"

                    val apiHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "*/*",
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )

                    val response = app.get(mediaUrl, headers = apiHeaders, timeout = 15L)

                    if (response.code == 200) {
                        Log.d(TAG, "✓ API 200 para $locale")

                        val videoData = AppUtils.parseJson<VideoResponse>(response.text)

                        Log.d(TAG, "DEBUG - DASH disponible: ${videoData.versions?.dash != null}")
                        Log.d(TAG, "DEBUG - HLS disponible: ${videoData.versions?.hls != null}")
                        Log.d(TAG, "DEBUG - Cantidad DASH: ${videoData.versions?.dash?.size ?: 0}")
                        Log.d(TAG, "DEBUG - Cantidad HLS: ${videoData.versions?.hls?.size ?: 0}")

                        val dashVersions = videoData.versions?.dash ?: emptyList()
                        val hlsVersions = mutableListOf<HlsVersion>()
                        videoData.versions?.hls?.let { hlsVersions.addAll(it) }
                        videoData.hls?.let { hlsVersions.add(it) }

                        // DASH
                        if (dashVersions.isNotEmpty()) {
                            Log.d(TAG, "Procesando ${dashVersions.size} versiones DASH")
                            dashVersions.forEach { dashVersion ->
                                if (dashVersion.playlist.isNotBlank()) {
                                    Log.d(TAG, "✓ DASH ${dashVersion.locale}")
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - ${dashVersion.locale.uppercase()}",
                                            url = dashVersion.playlist,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.referer = "$mainUrl/"
                                            this.headers = mapOf(
                                                "Accept" to "*/*",
                                                "Origin" to mainUrl,
                                                "Referer" to "$mainUrl/"
                                            )
                                        }
                                    )
                                    linksEnviados++
                                }
                            }
                        }

                        if (hlsVersions.isNotEmpty()) {
                            Log.d(TAG, "Procesando ${hlsVersions.size} versiones HLS")
                            val commonHeaders = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Accept" to "*/*",
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/"
                            )
                            for (hlsVersion in hlsVersions) {
                                if (hlsVersion.playlist.isNotBlank()) {
                                    val masterUrl = hlsVersion.playlist
                                    val localeTag = hlsVersion.locale.uppercase()
                                    Log.d(TAG, "Master URL: $masterUrl")
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - 1080p [${localeTag}]",
                                            url = masterUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.referer = "$mainUrl/"
                                            this.headers = commonHeaders
                                        }
                                    )
                                    linksEnviados++

                                    hlsVersion.subtitles?.forEach { sub ->
                                        if (sub.url.isNotBlank()) {
                                            Log.d(TAG, "✓ Subtitle ${sub.language}: ${sub.url}")
                                            subtitleCallback(newSubtitleFile(sub.language, sub.url))
                                        }
                                    }

                                    hlsVersion.hard_subs?.forEach { hs ->
                                        if (hs.playlist.isNotBlank()) {
                                            val hsUrl = hs.playlist
                                            Log.d(TAG, "✓ HardSub ${hs.locale}: $hsUrl")
                                            callback(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "${this.name} - 1080p [${localeTag}] (Subs ${hs.locale.uppercase()})",
                                                    url = hsUrl,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.quality = Qualities.P1080.value
                                                    this.referer = "$mainUrl/"
                                                    this.headers = commonHeaders
                                                }
                                            )
                                            linksEnviados++
                                        }
                                    }
                                }
                            }
                        }

                        if (linksEnviados > 0) break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error con locale $locale: ${e.message}")
                    continue
                }
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "TOTAL LINKS: $linksEnviados")
            Log.d(TAG, "========================================")

            linksEnviados > 0

        } catch (e: Exception) {
            Log.e(TAG, "ERROR: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    @Serializable
    data class SeriesItem(
        val content_id: String,
        val title: String,
        val image: String? = null,
        val type: String? = null
    )

    @Serializable
    data class SearchRoot(
        val series: List<SeriesItem>? = null,
        val episodes: List<EpisodeItem>? = null
    )

    @Serializable
    data class DetailsResponse(
        val content_id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val images: List<ImageItem>? = null,
        val seasons: List<SeasonItem>? = null,
        val audio_locales: List<String>? = null,
        val subtitle_locales: List<String>? = null
    )

    @Serializable
    data class SeasonItem(
        val content_id: String,
        val season_number: Int,
        val title: String? = null,
        val episode_count: Int? = null,
        val season_seq_number: Int? = null
    )

    @Serializable
    data class EpisodeItem(
        val content_id: String,
        val series_id: String? = null,
        val title: String? = null,
        val episode_number: Double? = null,
        val image: String? = null,
        val is_clip: Boolean? = false
    )

    @Serializable
    data class ImageItem(
        val url: String,
        val type: String
    )

    @Serializable
    data class VideoResponse(
        val versions: Versions? = null,
        val hls: HlsVersion? = null
    )

    @Serializable
    data class Versions(
        val hls: List<HlsVersion>? = null,
        val dash: List<DashVersion>? = null
    )

    @Serializable
    data class HlsVersion(
        val locale: String,
        val playlist: String,
        val subtitles: List<SubtitleItem>? = null,
        val hard_subs: List<HardSubItem>? = null
    )

    @Serializable
    data class DashVersion(
        val locale: String,
        val playlist: String,
        val subtitles: List<SubtitleItem>? = null,
        val hard_subs: List<HardSubItem>? = null
    )

    @Serializable
    data class HardSubItem(
        val locale: String,
        val playlist: String
    )

    @Serializable
    data class SubtitleItem(
        val language: String,
        val url: String,
        val mimeType: String? = null
    )
}