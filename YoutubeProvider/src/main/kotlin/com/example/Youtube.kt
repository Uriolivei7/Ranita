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
            ?: renderer.getMapKey("richItemRenderer")?.getMapKey("content")?.let { content ->
                content.getMapKey("videoRenderer")
                    ?: content.getMapKey("gridVideoRenderer")
                    ?: content.getMapKey("compactVideoRenderer")
            }

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
                "richGridRenderer",
                "gridRenderer",
                "richItemRenderer",
                "content",
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
        val patterns = listOf(
            Regex("""(?:var|const|let)\s+ytInitialData\s*=\s*(\{.+\});""", RegexOption.DOT_MATCHES_ALL),
            Regex("""window\["ytInitialData"\]\s*=\s*(\{.+\});""", RegexOption.DOT_MATCHES_ALL),
            Regex("""window\.ytInitialData\s*=\s*(\{.+\});""", RegexOption.DOT_MATCHES_ALL),
            Regex("""ytInitialData\s*=\s*(\{.+\});""", RegexOption.DOT_MATCHES_ALL)
        )
        for (regex in patterns) {
            try {
                val match = regex.find(html)
                if (match != null) {
                    val json = parseJson<Map<String, Any>>(match.groupValues[1])
                    if (json != null) return json
                }
            } catch (_: Exception) {}
        }
        return null
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

                Log.d("YTMain", "getMainPage: request='$requestData' targetUrl='$targetUrl' isPlaylist=$isPlaylist")

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

                        // Fallback for channels: try without /videos
                        if (results.isEmpty() && requestData.startsWith("http") && cleanUrl.endsWith("/videos")) {
                            Log.w("YTMain", "No results with /videos, trying base URL")
                            val baseUrl = cleanUrl.removeSuffix("/videos")
                            val fallbackHtml = app.get(baseUrl, interceptor = ytInterceptor).text
                            val fallbackData = extractYtInitialData(fallbackHtml)
                            if (fallbackData != null) {
                                processRecursive(fallbackData, results, seenIds, playlistMode = false)
                                nextContinuation = findTokenRecursive(fallbackData)
                                Log.d("YTMain", "Fallback (without /videos) found ${results.size} results")
                            }
                        }
                    }
                }
                Log.d("YTMain", "getMainPage: done, results=${results.size}")
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
                Log.i("YtChannel", "Loading channel: $channelUrl")
                val response = app.get(channelUrl, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                if (data == null) {
                    Log.w("YtChannel", "extractYtInitialData returned null for $channelUrl")
                    throw ErrorLoadingException("Failed to extract channel data")
                }
                Log.i("YtChannel", "ytInitialData extracted OK, keys: ${data.keys}")

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
                                if (content == null) {
                                    Log.w("YtChannel", "richItemRenderer.content is null, richItemRenderer keys: ${map["richItemRenderer"]?.let { (it as? Map<*, *>)?.keys?.joinToString(",") }}")
                                    null
                                } else {
                                    val vr = content["videoRenderer"] ?: content["gridVideoRenderer"] ?: content["shortsLockupViewModel"] ?: content["lockupViewModel"]
                                    if (vr == null) {
                                        Log.w("YtChannel", "content keys WHITOUT videoRenderer: ${content.keys.joinToString(",")}")
                                        content.keys.firstOrNull()?.let { k ->
                                            val v = content[k]
                                            Log.w("YtChannel", "first content key '$k' type=${v?.javaClass?.simpleName} preview=${v.toString().take(200)}")
                                        }
                                    }
                                    vr as? Map<*, *>
                                }
                            }
                            else -> null
                        }

                        if (videoRenderer != null) {
                            val isLockup = videoRenderer.containsKey("contentId") || videoRenderer.containsKey("contentType")
                            if (isLockup) {
                                val vId = videoRenderer["contentId"] as? String ?: return@forEach
                                val metadata = videoRenderer.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                                val vidTitle = getText(metadata?.getMapKey("title")).ifEmpty { "Video" }
                                val (channel, views) = extractLockupMetadata(videoRenderer)
                                val thumb = getBestThumbnail(
                                    videoRenderer.getMapKey("contentImage")?.getMapKey("thumbnailViewModel")?.getMapKey("image")?.getListKey("sources")
                                        ?: videoRenderer.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                                ) ?: "https://i.ytimg.com/vi/$vId/hqdefault.jpg"
                                val vidUrl = "$mainUrl/watch?v=$vId"
                                val finalName = if (channel.isNotBlank()) "{$channel | $views} $vidTitle" else "{$views} $vidTitle"
                                collectTo.add(newEpisode(vidUrl) {
                                    this.name = finalName
                                    this.posterUrl = thumb
                                })
                            } else {
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
                }

                fun extractItemsFromTabContent(content: Map<*, *>): List<*>? {
                    if (content.containsKey("richGridRenderer")) {
                        val items = safeGet(content, "richGridRenderer", "contents") as? List<*>
                        Log.d("YtChannel", "extractItems: richGridRenderer -> ${items?.size ?: 0} items")
                        return items
                    }
                    if (content.containsKey("gridRenderer")) {
                        val items = safeGet(content, "gridRenderer", "items") as? List<*>
                        Log.d("YtChannel", "extractItems: gridRenderer -> ${items?.size ?: 0} items")
                        return items
                    }
                    if (content.containsKey("sectionListRenderer")) {
                        val sectionItems = safeGet(content, "sectionListRenderer", "contents") as? List<*>
                        Log.d("YtChannel", "extractItems: sectionListRenderer -> ${sectionItems?.size ?: 0} section items")
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
                                } else {
                                    val richSection = sectionMap["richSectionRenderer"] as? Map<*, *>
                                    val richContent = richSection?.get("content") as? Map<*, *>
                                    if (richContent?.containsKey("richGridRenderer") == true) {
                                        val richItems = safeGet(richContent, "richGridRenderer", "contents") as? List<*>
                                        if (richItems != null) flattened.addAll(richItems.mapNotNull { it as? Map<*, *> })
                                    }
                                }
                            }
                            Log.d("YtChannel", "extractItems: sectionListRenderer flattened -> ${flattened.size} items")
                            return flattened
                        }
                    }
                    Log.w("YtChannel", "extractItems: UNKNOWN content keys: ${content.keys.joinToString(",")}")
                    // Log first key's value type to help diagnose
                    content.keys.firstOrNull()?.let { k ->
                        val v = content[k]
                        Log.w("YtChannel", "extractItems: first key '$k' is ${v?.javaClass?.simpleName} : ${v.toString().take(300)}")
                    }
                    return null
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                Log.i("YtChannel", "Tabs found: ${tabs?.size ?: 0}")
                if (tabs != null) {
                    for ((tabIdx, tab) in tabs.withIndex()) {
                        val tabMap = tab as? Map<*, *>
                        val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                        val tabTitle = extractTitle(safeGet(tabRenderer, "title") as? Map<*, *>) ?: "tab$tabIdx"
                        val hasContent = tabRenderer?.containsKey("content") == true
                        Log.d("YtChannel", "Tab $tabIdx: '$tabTitle' hasContent=$hasContent selected=${tabRenderer?.get("selected")}")
                        val content = tabRenderer?.get("content") as? Map<*, *>
                        if (content != null) {
                            val foundKeys = content.keys.joinToString(",")
                            Log.d("YtChannel", "Tab $tabIdx content keys: $foundKeys")
                            // Log first 200 chars of first content value to understand structure
                            content.keys.firstOrNull()?.let { firstKey ->
                                val firstVal = content[firstKey]
                                Log.d("YtChannel", "Tab $tabIdx firstKey='$firstKey' type=${firstVal?.javaClass?.simpleName}")
                            }
                            initialItems = extractItemsFromTabContent(content)
                            Log.d("YtChannel", "Tab $tabIdx extractItems: ${initialItems?.size ?: 0} items")
                            if (initialItems != null) {
                                Log.d("YtChannel", "Tab $tabIdx first item keys: ${(initialItems!!.firstOrNull() as? Map<*, *>)?.keys?.joinToString(",") ?: "null"}")
                                break
                            }
                        } else {
                            Log.d("YtChannel", "Tab $tabIdx NO content - tabRenderer keys: ${tabRenderer?.keys?.joinToString(",") ?: "null"}")
                        }
                    }
                } else {
                    Log.w("YtChannel", "No tabs array found. data keys under twoColumnBrowseResultsRenderer: ${safeGet(data, "contents", "twoColumnBrowseResultsRenderer")?.let { (it as? Map<*, *>)?.keys?.joinToString(",") ?: "not a map" }}")
                }

                // Si no se encontraron videos con /videos, intentar sin /videos
                if (initialItems == null && url.endsWith("/videos")) {
                    Log.w("YtChannel", "No items with /videos, retrying without /videos")
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
                    } catch (e: Exception) {
                        Log.w("YtChannel", "Fallback without /videos failed: ${e.message}")
                    }
                }

                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                    Log.i("YtChannel", "Extracted ${allEpisodes.size} videos from initialItems")
                } else {
                    Log.w("YtChannel", "No initialItems found from any tab")
                }

                if (allEpisodes.isEmpty()) {
                    Log.w("YtChannel", "No videos yet, trying recursive search of full data tree")
                    fun findVideosRecursive(obj: Any?) {
                        if (obj is Map<*, *>) {
                            if (obj.containsKey("videoRenderer") || obj.containsKey("gridVideoRenderer") || obj.containsKey("compactVideoRenderer") || obj.containsKey("lockupViewModel")) {
                                extractVideosFromItems(listOf(obj), allEpisodes)
                                return
                            }
                            for (v in obj.values) findVideosRecursive(v)
                        } else if (obj is List<*>) {
                            for (i in obj) findVideosRecursive(i)
                        }
                    }
                    findVideosRecursive(data)
                    Log.i("YtChannel", "Recursive search found ${allEpisodes.size} videos")
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
                        Log.d("YtChannel", "Pagination page $pagesFetchedLocal, token=${currentToken?.take(30)}...")
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
                        Log.d("YtChannel", "Pagination got ${allEpisodes.size} total videos so far")
                        currentToken = findContinuationTokenFromItems(continuationItems)
                        kotlinx.coroutines.delay((SLEEP_BETWEEN * 10).toLong())
                    } catch (e: Exception) {
                        Log.w("YtChannel", "Pagination error on page $pagesFetchedLocal: ${e.message}")
                        break
                    }
                }

                Log.i("YtChannel", "Returning ${allEpisodes.size} episodes for channel $title")
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Canal: $title\nSuscriptores: ${subscriberCount ?: "N/A"}\nVideos obtenidos: ${allEpisodes.size}"
                    this.tags = listOf(title, "Canal")
                }

            } catch (e: Exception) {
                Log.e("YtChannel", "Channel load error: ${e.message}")
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
                    0, "itemSectionRenderer", "contents"
                ) as? List<*>

                if (contents == null) {
                    Log.w("YtPlaylist", "No itemSectionRenderer.contents found. data top keys: ${data.keys.joinToString(",")}")
                }

                contents?.forEachIndexed { index, item ->
                    val videoMap = item as? Map<*, *> ?: return@forEachIndexed
                    val renderer = videoMap["lockupViewModel"] as? Map<*, *>
                    if (renderer == null) {
                        Log.w("YtPlaylist", "Item $index has no lockupViewModel, keys: ${videoMap.keys.joinToString(",")}")
                        return@forEachIndexed
                    }
                    val vId = renderer["contentId"] as? String ?: return@forEachIndexed
                    val metadata = renderer.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    val vidTitle = getText(metadata?.getMapKey("title")).ifEmpty { "Episode ${index + 1}" }
                    val thumb = getBestThumbnail(
                        renderer.getMapKey("contentImage")?.getMapKey("thumbnailViewModel")?.getMapKey("image")?.getListKey("sources")
                            ?: renderer.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: buildThumbnailFromId(vId)
                    val vidUrl = "$mainUrl/watch?v=$vId"
                    Log.d("YtPlaylist", "Playlist video $index: id=$vId title=$vidTitle")
                    episodes.add(newEpisode(vidUrl) {
                        this.name = vidTitle
                        this.episode = index + 1
                        this.posterUrl = thumb
                    })
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

        Log.i("YtExtractor", "Video $videoId: Trying NewPipe YoutubeExtractor")
        com.example.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, trackingCallback)

        // 2. If no links, try CS3 built-in extractor
        if (!foundAnyLink) {
            Log.i("YtExtractor", "Video $videoId: NewPipe gave no links, trying loadExtractor (CS3 built-in)")
            loadExtractor(fullUrl, subtitleCallback, trackingCallback)
        }

        // 3. If still no links, try InnerTube API
        if (!foundAnyLink) {
            Log.i("YtExtractor", "Video $videoId: NewPipe gave no links, trying InnerTube API")
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
        var scrapedApiKey = ""
        var visitorData = ""
        var webClientVersion = "2.20260526.01.00"
        if (!watchHtml.isNullOrBlank()) {
            val ytcfgBlock = try {
                val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""", RegexOption.DOT_MATCHES_ALL)
                val m = regex.find(watchHtml)
                m?.groupValues?.getOrNull(1)
                    ?: watchHtml.substringAfter("ytcfg.set(", "").substringBefore(");")
                        .takeIf { it.trim().startsWith("{") }
            } catch (_: Exception) { null }
            if (!ytcfgBlock.isNullOrBlank()) {
                scrapedApiKey = findConfig(ytcfgBlock, "INNERTUBE_API_KEY") ?: ""
                visitorData = findConfig(ytcfgBlock, "VISITOR_DATA") ?: ""
                webClientVersion = findConfig(ytcfgBlock, "INNERTUBE_CLIENT_VERSION") ?: "2.20260526.01.00"
            }
        }

        Log.i("YtExtractor", "Video $videoId: Scraped apiKey present=${scrapedApiKey.isNotBlank()} visitorData present=${visitorData.isNotBlank()} webClientVersion=$webClientVersion")

        // Try to get visitor data from sharedPrefs as fallback
        if (visitorData.isBlank()) {
            visitorData = sharedPref?.getString("VISITOR_INFO1_LIVE", null) ?: ""
            Log.i("YtExtractor", "Video $videoId: Using VISITOR_INFO1_LIVE from prefs as visitorData, present=${visitorData.isNotBlank()}")
        }

        // Known InnerTube API keys per client
        val knownApiKeys = mapOf(
            "WEB" to "AIzaSyAO_FJ2t_7EIXq_yNMgq_sR3gCwq9ANz8M",
            "WEB_CREATOR" to "AIzaSyAO_FJ2t_7EIXq_yNMgq_sR3gCwq9ANz8M",
            "ANDROID" to "AIzaSyA8eiZmM1Gd7dBAa3yl3WjAwF6mS7Fv9Ew",
            "ANDROID_MUSIC" to "AIzaSyAOghZGza2MQSZkYGeOQWTb9GPwW6fMkak",
            "iOS" to "AIzaSyB-63vPr2V1sSGG8_NX_g3BnyTvkZISMrg",
            "TVHTML5" to "AIzaSyAO_FJ2t_7EIXq_yNMgq_sR3gCwq9ANz8M"
        )
        fun apiKeyFor(clientName: String): String {
            val known = knownApiKeys[clientName] ?: ""
            return if (known.isNotBlank()) known else scrapedApiKey
        }

        // Try multiple clients — WEB often fails, ANDROID clients are more permissive
        data class Client(val name: String, val version: String, val platform: String, val userAgent: String)
        val clients = mutableListOf<Client>()
        clients.add(Client("WEB", webClientVersion, "DESKTOP", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"))
        clients.add(Client("WEB_CREATOR", "1.20240726.00.00", "DESKTOP", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"))
        clients.add(Client("ANDROID", "19.09.37", "MOBILE", "com.google.android.youtube/19.09.37 (Linux; U; Android 12; GB) gzip"))
        clients.add(Client("ANDROID_MUSIC", "5.19.0", "MOBILE", "com.google.android.apps.youtube.music/5.19.0 (Linux; U; Android 12; GB) gzip"))
        clients.add(Client("iOS", "19.09.37", "MOBILE", "com.google.ios.youtube/19.09.37 (iPhone; CPU iPhone OS 16_0 like Mac OS X; en_US)"))
        clients.add(Client("TVHTML5", "7.20241024.00.00", "TV", "Mozilla/5.0 (ChromiumStylePlatform; Chrome/116.0.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"))

        for (client in clients) {
            val apiKey = apiKeyFor(client.name)
            Log.i("YtExtractor", "Video $videoId: Trying client=${client.name} v=${client.version} apiKey=${apiKey.take(12)}... (minimal payload, no visitorData/checks)")
            try {
                val result = fetchAndParsePlayerResponse(
                    videoId, apiKey, visitorData, webClientVersion,
                    client.name, client.version, client.platform, client.userAgent,
                    subtitleCallback, callback
                )
                if (result) return true
            } catch (e: Exception) {
                Log.w("YtExtractor", "Video $videoId: Client ${client.name} error: ${e.message}")
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
        val payload = mapOf<String, Any>(
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to clientName,
                    "clientVersion" to clientVersion,
                    "hl" to "en",
                    "gl" to "US"
                )
            ),
            "videoId" to videoId
        )
        // playbackContext omitted for ANDROID - html5Preference enum is unknown

        val headers = mutableMapOf<String, String>()
        headers["Content-Type"] = "application/json"
        headers["User-Agent"] = userAgent
        headers["Accept-Language"] = "en-US,en;q=0.9"
        val clientNameNumeric = when (clientName) {
            "WEB" -> "1"
            "ANDROID" -> "3"
            "iOS" -> "5"
            "ANDROID_MUSIC" -> "21"
            "WEB_CREATOR" -> "62"
            "TVHTML5" -> "85"
            else -> "1"
        }
        headers["X-Youtube-Client-Name"] = clientNameNumeric
        headers["X-Youtube-Client-Version"] = clientVersion

        headers["Origin"] = "https://www.youtube.com"
        headers["Referer"] = "https://www.youtube.com/watch?v=$videoId"
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

        Log.d("YtExtractor", "Video $videoId: $clientName request payload: ${JSONObject(payload.toMap()).toString(2).take(1500)}")
        Log.d("YtExtractor", "Video $videoId: $clientName request headers: $headers")

        val responseText = app.post(apiUrl, json = payload.toMap(), headers = headers, interceptor = ytInterceptor).text
        if (responseText.isBlank()) {
            Log.w("YtExtractor", "Video $videoId: $clientName empty response")
            return false
        }
        Log.d("YtExtractor", "Video $videoId: $clientName response length=${responseText.length} bytes")

        val root = JSONObject(responseText)

        val playabilityStatus = root.optJSONObject("playabilityStatus")
        if (playabilityStatus != null) {
            val status = playabilityStatus.optString("status", "")
            val reason = playabilityStatus.optString("reason", "")
            val subreason = playabilityStatus.optJSONObject("errorScreen")?.optJSONObject("playerErrorMessageRenderer")?.optJSONArray("subreason")?.optJSONObject(0)?.optString("simpleText", "") ?: ""
            if (status == "OK") {
                Log.i("YtExtractor", "Video $videoId: $clientName playabilityStatus=OK (video is playable!)")
            } else {
                Log.w("YtExtractor", "Video $videoId: $clientName playabilityStatus=$status reason=$reason subreason=$subreason")
                val errorScreen = playabilityStatus.optJSONObject("errorScreen")
                if (errorScreen != null) {
                    Log.w("YtExtractor", "Video $videoId: $clientName errorScreen keys: ${errorScreen.keys().asSequence().joinToString(",")}")
                }
                if (status == "UNPLAYABLE" || status == "ERROR" || status == "LOGIN_REQUIRED") {
                    return false
                }
            }
        } else {
            val keys = root.keys().asSequence().joinToString(",")
            if (keys == "error") {
                val errObj = root.optJSONObject("error")
                val errCode = errObj?.optInt("code", 0)
                val errMsg = errObj?.optString("message", "")
                Log.w("YtExtractor", "Video $videoId: $clientName API error code=$errCode message=$errMsg")
                Log.d("YtExtractor", "Video $videoId: $clientName full error: ${responseText.take(1000)}")
            } else {
                Log.w("YtExtractor", "Video $videoId: $clientName no playabilityStatus in response (keys: $keys, body: ${responseText.take(500)})")
            }
        }

        val streamingData = root.optJSONObject("streamingData")
        if (streamingData == null) {
            val keys = root.keys().asSequence().joinToString(",")
            Log.w("YtExtractor", "Video $videoId: $clientName no streamingData, root keys: $keys, response length=${responseText.length}")
            if (responseText.length < 3000) {
                Log.w("YtExtractor", "Video $videoId: $clientName full response: $responseText")
            }
            val endpoint = root.optJSONObject("currentVideoEndpoint") ?: root.optJSONObject("watchEndpoint")
            if (endpoint != null) {
                Log.w("YtExtractor", "Video $videoId: $clientName redirect endpoint found: ${endpoint.toString().take(500)}")
            }
            return false
        }

        var found = false

        val hlsUrl = streamingData.optString("hlsManifestUrl", "")
        if (hlsUrl.isNotBlank()) {
            Log.i("YtExtractor", "Video $videoId: $clientName HLS manifest found: $hlsUrl")
            callback(newExtractorLink(this.name, "$clientName M3U8", hlsUrl) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            })
            found = true
            try {
                val masterM3u8 = app.get(hlsUrl, referer = mainUrl).text
                val lines = masterM3u8.lines()
                Log.d("YtExtractor", "Video $videoId: $clientName HLS master has ${lines.size} lines")
                lines.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=SUBTITLES") }
                    .forEach { line ->
                        val subUri = parseM3u8Tag(line, "URI")
                        val subName = parseM3u8Tag(line, "NAME")
                        val subLang = parseM3u8Tag(line, "LANGUAGE")
                        if (subUri != null) {
                            subtitleCallback(newSubtitleFile(subName ?: subLang ?: "HLS Sub", subUri))
                        }
                    }
                var streamInfCount = 0
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val urlLine = lines.getOrNull(index + 1)?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
                        val resolution = parseM3u8Tag(line, "RESOLUTION")
                        val resHeight = resolution?.substringAfter("x")?.plus("p") ?: ""
                        callback(newExtractorLink(this.name, "$clientName $resHeight", urlLine) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(resHeight)
                        })
                        streamInfCount++
                        found = true
                    }
                }
                Log.i("YtExtractor", "Video $videoId: $clientName emitted $streamInfCount HLS variants")
            } catch (e: Exception) {
                Log.w("YtExtractor", "Video $videoId: $clientName HLS parsing failed: ${e.message}")
            }
        }

        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            Log.d("YtExtractor", "Video $videoId: $clientName formats count=${formats.length()}")
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
        } else {
            Log.d("YtExtractor", "Video $videoId: $clientName no formats array")
        }

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null) {
            Log.d("YtExtractor", "Video $videoId: $clientName adaptiveFormats count=${adaptiveFormats.length()}")
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
            Log.d("YtExtractor", "Video $videoId: $clientName ${videoFormats.size} video / ${audioFormats.size} audio adaptive formats")

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
                    } else {
                        val hasCipher = vf.optString("signatureCipher", "").isNotBlank()
                        if (hasCipher) {
                            Log.d("YtExtractor", "Video $videoId: $clientName video adaptive format has signatureCipher (needs decrypt)")
                        }
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
                    } else {
                        val hasCipher = af.optString("signatureCipher", "").isNotBlank()
                        if (hasCipher) {
                            Log.d("YtExtractor", "Video $videoId: $clientName audio adaptive format has signatureCipher (needs decrypt)")
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            Log.d("YtExtractor", "Video $videoId: $clientName no adaptiveFormats array")
        }

        Log.i("YtExtractor", "Video $videoId: $clientName total links emitted=$found")
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
