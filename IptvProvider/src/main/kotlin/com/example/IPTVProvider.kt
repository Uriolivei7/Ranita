package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID
import android.util.Log

class IPTVProvider(mainUrl: String, name: String) : MainAPI() {
    override var mainUrl = mainUrl
    override var name = name
    override val hasMainPage = true
    override var lang = "mx"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Live
    )

    val items = mutableMapOf<String, Playlist?>()
    val headers = mapOf("User-Agent" to "Player (Linux; Android 14)")

    companion object {
        private const val LOG_TAG = "IPTV_POSTER"
    }

    private fun getSafePosterUrl(url: String?): String {
        val isInvalid = url.isNullOrBlank() ||
                url == "null" ||
                !url.startsWith("http", ignoreCase = true)

        val finalUrl = if (isInvalid) {
            Log.d(LOG_TAG, "Original URL '$url' es INVÁLIDA o no HTTP. Usando URL VACÍA.")
            ""
        } else {
            Log.d(LOG_TAG, "Original URL '$url' es VÁLIDA. Usando URL original.")
            url
        }

        return finalUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        items[name] = IptvPlaylistParser().parseM3U(app.get(mainUrl, headers = headers).text)

        return newHomePageResponse(
            items[name]!!.items.groupBy { it.attributes["group-title"] }.map { group ->
                val title = group.key ?: ""
                val show = group.value.map { item ->
                    val streamurl = item.url.toString()
                    val channelname = item.title.toString()
                    val posterurl = getSafePosterUrl(item.attributes["tvg-logo"].toString())
                    Log.d(LOG_TAG, "MAIN PAGE | Canal: $channelname | Póster final: $posterurl")
                    val chGroup = item.attributes["group-title"].toString()
                    val key = item.attributes["key"].toString()
                    val keyid = item.attributes["keyid"].toString()

                    newLiveSearchResponse(
                        name = channelname,
                        url = LoadData(streamurl, channelname, posterurl, chGroup, key, keyid).toJson(),
                        type = TvType.Live
                    ) {
                        posterUrl = posterurl
                    }
                }

                HomePageList(title, show, isHorizontalImages = true)
            },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (items[name] == null) {
            items[name] = IptvPlaylistParser().parseM3U(app.get(mainUrl, headers = headers).text)
        }

        return items[name]!!.items.filter { it.title.toString().lowercase().contains(query.lowercase()) }.map { item ->
            val streamurl = item.url.toString()
            val channelname = item.title.toString()
            val posterurl = getSafePosterUrl(item.attributes["tvg-logo"].toString())
            Log.d(LOG_TAG, "SEARCH | Canal: $channelname | Póster final: $posterurl")
            val chGroup = item.attributes["group-title"].toString()
            val key = item.attributes["key"].toString()
            val keyid = item.attributes["keyid"].toString()

            newLiveSearchResponse(
                name = channelname,
                url = LoadData(streamurl, channelname, posterurl, chGroup, key, keyid).toJson(),
                type = TvType.Live
            ) {
                posterUrl = posterurl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val loadData = parseJson<LoadData>(url)
        if (items[name] == null) {
            items[name] = IptvPlaylistParser().parseM3U(app.get(mainUrl, headers = headers).text)
        }
        val recommendations = mutableListOf<LiveSearchResponse>()
        for (item in items[name]!!.items) {
            if (recommendations.size >= 24) break
            if (item.attributes["group-title"].toString() == loadData.group) {
                val rcStreamUrl = item.url.toString()
                val rcChannelName = item.title.toString()
                if (rcChannelName == loadData.title) continue
                val rcPosterUrl = getSafePosterUrl(item.attributes["tvg-logo"].toString())
                Log.d(LOG_TAG, "REC | Canal Rec: $rcChannelName | Póster final: $rcPosterUrl")
                val rcChGroup = item.attributes["group-title"].toString()
                val key = item.attributes["key"].toString()
                val keyid = item.attributes["keyid"].toString()

                recommendations.add(newLiveSearchResponse(
                    name = rcChannelName,
                    url = LoadData(rcStreamUrl, rcChannelName, rcPosterUrl, rcChGroup, key, keyid).toJson(),
                    type = TvType.Live
                ) {
                    posterUrl = rcPosterUrl
                })
            }
        }

        return newLiveStreamLoadResponse(
            name = loadData.title,
            url = url,
            dataUrl = url,
        ) {
            posterUrl = loadData.poster
            recommendations.addAll(recommendations)
            this.recommendations = recommendations
            contentRating = null
            type = TvType.Live
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = parseJson<LoadData>(data)
        val item = items[name]!!.items.first { it.url == loadData.url }
        val response = checkLinkType(loadData.url, item.headers)
        val isM3u8 = if (response == "m3u8") true else false
        if (loadData.url.contains(".mpd")) {
            callback.invoke(
                newDrmExtractorLink(
                    source = loadData.title,
                    name = this.name,
                    url = loadData.url,
                    uuid = UUID.randomUUID(),
                ) {
                    kid = loadData.keyid.toString().trim()
                    key = loadData.key.toString().trim()
                }
            )
        } else {
            callback.invoke(
                newExtractorLink(
                    source = loadData.title,
                    name = this.name,
                    url = loadData.url,
                ) {
                    headers = item.headers
                    referer = item.headers["referrer"] ?: ""
                }
            )
        }
        return true
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
