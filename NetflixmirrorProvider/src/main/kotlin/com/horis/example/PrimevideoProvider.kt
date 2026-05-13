package com.horis.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import android.util.Log

class PrimevideoProvider : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://net52.cc"
    override var name = "PrimeVideo"
    override val hasMainPage = true

    private val ott = "pv"

    private fun pvPoster(id: String): String = "https://imgcdn.kim/pv/v/$id.jpg"
    private fun pvBg(id: String): String = "https://imgcdn.kim/pv/h/$id.jpg"
    private fun pvEpPoster(id: String): String = "https://imgcdn.kim/pvepimg/150/$id.jpg"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiBase = resolveApiUrl()
        val response = app.get(
            "$apiBase/newtv/main.php",
            headers = buildNewTvHeaders(ott, mapOf("Page" to "all", "Recentplay" to "", "Watchlist" to "", "Usertoken" to ""))
        ).parsed<NewTvMainResponse>()
        val imgReferer = response.img_referer ?: apiBase
        val items = response.post.orEmpty().map { category ->
            val ids = category.ids?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val results = ids.mapNotNull { id ->
                newAnimeSearchResponse("", NewTvId(id).toJson()) {
                    posterUrl = pvPoster(id)
                    posterHeaders = mapOf("Referer" to imgReferer)
                }
            }
            HomePageList(category.cate.orEmpty(), results, isHorizontalImages = false)
        }
        return newHomePageResponse(items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiBase = resolveApiUrl()
        val data = app.get(
            "$apiBase/newtv/search.php?s=${URLEncoder.encode(query, "UTF-8")}",
            headers = buildNewTvHeaders(ott)
        ).parsed<NewTvSearchResponse>()
        val imgReferer = data.img_referer ?: apiBase
        return data.searchResult.orEmpty().map { item ->
            newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                posterUrl = pvPoster(item.id)
                posterHeaders = mapOf("Referer" to imgReferer)
            }
        }
    }

    private fun extractSeasonNumber(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        return Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiBase = resolveApiUrl()
        val id = parseJson<NewTvId>(url).id
        val rawResponse = app.get(
            "$apiBase/newtv/post.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to ""))
        ).text
        Log.d("Primevideo", "RAW post response: $rawResponse")
        val data = JSONParser.parse(rawResponse, NewTvPostResponse::class)
        Log.d("Primevideo", "ua=${data.ua}")

        Log.d("Primevideo", "Seasons count: ${data.season?.size ?: 0}")
        data.season?.forEachIndexed { i, s ->
            Log.d("Primevideo", "Season[$i]: id=${s.id}, s=${s.s}, selected=${s.selected}")
        }
        Log.d("Primevideo", "Episodes count: ${data.episodes?.size ?: 0}")
        Log.d("Primevideo", "nextPageSeason: ${data.nextPageSeason}")
        Log.d("Primevideo", "type: ${data.type}")

        val title = data.title ?: id
        val playbackId = data.main_id ?: id
        val cast = data.cast?.split(",")?.map { it.trim() }?.map { ActorData(Actor(it)) } ?: emptyList()
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val languages = data.lang?.map { it.l.orEmpty() }?.filter { it.isNotBlank() }?.distinct()
        val director = data.moredetails?.find { it.k == "Director" }?.v
        val studio = data.moredetails?.find { it.k == "Studio" }?.v
        val writer = data.moredetails?.find { it.k == "Writer" }?.v
        val thisMovieIs = data.moredetails?.find { it.k == "This Movie Is" }?.v
        Log.d("Primevideo", "lang=${languages} director=${director} studio=${studio} writer=${writer} thisMovieIs=${thisMovieIs}")
        val imdbFromDetails = data.moredetails?.find { it.k == "IMDB Rating" }?.v
        val rating = when {
            data.match?.startsWith("IMDb ") == true -> data.match?.replace("IMDb ", "")
            data.match?.contains("%") == true -> {
                val pct = data.match?.replace(Regex("[^0-9]"), "")?.toFloatOrNull()
                if (pct != null) String.format("%.1f", pct / 10f) else null
            }
            else -> data.match ?: imdbFromDetails
        }
        val runTime = convertRuntimeToMinutes(data.runtime ?: "")
        val isSeries = data.type == "t" || data.episodes?.any { it != null } == true
        val extraPlot = buildList {
            //if (!director.isNullOrBlank()) add("Director: $director")
            //if (!writer.isNullOrBlank()) add("Writer: $writer")
            if (!studio.isNullOrBlank()) add(" - Studio: $studio")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        val languagesText = if (!languages.isNullOrEmpty()) " - Audio: ${languages.joinToString(", ")}" else null
        val tags = buildList {
            if (!genre.isNullOrEmpty()) addAll(genre)
            if (!thisMovieIs.isNullOrBlank()) add(thisMovieIs)
        }.takeIf { it.isNotEmpty() }
        val fullPlot = buildList {
            data.desc?.let { add(it) }
            languagesText?.let { add(it) }
            extraPlot?.let { add(it) }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = pvPoster(it.id)
                posterHeaders = mapOf("Referer" to apiBase)
            }
        }

        if (!isSeries) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                NewTvLoadData(title, playbackId).toJson()
            ) {
                posterUrl = pvPoster(id)
                backgroundPosterUrl = pvBg(id)
                posterHeaders = mapOf("Referer" to apiBase)
                plot = fullPlot; year = data.year?.toIntOrNull(); this.tags = tags
                actors = cast; this.score = Score.from10(rating); duration = runTime
                recommendations = suggest
                contentRating = data.ua ?: data.certification ?: data.age
            }
        }

        val episodes = arrayListOf<Episode>()

        if (data.episodes.isNullOrEmpty()) {
            if (data.type != "t") episodes.add(newEpisode(NewTvLoadData(title, playbackId)) {
                name = title
            })
        } else {
            val selectedSeasonIdx = data.season?.indexOfFirst { it.selected == true }?.takeIf { it >= 0 }
            val selectedSeasonId = selectedSeasonIdx?.let { data.season?.getOrNull(it)?.id } ?: data.nextPageSeason
            val selectedSeasonNum = extractSeasonNumber(data.season?.find { it.selected == true }?.s)

            val allSeasons = mutableListOf<Pair<String, Int?>>()

            Log.d("Primevideo", "Main block episodes count: ${data.episodes?.size ?: 0}")
            data.episodes?.forEachIndexed { i, ep ->
                Log.d("Primevideo", "  main ep[$i]: id=${ep?.id}, t=${ep?.t}, ep=${ep?.ep}")
            }
            val mainBlockEpisodes = data.episodes
                .filterNotNull()
                .distinctBy { it.id }
                .map { ep ->
                    Log.d("Primevideo", "main ep id=${ep.id} t=${ep.t} info=${ep.info}")
                    newEpisode(NewTvLoadData(title, ep.id.orEmpty())) {
                        name = ep.t
                        episode = ep.ep?.toIntOrNull() ?: ep.epNum?.replace("E", "").orEmpty().toIntOrNull()
                        season = selectedSeasonNum ?: extractSeasonNumber(ep.s)
                        posterUrl = pvEpPoster(ep.id.orEmpty())
                        this.runTime = ep.info?.getOrNull(2)?.replace("m", "")?.toIntOrNull()
                        description = ep.ep_desc
                    }
                }

            if (!selectedSeasonId.isNullOrBlank()) {
                val selNum = extractSeasonNumber(data.season?.find { it.id == selectedSeasonId }?.s)
                allSeasons.add(Pair(selectedSeasonId, selNum))
            }
            Log.d("Primevideo", "selectedSeasonId=$selectedSeasonId, nextPageShow=${data.nextPageShow}, allSeasons before loop=${allSeasons.size}")

            data.season?.forEach { season ->
                if (season.id != selectedSeasonId && !season.id.isNullOrBlank()) {
                    allSeasons.add(Pair(season.id, extractSeasonNumber(season.s)))
                }
            }

            allSeasons.sortBy { it.second ?: 0 }

            allSeasons.forEach { (sid, sNum) ->
                if (sid == selectedSeasonId) {
                    episodes.addAll(mainBlockEpisodes)
                    if (data.nextPageShow == 1) {
                        episodes.addAll(getEpisodes(title, sid, 2, sNum))
                    }
                } else {
                    episodes.addAll(getEpisodes(title, sid, 1, sNum))
                }
            }
        }

        if (data.type == "t" && episodes.isEmpty() && !data.season.isNullOrEmpty()) {
            val seasonsList = data.season.map { Pair(it.id, extractSeasonNumber(it.s)) }.sortedBy { it.second ?: 0 }
            seasonsList.forEach { (sid, sNum) ->
                if (!sid.isNullOrBlank()) {
                    episodes.addAll(getEpisodes(title, sid, 1, sNum))
                }
            }
        }

        Log.d("Primevideo", "Total episodes returned: ${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = pvPoster(id)
            backgroundPosterUrl = pvBg(id)
            posterHeaders = mapOf("Referer" to apiBase)
            plot = fullPlot; year = data.year?.toIntOrNull(); this.tags = tags
            actors = cast; this.score = Score.from10(rating); duration = runTime
            recommendations = suggest
            contentRating = data.ua ?: data.certification ?: data.age
        }
    }

    private suspend fun getEpisodes(
        title: String, sid: String, page: Int, seasonNumber: Int? = null
    ): List<Episode> {
        val apiBase = resolveApiUrl()
        val episodes = arrayListOf<Episode>()
        val seenIds = mutableSetOf<String>()
        var pg = page
        while (true) {
            val rawEp = app.get(
                "$apiBase/newtv/episodes.php",
                params = mapOf("id" to sid, "page" to pg.toString()),
                headers = buildNewTvHeaders(ott)
            ).text
            Log.d("Primevideo", "RAW episodes page=$pg: $rawEp")
            val data = JSONParser.parse(rawEp, NewTvEpisodesResponse::class)

            Log.d("Primevideo", "getEpisodes: sid=$sid page=$pg got=${data.episodes?.size ?: 0}")
            data.episodes?.forEach { e ->
                Log.d("Primevideo", "  ep: id=${e.id}, t=${e.t}, s=${e.s}, ep=${e.ep}")
            }

            data.episodes.orEmpty().forEach { ep ->
                if (ep.id.isNullOrBlank()) return@forEach
                if (ep.id in seenIds) return@forEach
                seenIds.add(ep.id)
                Log.d("Primevideo", "getEpisodes id=${ep.id} t=${ep.t} info=${ep.info}")
                episodes.add(newEpisode(NewTvLoadData(title, ep.id.orEmpty())) {
                    name = ep.t
                    episode = ep.ep?.toIntOrNull() ?: ep.epNum?.replace("E", "").orEmpty().toIntOrNull()
                    season = seasonNumber ?: extractSeasonNumber(ep.s) ?: extractSeasonNumber(ep.sNum)
                    posterUrl = pvEpPoster(ep.id.orEmpty())
                    this.runTime = ep.info?.getOrNull(2)?.replace("m", "")?.toIntOrNull()
                    description = ep.ep_desc
                })
            }

            if (data.nextPageShow != 1) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = resolveApiUrl()
        val load = parseJson<NewTvLoadData>(data)
        Log.d("Primevideo", "loadLinks: playbackId=${load.id}")
        val result = app.get(
            "$apiBase/newtv/player.php?id=${load.id}",
            headers = buildNewTvHeaders(ott, mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()
        Log.d("Primevideo", "loadLinks result=${result}")
        if (result.status != "ok" || result.video_link.isNullOrBlank()) {
            Log.e("Primevideo", "loadLinks FAILED: status=${result.status}, video_link=${result.video_link}")
            return false
        }
        callback.invoke(newExtractorLink(name, name, result.video_link, type = ExtractorLinkType.M3U8) {
            this.referer = result.referer ?: apiBase
        })
        return true
    }
}