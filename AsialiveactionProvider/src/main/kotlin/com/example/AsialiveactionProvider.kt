package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import android.util.Log
import java.util.Calendar

class AsialiveactionProvider : MainAPI() {

    companion object {
        private const val TAG = "Asialiveaction"
    }

    override var mainUrl = "https://asialiveaction.com"
    override var name = "AsiaLiveAction"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }.take(4)

    private fun parseDuration(text: String): Int? {
        val clean = text.trim().lowercase()
        var maxSeconds = 0

        Regex("""(\d+)\s*h\s*(\d+)\s*min""").findAll(clean).forEach {
            val secs = it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60
            if (secs > maxSeconds) maxSeconds = secs
        }
        if (maxSeconds > 0) return maxSeconds.coerceAtMost(5 * 3600)

        Regex("""(\d+)\s*min""").findAll(clean).forEach {
            val secs = it.groupValues[1].toInt() * 60
            if (secs > maxSeconds) maxSeconds = secs
        }
        if (maxSeconds > 0) return maxSeconds.coerceAtMost(5 * 3600)

        Regex("""más\s*de\s*(\d+)\s*min""").find(clean)?.let {
            return it.groupValues[1].toInt() * 60
        }
        Regex("""menos\s*de\s*(\d+)\s*min""").find(clean)?.let {
            return (it.groupValues[1].toInt() * 0.66).toInt() * 60
        }
        Regex("""(\d+)\s*h""").find(clean)?.let {
            return (it.groupValues[1].toInt() * 3600).coerceAtMost(5 * 3600)
        }
        return null
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https)://([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        Log.d(TAG, "getMainPage: Obteniendo página principal")

        val seriesItems = ArrayList<SearchResponse>()
        val peliculasItems = ArrayList<SearchResponse>()

        val seenLinks = mutableSetOf<String>()

        val seriesResponse = app.get("$mainUrl/navegacion/?tipo%5B%5D=series")
        val seriesDoc = Jsoup.parse(seriesResponse.text)

        seriesDoc.select(".navegacion-grid a[href*='/tv/'], #carousel-series .splide__slide a[href*='/tv/']").forEach { element ->
            val link = element.attr("href") ?: return@forEach
            if (seenLinks.contains(link)) return@forEach
            seenLinks.add(link)

            val title = element.selectFirst("h5")?.text()?.trim()
                ?: element.selectFirst("h4")?.text()?.trim()
                ?: return@forEach

            val poster = element.selectFirst("img:not(.hover-img)")?.attr("src")
                ?: element.selectFirst("img:not(.hover-img)")?.attr("data-src")

            if (title.isNotEmpty() && poster != null) {
                seriesItems.add(
                    newAnimeSearchResponse(title, link, TvType.AsianDrama) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        seenLinks.clear()

        val peliculasResponse = app.get("$mainUrl/navegacion/?tipo%5B%5D=peliculas")
        val peliculasDoc = Jsoup.parse(peliculasResponse.text)

        peliculasDoc.select(".navegacion-grid a[href*='/pelicula/'], #carousel-peliculas .splide__slide a[href*='/pelicula/']").forEach { element ->
            val link = element.attr("href") ?: return@forEach
            if (seenLinks.contains(link)) return@forEach
            seenLinks.add(link)

            val title = element.selectFirst("h5")?.text()?.trim()
                ?: element.selectFirst("h4")?.text()?.trim()
                ?: return@forEach

            val poster = element.selectFirst("img:not(.hover-img)")?.attr("src")
                ?: element.selectFirst("img:not(.hover-img)")?.attr("data-src")

            if (title.isNotEmpty() && poster != null) {
                peliculasItems.add(
                    newMovieSearchResponse(title, link) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        Log.d(TAG, "getMainPage: Series=${seriesItems.size}, Peliculas=${peliculasItems.size}")

        if (seriesItems.isNotEmpty()) {
            items.add(HomePageList("Series", seriesItems))
        }
        if (peliculasItems.isNotEmpty()) {
            items.add(HomePageList("Películas", peliculasItems))
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: Buscando '$query'")
        val response = app.get("$mainUrl/search/$query")
        val document = Jsoup.parse(response.text)

        val results = document.select(".navegacion-grid .splide__slide a, .search-result-item, article.post").mapNotNull { element ->
            val link = element.attr("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = element.selectFirst("h5")?.text()
                ?: element.selectFirst("h2, h3, h4, .title")?.text()
                ?: element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: element.selectFirst("img")?.attr("data-src")

            Log.d(TAG, "search: Title=$title, Poster=$poster, Link=$link")

            if (link.isNotEmpty() && title.isNotEmpty()) {
                val tvType = if (link.contains("/pelicula/")) TvType.Movie else TvType.AsianDrama
                newAnimeSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } else {
                null
            }
        }

        Log.d(TAG, "search: ${results.size} resultados encontrados")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: Cargando URL=$url")
        val response = app.get(url)
        val document = Jsoup.parse(response.text)

        val title = document.selectFirst("h2.Title, h1.Title")?.text() ?: ""
        Log.d(TAG, "load: Title=$title")

        val poster = document.selectFirst(".Poster")?.attr("style")?.let { style ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
        } ?: document.selectFirst("article img")?.attr("src")
        ?: document.selectFirst("article img")?.attr("data-src")
        Log.d(TAG, "load: Poster=$poster")

        val description = document.selectFirst("article p")?.text() ?: ""

        val yearText = document.selectFirst(".estreno")?.text() ?: ""
        val year = yearText.filter { it.isDigit() }.take(4).toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)

        val durationText = document.selectFirst(".duraciones .duracion")?.text()?.trim()
        val durationSeconds = durationText?.let { parseDuration(it) }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val status = when {
            year < currentYear -> ShowStatus.Completed
            year == currentYear -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = ArrayList<Episode>()
        val isMovie = url.contains("/pelicula/")

        if (!isMovie) {
            document.select(".lista-episodios > .episodio-unico > a").forEach { element ->
                val epUrl = element.attr("abs:href")
                if (epUrl.isNotEmpty()) {
                    val rawName = element.selectFirst(".numero-episodio")?.text()?.trim() ?: return@forEach
                    val subtitleText = element.selectFirst(".numero-episodio > .subtitulo")?.text()?.trim()

                    val cleanName = if (subtitleText != null) {
                        subtitleText
                    } else {
                        val cleaned = rawName
                            .replace(Regex("(?i)^(Episodio|Ep)\\s*\\d+\\s*[:\\-]\\s*"), "")
                            .trim()
                        if (cleaned.isNotEmpty() && cleaned != rawName) cleaned else null
                    }

                    val epNum = getNumberFromEpsString(rawName)
                    val publishedText = element.selectFirst(".publicado")?.text()?.trim()

                    Log.d(TAG, "load: Episodio=$rawName -> Nombre=$cleanName, Num=$epNum, Publicado=$publishedText")

                    episodes.add(newEpisode(epUrl) {
                        this.name = cleanName
                        this.episode = epNum.toIntOrNull() ?: 1
                        this.posterUrl = poster
                        if (publishedText != null) this.description = publishedText
                    })
                }
            }

            document.select(".lista-episodios > .episodio-group").forEach { group ->
                val groupEpName = group.selectFirst(".toggle-acordeon .numero-episodio")?.text()?.trim() ?: return@forEach
                val groupEpNum = getNumberFromEpsString(groupEpName)
                val groupPublishedText = group.selectFirst(".toggle-acordeon .publicado")?.text()?.trim()

                val firstLink = group.selectFirst(".contenido-acordeon ul li a") ?: return@forEach
                val epUrl = firstLink.attr("abs:href")
                if (epUrl.isNotEmpty()) {
                    episodes.add(newEpisode(epUrl) {
                        this.name = groupEpName
                        this.episode = groupEpNum.toIntOrNull() ?: 1
                        this.posterUrl = poster
                        if (groupPublishedText != null) this.description = groupPublishedText
                    })
                }
            }

            Log.d(TAG, "load: ${episodes.size} episodios encontrados")
        }

        val recommendations = ArrayList<SearchResponse>()
        val seenRecs = mutableSetOf<String>()

        document.select("#carousel-recomendados .splide__slide a").forEach { element ->
            val link = element.attr("href") ?: return@forEach
            if (seenRecs.contains(link)) return@forEach
            seenRecs.add(link)

            val recTitle = element.selectFirst("h5")?.text()?.trim() ?: return@forEach
            val recPoster = element.selectFirst("img:not(.hover-img)")?.attr("src")

            if (recTitle.isNotEmpty() && recPoster != null) {
                val recType = if (link.contains("/pelicula/")) TvType.Movie else TvType.AsianDrama
                if (recType == TvType.Movie) {
                    recommendations.add(newMovieSearchResponse(recTitle, link) {
                        this.posterUrl = recPoster
                    })
                } else {
                    recommendations.add(newAnimeSearchResponse(recTitle, link, recType) {
                        this.posterUrl = recPoster
                    })
                }
            }
        }

        Log.d(TAG, "load: ${recommendations.size} recomendaciones encontradas")

        Log.d(TAG, "load: DurationText=$durationText, DurationSeconds=$durationSeconds")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                if (durationSeconds != null) {
                    Log.d(TAG, "load: Setting movie duration=${durationSeconds / 60} min")
                    this.duration = durationSeconds / 60
                }
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = status
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
        Log.d(TAG, "loadLinks: Extrayendo de URL=$data")

        val response = app.get(data)
        val htmlContent = response.text

        val allVideosPattern = """allVideos\s*=\s*(\{.*?\});""".toRegex(RegexOption.DOT_MATCHES_ALL)
        var allVideosMatch = allVideosPattern.find(htmlContent)

        if (allVideosMatch == null) {
            Log.d(TAG, "loadLinks: allVideos no encontrado, buscando enlace a episodio")
            val episodeLink = Regex("""href="(https://asialiveaction\.com/f/[^"]+)"""").find(htmlContent)
            if (episodeLink != null) {
                val episodeResponse = app.get(episodeLink.groupValues[1])
                allVideosMatch = allVideosPattern.find(episodeResponse.text)
            }
        }

        var linkCount = 0

        if (allVideosMatch != null) {
            val allVideosJson = allVideosMatch.groupValues[1]
            Log.d(TAG, "loadLinks: allVideos encontrado: ${allVideosJson.take(200)}")

            try {
                val serverDataMap = parseJson<Map<String, List<List<String>>>>(allVideosJson)
                serverDataMap.forEach { (langId, serverList) ->
                    val langName = when {
                        langId.contains("1385") -> "Subtitulado"
                        langId.contains("1388") -> "Español Latino"
                        langId.contains("1387") -> "Español de España"
                        else -> "DEFAULT"
                    }

                    serverList.forEach { serverData ->
                        if (serverData.size >= 2) {
                            val serverCode = serverData[0]
                            val encodedUrl = serverData[1]
                            val decodedUrl = encodedUrl
                                .replace("\\/", "/")
                                .removeSurrounding("\"")

                            val serverName = when (serverCode) {
                                "YM" -> "Yui"
                                "MK" -> "Moa"
                                "MM" -> "Momo"
                                "FM" -> "Byse"
                                else -> serverCode
                            }
                            val fullName = "$serverName [$langName]"

                            if (decodedUrl.startsWith("http")) {
                                Log.d(TAG, "loadLinks: [$langName] Server=$serverCode, URL=$decodedUrl")
                                val fixedUrl = fixHostsLinks(decodedUrl)

                                if (fixedUrl.contains("vk.com")) {
                                    linkCount += extractVKVideo(fullName, fixedUrl, subtitleCallback, callback)
                                } else {
                                    loadSourceNameExtractor(fullName, fixedUrl, data, subtitleCallback, callback)
                                    linkCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: Error parseando allVideos: ${e.message}")
            }
        } else {
            Log.d(TAG, "loadLinks: No se encontró allVideos")
        }

        Log.d(TAG, "loadLinks: $linkCount enlaces producidos")
        return true
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("vkvideo.ru", "vk.com")
            .replace("bysejikuar.com", "filemoon.sx")
            .replace("bysedikamoum.com", "filemoon.sx")
    }

    private suspend fun extractVKVideo(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        try {
            val page = app.get(url, referer = "https://vk.com/", timeout = 60L).text
            Log.d(TAG, "extractVKVideo: page len=${page.length}")

            val mp4Match = Regex("\"mp4_720\"\\s*:\\s*\"([^\"]+)\"").find(page)
            if (mp4Match != null) {
                val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
                callback(newExtractorLink(source, "$source 720p", videoUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 720
                })
                count++
            }

            val hlsMatch = Regex("\"hls_ondemand\"\\s*:\\s*\"([^\"]+)\"").find(page)
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1].replace("\\/", "/")
                callback(newExtractorLink(source, "$source HLS", hlsUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 1080
                    this.type = ExtractorLinkType.M3U8
                })
                count++
            }

            val dashMatch = Regex("\"dash_ondemand\"\\s*:\\s*\"([^\"]+)\"").find(page)
            if (dashMatch != null) {
                val dashUrl = dashMatch.groupValues[1].replace("\\/", "/")
                callback(newExtractorLink(source, "$source DASH", dashUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 1080
                    this.type = ExtractorLinkType.DASH
                })
                count++
            }

            if (count == 0) Log.e(TAG, "extractVKVideo: no video URLs found in page")
        } catch (e: Exception) {
            Log.e(TAG, "extractVKVideo: ${e.message}")
        }
        return count
    }
}

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}