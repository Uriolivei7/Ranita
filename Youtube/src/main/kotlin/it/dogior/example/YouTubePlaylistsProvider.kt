package it.dogior.example

import android.util.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.stream.StreamInfo

class YouTubePlaylistsProvider(language: String) : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "YouTube Playlists"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override var lang = language
    private val ytParser = YouTubeParser(this.name)

    companion object{
        const val MAIN_URL = "https://www.youtube.com"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = ytParser.parseSearch(query)

        return allResults?.filterIsInstance<TvSeriesSearchResponse>() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/watch?v=") || url.contains("/channel/") || url.contains("/user/")) {
            return null
        }

        try {
            val video = ytParser.playlistToLoadResponse(url)
            return video
        } catch (e: Exception) {
            logError(e)
            Log.e("Youtube", "Error al cargar la Playlist $url", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        val info = StreamInfo.getInfo(data)
        val refererUrl = info.url

        info.videoStreams
            .sortedByDescending { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 0 }
            .forEach { stream ->

                val streamUrl = stream.url ?: return@forEach
                val resolutionName = stream.resolution ?: return@forEach

                if (resolutionName.removeSuffix("p").toIntOrNull() == null) {
                    return@forEach
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Video - $resolutionName",
                        url = streamUrl
                    ) {
                        this.referer = refererUrl
                    }
                )
            }

        info.subtitles.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            val subName = sub.languageTag ?: return@forEach

            subtitleCallback.invoke(
                SubtitleFile(
                    subName,
                    subUrl
                )
            )
        }

        return true
    }
}