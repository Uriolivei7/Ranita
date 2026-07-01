package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import android.util.Log
import kotlinx.coroutines.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnimeflvnetProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://www4.animeflv.net/"
    override var name = "AnimeFLV"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/browse?type[]=movie&order=updated", "Películas"),
            Pair("$mainUrl/browse?status[]=2&order=default", "Animes"),
            Pair("$mainUrl/browse?status[]=1&order=rating", "En emision"),
        )
        val items = ArrayList<HomePageList>()
        val isHorizontal = true
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("main.Main ul.ListEpisodios li").mapNotNull {
                    val title = it.selectFirst("strong.Title")?.text() ?: return@mapNotNull null
                    val poster = it.selectFirst("span img")?.attr("src") ?: return@mapNotNull null
                    val epRegex = Regex("(-(\\d+)\$)")
                    val url = it.selectFirst("a")?.attr("href")?.replace(epRegex, "")
                        ?.replace("ver/", "anime/") ?: return@mapNotNull null
                    val epNum =
                        it.selectFirst("span.Capi")?.text()?.replace("Episodio ", "")?.toIntOrNull()
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title), epNum)
                    }
                }, isHorizontal
            )
        )

        coroutineScope {
            urls.map { (url, name) ->
                async { // Inicia una tarea asíncrona para cada URL
                    val doc = app.get(url).document
                    val home = doc.select("ul.ListAnimes li article").mapNotNull {
                        val title = it.selectFirst("h3.Title")?.text() ?: return@mapNotNull null
                        val poster =
                            it.selectFirst("figure img")?.attr("src") ?: return@mapNotNull null
                        newAnimeSearchResponse(
                            title,
                            fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                        ) {
                            posterUrl = fixUrl(poster)
                            addDubStatus(getDubStatus(title))
                        }
                    }

                    items.add(HomePageList(name, home))
                }
            }.awaitAll() // Espera a que todas las tareas asíncronas terminen
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    data class SearchObject(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val response = app.post(
            "https://www3.animeflv.net/api/animes/search",
            data = mapOf(Pair("value", query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        return json.map { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/animes/covers/${searchr.id}.jpg"
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrl(image)
                addDubStatus(getDubStatus(title))
            }
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/browse?q=$query").document
        val sss = doc.select("ul.ListAnimes article").map { ll ->
            val title = ll.selectFirst("h3")?.text() ?: ""
            val image = ll.selectFirst("figure img")?.attr("src") ?: ""
            val href = ll.selectFirst("a")?.attr("href") ?: ""
            newAnimeSearchResponse(title, href){
                this.posterUrl = image
                addDubStatus(getDubStatus(title))
            }
        }
        return sss
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()
        val title = doc.selectFirst("h1.Title")!!.text()
        val poster = doc.selectFirst("div.AnimeCover div.Image figure img")?.attr("src")!!
        val description = doc.selectFirst("div.Description p")?.text()
        val type = doc.selectFirst("span.Type")?.text() ?: ""
        val status = when (doc.selectFirst("p.AnmStts span")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("nav.Nvgnrs a")
            .map { it?.text()?.trim().toString() }

        val episodesFromHTML = doc.select("ul.ListCaps#episodeList li")
        Log.d("AnimeFlvProvider", "DEBUG: Episodios encontrados en HTML: ${episodesFromHTML.size}")

        if (episodesFromHTML.isNotEmpty()) {
            episodesFromHTML.forEachIndexed { index, episodeLi ->
                val episodeLink = episodeLi.selectFirst("a")?.attr("href")
                val episodeTitle = episodeLi.selectFirst("h3.Title")?.text()
                val episodeNumber = episodeLi.selectFirst("p")?.text()?.replace("Episodio ", "")?.toIntOrNull()

                val episodeImg = episodeLi.selectFirst("figure img")
                val imgSrc = episodeImg?.attr("src")
                val imgDataSrc = episodeImg?.attr("data-src")

                Log.d("AnimeFlvProvider", "DEBUG: Episodio ${index + 1} - src: '$imgSrc', data-src: '$imgDataSrc'")

                val episodePoster = when {
                    imgSrc?.startsWith("https://") == true -> imgSrc
                    !imgDataSrc.isNullOrEmpty() -> imgDataSrc
                    else -> imgSrc
                }

                Log.d("AnimeFlvProvider", "DEBUG: Poster final seleccionado: '$episodePoster'")

                if (!episodeLink.isNullOrEmpty() && episodeNumber != null) {
                    val fullLink = if (episodeLink.startsWith("http")) episodeLink else "https://www3.animeflv.net$episodeLink"

                    episodes.add(
                        newEpisode(fullLink) {
                            this.name = episodeTitle ?: "Episodio $episodeNumber"
                            this.episode = episodeNumber
                            this.posterUrl = episodePoster?.let { fixUrl(it) }
                            this.runTime = null
                        }
                    )
                }
            }
            Log.d("AnimeFlvProvider", "DEBUG: Total episodios agregados desde HTML: ${episodes.size}")
        }

        if (episodes.isEmpty()) {
            Log.d("AnimeFlvProvider", "DEBUG: No se encontraron episodios en HTML, usando JavaScript")
            doc.select("script").map { script ->
                if (script.data().contains("var episodes = [")) {
                    val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                    data.split("],").forEach {
                        val epNum = it.removePrefix("[").substringBefore(",")
                        val link = url.replace("/anime/", "/ver/") + "-$epNum"
                        episodes.add(
                            newEpisode(link) {
                                this.name = "Episodio $epNum"
                                this.episode = epNum.toIntOrNull()
                                this.posterUrl = fixUrl(poster)
                                this.runTime = null
                            }
                        )
                    }
                }
            }
            Log.d("AnimeFlvProvider", "DEBUG: Total episodios agregados desde JavaScript: ${episodes.size}")
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = fixUrl(poster)
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
        }
    }

    data class MainServers(
        @JsonProperty("SUB")
        val sub: List<Sub> = emptyList(),
        @JsonProperty("LAT")
        val lat: List<Sub> = emptyList(),
        @JsonProperty("ENG")
        val eng: List<Sub> = emptyList(),
        @JsonProperty("VOSE")
        val vose: List<Sub> = emptyList(),
    )

    data class Sub(
        val code: String,
    )

    data class EpisodeResponse(
        @JsonProperty("anime_id")
        val animeId: String = "",
        @JsonProperty("episode_id")
        val episodeId: String = "",
        @JsonProperty("anime")
        val anime: String = "",
        @JsonProperty("episode")
        val episode: String = "",
        @JsonProperty("date")
        val date: String = "",
    )

    data class EpisodeSourcesResponse(
        @JsonProperty("anime_id")
        val animeId: String = "",
        @JsonProperty("episode_id")
        val episodeId: String = "",
        @JsonProperty("sources")
        val sources: Sources = Sources(),
    )

    data class Sources(
        @JsonProperty("SUB")
        val sub: List<SourceEntry> = emptyList(),
        @JsonProperty("LAT")
        val lat: List<SourceEntry> = emptyList(),
        @JsonProperty("ENG")
        val eng: List<SourceEntry> = emptyList(),
        @JsonProperty("VOSE")
        val vose: List<SourceEntry> = emptyList(),
    )

    data class SourceEntry(
        val code: String,
        @JsonProperty("title")
        val title: String? = null,
        @JsonProperty("type")
        val type: String? = null,
    )


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "AnimeFlvProvider"
        Log.d(tag, "=== loadLinks: $data ===")
        var linksFound = false

        try {
            // Try fetching from multiple possible base domains
            val domains = listOf("www4.animeflv.net", "www3.animeflv.net", "animeflv.net")
            val pagePath = data.substringAfter("//").substringAfter("/")
            var mainDoc: org.jsoup.nodes.Document? = null
            var rawHtml: String? = null

            for (domain in domains) {
                try {
                    val altUrl = "https://$domain/$pagePath".replace("//", "/").replace(":/", "://")
                    Log.d(tag, "Intentando fetch: $altUrl")
                    val resp = app.get(altUrl)
                    mainDoc = resp.document
                    rawHtml = resp.text
                    Log.d(tag, "Fetch OK desde $domain (${rawHtml.length} chars)")
                    break
                } catch (e: Exception) {
                    Log.w(tag, "Fetch falló para $domain: ${e.message}")
                }
            }

            if (mainDoc == null || rawHtml == null) {
                Log.e(tag, "No se pudo obtener la página desde ningún dominio")
                return false
            }

            val scripts = mainDoc.select("script")
            Log.d(tag, "Scripts encontrados: ${scripts.size}")

            var animeId: String? = null
            var episodeId: String? = null
            var inlineVideosJson: String? = null

            scripts.forEachIndexed { i, scriptElement ->
                val src = scriptElement.attr("src")
                if (src.isNotBlank()) {
                    Log.d(tag, "Script[$i] src: $src")
                } else {
                    val sd = scriptElement.data().trim()
                    if (sd.length in 10..500) {
                        Log.d(tag, "Script[$i] inline: ${sd.take(300)}")
                    }
                }

                val scriptData = scriptElement.data()
                val animeIdMatch = Regex("""var anime_id\s*=\s*(\d+);""").find(scriptData)
                if (animeIdMatch != null) animeId = animeIdMatch.groupValues[1]
                val episodeIdMatch = Regex("""var episode_id\s*=\s*(\d+);""").find(scriptData)
                if (episodeIdMatch != null) episodeId = episodeIdMatch.groupValues[1]
                val videosMatch = Regex("""var videos\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(scriptData)
                if (videosMatch != null) inlineVideosJson = videosMatch.groupValues[1]
            }

            Log.d(tag, "animeId=$animeId episodeId=$episodeId inlineVideosJson=${inlineVideosJson?.take(100) ?: "null"}")

            // Try #1: inline JSON (if found and not empty)
            if (!inlineVideosJson.isNullOrBlank() && inlineVideosJson != "[]") {
                Log.d(tag, "Intentando inline JSON")
                try {
                    val serversJson = parseJson<MainServers>(inlineVideosJson)
                    val allServers = serversJson.sub + serversJson.lat + serversJson.eng + serversJson.vose
                    Log.d(tag, "Servidores inline: ${allServers.size}")
                    for (server in allServers) {
                        if (processServerCode(server.code, data, subtitleCallback, callback, tag)) {
                            linksFound = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Fallo inline JSON: ${e.message}")
                }
            }

            // Try #2: Look for <table class="RTbl"> with download links
            if (!linksFound) {
                val table = mainDoc.selectFirst("table.RTbl")
                if (table != null) {
                    Log.d(tag, "Tabla RTbl encontrada!")
                    val rows = table.select("tr")
                    Log.d(tag, "Filas en tabla: ${rows.size}")
                    for (row in rows) {
                        val links = row.select("a[href]")
                        for (link in links) {
                            val href = link.attr("href")
                            val serverName = row.selectFirst("td")?.text() ?: ""
                            Log.d(tag, "RTbl link: server=$serverName href=${href.take(100)}")
                            if (href.isNotBlank() && !href.contains("ouo.io")) {
                                if (processServerCode(href, data, subtitleCallback, callback, tag)) {
                                    linksFound = true
                                }
                            }
                        }
                    }
                } else {
                    Log.d(tag, "No se encontró tabla RTbl")
                }
            }

            // Try #3: Look for any <iframe> with video source
            if (!linksFound) {
                val iframes = mainDoc.select("iframe[src]")
                Log.d(tag, "Iframes encontrados: ${iframes.size}")
                for (iframe in iframes) {
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                        Log.d(tag, "Iframe src: ${src.take(100)}")
                        if (processServerCode(src, data, subtitleCallback, callback, tag)) {
                            linksFound = true
                        }
                    }
                }
            }

            // Try #4: API endpoints
            if (!linksFound && animeId != null && episodeId != null) {
                val postData = mapOf("anime_id" to animeId!!, "episode_id" to episodeId!!)
                val apiHeaders = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                )

                val apiAttempts = mutableListOf<Pair<String, suspend () -> String>>()
                for (domain in listOf("www3.animeflv.net", "animeflv.net")) {
                    apiAttempts.add("POST $domain/api/animes/episode" to {
                        app.post("https://$domain/api/animes/episode",
                            data = postData, referer = data, headers = apiHeaders).text
                    })
                    apiAttempts.add("POST $domain/api/episode" to {
                        app.post("https://$domain/api/episode",
                            data = postData, referer = data, headers = apiHeaders).text
                    })
                    apiAttempts.add("GET $domain/api/episode/$episodeId" to {
                        app.get("https://$domain/api/episode/$episodeId",
                            referer = data, headers = apiHeaders).text
                    })
                    apiAttempts.add("GET $domain/api/animes/episode?anime_id=$animeId&episode_id=$episodeId" to {
                        app.get("https://$domain/api/animes/episode?anime_id=$animeId&episode_id=$episodeId",
                            referer = data, headers = apiHeaders).text
                    })
                }

                for ((apiName, attempt) in apiAttempts) {
                    if (linksFound) break
                    try {
                        val response = attempt()
                        if (response.isBlank() || response.startsWith("<!") || response in listOf("[]", "{}")) {
                            Log.w(tag, "API $apiName: respuesta inválida")
                            continue
                        }
                        Log.d(tag, "API $apiName OK: ${response.take(300)}")

                        var sourcesFound = false
                        tryParseJson<EpisodeSourcesResponse>(response)?.let { epResponse ->
                            val allSources = epResponse.sources.sub + epResponse.sources.lat +
                                    epResponse.sources.eng + epResponse.sources.vose
                            if (allSources.isNotEmpty()) {
                                Log.d(tag, "EpisodeSourcesResponse: ${allSources.size} fuentes")
                                for (source in allSources) {
                                    if (processServerCode(source.code, data, subtitleCallback, callback, tag)) {
                                        linksFound = true; sourcesFound = true
                                    }
                                }
                            }
                        }
                        if (!sourcesFound) {
                            tryParseJson<MainServers>(response)?.let { servers ->
                                val allServers = servers.sub + servers.lat + servers.eng + servers.vose
                                if (allServers.isNotEmpty()) {
                                    Log.d(tag, "MainServers: ${allServers.size} servidores")
                                    for (server in allServers) {
                                        if (processServerCode(server.code, data, subtitleCallback, callback, tag)) {
                                            linksFound = true; sourcesFound = true
                                        }
                                    }
                                }
                            }
                        }
                        if (!sourcesFound) {
                            try {
                                val rawCodes = parseJson<List<String>>(response)
                                Log.d(tag, "Lista de ${rawCodes.size} códigos")
                                for (code in rawCodes) {
                                    if (processServerCode(code, data, subtitleCallback, callback, tag)) linksFound = true
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "API $apiName error: ${e.message}")
                    }
                }
            }

            // Try #5: Search raw HTML for any video URLs or JSON data
            if (!linksFound) {
                Log.d(tag, "Busqueda exhaustiva en HTML (${rawHtml.length} chars)")
                val patterns = listOf(
                    Regex("""["']code["']\s*:\s*["']([^"']+)["']"""),
                    Regex("""["']url["']\s*:\s*["']([^"']+)["']"""),
                    Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
                    Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)"""),
                    Regex("""https?://[^\s"'<>]*(?:vidhide|mega|ok\.ru|yourupload|streamlare|streamtape|mixdrop|doodstream)[^\s"'<>]*"""),
                    Regex("""<iframe[^>]+src=["']([^"']+)["']"""),
                )
                for ((pi, pattern) in patterns.withIndex()) {
                    val matches = pattern.findAll(rawHtml)
                    val count = matches.count()
                    if (count > 0) {
                        Log.d(tag, "Patrón[$pi] ('${pattern.pattern.take(40)}'): $count match(es)")
                        for (match in matches) {
                            val value = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                            Log.d(tag, "  → ${value.take(120)}")
                            if (processServerCode(value, data, subtitleCallback, callback, tag)) {
                                linksFound = true
                            }
                        }
                    }
                }
            }

            Log.d(tag, "=== loadLinks FIN: linksFound=$linksFound ===")
            return linksFound
        } catch (e: Exception) {
            Log.e(tag, "Error general en loadLinks: ${e.message}")
            return false
        }
    }

    private suspend fun processServerCode(
        code: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tag: String
    ): Boolean {
        if (code.isBlank()) return false
        Log.d(tag, "Procesando: ${code.take(100)}")
        return try {
            withTimeout(7000) {
                val success = loadExtractor(code, referer, subtitleCallback, callback)
                if (success) Log.d(tag, "OK: ${code.take(60)}")
                else Log.d(tag, "loadExtractor devolvió false: ${code.take(60)}")
                success
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(tag, "Timeout: ${code.take(60)}")
            false
        } catch (e: Exception) {
            Log.e(tag, "Error: ${code.take(60)} - ${e.message}")
            false
        }
    }

    private inline fun <reified T : Any> tryParseJson(json: String): T? {
        return try {
            parseJson<T>(json)
        } catch (_: Exception) {
            null
        }
    }
}
