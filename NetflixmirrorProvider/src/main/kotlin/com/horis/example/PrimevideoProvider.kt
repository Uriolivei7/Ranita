package com.horis.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response

class PrimevideoProvider : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override var mainUrl = "https://net52.cc"
    override var name = "PrimeVideo"
    override val hasMainPage = true

    private val ott = "pv"
    @Volatile private var lastBypassCookie = ""
    private var lastLoadedId = ""

    init {
        Log.e("Netmirror", "PrimevideoProvider init called")
    }

    private fun pvPoster(id: String): String = "https://imgcdn.kim/pv/v/$id.jpg"
    private fun pvBg(id: String): String = "https://imgcdn.kim/pv/h/$id.jpg"
    private fun pvEpPoster(id: String): String = "https://imgcdn.kim/pvepimg/150/$id.jpg"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        if (cookie.length <= 10) { Log.e("Netmirror", "getMainPage: bypass failed"); return null }
        Log.e("Netmirror", "getMainPage cookie=${cookie.take(40)}...")

        val cookies = mobileCookies(cookie, ott)
        val mHeaders = mobileHeaders(ott, cookie, mapOf("Referer" to "$mainUrl/mobile/home?app=1"))

        try {
            val doc = app.get(
                "$mainUrl/mobile/home?app=1",
                headers = mHeaders,
                cookies = cookies,
                referer = "$mainUrl/mobile/home?app=1"
            ).document

            val items = doc.select(".tray-container, #top10").mapNotNull { tray ->
                val name = tray.select("h2, span").text().ifBlank { return@mapNotNull null }
                val results = tray.select("article, .top10-post").mapNotNull { el ->
                    val id = el.selectFirst("a")?.attr("data-post")?.ifBlank { null } ?: el.attr("data-post").ifBlank { null } ?: return@mapNotNull null
                    newAnimeSearchResponse("", NewTvId(id).toJson()) {
                        posterUrl = pvPoster(id)
                        posterHeaders = mapOf("Referer" to "$mainUrl/home")
                    }
                }
                if (results.isEmpty()) return@mapNotNull null
                HomePageList(name, results, isHorizontalImages = false)
            }
            Log.e("Netmirror", "getMainPage: ${items.size} categories")
            return newHomePageResponse(items, hasNext = false)
        } catch (e: Exception) {
            Log.e("Netmirror", "getMainPage failed: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.e("Netmirror", "search called query=$query")
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        if (cookie.length <= 10) { Log.e("Netmirror", "search: bypass failed"); return emptyList() }

        val cookies = mobileCookies(cookie, ott)
        val mHeaders = mobileHeaders(ott, cookie, mapOf("Referer" to "$mainUrl/home"))

        try {
            val url = "$mainUrl/mobile/pv/search.php?s=${URLEncoder.encode(query, "UTF-8")}&t=${System.currentTimeMillis()}"
            val data = app.get(url, headers = mHeaders, cookies = cookies, referer = "$mainUrl/home")
                .parsed<MobileSearchData>()
            return data.searchResult.orEmpty().map { item ->
                newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                    posterUrl = pvPoster(item.id)
                    posterHeaders = mapOf("Referer" to "$mainUrl/home")
                }
            }
        } catch (e: Exception) {
            Log.e("Netmirror", "search failed: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = parseJson<NewTvId>(url).id
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        if (cookie.length <= 10) { Log.e("Netmirror", "load: bypass failed"); return null }

        val cookies = mobileCookies(cookie, ott)
        val mHeaders = mobileHeaders(ott, cookie, mapOf("Referer" to "$mainUrl/home"))

        val rawResponse = app.get(
            "$mainUrl/mobile/pv/post.php?id=$id&t=${System.currentTimeMillis()}",
            headers = mHeaders,
            cookies = cookies,
            referer = "$mainUrl/home"
        ).text
        Log.d("Netmirror", "RAW mobile post response: $rawResponse")
        val data = fromJson<MobilePostData>(rawResponse)

        val title = data.title ?: id
        val cast = data.cast?.split(",")?.map { it.trim() }?.map { ActorData(Actor(it)) } ?: emptyList()
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime ?: "")
        val isSeries = data.episodes?.any { it != null } == true

        // Build audio language list
        val audioNames = data.lang?.mapNotNull { lang -> lang.l?.takeIf { it.isNotBlank() } }
        val audioInfo = if (audioNames.isNullOrEmpty()) null else audioNames.joinToString(", ")
        val enhancedPlot = buildString {
            append(data.desc ?: "")
            if (audioInfo != null) {
                append("\n\nAudio: $audioInfo")
            }
        }

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = pvPoster(it.id)
            }
        }

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, NewTvLoadData(title, id).toJson()) {
                posterUrl = pvPoster(id)
                backgroundPosterUrl = pvBg(id)
                plot = enhancedPlot; year = data.year?.toIntOrNull(); this.tags = genre
                actors = cast; this.score = Score.from10(rating); duration = runTime
                recommendations = suggest
                contentRating = data.ua
            }
        }

        val episodes = arrayListOf<Episode>()

        // Load ALL seasons from page 1
        data.season?.forEach { season ->
            if (!season.id.isNullOrBlank())
                episodes.addAll(getEpisodes(title, season.id, 1))
        }

        // If no seasons, try the direct episode list
        if (episodes.isEmpty() && !data.episodes.isNullOrEmpty()) {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    posterUrl = pvEpPoster(it.id)
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = pvPoster(id)
            backgroundPosterUrl = pvBg(id)
            plot = enhancedPlot; year = data.year?.toIntOrNull(); this.tags = genre
            actors = cast; this.score = Score.from10(rating); duration = runTime
            recommendations = suggest
            contentRating = data.ua
        }
    }

    private suspend fun getEpisodes(
        title: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        if (cookie.length <= 10) return episodes
        val cookies = mobileCookies(cookie, ott)
        val mHeaders = mobileHeaders(ott, cookie, mapOf("Referer" to "$mainUrl/home"))
        var pg = page
        while (true) {
            val rawEp = app.get(
                "$mainUrl/mobile/pv/episodes.php?s=$sid&series=$title&t=${System.currentTimeMillis()}&page=$pg",
                headers = mHeaders,
                cookies = cookies,
                referer = "$mainUrl/home"
            ).text
            Log.d("Netmirror", "RAW episodes page=$pg: $rawEp")
            val data = fromJson<MobileEpisodesData>(rawEp)

            data.episodes.orEmpty().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    posterUrl = pvEpPoster(it.id)
                    this.runTime = it.time.replace("m", "").toIntOrNull()
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
                var cookie = lastBypassCookie
                if (cookie.isBlank()) {
                    cookie = NetflixMirrorStorage.getCookie().first ?: ""
                }
                val rawCookie = try {
                    java.net.URLDecoder.decode(cookie, "UTF-8")
                } catch (_: Exception) {
                    cookie.replace("%3A%3A", "::")
                }
                val newRequest = request.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Referer", "https://net52.cc/")
                    .header("Cookie", "t_hash_t=$rawCookie; hd=on; ott=pv")
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
        val id = parseJson<NewTvLoadData>(data).id

        if (id != lastLoadedId) {
            NetflixMirrorStorage.clearCookie()
            lastLoadedId = id
        }
        val cookie = try { bypass(mainUrl) } catch (_: Exception) { "" }
        lastBypassCookie = cookie
        Log.d("Netmirror", "loadLinks id=$id cookie=$cookie")

        for (domain in listOf("https://net52.cc", "https://net22.cc")) {
            try {
                val resp = app.get("$domain/mobile/pv/playlist.php?id=$id", headers = mobileHeaders(ott, cookie))
                val text = resp.text
                Log.d("Netmirror", "playlist $domain raw=${text.take(200)}")
                val items = tryParseJsonList<PlaylistItem>(text)
                val src = items?.firstOrNull()?.sources?.firstOrNull()?.file
                if (!src.isNullOrBlank()) {
                    val fixedSrc = src
                        .replace("in=unknown::ep", "in=$cookie")
                        .replace("in=unknown%3A%3Aep", "in=$cookie")
                        .replace("::ep::99", "::ep::m")
                        .replace("%3A%3Aep%3A%3A99", "%3A%3Aep%3A%3Am")

                    val m3u8 = (if (fixedSrc.startsWith("http")) fixedSrc else "$domain$fixedSrc") + "&_t=${System.currentTimeMillis()}"
                    Log.e("Netmirror", "URL M3U8 Base Enviada: $m3u8")

                    // Parse subtitle tracks from JSON response
                    items.firstOrNull()?.tracks.orEmpty().forEach { t ->
                        if (t.kind == "captions" && !t.file.isNullOrBlank()) {
                            val subLang = t.label?.substringBefore(" [")?.lowercase() ?: "und"
                            val subUrl = if (t.file.startsWith("//")) "https:${t.file}" else t.file
                            Log.d("Netmirror", "subtitle lang=$subLang url=$subUrl")
                            subtitleCallback(newSubtitleFile(subLang, subUrl))
                        }
                    }

                    callback(newExtractorLink(name, name, m3u8, type = ExtractorLinkType.M3U8) {
                        referer = "$domain/"
                    })
                    return true
                }
            } catch (e: Exception) {
                Log.e("Netmirror", "$domain exception en loadLinks: ${e.message}")
            }
        }
        return false
    }

}