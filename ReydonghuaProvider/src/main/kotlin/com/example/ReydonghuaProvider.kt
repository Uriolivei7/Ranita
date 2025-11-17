package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.coroutineScope
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList


class ReydonghuaProvider : MainAPI() {
    companion object {
        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
    }

    override var mainUrl = "https://reydonghua.org"
    override var name = "ReyDonghua"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    private suspend fun getToken(url: String): Map<String, String> {
        val maintas = app.get(url)
        val token =
            maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
        val cookies = maintas.cookies
        latestToken = token
        latestCookie = cookies
        return latestCookie
    }

    //Yeji
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return getHomePage(request)
    }

    private suspend fun getHomePage(request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val sections = ArrayList<HomePageList>()

        val newEpisodesTitle = "Nuevos Episodios"
        val latestEpisodes = document.select("div.d-none.d-sm-block .row a[href^=\"https://reydonghua.org/ver/\"]").map {
            val title = it.selectFirst("h2.card-title")?.text() ?: ""
            val episodeUrl = fixUrl(it.attr("href"))
            val image = it.selectFirst("img.lazy")?.attr("data-src") ?: ""

            var seriesPath = episodeUrl.removePrefix("$mainUrl/ver/")
            val lastEpisodeIndex = seriesPath.lastIndexOf("-episodio-")
            if (lastEpisodeIndex != -1) {
                seriesPath = seriesPath.substring(0, lastEpisodeIndex)
            }

            val seriesUrl = "$mainUrl/donghua/$seriesPath"
            newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                this.posterUrl = fixUrl(image)
            }
        }.filter { it.name.isNotEmpty() }

        if (latestEpisodes.isNotEmpty()) {
            sections.add(HomePageList(newEpisodesTitle, latestEpisodes))
        }

        val recentSeriesTitle = "Series recientes"
        val latestSeries = document.select("h2:contains(Series recientes) + div.row a[href^=\"https://reydonghua.org/donghua/\"]").map {
            val title = it.selectFirst("h3.fs-6")?.text() ?: ""
            val url = fixUrl(it.attr("href"))
            val image = it.selectFirst("img.lazy")?.attr("data-src") ?: ""

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrl(image)
            }
        }.filter { it.name.isNotEmpty() }

        if (latestSeries.isNotEmpty()) {
            sections.add(HomePageList(recentSeriesTitle, latestSeries))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select("li.col").map {

            val title = it.selectFirst("h2")?.text() ?: ""

            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")

            val image = it.selectFirst("img")?.attr("data-src") ?: ""

            newAnimeSearchResponse(
                title,
                href,
                TvType.Anime,
            ){
                this.posterUrl = fixUrl(image)
                this.dubStatus = EnumSet.of(
                    DubStatus.Subbed
                )
            }
        }.filter { it.name.isNotEmpty() }
    }

    data class PaginateUrl(
        @JsonProperty("paginate_url") val paginateUrl: String,
    )

    data class CapList(
        @JsonProperty("caps") val caps: List<Ep>,
    )

    data class Ep(
        val episodio: Int?,
        val url: String?,
        val thumb: String? = null
    )

    suspend fun getCaps(caplist: String, referer: String): NiceResponse {
        val res = app.post(
            caplist,
            headers = mapOf(
                "Host" to URL(mainUrl).host,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to referer,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "TE" to "trailers"
            ),
            cookies = latestCookie,
            data = mapOf(
                "_token" to latestToken,
                "p" to "1",
                "order" to "1"
            )
        )
        latestCookie = res.cookies
        return res
    }

    override suspend fun load(url: String): LoadResponse {
        getToken(url)
        val doc = app.get(url, timeout = 120).document

        val caplist = doc.selectFirst(".caplist")?.attr("data-ajax")
            ?: throw ErrorLoadingException("No se encontró el selector de lista de capítulos, el sitio web ha cambiado su API de episodios.")

        val poster = doc.selectFirst("div.mierda img.lazy")?.attr("data-src")
            ?: doc.selectFirst(".d-md-none img.lazy")?.attr("data-src") ?: ""

        val title = doc.selectFirst(".mojon h1.text-light")?.text() ?: "Título no encontrado"

        val description = doc.selectFirst("div.col p.text-muted")?.text() ?: ""

        val genres = doc.select("div.lh-lg a span.badge.bg-secondary")
            .map { it.text() }

        val statusText = doc.selectFirst(".mb-4 .ms-2 div:nth-child(2)")?.text()

        val status = when (statusText?.trim()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }

        val pagurl = getCaps(caplist, url).parsed<PaginateUrl>()
        val capJson = getCaps(pagurl.paginateUrl, url).parsed<CapList>()

        val epList = capJson.caps.map { epnum ->
            newEpisode(
                epnum.url
            ) {
                this.episode = epnum.episodio
                this.posterUrl = epnum.thumb
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = fixUrl(poster)
            backgroundPosterUrl = fixUrl(poster)
            addEpisodes(DubStatus.Subbed, epList)
            showStatus = status
            plot = description
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val doc = app.get(data).document

        doc.select("ul.nav-tabs button.play-video").map { element ->
            val encodedurl = element.attr("data-player")

            if (encodedurl.isNotEmpty()) {
                val urlDecoded = base64Decode(encodedurl).replace("https://playerwish.com", "https://streamwish.to")

                loadExtractor(urlDecoded, mainUrl, subtitleCallback, callback)
            }
        }
        true
    }
}