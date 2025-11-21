package com.horis.example

import android.util.Log // Agregado para usar Log.i()
import com.horis.example.entities.EpisodesData
import com.horis.example.entities.PlayList
import com.horis.example.entities.PostData
import com.horis.example.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.getQualityFromName
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class JioHotstarProvider : MainAPI() {
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"

    override var mainUrl = "https://net20.cc"
    private var newUrl = "https://net51.cc"
    override var name = "JioHotstar"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val TAG = "JioHotstarProvider"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.i(TAG, "Starting getMainPage (Page: $page)")
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "hs",
            "hd" to "on"
        )
        val document = app.get(
            "$mainUrl/mobile/home",
            cookies = cookies,
            referer = "$mainUrl/home",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        Log.i(TAG, "Found ${items.size} homepage lists.")
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        Log.i(TAG, "List '$name' extracted ${items.size} results.")
        return HomePageList(name, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post") ?: return null

        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = "https://imgcdn.kim/hs/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.i(TAG, "Starting search for query: $query")
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "hs"
        )
        val url = "$mainUrl/mobile/hs/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", cookies = cookies).parsed<SearchData>()

        Log.i(TAG, "Search query '$query' returned ${data.searchResult.size} results.")

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/hs/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i(TAG, "Starting load for URL: $url")
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        Log.i(TAG, "Extracted ID: $id")

        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "hs"
        )
        val data = app.get(
            "$mainUrl/mobile/hs/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(
                Actor(it),
            )
        }
        val genre = data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                this.posterUrl = "https://imgcdn.kim/hs/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        Log.i(TAG, "Title loaded: $title, Type check: ${if (data.episodes.first() == null) "Movie" else "Series"}")

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
            Log.i(TAG, "Added 1 episode for Movie type.")
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/hsepimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            Log.i(TAG, "Added ${data.episodes.filterNotNull().size} episodes from initial post data.")


            if (data.nextPageShow == 1) {
                Log.i(TAG, "Fetching next page episodes for season: ${data.nextPageSeason}")
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                Log.i(TAG, "Fetching episodes for subsequent season ID: ${it.id}")
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
            Log.i(TAG, "Total episodes collected: ${episodes.size}")
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        Log.i(TAG, "Final Load Response metadata: Year=${data.year}, Rating=${rating}, Tags=${genre?.size}")

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/hs/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/hs/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            //this.rating = (rating?.toDoubleOrNull()?.times(10.0))?.toInt()
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "hs"
        )
        var pg = page
        while (true) {
            val epUrl = "$mainUrl/mobile/hs/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg"
            Log.i(TAG, "Fetching episodes page $pg for SID: $sid")

            val data = app.get(
                epUrl,
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()

            val newEpsCount = data.episodes?.size ?: 0
            Log.i(TAG, "Fetched $newEpsCount episodes from page $pg.")

            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/hsepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        Log.i(TAG, "Finished fetching episodes for SID: $sid. Total: ${episodes.size}")
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "Starting loadLinks for data: $data")
        val (title, id) = parseJson<LoadData>(data)
        Log.i(TAG, "Loading links for Episode/Movie ID: $id, Title: $title")

        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "hs"
        )
        val playlistUrl = "$mainUrl/mobile/hs/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}"
        Log.i(TAG, "Fetching playlist from: $playlistUrl")

        val playlist = app.get(
            playlistUrl,
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PlayList>()

        var linkCount = 0
        var subtitleCount = 0

        playlist.forEach { item ->
            item.sources.forEach {
                callback.invoke(
                    newExtractorLink(
                        name,
                        it.label,
                        "$newUrl/${it.file}",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$newUrl/home"
                        this.quality = getQualityFromName(it.file.substringAfter("q=", ""))
                    }
                )
                linkCount++
                Log.i(TAG, "Found Link: ${it.label} (${it.file})")
            }

            item.tracks?.filter { it.kind == "captions" }?.map { track ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        track.label.toString(),
                        httpsify(track.file.toString()),
                    )
                )
                subtitleCount++
                Log.i(TAG, "Found Subtitle: ${track.label} at ${track.file} (Kind: ${track.kind})")
            }
        }

        Log.i(TAG, "Finished loadLinks. Total links: $linkCount, Total subtitles: $subtitleCount")

        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        Log.i(TAG, "Interceptor requested for URL: ${extractorLink.url}")
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    Log.i(TAG, "Applying 'hd=on' cookie to M3U8 request.")
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String, val id: String
    )
}