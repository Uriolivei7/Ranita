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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.nodes.Element
import android.util.Log

class Animeav1 : MainAPI() {
    override var mainUrl              = "https://animeav1.com"
    override var name                 = "AnimeAV1"
    override val hasMainPage          = true
    override var lang                 = "mx"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(
        TvType.Anime
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
            text.contains("TV Anime", ignoreCase = true) -> TvType.TvSeries
            text.contains("Película", ignoreCase = true) -> TvType.Movie
            text.contains("OVA", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title= document.selectFirst("article h1")?.text() ?: "Unknown"
        val poster = document.select("img.aspect-poster.w-full.rounded-lg").attr("src")
        val description = document.selectFirst("div.entry.text-lead p")?.text()
        val rawtype= document.select("div.flex.flex-wrap.items-center.gap-2.text-sm > span:nth-child(1)")
            .text()
        val type = getTvType(rawtype)
        val tags=document.select("header > div:nth-child(3) a").map { it.text() }
        val href=fixUrl(document.select("div.grid > article a").attr("href"))
        return if (type==TvType.TvSeries)
        {
            val episodes = mutableListOf<Episode>()
            document.select("div.grid > article").map {
                val epposter=it.select("img").attr("src")
                val epno=it.select("span.text-lead > font > font").text()
                val ephref=it.select("a").attr("href")
                episodes.add(
                    newEpisode(ephref)
                    {
                        this.name="Episode $epno"
                        this.episode=epno.toIntOrNull()
                        this.posterUrl=epposter
                    })

            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
        else newMovieLoadResponse(title, url, TvType.Movie, href) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
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

        Log.d("Animeav1", "Script encontrado, longitud: ${scriptHtml.length}")

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

        val embedsJson = embedsMatch.let { cleanJsToJson(it) }
        Log.d("Animeav1", "JSON parseado: $embedsJson")

        if (!embedsJson.isNullOrEmpty()) {
            try {
                val embedsObject = JSONObject(embedsJson)

                val stats = mutableMapOf<String, Int>()
                val failedServers = mutableListOf<String>()

                fun extractLinks(arrayName: String): List<Pair<String, String>> {
                    val list = mutableListOf<Pair<String, String>>()
                    if (embedsObject.has(arrayName)) {
                        val jsonArray = embedsObject.getJSONArray(arrayName)
                        Log.d("Animeav1", "Encontrados ${jsonArray.length()} enlaces en $arrayName")

                        for (i in 0 until jsonArray.length()) {
                            try {
                                val obj = jsonArray.getJSONObject(i)
                                val server = obj.getString("server")
                                val url = obj.getString("url")
                                list.add(server to url)
                                Log.d("Animeav1", "$arrayName - Servidor: $server, URL: $url")
                            } catch (e: Exception) {
                                Log.e("Animeav1", "Error parseando objeto en posición $i de $arrayName: ${e.message}")
                            }
                        }
                    } else {
                        Log.w("Animeav1", "No se encontró array '$arrayName' en el JSON")
                    }
                    return list
                }

                val subEmbeds = extractLinks("SUB")
                val dubEmbeds = extractLinks("DUB")

                Log.i("Animeav1", "Total enlaces SUB: ${subEmbeds.size}")
                Log.i("Animeav1", "Total enlaces DUB: ${dubEmbeds.size}")

                var subProcessed = 0
                var dubProcessed = 0

                subEmbeds.forEach { (server, url) ->
                    Log.d("Animeav1", "Procesando SUB enlace #${++subProcessed}: $server")
                    val startTime = System.currentTimeMillis()

                    try {
                        loadCustomExtractor(
                            "Animeav1 [SUB:$server]",
                            url,
                            "",
                            subtitleCallback,
                            callback
                        )

                        val duration = System.currentTimeMillis() - startTime

                        stats[server] = (stats[server] ?: 0) + 1
                        Log.d("Animeav1", "✓ SUB:$server procesado exitosamente en ${duration}ms")

                    } catch (e: Exception) {
                        failedServers.add("SUB:$server")
                        Log.e("Animeav1", "✗ Error al procesar SUB:$server - ${e.javaClass.simpleName}: ${e.message}")
                        Log.e("Animeav1", "URL problemática: $url")
                        e.printStackTrace()
                    }
                }

                dubEmbeds.forEach { (server, url) ->
                    Log.d("Animeav1", "Procesando DUB enlace #${++dubProcessed}: $server")
                    val startTime = System.currentTimeMillis()

                    try {
                        loadCustomExtractor(
                            "Animeav1 [DUB:$server]",
                            url,
                            "",
                            subtitleCallback,
                            callback
                        )

                        val duration = System.currentTimeMillis() - startTime

                        stats[server] = (stats[server] ?: 0) + 1
                        Log.d("Animeav1", "✓ DUB:$server procesado exitosamente en ${duration}ms")

                    } catch (e: Exception) {
                        failedServers.add("DUB:$server")
                        Log.e("Animeav1", "✗ Error al procesar DUB:$server - ${e.javaClass.simpleName}: ${e.message}")
                        Log.e("Animeav1", "URL problemática: $url")
                        e.printStackTrace()
                    }
                }

                Log.i("Animeav1", "RESUMEN DE PROCESAMIENTO:")
                Log.i("Animeav1", "Total procesados - SUB: $subProcessed, DUB: $dubProcessed")
                Log.i("Animeav1", "Servidores exitosos: ${stats.entries.joinToString { "${it.key}(${it.value})" }}")

                if (failedServers.isNotEmpty()) {
                    Log.w("Animeav1", "Servidores fallidos: ${failedServers.joinToString()}")
                }

            } catch (e: Exception) {
                Log.e("Animeav1", "Error al parsear JSON: ${e.message}")
                Log.e("Animeav1", "JSON problemático: $embedsJson")
                return false
            }
        } else {
            Log.e("Animeav1", "embedsJson está vacío o nulo")
            return false
        }

        return true
    }

    suspend fun loadCustomExtractor(
        name: String? = null,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        name ?: link.source,
                        name ?: link.name,
                        link.url,
                    ) {
                        this.quality = when {
                            else -> quality ?: link.quality
                        }
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }
}