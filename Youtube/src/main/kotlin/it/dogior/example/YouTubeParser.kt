package it.dogior.example

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Date
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem

class YouTubeParser(override var name: String) : MainAPI() {

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val service = ServiceList.YouTube
        val kiosks = service.kioskList
        val trendingsUrl = kiosks.defaultKioskExtractor.url
        val infoItem = KioskInfo.getInfo(ServiceList.YouTube, trendingsUrl)

        val videos = if (page == 1) {
            infoItem.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = infoItem.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = infoItem.nextPage
            while (count < page && hasNext) {
                val more = KioskInfo.getMoreItems(ServiceList.YouTube, trendingsUrl, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.filter { !it.isShortFormContent }.map { video ->
            this.newMovieSearchResponse(
                name = video.name,
                url = video.url,
                type = TvType.Others
            ).apply {
                this.posterUrl = video.thumbnails.last().url
            } as SearchResponse
        }
        return HomePageList(
            name = "Trending",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val videos = if (page == 1) {
            playlistInfo.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = playlistInfo.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (count < page && hasNext) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map { video ->
            this.newMovieSearchResponse(
                name = video.name,
                url = video.url,
                type = TvType.Others
            ).apply {
                this.posterUrl = video.thumbnails.last().url
            } as SearchResponse
        }
        return HomePageList(
            name = "${playlistInfo.uploaderName}: ${playlistInfo.name}",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        val channelInfo = ChannelInfo.getInfo(url)
        val tabsLinkHandlers = channelInfo.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
        val videoTab = tabs.first { it.name == "videos" }

        val videos = if (page == 1) {
            videoTab.relatedItems.toMutableList()
        } else {
            mutableListOf<InfoItem>()
        }

        if (page > 1) {
            var hasNext = videoTab.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = videoTab.nextPage
            while (count < page && hasNext) {

                val videoTabHandler = tabsLinkHandlers.first{it.url.endsWith("/videos")}
                val more = ChannelTabInfo.getMoreItems(ServiceList.YouTube, videoTabHandler, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map { video ->
            this.newMovieSearchResponse(
                name = video.name,
                url = video.url,
                type = TvType.Others
            ).apply {
                this.posterUrl = video.thumbnails.last().url
            } as SearchResponse
        }
        return HomePageList(
            name = channelInfo.name,
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun parseSearch(
        query: String,
    ): List<SearchResponse> {

        val handlerFactory = ServiceList.YouTube.searchQHFactory

        val searchHandler = handlerFactory.fromQuery(
            query,
            emptyList(),
            null
        )

        val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, SearchQueryHandler(searchHandler))

        val finalResults = searchInfo.getRelatedItems()?.mapNotNull { item ->
            val name = item.name.toString()
            val url = item.url
            val poster = item.thumbnails.lastOrNull()?.url

            when (item) {
                is StreamInfoItem -> {
                    newMovieSearchResponse(
                        name = name,
                        url = url,
                    ).apply {
                        this.type = TvType.Others
                        this.posterUrl = poster
                    }
                }

                is ChannelInfoItem, is PlaylistInfoItem -> {
                    newTvSeriesSearchResponse(
                        name = name,
                        url = url,
                    ).apply {
                        this.type = TvType.Others
                        this.posterUrl = poster
                    }
                }

                else -> null
            }
        }
        return finalResults ?: emptyList()
    }

    suspend fun videoToLoadResponse(videoUrl: String): LoadResponse {
        val CURRENT_TAG = "YT_REC"
        Log.e(CURRENT_TAG, "--- INICIO Carga LoadResponse para URL: $videoUrl ---")

        val videoInfo = try {
            StreamInfo.getInfo(videoUrl)
        } catch (e: Exception) {
            Log.e(CURRENT_TAG, "ERROR CRÍTICO en StreamInfo.getInfo: ${e.message}")
            throw e
        }

        val views = "Views: ${videoInfo.viewCount}"
        val likes = "Likes: ${videoInfo.likeCount}"
        val length = videoInfo.duration / 60

        val rawRecommendations = try {
            videoInfo.relatedStreams
        } catch (e: Exception) {
            emptyList()
        }

        Log.e(CURRENT_TAG, "RECOMENDACIONES RAW encontradas: ${rawRecommendations.size}")

        val recommendations = rawRecommendations.mapNotNull { item ->
            val video = item as? StreamInfoItem ?: return@mapNotNull null

            if (video.name.isNullOrBlank() || video.url.isNullOrBlank()) return@mapNotNull null

            this.newMovieSearchResponse(
                name = video.name,
                url = video.url,
                type = TvType.Others
            ).apply {
                this.posterUrl = video.thumbnails.lastOrNull()?.url
            } as SearchResponse
        }

        return this.newMovieLoadResponse(
            name = videoInfo.name,
            url = videoUrl,
            dataUrl = videoUrl,
            type = TvType.Others
        ).apply {
            this.posterUrl = videoInfo.thumbnails.lastOrNull()?.url
            this.plot = videoInfo.description?.content ?: ""
            this.tags = listOf(videoInfo.getUploaderName() ?: "Unknown", views, likes)
            this.duration = length.toInt()
            this.recommendations = recommendations
        }
    }

    suspend fun channelToLoadResponse(url: String): LoadResponse {
        val channelInfo = ChannelInfo.getInfo(url)

        val avatars = try {
            channelInfo.avatars.last().url
        } catch (e: Exception){
            null
        }
        val banners = try {
            channelInfo.banners.last().url
        } catch (e: Exception){
            null
        }
        val tags = mutableListOf("Subscribers: ${channelInfo.subscriberCount}")

        return this.newTvSeriesLoadResponse(
            name = channelInfo.name,
            url = url,
            type = TvType.Others,
            episodes = getChannelVideos(channelInfo)
        ).apply {
            this.posterUrl = avatars
            this.backgroundPosterUrl = banners
            this.plot = channelInfo.description
            this.tags = tags
        }
    }

    private fun getChannelVideos(channel: ChannelInfo): List<Episode> {
        val tabsLinkHandlers = channel.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
        val videoTab = tabs.first { it.name == "videos" }
        val videos = videoTab.relatedItems.mapNotNull { video ->
            newEpisode(
                url = video.url
            ).apply {
                this.name = video.name
                this.posterUrl = video.thumbnails.last().url
            }
        }
        return videos.reversed()
    }

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val tags = mutableListOf("Channel: ${playlistInfo.uploaderName}")
        val banner =
            if (playlistInfo.banners.isNotEmpty()) playlistInfo.banners.last().url else playlistInfo.thumbnails.last().url
        val eps = playlistInfo.relatedItems.toMutableList()
        var hasNext = playlistInfo.hasNextPage()
        var count = 1
        var nextPage = playlistInfo.nextPage
        while (hasNext) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
            eps.addAll(more.items)
            hasNext = more.hasNextPage()
            nextPage = more.nextPage
            count++
            if (count >= 10) break
//            Log.d("YouTubeParser", "Page ${count + 1}: ${more.items.size}")
        }
        return this.newTvSeriesLoadResponse(
            name = playlistInfo.name,
            url = url,
            type = TvType.Others,
            episodes = getPlaylistVideos(eps)
        ).apply {
            this.posterUrl = playlistInfo.thumbnails.last().url
            this.backgroundPosterUrl = banner
            this.plot = playlistInfo.description.content
            this.tags = tags
        }
    }

    private fun getPlaylistVideos(videos: List<StreamInfoItem>): List<Episode> {
        val episodes = videos.map { video ->
            newEpisode(
                url = video.url
            ).apply {
                this.name = video.name
                this.posterUrl = video.thumbnails.last().url
                this.runTime = (video.duration / 60).toInt()
                video.uploadDate?.let { addDate(Date(it.date().timeInMillis)) }
            }
        }
        return episodes
    }
}