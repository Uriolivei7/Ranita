package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.fasterxml.jackson.annotation.*
import com.lagradost.cloudstream3.mvvm.Resource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.delay

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.to"
    override var name = "AnimeKAI"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val TAG = "AnimeKai"
    private val decryptionApi = "https://enc-dec.app/api"

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    private suspend fun getCleanToken(text: String): String? {
        var result = ""
        repeat(2) { attempt ->
            try {
                val raw = app.get("$decryptionApi/enc-kai?text=$text").text
                result = if (raw.contains("result\":\"")) {
                    raw.substringAfter("result\":\"").substringBefore("\"")
                } else {
                    raw.replace("\"", "").trim()
                }

                if (result.isNotEmpty() && !result.contains("<html") && !result.contains("error code:")) {
                    return result
                }

                Log.e(
                    TAG,
                    "Logs: Intento ${attempt + 1} fallido (API offline o Error 500). Reintentando..."
                )
                if (attempt == 0) delay(1000)
            } catch (e: Exception) {
                Log.e(TAG, "Logs: Error en petición de token: ${e.message}")
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = listOf(
            Pair("$mainUrl/trending?page=$page", "En Tendencia"),
            Pair("$mainUrl/updates?page=$page", "Actualizados")
        )

        val lists = items.map { (url, title) ->
            val document = app.get(url, headers = apiHeaders).document
            val animeList = document.select("div.aitem-wrapper div.aitem").map { element: Element ->
                val link = element.selectFirst("a.poster")?.attr("href") ?: ""
                val name = element.selectFirst("a.title")?.text() ?: ""
                val poster = element.select("a.poster img").attr("data-src")

                newAnimeSearchResponse(name, link, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
            HomePageList(title, animeList)
        }
        return newHomePageResponse(lists, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browser?keyword=$query"
        val document = app.get(url, headers = apiHeaders).document

        return document.select("div.aitem-wrapper div.aitem").map { element: Element ->
            val link = element.selectFirst("a.poster")?.attr("href") ?: ""
            val name = element.selectFirst("a.title")?.text() ?: ""
            val poster = element.select("a.poster img").attr("data-src")

            newAnimeSearchResponse(name, link, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Logs: Iniciando load para $url")

        val response = app.get(url, headers = apiHeaders)
        val document = response.document
        val animeId = document.selectFirst("div[data-id]")?.attr("data-id") ?: return null

        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val poster =
            document.selectFirst(".poster img")?.attr("src") ?: document.selectFirst(".poster img")
                ?.attr("data-src")
        val description = document.selectFirst(".desc, .synopsis, .description")?.text()
        val year =
            document.select(".detail").filter { it.text().contains("Released") }.firstOrNull()
                ?.text()?.replace("Released:", "")?.trim()?.toIntOrNull()
        val genres = document.select(".detail a[href*='genre']").map { it.text() }

        val cleanToken = getCleanToken(animeId)
        if (cleanToken == null) {
            Log.e(TAG, "Logs: Falló validación de token tras reintentos (API Offline)")
            return null
        }

        Log.d(TAG, "Logs: Token obtenido con éxito: $cleanToken")

        val resJson = app.get(
            "$mainUrl/ajax/episodes/list?ani_id=$animeId&_=$cleanToken",
            headers = apiHeaders
        ).parsedSafe<ResultResponse>()
        val episodes = resJson?.toDocument()?.select("div.eplist a")?.map { element ->
            val token = element.attr("token")
            val epNum = element.attr("num").toIntOrNull() ?: 0
            val epTitle = element.selectFirst("span")?.text() ?: "Episode $epNum"

            newEpisode(token) {
                this.name = epTitle
                this.episode = epNum
            }
        }?.reversed() ?: emptyList()

        Log.d(TAG, "Logs: Se cargaron ${episodes.size} episodios")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanId = if (data.contains("/")) data.split("/").last() else data
        Log.d(TAG, "Logs: Iniciando loadLinks para ID limpio: $cleanId")

        val resource = safeApiCall {
            val cleanToken = getCleanToken(cleanId) ?: return@safeApiCall false

            val serverRes = app.get("$mainUrl/ajax/links/list?token=$cleanId&_=$cleanToken", headers = apiHeaders).parsedSafe<ResultResponse>()
            val serverDoc = serverRes?.toDocument() ?: return@safeApiCall false

            serverDoc.select("div.server-items[data-id]").forEach { typeGroup ->
                val type = typeGroup.attr("data-id")
                val typeSuffix = when (type) {
                    "sub" -> "HardSub"
                    "softsub" -> "SoftSub"
                    "dub" -> "Dub"
                    else -> type.uppercase()
                }

                typeGroup.select("span.server[data-lid]").forEach { serverElm ->
                    val lid = serverElm.attr("data-lid")
                    val serverName = serverElm.text()

                    val lidEnc = getCleanToken(lid) ?: return@forEach
                    val viewRes = app.get("$mainUrl/ajax/links/view?id=$lid&_=$lidEnc", headers = apiHeaders).parsedSafe<ResultResponse>()
                    val encodedLink = viewRes?.result ?: return@forEach

                    val iframeRes = app.post("$decryptionApi/dec-kai", json = mapOf("text" to encodedLink)).parsedSafe<IframeResponse>()
                    val iframeUrl = iframeRes?.result?.url ?: return@forEach

                    Log.d(TAG, "Logs: Cargando $typeSuffix: $serverName -> $iframeUrl")

                    if (iframeUrl.contains("megaup") || iframeUrl.contains("site")) {
                        loadMegaUpSource(iframeUrl, "$serverName ($typeSuffix)", callback, subtitleCallback)
                    }
                }
            }
            true
        }
        return resource is Resource.Success
    }

    private suspend fun loadMegaUpSource(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val parsedUrl = url.toHttpUrl()
            val token = parsedUrl.pathSegments.lastOrNull() ?: return
            val megaUrl = "${parsedUrl.scheme}://${parsedUrl.host}/media/$token"

            val megaToken = app.get(megaUrl, headers = apiHeaders).parsedSafe<ResultResponse>()?.result ?: return

            val megaUpResult = app.post("$decryptionApi/dec-mega",
                json = mapOf(
                    "text" to megaToken,
                    "agent" to apiHeaders["User-Agent"]
                )
            ).parsedSafe<MegaUpResponse>()?.result ?: return

            megaUpResult.tracks.forEach { track ->
                if (track.kind == "captions" && track.file.endsWith(".vtt")) {
                    Log.d(TAG, "Logs: Subtítulo Softsub detectado: ${track.label}")
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            track.label ?: "English",
                            track.file
                        )
                    )
                }
            }

            megaUpResult.sources.forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = name,
                        url = source.file,
                        type = if (source.file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://megaup.live/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logs: Error en loadMegaUpSource: ${e.message}")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultResponse(
    @JsonProperty("result") val result: String? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("status") val status: Int? = null
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result ?: "")
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IframeResponse(val result: IframeUrl? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IframeUrl(val url: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaUpResponse(
    @JsonProperty("result") val result: MegaUpResult? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaUpResult(
    @JsonProperty("sources") val sources: List<MegaUpSource> = emptyList(),
    @JsonProperty("tracks") val tracks: List<MegaUpTrack> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaUpSource(val file: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MegaUpTrack(val file: String, val label: String?, val kind: String)