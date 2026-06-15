package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URL
import java.util.regex.Pattern

class PlushdProvider : MainAPI() {
    override var mainUrl = "https://tioplus.app"
    override var name = "PlusHD"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Movie,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "series" to "Series",
        "animes" to "Animes",
        "peliculas" to "Peliculas",
        "doramas" to "Doramas",
    )

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/$page").document
        val home = document.select(".articlesList article").filter { !it.selectFirst("a")?.attr("target").equals("_blank") }
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
        val title = this.selectFirst("a h2")?.text() ?: return null
        val link = this.selectFirst("a.itemA")?.attr("href") ?: return null
        val img = this.selectFirst("picture img")?.attr("data-src")

        val yearRegex = Regex("""\s*\((\d{4})\)$""")
        val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

        val searchType = when {
            link.contains("/serie") -> TvType.TvSeries
            link.contains("/anime") -> TvType.Anime
            link.contains("/pelicula") -> TvType.Movie
            link.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return when (searchType) {
            TvType.Movie -> newMovieSearchResponse(title, link, searchType) {
                this.posterUrl = img
                this.year = year
            }
            else -> newTvSeriesSearchResponse(title, link, searchType) {
                this.posterUrl = img
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/api/search/$query").document
        val results = document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
    }

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val tvType = when {
            url.contains("/serie") -> TvType.TvSeries
            url.contains("/anime") -> TvType.Anime
            url.contains("/pelicula") -> TvType.Movie
            url.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.TvSeries
        }

        val title = doc.selectFirst(".slugh1")?.text()?.trim() ?: ""
        val year = doc.selectFirst("span:contains(Año) a")?.text()?.toIntOrNull()

        val backimage = doc.selectFirst(".bg")?.attr("style")?.let {
            Regex("url\\(\"?(.*?)\"?\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""

        var verticalPoster = doc.select(".poster img, .data img").firstNotNullOfOrNull {
            val src = it.attr("data-src").ifBlank { it.attr("src") }

            if (src.isNotBlank() &&
                !src.contains("nGfjgUlES2WuYrHXNNF4fbGe2Eq") &&
                src.contains("tmdb.org") &&
                !src.contains("/episodes/") &&
                !src.contains("/seasons/")
            ) {
                src
            } else null
        }?.replace("original", "w342")

        if (verticalPoster.isNullOrBlank()) {
            verticalPoster = doc.selectFirst(".poster img")?.attr("src")?.replace("original", "w342") ?: backimage
        }

        val description = doc.selectFirst("div.description")?.text() ?: ""
        val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
        val epi = ArrayList<Episode>()

        if (tvType != TvType.Movie) {
            val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
            if (!script.isNullOrEmpty()) {
                val jsonRegex = Regex("seasonsJson\\s*=\\s*(\\{[^;]*\\});")
                val matchJson = jsonRegex.find(script)
                val jsonscript = matchJson?.groupValues?.get(1)

                if (!jsonscript.isNullOrEmpty()) {
                    try {
                        val seasonsMap = parseJson<Map<String, List<MainTemporadaElement>>>(jsonscript)
                        seasonsMap.values.forEach { list ->
                            list.forEach { info ->
                                val epTitle = info.title
                                val seasonNum = info.season
                                val epNum = info.episode
                                val img = info.image
                                val realimg = if (img.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                                val epurl = "$url/season/$seasonNum/episode/$epNum"
                                if (epTitle != null && seasonNum != null && epNum != null) {
                                    epi.add(newEpisode(epurl) {
                                        this.name = epTitle
                                        this.season = seasonNum
                                        this.episode = epNum
                                        this.posterUrl = realimg
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PlushdProvider", "Error al parsear seasonsJson: ${e.message}")
                    }
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> {
                newTvSeriesLoadResponse(title, url, tvType, epi) {
                    this.posterUrl = verticalPoster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = verticalPoster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
            else -> null
        }
    }

    private fun fixPelisplusHostsLinks(url: String): String {
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
            .replaceFirst("https://doodstream.com", "https://dood.la")
            .replaceFirst("https://streamtape.com", "https://streamtape.cc")
            .replace("https://sblanh.com", "https://lvturbo.com")
            .replaceFirst("https://emturbovid.com", "https://turbovidhls.com")
    }

    private val REGEX_LINK = Pattern.compile(
        "^(https?:)?//(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*)$"
    )

    private fun fetchUrls(text: String): List<String> {
        val urls = ArrayList<String>()
        val regex = Regex("""(https?://[^\s"']+)""")
        regex.findAll(text).forEach { urls.add(it.value) }
        return urls
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/",
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache"
        )

        val doc = app.get(data, headers = headers).document

        val serverItems = doc.select("div ul.subselect li")
        Log.d("PlushdProvider", "=== loadLinks: ${serverItems.size} servidores encontrados en página ===")

        val loggingSubtitleCallback: (SubtitleFile) -> Unit = { file ->
            Log.d("PlushdProvider", "Subtítulo encontrado. URL: ${file.url}")
            subtitleCallback.invoke(file)
        }

        serverItems.toList().amap { serverLi ->
            if (linksFound) return@amap
            val tag = "PlushdProvider-Server"
            try {
                val serverData = serverLi.attr("data-server")
                if (serverData.isNullOrEmpty()) {
                    Log.w(tag, "data-server vacío, saltando")
                    return@amap
                }

                val decoded = String(Base64.decode(serverData, Base64.DEFAULT))
                Log.d(tag, "decoded: ${decoded.take(120)}")

                val isPlayerPath = !REGEX_LINK.matcher(decoded).matches()
                val url = if (isPlayerPath) {
                    "$mainUrl/player/${base64Encode(serverData.toByteArray())}"
                } else {
                    decoded
                }
                Log.d(tag, "usará ${if (isPlayerPath) "PLAYER" else "DIRECT"}: ${url.take(120)}")

                val videoUrl = if (url.contains("/player/")) {
                    val playerHeaders = headers + mapOf("Referer" to data)
                    Log.d(tag, "fetcheando player page: $url")
                    val playerDoc = app.get(url, headers = playerHeaders).document
                    Log.d(tag, "HTML player page: ${playerDoc.html().length} chars")
                    extractUrlFromPlayerPage(playerDoc)
                } else {
                    url
                }

                if (videoUrl.isBlank()) {
                    Log.w(tag, "videoUrl en blanco después de extracción")
                    return@amap
                }
                Log.d(tag, "videoUrl raw: ${videoUrl.take(120)}")

                val fixedLink = fixPelisplusHostsLinks(videoUrl)
                    .replace(Regex("""([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https://ww3.pelisplus.to.*"""), "")
                Log.d(tag, "fixedLink: ${fixedLink.take(120)}")

                if (fixedLink.isBlank()) {
                    Log.w(tag, "fixedLink en blanco después de fixPelisplusHostsLinks")
                    return@amap
                }

                if (fixedLink.contains("turbovidhls.com")) {
                    Log.w(tag, "turbovid (error 3003), saltando")
                    return@amap
                }
                if (fixedLink.contains("#") && (
                            fixedLink.contains("upns.pro") ||
                                    fixedLink.contains("rpmstream.live") ||
                                    fixedLink.contains("strp2p.com") ||
                                    fixedLink.contains("4meplayer.pro") ||
                                    fixedLink.contains("pelisplusto")
                            )) {
                    Log.w(tag, "SPA hash (error 2001), saltando")
                    return@amap
                }

                val extractorReferer = try {
                    val urlObject = URL(fixedLink)
                    urlObject.protocol + "://" + urlObject.host + "/"
                } catch (e: Exception) {
                    Log.e(tag, "Error al parsear URL para Referer: ${e.message}")
                    url
                }
                Log.d(tag, "extractorReferer: $extractorReferer")

                val found = loadExtractor(
                    url = fixedLink,
                    referer = extractorReferer,
                    subtitleCallback = loggingSubtitleCallback,
                    callback = callback
                )
                if (found) {
                    linksFound = true
                    Log.d(tag, "OK (loadExtractor)")
                } else {
                    Log.w(tag, "loadExtractor no encontró extractor")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error: ${e.message}")
            }
        }

        Log.d("PlushdProvider", "=== loadLinks FIN: linksFound=$linksFound ===")
        return linksFound
    }

    private suspend fun extractUrlFromPlayerPage(playerDoc: org.jsoup.nodes.Document): String {
        val tag = "PlushdProvider-Player"
        Log.d(tag, "HTML total: ${playerDoc.html().length} chars")

        val strategies = listOf(
            "window.onload" to { doc: org.jsoup.nodes.Document ->
                val script = doc.selectFirst("script:containsData(window.onload)")?.data() ?: ""
                fetchUrls(script).firstOrNull() ?: ""
            },
            "iframe[src]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("iframe[src]")?.attr("src") ?: ""
            },
            "script.mp4" to { doc: org.jsoup.nodes.Document ->
                doc.select("script").firstOrNull { it.data().contains("https://") && it.data().contains(".mp4") }
                    ?.let { fetchUrls(it.data()).firstOrNull() } ?: ""
            },
            "script.m3u8" to { doc: org.jsoup.nodes.Document ->
                doc.select("script").firstOrNull { it.data().contains("https://") && it.data().contains("m3u8") }
                    ?.let { fetchUrls(it.data()).firstOrNull() } ?: ""
            },
            "video source[src]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("video source[src], video[src]")?.attr("src") ?: ""
            },
            "a[href]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("a[href$=\".mp4\"], a[href$=\".m3u8\"]")?.attr("href") ?: ""
            },
        )

        for ((name, extract) in strategies) {
            val url = extract(playerDoc)
            if (url.isNotBlank()) {
                Log.d(tag, "Estrategia '$name' OK: ${url.take(100)}")
                return url
            } else {
                Log.d(tag, "Estrategia '$name' no encontró URL")
            }
        }

        Log.w(tag, "Ninguna estrategia encontró URL")
        return ""
    }
}