package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import com.google.gson.annotations.SerializedName

class PelisplusProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "PelisPlus"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val TAG = "PelisPlusProvider"


    private fun cleanTitle(title: String?): String? {
        if (title == null) return null
        return title
            .replace(Regex("(?i)^(Ver|Ver Online)\\s+"), "")
            .replace(Regex("(?i)\\s+Online\\s+Gratis.*$"), "")
            .replace(Regex("(?i)\\s+HD$"), "")
            .replace(Regex("\\s+\\(\\d{4}\\)$"), "")
            .replace(Regex("\\s+\\d{4}$"), "")
            .trim()
    }

    @Serializable
    data class EpisodeLoadData(val title: String, val url: String)

    @Serializable
    data class SortedEmbed(
        val type: String? = null,
        val link: String? = null,
        val servername: String? = null,
        val download: String? = null
    )

    @Serializable
    data class DataLinkEntry(
        val serverName: String? = null,
        val video_language: String? = null,
        val sortedEmbeds: List<SortedEmbed>? = emptyList()
    )

    @Serializable
    data class DecryptRequestBody(
        val links: List<String>
    )

    @Serializable
    data class DecryptedLink(
        val id: Int? = null,
        val link: String? = null
    )

    @Serializable
    data class DecryptionResponse(
        val success: Boolean? = false,
        val reason: String? = null,
        val links: List<DecryptedLink>? = emptyList()
    )

    private fun fixHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://awish.pro", "https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
            .replaceFirst("https://doodstream.com", "https://dood.la")
            .replaceFirst("https://streamtape.com", "https://streamtape.cc")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Doramas", "$mainUrl/generos/dorama"),
        )

        val homePageLists = urls.amap { (name, url) ->
            val doc = app.get(url).document
            val homeItems = doc.select("div.Posters a.Posters-link").mapNotNull { element ->
                val rawTitle = element.attr("data-title")
                val cleanTitle = cleanTitle(rawTitle) ?: return@mapNotNull null
                val link = element.attr("href") ?: return@mapNotNull null
                val img = element.selectFirst("img.Posters-img")?.attr("src")

                newAnimeSearchResponse(cleanTitle, fixUrl(link)) {
                    this.posterUrl = fixUrl(img ?: "")
                    this.type = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
                }
            }
            HomePageList(name, homeItems)
        }
        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        Log.d(TAG, "Iniciando búsqueda: $url")
        val doc = app.get(url).document

        return doc.select("div.Posters a.Posters-link").mapNotNull {
            val rawTitle = it.attr("data-title")
            val cleanTitle = cleanTitle(rawTitle) ?: return@mapNotNull null
            val link = it.attr("href") ?: return@mapNotNull null
            val img = it.selectFirst("img.Posters-img")?.attr("src")

            newAnimeSearchResponse(cleanTitle, fixUrl(link)) {
                this.posterUrl = fixUrl(img ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Iniciando carga de URL: $url")
        val response = app.get(url)
        val doc = response.document
        val isMovie = url.contains("/pelicula/")

        val rawTitle = doc.selectFirst("h1.m-b-5")?.text() ?: ""
        val title = cleanTitle(rawTitle) ?: rawTitle

        val year = doc.select("div.p-v-20.p-r-15 span.text-info").text().toIntOrNull()
            ?: Regex("""(\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val genres = doc.select("a[href*='/generos/'] span").map { it.text() }
        val country = doc.selectFirst("a[href*='/pais/']")?.text()
        val allTags = if (country != null) genres + country else genres

        val ratingText = doc.select("span.ion-md-star").text().split("/").firstOrNull()?.trim()
        val averageScore = ratingText?.toDoubleOrNull()
        val score = averageScore?.let { Score.from10(it) }

        val mainPoster = fixUrl(doc.selectFirst("img.img-fluid")?.attr("src") ?: "")
        val description = doc.selectFirst("div.text-large")?.text() ?: ""

        Log.d(TAG, "Carga: $title | Año: $year | Score: $averageScore | Tags: ${allTags.joinToString()}")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = mainPoster
                this.plot = description
                this.year = year
                this.tags = allTags
                this.score = score
            }
        } else {
            val episodes = doc.select("div.tab-pane a.btn-primary").mapNotNull { element ->
                val epUrl = fixUrl(element.attr("href") ?: "")
                val epRawName = element.text() ?: ""
                val cleanEpName = epRawName.replace(Regex("(?i)T\\d+\\s*-\\s*E\\d+:\\s*"), "").trim()

                val epThumb = element.selectFirst("img")?.attr("src") ?: mainPoster

                val season = Regex("(?i)T(\\d+)").find(epRawName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("(?i)E(\\d+)").find(epRawName)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(EpisodeLoadData(cleanEpName, epUrl).toJson()) {
                    this.name = cleanEpName
                    this.season = season
                    this.episode = episode
                    this.posterUrl = fixUrl(epThumb)
                }
            }

            if (episodes.isEmpty()) Log.e(TAG, "Lista de episodios vacía para $url")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = mainPoster
                this.plot = description
                this.year = year
                this.tags = allTags
                this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val targetUrl = tryParseJson<EpisodeLoadData>(data)?.url ?: data
        val doc = app.get(targetUrl).document

        val scriptContent = doc.select("script").joinToString("\n") { it.html() }
        val playerUrls = mutableListOf<String>()

        Regex("""video\[\d+\]\s*=\s*['"](https?:\/\/[^"']+)['"]""").findAll(scriptContent).forEach {
            playerUrls.add(it.groupValues[1])
        }

        doc.selectFirst("iframe")?.attr("src")?.let { playerUrls.add(fixUrl(it)) }

        if (playerUrls.isEmpty()) return@coroutineScope false

        playerUrls.distinct().amap { playerUrl ->
            val fixedPlayerUrl = fixUrl(playerUrl)

            when {
                fixedPlayerUrl.contains("embed69.org") -> {
                    val frameRes = app.get(fixedPlayerUrl, referer = targetUrl).text
                    val dataLinkJson = Regex("""dataLink\s*=\s*(\[.*?\])\s*;""").find(frameRes)?.groupValues?.get(1)

                    val files = tryParseJson<List<DataLinkEntry>>(dataLinkJson ?: "")
                    val encryptedLinks = files?.flatMap { it.sortedEmbeds?.mapNotNull { e -> e.link } ?: emptyList() }?.distinct()

                    if (!encryptedLinks.isNullOrEmpty()) {
                        val body = DecryptRequestBody(encryptedLinks).toJson()
                            .toRequestBody("application/json".toMediaTypeOrNull())

                        val decRes = app.post("https://embed69.org/api/decrypt", requestBody = body).text
                        tryParseJson<DecryptionResponse>(decRes)?.links?.forEach { decLink ->
                            decLink.link?.let {
                                loadExtractor(fixHostsLinks(it), fixedPlayerUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }

                fixedPlayerUrl.contains("xupalace.org/video/") -> {
                    val xDoc = app.get(fixedPlayerUrl, referer = targetUrl).document
                    val xRegex = Regex("""go_to_playerVast\('([^']+)'""")
                    xDoc.select("*[onclick*='go_to_playerVast']").forEach { el ->
                        xRegex.find(el.attr("onclick"))?.groupValues?.get(1)?.let { link ->
                            loadExtractor(fixHostsLinks(link), fixedPlayerUrl, subtitleCallback, callback)
                        }
                    }
                }

                fixedPlayerUrl.contains("xupalace.org/uqlink.php") -> {
                    val uqDoc = app.get(fixedPlayerUrl, referer = targetUrl).document
                    uqDoc.selectFirst("iframe")?.attr("src")?.let {
                        loadExtractor(fixHostsLinks(it), fixedPlayerUrl, subtitleCallback, callback)
                    }
                }

                else -> {
                    loadExtractor(fixHostsLinks(fixedPlayerUrl), targetUrl, subtitleCallback, callback)
                }
            }
        }
        return@coroutineScope true
    }
}