package com.horis.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response

class JioHotstarProvider : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://net52.cc"
    override var name = "JioHotstar"
    override val hasMainPage = true

    private val ott = "hs"
    private var lastLoadedId = ""

    private val androidHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/149.0.7827.91 Safari/537.36 /OS.Gatu v3.0",
        "Accept" to "*/*",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Connection" to "keep-alive",
        "X-Requested-With" to "app.netmirror.netmirrornew",
        "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Cache-Control" to "max-age=0"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiBase = try { resolveApiUrl() } catch (_: Exception) { mainUrl }
        for (base in listOf(apiBase, mainUrl).distinct()) {
            try {
                val response = app.get(
                    "$base/newtv/main.php",
                    headers = buildNewTvHeaders(ott, mapOf("Page" to "all", "Recentplay" to "", "Watchlist" to "", "Usertoken" to ""))
                ).parsed<NewTvMainResponse>()
                val items = response.post.orEmpty().map { category ->
                    val ids = category.ids?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    val template = response.imgcdn_v.takeUnless { it.isNullOrBlank() } ?: response.imgcdn_h
                    val results = ids.mapNotNull { id ->
                        newAnimeSearchResponse("", NewTvId(id).toJson()) {
                            posterUrl = buildPosterUrl(template, id) ?: buildVerticalPosterUrl(id, ott)
                        }
                    }
                    HomePageList(category.cate.orEmpty(), results, isHorizontalImages = false)
                }
                return newHomePageResponse(items, hasNext = items.isNotEmpty())
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiBase = try { resolveApiUrl() } catch (_: Exception) { mainUrl }
        for (base in listOf(apiBase, mainUrl).distinct()) {
            try {
                val data = app.get(
                    "$base/newtv/search.php?s=${URLEncoder.encode(query, "UTF-8")}",
                    headers = buildNewTvHeaders(ott)
                ).parsed<NewTvSearchResponse>()
                val template = data.detailsimgcdn ?: data.imgcdn
                return data.searchResult.orEmpty().map { item ->
                    newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                        posterUrl = buildPosterUrl(template, item.id) ?: buildVerticalPosterUrl(item.id, ott)
                    }
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiBase = try { resolveApiUrl() } catch (_: Exception) { mainUrl }
        val id = parseJson<NewTvId>(url).id

        val rawResponse = retryOnDbError {
            val text = app.get(
                "$apiBase/newtv/post.php?id=$id",
                headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to ""))
            ).text
            checkDbError(text)
            text
        }
        val data = fromJson<NewTvPostResponse>(rawResponse)

        val title = data.title ?: id
        val playbackId = data.main_id ?: id
        val cast = data.cast?.split(",")?.map { it.trim() }?.map { ActorData(Actor(it)) } ?: emptyList()
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime ?: "")
        val isSeries = data.type == "t" || data.episodes?.any { it != null } == true
        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = buildVerticalPosterUrl(it.id, ott)
            }
        }

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, NewTvLoadData(title, playbackId).toJson()) {
                posterUrl = buildPosterUrl(data.main_poster, id) ?: buildVerticalPosterUrl(id, ott)
                backgroundPosterUrl = buildBackgroundPosterUrl(id, ott)
                plot = data.desc; year = data.year?.toIntOrNull(); tags = genre
                actors = cast; this.score = Score.from10(rating); duration = runTime
                recommendations = suggest
            }
        }

        val episodes = arrayListOf<Episode>()

        if (data.episodes.isNullOrEmpty()) {
            if (data.type != "t") episodes.add(newEpisode(NewTvLoadData(title, playbackId)) { name = title })
        } else {
            val selectedSeasonIdx = data.season?.indexOfFirst { it.selected == true }?.takeIf { it >= 0 }
            val selectedSeasonId = selectedSeasonIdx?.let { data.season?.getOrNull(it)?.id } ?: data.nextPageSeason
            val selectedSeasonNumber = selectedSeasonIdx?.plus(1)

            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id.orEmpty())) {
                    this.name = it.t
                    episode = it.ep?.toIntOrNull() ?: it.epNum?.replace("E", "").orEmpty().toIntOrNull()
                    season = selectedSeasonNumber ?: it.sNum?.replace("S", "").orEmpty().toIntOrNull()
                    posterUrl = buildPosterUrl(data.ep_poster, it.id.orEmpty()) ?: buildVerticalPosterUrl(it.id.orEmpty(), ott)
                    this.runTime = it.timeVal?.replace("m", "").orEmpty().toIntOrNull()
                    description = it.ep_desc
                }
            }

            if (data.nextPageShow == 1 && !selectedSeasonId.isNullOrBlank())
                episodes.addAll(getEpisodes(title, selectedSeasonId, 2, data.ep_poster, selectedSeasonNumber))

            data.season?.forEachIndexed { index, season ->
                if (season.id != selectedSeasonId && !season.id.isNullOrBlank())
                    episodes.addAll(getEpisodes(title, season.id, 1, data.ep_poster, index + 1))
            }
        }

        if (data.type == "t" && episodes.isEmpty() && !data.season.isNullOrEmpty()) {
            data.season.forEachIndexed { index, season ->
                if (!season.id.isNullOrBlank())
                    episodes.addAll(getEpisodes(title, season.id, 1, data.ep_poster, index + 1))
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = buildPosterUrl(data.main_poster, id) ?: buildVerticalPosterUrl(id, ott)
            backgroundPosterUrl = buildBackgroundPosterUrl(id, ott)
            plot = data.desc; year = data.year?.toIntOrNull(); tags = genre
            actors = cast; this.score = Score.from10(rating); duration = runTime
            recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, sid: String, page: Int,
        epPoster: String? = null, seasonNumber: Int? = null
    ): List<Episode> {
        val apiBase = try { resolveApiUrl() } catch (_: Exception) { mainUrl }
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val rawEp = retryOnDbError {
                val text = app.get(
                    "$apiBase/newtv/episodes.php",
                    params = mapOf("id" to sid, "page" to pg.toString()),
                    headers = buildNewTvHeaders(ott)
                ).text
                checkDbError(text)
                text
            }
            val data = fromJson<NewTvEpisodesResponse>(rawEp)

            data.episodes.orEmpty().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id.orEmpty())) {
                    name = it.t
                    episode = it.ep?.toIntOrNull() ?: it.epNum?.replace("E", "").orEmpty().toIntOrNull()
                    season = seasonNumber ?: it.sNum?.replace("S", "").orEmpty().toIntOrNull()
                    posterUrl = buildPosterUrl(epPoster, it.id.orEmpty()) ?: buildVerticalPosterUrl(it.id.orEmpty(), ott)
                    this.runTime = it.timeVal?.replace("m", "").orEmpty().toIntOrNull()
                    description = it.ep_desc
                }
            }

            if (data.nextPageShow != 1) break
            pg++
        }
        return episodes
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val cookie = NetflixMirrorStorage.getCookie().first ?: ""
                val rawCookie = try {
                    java.net.URLDecoder.decode(cookie, "UTF-8")
                } catch (_: Exception) {
                    cookie.replace("%3A%3A", "::")
                }
                val newRequest = request.newBuilder()
                    .header("Cookie", if (rawCookie.isNotBlank()) "t_hash_t=$rawCookie; hd=on" else "hd=on")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .build()
                return chain.proceed(newRequest)
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = try { resolveApiUrl() } catch (_: Exception) { mainUrl }
        val id = parseJson<NewTvLoadData>(data).id
        Log.d("JioHotstar", "loadLinks id=$id apiBase=$apiBase")

        // Force fresh bypass when episode changes
        if (id != lastLoadedId) {
            NetflixMirrorStorage.clearCookie()
            NetflixMirrorStorage.clearFullCookie()
            lastLoadedId = id
        }
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        val cookies = mapOf("Cookie" to "t_hash_t=$cookie; hd=on")
        val mHeaders = mobileHeaders(ott, cookie)

        // Primary: player.php
        val userToken = try { getNewTvUserToken(apiBase, ott) } catch (e: Exception) { Log.d("JioHotstar", "getNewTvUserToken failed: ${e.message}"); "" }
        val playerHeaders = buildNewTvHeaders(ott, mapOf("Usertoken" to userToken)) + cookies
        val playerResp = app.get("$apiBase/newtv/player.php?id=$id", headers = playerHeaders)
            .parsed<NewTvPlayerResponse>()
        if (playerResp.status == "ok" && !playerResp.video_link.isNullOrBlank()) {
            val m3u8 = playerResp.video_link + (if (playerResp.video_link.contains("?")) "&_t=${System.currentTimeMillis()}" else "?_t=${System.currentTimeMillis()}")
            callback(newExtractorLink(name, name, m3u8, type = ExtractorLinkType.M3U8) {
                referer = playerResp.referer ?: apiBase
            })
            return true
        }

        Log.d("JioHotstar", "player.php status=${playerResp.status}, trying playlist.php fallback")
        val playResp = app.get("$apiBase/mobile/hs/playlist.php?id=$id", headers = mHeaders, cookies = mapOf("t_hash_t" to cookie, "hd" to "on", "ott" to ott))
        val playText = playResp.text
        Log.d("JioHotstar", "playlist.php raw=${playText.take(300)}")
        val items = tryParseJsonList<PlaylistItem>(playText)
        val src = items?.firstOrNull()?.sources?.firstOrNull()?.file
        if (src != null) {
            val m3u8 = (if (src.startsWith("http")) src else "${apiBase}${src}") + (if (src.contains("?")) "&_t=${System.currentTimeMillis()}" else "?_t=${System.currentTimeMillis()}")
            callback(newExtractorLink(name, name, m3u8, type = ExtractorLinkType.M3U8) {
                referer = apiBase
            })
            return true
        }

        Log.d("JioHotstar", "all playback methods failed id=$id")
        return false
    }
}