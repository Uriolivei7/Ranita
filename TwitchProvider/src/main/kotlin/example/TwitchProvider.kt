package example

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.mapper
import android.util.Log
import org.jsoup.nodes.Element
import java.lang.RuntimeException


class TwitchProvider : MainAPI() {
    override var mainUrl = "https://twitchtracker.com"
    override var name = "Twitch"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "es"
    override val hasMainPage = true
    private val gamesName = "games"
    private val TAG = "TwitchProvider"

    override val mainPage = mainPageOf(
        "$mainUrl/channels/live" to "Streams",
        "$mainUrl/channels/live/spanish" to "Streams en Español",
        "$mainUrl/channels/live/english" to "Streams en Inglés",
        "$mainUrl/channels/live/portuguese" to "Streams en Portugués",
        "$mainUrl/games" to gamesName
    )

    private val isHorizontal = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.e(TAG, "getMainPage - Solicitando datos para: ${request.name}")
        return when (request.name) {
            gamesName -> newHomePageResponse(parseGames(), hasNext = false)
            else -> {
                val url = request.data
                val doc = app.get(url, params = mapOf("page" to page.toString())).document

                Log.e(TAG, "getMainPage - Longitud del cuerpo: ${doc.body().html().length}. ¿Contiene Cloudflare? ${doc.body().html().contains("Cloudflare")}")

                val channels = doc.select("table#channels tr").map { element ->
                    element.toLiveSearchResponse()
                }

                val finalChannels = channels

                Log.e(TAG, "getMainPage - Canales encontrados: ${finalChannels.size}")

                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name,
                            finalChannels,
                            isHorizontalImages = isHorizontal
                        )
                    ),
                    hasNext = true
                )
            }
        }
    }

    private fun Element.toLiveSearchResponse(): LiveSearchResponse {

        val anchor = this.select("td a[href^='/']").first()
        val linkName = anchor?.attr("href")?.substringAfterLast("/") ?: ""

        val name = this.select("a.item-title").text().ifBlank { anchor?.text() }

        val image = this.select("img").attr("src")

        if (name.isNullOrBlank() || linkName.isBlank()) {
            Log.e(TAG, "toLiveSearchResponse - FALLO al extraer datos del canal.")
        } else {
            Log.e(TAG, "toLiveSearchResponse - Extrayendo canal: $name, LinkName: $linkName")
        }


        return newLiveSearchResponse(
            name?.trim() ?: "",
            linkName,
            TvType.Live,
            fix = false
        ) { posterUrl = image }
    }

    private suspend fun parseGames(): List<HomePageList> {
        val doc = app.get("$mainUrl/games").document
        return doc.select("div.ranked-item")
            .take(5)
            .mapNotNull { element ->
                val game = element.select("div.ri-name > a")
                val url = fixUrl(game.attr("href"))
                val name = game.text()
                val searchResponses = parseGame(url).ifEmpty { return@mapNotNull null }
                HomePageList(name, searchResponses, isHorizontalImages = isHorizontal)
            }
    }

    private suspend fun parseGame(url: String): List<LiveSearchResponse> {
        val doc = app.get(url).document
        return doc.select("td.cell-slot.sm").map { element ->
            element.toLiveSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.e(TAG, "load - URL recibida: $url")
        val realUrl = url.substringAfterLast("/")
        Log.e(TAG, "load - Nombre de canal extraído: $realUrl")

        val doc = app.get("$mainUrl/$realUrl", referer = mainUrl).document
        val name = doc.select("div#app-title").text()
        if (name.isBlank()) {
            throw RuntimeException("Could not load page, please try again.\n")
        }
        val rank = doc.select("div.rank-badge > span").last()?.text()?.toIntOrNull()
        val image = doc.select("div#app-logo > img").attr("src")
        val poster = doc.select("div.embed-responsive > img").attr("src").ifEmpty { image }
        val description = doc.select("div[style='word-wrap:break-word;font-size:12px;']").text()
        val language = doc.select("a.label.label-soft").text().ifEmpty { null }
        val isLive = doc.select("div.live-indicator-container").isNotEmpty()

        val tags = listOfNotNull(
            isLive.let { if (it) "Live" else "Offline" },
            language,
            rank?.let { "Rank: $it" },
        )

        val twitchUrl = "https://twitch.tv/$realUrl"
        Log.e(TAG, "load - URL de Twitch (Data para Extractor): $twitchUrl")

        return newLiveStreamLoadResponse(
            name, twitchUrl, twitchUrl
        ) {
            plot = description
            posterUrl = image
            backgroundPosterUrl = poster
            this@newLiveStreamLoadResponse.tags = tags
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        Log.e(TAG, "search - Query: $query")
        val document =
            app.get("$mainUrl/search", params = mapOf("q" to query), referer = mainUrl).document

        Log.e(TAG, "search - Longitud del cuerpo: ${document.body().html().length}. ¿Contiene Cloudflare? ${document.body().html().contains("Cloudflare")}")

        val results = document.select("table.tops tr").map { it.toLiveSearchResponse() }.drop(1)

        Log.e(TAG, "search - Resultados encontrados: ${results.size}")

        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks - DATA enviada al Extractor: $data")
        TwitchExtractor().getUrl(data, null, subtitleCallback, callback)

        return true
    }
}