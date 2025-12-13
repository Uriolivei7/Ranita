package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response

class XtreamProvider(mainUrl: String, name: String, username: String, password: String) : MainAPI() {
    override var mainUrl = mainUrl
    override var name = name
    override val hasMainPage = true
    override var lang = "mx"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.Live
    )

    var items = mutableMapOf<String, List<Stream>>()
    val apiURL = "${mainUrl}/player_api.php?username=${username}&password=${password}"
    val serverUrlWithData = "${mainUrl}/${username}/${password}/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<HomePageList>()

        val categories = parseJson<List<Category>>(loadLiveCategories())
        items[name] = parseJson<List<Stream>>(loadLiveStreams())

        categories.map { it ->
            val streamLists = mutableListOf<SearchResponse>()
            items[name]?.map { stream ->
                if (stream.category_id == it.category_id) {
                    streamLists.add(
                        newLiveSearchResponse(
                            name = stream.name,
                            url = Data(
                                num = stream.num,
                                name = stream.name,
                                stream_type = stream.stream_type,
                                stream_id = stream.stream_id,
                                stream_icon = stream.stream_icon,
                                epg_channel_id = stream.epg_channel_id,
                                added = stream.added,
                                is_adult = stream.is_adult,
                                category_id = stream.category_id,
                                custom_sid = stream.custom_sid,
                                tv_archive = stream.tv_archive,
                                direct_source = stream.direct_source,
                                tv_archive_duration = stream.tv_archive_duration,
                            ).toJson(),
                            type = TvType.Live,
                        ) {
                            posterUrl = stream.stream_icon
                        }
                    )
                }
            }
            home.add(HomePageList(it.category_name, streamLists, isHorizontalImages = true))
        }

        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (items[name] == null) {
            items[name] = parseJson<List<Stream>>(loadLiveStreams())
        }

        return items[name]!!.filter { it.name.toString().lowercase().contains(query.lowercase()) }.map { item ->
            newLiveSearchResponse(
                name = item.name,
                url = Data(
                    num = item.num,
                    name = item.name,
                    stream_type = item.stream_type,
                    stream_id = item.stream_id,
                    stream_icon = item.stream_icon,
                    epg_channel_id = item.epg_channel_id,
                    added = item.added,
                    is_adult = item.is_adult,
                    category_id = item.category_id,
                    custom_sid = item.custom_sid,
                    tv_archive = item.tv_archive,
                    direct_source = item.direct_source,
                    tv_archive_duration = item.tv_archive_duration,
                ).toJson(),
                type = TvType.Live,
            ) {
                posterUrl = item.stream_icon
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<Data>(url)
        return newMovieLoadResponse(data.name, url, TvType.Live, url) {
            this.posterUrl = data.stream_icon
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsedData = parseJson<Data>(data)
        val response = checkLinkType(serverUrlWithData + parsedData.stream_id.toString())
        val isM3u8Value = response == "m3u8"

        callback.invoke(
            newExtractorLink(
                source = parsedData.name,
                name = name,
                url = serverUrlWithData + parsedData.stream_id.toString(),
            ) {
                referer = ""
                quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun loadLiveCategories(): String {
        return app.get("$apiURL&action=get_live_categories").body.string()
    }

    private suspend fun loadLiveStreams(): String {
        return app.get("$apiURL&action=get_live_streams").body.string()
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()

                return chain.proceed(request)
            }
        }
    }
}