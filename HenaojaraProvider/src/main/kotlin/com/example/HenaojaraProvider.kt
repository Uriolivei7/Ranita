package com.example

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HenaojaraProvider : MainAPI() {
    override var mainUrl = "https://ww1.henaojara.net"
    override var name = "HenaoJara"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    override val mainPage = mainPageOf(
        mainUrl to "Episodios nuevos",
        "${mainUrl}/animes?tipo=anime" to "Animes",
        "${mainUrl}/animes?tipo=pelicula" to "Peliculas",
        "${mainUrl}/animes?genero=accion&orden=desc" to "Acción",
        "${mainUrl}/animes?genero=ciencia-ficcion&orden=desc" to "Ciencia Ficción",
        "${mainUrl}/animes?genero=comedia&orden=desc" to "Comedia",
        "${mainUrl}/animes?genero=drama&orden=desc" to "Drama",
        "${mainUrl}/animes?genero=misterio&orden=desc" to "Misterio",
        "${mainUrl}/animes?genero=shounen&orden=desc" to "Shounen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.name == "Episodios nuevos"
        val url = if (isHome || page <= 1) request.data else "${request.data}${if (request.data.contains("?")) "&" else "?"}pag=$page"
        val items = app.get(url).document.select(if (isHome) "div.ul.hm article.li" else "div.ul article.li").mapNotNull { it.toEpisodePageResult() }
        return newHomePageResponse(request.name, items, !isHome && items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/animes?buscar=$query${if (page > 1) "&pag=$page" else ""}"
        val results = app.get(url).document.select("div.ul article.li").mapNotNull { it.toEpisodePageResult() }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun Element.toEpisodePageResult(): SearchResponse? {
        val title = selectFirst("h3.h a")?.text() ?: return null
        val ep = selectFirst("b.e")?.text() ?: ""
        val href = fixUrlNull(select(".mep li.ls a, li.ls a").attr("href").ifBlank { selectFirst("a")?.attr("href") }) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })

        return newAnimeSearchResponse(if (ep.isNotEmpty()) "$title - $ep" else title, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d("HenaoJara", "Iniciando carga: $url")

        var doc = app.get(url).document
        if (url.contains("/ver/")) {
            doc.selectFirst("li.ls a")?.attr("href")?.let {
                doc = app.get(fixUrl(it)).document
            }
        }

        val title = doc.selectFirst("div.info-b h1")?.text() ?: return null
        val mainPoster = fixUrlNull(doc.selectFirst("div.info-a img")?.attr("data-src") ?: doc.selectFirst("div.info-a img")?.attr("src"))
        val slug = doc.selectFirst("div.th")?.attr("data-sl") ?: doc.location().split("/").last()

        val scriptData = doc.select("script").map { it.data() }.firstOrNull { it.contains("var eps =") } ?: ""

        val episodes = Regex("""\[\s*"(\d+)"\s*,\s*"(\d+)"\s*,\s*"(.*?)"(?:\s*,\s*"(.*?)")?\s*]""").findAll(scriptData).map { match ->
            val num = match.groupValues[1]
            val id = match.groupValues[2] 
            val code = match.groupValues[3]
            val epThumb = match.groupValues.getOrNull(4)

            val finalPoster = if (!epThumb.isNullOrBlank() && epThumb != "null") fixUrlNull(epThumb) else mainPoster

            val epUrl = if (code.isNotEmpty()) {
                "$mainUrl/ver/$slug-$num-$code?id=$id"
            } else {
                "$mainUrl/ver/$slug-$num?id=$id"
            }

            newEpisode(epUrl) {
                this.name = "Episodio $num"
                this.episode = num.toIntOrNull()
                this.posterUrl = finalPoster
            }
        }.toList().sortedByDescending { it.episode }

        Log.i("HenaoJara", "Total episodios: ${episodes.size} para $title")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = mainPoster
            this.plot = doc.selectFirst("div.tx p")?.text()
            this.tags = doc.select("ul.gn li a").map { it.text() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.substringBefore("#")
        Log.d("HenaoJara", "Solicitando links: $cleanUrl")

        val document = app.get(cleanUrl).document
        val mainEncrypt = document.selectFirst(".opt")?.attr("data-encrypt") ?: return false

        val response = app.post(
            "$mainUrl/hj",
            data = mapOf("acc" to "opt", "i" to mainEncrypt),
            headers = mapOf("Referer" to cleanUrl, "X-Requested-With" to "XMLHttpRequest")
        ).text

        org.jsoup.Jsoup.parse(response).select("li[encrypt]").forEach { element ->
            try {
                val decodedUrl = element.attr("encrypt").chunked(2)
                    .map { it.toInt(16).toChar() }
                    .joinToString("")

                if (decodedUrl.startsWith("http")) {
                    loadExtractor(decodedUrl, cleanUrl, subtitleCallback) { link ->
                        kotlinx.coroutines.runBlocking {
                            val newLink = newExtractorLink(
                                source = link.source,
                                name = link.name,
                                url = link.url,
                                type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.extractorData = link.extractorData
                                this.headers = link.headers + mapOf(
                                    "X-Embed-Origin" to "ww1.henaojara.net",
                                    "X-Embed-Referer" to "https://ww1.henaojara.net/"
                                )
                            }
                            callback.invoke(newLink)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HenaoJara", "Error: ${e.message}")
            }
        }
        return true
    }
}