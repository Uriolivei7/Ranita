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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Animeav1 : MainAPI() {
    override var mainUrl              = "https://animeav1.com"
    override var name                 = "AnimeAV1"
    override val hasMainPage          = true
    override var lang                 = "mx"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "catalogo?status=emision" to "Emision",
        "catalogo?status=finalizado" to "Finalizado",
        "catalogo?category=pelicula" to "Pelicula",
        "catalogo?category=ova" to "OVA",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home     = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("h3").text()
        val href      = this.select("a").attr("href")
        val posterUrl = fixUrlNull(this.select("figure img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }


    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/catalogo?search=$query").document
        val results =document.select("article").mapNotNull { it.toSearchResult() }
        return results
    }


    private fun getTvType(text: String): TvType {
        return when {
            text.contains("TV Anime", ignoreCase = true) -> TvType.Anime
            text.contains("Película", ignoreCase = true) -> TvType.Movie
            text.contains("OVA", ignoreCase = true) -> TvType.Anime
            else -> TvType.Movie
        }
    }

    private fun getStatus(text: String): ShowStatus? {
        return when (text.lowercase()) {
            "en emisión", "en emision" -> ShowStatus.Ongoing
            "finalizado" -> ShowStatus.Completed
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        Log.d("Animeav1", "LOAD INICIO: Título extraído: $title")

        val poster = document.selectFirst("img[alt*='Poster']")?.attr("src")
            ?: document.selectFirst("img.aspect-poster.w-full.rounded-lg")?.attr("src") ?: ""
        Log.d("Animeav1", "LOAD INICIO: Póster extraído: $poster")

        val description = document.selectFirst("div.entry.line-clamp-4 p")?.text()

        val infoContainer = document.selectFirst("header div.flex.flex-wrap.items-center.gap-2.text-sm")

        Log.d("Animeav1", "LOAD METADATA: Contenedor HTML (V4): ${infoContainer?.outerHtml()}")

        val yearText = infoContainer?.select("span:nth-child(3)")?.text()
        val year = yearText?.toIntOrNull()
        Log.d("Animeav1", "LOAD METADATA: Año extraído (toInt): $year")

        val statusText = infoContainer?.select("span:nth-child(7)")?.text()
        Log.d("Animeav1", "LOAD METADATA: Estado extraído (Corregido): $statusText")

        val showStatus = statusText?.let { getStatus(it) }

        val tags = document.select("header > div.flex.flex-wrap.items-center.gap-2 a").map { it.text() }
        Log.d("Animeav1", "LOAD METADATA: Tags extraídos: $tags")

        val rawtype = infoContainer?.select("span:nth-child(1)")?.text() ?: ""
        val type = getTvType(rawtype)

        val recommendations = document.select("section div.gradient-cut > div > div").mapNotNull {
            val recTitle = it.select("h3").text()
            val recHref = fixUrl(it.select("a").attr("href"))
            val recPoster = fixUrlNull(it.select("img").attr("src"))
            newMovieSearchResponse(recTitle, recHref, TvType.Anime) {
                this.posterUrl = recPoster
            }
        }

        if (type == TvType.Anime) {
            val episodes = mutableListOf<Episode>()
            val mediaId = Regex("/(\\d+)\\.jpg$").find(poster)?.groupValues?.get(1) ?: "0"
            val scriptContent = document.select("script").html()
            val episodeZeroRegex = Regex("number:\\s*0")
            val regex = Regex("media:\\{.*?episodesCount:(\\d+).*?slug:\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)

            val match = regex.find(scriptContent)
            if (match != null) {
                val totalEpisodes = match.groupValues[1].toIntOrNull() ?: 0
                val slug = match.groupValues[2]
                val hasEpisodeZero = episodeZeroRegex.containsMatchIn(scriptContent)
                val startEp = if (hasEpisodeZero) 0 else 1

                Log.d("Animeav1", "Total episodios: $totalEpisodes, Slug: $slug")

                for (i in startEp..totalEpisodes) {
                    val epUrl = "https://animeav1.com/media/$slug/$i"
                    val epposter = "https://cdn.animeav1.com/screenshots/$mediaId/$i.jpg"
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episode $i"
                            this.episode = i
                            this.posterUrl = epposter
                        }
                    )
                }
            } else {
                Log.e("Animeav1", "No se encontró episodesCount o slug, usando HTML como fallback")
                document.select("div.grid > article").map {
                    val epposter = it.select("img").attr("src")
                    val epno = it.select("span.text-lead > font > font").text()
                    val ephref = it.select("a").attr("href")
                    episodes.add(
                        newEpisode(ephref) {
                            this.name = "Episode $epno"
                            this.episode = epno.toIntOrNull()
                            this.posterUrl = epposter
                        }
                    )
                }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = showStatus
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val href = fixUrl(document.select("div.grid > article a").attr("href"))
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
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
        Log.d("Animeav1", "loadLinks iniciado con URL: $data")

        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e("Animeav1", "Error al cargar documento: ${e.message}")
            return false
        }

        val scriptHtml = document.select("script")
            .firstOrNull { it.html().contains("__sveltekit_") }
            ?.html()
            .orEmpty()

        if (scriptHtml.isEmpty()) {
            Log.e("Animeav1", "No se encontró script con __sveltekit_")
            return false
        }

        fun cleanJsToJson(js: String): String {
            var cleaned = js.replaceFirst("""^\s*\w+\s*:\s*""".toRegex(), "")
            cleaned = cleaned.replace("void 0", "null")
            cleaned = Regex("""(?<=[{,])\s*(\w+)\s*:""").replace(cleaned) { "\"${it.groupValues[1]}\":" }
            return cleaned.trim()
        }

        val embedsPattern = "embeds:\\s*\\{([^}]*\\{[^}]*\\})*[^}]*\\}".toRegex(RegexOption.DOT_MATCHES_ALL)
        val embedsMatch = embedsPattern.find(scriptHtml)?.value

        if (embedsMatch == null) {
            Log.e("Animeav1", "No se encontró patrón 'embeds' en el script")
            return false
        }

        val embedsJson = cleanJsToJson(embedsMatch)
        Log.d("Animeav1", "JSON parseado, longitud: ${embedsJson.length}")

        return runCatching {
            val embedsObject = JSONObject(embedsJson)

            fun extractLinks(arrayName: String): List<Pair<String, String>> {
                val list = mutableListOf<Pair<String, String>>()
                if (embedsObject.has(arrayName)) {
                    val jsonArray = embedsObject.getJSONArray(arrayName)
                    for (i in 0 until jsonArray.length()) {
                        try {
                            val obj = jsonArray.getJSONObject(i)
                            val server = obj.getString("server")
                            val url = obj.getString("url")
                            list.add(server to url)
                        } catch (e: Exception) {
                            Log.e("Animeav1", "Error parseando objeto en $arrayName[$i]: ${e.message}")
                        }
                    }
                }
                return list
            }

            val subEmbeds = extractLinks("SUB")
            val dubEmbeds = extractLinks("DUB")

            Log.i("Animeav1", "Enlaces encontrados - Subtitulado: ${subEmbeds.size}, Español Latino: ${dubEmbeds.size}")

            val jobs = mutableListOf<Job>()
            coroutineScope {
                subEmbeds.forEach { (server, url) ->
                    jobs += launch(Dispatchers.IO) {
                        try {
                            val linkName = "Subtitulado: $server"
                            loadCustomExtractor(
                                name = linkName,
                                url = url,
                                referer = "",
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                quality = null
                            )
                            Log.d("Animeav1", "Procesado $linkName")
                        } catch (e: Exception) {
                            Log.e("Animeav1", "Error procesando Subtitulado:$server - ${e.message}, URL: $url")
                        }
                    }
                }

                dubEmbeds.forEach { (server, url) ->
                    jobs += launch(Dispatchers.IO) {
                        try {
                            val linkName = "Español Latino: $server"
                            loadCustomExtractor(
                                name = linkName,
                                url = url,
                                referer = "",
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                quality = null
                            )
                            Log.d("Animeav1", "Procesado $linkName")
                        } catch (e: Exception) {
                            Log.e("Animeav1", "Error procesando Español Latino:$server - ${e.message}, URL: $url")
                        }
                    }
                }
            }

            jobs.joinAll()

            subEmbeds.isNotEmpty() || dubEmbeds.isNotEmpty().also {
                Log.i("Animeav1", "Procesamiento completado - Subtitulado: ${subEmbeds.size}, Español Latino: ${dubEmbeds.size}, Éxito: $it")
            }
        }.getOrElse { e ->
            Log.e("Animeav1", "Error general en loadLinks: ${e.message}")
            false
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
                        Log.e("Animeav1", "Error en newExtractorLink para $name: ${e.message}, URL: $url")
                    }
                }
            } ?: Log.e("Animeav1", "Timeout en loadCustomExtractor para $name, URL: $url")
        } catch (e: Exception) {
            Log.e("Animeav1", "Error en loadCustomExtractor para $name: ${e.message}, URL: $url")
        }
    }
}