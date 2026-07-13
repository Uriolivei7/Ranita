package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import kotlin.collections.ArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MundoDonghuaProvider : MainAPI() {
    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl, timeout = 120).document.select("div.md-card-grid:not(.md-card-grid-limited) div.md-card:not(.limited) > a").mapNotNull { a ->
                    val title = a.selectFirst(".md-card-title")?.text() ?: return@mapNotNull null
                    val poster = a.selectFirst(".md-card-img img")?.attr("src") ?: return@mapNotNull null
                    val href = a.attr("href")
                    val slug = href.substringAfter("/ver/").substringBefore("/")
                    newAnimeSearchResponse(title, fixUrl("/donghua/$slug"), TvType.Anime) {
                        this.posterUrl = fixUrl(poster)
                    }
                }
            )
        )

        coroutineScope {
            listOf(
                "$mainUrl/lista-donghuas" to "Donghuas"
            ).map { (url, name) ->
                async {
                    val home = app.get(url, timeout = 120).document.select("div.md-card-grid div.md-card > a").mapNotNull { a ->
                        val title = a.selectFirst(".md-card-title")?.text() ?: return@mapNotNull null
                        val poster = a.selectFirst(".md-card-img img")?.attr("src") ?: ""
                        val href = a.attr("href")
                        newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                            this.posterUrl = fixUrl(poster)
                        }
                    }
                    items.add(HomePageList(name, home))
                }
            }.awaitAll()
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/busquedas/$query", timeout = 120).document.select("div.md-card-grid div.md-card > a").mapNotNull {
            val title = it.selectFirst(".md-card-title")?.text() ?: return@mapNotNull null
            val href = fixUrl(it.attr("href"))
            val image = it.selectFirst(".md-card-img img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = fixUrl(image ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""
        val title = doc.selectFirst(".md-detail-title")?.text() ?: ""
        val description = doc.selectFirst(".md-detail-synopsis")?.text() ?: ""
        val genres = doc.select("div.md-genre-tags a.md-genre-tag").map { it.text() }
        val status = when (doc.selectFirst(".md-emision-badge")?.text()?.trim()) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val typeBadge = doc.selectFirst(".md-card-badge.md-badge-static")?.text()?.trim()
        val tvType = if (typeBadge == "Película" || typeBadge == "Pelicula") TvType.AnimeMovie else TvType.Anime

        val newEpisodes = ArrayList<Episode>()
        doc.select("ul.md-episode-list li.md-episode-item").mapNotNull { li ->
            val epNum = li.attr("data-ep").toIntOrNull()
            val link = li.selectFirst("a.md-ep-link")?.attr("href") ?: return@mapNotNull null
            val epPoster = li.selectFirst(".md-episode-thumb img")?.attr("src")
            newEpisodes.add(newEpisode(fixUrl(link)) {
                this.episode = epNum
                this.posterUrl = if (!epPoster.isNullOrEmpty()) fixUrl(epPoster) else null
            })
        }

        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, newEpisodes.sortedBy { it.episode })
            showStatus = status
            plot = description
            tags = genres
        }
    }

    data class Protea (
        @JsonProperty("source") val source: List<Source>,
        @JsonProperty("poster") val poster: String?
    )

    data class Source (
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("default") val default: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val datafix = data.replace("ñ", "%C3%B1")
        val reqHEAD = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to datafix,
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "TE" to "trailers"
        )
        var linksFound = false
        coroutineScope {
            app.get(data).document.select("script").forEach { script ->
                if (script.data().contains("eval(function(p,a,c,k,e")) {
                    val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                    packedRegex.findAll(script.data()).forEach { match ->
                        val unpack = getAndUnpack(match.value).replace("diasfem", "embedsito")
                        fetchUrls(unpack).forEach { url ->
                            val newUrl = url.replace("https://sbbrisk.com", "https://watchsb.com")
                            if (loadExtractor(newUrl, data, subtitleCallback, callback)) {
                                linksFound = true
                            }
                        }
                        if (unpack.contains("protea_tab")) {
                            val protearegex = Regex("protea_tab.*slug.*\\\"(.*)\\\".*,type")
                            val ssee = protearegex.find(unpack)?.destructured?.component1()
                            if (!ssee.isNullOrEmpty()) {
                                val aa = app.get("$mainUrl/api_donghua.php?slug=$ssee", headers = reqHEAD).text
                                val secondK = aa.substringAfter("url\":\"").substringBefore("\"}")
                                val se = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$secondK"
                                val aa3 = app.get(se, headers = reqHEAD, allowRedirects = false).text
                                val idReg = Regex("video.*\\\"(.*?)\\\"")
                                val vidID = idReg.find(aa3)?.destructured?.component1()
                                if (!vidID.isNullOrEmpty()) {
                                    val newLink = "https://www.dailymotion.com/embed/video/$vidID"
                                    if (loadExtractor(newLink, data, subtitleCallback, callback)) {
                                        linksFound = true
                                    }
                                }
                            }
                        }
                        if (unpack.contains("asura_player")) {
                            val asuraRegex = Regex("file.*\\\"(.*)\\\".*type")
                            val aass = asuraRegex.find(unpack)?.destructured?.component1()
                            if (!aass.isNullOrEmpty()) {
                                val test = app.get(aass).text
                                if (test.contains(Regex("#EXTM3U"))) {
                                    generateM3u8("Asura", aass, "").forEach { link ->
                                        callback(link)
                                        linksFound = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return linksFound
    }
}