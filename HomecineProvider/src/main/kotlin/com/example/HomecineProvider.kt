package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import com.lagradost.cloudstream3.ErrorLoadingException

class HomecineProvider: MainAPI() {
    override var mainUrl = "https://www3.homecine.to"
    override var name = "HomeCine"
    override var lang = "mx"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Movie,
    )

    private fun getContentTypeFromUrl(url: String): TvType {
        return if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
    }


    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse? {
        Log.d("HomeCineProvider", "DEBUG: Iniciando getMainPage, página: $page")
        val items = ArrayList<HomePageList>()

        val urls = listOf(
            Pair("Todas las Series", "$mainUrl/series"),
            Pair("Estrenos 2025", "$mainUrl/release-year/2025"),
            Pair("Películas Nuevas", "$mainUrl/peliculas-nuevas"),
        )

        try {
            urls.map { (name, url) ->
                Log.d("HomeCineProvider", "DEBUG: Obteniendo datos para la lista: $name de $url")
                val doc = app.get(url).document

                val home = doc.select("div.ml-item").mapNotNull { article ->
                    val title = article.selectFirst("span.mli-info > h2")?.text()
                    val img = article.selectFirst("img")?.attr("data-original")
                    val link = article.selectFirst("a.ml-mask")?.attr("href")

                    if (title == null || img == null || link == null) {
                        return@mapNotNull null
                    }

                    val contentType = getContentTypeFromUrl(link)

                    when (contentType) {
                        TvType.Movie -> {
                            newMovieSearchResponse(name = title, url = link, type = contentType) {
                                this.posterUrl = fixUrl(img)
                            }
                        }
                        TvType.TvSeries -> {
                            newTvSeriesSearchResponse(name = title, url = link, type = contentType) {
                                this.posterUrl = fixUrl(img)
                            }
                        }
                        else -> null
                    }
                }
                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                }
            }
        } catch (e: Exception) {
            Log.e("HomeCineProvider", "ERROR en getMainPage: ${e.message}", e)
            return null
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("HomeCineProvider", "DEBUG: Iniciando search para query: $query")
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document

        return doc.select("div.ml-item").mapNotNull { article ->
            val title = article.selectFirst("span.mli-info > h2")?.text()
            val img = article.selectFirst("img")?.attr("data-original")
            val link = article.selectFirst("a.ml-mask")?.attr("href")

            if (title == null || img == null || link == null) {
                return@mapNotNull null
            }

            val contentType = getContentTypeFromUrl(link)

            when (contentType) {
                TvType.Movie -> {
                    newMovieSearchResponse(name = title, url = link, type = contentType) {
                        this.posterUrl = fixUrl(img)
                    }
                }
                TvType.TvSeries -> {
                    newTvSeriesSearchResponse(name = title, url = link, type = contentType) {
                        this.posterUrl = fixUrl(img)
                    }
                }
                else -> null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("HomeCineProvider", "DEBUG: Iniciando load para URL: $url")

        try {
            val doc = app.get(url).document

            val tvType = getContentTypeFromUrl(url)

            val poster = doc.selectFirst(".mvic-thumb img.hidden")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/")
            val backimage = doc.selectFirst(".bghd img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/") ?: poster

            val title = doc.selectFirst("h1[itemprop='name']")?.text()
                ?: doc.selectFirst("h1.entry-title")?.text()
                ?: doc.selectFirst("h3[itemprop='name']")?.text()

            if (title.isNullOrEmpty()) {
                Log.e("HomeCineProvider", "ERROR: Título no encontrado o vacío para URL: $url")
                return null
            }

            val plot = doc.selectFirst("div[itemprop=\"description\"] > p.f-desc")?.text() ?: doc.selectFirst(".description > p")?.text()
            val tags = doc.select("div.mvici-left p:contains(Genre) a").map { it.text() }
            val yearrr = doc.selectFirst("p:contains(Release) a")?.text()?.toIntOrNull()
            val duration = doc.selectFirst("p:contains(Duration) span[itemprop=\"duration\"]")?.text()

            val epi = ArrayList<Episode>()

            if (tvType == TvType.TvSeries) {
                doc.select("div#seasons > div.tvseason").forEach { seasonBlock ->
                    val seasonTitleRaw = seasonBlock.selectFirst(".les-title strong")?.text()
                    val seasonTitle = seasonTitleRaw?.replace(Regex("Season\\s*"), "")?.toIntOrNull()

                    seasonBlock.select(".les-content a").forEach { episodeLink ->
                        val href = episodeLink.attr("href")
                        if (href.isNullOrEmpty()) return@forEach

                        val titleText = episodeLink.text().trim()

                        val seregex = Regex("temporada-(\\d+)-capitulo-(\\d+)")
                        val match = seregex.find(href)
                        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                        val episodeName = if (titleText.startsWith("Episode")) null else titleText

                        epi.add(
                            newEpisode(href) {
                                this.name = episodeName
                                this.season = season ?: seasonTitle
                                this.episode = episode
                            }
                        )
                    }
                }
            }

            val recs = doc.select("div.movies-list-wrap.mlw-related div.ml-item").mapNotNull { rec ->
                val recTitle = rec.selectFirst("span.mli-info h2")?.text()
                val recImg = rec.selectFirst("img")?.attr("data-original")
                val recLink = rec.selectFirst("a.ml-mask")?.attr("href")

                if (recTitle == null || recImg == null || recLink == null) return@mapNotNull null

                val recTvType = getContentTypeFromUrl(recLink)

                when (recTvType) {
                    TvType.Movie -> newMovieSearchResponse(recTitle, recLink, recTvType) { this.posterUrl = fixUrl(recImg) }
                    TvType.TvSeries -> newTvSeriesSearchResponse(recTitle, recLink, recTvType) { this.posterUrl = fixUrl(recImg) }
                    else -> null
                }
            }

            val movieEpisodeList = if (tvType == TvType.Movie) {
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                epi
            }

            return when (tvType) {
                TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, epi) {
                    this.posterUrl = poster?.let { fixUrl(it) } ?: ""
                    this.backgroundPosterUrl = backimage?.let { fixUrl(it) } ?: ""
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
                }

                TvType.Movie -> newTvSeriesLoadResponse(title, url, TvType.Movie, movieEpisodeList) {
                    this.posterUrl = poster?.let { fixUrl(it) } ?: ""
                    this.backgroundPosterUrl = backimage?.let { fixUrl(it) } ?: ""
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("HomeCineProvider", "ERROR CRÍTICO en load para URL: $url. Excepción: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HomeCineProvider", "DEBUG: Iniciando loadLinks para data: $data")
        var linksFound = false

        try {
            val doc = app.get(data).document

            val options = doc.select("ul.idTabs a")
            val playerContainer = doc.selectFirst("div#player2")

            if (options.isEmpty() || playerContainer == null) {
                Log.e(
                    "HomeCineProvider",
                    "DIAGNÓSTICO loadLinks FALLIDO: Pestañas (ul.idTabs): ${options.size}, Contenedor (div#player2): ${playerContainer != null}. URL: $data"
                )
                return false
            }

            coroutineScope {
                options.mapNotNull { option ->
                    async(Dispatchers.IO) {
                        val href = option.attr("href")
                        val serverName = option.text().trim()

                        if (href.startsWith("#")) {
                            val targetId = href.substring(1)

                            val iframe = playerContainer.selectFirst("div#$targetId iframe")
                            val embedlink = iframe?.attr("src")

                            if (embedlink.isNullOrEmpty()) {
                                Log.w("HomeCineProvider", "WARN: Enlace de iframe nulo/vacío para $serverName, saltando.")
                                return@async null
                            }

                            try {
                                loadCustomExtractor(
                                    name = serverName,
                                    url = embedlink,
                                    referer = data,
                                    subtitleCallback = subtitleCallback,
                                    callback = {
                                        callback.invoke(it)
                                        linksFound = true
                                    }
                                )
                            } catch (e: Exception) {
                                Log.e("HomeCineProvider", "ERROR al procesar $serverName ($embedlink): ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }

            return linksFound
        } catch (e: Exception) {
            Log.e("HomeCineProvider", "ERROR GENERAL en loadLinks para data '$data': ${e.message}", e)
            return false
        }
    }

    suspend fun loadCustomExtractor(
        name: String? = null,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null
    ) {
        try {
            withTimeoutOrNull(10000) {
                val extractedLinks = mutableListOf<ExtractorLink>()
                loadExtractor(url, referer, subtitleCallback) { link ->
                    extractedLinks.add(link)
                }

                extractedLinks.forEach { link ->
                    try {
                        callback.invoke(
                            withContext(Dispatchers.IO) {
                                newExtractorLink(
                                    source = name ?: link.source,
                                    name = name ?: link.name,
                                    url = link.url
                                ) {
                                    this.quality = quality ?: link.quality
                                    this.type = link.type
                                    this.referer = link.referer
                                    this.headers = link.headers
                                    this.extractorData = link.extractorData
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("HomeCineProvider", "Error en newExtractorLink para $name: ${e.message}, URL: $url")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeCineProvider", "Error en loadCustomExtractor para $name: ${e.message}, URL: $url")
        }
    }
}