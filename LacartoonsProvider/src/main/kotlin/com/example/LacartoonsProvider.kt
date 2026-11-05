package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log
import org.json.JSONObject

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
        val title = titleElement.ownText().trim() // Título de la serie (sin el <span>)

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
        var emittedLinks = 0
        val pageResponse = try {
            app.get(data)
        } catch (e: Exception) {
            Log.e("LACartoonsProvider", "loadLinks page request failed type=${e::class.simpleName} message=${e.message}")
            return false
        }
        Log.d("LACartoonsProvider", "loadLinks page HTTP ${pageResponse.code} length=${pageResponse.text.length}")
        val res = pageResponse.document
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

            val safeRaw = safeLinkSummary(rawLink)
            Log.d("LACartoonsProvider", "[#$index] iframe src=$safeRaw")

            val link = rawLink.replaceFirst("https://short.ink/", "https://abysscdn.com/?v=")
            if (link != rawLink) {
                Log.d("LACartoonsProvider", "[#$index] short.ink normalized to ${safeLinkSummary(link)}")
            }

            val finalLink = fixHostsLinks(link)
            if (finalLink != link) {
                Log.d("LACartoonsProvider", "[#$index] host alias normalized to ${safeLinkSummary(finalLink)}")
            }

            val emittedBefore = emittedLinks
            val startedAt = System.currentTimeMillis()
            val host = safeLinkSummary(finalLink).substringBefore("/")
            if (host.equals("ok.ru", ignoreCase = true) || host.endsWith(".ok.ru", ignoreCase = true)) {
                val okruEmitted = extractOkru(finalLink, data) { extractedLink ->
                    emittedLinks++
                    callback(extractedLink)
                }
                val elapsedMs = System.currentTimeMillis() - startedAt
                Log.d("LACartoonsProvider", "[#$index] OK.ru custom result emitted=${emittedLinks - emittedBefore} elapsedMs=$elapsedMs")
                if (!okruEmitted) Log.w("LACartoonsProvider", "[#$index] OK.ru no entregó HLS ni videos progresivos")
                return@forEachIndexed
            }
            val extractorMatched = try {
                loadExtractor(finalLink, data, subtitleCallback) { link ->
                    emittedLinks++
                    Log.d("LACartoonsProvider", "[#$index] callback link name='${link.name}' type=${link.type} quality=${link.quality}")
                    callback(link)
                }
            } catch (e: Exception) {
                Log.e("LACartoonsProvider", "[#$index] extractor exception host=$host type=${e::class.simpleName} message=${e.message}")
                false
            }
            val iframeEmitted = emittedLinks - emittedBefore
            val elapsedMs = System.currentTimeMillis() - startedAt
            Log.d("LACartoonsProvider", "[#$index] extractor result host=$host matched=$extractorMatched emitted=$iframeEmitted elapsedMs=$elapsedMs")
            if (!extractorMatched) Log.w("LACartoonsProvider", "[#$index] no extractor matched host=$host")
            if (extractorMatched && iframeEmitted == 0) Log.w("LACartoonsProvider", "[#$index] extractor matched but emitted no links host=$host")
        }

        val linksFound = emittedLinks > 0
        Log.d("LACartoonsProvider", "loadLinks returning: $linksFound (emitted=$emittedLinks)")
        return linksFound
    }

    private suspend fun extractOkru(
        url: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("""(?:videoembed|video)/(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val embedUrl = "https://ok.ru/videoembed/$videoId"
        return try {
            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                    "Referer" to pageReferer
                ),
                timeout = 30L
            )
            val rawOptions = response.document.selectFirst("[data-options]")?.attr("data-options")
            Log.d("LACartoonsProvider", "[OK.ru] id=$videoId HTTP ${response.code} htmlLen=${response.text.length} dataOptions=${!rawOptions.isNullOrBlank()}")
            if (rawOptions.isNullOrBlank()) return false
            val metadata = JSONObject(rawOptions).optJSONObject("flashvars")?.optJSONObject("metadata")
            val hls = metadata?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() && it != "null" }
            if (hls != null) {
                callback(newExtractorLink(name, "$name [OK.ru]", hls, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = Qualities.P1080.value
                })
                Log.d("LACartoonsProvider", "[OK.ru] id=$videoId HLS emitido host=${safeLinkSummary(hls).substringBefore("/")}")
                return true
            }

            val videos = metadata?.optJSONArray("videos") ?: return false
            var emitted = 0
            for (i in videos.length() - 1 downTo 0) {
                val video = videos.optJSONObject(i) ?: continue
                val videoUrl = video.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val qualityName = video.optString("name")
                val quality = when (qualityName.lowercase()) {
                    "full" -> Qualities.P1080.value
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest", "mobile" -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink(name, "$name [OK.ru $qualityName]", videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = quality
                })
                emitted++
            }
            Log.d("LACartoonsProvider", "[OK.ru] id=$videoId progresivos emitidos=$emitted")
            emitted > 0
        } catch (e: Exception) {
            Log.e("LACartoonsProvider", "[OK.ru] id=$videoId error ${e::class.simpleName}: ${e.message}")
            false
        }
    }
}

private fun safeLinkSummary(link: String): String = try {
    val parsed = java.net.URL(link)
    parsed.host + parsed.path
} catch (_: Exception) {
    "invalid-url"
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
