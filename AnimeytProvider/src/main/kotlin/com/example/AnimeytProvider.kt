package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.serialization.*

class AnimeytProvider : MainAPI() {
    override var mainUrl = "https://ytanime.tv"
    override var name = "AnimeYT"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val sections = listOf(
            Pair("$mainUrl/ultimos-animes?page=$page", "Recién Agregados"),
            Pair("$mainUrl/mas-populares?page=$page", "Más Populares")
        )

        sections.forEach { (url, title) ->
            try {
                val doc = app.get(url).document
                val animeList = doc.select("div.video-block div.row div.col-md-2 div.video-card").amap {
                    val href = it.select("div.video-card-body div.video-title a").attr("href")
                    val aTitle = it.select("div.video-card-body div.video-title a").text()
                    val fullUrl = fixUrl(href)

                    val poster = getPosterFromLoad(fullUrl)

                    newAnimeSearchResponse(aTitle, fullUrl, TvType.Anime) {
                        this.posterUrl = poster
                    }
                }

                if (animeList.isNotEmpty()) {
                    items.add(HomePageList(title, animeList))
                }
            } catch (e: Exception) { }
        }

        return newHomePageResponse(items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        val results = doc.select("div.video-block div.row div.col-md-2 div.video-card")

        return results.amap { element ->
            val title = element.select("div.video-card-body div.video-title a").text()
            val href = element.select("div.video-card-body div.video-title a").attr("href")
            val fullUrl = fixUrl(href)

            val poster = getPosterFromLoad(fullUrl)

            newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    private suspend fun getPosterFromLoad(url: String): String? {
        return try {
            val doc = app.get(url).document
            doc.selectFirst("figure.sa-poster__fig img")?.attr("src")
                ?: doc.selectFirst("div.sa-series-dashboard__poster img")?.attr("src")
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select("div.video-card-body div.video-title a").text()
        val href = this.select("div.video-card-body div.video-title a").attr("href")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.select("img").attr("src")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("#info div.sa-layout__line div div.sa-title-series__title span")?.text() ?: ""
        val poster = doc.selectFirst("div.sa-series-dashboard__poster img")?.attr("src")
        val description = doc.selectFirst("#info div.sa-layout__line p.sa-text")?.text()?.removeSurrounding("\"")

        val statusText = doc.select("#info > div:nth-child(2) > button").text()
        val status = when {
            statusText.contains("En Emisión") -> ShowStatus.Ongoing
            statusText.contains("Finalizado") -> ShowStatus.Completed
            else -> null
        }

        val episodes = doc.select("#caps ul.list-group li.list-group-item a").map { element ->
            val epName = element.select("span.sa-series-link__number").text()
            val epHref = element.attr("href")
            val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = "Episodio $epNum"
                this.episode = epNum
            }
        }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("#plays iframe").forEach { container ->
            var videoUrl = container.attr("src")

            if (videoUrl.contains("fastream") || videoUrl.contains("emb.html")) {
                if (videoUrl.contains("emb.html")) {
                    val key = videoUrl.split("/").last()
                    videoUrl = "https://fastream.to/embed-$key.html"
                }

                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}