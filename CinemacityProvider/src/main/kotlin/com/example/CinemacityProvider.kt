package com.example

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class CinemacityProvider : MainAPI() {
    override var mainUrl = "https://cinemacity.cc"
    override var name = "CinemaCity"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.AsianDrama, TvType.Anime
    )

    private var dynamicCookies: Map<String, String> = mapOf(
        "dle_user_id" to "32729",
        "dle_password" to "894171c6a8dab18ee594d5c652009a35"
    )

    private val protectionHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val cfKiller = CloudflareKiller()

    private suspend fun doRequest(url: String): NiceResponse {
        return app.get(
            url,
            headers = protectionHeaders + ("Referer" to "$mainUrl/"),
            cookies = dynamicCookies,
            interceptor = cfKiller
        ).also {
            if (it.cookies.isNotEmpty()) dynamicCookies = dynamicCookies + it.cookies
        }
    }

    private val tmdbPosterCache = mutableMapOf<String, String>()

    private suspend fun tmdbSearchCached(cleanTitle: String): String? {
        val key = cleanTitle.lowercase().trim()
        tmdbPosterCache[key]?.let { Log.d("Cinemacity", "tmdbCache HIT for '$key' -> ${tmdbPosterCache[key]}"); return it }
        val result = runCatching {
            val enc = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val resp = app.get(
                "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$enc",
                timeout = 8000L
            )
            val tmdbArr = JSONObject(resp.text).optJSONArray("results") ?: JSONArray()
            val limit = if (tmdbArr.length() > 3) 3 else tmdbArr.length()
            for (i in 0 until limit) {
                val item = tmdbArr.getJSONObject(i)
                val tmdbTitle =
                    (item.optString("title").takeIf { it.isNotBlank() } ?: item.optString("name")
                    ?: "").lowercase().trim()
                val path = item.optString("poster_path") ?: continue
                if (path.isNotBlank() && (tmdbTitle == key || key.contains(tmdbTitle) || tmdbTitle.contains(
                        key
                    ))
                ) {
                    Log.d("Cinemacity", "tmdbSearch: '$key' matched '$tmdbTitle' -> $path")
                    return "$TMDBIMAGEBASEURL$path".also { tmdbPosterCache[key] = it }
                }
            }
            tmdbArr.optJSONObject(0)?.optString("poster_path")?.takeIf { it.isNotBlank() }
                ?.let { "$TMDBIMAGEBASEURL$it".also { p -> Log.d("Cinemacity", "tmdbSearch: '$key' fallback -> $p"); tmdbPosterCache[key] = p } }
        }.getOrNull()
        if (result == null) Log.d("Cinemacity", "tmdbSearch: '$key' no result")
        return result
    }

    private fun cleanForTmdb(name: String): String {
        return name.split(" /", " (", " -")[0].trim()
            .replace(
                Regex(
                    "\\b(extended edition|director'?s cut|uncut|unrated|theatrical cut|ultimate edition|collector'?s edition|special edition|limited edition)s?\$",
                    RegexOption.IGNORE_CASE
                ), ""
            ).trim()
            .lowercase()
    }

    private suspend fun enrichTmdbPosters(results: List<SearchResponse>) {
        if (results.isEmpty()) return
        val cleaned = results.map { cleanForTmdb(it.name) }.distinct()
        coroutineScope {
            cleaned.map { clean -> async { tmdbSearchCached(clean) } }.awaitAll()
        }
        results.forEach { sr ->
            val clean = cleanForTmdb(sr.name)
            tmdbPosterCache[clean]?.let { sr.posterUrl = it }
        }
    }

    companion object {
        private const val TMDBIMAGEBASEURL = "https://image.tmdb.org/t/p/original"
        private const val cinemeta_url =
            "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb/meta"
    }

    fun parseCredits(jsonText: String?): List<ActorData> {
        if (jsonText.isNullOrBlank()) return emptyList()
        val list = ArrayList<ActorData>()
        val root = JSONObject(jsonText)
        val castArr = root.optJSONArray("cast") ?: return list
        for (i in 0 until castArr.length()) {
            val c = castArr.optJSONObject(i) ?: continue
            val name =
                c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name")
                    .orEmpty()
            val profile = c.optString("profile_path").takeIf { it.isNotBlank() }
                ?.let { "$TMDBIMAGEBASEURL$it" }
            val character = c.optString("character").takeIf { it.isNotBlank() }
            val actor = Actor(name, profile)
            list += ActorData(actor, roleString = character)
        }
        return list
    }

    override val mainPage = mainPageOf(
        "$mainUrl/tv-series/" to "Series",
        "$mainUrl/movies/" to "Movies",
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else "$base/"
        val doc = doRequest(url).document
        val home = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("a[href*='/page/'], .pnext, .next").isNotEmpty()
        enrichTmdbPosters(home)
        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.select("a").firstOrNull {
            val h = it.attr("href")
            (h.contains("/movies/") || h.contains("/tv-series/")) && !h.contains(Regex("\\.(webp|jpg|png)"))
        } ?: return null

        val title = link.text().split(" (", " S0", " -")[0].trim()
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val imgSrc = img?.attr("src")
        val imgDataSrc = img?.attr("data-src")
        val poster = fixUrlNull(imgSrc) ?: fixUrlNull(imgDataSrc)
        Log.d(
            "Cinemacity",
            "SearchResult: title='$title', img=$img, src='$imgSrc', data-src='$imgDataSrc', poster='$poster'"
        )
        val isTv = href.contains("/tv-series/")
        val score = this.selectFirst("span.rating-color")?.text()
        val date = this.selectFirst("span a[href*=year]")?.text()?.toIntOrNull()

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(score)
                this.year = date
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.score = Score.from10(score)
                this.year = date
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val formData = mapOf("do" to "search", "subaction" to "search", "story" to query)
        val resp = app.post(
            mainUrl,
            headers = protectionHeaders + ("Referer" to "$mainUrl/") + ("X-Requested-With" to "XMLHttpRequest"),
            cookies = dynamicCookies,
            data = formData,
            timeout = 15000L,
            interceptor = cfKiller
        ).also {
            if (it.cookies.isNotEmpty()) dynamicCookies = dynamicCookies + it.cookies
        }

        if (resp.code != 200) {
            Log.w("Cinemacity", "Search: status ${resp.code}")
            return null
        }

        val results = resp.document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        Log.d("Cinemacity", "Search: query='$query', total items=${results.size}")
        enrichTmdbPosters(results)
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val formData = mapOf("do" to "search", "subaction" to "search", "story" to query)
        val resp = app.post(
            mainUrl,
            headers = protectionHeaders + ("Referer" to "$mainUrl/") + ("X-Requested-With" to "XMLHttpRequest"),
            cookies = dynamicCookies,
            data = formData,
            timeout = 15000L,
            interceptor = cfKiller
        ).also {
            if (it.cookies.isNotEmpty()) dynamicCookies = dynamicCookies + it.cookies
        }
        if (resp.code != 200) return null
        return resp.document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
    }


    override suspend fun load(url: String): LoadResponse {
        val page = doRequest(url)
        val doc = page.document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = ogTitle.substringBefore("(").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val bgposter = doc.selectFirst("div.dar-full_bg a")?.attr("href")
        val trailer = doc.select("div.dar-full_bg.e-cover > div").attr("data-vbg")

        val audioLanguages = doc
            .select("li")
            .firstOrNull {
                it.selectFirst("span")?.text()
                    ?.equals("Audio language", ignoreCase = true) == true
            }
            ?.select("span:eq(1) a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")

        val descriptions = doc.selectFirst("#about div.ta-full_text1")?.text()

        val recommendation = doc.select("div.ta-rel > div.ta-rel_item").map {
            val recTitle = it.select("a").text().substringBefore("(").trim()
            val recHref = fixUrl(it.selectFirst("> div > a")?.attr("href") ?: "")
            val recImg = it.selectFirst("img")
            val recImgSrc = recImg?.attr("src")
            val recImgDataSrc = recImg?.attr("data-src")
            val recPosterUrl = fixUrlNull(recImgSrc) ?: fixUrlNull(recImgDataSrc)
            Log.d(
                "Cinemacity",
                "Recommendation: title='$recTitle', img=$recImg, src='$recImgSrc', data-src='$recImgDataSrc', poster='$recPosterUrl'"
            )

            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }
        enrichTmdbPosters(recommendation)

        val year = ogTitle.substringAfter("(", "").substringBefore(")").toIntOrNull()
        val contenttype = doc.select("div.dar-full_meta > span:nth-child(5) > a").text()

        val tvtype = if (url.contains("/movies/", true)) TvType.Movie else TvType.TvSeries
        val tmdbmetatype = if (tvtype == TvType.TvSeries) "tv" else "movie"

        var genre: List<String>? = null
        var background: String? = null
        var description: String? = null


        val imdbId = doc
            .select("div.ta-full_rating1 > div")
            .mapNotNull { it.attr("onclick") }
            .firstNotNullOfOrNull { Regex("tt\\d+").find(it)?.value }

        val tmdbId = imdbId?.let { id ->
            runCatching {
                val obj = JSONObject(
                    app.get(
                        "https://api.themoviedb.org/3/find/$id" +
                                "?api_key=1865f43a0549ca50d341dd9ab8b29f49" +
                                "&external_source=imdb_id"
                    ).textLarge
                )

                obj.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")
                    ?.takeIf { it != 0 }
                    ?: obj.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")
                        ?.takeIf { it != 0 }
            }.getOrNull()?.toString()
        }

        val logoPath = imdbId?.let {
            "https://live.metahub.space/logo/medium/$it/img"
        }

        val creditsJson = tmdbId?.let {
            runCatching {
                app.get(
                    "https://api.themoviedb.org/3/$tmdbmetatype/$it/credits" +
                            "?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=en-US"
                ).textLarge
            }.getOrNull()
        }

        val castList = parseCredits(creditsJson)

        val tmdbPoster = tmdbId?.let { id ->
            runCatching {
                val obj = JSONObject(app.get(
                    "https://api.themoviedb.org/3/$tmdbmetatype/$id?api_key=1865f43a0549ca50d341dd9ab8b29f49"
                ).textLarge)
                obj.optString("poster_path").takeIf { it.isNotBlank() }
                    ?.let { "$TMDBIMAGEBASEURL$it" }
            }.getOrNull()
        }
        val typeset = if (tvtype == TvType.TvSeries) "series" else "movie"

        val responseData = imdbId?.takeIf { it.isNotBlank() }?.let {
            val text = app.get("$cinemeta_url/$typeset/$it.json").text
            if (text.startsWith("{")) Gson().fromJson(text, ResponseData::class.java) else null
        }

        responseData?.meta?.let {
            description = it.description ?: descriptions
            background = it.background ?: poster
            genre = it.genres
        }

        val epMetaMap: Map<String, ResponseData.Meta.EpisodeDetails> =
            responseData?.meta?.videos
                ?.filter { it.season != null && it.episode != null }
                ?.associateBy { "${it.season}:${it.episode}" }
                ?: emptyMap()

        val atobScripts = doc.select("script:containsData(atob)")
        val playerScript = atobScripts.getOrNull(1)?.data()

        val fileArray: JSONArray = if (playerScript != null) {
            val b64 = playerScript.substringAfter("atob(\"").substringBefore("\")")
            val decodedPlayer = base64Decode(b64)

            val playerJsonStr = decodedPlayer
                ?.substringAfter("new Playerjs(")
                ?.substringBeforeLast(");")

            if (playerJsonStr.isNullOrBlank()) {
                JSONArray()
            } else {
                val playerJson = JSONObject(playerJsonStr)
                val rawFile = playerJson.opt("file")

                when {
                    rawFile is JSONArray -> rawFile
                    rawFile is String && rawFile.isNotBlank() -> {
                        val value = rawFile.trim()
                        when {
                            value.startsWith("[") && value.endsWith("]") -> JSONArray(value)
                            value.startsWith("{") && value.endsWith("}") -> JSONArray().apply { put(JSONObject(value)) }
                            else -> JSONArray().apply { put(JSONObject().apply { put("file", value) }) }
                        }
                    }
                    else -> JSONArray()
                }
            }
        } else {
            JSONArray()
        }

        if (fileArray.length() == 0) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    fileArray.put(JSONObject().apply { put("file", src) })
                }
            }
            doc.select("video source, source[src*=m3u8], source[src*=mp4]").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    fileArray.put(JSONObject().apply { put("file", src) })
                }
            }
        }

        val seasonRegex = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        val episodeList = mutableListOf<Episode>()

        val movieHrefs: String? = fileArray.optJSONObject(0)
            ?.takeIf { !it.has("folder") }
            ?.optString("file")
            ?.takeIf { it.isNotBlank() }

        val movieSubtitleTracks = parseSubtitles(
            when {
                playerScript != null -> {
                    val decodedPlayer = base64Decode(
                        playerScript.substringAfter("atob(\"").substringBefore("\")")
                    ) ?: ""
                    val str = decodedPlayer
                        .substringAfter("new Playerjs(")
                        .substringBeforeLast(");")
                    if (str.isNotBlank()) {
                        val pj = JSONObject(str)
                        pj.optString("subtitle").takeIf { it.isNotBlank() }
                            ?: fileArray.optJSONObject(0)?.optString("subtitle")?.takeIf { it.isNotBlank() }
                    } else null
                }
                else -> null
            }
        )

        val moviejson = movieHrefs?.let {
            JSONObject().apply {
                put("streamUrl", it)
                put("subtitleTracks", movieSubtitleTracks)
            }.toString()
        }

        if (tvtype == TvType.TvSeries) {
            for (i in 0 until fileArray.length()) {
                val seasonJson = fileArray.getJSONObject(i)

                val seasonNumber = seasonRegex
                    .find(seasonJson.optString("title"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue

                val episodes = seasonJson.optJSONArray("folder") ?: continue
                for (j in 0 until episodes.length()) {
                    val epJson = episodes.getJSONObject(j)

                    val episodeNumber = episodeRegex
                        .find(epJson.optString("title"))
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: continue

                    val streamUrls = mutableListOf<String>()

                    epJson.optString("file")
                        .takeIf { it.isNotBlank() }
                        ?.let { streamUrls += it }

                    epJson.optJSONArray("folder")?.let { sources ->
                        for (k in 0 until sources.length()) {
                            sources.optJSONObject(k)
                                ?.optString("file")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { streamUrls += it }
                        }
                    }

                    if (streamUrls.isEmpty()) continue

                    val metaKey = "$seasonNumber:$episodeNumber"
                    val epMeta = epMetaMap[metaKey]

                    val epSubtitleTracks =
                        parseSubtitles(epJson.optString("subtitle"))

                    val epjson = JSONObject().apply {
                        put("streams", JSONArray(streamUrls))
                        put("subtitleTracks", epSubtitleTracks)
                    }.toString()

                    episodeList += newEpisode(epjson) {
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.name = epMeta?.title ?: "S${seasonNumber}E${episodeNumber}"
                        this.description = epMeta?.overview
                        this.posterUrl = epMeta?.thumbnail
                        addDate(epMeta?.released)
                    }
                }
            }
            return newTvSeriesLoadResponse(
                responseData?.meta?.name ?: title,
                url,
                TvType.TvSeries,
                episodeList
            ) {
                this.backgroundPosterUrl = background ?: bgposter
                this.posterUrl = tmdbPoster ?: poster
                this.year = year ?: responseData?.meta?.year?.toIntOrNull()
                this.plot = buildString {
                    append(description ?: descriptions)
                    if (!audioLanguages.isNullOrBlank()) {
                        append(" — Audio: ")
                        append(audioLanguages)
                    }
                }
                this.recommendations = recommendation
                this.tags = genre
                this.score = Score.from10(responseData?.meta?.imdbRating)
                this.contentRating = responseData?.meta?.appExtras?.certification
                addImdbId(imdbId)
                addTMDbId(tmdbId)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(
            responseData?.meta?.name ?: title,
            url,
            TvType.Movie,
            moviejson ?: "{}"
        ) {
            this.backgroundPosterUrl = background ?: bgposter
            this.posterUrl = tmdbPoster ?: poster
            this.year = year ?: responseData?.meta?.year?.toIntOrNull()
            this.plot = buildString {
                append(description ?: descriptions)
                if (!audioLanguages.isNullOrBlank()) {
                    append(" — Audio: ")
                    append(audioLanguages)
                }
            }
            this.recommendations = recommendation
            this.tags = genre
            this.contentRating = responseData?.meta?.appExtras?.certification
            this.score = Score.from10(responseData?.meta?.imdbRating)
            addImdbId(imdbId)
            addTMDbId(tmdbId)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || data == "null" || data == "{}") return false

        val obj = JSONObject(data)

        obj.optJSONArray("subtitleTracks")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                subtitleCallback(
                    newSubtitleFile(
                        s.getString("language"),
                        s.getString("subtitleUrl")
                    )
                )
            }
        }

        val streamUrls = mutableListOf<String>()

        obj.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)
                    .takeIf { it.isNotBlank() }
                    ?.let { streamUrls += it }
            }
        }

        if (streamUrls.isEmpty()) {
            obj.optString("streamUrl")
                .takeIf { it.isNotBlank() }
                ?.let { streamUrls += it }
        }

        if (streamUrls.isEmpty()) return false

        streamUrls.forEach { url ->
            Log.d("Cinemacity", "Cargando link: $url")
            callback(
                newExtractorLink(
                    name,
                    name,
                    url,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = extractQuality(url)
                }
            )
        }

        return true
    }


    fun extractQuality(url: String): Int {
        return when {
            url.contains("2160p") -> Qualities.P2160.value
            url.contains("1440p") -> Qualities.P1440.value
            url.contains("1080p") -> Qualities.P1080.value
            url.contains("720p")  -> Qualities.P720.value
            url.contains("480p")  -> Qualities.P480.value
            url.contains("360p")  -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    fun parseSubtitles(raw: String?): JSONArray {
        val tracks = JSONArray()
        if (raw.isNullOrBlank()) return tracks

        raw.split(",").forEach { entry ->
            val match = Regex("""\[(.+?)](https?://.+)""").find(entry.trim())
            if (match != null) {
                tracks.put(
                    JSONObject().apply {
                        put("language", match.groupValues[1])
                        put("subtitleUrl", match.groupValues[2])
                    }
                )
            }
        }
        return tracks
    }

}
