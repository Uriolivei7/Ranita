package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

open class PlutotvProvider : MainAPI() {
    override var mainUrl: String = "https://pluto.tv"
    override var name: String = "PlutoTV"
    override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries, TvType.Live, TvType.Cartoon)
    override var lang: String = "mx"

    open val bootUrl = "https://boot.pluto.tv"

    override val hasMainPage: Boolean = true

    private lateinit var sessionData: Pair<String, Servers>

    private suspend fun obtainSessionData(): Pair<String, Servers> {
        if (this::sessionData.isInitialized) {
            Log.d("PLUTOTV", "obtainSessionData: using cached session")
            return sessionData
        }

        Log.d("PLUTOTV", "obtainSessionData: fetching new session from $bootUrl/v4/start")
        val bootResponse = app.get(
            "$bootUrl/v4/start", params = mapOf(
                "appName" to "web",
                "appVersion" to "9.22.0",
                "deviceVersion" to "142.0.0",
                "deviceModel" to "web",
                "deviceMake" to "firefox",
                "clientID" to UUID.randomUUID().toString(),
                "clientModelNumber" to "1.0.0"
            )
        ).parsed<BootResponse>()
        Log.d("PLUTOTV", "obtainSessionData: got sessionToken=${bootResponse.sessionToken.take(20)}... servers.api=${bootResponse.servers.api}")

        sessionData = bootResponse.sessionToken to bootResponse.servers
        return sessionData
    }

    private suspend fun authHeaders(): Map<String, String> {
        val (jwt, _) = obtainSessionData()

        return mapOf("Authorization" to "Bearer $jwt")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("PLUTOTV", "getMainPage: page=$page")
        val (_, servers) = obtainSessionData()
        Log.d("PLUTOTV", "getMainPage: servers.channels=${servers.channels}, servers.vod=${servers.vod}")

        // live
        Log.d("PLUTOTV", "getMainPage: fetching live channels from ${servers.channels}/v2/guide/channels")
        val channels = app.get(
            "${servers.channels}/v2/guide/channels?sort=number:asc",
            headers = authHeaders()
        )
            .parsed<ChannelsResponse>().data.map { channel ->
                newLiveSearchResponse(
                    name = channel.name,
                    url = channel.toJson(),
                    fix = false
                ) {
                    this.posterUrl = channel.images.firstOrNull()?.url
                }
            }
        Log.d("PLUTOTV", "getMainPage: found ${channels.size} live channels")

        // vod
        Log.d("PLUTOTV", "getMainPage: fetching VOD categories from ${servers.vod}/v4/vod/categories")
        val categoriesResponse = app.get(
            "${servers.vod}/v4/vod/categories",
            params = mapOf("includeItems" to "true"),
            headers = authHeaders()
        ).parsedLarge<CategoriesResponse>()
        Log.d("PLUTOTV", "getMainPage: got ${categoriesResponse.categories.size} categories")

        val categories = categoriesResponse.categories.map { category ->
            HomePageList(category.name, category.items.map { it.toMovieSearchResponse() })
        }

        Log.d("PLUTOTV", "getMainPage: returning ${categories.size + 1} rows (Live + $categories VOD)")
        return newHomePageResponse(
            listOf(
                HomePageList("Live", channels)
            ) + categories
        )
    }

    fun MediaItem.toMovieSearchResponse(): MovieSearchResponse {
        Log.d("PLUTOTV", "toMovieSearchResponse: name=$name id=$id type=$type")
        return newMovieSearchResponse(
            name = name,
            url = toJson(),
            fix = false
        ) {
            this.posterUrl = featuredImage.path
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d("PLUTOTV", "search: query='$query'")
        val (_, servers) = obtainSessionData()
        Log.d("PLUTOTV", "search: servers.search=${servers.search}, servers.vod=${servers.vod}")

        val response = app.get(
            "${servers.search}/v1/search", headers = authHeaders(),
            params = mapOf("q" to query, "limit" to "100")
        ).parsed<SearchResults>()
        Log.d("PLUTOTV", "search: found ${response.data.size} results from search API")

        if (response.data.isEmpty()) {
            Log.d("PLUTOTV", "search: no results, returning empty")
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()

        // Buscar canales en vivo por nombre (client-side)
        try {
            val allChannels = app.get(
                "${servers.channels}/v2/guide/channels",
                headers = authHeaders(),
                params = mapOf("sort" to "number:asc")
            ).parsed<ChannelsResponse>().data
            for (ch in allChannels) {
                if (ch.name.contains(query, ignoreCase = true)) {
                    Log.d("PLUTOTV", "search: live channel match: ${ch.name}")
                    results.add(newLiveSearchResponse(
                        name = ch.name,
                        url = ch.toJson(),
                        fix = false
                    ) {
                        this.posterUrl = ch.images.firstOrNull()?.url
                    })
                }
            }
        } catch (e: Exception) {
            Log.d("PLUTOTV", "search: channel search failed: ${e.message}")
        }

        // VOD (series/películas) desde search API
        val vodResults = response.data.filter { it.channel == null }
        if (vodResults.isNotEmpty()) {
            val ids = vodResults.joinToString(",") { it.id }
            Log.d("PLUTOTV", "search: fetching VOD details for ids=$ids")
            val vodDatas = app.get(
                "${servers.vod}/v4/vod/items",
                headers = authHeaders(),
                params = mapOf("ids" to ids)
            ).parsed<SearchDetailsResponse>()
            Log.d("PLUTOTV", "search: got ${vodDatas.size} VOD results")
            results.addAll(vodDatas.map { it.toMovieSearchResponse() })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PLUTOTV", "load: url=$url")
        val (_, servers) = obtainSessionData()

        // 1. Intentar parsear si viene como JSON estructurado (Búsqueda/Navegación directa)
        val channel = AppUtils.tryParseJson<Channel>(url)
        channel?.let { channel ->
            Log.d("PLUTOTV", "load: it's a LIVE channel (JSON): ${channel.name} (id=${channel.id})")
            return createLiveStreamResponse(channel)
        }

        val details = AppUtils.tryParseJson<MediaItem>(url)
        details?.let { details ->
            Log.d("PLUTOTV", "load: it's a VOD item (JSON): ${details.name} (id=${details.id}, type=${details.type})")
            return loadVodDetails(details, servers)
        }

        // 2. Si es una URL limpia de Favoritos, extraer ID usando Regex
        val seriesMatch = Regex("""/series/([^/?]+)""").find(url)
        val movieMatch = Regex("""/movies/([^/?]+)""").find(url)
        val liveMatch = Regex("""/live-tv/([^/?]+)""").find(url)

        val idFromUrl = seriesMatch?.groupValues?.get(1) ?: movieMatch?.groupValues?.get(1)
        val liveIdFromUrl = liveMatch?.groupValues?.get(1)

        // Caso A: Es un canal En Vivo desde favoritos (URL Plana)
        if (liveIdFromUrl != null) {
            Log.d("PLUTOTV", "load: fetching LIVE channel by id=$liveIdFromUrl from URL")

            val channelsResponse = app.get(
                "${servers.channels}/v2/guide/channels?sort=number:asc",
                headers = authHeaders()
            ).parsedSafe<ChannelsResponse>()

            val liveChannel = channelsResponse?.data?.find { it.id == liveIdFromUrl }

            if (liveChannel != null) {
                Log.d("PLUTOTV", "load: resolved liveId=$liveIdFromUrl to channel=${liveChannel.name}")
                return createLiveStreamResponse(liveChannel)
            } else {
                Log.d("PLUTOTV", "load: liveId=$liveIdFromUrl not found in channels list")
            }
        }

        // Caso B: Series o Películas desde favoritos
        if (idFromUrl != null) {
            Log.d("PLUTOTV", "load: fetching VOD item by id=$idFromUrl from URL")
            val item = app.get(
                "${servers.vod}/v4/vod/items",
                headers = authHeaders(),
                params = mapOf("ids" to idFromUrl)
            ).parsedSafe<SearchDetailsResponse>()?.firstOrNull()

            if (item != null) {
                Log.d("PLUTOTV", "load: resolved id=$idFromUrl to item=${item.name}")
                return loadVodDetails(item, servers)
            }
        }

        Log.d("PLUTOTV", "load: could not parse URL as Channel or MediaItem, returning null")
        return null
    }

    private suspend fun createLiveStreamResponse(channel: Channel): LiveStreamLoadResponse {
        Log.d("PLUTOTV", "load: stitched path=${channel.stitched?.path}")
        return newLiveStreamLoadResponse(
            name = channel.name,
            url = "$mainUrl/gsa/live-tv/${channel.id}",
            dataUrl = channel.stitched?.toJson() ?: ""
        ) {
            this.posterUrl = channel.images?.firstOrNull()?.url
            this.plot = channel.summary
        }
    }

    private suspend fun loadVodDetails(details: MediaItem, servers: Servers): LoadResponse {
        if (details.type == "series") {
            Log.d("PLUTOTV", "load: fetching seasons for series ${details.id}")
            val seasonInfo = app.get(
                "${servers.vod}/v4/vod/series/${details.id}/seasons",
                headers = authHeaders(),
            ).parsed<SeasonInfo>()
            Log.d("PLUTOTV", "load: got ${seasonInfo.seasons.size} seasons, name=${seasonInfo.name}")

            val episodes = seasonInfo.seasons.flatMap { season ->
                season.episodes.map { episode ->
                    newEpisode(episode.stitched) {
                        this.name = episode.name
                        this.posterUrl = episode.covers.firstOrNull()?.url
                        this.runTime = (episode.originalContentDuration / 1000 / 60).toInt()
                        this.description = episode.description
                        this.season = season.number.toInt()
                    }
                }
            }
            Log.d("PLUTOTV", "load: total episodes=${episodes.size}")

            return newTvSeriesLoadResponse(
                name = seasonInfo.name,
                url = "$mainUrl/gsa/search/details/series/${seasonInfo.id}",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = seasonInfo.featuredImage.path
                this.plot = seasonInfo.summary
            }
        } else {
            Log.d("PLUTOTV", "load: it's a MOVIE, stitched path=${details.stitched.path}")
            return newMovieLoadResponse(
                name = details.name,
                url = "$mainUrl/gsa/search/details/movies/${details.id}",
                dataUrl = details.stitched.toJson(),
                type = TvType.Movie,
            ) {
                this.posterUrl = details.featuredImage.path
                this.plot = details.summary
                this.duration = (details.originalContentDuration / 1000 / 60).toInt()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PLUTOTV", "loadLinks: data=$data")
        val stream = AppUtils.parseJson<Stitched>(data)
        Log.d("PLUTOTV", "loadLinks: parsed stitched path=${stream.path}, type=${stream.type}")
        val (_, servers) = obtainSessionData()
        Log.d("PLUTOTV", "loadLinks: servers.stitcher=${servers.stitcher}, servers.stitcherDash=${servers.stitcherDash}")

        val (url, type) = when (stream.type) {
            null, "hls" -> "${servers.stitcher}/v2${stream.path}" to ExtractorLinkType.M3U8
            "dash", "mpd" -> "${servers.stitcherDash}/v2${stream.path}" to ExtractorLinkType.DASH
            else -> {
                Log.d("PLUTOTV", "loadLinks: unknown stream type '${stream.type}', skipping")
                return true
            }
        }
        Log.d("PLUTOTV", "loadLinks: final url=$url, type=$type")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = type
            ) {
                this.headers = authHeaders()
            }
        )
        Log.d("PLUTOTV", "loadLinks: callback invoked, returning true")

        return true
    }


    data class BootResponse(
        val servers: Servers,
        val session: Session,
        val startingChannel: StartingChannel,
        val stitcherParams: String,
        val serverTime: String,
        val refreshInSec: Long,
        val isSuspicious: Boolean,
        val sessionToken: String,
    )

    data class Servers(
        val api: String,
        val channels: String,
        val vod: String,
        val stitcher: String,
        val analytics: String,
        val watchlist: String,
        val search: String,
        val concierge: String,
        val preferences: String,
        val campaigns: String,
        val users: String,
        val recommender: String,
        val catalog: String,
        val stitcherDash: String,
        val pause: String,
        val carousel: String,
        val features: String,
        val hub: String,
    )

    data class Session(
        @JsonProperty("sessionID")
        val sessionId: String,
        @JsonProperty("clientIP")
        val clientIp: String,
        val countryCode: String,
        val activeRegion: String,
        val city: String,
        val postalCode: String,
        val preferredLanguage: String,
        val deviceType: String,
        val deviceMake: String,
        val deviceModel: String,
        val logLevel: String,
        @JsonProperty("clientID")
        val clientId: String,
        val clientDeviceType: Long,
        val marketingRegion: String,
        @JsonProperty("restartThresholdMS")
        val restartThresholdMs: Long,
    )

    data class Stitched(
        val path: String?,
        val type: String?
    )

    data class Image(
        val type: String,
        val style: String,
        val ratio: Double,
        val defaultWidth: Long,
        val defaultHeight: Long,
        val url: String,
    )

    data class StartingChannel(
        val id: String,
        val slug: String,
    )

    data class ChannelsResponse(
        val data: List<Channel>,
    )

    data class Channel(
        val id: String,
        val slug: String,
        val name: String,
        val hash: String,
        val number: Long,
        val summary: String,
        val plutoOfficeOnly: Boolean,
        val featured: Boolean,
        val featuredOrder: Long?,
        val stitched: Stitched,
        val images: List<Image>,
        @JsonProperty("categoryIDs")
        val categoryIds: List<String>,
        val tmsid: String?,
        val kidsMode: Boolean?,
    )

    data class SearchResults(
        val data: List<SearchResult>,
    )

    data class SearchResult(
        val id: String,
        val slug: String,
        val name: String,
        val type: String,
        val language: String,
        val rating: String?,
        val start: String?,
        val stop: String?,
        val channel: SearchChannel?,
        val number: Long?,
    )

    data class SearchChannel(
        val slug: String,
        val name: String,
        val number: Long,
        val logo: Logo,
    )

    data class Logo(
        val path: String,
    )

    class SearchDetailsResponse : ArrayList<MediaItem>()

    data class MediaItem(
        @JsonProperty("_id")
        val id: String,
        @JsonProperty("seriesID")
        val seriesId: String?,
        val slug: String,
        val name: String,
        val summary: String,
        val description: String,
        val duration: Long?,
        val originalContentDuration: Long,
        val allotment: Long?,
        val rating: String,
        val featuredImage: FeaturedImage,
        val genre: String,
        val type: String,
        val seasonsNumbers: List<Long>,
        val stitched: Stitched,
        val covers: List<Cover>,
        val clip: Clip?,
        val avail: Map<String, Any>,
    )

    data class FeaturedImage(
        val path: String,
    )

    data class Clip(
        val actors: List<String>?,
        val directors: List<String>?,
        val producers: List<String>?,
        @JsonProperty("original Release Date")
        val originalReleaseDate: String?,
        val writers: List<String>?,
    )

    data class SeasonInfo(
        @JsonProperty("_id")
        val id: String,
        val name: String,
        val summary: String,
        val description: String,
        val slug: String,
        val type: String,
        val rating: String,
        val featuredImage: FeaturedImage,
        val genre: String,
        val offset: Long,
        val page: Long,
        val seasons: List<Season>,
        val covers: List<Cover2>,
        val avail: Map<String, Any>,
    )

    data class Season(
        val episodes: List<Episode>,
        val number: Long,
    )

    data class Episode(
        @JsonProperty("_id")
        val id: String,
        val name: String,
        val description: String,
        val allotment: Long,
        val rating: String,
        val slug: String,
        val duration: Long,
        val originalContentDuration: Long,
        val genre: String,
        val type: String,
        val number: Long,
        val season: Long,
        val stitched: Stitched,
        val covers: List<Cover>,
        val clip: Clip,
    )

    data class Cover(
        val aspectRatio: String,
        val url: String,
    )

    data class Cover2(
        val aspectRatio: String,
        val url: String,
    )

    data class CategoriesResponse(
        val offset: Long,
        val page: Long,
        val totalCategories: Long,
        val totalPages: Long,
        val categories: List<HomePageCategory>,
    )

    data class HomePageCategory(
        @JsonProperty("_id")
        val id: String,
        val name: String,
        val plutoOfficeOnly: Boolean,
        val page: Long,
        val offset: Long,
        val totalItemsCount: Long,
        val items: List<MediaItem>,
        @JsonProperty("hero_carousel")
        val heroCarousel: Boolean?,
        val kidsMode: Boolean?,
    )
}