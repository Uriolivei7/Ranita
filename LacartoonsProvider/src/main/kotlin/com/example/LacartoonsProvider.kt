package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

class LacartoonsProvider : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/?Categoria_id=7" to "Marvel",
        "/?Categoria_id=1" to "Nickelodeon",
        "/?Categoria_id=2" to "Cartoon Network",
        "/?Categoria_id=3" to "Fox Kids",
        "/?Categoria_id=5" to "Disney",
        "/?Categoria_id=4" to "Hanna Barbera",
        "/?Categoria_id=6" to "Warner Channel",
        "/?Categoria_id=8" to "Otros",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(url).document
        val home = document.select(".categorias .conjuntos-series a")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.nombre-serie")?.text() ?: return null
        val href = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: return null

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?Titulo=$query").document
        val results = document.select(".categorias .conjuntos-series a").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("LACartoonsProvider", "Loading series page: $url")
        val doc = app.get(url).document

        val regexep = Regex("Capitulo\\s*(\\d+)")

        val titleElement = doc.selectFirst("h2.text-center") ?: return null.also { Log.e("LACartoonsProvider", "Could not find h2.text-center in $url") }
        val title = titleElement.ownText().trim()

        val tags = titleElement.selectFirst("span")?.text()?.let { listOf(it) } ?: emptyList()

        val description = doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()
            ?.substringAfter("Reseña:")?.trim()

        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")

        val episodes = doc.select("ul.listas-de-episodion li").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val rawTitle = element.selectFirst("a")?.text() ?: return@mapNotNull null

            val seasonNum = href.substringAfter("t=").toIntOrNull()

            val epMatch = regexep.find(rawTitle)
            val epNum = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

            val name = rawTitle.substringAfter("- ").trim()

            newEpisode(href) {
                this.name = name
                this.season = seasonNum
                this.episode = epNum
            }
        }

        Log.d("LACartoonsProvider", "Found ${episodes.size} episodes for '$title'")
        val recommendations = doc.select(".series-recomendadas a").mapNotNull { it.toSearchResult() }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.backgroundPosterUrl = backposter?.let { fixUrl(it) }
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LACartoonsProvider", "loadLinks called for: $data")
        var linksFound = false
        val res = app.get(data).document
        val iframes = res.select(".serie-video-informacion iframe")
        Log.d("LACartoonsProvider", "Found ${iframes.size} iframe(s) on the page")
        if (iframes.isEmpty()) {
            Log.w("LACartoonsProvider", "No iframes found with selector .serie-video-informacion iframe")
        }

        iframes.forEachIndexed { index, element ->
            val rawLink = element.attr("src")
            if (rawLink.isNullOrBlank()) {
                Log.w("LACartoonsProvider", "[#$index] Empty iframe src")
                return@forEachIndexed
            }

            Log.d("LACartoonsProvider", "[#$index] Raw iframe src: $rawLink")

            val link = rawLink.replaceFirst("https://short.ink/", "https://abysscdn.com/?v=")
            if (link != rawLink) {
                Log.d("LACartoonsProvider", "[#$index] short.ink replaced → $link")
            }

            val finalLink = fixHostsLinks(link)
            if (finalLink != link) {
                Log.d("LACartoonsProvider", "[#$index] fixHostsLinks applied → $finalLink")
            }

            val success = try {
                loadExtractor(finalLink, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("LACartoonsProvider", "[#$index] loadExtractor threw exception for $finalLink: ${e::class.simpleName}: ${e.message}")
                false
            }
            Log.d("LACartoonsProvider", "[#$index] loadExtractor($finalLink) returned: $success")
            if (success) {
                linksFound = true
            }
        }

        Log.d("LACartoonsProvider", "loadLinks returning: $linksFound (${if (linksFound) "links found" else "NO links found"})")
        return linksFound
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}