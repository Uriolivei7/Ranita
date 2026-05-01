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
import androidx.room.util.copy
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeGratisProvider : MainAPI() {
    override var mainUrl = "https://animegratis.net"
    override var name = "AnimeGratis"
    override val hasMainPage = true
    override var lang = "mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/directorio" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains('?')) "${request.data}&page=$page" else "${request.data}?page=$page"
        Log.d("AnimeGratis", "getMainPage URL: $url")
        val document = app.get(url).document

        val ssrScript = document.selectFirst("script#ssr-init")
        if (ssrScript != null) {
            val items = parseSsrJson(ssrScript.data())
            if (items.isNotEmpty()) {
                return newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = items,
                        isHorizontalImages = false
                    ),
                    hasNext = true
                )
            }
        }

        val fallback = document.select("div.anime-card").mapNotNull { it.toAnimeCardResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = fallback,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun parseSsrJson(json: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        return try {
            val root = JSONObject(json)
            val animesArray = root.optJSONArray("animes") ?: return results
            Log.d("AnimeGratis", "SSR animes count: ${animesArray.length()}")

            for (i in 0 until animesArray.length()) {
                val item = animesArray.getJSONObject(i)
                val title = item.optString("t", "")
                val slug = item.optString("sl", "")
                val poster = item.optString("fb", "").ifEmpty {
                    item.optString("im", "")
                }
                val ty = item.optString("ty", "")

                val href = if (slug.isNotBlank()) "$mainUrl/anime/$slug-anime" else ""
                if (title.isNotBlank() && href.isNotBlank()) {
                    val tvType = if (ty == "movie") TvType.AnimeMovie else TvType.Anime
                    results.add(
                        newTvSeriesSearchResponse(title, href, tvType) {
                            this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
                        }
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e("AnimeGratis", "Error parsing SSR JSON: ${e.message}")
            results
        }
    }

    private fun parseSearchJson(json: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        return try {
            val root = JSONObject(json)
            val animesArray = root.optJSONArray("animes") ?: return results
            Log.d("AnimeGratis", "API search results: ${animesArray.length()}")

            for (i in 0 until animesArray.length()) {
                val item = animesArray.getJSONObject(i)
                val title = item.optString("t", "")
                val slug = item.optString("sl", "")
                val poster = item.optString("fb", "").ifEmpty {
                    item.optString("im", "")
                }
                val ty = item.optString("ty", "")

                val href = if (slug.isNotBlank()) "$mainUrl/anime/$slug-anime" else ""
                if (title.isNotBlank() && href.isNotBlank()) {
                    val tvType = if (ty == "movie") TvType.AnimeMovie else TvType.Anime
                    results.add(
                        newTvSeriesSearchResponse(title, href, tvType) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e("AnimeGratis", "Error parsing search API JSON: ${e.message}")
            results
        }
    }

    private fun Element.toAnimeCardResult(): SearchResponse {
        val link = this.selectFirst("a") ?: this
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img")
        val poster = img?.run {
            attr("data-fallback").ifEmpty {
                attr("src").ifEmpty { attr("data-src") }
            }
        }
        val title = this.selectFirst("h3, h4, p.font-semibold")?.text()?.trim()
            ?: img?.attr("alt")?.replace("Portada de ", "")?.trim() ?: ""
        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/api/animes/directory?page=1&limit=100&q=${query.replace(" ", "+")}&genre=&type=&status=&sort=&year=&score_min="
        Log.d("AnimeGratis", "search API URL: $apiUrl")

        return try {
            val response = app.get(apiUrl).text
            val apiResults = parseSearchJson(response)
            if (apiResults.isNotEmpty()) return apiResults

            val docUrl = "$mainUrl/directorio?q=$query"
            Log.d("AnimeGratis", "Falling back to SSR search: $docUrl")
            val document = app.get(docUrl).document

            val ssrScript = document.selectFirst("script#ssr-init")
            if (ssrScript != null) {
                val allItems = parseSsrJson(ssrScript.data())
                if (allItems.isNotEmpty()) {
                    val q = query.lowercase()
                    return allItems.filter { it.name.lowercase().contains(q) }
                }
            }

            document.select("div.anime-card").mapNotNull { it.toAnimeCardResult() }
        } catch (e: Exception) {
            Log.e("AnimeGratis", "Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("AnimeGratis", "load URL: $url")
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = extractPoster(document)
        Log.d("AnimeGratis", "load poster: $poster")

        val description = document.select("section:has(h2:contains(Sinopsis)) p").firstOrNull()?.text()
            ?: document.select("div.space-y-3 > p.text-white\\/90").firstOrNull()?.text()
            ?: document.selectFirst("p.text-white\\/90.leading-relaxed")?.text()
            ?: document.selectFirst("p.text-white\\/85.leading-relaxed")?.text()
            ?: ""

        val tags = document.select("div.space-y-3 a[href*='/directorio/genero/']").map { it.text() }

        val allSpans = document.select("div.flex.flex-wrap.gap-3 span.text-white\\/90")
        val statusText = allSpans.find { it.text().contains("Finalizado", ignoreCase = true) || it.text().contains("Emisión", ignoreCase = true) }?.text()
        val showStatus = when {
            statusText?.contains("Emisión", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Finalizado", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val yearSpan = allSpans.find { it.text().contains("📅") }?.text()
        val year = Regex("(\\d{4})").find(yearSpan ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val episodes = extractEpisodes(document, url, poster)
        Log.d("AnimeGratis", "Episodes found: ${episodes.size}")

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

    private fun extractEpisodes(document: Document, animeUrl: String, posterUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("#episodes-grid a[data-episode], #dh-episodes-grid a[data-episode]").forEach { card ->
            val epHref = card.attr("abs:href")
            val epNum = card.attr("data-episode").toIntOrNull()
            if (epHref.isNotBlank() && epNum != null) {
                val epImg = card.selectFirst("img")
                val epThumb = epImg?.run {
                    attr("src").ifBlank {
                        attr("data-fallback").ifBlank { attr("data-src") }
                    }
                }
                episodes.add(
                    newEpisode(epHref) {
                        this.name = "Episodio $epNum"
                        this.episode = epNum
                        this.posterUrl = epThumb?.takeIf { it.isNotBlank() } ?: posterUrl.ifBlank { null }
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) return episodes

        val numEpisodes = extractEpisodeCount(document)
        if (numEpisodes <= 0) return episodes

        val slug = Regex("/anime/([^/]+)-anime").find(animeUrl)?.groupValues?.get(1)
            ?: Regex("/donghua/([^/]+)").find(animeUrl)?.groupValues?.get(1)
            ?: return episodes

        val baseUrl = if (animeUrl.contains("/donghua/")) "$mainUrl/donghua/$slug" else "$mainUrl/anime/$slug"
        for (epNum in 1..numEpisodes) {
            val epUrl = "$baseUrl/episodio-$epNum"
            episodes.add(
                newEpisode(epUrl) {
                    this.name = "Episodio $epNum"
                    this.episode = epNum
                    this.posterUrl = posterUrl.ifBlank { null }
                }
            )
        }
        return episodes
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

        val infoSpans = document.select("div.flex.items-center.gap-1\\.5, div.flex.items-center.gap-2")
        for (span in infoSpans) {
            val text = span.text()
            if (text.contains("Episodios", ignoreCase = true)) {
                val match = Regex("(\\d+)").find(text)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }

        return 0
    }

    private fun extractPoster(document: Document): String {
        val pageImg = document.selectFirst("div.rounded-xl.overflow-hidden img[src*='cdn.animegratis.net'], div.relative.aspect-\\[2\\/3\\] img[src*='cdn.animegratis.net']")
        if (pageImg != null) {
            val src = pageImg.attr("src")
            if (src.isNotBlank() && !src.contains("cdn-cgi/image")) return src
        }

        val canonicalUrl = document.selectFirst("link[rel='canonical']")?.attr("href") ?: ""
        val slug = Regex("/anime/([^/]+)").find(canonicalUrl)?.groupValues?.get(1)
            ?: Regex("/donghua/([^/]+)").find(canonicalUrl)?.groupValues?.get(1)

        if (!slug.isNullOrBlank()) {
            val prefix = if (canonicalUrl.contains("/donghua/")) "donghua" else "anime"
            val cdnUrl = "https://cdn.animegratis.net/$prefix/$slug/images/cover.webp"
            return cdnUrl
        }

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (!ogImage.isNullOrBlank()) {
            return if (ogImage.startsWith("http")) ogImage else "$mainUrl$ogImage"
        }

        return ""
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AnimeGratis", "loadLinks URL: $data")
        val document = app.get(data).document

        val langMap = mapOf("ja" to "Subtitulado", "cast" to "Castellano", "lat" to "Latino")

        document.select("button.server-btn").forEach { btn ->
            val serverName = btn.attr("data-server")
            val serverUrl = btn.attr("data-url")
            val langGroup = btn.attr("data-lang-group")
            val langLabel = langMap[langGroup] ?: "SUB"

            if (serverUrl.isNotBlank()) {
                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    callback(
                        ExtractorLink(
                            source = "[$langLabel] ${link.source}",
                            name = "[$langLabel] ${link.name}",
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            isM3u8 = link.isM3u8,
                        )
                    )
                }
                Log.d("AnimeGratis", "Server: [$langLabel] $serverName -> $serverUrl")
                try {
                    loadExtractor(serverUrl, mainUrl, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    Log.e("AnimeGratis", "Error extractor $serverName: ${e.message}")
                }
            }
        }

        document.select("iframe#videoFrame").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                Log.d("AnimeGratis", "iframe videoFrame: $src")
                loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}