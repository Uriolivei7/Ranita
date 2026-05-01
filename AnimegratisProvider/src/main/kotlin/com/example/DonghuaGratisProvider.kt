package com.example

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DonghuaGratisProvider : MainAPI() {
    override var mainUrl = "https://animegratis.net"
    override var name = "DonghuaGratis"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/donghua" to "Donghuas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains('?')) "${request.data}&page=$page" else "${request.data}?page=$page"
        Log.d("DonghuaGratis", "getMainPage URL: $url")
        val document = app.get(url).document

        val items = document.select("div.grid > a.group[href*='/donghua/']")
            .filter { !it.attr("href").contains("/episodio") }
            .mapNotNull { it.toDonghuaCard() }

        if (items.isEmpty()) {
            val fallback = document.select("a.group[href*='/donghua/']")
                .filter { !it.attr("href").contains("/episodio") }
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toDonghuaCard() }
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = fallback, isHorizontalImages = false),
                hasNext = true
            )
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toDonghuaCard(): SearchResponse {
        val href = fixUrl(attr("href"))
        val img = selectFirst("img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")
        val title = selectFirst("h3")?.text()?.trim()
            ?: img?.attr("alt")?.replace("Portada de ", "")?.trim()
            ?: text().trim()
        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/donghua?q=$query"
        Log.d("DonghuaGratis", "search URL: $url")
        val document = app.get(url).document

        val items = document.select("div.grid > a.group[href*='/donghua/']")
            .filter { !it.attr("href").contains("/episodio") }
            .mapNotNull { it.toDonghuaCard() }

        if (items.isEmpty()) {
            return document.select("a.group[href*='/donghua/']")
                .filter { !it.attr("href").contains("/episodio") }
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toDonghuaCard() }
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("DonghuaGratis", "load URL: $url")
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = extractPoster(document)

        val description = document.select("section:has(h2:contains(Sinopsis)) p").firstOrNull()?.text()
            ?: document.selectFirst("p.text-white\\/90.leading-relaxed")?.text()
            ?: document.selectFirst("p.text-white\\/85.leading-relaxed")?.text()
            ?: ""

        val tags = document.select("span.bg-gradient-to-r").map { it.text().trim() }.filter { it.isNotBlank() }

        val allSpans = document.select("span.text-white\\/90, span.text-slate-400")
        val statusText = allSpans.find { it.text().contains("Finalizado", ignoreCase = true) || it.text().contains("Emisión", ignoreCase = true) }?.text()
        val showStatus = when {
            statusText?.contains("Emisión", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Finalizado", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val year = Regex("(\\d{4})").find(document.text())?.groupValues?.get(1)?.toIntOrNull()

        val episodes = extractEpisodes(document, url, poster)
        Log.d("DonghuaGratis", "Episodes found: ${episodes.size}")

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = showStatus
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    private suspend fun extractEpisodes(document: Document, animeUrl: String, posterUrl: String): List<Episode> {
        val slug = Regex("/donghua/([^/]+)").find(animeUrl)?.groupValues?.get(1)
            ?: return extractEpisodesFallback(document, animeUrl, posterUrl)

        val numEpisodes = extractEpisodeCount(document)
        if (numEpisodes <= 0) return extractEpisodesFallback(document, animeUrl, posterUrl)

        try {
            val apiUrl = "$mainUrl/api/donghua-episodes-range?slug=$slug&start=1&end=$numEpisodes"
            Log.d("DonghuaGratis", "API URL: $apiUrl")
            val response = app.get(apiUrl).text
            val json = JSONObject(response)
            val episodesArray = json.optJSONArray("episodes")

            if (episodesArray != null && episodesArray.length() > 0) {
                val episodes = mutableListOf<Episode>()
                for (i in 0 until episodesArray.length()) {
                    val ep = episodesArray.getJSONObject(i)
                    val epNum = ep.optInt("episode_number", 0)
                    if (epNum > 0) {
                        val epUrl = "$mainUrl/donghua/$slug/episodio-$epNum"
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = ep.optString("title", "Episodio $epNum")
                                this.episode = epNum
                                this.posterUrl = posterUrl.ifBlank { null }
                            }
                        )
                    }
                }
                if (episodes.isNotEmpty()) {
                    Log.d("DonghuaGratis", "API returned ${episodes.size} episodes")
                    return episodes
                }
            }
        } catch (e: Exception) {
            Log.e("DonghuaGratis", "API error: ${e.message}")
        }

        return extractEpisodesFallback(document, animeUrl, posterUrl)
    }

    private fun extractEpisodesFallback(document: Document, animeUrl: String, posterUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("#dh-episodes-grid a[data-episode]").forEach { card ->
            val epHref = fixUrl(card.attr("href"))
            val epNum = card.attr("data-episode").toIntOrNull()
            if (epHref.isNotBlank() && epNum != null) {
                episodes.add(
                    newEpisode(epHref) {
                        this.name = "Episodio $epNum"
                        this.episode = epNum
                        this.posterUrl = posterUrl.ifBlank { null }
                    }
                )
            }
        }

        val numEpisodes = extractEpisodeCount(document)
        if (numEpisodes <= 0) return episodes

        val slug = Regex("/donghua/([^/]+)").find(animeUrl)?.groupValues?.get(1)
            ?: return episodes

        val foundNums = episodes.map { it.episode }.toSet()
        for (epNum in 1..numEpisodes) {
            if (epNum in foundNums) continue
            val epUrl = "$mainUrl/donghua/$slug/episodio-$epNum"
            episodes.add(
                newEpisode(epUrl) {
                    this.name = "Episodio $epNum"
                    this.episode = epNum
                    this.posterUrl = posterUrl.ifBlank { null }
                }
            )
        }

        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeCount(document: Document): Int {
        val jsonLdScripts = document.select("script[type=\"application/ld+json\"]")
        for (script in jsonLdScripts) {
            val data = script.data()
            if (data.contains("\"numberOfEpisodes\"")) {
                val match = Regex("\"numberOfEpisodes\":(\\d+)").find(data)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }

        val epCountText = document.selectFirst("p.text-slate-400.text-sm")?.text()
        if (!epCountText.isNullOrBlank()) {
            val match = Regex("(?i)(\\d+)\\s+episodios?\\s+disponibles?").find(epCountText)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }

        val badgeText = document.selectFirst("span:contains(episodio)")?.text()
        if (!badgeText.isNullOrBlank()) {
            val match = Regex("(\\d+)").find(badgeText)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }

        return 0
    }

    private fun extractPoster(document: Document): String {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (!ogImage.isNullOrBlank() && ogImage.contains("cdn.animegratis.net")) {
            return ogImage
        }

        val slug = document.selectFirst("link[rel='canonical']")?.attr("href")
            ?.let { url ->
                Regex("/donghua/([^/]+)").find(url)?.groupValues?.get(1)
            }

        if (!slug.isNullOrBlank()) {
            return "https://cdn.animegratis.net/donghua/$slug/images/cover.webp"
        }

        return ""
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DonghuaGratis", "loadLinks URL: $data")
        val document = app.get(data).document

        document.select("#server-tabs .server-tab").forEach { btn ->
            val serverName = btn.text().trim()
            val serverUrl = btn.attr("data-url")

            if (serverUrl.isNotBlank()) {
                Log.d("DonghuaGratis", "Servidor: $serverName -> $serverUrl")
                try {
                    loadExtractor(serverUrl, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("DonghuaGratis", "Error extractor $serverName: ${e.message}")
                }
            }
        }

        document.select("iframe#main-player").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                Log.d("DonghuaGratis", "iframe main-player: $src")
                loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}