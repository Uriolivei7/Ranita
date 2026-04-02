package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import kotlin.collections.ArrayList

class MonoschinosProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
    }

    override var mainUrl = "https://monoschinos2.com"
    override var name = "MonosChinos"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    private suspend fun getToken(url: String): Map<String, String> {
        val maintas = app.get(url)
        val token = maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
        val cookies = maintas.cookies
        latestToken = token
        latestCookie = cookies
        return latestCookie
    }


    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/emision", "Recientes"),
            Pair("$mainUrl/animes", "Animes"),
        )
        val items = ArrayList<HomePageList>()
        val isHorizontal = true
        items.add(
            HomePageList(
                "Capítulos actualizados",
                app.get(mainUrl, timeout = 120).document.select(".row-cols-xl-4 li article").map {
                    val title = it.selectFirst("h2")?.text() ?: it.selectFirst("h2.text-truncate")?.text() ?: ""
                    val poster =
                        it.selectFirst("img")?.attr("data-src") ?: ""

                    val epRegex = Regex("episodio-(\\d+)")
                    val url = it.selectFirst("a")?.attr("href")!!.replace("ver/", "anime/")
                        .replace(epRegex, "sub-espanol")
                    val epNum = (it.selectFirst("article span.episode")?.text() ?: it.selectFirst("div.positioning p")?.text())?.toIntOrNull()
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title), epNum)
                    }
                }, isHorizontal)
        )

        urls.amap { (url, name) ->

            val home = app.get(url, timeout = 120).document.select("li.col").map {
                val title = it.selectFirst("h3")!!.text()
                val poster =
                    it.selectFirst("img")?.attr("data-src")
                        ?: ""

                newAnimeSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href"))) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select("li.col article").map {
            val title = it.selectFirst("h3")!!.text()
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("img")!!.attr("data-src")
            newAnimeSearchResponse(
                title,
                href,
                TvType.Anime,
            ){
                this.posterUrl = fixUrl(image)
                this.dubStatus = if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed)

            }
        }
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<Ep>? = null,
        @JsonProperty("caps") val caps: List<Ep>? = null,
    )

    data class Ep(
        @JsonProperty("num") val num: Int? = null,
        @JsonProperty("episodio") val episodio: Int? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        getToken(url)
        val doc = app.get(url, timeout = 120).document
        val caplist = doc.selectFirst(".caplist")!!.attr("data-ajax")
        val poster = doc.selectFirst("img.w-100")!!.attr("data-src")
        val backimage = doc.selectFirst("img.rounded-3")!!.attr("data-src")
        val title = doc.selectFirst(".fs-2")!!.text()
        val type = doc.selectFirst("div.bg-transparent > dl:nth-child(1) > dd:nth-child(2)")?.text() ?: ""
        val description = doc.selectFirst("div.mb-3")!!.text().replace("Ver menos", "")
        val genres = doc.select(".my-4 > div a span").map { it.text() }
        val status = when (doc.selectFirst("div.col:nth-child(1) > div:nth-child(1) > div")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val capJson = app.post(caplist,
            headers = mapOf(
                "Host" to "monoschinos2.com",
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to url,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "DNT" to "1",
                "Alt-Used" to "monoschinos2.com",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "TE" to "trailers"
            ),
            cookies = latestCookie,
            data = mapOf("_token" to latestToken)).parsed<CapList>()

        val allEpisodes = capJson.eps ?: capJson.caps ?: emptyList()

        val epList = allEpisodes.map { ep ->
            val episodeNumber = ep.episodio ?: ep.num
            val epUrl = ep.url ?: "${url.replace("-sub-espanol","").replace("/anime/","/ver/")}-episodio-$episodeNumber"

            val thumbUrl = if (!ep.thumb.isNullOrBlank()) {
                if (ep.thumb.startsWith("http")) ep.thumb else "$mainUrl${ep.thumb}"
            } else {
                backimage
            }

            newEpisode(epUrl) {
                this.episode = episodeNumber
                this.posterUrl = thumbUrl
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
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
    ): Boolean {
        app.get(data).document.select("#myTab li").amap {
            val encodedurl = it.select(".play-video").attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            val url = (urlDecoded).replace("https://monoschinos2.com/reproductor?url=", "")
                .replace("https://sblona.com","https://watchsb.com").replace("https://swdyu.com","https://streamwish.to")
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}