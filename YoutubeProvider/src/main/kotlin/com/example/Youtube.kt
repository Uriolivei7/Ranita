package com.example

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import com.lagradost.cloudstream3.AcraApplication
import androidx.preference.PreferenceManager
import com.example.YoutubeProvider.Config.SLEEP_BETWEEN

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {



    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true
    )
    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Others)

    companion object {

        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()
    }

    private var isSearchContext = false


    class YouTubeInterceptor(private val prefs: SharedPreferences?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )

            val cookieBuilder = StringBuilder()
            val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)

            if (!visitor.isNullOrBlank()) {
                cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
            } else {
                cookieBuilder.append("VISITOR_INFO1_LIVE=_Mk3UVhY40g; ")
            }

            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                }
            }
            cookieBuilder.append("PREF=f6=40000000&hl=en; CONSENT=YES+fx.456722336;")

            requestBuilder.addHeader("Cookie", cookieBuilder.toString())
            return chain.proceed(requestBuilder.build())
        }
    }

    private val ytInterceptor = YouTubeInterceptor(sharedPref)
    private val safariUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"

    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    @Suppress("PropertyName")
    private data class PlayerResponse(@JsonProperty("streamingData") val streamingData: StreamingData?)
    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?)




    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>

    private fun Map<*, *>.getString(key: String): String? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? String


    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: obj.getListKey("runs")?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> run.getString("text")
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    private fun extractLockupMetadata(lockup: Map<*, *>): Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            rows?.forEach { row ->
                val parts = row.getListKey("metadataParts")
                parts?.forEach { part ->
                    val text = getText(part.getMapKey("text")) ?: ""
                    if (text.isNotBlank()) {

                        if (text.matches(Regex(".*(\\d+[KMBkmb]|views|مشاهدة).*"))) {
                            views = formatViews(text)
                        }

                        else if (!text.matches(Regex(".*(\\d+:\\d+|ago|قبل).*")) && text.length > 1 && !text.contains(
                                "•"
                            )
                        ) {
                            channel = text
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (!direct.isNullOrBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        val text = viewText.toString()
        if (text.any { it in listOf('K', 'M', 'B', 'k', 'm', 'b') } && text.length < 15) {
            return text.split("view")[0].split("مشاهدة")[0].trim()
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isBlank()) return text
        return try {
            val v = digits.toLong()
            when {
                v < 1000 -> v.toString()
                v < 1_000_000 -> String.format("%.1fK", v / 1000.0).replace(".0K", "K")
                v < 1_000_000_000 -> String.format("%.1fM", v / 1_000_000.0).replace(".0M", "M")
                else -> String.format("%.1fB", v / 1_000_000_000.0).replace(".0B", "B")
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)

                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }




    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) { this.posterUrl = poster }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                if (currentList.none { it.data == finalUrl }) {
                    currentList.add(newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                    })
                }

                val finalTitle = "#$episodeNum {$views} $title"

                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            if (lockup.getString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    val title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"
                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val finalTitle =
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title
                    return newTvSeriesSearchResponse(
                        finalTitle,
                        "$mainUrl/playlist?list=$playlistId",
                        TvType.TvSeries
                    ) { this.posterUrl = poster }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                var title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isEmpty()) title = "YouTube Video"
                var (channel, views) = extractLockupMetadata(lockup)
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|عبر|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    if (currentList.none { it.data == finalUrl }) {
                        currentList.add(newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {$views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) { this.posterUrl = poster }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) { this.posterUrl = poster }
            }
        }
        return null
    }


    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean
    ) {
        if (data is Map<*, *>) {

            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode)
            }
        }
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        val regex = Regex(
            """(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*\});""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = try {
            regex.find(html)
        } catch (e: Exception) {
            null
        }
        return match?.groupValues?.getOrNull(1)?.let {
            try {
                parseJson<Map<String, Any>>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun findConfig(html: String, key: String): String? {
        return try {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)
            m?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            if (data.containsKey("continuationCommand")) return (data["continuationCommand"] as? Map<*, *>)?.get(
                "token"
            ) as? String
            for (v in data.values) {
                val t = findTokenRecursive(v); if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i); if (t != null) return t
            }
        }
        return null
    }

    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val isEn = lang == "en"

            val customSections = getCustomHomepages()
            val hasEnabledSections = customSections.any { it.isEnabled }

            if (!hasEnabledSections && sharedPref?.getBoolean("show_trending_home", true) == true) {
                list.add(MainPageData(if (isEn) "Tendencias" else "Principal (Tendencias)", "Home"))
            }

            customSections.filter { it.isEnabled }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url))
            }

            return list
        }
    private fun getCustomHomepages(): List<CustomSection> {
        val json = sharedPref?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }

    private fun resetContinuation() {
        savedContinuationToken = null
    }

    private val continuationTokens = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null

        val requestData = request.data
        val isPlaylist = if (page == 1) requestData.contains("list=") else requestData.startsWith("playlist_")



        fun extractPlaylistVideos(items: List<*>) {
            items.forEach { item ->
                val videoMap = item as? Map<*, *>
                val renderer = videoMap?.get("playlistVideoRenderer") as? Map<*, *>
                if (renderer != null) {
                    val vId = renderer["videoId"] as? String
                    if (vId != null && seenIds.add(vId)) {
                        val vidTitle = extractTitle(renderer["title"] as? Map<*, *>) ?: "Video"
                        val thumb = getBestThumbnail(renderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                        val vidUrl = "$mainUrl/watch?v=$vId"
                        val durationText = extractTitle(safeGet(renderer, "lengthText") as? Map<*, *>)
                        val finalTitle = if (durationText != null) "{$durationText} $vidTitle" else vidTitle

                        results.add(
                            newMovieSearchResponse(finalTitle, vidUrl, TvType.Movie) {
                                this.posterUrl = thumb
                            }
                        )
                    }
                }
            }
        }

        fun findPlaylistToken(items: List<*>?): String? {
            if (items == null) return null
            for (it in items) {
                val m = it as? Map<*, *> ?: continue
                val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                if (!token.isNullOrBlank()) return token
            }
            return null
        }



        try {
            if (page == 1) {
                continuationTokens.remove(requestData)
                if (requestData == "Home") homeShorts.clear()

                var cleanUrl = requestData
                if (cleanUrl.contains("?")) cleanUrl = cleanUrl.substringBefore("?")

                val targetUrl = when {
                    requestData.startsWith("http") && !isPlaylist && !cleanUrl.endsWith("/videos") -> "$cleanUrl/videos"
                    requestData.startsWith("http") -> requestData
                    else -> mainUrl
                }

                val html = app.get(targetUrl, interceptor = ytInterceptor).text

                savedApiKey = findConfig(html, "INNERTUBE_API_KEY")
                savedClientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    if (isPlaylist) {

                        val contents = safeGet(
                            initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
                            "tabRenderer", "content", "sectionListRenderer", "contents",
                            0, "itemSectionRenderer", "contents", 0,
                            "playlistVideoListRenderer", "contents"
                        ) as? List<*>

                        if (contents != null) {
                            extractPlaylistVideos(contents)
                            nextContinuation = findPlaylistToken(contents)
                        }

                        if (nextContinuation.isNullOrBlank()) {
                            val conts = findContinuationItemsRecursive(initialData)
                            nextContinuation = findPlaylistToken(conts)
                        }

                    } else {

                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {



                val tokenToUse = continuationTokens[requestData]
                if (!tokenToUse.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val decodedToken = java.net.URLDecoder.decode(tokenToUse, "UTF-8")

                    val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf("client" to mapOf(
                            "visitorData" to (savedVisitorData ?: ""), "clientName" to "WEB",
                            "clientVersion" to (savedClientVersion ?: "2.20240725.01.00"),
                            "platform" to "DESKTOP"
                        )),
                        "continuation" to decodedToken
                    )
                    val headers = mapOf(
                        "X-Youtube-Client-Name" to "WEB",
                        "X-Youtube-Client-Version" to (savedClientVersion ?: ""),
                        "Origin" to mainUrl, "Referer" to mainUrl
                    )

                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        if (isPlaylist) {

                            val continuationItems = findContinuationItemsRecursive(response)
                            if (continuationItems != null) {
                                extractPlaylistVideos(continuationItems)
                                nextContinuation = findPlaylistToken(continuationItems)
                            }
                        } else {

                            val actions = response["onResponseReceivedActions"] ?: response["onResponseReceivedCommands"] ?: response["continuationContents"] ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!nextContinuation.isNullOrBlank()) {
            continuationTokens[requestData] = nextContinuation
        } else {
            continuationTokens.remove(requestData)
        }

        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    fun findContinuationItemsRecursive(obj: Any?): List<*>? {
        when (obj) {
            is Map<*, *> -> {
                if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>

                val keysToTry = listOf(
                    "onResponseReceivedActions",
                    "onResponseReceivedCommands",
                    "onResponseReceivedEndpoints",
                    "continuationContents"
                )
                for (k in keysToTry) {
                    val v = obj[k]
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }

                for (v in obj.values) {
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }
            }

            is List<*> -> {
                for (i in obj) {
                    val r = findContinuationItemsRecursive(i)
                    if (r != null) return r
                }
            }
        }
        return null
    }



    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val results = mutableListOf<SearchResponse>()

        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }


        val seenIds = mutableSetOf<String>()

        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = sharedPref?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }

        try {
            if (page == 1) {
                savedContinuationToken = null
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                val regexKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
                savedApiKey = regexKey.find(html)?.groupValues?.get(1)
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                }
            } else {
                if (!savedContinuationToken.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "visitorData" to (savedVisitorData ?: "")
                            )
                        ),
                        "continuation" to savedContinuationToken
                    )

                    val response = app.post(apiUrl, json = payload, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedCommands"] ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                    }
                }
            }
            return newSearchResponseList(results, !savedContinuationToken.isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }

    private val collectedShorts = mutableListOf<Episode>()

    private fun addShortToCache(title: String, url: String, poster: String?) {

        if (collectedShorts.none { it.data == url }) {
            collectedShorts.add(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                    this.episode = collectedShorts.size + 1
                }
            )
        }
    }

    private fun parseDurationToSeconds(durationText: String?): Int? {
        if (durationText == null) return null
        val parts = durationText.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> null
        }
    }

    private fun parseIsoDurationToSeconds(iso: String?): Int? {
        if (iso == null) return null
        val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val m = regex.matchEntire(iso) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        return h * 3600 + min * 60 + s
    }

    private fun formatSecondsToDuration(seconds: Int?): String? {
        if (seconds == null) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun translateDateToSpanish(text: String?): String? {
        if (text == null) return null
        return text
            .replace(Regex("(\\d+)\\s*years?\\s*ago"), "hace $1 años")
            .replace(Regex("(\\d+)\\s*months?\\s*ago"), "hace $1 meses")
            .replace(Regex("(\\d+)\\s*weeks?\\s*ago"), "hace $1 semanas")
            .replace(Regex("(\\d+)\\s*days?\\s*ago"), "hace $1 días")
            .replace(Regex("(\\d+)\\s*hours?\\s*ago"), "hace $1 horas")
            .replace(Regex("(\\d+)\\s*minutes?\\s*ago"), "hace $1 minutos")
            .replace(Regex("(\\d+)\\s*seconds?\\s*ago"), "hace $1 segundos")
            .replace("Streamed live", "Transmitido en vivo")
            .replace("Premiered", "Estrenado")
            .replace("Scheduled for", "Programado para")
            .replace("Recommended", "Recomendado")
            .trim()
    }

    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }



    override suspend fun load(url: String): LoadResponse {

        if (url.contains("/shorts/")) {
            val videoId = url.extractYoutubeId() ?: "video"
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { it.data.extractYoutubeId() == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: buildThumbnailFromId(videoId)

            return newTvSeriesLoadResponse("Shorts Feed", url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = "Lista de reproducción automática de cortos (${targetEpisodes.size} Video)"
                this.tags = listOf("Shorts", "Feed")
            }
        }



        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                val channelUrl = if (url.endsWith("/videos")) url else "$url/videos"
                val response = app.get(channelUrl, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val apiKey = findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = getBestThumbnail(safeGet(header, "avatar"))
                    ?: getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val subscriberCount = extractTitle(safeGet(header, "subscriberCountText") as? Map<*, *>)
                    ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows", 1, "metadataParts", 0, "text", "content") as? String

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?): List<*>? {
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints", "continuationContents", "onResponseReceivedResults")
                            for (k in keysToTry) {
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                        if (!token.isNullOrBlank()) return token
                        val token2 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "browseContinuationEndpoint", "token") as? String
                        if (!token2.isNullOrBlank()) return token2
                        val token3 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "token") as? String
                        if (!token3.isNullOrBlank()) return token3
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    items.forEach { item ->
                        val map = item as? Map<*, *> ?: return@forEach
                        val videoRenderer = when {
                            map.containsKey("videoRenderer") -> map["videoRenderer"] as? Map<*, *>
                            map.containsKey("gridVideoRenderer") -> map["gridVideoRenderer"] as? Map<*, *>
                            map.containsKey("compactVideoRenderer") -> map["compactVideoRenderer"] as? Map<*, *>
                            map.containsKey("shortsVideoRenderer") -> map["shortsVideoRenderer"] as? Map<*, *>
                            map.containsKey("reelItemRenderer") -> {
                                val content = safeGet(map, "reelItemRenderer", "content") as? Map<*, *>
                                content?.get("reelItemRenderer") as? Map<*, *>
                            }
                            map.containsKey("richItemRenderer") -> {
                                val content = safeGet(map, "richItemRenderer", "content") as? Map<*, *>
                                (content?.get("videoRenderer") ?: content?.get("gridVideoRenderer") ?: content?.get("shortsLockupViewModel")) as? Map<*, *>
                            }
                            else -> null
                        }

                        if (videoRenderer != null) {
                            val vId = videoRenderer["videoId"] as? String ?: return@forEach
                            val vidTitle = extractTitle(videoRenderer["title"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["headline"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["shortBylineText"] as? Map<*, *>)
                                ?: "Video"
                            val thumb = getBestThumbnail(videoRenderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val viewCount = formatViews(safeGet(videoRenderer, "viewCountText", "simpleText") as? String)
                            val publishedTime = extractTitle(safeGet(videoRenderer, "publishedTimeText") as? Map<*, *>)
                            val durationText = extractTitle(safeGet(videoRenderer, "lengthText") as? Map<*, *>)
                            val durationSec = parseDurationToSeconds(durationText)
                            Log.d("YoutubeProvider", "Video $vId lengthText=$durationText durationSec=$durationSec")
                            val finalName = if (durationText != null) "{$durationText} $vidTitle" else vidTitle

                            collectTo.add(newEpisode(vidUrl) {
                                this.name = finalName
                                this.posterUrl = thumb
                                this.runTime = (durationSec ?: 0) / 60
                                this.description = listOfNotNull(viewCount, translateDateToSpanish(publishedTime)).joinToString(" • ")
                            })
                        }
                    }
                }

                fun extractItemsFromTabContent(content: Map<*, *>): List<*>? {
                    if (content.containsKey("richGridRenderer"))
                        return safeGet(content, "richGridRenderer", "contents") as? List<*>
                    if (content.containsKey("gridRenderer"))
                        return safeGet(content, "gridRenderer", "items") as? List<*>
                    if (content.containsKey("sectionListRenderer")) {
                        val sectionItems = safeGet(content, "sectionListRenderer", "contents") as? List<*>
                        if (sectionItems != null) {
                            val flattened = mutableListOf<Map<*, *>>()
                            for (item in sectionItems) {
                                val sectionMap = item as? Map<*, *> ?: continue
                                val itemSection = sectionMap["itemSectionRenderer"] as? Map<*, *>
                                if (itemSection != null) {
                                    val innerContents = itemSection["contents"] as? List<*>
                                    innerContents?.forEach { ci ->
                                        val ciMap = ci as? Map<*, *>
                                        if (ciMap != null) flattened.add(ciMap)
                                    }
                                }
                            }
                            return flattened
                        }
                    }
                    return null
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    for (tab in tabs) {
                        val tabMap = tab as? Map<*, *>
                        val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                        val content = tabRenderer?.get("content") as? Map<*, *>
                        if (content != null) {
                            initialItems = extractItemsFromTabContent(content)
                            if (initialItems != null) break
                        }
                    }
                }

                // Si no se encontraron videos con /videos, intentar sin /videos
                if (initialItems == null && url.endsWith("/videos")) {
                    try {
                        val baseUrl = url.removeSuffix("/videos")
                        val fallbackResponse = app.get(baseUrl, interceptor = ytInterceptor)
                        val fallbackHtml = fallbackResponse.text
                        val fallbackData = extractYtInitialData(fallbackHtml)
                        if (fallbackData != null) {
                            val fallbackTabs = safeGet(fallbackData, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                            if (fallbackTabs != null) {
                                for (tab in fallbackTabs) {
                                    val tabMap = tab as? Map<*, *>
                                    val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                                    val content = tabRenderer?.get("content") as? Map<*, *>
                                    if (content != null) {
                                        initialItems = extractItemsFromTabContent(content)
                                        if (initialItems != null) break
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPages && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationTokenFromItems(continuationItems)
                        kotlinx.coroutines.delay((SLEEP_BETWEEN * 10).toLong())
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Canal: $title\nSuscriptores: ${subscriberCount ?: "N/A"}\nVideos obtenidos: ${allEpisodes.size}"
                    this.tags = listOf(title, "Canal")
                }

            } catch (e: Exception) {

            }
        }



        if (url.contains("list=")) {
            try {
                val response = app.get(url, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")

                val header = safeGet(data, "header", "playlistHeaderRenderer") as? Map<*, *>
                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: response.document.selectFirst("title")?.text()?.substringBefore(" - YouTube")?.trim()
                    ?: "YouTube Playlist"
                val ownerObj = safeGet(header, "ownerText") as? Map<*, *>
                val author = extractTitle(ownerObj)
                    ?: response.document.selectFirst("a[href^='/channel/']")?.text()
                    ?: "Canal Desconocido"
                val description = extractTitle(safeGet(header, "description") as? Map<*, *>)

                val episodes = mutableListOf<Episode>()
                val contents = safeGet(
                    data, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
                    "tabRenderer", "content", "sectionListRenderer", "contents",
                    0, "itemSectionRenderer", "contents", 0,
                    "playlistVideoListRenderer", "contents"
                ) as? List<*>

                contents?.forEachIndexed { index, item ->
                    val videoMap = item as? Map<*, *>
                    val renderer = videoMap?.get("playlistVideoRenderer") as? Map<*, *>
                    if (renderer != null) {
                        val vId = renderer["videoId"] as? String
                        if (vId != null) {
                            val vidTitle = extractTitle(renderer["title"] as? Map<*, *>) ?: "Episode ${index + 1}"
                            val thumb = getBestThumbnail(renderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val durationText = extractTitle(safeGet(renderer, "lengthText") as? Map<*, *>)
                            val durationSec = parseDurationToSeconds(durationText)
                            com.lagradost.api.Log.d("YoutubeProvider", "Playlist video $vId lengthText=$durationText durationSec=$durationSec")
                            episodes.add(newEpisode(vidUrl) {
                                this.name = vidTitle
                                this.episode = index + 1
                                this.posterUrl = thumb
                                this.runTime = (durationSec ?: 0) / 60
                                this.description = if (durationText != null) "Duración: $durationText" else null
                            })
                        }
                    }
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    val finalDescription = if (description.isNullOrBlank()) "Canal: $author" else "Canal: $author\n\n$description"
                    this.plot = finalDescription
                    this.tags = listOf(author)
                }
            } catch (e: Exception) {

            }
        }



        val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL")

        val response = app.get(url, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)

        var title = "YouTube Video"
        var plot = ""
        var poster = buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""
        var singleVideoDurationMin = 0

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {

            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = translateDateToSpanish(extractTitle(primary["dateText"] as? Map<*, *>))
                    if (!dateText.isNullOrBlank()) plot += "📅 $dateText - \n"
                    val lengthText = extractTitle(primary["lengthText"] as? Map<*, *>)
                        ?: extractTitle(primary["length"] as? Map<*, *>)
                    com.lagradost.api.Log.d("YoutubeProvider", "Single video $videoId lengthText=$lengthText primaryKeys=${primary.keys}")
                    val lengthSec = parseDurationToSeconds(lengthText)
                    if (lengthSec != null) {
                        singleVideoDurationMin = lengthSec / 60
                        plot = "⏱ ${formatSecondsToDuration(lengthSec)}" + if (plot.isNotEmpty()) "\n$plot" else ""
                    }
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {

                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {

                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {

            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }

        if (singleVideoDurationMin == 0) {
            val durationMeta = response.document.selectFirst("meta[itemprop=duration]")?.attr("content")
                ?: response.document.selectFirst("link[itemprop=duration]")?.attr("href")
            if (durationMeta != null) {
                val sec = parseIsoDurationToSeconds(durationMeta)
                com.lagradost.api.Log.d("YoutubeProvider", "Single video $videoId durationMeta=$durationMeta sec=$sec")
                if (sec != null) {
                    singleVideoDurationMin = sec / 60
                    plot = "⏱ ${formatSecondsToDuration(sec)}" + if (plot.isNotEmpty()) "\n$plot" else ""
                }
            }
        }


        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Canal: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar


            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot
            this.duration = singleVideoDurationMin

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs
        }
    }

    private fun sha1(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getSapisidHash(
        sapisid: String,
        origin: String = "https://www.youtube.com"
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val msg = "$timestamp $sapisid $origin"
        val hash = sha1(msg)
        return "SAPISIDHASH ${timestamp}_$hash"
    }
    private fun extractYoutubeIdSafe(input: String): String {
        return when {
            input.contains("youtu.be/") ->
                input.substringAfter("youtu.be/").substringBefore("?")

            input.contains("watch?v=") ->
                input.substringAfter("watch?v=").substringBefore("&")

            input.contains("/shorts/") ->
                input.substringAfter("/shorts/").substringBefore("?")

            else -> input
        }
    }




    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data.extractYoutubeId() ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"
        var foundAnyLink = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            callback(link)
            foundAnyLink = true
        }

        val context = AcraApplication.context
        val playerType = if (context != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString("youtube_player_type", "classic")
        } else {
            "classic"
        }

        Log.i("YtExtractor", "Video $videoId: Player type=$playerType")

        if (playerType == "classic") {
            Log.i("YtExtractor", "Video $videoId: Using loadExtractor (CS3 built-in)")
            loadExtractor(fullUrl, subtitleCallback, trackingCallback)
        } else {
            Log.i("YtExtractor", "Video $videoId: Using NewPipe YoutubeExtractor")
            com.example.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, trackingCallback)
        }

        if (!foundAnyLink) {
            Log.i("YtExtractor", "Video $videoId: Primary extractor gave no links, trying InnerTube API")
            var watchHtml: String? = null
            try {
                watchHtml = app.get(fullUrl, interceptor = ytInterceptor).text
            } catch (_: Exception) {}
            foundAnyLink = tryInnerTubeClients(videoId, watchHtml, subtitleCallback, trackingCallback)
        }

        Log.i("YtExtractor", "Video $videoId: loadLinks returning $foundAnyLink")
        return foundAnyLink
    }

    private suspend fun tryInnerTubeClients(
        videoId: String,
        watchHtml: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val webApiKey = if (!watchHtml.isNullOrBlank()) findConfig(watchHtml, "INNERTUBE_API_KEY") ?: "" else ""
        var visitorData = if (!watchHtml.isNullOrBlank()) findConfig(watchHtml, "VISITOR_DATA") ?: "" else ""
        val webClientVersion = if (!watchHtml.isNullOrBlank()) findConfig(watchHtml, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00" else "2.20240725.01.00"

        Log.i("YtExtractor", "Video $videoId: Scraped apiKey present=${webApiKey.isNotBlank()} visitorData present=${visitorData.isNotBlank()} webClientVersion=$webClientVersion")

        // Try to get visitor data from sharedPrefs as fallback
        if (visitorData.isBlank()) {
            visitorData = sharedPref?.getString("VISITOR_INFO1_LIVE", null) ?: ""
            Log.i("YtExtractor", "Video $videoId: Using VISITOR_INFO1_LIVE from prefs as visitorData, present=${visitorData.isNotBlank()}")
        }

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        // Try WEB client first (most permissive), then WEB_CREATOR
        val clients = mutableListOf<Triple<String, String, String>>()
        if (webApiKey.isNotBlank()) {
            clients.add(Triple("2.20240725.01.00", "WEB", "DESKTOP"))
            clients.add(Triple("1.20240726.00.00", "WEB_CREATOR", "DESKTOP"))
        }

        for ((version, clientName, platform) in clients) {
            Log.i("YtExtractor", "Video $videoId: Trying client=$clientName v=$version apiKey=${webApiKey.take(12)}... visitorData=${visitorData.take(20)}")
            try {
                val result = fetchAndParsePlayerResponse(
                    videoId, webApiKey, visitorData, webClientVersion,
                    clientName, version, platform, userAgent,
                    subtitleCallback, callback
                )
                if (result) return true
            } catch (e: Exception) {
                Log.w("YtExtractor", "Video $videoId: Client $clientName error: ${e.message}")
            }
        }
        return false
    }

    private suspend fun fetchAndParsePlayerResponse(
        videoId: String,
        apiKey: String,
        visitorData: String,
        webClientVersion: String,
        clientName: String,
        clientVersion: String,
        platform: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = "$mainUrl/youtubei/v1/player?key=$apiKey"
        val clientMap = mutableMapOf<String, Any>(
            "clientName" to clientName,
            "clientVersion" to clientVersion,
            "hl" to "en",
            "gl" to "US",
            "visitorData" to visitorData,
            "platform" to platform
        )
        val payload = mutableMapOf<String, Any>(
            "context" to mapOf("client" to clientMap),
            "videoId" to videoId,
            "contentCheckOk" to true,
            "racyCheckOk" to true
        )

        val headers = mutableMapOf<String, String>()
        headers["Content-Type"] = "application/json"
        headers["User-Agent"] = userAgent
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["X-Youtube-Client-Name"] = clientName
        headers["X-Youtube-Client-Version"] = clientVersion
        if (visitorData.isNotBlank()) headers["X-Goog-Visitor-Id"] = visitorData

        val sapisid = sharedPref?.getString("SAPISID", null)
        if (!sapisid.isNullOrBlank() && clientName != "ANDROID") {
            try {
                val origin = "https://www.youtube.com"
                val hash = getSapisidHash(sapisid, origin)
                headers["Authorization"] = hash
                headers["X-Origin"] = origin
                headers["Origin"] = origin
                headers["X-Goog-AuthUser"] = "0"
            } catch (_: Exception) {}
        }

        val responseText = app.post(apiUrl, json = payload.toMap(), headers = headers).text
        if (responseText.isBlank()) return false

        val root = JSONObject(responseText)

        val playabilityStatus = root.optJSONObject("playabilityStatus")
        if (playabilityStatus != null) {
            val status = playabilityStatus.optString("status", "")
            if (status != "OK") {
                val reason = playabilityStatus.optString("reason", "")
                val subreason = playabilityStatus.optJSONObject("errorScreen")?.optJSONObject("playerErrorMessageRenderer")?.optJSONArray("subreason")?.optJSONObject(0)?.optString("simpleText", "") ?: ""
                Log.w("YtExtractor", "Video $videoId: $clientName playabilityStatus=$status reason=$reason subreason=$subreason")
                if (status == "UNPLAYABLE" || status == "ERROR" || status == "LOGIN_REQUIRED") {
                    return false
                }
            }
        }

        val streamingData = root.optJSONObject("streamingData")
        if (streamingData == null) {
            Log.w("YtExtractor", "Video $videoId: $clientName no streamingData in response (full response: ${responseText.take(2000)})")
            return false
        }

        var found = false

        val hlsUrl = streamingData.optString("hlsManifestUrl", "")
        if (hlsUrl.isNotBlank()) {
            Log.i("YtExtractor", "Video $videoId: $clientName HLS found")
            callback(newExtractorLink(this.name, "$clientName M3U8", hlsUrl) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            })
            found = true
            try {
                val masterM3u8 = app.get(hlsUrl, referer = mainUrl).text
                val lines = masterM3u8.lines()
                lines.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=SUBTITLES") }
                    .forEach { line ->
                        val subUri = parseM3u8Tag(line, "URI")
                        val subName = parseM3u8Tag(line, "NAME")
                        val subLang = parseM3u8Tag(line, "LANGUAGE")
                        if (subUri != null) {
                            subtitleCallback(newSubtitleFile(subName ?: subLang ?: "HLS Sub", subUri))
                        }
                    }
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val urlLine = lines.getOrNull(index + 1)?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
                        val resolution = parseM3u8Tag(line, "RESOLUTION")
                        val resHeight = resolution?.substringAfter("x")?.plus("p") ?: ""
                        callback(newExtractorLink(this.name, "$clientName $resHeight", urlLine) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(resHeight)
                        })
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                try {
                    val fmt = formats.optJSONObject(i)
                    val fmtUrl = fmt.optString("url", "")
                    if (fmtUrl.isNotBlank()) {
                        val qualityLabel = fmt.optString("qualityLabel", "")
                        val mime = fmt.optString("mimeType", "").substringBefore(";")
                        val label = if (qualityLabel.isNotBlank()) "$clientName $qualityLabel" else "$clientName muxed"
                        callback(newExtractorLink(this.name, label, fmtUrl) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(qualityLabel)
                        })
                        found = true
                    } else {
                        val cipher = fmt.optString("cipher", "")
                        val sigCipher = fmt.optString("signatureCipher", "")
                        if (cipher.isNotBlank() || sigCipher.isNotBlank()) {
                            val urlParam = if (cipher.isNotBlank()) cipher else sigCipher
                            val extractedUrl = urlParam.split("&").firstOrNull { it.startsWith("url=") }?.substringAfter("url=")?.let { java.net.URLDecoder.decode(it, "utf-8") }
                            if (extractedUrl != null) {
                                val qualityLabel = fmt.optString("qualityLabel", "")
                                callback(newExtractorLink(this.name, "$clientName $qualityLabel (cipher)", extractedUrl) {
                                    this.referer = mainUrl
                                    this.quality = getQualityFromName(qualityLabel)
                                })
                                found = true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null) {
            val videoFormats = mutableListOf<JSONObject>()
            val audioFormats = mutableListOf<JSONObject>()
            for (i in 0 until adaptiveFormats.length()) {
                try {
                    val fmt = adaptiveFormats.optJSONObject(i)
                    val mime = fmt.optString("mimeType", "")
                    if (mime.startsWith("video/")) {
                        videoFormats.add(fmt)
                    } else if (mime.startsWith("audio/")) {
                        audioFormats.add(fmt)
                    }
                } catch (_: Exception) {}
            }

            for (vf in videoFormats) {
                try {
                    val fmtUrl = vf.optString("url", "")
                    if (fmtUrl.isNotBlank()) {
                        val qualityLabel = vf.optString("qualityLabel", "")
                        callback(newExtractorLink(this.name, "$clientName DASH $qualityLabel", fmtUrl) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(qualityLabel)
                        })
                        found = true
                    }
                } catch (_: Exception) {}
            }
            for (af in audioFormats) {
                try {
                    val fmtUrl = af.optString("url", "")
                    if (fmtUrl.isNotBlank()) {
                        val audioQuality = af.optString("audioQuality", "").substringAfter("AUDIO_QUALITY_")
                        val bitrate = af.optInt("bitrate", 0)
                        val label = "$clientName audio ${audioQuality.ifBlank { bitrate.toString() }}"
                        callback(newExtractorLink(this.name, label, fmtUrl) {
                            this.referer = mainUrl
                            this.quality = if (audioQuality.contains("HIGH")) Qualities.Unknown.value else Qualities.Unknown.value
                        })
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }

    // loadLinksAdvanced and loadLinksClassic removed — replaced by fetchAndParsePlayerResponse

    private fun parseM3u8Tag(tag: String, key: String): String? {
        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null }
            ?: match?.groupValues?.get(3)?.ifBlank { null }
    }

    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }
}