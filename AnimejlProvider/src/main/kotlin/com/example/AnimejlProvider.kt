package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Log

class AnimejlProvider : MainAPI() {
    override var mainUrl = "https://www.anime-jl.net"
    override var name = "AnimeJL"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Latino", "$mainUrl/animes?genre[]=46&order=updated"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Donghuas", "$mainUrl/animes?tipo[]=7&order=updated"),
            Pair("Peliculas", "$mainUrl/animes?tipo[]=3&order=updated"),
        )

        urls.amap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("ul.ListAnimes li").mapNotNull {
                val title = it.selectFirst("article.Anime h3.Title")?.text() ?: return@mapNotNull null
                val link = it.selectFirst("article.Anime a")?.attr("href") ?: return@mapNotNull null
                val img = it.selectFirst("article.Anime a div.Image figure img")?.attr("src")
                    ?.replaceFirst("^/".toRegex(), "$mainUrl/")

                newTvSeriesSearchResponse(title, link, TvType.Anime) {
                    posterUrl = img
                }

            }
            items.add(HomePageList(name, home))
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/animes?q=$query"
        val doc = app.get(url).document
        return doc.select("ul.ListAnimes li").mapNotNull {
            val title = it.selectFirst("article.Anime h3.Title")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("article.Anime a")?.attr("href") ?: return@mapNotNull null
            val img = it.selectFirst("article.Anime a div.Image figure img")?.attr("src")
                ?.replaceFirst("^/".toRegex(), "$mainUrl/")

            newTvSeriesSearchResponse(title, link, TvType.Anime) {
                posterUrl = img
            }
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.Ficha div.Container h1.Title")?.text() ?: ""
        val backimage = doc.selectFirst("div.Ficha div.Bg")?.attr("style")
            ?.substringAfter("background-image:url(")?.substringBefore(")") ?: ""
        val poster = doc.selectFirst("div.Container div.Image figure img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.Container main.Main section.WdgtCn div.Description")?.text() ?: ""
        val tags = doc.select("div.Container main.Main section.WdgtCn nav.Nvgnrs a").map { it.text() }

        val episodes = ArrayList<Episode>()
        val script = doc.select("script").firstOrNull { it.html().contains("var episodes =") }?.html()
        if (!script.isNullOrEmpty()) {
            val jsonscript = script.substringAfter("episodes = ").substringBefore(";").replace(",]", "]")
            val json = parseJson<List<List<String>>>(jsonscript)
            json.map { list ->
                var epNum = 0
                var epTitle = ""
                var epurl = ""
                var realimg = ""
                list.forEachIndexed { idx, it ->
                    when (idx) {
                        0 -> epNum = it.toIntOrNull() ?: 0
                        1 -> epurl = "$url/$it"
                        2 -> realimg = "$mainUrl/storage/$it"
                        3 -> epTitle = it.ifEmpty { "Episodio $epNum" }
                    }
                }
                episodes.add(
                    newEpisode(epurl) {
                        name = epTitle
                        episode = epNum
                        posterUrl = realimg
                        season = 0
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val regex = """<iframe\s[^>]*src=["']([^"']+)["']""".toRegex()
        val script = app.get(data).document.select("script")
            .firstOrNull { it.html().contains("var video = [];") }

        if (script == null) {
            Log.e("AnimejlProvider", "❌ No se encontró script con var video = [];")
            return false
        }

        val iframeUrls = regex.findAll(script.html()).map { it.groupValues[1] }.toList()
        if (iframeUrls.isEmpty()) {
            Log.e("AnimejlProvider", "❌ No se encontraron iframes en el script.")
            return false
        }

        iframeUrls.amap {
            Log.d("AnimejlProvider", "✅ Enviando iframe a extractor: $it")
            loadExtractor(it, data, subtitleCallback, callback)
        }

        return true
    }
}
