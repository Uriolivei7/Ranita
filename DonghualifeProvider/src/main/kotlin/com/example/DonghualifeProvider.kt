package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class DonghualifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    private fun fixTitle(title: String?): String {
        return title?.replace(Regex("\\s*\\|.*$"), "")?.trim() ?: ""
    }

    private fun extractYearFromDatetime(datetime: String?): String? {
        return datetime?.take(4)?.takeIf { it.all { c -> c.isDigit() } }
    }

    private fun extractHref(element: Element?): String? {
        val href = element?.attr("href") ?: element?.attr("abs:href")
        return if (href.isNullOrBlank()) null else fixUrl(href)
    }

    private fun extractImgSrc(element: Element?): String? {
        val src = element?.attr("src") ?: element?.attr("abs:src")
        return if (src.isNullOrBlank()) null else fixUrl(src)
    }

    private fun episodeToSeriesUrl(episodeUrl: String): String? {
        val match = Regex("/episode/([^/]+)-\\d+$").find(episodeUrl) ?: return null
        return "$mainUrl/series/${match.groupValues[1]}"
    }

    private fun parseListingItem(item: Element): SearchResponse? {
        val isMovie = item.hasClass("movie")
        val poster = extractImgSrc(item.selectFirst(".imagen img"))
        val href = extractHref(item.selectFirst(".imagen a"))
        val title = fixTitle(item.selectFirst(".titulo")?.text())
        if (href.isNullOrBlank() || title.isBlank()) return null
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    private suspend fun fetchListingPage(path: String, page: Int): List<SearchResponse> {
        val doc = app.get("$mainUrl/$path?page=$page").document
        return doc.select(".views-row .serie, .views-row .movie").mapNotNull { parseListingItem(it) }
    }

    private fun parseSidebarSerie(item: Element): SearchResponse? {
        val poster = extractImgSrc(item.selectFirst(".imagen img"))
        val href = extractHref(item.selectFirst(".imagen a"))
        val title = fixTitle(item.selectFirst(".titulo")?.text())
            ?: fixTitle(item.selectFirst(".titulo a")?.text())
        if (href.isNullOrBlank() || title.isBlank()) return null
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    private fun parseSidebarEpisode(item: Element): SearchResponse? {
        val poster = extractImgSrc(item.selectFirst(".imagen img"))
        val episodeHref = extractHref(item.selectFirst(".titulo a"))
        val title = fixTitle(item.selectFirst(".titulo")?.text())
        val seriesUrl = episodeHref?.let { episodeToSeriesUrl(it) }
        val href = seriesUrl ?: episodeHref ?: return null
        if (title.isBlank()) return null
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
        val donghuas = async { fetchListingPage("donghuas", page) }
        val moviesPage = async { fetchListingPage("movies", page) }
        val airing = async { fetchListingPage("en-emision", page) }
        val finished = async { fetchListingPage("finalizado", page) }
        val homeDoc = async { app.get("$mainUrl/").document }

        val donghuaList = donghuas.await()
        val movieList = moviesPage.await()
        val airingList = airing.await()
        val finishedList = finished.await()
        val home = homeDoc.await()

        val popular = home.select(".view-mas-populares .views-row .serie").mapNotNull { parseSidebarSerie(it) }
        val mostViewed = home.select(".view-mas-vistos-hoy .views-row .episodio").mapNotNull { parseSidebarEpisode(it) }

        val pages = mutableListOf<HomePageList>()
        if (donghuaList.isNotEmpty()) pages.add(HomePageList("Recientes", donghuaList))
        if (airingList.isNotEmpty()) pages.add(HomePageList("En Emisión", airingList))
        if (finishedList.isNotEmpty()) pages.add(HomePageList("Finalizados", finishedList))
        if (movieList.isNotEmpty()) pages.add(HomePageList("Películas", movieList))

        if (pages.isEmpty()) null else newHomePageResponse(pages, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?search_api_fulltext=${java.net.URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")).document

        if (doc.selectFirst(".view-empty") != null) return emptyList()

        val allItems = doc.select(".region-content .views-row .serie, .region-content .views-row .movie")

        return allItems.mapNotNull { parseListingItem(it) }
    }

    private fun parseSeasonUrl(url: String): Pair<String, Int>? {
        val match = Regex("/season/([^/]+)-(\\d+)").find(url) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2].toInt())
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val isMovie = url.contains("/movie/")

        val title = fixTitle(doc.selectFirst(".field--name-title")?.text())
            ?: fixTitle(doc.selectFirst("h2 a span")?.text())
            ?: "Sin título"

        val poster = extractImgSrc(doc.selectFirst(".field--name-field-poster img"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".field--name-field-synopsis")?.text()

        val year = extractYearFromDatetime(doc.selectFirst(".field--name-field-fecha-de-emision time")?.attr("datetime"))

        val tags = doc.select(".field--name-field-genero .field__item a").mapNotNull { it.text().trim() }.takeIf { it.isNotEmpty() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                this.plot = description
                this.tags = tags
            }
        }

        val seasonsUrls = doc.select(".view-temporadas .views-row .serie a").mapNotNull {
            extractHref(it)
        }

        val episodes = mutableListOf<Episode>()

        if (seasonsUrls.isNotEmpty()) {
            for (seasonUrl in seasonsUrls) {
                val seasonInfo = parseSeasonUrl(seasonUrl)
                val seasonNum = seasonInfo?.second ?: 1

                try {
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select("table.table-hover tbody tr, .views-row tr").forEach { row ->
                        val epNum = row.selectFirst("th[scope=row]")?.text()?.trim()?.toIntOrNull()
                        val epLink = row.selectFirst("td a") ?: return@forEach
                        val epHref = extractHref(epLink) ?: return@forEach
                        val epName = epLink.text().trim()

                        if (epHref.isNotBlank()) {
                            episodes.add(newEpisode(epHref) {
                                this.name = epName.takeIf { it.isNotBlank() } ?: "Episodio $epNum"
                                this.episode = epNum
                                this.season = seasonNum
                            })
                        }
                    }
                } catch (e: Exception) {
                }
            }
        } else {
            doc.select(".episode-item, .views-row .episode").forEach {
                val a = it.selectFirst("a") ?: return@forEach
                val epTitle = a.text().trim()
                val epHref = extractHref(a) ?: return@forEach
                val epNum = Regex("(?i)episodio\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                })
            }
        }

        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                this.plot = description
                this.tags = tags
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                addEpisodes(DubStatus.Subbed, episodes)
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data).document

        var found = false

        doc.select(".embed-links li a").forEach { link ->
            val videoUrl = link.attr("data-video")

            if (videoUrl.isNotBlank()) {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select("source, video").forEach { video ->
            val src = video.attr("abs:src")
            if (src.isNotBlank()) {
                val isM3u8 = src.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(this.name, this.name, src) {
                        this.referer = data
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
                found = true
            }
        }

        return found
    }
}