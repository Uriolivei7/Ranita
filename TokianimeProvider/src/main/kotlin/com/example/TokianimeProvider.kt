package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class TokianimeProvider : MainAPI() {
    override var mainUrl = "https://tokianime.tv"
    override var name = "TokiAnime"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "mx"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    override val mainPage = mainPageOf(
        "#ultimos" to "Últimos Episodios",
        "/" to "Tendencia",
        "/genero/comedia" to "Comedia",
        "/genero/accion" to "Acción",
        "/genero/fantasia" to "Fantasía",
        "/genero/drama" to "Drama",
        "/genero/aventura" to "Aventura",
        "/genero/romance" to "Romance",
        "/genero/recuentos-de-la-vida" to "Recuentos de la Vida",
        "/genero/sci-fi" to "Sci-Fi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val url = if (request.data == "/" || request.data == "#ultimos") {
                mainUrl
            } else {
                "$mainUrl${request.data}${if (page > 1) "?page=$page" else ""}"
            }
            Log.i("Tokianime", "getMainPage: fetching section='${request.name}' data='${request.data}' url='$url' page=$page")
            val html = app.get(url, headers = headers).text
            Log.i("Tokianime", "getMainPage: HTML length=${html.length} primeros_200=${html.take(200)}")
            val doc = Jsoup.parse(html)

            if (request.data == "/") {
                val items = mutableListOf<SearchResponse>()
                val links = doc.select("section a[href^='/anime/']")
                Log.i("Tokianime", "getMainPage(/): encontré ${links.size} links section a[href^=/anime/]")
                links.forEach { link ->
                    val href = link.attr("href")
                    if (href.isBlank()) return@forEach
                    val img = link.selectFirst("img")
                    val title = img?.attr("alt") ?: link.attr("title").ifBlank { return@forEach }
                    val poster = img?.attr("src") ?: ""
                    items.add(newMovieSearchResponse(title, "$mainUrl$href", TvType.Anime) {
                        this.posterUrl = fixPoster(poster)
                    })
                }
                Log.i("Tokianime", "getMainPage(/): items después de parsear=${items.size}")
                if (items.isEmpty()) {
                    Log.w("Tokianime", "getMainPage(/): 0 items, revisa el selector CSS 'section a[href^=/anime/]'")

                    val fallbackLinks = doc.select("a[href^='/anime/']")
                    Log.i("Tokianime", "getMainPage(/): fallback genérico encontró ${fallbackLinks.size} links")
                    return null
                }
                return newHomePageResponse(listOf(HomePageList("Tendencia", items.distinctBy { it.url }.take(50))), false)
            }

            if (request.data == "#ultimos") {
                val items = mutableListOf<SearchResponse>()
                val links = doc.select("a[href^='/watch/']")
                Log.i("Tokianime", "getMainPage(#ultimos): encontré ${links.size} links a[href^=/watch/]")
                val seenSlugs = mutableSetOf<String>()
                links.forEach { link ->
                    val href = link.attr("href")
                    val img = link.selectFirst("img")
                    val poster = img?.attr("src") ?: ""
                    val title = (img?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: link.attr("title").takeIf { it.isNotBlank() }
                        ?: link.text().takeIf { it.isNotBlank() }
                        ?: return@forEach).trim()

                    if (title.equals("Ver ahora", ignoreCase = true) || title.equals("Watch now", ignoreCase = true)) return@forEach

                    val slug = Regex("""/watch/([^/]+)""").find(href)?.groupValues?.get(1) ?: return@forEach
                    if (seenSlugs.contains(slug)) return@forEach
                    seenSlugs.add(slug)
                    val animeUrl = "$mainUrl/anime/$slug"
                    items.add(newMovieSearchResponse(title, animeUrl, TvType.Anime) {
                        this.posterUrl = fixPoster(poster)
                    })
                }
                Log.i("Tokianime", "getMainPage(#ultimos): items=${items.size} slugs_unicos=${seenSlugs.size}")
                if (items.isEmpty()) {
                    Log.w("Tokianime", "getMainPage(#ultimos): 0 items, revisa selector 'a[href^=/watch/]'")
                    return null
                }
                return newHomePageResponse(listOf(HomePageList("Últimos Episodios", items.take(50))), false)
            }

            val items = mutableListOf<SearchResponse>()
            val links = doc.select("a[href^='/anime/']")
            Log.i("Tokianime", "getMainPage('${request.data}'): encontré ${links.size} links a[href^=/anime/]")
            links.forEach { link ->
                val href = link.attr("href")
                if (href.isBlank()) return@forEach
                val img = link.selectFirst("img")
                val title = img?.attr("alt") ?: link.text().ifBlank { return@forEach }
                val poster = img?.attr("src") ?: ""
                items.add(newMovieSearchResponse(title, "$mainUrl$href", TvType.Anime) {
                    this.posterUrl = fixPoster(poster)
                })
            }

            val hasNext = doc.select("a:contains(Siguiente)").isNotEmpty()
            val listName = request.name.ifBlank { request.data }
            Log.i("Tokianime", "getMainPage('${request.data}'): items=${items.size} hasNext=$hasNext")
            if (items.isEmpty()) {
                Log.w("Tokianime", "getMainPage('${request.data}'): 0 items, página posiblemente dinámica o selector incorrecto")
                return null
            }
            return newHomePageResponse(listOf(HomePageList(listName, items.distinctBy { it.url })), hasNext)
        } catch (e: Exception) {
            Log.e("Tokianime", "getMainPage error: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        var page = 0
        val pageSize = 20
        var hasMore = true

        while (hasMore && results.size < 50) {
            try {
                val apiUrl = "$mainUrl/api/catalog?adult=0&q=${query.replace(" ", "%20")}&page=$page&pageSize=$pageSize"
                Log.i("Tokianime", "search: API query='$query' page=$page url=$apiUrl")
                val resp = app.get(apiUrl, headers = headers).text
                Log.i("Tokianime", "search: API respuesta (primeros 300)=${resp.take(300)}")

                if (resp.contains("\"items\":[]") || resp.contains("\"items\": []")) {
                    hasMore = false
                } else {
                    val itemMatches = Regex(""""slug":"([^"]+)"[^}]*?"title":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(resp).toList()
                    val posters = Regex(""""coverImage":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(resp).toList()
                    Log.i("Tokianime", "search: page=$page items=${itemMatches.size} posters=${posters.size}")
                    var added = 0
                    for ((idx, match) in itemMatches.withIndex()) {
                        val slug = match.groupValues[1]
                        val title = match.groupValues[2]
                        if (seen.contains(slug)) continue
                        seen.add(slug)
                        val poster = if (idx < posters.size) posters[idx].groupValues[1].replace("\\/", "/") else ""
                        results.add(newMovieSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                            this.posterUrl = poster
                        })
                        added++
                    }
                    val totalMatch = Regex(""""total":(\d+)""").find(resp)
                    val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    hasMore = results.size < total && results.size < 50 && added > 0
                }
                page++
            } catch (e: Exception) {
                Log.e("Tokianime", "search: API error=${e.message}")
                hasMore = false
            }
        }

        Log.i("Tokianime", "search: total resultados=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val watchMatch = Regex("""/watch/([^/]+)""").find(url)
            val slug = watchMatch?.groupValues?.get(1)
                ?: url.substringAfter("/anime/").substringBefore("?")
            if (slug.isBlank()) {
                Log.w("Tokianime", "load: slug vacío para url='$url'")
                return null
            }
            Log.i("Tokianime", "load: slug='$slug' url='$url'")

            val pageUrl = "$mainUrl/anime/$slug"
            val html = app.get(pageUrl, headers = headers).text
            Log.i("Tokianime", "load: HTML length=${html.length} primeros_300=${html.take(300)}")
            val doc = Jsoup.parse(html)

            val titleRaw = doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            val title = titleRaw.replace(Regex("""\s*(Sub Español Online HD|Sub Español|Online HD)\s*$"""), "").trim()
            Log.i("Tokianime", "load: title='$title' (raw='$titleRaw')")

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
            val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: ""

            val scoreText = doc.select("div:contains(Puntuación)").firstOrNull()
                ?.text()?.substringAfter("Puntuación")?.substringBefore("/")?.trim()
            val score = scoreText?.toFloatOrNull()

            val yearRaw = doc.select("span.tabular-nums").firstOrNull()?.text()?.trim()?.toIntOrNull()
                ?: Regex("""\b(19[0-9]{2}|20[0-9]{2})\b""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            val year = if (yearRaw != null && yearRaw in 1900..2050) yearRaw else null

            val tags = doc.select("a[href^='/genero/']").mapNotNull { it.text().ifBlank { null } }.distinct()
            Log.i("Tokianime", "load: tags=$tags year=$year score=$score")

            val episodes = mutableListOf<Episode>()
            val seenSlugs = mutableSetOf<String>()

            suspend fun fetchEpisodes(epSlug: String, seasonNum: Int): List<Episode> {
                val result = mutableListOf<Episode>()
                try {
                    val apiUrl = "$mainUrl/api/anime/$epSlug/episodes"
                    Log.i("Tokianime", "load: consultando API slug='$epSlug' season=$seasonNum: $apiUrl")
                    val apiResp = app.get(apiUrl, headers = headers).text
                    val withVideoMatch = Regex(""""withVideo":\[([^\]]+)\]""").find(apiResp)
                    if (withVideoMatch != null) {
                        val epNums = Regex("""\d+""").findAll(withVideoMatch.groupValues[1])
                            .map { it.value.toIntOrNull() }.filterNotNull().toList()
                        Log.i("Tokianime", "load: API slug='$epSlug' withVideo=${epNums}")
                        for (epNum in epNums) {
                            val metaRegex = Regex(""""$epNum":\{"title":"([^"]*)","overview":"([^"]*)"""")
                            val metaMatch = metaRegex.find(apiResp)
                            val epTitle = metaMatch?.groupValues?.get(1)
                            val epOverview = metaMatch?.groupValues?.get(2)
                            val thumbRegex = Regex(""""$epNum":"([^"]+)"""")
                            val epThumbnail = thumbRegex.find(apiResp)?.groupValues?.get(1)
                            result.add(newEpisode("$mainUrl/watch/$epSlug/$epNum") {
                                this.name = if (epTitle.isNullOrBlank()) "Episodio $epNum" else epTitle
                                this.episode = epNum
                                this.season = seasonNum
                                this.description = epOverview
                                this.posterUrl = epThumbnail
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Tokianime", "load: API falló para slug='$epSlug': ${e.message}")
                }
                return result
            }

            try {
                val suggestButton = doc.select("button:contains(Ver orden sugerido)").firstOrNull()
                if (suggestButton != null) {
                    val listId = suggestButton.attr("aria-controls")
                    val listItems = doc.select("div#$listId li")
                    Log.i("Tokianime", "load: 'Ver orden sugerido' encontrado con ${listItems.size} entradas")
                    data class SeasonEntry(val slug: String, val name: String, val order: Int)
                    val entries = mutableListOf<SeasonEntry>()
                    for ((idx, item) in listItems.withIndex()) {
                        val aTag = item.selectFirst("a[href^='/anime/']") ?: continue
                        val seasonSlug = aTag.attr("href").substringAfter("/anime/").substringBefore("?")
                        if (seasonSlug.isBlank() || seenSlugs.contains(seasonSlug)) continue
                        seenSlugs.add(seasonSlug)
                        val seasonName = item.select("span.pointer-events-none span span").firstOrNull()?.text()
                            ?: seasonSlug
                        entries.add(SeasonEntry(seasonSlug, seasonName, idx))
                    }

                    entries.sortWith(compareBy<SeasonEntry> {
                        when {
                            it.name.contains("Temporada", ignoreCase = true) -> 0
                            it.name.contains("OVA", ignoreCase = true) -> 1
                            it.name.contains("Especial", ignoreCase = true) || it.name.contains("Special", ignoreCase = true) -> 2
                            it.name.contains("Película", ignoreCase = true) || it.name.contains("Movie", ignoreCase = true) -> 3
                            else -> 4
                        }
                    }.thenBy { it.order })
                    for ((seasonNum, entry) in entries.withIndex()) {
                        Log.i("Tokianime", "load: orden sugerido #${seasonNum+1} slug='${entry.slug}' name='${entry.name}'")
                        val seasonEps = fetchEpisodes(entry.slug, seasonNum + 1)
                        episodes.addAll(seasonEps)
                    }
                }
            } catch (e: Exception) {
                Log.w("Tokianime", "load: error parseando 'Ver orden sugerido': ${e.message}")
            }

            if (episodes.isEmpty() || !seenSlugs.contains(slug)) {
                if (!seenSlugs.contains(slug)) {
                    seenSlugs.add(slug)
                    val slugEps = fetchEpisodes(slug, 1)
                    episodes.addAll(slugEps)
                    Log.i("Tokianime", "load: episodios desde API slug actual=${slugEps.size}")
                }
            }

            if (episodes.isEmpty()) {
                val epItems = doc.select("a[href^='/watch/$slug/']")
                Log.i("Tokianime", "load: episodios por DOM (a[href^=/watch/$slug/]) = ${epItems.size}")
                epItems.forEach { epLink ->
                    val epHref = epLink.attr("href")
                    val epText = epLink.text().trim()
                    val epNum = Regex("""(\d+)$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    if (epNum != null) {
                        episodes.add(newEpisode("$mainUrl$epHref") {
                            this.name = if (epText.isBlank() || epText.contains("Ver ahora", ignoreCase = true)) "Episodio $epNum" else epText
                            this.episode = epNum
                            this.season = 1
                        })
                    } else {
                        Log.w("Tokianime", "load: no se pudo extraer número de episodio de href='$epHref' texto='$epText'")
                    }
                }
            }

            if (episodes.isEmpty()) {
                Log.i("Tokianime", "load: DOM no dio episodios, buscando regex de total de episodios")
                val totalEps = Regex("""(\d+)\s*eps""", RegexOption.IGNORE_CASE).find(html)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*(\d+)""").find(html)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*de\s*(\d+)""").find(html)?.groupValues?.get(2)?.toIntOrNull()
                Log.i("Tokianime", "load: totalEps por regex=$totalEps")
                if (totalEps != null && totalEps in 1..1000) {
                    Log.i("Tokianime", "load: generando $totalEps episodios por rango numérico")
                    for (i in 1..totalEps) {
                        episodes.add(newEpisode("$mainUrl/watch/$slug/$i") {
                            this.name = "Episodio $i"
                            this.episode = i
                            this.season = 1
                        })
                    }
                }
            }

            episodes.sortBy { it.episode }
            Log.i("Tokianime", "load: episodios finales=${episodes.size} slugs_procesados=${seenSlugs}")

            if (episodes.isEmpty()) {
                Log.w("Tokianime", "load: 0 episodios! Slug='$slug' no tiene episodios. Agregando ep1 por defecto")
                episodes.add(newEpisode("$mainUrl/watch/$slug/1") {
                    this.name = title
                    this.episode = 1
                    this.season = 1
                })
            }

            val recommendations = doc.select("a[href^='/anime/']").mapNotNull { link ->
                val href = link.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val img = link.selectFirst("img")
                val recTitle = img?.attr("alt") ?: return@mapNotNull null
                val recPoster = img?.attr("src") ?: ""
                newMovieSearchResponse(recTitle, "$mainUrl$href", TvType.Anime) {
                    this.posterUrl = fixPoster(recPoster)
                }
            }.distinctBy { it.url }.take(20)
            Log.i("Tokianime", "load: recomendaciones=${recommendations.size}")

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixPoster(poster)
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                if (score != null) this.score = Score.from10((score * 10).toInt())
                if (year != null) this.year = year
            }
        } catch (e: Exception) {
            Log.e("Tokianime", "load error: $url - ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val watchMatch = Regex("""/watch/([^/]+)/(\d+)""").find(data)
            if (watchMatch == null) {
                Log.w("Tokianime", "loadLinks: no se pudo extraer slug/ep de data='$data'")
                return false
            }
            val slug = watchMatch.groupValues[1]
            val epNum = watchMatch.groupValues[2]
            Log.i("Tokianime", "loadLinks: slug='$slug' ep='$epNum' data='$data'")

            val watchUrl = "$mainUrl/watch/$slug/$epNum"
            val html = app.get(watchUrl, headers = headers).text
            Log.i("Tokianime", "loadLinks: HTML length=${html.length} primeros_400=${html.take(400)}")

            val rscChunks = Regex("""self\.__next_f\.push\(\[.*?""").findAll(html).toList()
            Log.i("Tokianime", "loadLinks: chunks RSC = ${rscChunks.size}")

            val hasRankedServers = html.contains("rankedServers")
            Log.i("Tokianime", "loadLinks: contiene 'rankedServers'=$hasRankedServers")

            val normalized = html.replace("\\\"", "\"")
            val playRegex = Regex(""""lang":"([^"]+)".*?"quality":"([^"]+)".*?"play":\{"src":"(/api/player/source[^"]+)""")
            val matches = playRegex.findAll(normalized).toList()
            Log.i("Tokianime", "loadLinks: matches de regex playSrc (normalizado) = ${matches.size}")

            if (matches.isEmpty()) {
                Log.w("Tokianime", "loadLinks: 0 matches incluso normalizado. Buscando '/api/player/source' en HTML crudo...")
                val rawMatches = Regex("""/api/player/source[^"\\]*""").findAll(html).toList()
                val normMatches = Regex("""/api/player/source[^"]*""").findAll(normalized).toList()
                Log.i("Tokianime", "loadLinks: raw src matches=${rawMatches.map { it.value }}")
                Log.i("Tokianime", "loadLinks: normalized src matches=${normMatches.map { it.value.take(100) }}")

                val rscFull = rscChunks.joinToString("") { it.value }
                    .replace("\\\"", "\"")
                    .replace("\\n", "")
                    .replace("\\t", "")
                val rankedMatch = Regex(""""rankedServers""").find(rscFull)
                Log.i("Tokianime", "loadLinks: rankedServers en RSC unido=${rankedMatch != null}")
                if (rankedMatch != null) {

                    val srcRegex = Regex(""""src":"(/api/player/source[^"]+)""")
                    val srcs = srcRegex.findAll(rscFull).map { it.groupValues[1] }.toList()
                    Log.i("Tokianime", "loadLinks: srcs de RSC unido = ${srcs}")
                    for (src in srcs) {
                        try {
                            val apiUrl = "$mainUrl$src"
                            Log.i("Tokianime", "loadLinks: intentando API (RSC fallback): $apiUrl")
                            val m3u8Resp = app.get(apiUrl, headers = headers).text
                            Log.i("Tokianime", "loadLinks: respuesta API (primeros 500 chars)=${m3u8Resp.take(500)}")
                            val masterUrl = Regex("""(https?://[^\s]+\.m3u8[^\s]*)""").find(m3u8Resp)?.value
                            if (masterUrl != null) {
                                Log.i("Tokianime", "loadLinks: M3U8 encontrado via RSC fallback! url='$masterUrl'")
                                callback.invoke(newExtractorLink("Tokianime", "Tokianime", masterUrl, ExtractorLinkType.M3U8) {
                                    this.referer = mainUrl
                                })
                                return true
                            }
                        } catch (e: Exception) {
                            Log.e("Tokianime", "loadLinks: RSC fallback error: ${e.message}")
                        }
                    }
                }
                return false
            }

            var found = false
            for ((idx, match) in matches.withIndex()) {
                val lang = match.groupValues[1]
                val qualityStr = match.groupValues[2]
                val playSrc = match.groupValues[3]
                val quality = qualityStr.takeWhile { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                Log.i("Tokianime", "loadLinks: match[$idx] lang='$lang' quality='$qualityStr' q=$quality src='$playSrc'")

                try {
                    val cleanSrc = playSrc.replace("""\u0026""", "&")
                    val apiUrl = "$mainUrl$cleanSrc"
                    Log.i("Tokianime", "loadLinks: consultando API player source: $apiUrl")
                    val m3u8Resp = app.get(apiUrl, headers = headers).text
                    Log.i("Tokianime", "loadLinks: respuesta API (primeros 500 chars)=${m3u8Resp.take(500)}")

                    val masterUrl = Regex("""(https?://[^\s]+\.m3u8[^\s]*)""").find(m3u8Resp)?.value
                    if (masterUrl != null) {
                        Log.i("Tokianime", "loadLinks: M3U8 encontrado! lang='$lang' q=$quality url='$masterUrl'")
                        callback.invoke(newExtractorLink("Tokianime", "Tokianime [$lang]", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = quality
                        })
                        found = true
                    } else {
                        Log.w("Tokianime", "loadLinks: no se encontró URL .m3u8 en respuesta de API para match[$idx]")
                    }
                } catch (e: Exception) {
                    Log.e("Tokianime", "loadLinks: error al consultar API player source match[$idx]: ${e.message}")
                }
            }
            Log.i("Tokianime", "loadLinks: resultado final=${if (found) "OK" else "SIN_ENLACES"}")
            return found
        } catch (e: Exception) {
            Log.e("Tokianime", "loadLinks error: ${e.message}")
            return false
        }
    }

    private fun fixPoster(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}