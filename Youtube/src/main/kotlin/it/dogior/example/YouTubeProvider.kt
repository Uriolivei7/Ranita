package it.dogior.example

import android.content.SharedPreferences
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.amap
import android.util.Log
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

class YoutubeProvider(
    language: String = "en",
    private val sharedPrefs: SharedPreferences? = null
) : MainAPI() {

    override var mainUrl = MAIN_URL
    override var name = "YouTube"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override var lang = language

    private val ytParser = YouTubeParser(this.name)

    companion object {
        const val MAIN_URL = "https://www.youtube.com"
        const val TAG = "Youtube"
        const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    private var youtubeCookie: String?
        get() = sharedPrefs?.getString("youtube_cookie", null)
        set(value) {
            sharedPrefs?.edit()?.putString("youtube_cookie", value)?.apply()
        }

    init {
        try {
            val downloader = NewPipe.getDownloader()

            val fields = downloader.javaClass.declaredFields
            val uaField = fields.find { it.name == "userAgent" }
            uaField?.isAccessible = true
            uaField?.set(downloader, PC_USER_AGENT)

            youtubeCookie?.let { cookie ->
                val cookieField = fields.find { it.name == "cookie" || it.name == "cookies" }
                if (cookieField != null) {
                    cookieField.isAccessible = true
                    cookieField.set(downloader, cookie)
                    Log.d(TAG, "Cookie aplicada exitosamente.")
                } else {
                    Log.w(TAG, "No se encontró campo de cookie, usando modo alternativo.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en inicialización: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "Iniciando getMainPage para página: $page")

        val isTrendingEnabled = sharedPrefs?.getBoolean("trending", true) ?: true
        val sections = mutableListOf<HomePageList>()

        if (isTrendingEnabled) {
            try {
                val videos = ytParser.getTrendingVideoUrls(page)
                videos?.let { sections.add(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error en tendencias: ${e.message}")
            }
        }

        val playlistsData = sharedPrefs?.getStringSet("playlists", emptySet()) ?: emptySet()
        if (playlistsData.isNotEmpty()) {
            val triples = playlistsData.map { parseJson<Triple<String, String, Long>>(it) }
            val list = triples.amap { data ->
                val playlistUrl = data.first
                try {
                    val urlPath = playlistUrl.substringAfter("youtu").substringAfter("/")
                    val isPlaylist = urlPath.startsWith("playlist?list=")
                    val isChannel = urlPath.startsWith("@") || urlPath.startsWith("channel")

                    val customSections = if (isPlaylist && !isChannel) {
                        ytParser.playlistToSearchResponseList(playlistUrl, page)
                    } else if (!isPlaylist && isChannel) {
                        ytParser.channelToSearchResponseList(playlistUrl, page)
                    } else null

                    customSections to data.third
                } catch (e: Exception) {
                    Log.e(TAG, "Error cargando sección personalizada: ${e.message}")
                    null to data.third
                }
            }
            list.sortedBy { it.second }.forEach {
                it.first?.let { section -> sections.add(section) }
            }
        }

        if (sections.isEmpty()) {
            sections.add(HomePageList("No content available. Check settings.", emptyList()))
        }

        return newHomePageResponse(sections, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            ytParser.parseSearch(query).filterIsInstance<MovieSearchResponse>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return ytParser.videoToLoadResponse(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val linkHandler = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(data)
            val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)

            extractor.fetchPage()

            val refererUrl = extractor.url

            extractor.videoStreams?.filterNotNull()?.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val resolutionName = stream.resolution ?: return@forEach

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

            extractor.subtitlesDefault?.filterNotNull()?.forEach { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(sub.languageTag ?: "Unknown", sub.url ?: return@forEach)
                )
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo en loadLinks (Samsung A06): ${e.message}")
            false
        }
    }
}