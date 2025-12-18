package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class XtreamProvider(val host: String, override var name: String, val user: String, val pass: String) : MainAPI() {
    override var mainUrl = host
    override val hasMainPage = true
    override var lang = "mx"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Live)

    private val apiURL = "${mainUrl.removeSuffix("/")}/player_api.php?username=$user&password=$pass"
    private val headers = mapOf("User-Agent" to "IPTVSmarters")

    private var globalSearchCache = mutableListOf<Pair<Stream, TvType>>()

    private suspend fun callApi(action: String): String {
        return app.get("$apiURL&action=$action", headers = headers).body.string()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<HomePageList>()

        val liveStreams = safeParse<List<Stream>>(callApi("get_live_streams"))
        val vodStreams = safeParse<List<Stream>>(callApi("get_vod_streams"))
        val seriesStreams = safeParse<List<Stream>>(callApi("get_series"))

        globalSearchCache.clear()
        liveStreams.forEach { globalSearchCache.add(it to TvType.Live) }
        vodStreams.forEach { globalSearchCache.add(it to TvType.Movie) }
        seriesStreams.forEach { globalSearchCache.add(it to TvType.TvSeries) }

        if (liveStreams.isNotEmpty()) {
            home.add(HomePageList("TV en Vivo", liveStreams.take(20).map { it.toSearchResponse(TvType.Live) }))
        }
        if (vodStreams.isNotEmpty()) {
            home.add(HomePageList("Películas Recientes", vodStreams.take(20).map { it.toSearchResponse(TvType.Movie) }))
        }
        if (seriesStreams.isNotEmpty()) {
            home.add(HomePageList("Series de TV", seriesStreams.take(20).map { it.toSearchResponse(TvType.TvSeries) }))
        }

        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (globalSearchCache.isEmpty()) {
            val live = safeParse<List<Stream>>(callApi("get_live_streams"))
            val vod = safeParse<List<Stream>>(callApi("get_vod_streams"))
            val series = safeParse<List<Stream>>(callApi("get_series"))
            live.forEach { globalSearchCache.add(it to TvType.Live) }
            vod.forEach { globalSearchCache.add(it to TvType.Movie) }
            series.forEach { globalSearchCache.add(it to TvType.TvSeries) }
        }

        return globalSearchCache.filter { it.first.name.contains(query, ignoreCase = true) }.map {
            it.first.toSearchResponse(it.second)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<Data>(url)
        val poster = data.stream_icon

        return when (data.stream_type) {
            "series" -> {
                val seriesDataRaw = callApi("get_series_info&series_id=${data.series_id}")
                val seriesInfo = parseJson<SeriesInfoResponse>(seriesDataRaw)

                val finalPoster = seriesInfo.info?.cover
                    ?: seriesInfo.info?.movie_image
                    ?: poster

                val episodesList = mutableListOf<Episode>()

                seriesInfo.episodes?.forEach { (seasonNum, episodes) ->
                    episodes.forEach { ep ->
                        val epData = Data(
                            name = ep.title ?: "T$seasonNum E${ep.episode_num}",
                            stream_id = ep.id ?: ep.episode_id ?: 0,
                            stream_type = "series",
                            series_id = data.series_id,
                            container_extension = ep.container_extension
                        ).toJson()

                        episodesList.add(newEpisode(epData) {
                            this.name = ep.title
                            this.season = seasonNum.toIntOrNull()
                            this.episode = ep.episode_num?.toIntOrNull()
                            val infoMap = ep.info as? Map<*, *>
                            this.posterUrl = infoMap?.get("movie_image")?.toString() ?: finalPoster
                        })
                    }
                }

                newTvSeriesLoadResponse(data.name, url, TvType.TvSeries, episodesList) {
                    this.posterUrl = finalPoster
                    this.plot = seriesInfo.info?.plot
                }
            }
            "live" -> newLiveStreamLoadResponse(data.name, url, url) { this.posterUrl = poster }
            else -> newMovieLoadResponse(data.name, url, TvType.Movie, url) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = parseJson<Data>(data)
        val folder = when(parsed.stream_type) {
            "live" -> "live"
            "series" -> "series"
            else -> "movie"
        }
        val ext = parsed.container_extension ?: if (folder == "live") "ts" else "mp4"
        val id = parsed.stream_id ?: parsed.series_id
        val finalUrl = "${mainUrl.removeSuffix("/")}/$folder/$user/$pass/$id.$ext"

        callback.invoke(newExtractorLink(parsed.name, this.name, finalUrl) { this.referer = "" })
        return true
    }

    private fun Stream.toSearchResponse(type: TvType) = newLiveSearchResponse(
        name = this.name,
        url = Data(
            name = this.name,
            stream_id = this.stream_id ?: this.series_id,
            stream_icon = this.stream_icon ?: this.cover,
            stream_type = when(type) {
                TvType.Live -> "live"
                TvType.TvSeries -> "series"
                else -> "movie"
            },
            series_id = this.series_id,
            category_id = this.category_id,
            container_extension = this.container_extension
        ).toJson(),
        type = type
    ) {
        this.posterUrl = this@toSearchResponse.stream_icon ?: this@toSearchResponse.cover
    }

    private inline fun <reified T> safeParse(json: String): T {
        return try {
            if (json.trim().startsWith("[")) parseJson<T>(json)
            else if (json.trim().startsWith("{")) parseJson<T>(json)
            else parseJson<T>("[]")
        } catch (e: Exception) {
            parseJson<T>("[]")
        }
    }
}