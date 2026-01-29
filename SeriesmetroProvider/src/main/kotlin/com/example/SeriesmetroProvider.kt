package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

class SeriesmetroProvider : MainAPI() {
    override var mainUrl = "https://www3.seriesmetro.net"
    override var name = "SeriesMetro"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Movie
    )

    private val TAG = "MetroSeries"

    private fun fixImg(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var cleanUrl = url.trim()

        if (cleanUrl.startsWith("//")) {
            cleanUrl = "https:$cleanUrl"
        }
        else if (cleanUrl.startsWith("/")) {
            cleanUrl = "$mainUrl$cleanUrl"
        }

        return cleanUrl
    }

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    private fun Element.getMetroPoster(): String? {
        val img = this.selectFirst(".post-thumbnail img, img") ?: return null
        val lazyPoster = img.attr("data-lazy-src").takeIf { it.isNotBlank() }
        val dataPoster = img.attr("data-src").takeIf { it.isNotBlank() }
        val normalPoster = img.attr("src").takeIf { it.isNotBlank() && !it.contains("data:image") }

        val finalPoster = lazyPoster ?: dataPoster ?: normalPoster

        return fixImg(finalPoster)?.replace("/w185/", "/w500/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val sections = listOf(
            Pair("Últimas series agregadas", ".series article"),
            Pair("Ultimas peliculas agregadas", ".movies article")
        )

        val soup = app.get(mainUrl, headers = headers).document

        sections.forEach { (name, selector) ->
            val home = soup.select(selector).mapNotNull { element ->
                val title = element.selectFirst(".entry-title")?.text() ?: return@mapNotNull null
                val href = element.selectFirst("a.lnk-blk")?.attr("abs:href")
                    ?: element.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null

                val poster = element.getMetroPoster()

                Log.d(TAG, "Logs: [DEBUG MAIN] $title -> $poster")

                newTvSeriesSearchResponse(title, href, if (href.contains("pelicula")) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            if (home.isNotEmpty()) items.add(HomePageList(name, home))
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Logs: Buscando: $query")
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = headers).document

        return document.select("div#aa-movies article.post, article.post").mapNotNull { element ->
            val title = element.selectFirst(".entry-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a.lnk-blk")?.attr("abs:href")
                ?: element.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null

            val poster = element.getMetroPoster()

            Log.d(TAG, "Logs: [DEBUG SEARCH] $title -> $poster")

            newTvSeriesSearchResponse(title, href, if (href.contains("pelicula")) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Cargando metadatos de: $url")
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst(".entry-header .entry-title, h1.entry-title")?.text() ?: ""

        val posterElement = doc.selectFirst(".post-thumbnail figure img")
        val rawPoster = posterElement?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
            ?: posterElement?.attr("src").takeIf { !it.isNullOrBlank() && !it.contains("data:image") }
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val poster = fixImg(rawPoster)?.replace("/w185/", "/w500/")

        val backposter = fixImg(
            doc.selectFirst("div.bghd img.TPostBg")?.attr("src")
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        ) ?: poster

        val description = doc.select(".description p, .entry-content p").joinToString { it.text() }.trim()
        val genres = doc.select(".genres a").map { it.text() }
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()

        val recommendations = doc.select("section.episodes").filter {
            it.selectFirst(".section-title")?.text()?.contains("Recomendadas", ignoreCase = true) == true
        }.flatMap { section ->
            section.select("article.post")
        }.mapNotNull { element: Element ->
            val recTitle = element.selectFirst(".entry-title")?.text() ?: return@mapNotNull null
            val recUrl = element.selectFirst("a.lnk-blk")?.attr("abs:href")
                ?: element.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null

            val recPoster = element.getMetroPoster()

            newTvSeriesSearchResponse(recTitle, recUrl, if (recUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = recPoster
            }
        }

        if (url.contains("/pelicula/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backposter
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            val seasonElements = doc.select(".sel-temp a")
            val datapost = doc.selectFirst("li.sel-temp a")?.attr("data-post") ?: ""

            seasonElements.amap { season ->
                try {
                    val seasonNum = season.attr("data-season")
                    val response = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "action_select_season", "season" to seasonNum, "post" to datapost),
                        headers = headers.plus("Referer" to url)
                    ).document

                    response.select(".post").reversed().forEach { ep ->
                        val epHref = ep.select("a").attr("abs:href")
                        val epText = ep.select(".num-epi").text()
                        val epNumber = epText.substringAfter("x").trim().toIntOrNull()
                        val epImg = ep.selectFirst("img")
                        val epThumb = fixImg(epImg?.attr("data-lazy-src") ?: epImg?.attr("src"))

                        synchronized(episodes) {
                            episodes.add(newEpisode(epHref) {
                                this.name = "T$seasonNum - E$epNumber"
                                this.season = seasonNum.toIntOrNull()
                                this.episode = epNumber
                                this.posterUrl = epThumb
                            })
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Logs: Error en episodios: ${e.message}") }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backposter
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        Log.d(TAG, "Logs: [2026-01-22] Analizando opciones en: $data")

        doc.select(".aa-tbs-video li a").forEach { option ->
            val fullText = option.text().lowercase().replace("-", "").trim()

            val qualityName = when {
                fullText.contains("latino") -> "Latino"
                fullText.contains("castellano") || fullText.contains("español") -> "Castellano"
                fullText.contains("vose") || fullText.contains("sub") -> "Subtitulado"
                else -> "SD"
            }

            val targetId = option.attr("href").replace("#", "")
            val container = doc.selectFirst("div#$targetId")

            val iframeUrl = container?.selectFirst("iframe")?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: container?.selectFirst("iframe")?.attr("src")
                ?: ""

            if (iframeUrl.isNotBlank()) {
                val fullIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

                coroutineScope {
                    if (fullIframeUrl.contains("seriesmetro.net") && (fullIframeUrl.contains("trembed") || fullIframeUrl.contains("/ir/"))) {
                        try {
                            val innerDoc = app.get(fullIframeUrl, headers = headers.plus("Referer" to data)).document
                            val realPlayerUrl = innerDoc.selectFirst("iframe")?.attr("src")
                                ?: innerDoc.selectFirst("iframe")?.attr("data-src")
                                ?: innerDoc.selectFirst("a")?.attr("href")

                            if (!realPlayerUrl.isNullOrBlank()) {
                                val finalUrl = if (realPlayerUrl.startsWith("//")) "https:$realPlayerUrl" else realPlayerUrl
                                loadExtractor(finalUrl, fullIframeUrl, subtitleCallback) { link ->
                                    launch {
                                        callback(newExtractorLink(
                                            link.source,
                                            "${link.name} [$qualityName]",
                                            link.url,
                                            link.type
                                        ) {
                                            this.quality = link.quality
                                            this.referer = link.referer
                                            this.headers = link.headers
                                            this.extractorData = link.extractorData
                                        })
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Logs: Error en $qualityName: ${e.message}")
                        }
                    } else {
                        loadExtractor(fullIframeUrl, data, subtitleCallback) { link ->
                            launch {
                                callback(newExtractorLink(
                                    link.source,
                                    "${link.name} [$qualityName]",
                                    link.url,
                                    link.type
                                ) {
                                    this.quality = link.quality
                                    this.referer = link.referer
                                })
                            }
                        }
                    }
                }
            }
        }
        return true
    }

}