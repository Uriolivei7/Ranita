package com.horis.example

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
import android.util.Log

class NetflixProvider : MainAPI() {
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"
    override var mainUrl = "https://net20.cc"
    private var newUrl = "https://net51.cc"
    override var name = "Netflix"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Connection" to "keep-alive",
        "Host" to "net51.cc",
        "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Android WebView\";v=\"139\", \"Chromium\";v=\"139\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/139.0.7258.158 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to ""
    )

    private val TAG = "NetflixProvider"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "nf",
            "hd" to "on"
        )
        val document = app.get(
            "https://net51.cc/mobile/home?app=1",
            headers = headers,
            cookies = cookies,
            referer = "https://net51.cc/",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = attr("data-post").takeIf { it.isNotEmpty() }
            ?: run {
                val imgSrc = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src") ?: ""
                imgSrc.substringAfterLast("/").substringBefore(".")
            }

        if (id.isEmpty()) return null

        val posterUrl = "https://imgcdn.kim/poster/v/${id}.jpg"
        val title = selectFirst("img")?.attr("alt") ?: ""

        return newAnimeSearchResponse(title, Id(id).toJson()) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.i(TAG, "Starting search for query: $query")
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        val url = "$mainUrl/search.php?s=$query&t=${APIHolder.unixTime}"
        Log.i(TAG, "Search URL: $url")

        val data = app.get(
            url,
            referer = "$mainUrl/tv/home",
            cookies = cookies
        ).parsed<SearchData>()

        Log.i(TAG, "Search query '$query' returned ${data.searchResult.size} results.")

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
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
            "ott" to "nf",
            "hd" to "on"
        )
        val postUrl = "$newUrl/post.php?id=$id&t=${APIHolder.unixTime}"
        Log.i(TAG, "Fetching post data from: $postUrl")

        val data = app.get(
            postUrl,
            headers,
            referer = "https://net51.cc",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        Log.i(TAG, "Title loaded: $title, Type check: ${if (data.episodes.first() == null) "Movie" else "Series"}")

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
                this.posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

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
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
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
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            backgroundPosterUrl ="https://imgcdn.kim/poster/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
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
            "ott" to "nf",
            "hd" to "on"
        )
        var pg = page
        while (true) {
            val epUrl = "$newUrl/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg"
            Log.i(TAG, "Fetching episodes page $pg for SID: $sid")

            val data = app.get(
                epUrl,
                headers,
                referer = "$newUrl/tv/home",
                cookies = cookies
            ).parsed<EpisodesData>()

            val newEpsCount = data.episodes?.size ?: 0
            Log.i(TAG, "Fetched $newEpsCount episodes from page $pg.")

            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
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
            "ott" to "nf",
            "hd" to "on"
        )
        val playlistUrl = "$newUrl/tv/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}"
        Log.i(TAG, "Fetching playlist from: $playlistUrl")

        val playlist = app.get(
            playlistUrl,
            headers,
            referer = "$mainUrl/tv/home",
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
                        """$newUrl${it.file.replace("/tv/", "/")}""",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$newUrl/"
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

    data class Cookie(
        val cookie: String
    )
}