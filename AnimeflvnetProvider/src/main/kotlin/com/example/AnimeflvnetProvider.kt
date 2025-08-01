package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*

class AnimeflvnetProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://www3.animeflv.net"
    override var name = "AnimeFLV"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/browse?type[]=movie&order=updated", "Películas"),
            Pair("$mainUrl/browse?status[]=2&order=default", "Animes"),
            Pair("$mainUrl/browse?status[]=1&order=rating", "En emision"),
        )
        val items = ArrayList<HomePageList>()
        val isHorizontal = true
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("main.Main ul.ListEpisodios li").mapNotNull {
                    val title = it.selectFirst("strong.Title")?.text() ?: return@mapNotNull null
                    val poster = it.selectFirst("span img")?.attr("src") ?: return@mapNotNull null
                    val epRegex = Regex("(-(\\d+)\$)")
                    val url = it.selectFirst("a")?.attr("href")?.replace(epRegex, "")
                        ?.replace("ver/", "anime/") ?: return@mapNotNull null
                    val epNum =
                        it.selectFirst("span.Capi")?.text()?.replace("Episodio ", "")?.toIntOrNull()
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title), epNum)
                    }
                }, isHorizontal)
        )

        urls.apmap { (url, name) ->
            val doc = app.get(url).document
            val home = doc.select("ul.ListAnimes li article").mapNotNull {
                val title = it.selectFirst("h3.Title")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst("figure img")?.attr("src") ?: return@mapNotNull null
                newAnimeSearchResponse(
                    title,
                    fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                ) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                }
            }

            items.add(HomePageList(name, home))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    data class SearchObject(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val response = app.post(
            "https://www3.animeflv.net/api/animes/search",
            data = mapOf(Pair("value", query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        return json.map { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/animes/covers/${searchr.id}.jpg"
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrl(image)
                addDubStatus(getDubStatus(title))
            }
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/browse?q=$query").document
        val sss = doc.select("ul.ListAnimes article").map { ll ->
            val title = ll.selectFirst("h3")?.text() ?: ""
            val image = ll.selectFirst("figure img")?.attr("src") ?: ""
            val href = ll.selectFirst("a")?.attr("href") ?: ""
            newAnimeSearchResponse(title, href){
                this.posterUrl = image
                addDubStatus(getDubStatus(title))
            }
        }
        return sss
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()
        val title = doc.selectFirst("h1.Title")!!.text()
        val poster = doc.selectFirst("div.AnimeCover div.Image figure img")?.attr("src")!!
        val description = doc.selectFirst("div.Description p")?.text()
        val type = doc.selectFirst("span.Type")?.text() ?: ""
        val status = when (doc.selectFirst("p.AnmStts span")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("nav.Nvgnrs a")
            .map { it?.text()?.trim().toString() }

        doc.select("script").map { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach {

                    val epNum = it.removePrefix("[").substringBefore(",")
                    val animeid = doc.selectFirst("div.Strs.RateIt")?.attr("data-id")
                    val link = url.replace("/anime/", "/ver/") + "-$epNum"
                    episodes.add(
                        newEpisode(link) {
                            this.episode = epNum.toIntOrNull()
                            this.runTime = null
                        }
                    )
                }
            }
        }
        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = fixUrl(poster)
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
        }
    }

    data class MainServers(
            @JsonProperty("SUB")
            val sub: List<Sub>,
    )

    data class Sub(
            val code: String,
    )


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            app.get(data).document.select("script").apmap { script ->
                if (script.data().contains("var videos = {") || script.data()
                        .contains("var anime_id =") || script.data().contains("server")
                ) {
                    println("Script encontrado: ${script.data().substring(0, minOf(script.data().length, 500))}")

                    val serversRegex = Regex("var videos = (\\{\"SUB\":\\[\\{.*?\\}\\]\\});")
                    val serversplain = serversRegex.find(script.data())?.destructured?.component1() ?: ""

                    if (serversplain.isBlank()) {
                        println("No se encontró JSON válido en el script")
                        return@apmap
                    }

                    try {
                        val json = parseJson<MainServers>(serversplain)
                        json.sub.apmap {
                            val code = it.code
                            println("Procesando servidor con código: $code")
                            loadExtractor(code, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        println("Error al analizar JSON: ${e.message}, JSON: $serversplain")
                    }
                }
            }
        } catch (e: Exception) {
            println("Error general en loadLinks: ${e.message}")
            return false
        }
        return true
    }
}
