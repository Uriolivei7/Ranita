package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
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

    private fun apiHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Referer" to referer,
        "Origin" to mainUrl,
        "X-Requested-With" to "XMLHttpRequest",
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
            val html = app.get(url, headers = headers).text
            val doc = Jsoup.parse(html)

            if (request.data == "/") {
                val items = mutableListOf<SearchResponse>()
                val links = doc.select("section a[href^='/anime/']")
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
                if (items.isEmpty()) {
                    Log.w("Tokianime", "getMainPage(/): 0 items, revisa el selector CSS 'section a[href^=/anime/]'")
                    return null
                }
                return newHomePageResponse(listOf(HomePageList("Tendencia", items.distinctBy { it.url }.take(50))), false)
            }

            if (request.data == "#ultimos") {
                val items = mutableListOf<SearchResponse>()
                val links = doc.select("a[href^='/watch/']")
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
                if (items.isEmpty()) {
                    Log.w("Tokianime", "getMainPage(#ultimos): 0 items, revisa selector 'a[href^=/watch/]'")
                    return null
                }
                return newHomePageResponse(listOf(HomePageList("Últimos Episodios", items.take(50))), false)
            }

            val items = mutableListOf<SearchResponse>()
            val links = doc.select("a[href^='/anime/']")
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
                Log.i("Tokianime", "search: query='$query' page=$page")
                val resp = app.get(apiUrl, headers = headers).text

                if (resp.contains("\"items\":[]") || resp.contains("\"items\": []")) {
                    hasMore = false
                } else {
                    val itemMatches = Regex(""""slug":"([^"]+)"[^}]*?"title":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(resp).toList()
                    val posters = Regex(""""coverImage":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(resp).toList()
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

            val episodes = mutableListOf<Episode>()
            val seenSlugs = mutableSetOf<String>()

            suspend fun fetchEpisodes(epSlug: String, seasonNum: Int): List<Episode> {
                val result = mutableListOf<Episode>()
                try {
                    val apiUrl = "$mainUrl/api/anime/$epSlug/episodes"
                    val apiResp = app.get(apiUrl, headers = headers).text
                    val withVideoMatch = Regex(""""withVideo":\[([^\]]+)\]""").find(apiResp)
                    if (withVideoMatch != null) {
                        val epNums = Regex("""\d+""").findAll(withVideoMatch.groupValues[1])
                            .map { it.value.toIntOrNull() }.filterNotNull().toList()
                        for (epNum in epNums) {
                            val metaRegex = Regex(""""$epNum":\{"title":"([^"]*)","overview":"([^"]*)"""")
                            val metaMatch = metaRegex.find(apiResp)
                            val epTitle = metaMatch?.groupValues?.get(1)
                            val epOverview = metaMatch?.groupValues?.get(2)
                            val thumbRegex = Regex(""""thumbs":\{"[^}]*?"$epNum":"([^"]+)"""")
                            var epThumbnail = thumbRegex.find(apiResp)?.groupValues?.get(1)
                            if (epThumbnail.isNullOrBlank()) {
                                val fallbackThumb = Regex(""""$epNum":"(https?://[^"]+)"""").find(apiResp)?.groupValues?.get(1)
                                if (fallbackThumb != null) epThumbnail = fallbackThumb
                            }
                            result.add(newEpisode("$mainUrl/watch/$epSlug/$epNum") {
                                this.name = if (epTitle.isNullOrBlank()) "Episodio $epNum" else epTitle
                                this.episode = epNum
                                this.season = seasonNum
                                this.description = epOverview
                                if (!epThumbnail.isNullOrBlank()) this.posterUrl = epThumbnail
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
                        it.order
                    })
                    for ((seasonNum, entry) in entries.withIndex()) {
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
                }
            }

            if (episodes.isEmpty()) {
                val epItems = doc.select("a[href^='/watch/$slug/']")
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
                val totalEps = Regex("""(\d+)\s*eps""", RegexOption.IGNORE_CASE).find(html)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*(\d+)""").find(html)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*de\s*(\d+)""").find(html)?.groupValues?.get(2)?.toIntOrNull()
                if (totalEps != null && totalEps in 1..1000) {
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

            val rscChunks = Regex("""self\.__next_f\.push\(\[.*?""").findAll(html).toList()

            val normalized = html.replace("\\\"", "\"")
            val servers = parseRankedServers(normalized)
            Log.i("Tokianime", "loadLinks: rankedServers parsed = ${servers.size}")
            if (servers.isNotEmpty()) {
                var found = false
                val seenSids = mutableSetOf<String>()
                for (server in servers) {
                    val langRaw = server.first
                    val qualityStr = server.second
                    val srcRaw = server.third
                    val lang = langRaw.uppercase()
                    val quality = qualityStr.takeWhile { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                    val cleanSrc = srcRaw.replace("""\u0026""", "&")
                    val apiUrl = "$mainUrl$cleanSrc"
                    val sid = Regex("sid=([^&]+)").find(cleanSrc)?.groupValues?.get(1) ?: ""
                    if (sid.isNotEmpty() && !seenSids.add(sid)) continue
                    try {
                        val respCall = app.get(apiUrl, headers = apiHeaders(watchUrl))
                        val headBuf = ByteArray(10240)
                        val headRead = respCall.body.byteStream().use { s -> s.read(headBuf) }
                        val headStr = if (headRead > 0) String(headBuf, 0, headRead) else ""
                        val langLabel = labelFor(lang)
                        Log.i("Tokianime", "loadLinks: [$langLabel] code=${respCall.code} len=$headRead head='${headStr.take(80)}'")
                        if (headStr.trimStart().startsWith("#EXTM3U")) {
                            callback.invoke(newExtractorLink("Tokianime", "Tokianime [$langLabel]", apiUrl, ExtractorLinkType.M3U8) {
                                this.referer = mainUrl; this.quality = quality
                            })
                            found = true
                            Log.i("Tokianime", "loadLinks: M3U8 [$langLabel] q=$quality")
                        } else if (headStr.contains("ftyp")) {
                            val iframeOk = tryFallbackIframe(apiUrl, langLabel, quality, callback, mainUrl)
                            if (!iframeOk) {
                                callback.invoke(newExtractorLink("Tokianime", "Tokianime [$langLabel]", apiUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl; this.quality = quality
                                })
                                found = true
                                Log.i("Tokianime", "loadLinks: MP4 [$langLabel] q=$quality")
                            } else {
                                found = true
                            }
                        } else if (headStr.contains("<!DOCTYPE html", ignoreCase = true) || headStr.contains("<html", ignoreCase = true) || headStr.isBlank()) {
                            tryFallbackIframe(apiUrl, langLabel, quality, callback, mainUrl)
                        } else {
                            Log.w("Tokianime", "loadLinks: respuesta no reconocida para '$lang': '${headStr.take(100)}'")
                        }
                    } catch (e: Exception) {
                        Log.e("Tokianime", "loadLinks: error al consultar API server: ${e.message}")
                    }
                }
                Log.i("Tokianime", "loadLinks: resultado final=${if (found) "OK" else "SIN_ENLACES"}")
                return found
            }

            val playRegex = Regex(""""lang":"([^"]+)".*?"quality":"([^"]+)".*?"play":\{"src":"(/api/player/source[^"]+)""")
            val matches = playRegex.findAll(normalized).toList()

            val allPlayUrls = Regex("""/api/player/source[^"]*""").findAll(normalized).toList()
                .map { it.value.replace("""\u0026""", "&") }
                .filter { it.contains("mode=play") }
                .distinct()

            if (matches.isEmpty()) {
                Log.w("Tokianime", "loadLinks: 0 matches. Buscando '/api/player/source' en HTML crudo...")
                val rawMatches = Regex("""/api/player/source[^"\\]*""").findAll(html).toList()
                val normMatches = Regex("""/api/player/source[^"]*""").findAll(normalized).toList()
                Log.i("Tokianime", "loadLinks: raw=${rawMatches.size} norm=${normMatches.size}")

                val rscFull = rscChunks.joinToString("") { it.value }
                    .replace("\\\"", "\"")
                    .replace("\\n", "")
                    .replace("\\t", "")
                val rankedMatch = Regex(""""rankedServers""").find(rscFull)
                if (rankedMatch != null) {

                    val srcRegex = Regex(""""src":"(/api/player/source[^"]+)""")
                    val srcs = srcRegex.findAll(rscFull).map { it.groupValues[1] }.toList()
                    for (src in srcs) {
                        try {
                            val apiUrl = "$mainUrl$src"
                            val respCall = app.get(apiUrl, headers = apiHeaders(watchUrl))
                            val headBuf = ByteArray(100)
                            val headRead = respCall.body.byteStream().use { s -> s.read(headBuf) }
                            val headStr = if (headRead > 0) String(headBuf, 0, headRead) else ""
                            if (headStr.trimStart().startsWith("#EXTM3U") || headStr.contains("ftyp")) {
                                val linkType = if (headStr.trimStart().startsWith("#EXTM3U")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                Log.i("Tokianime", "loadLinks: enlace válido via RSC fallback! type=$linkType url='$apiUrl'")
                                callback.invoke(newExtractorLink("Tokianime", "Tokianime", apiUrl, linkType) {
                                    this.referer = mainUrl
                                })
                                return true
                            }
                        } catch (e: Exception) {
                            Log.e("Tokianime", "loadLinks: RSC fallback error: ${e.message}")
                        }
                    }
                }

                Log.i("Tokianime", "loadLinks: probando normMatches como fallback final...")
                val playUrls = normMatches.map { it.value }.distinct().filter { it.contains("mode=play") }
                for (src in playUrls) {
                    try {
                        val apiUrl = "$mainUrl${src.replace("""\u0026""", "&")}"
                        try {

                            val call = app.get(apiUrl, headers = apiHeaders(watchUrl))
                            val headBuf = ByteArray(100)
                            val headRead = call.body.byteStream().use { s -> s.read(headBuf) }
                            val headStr = if (headRead > 0) String(headBuf, 0, headRead) else ""
                            if (headStr.trimStart().startsWith("#EXTM3U") || headStr.contains("ftyp")) {
                                val linkType = if (headStr.trimStart().startsWith("#EXTM3U")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                Log.i("Tokianime", "loadLinks: enlace válido via normMatch! type=$linkType url='$apiUrl'")
                                callback.invoke(newExtractorLink("Tokianime", "Tokianime", apiUrl, linkType) {
                                    this.referer = mainUrl
                                })
                                return true
                            }
                        } catch (e: Exception) {
                            Log.e("Tokianime", "loadLinks: normMatch verification error: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.e("Tokianime", "loadLinks: normMatch error: ${e.message}")
                    }
                }
                return false
            }

            var found = false

            val allLangs = matches.map { it.groupValues[1].uppercase() }.toSet()
            val hasEs = allLangs.any { it == "ES" }
            val hasLat = allLangs.contains("LAT")

            for ((idx, match) in matches.withIndex()) {
                val lang = match.groupValues[1]
                val qualityStr = match.groupValues[2]
                val playSrc = match.groupValues[3]
                val quality = qualityStr.takeWhile { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value

                val cleanSrc = playSrc.replace("""\u0026""", "&")
                    val apiUrl = "$mainUrl$cleanSrc"
                    try {
                        val respCall = app.get(apiUrl, headers = apiHeaders(watchUrl))
                        val headBuf = ByteArray(10240)
                        val headRead = respCall.body.byteStream().use { s -> s.read(headBuf) }
                        val headStr = if (headRead > 0) String(headBuf, 0, headRead) else ""
                        val langLabel = labelFor(lang)
                        Log.i("Tokianime", "loadLinks: match[$idx] code=${respCall.code} len=$headRead")

                        if (headStr.trimStart().startsWith("#EXTM3U")) {
                            callback.invoke(newExtractorLink("Tokianime", "Tokianime [$langLabel]", apiUrl, ExtractorLinkType.M3U8) {
                                this.referer = mainUrl; this.quality = quality
                            })
                            found = true
                            Log.i("Tokianime", "loadLinks: enlace M3U8 agregado [$langLabel] q=$quality")
                        } else if (headStr.contains("ftyp")) {

                            val iframeOk = tryFallbackIframe(apiUrl, langLabel, quality, callback, mainUrl)
                            if (!iframeOk) {
                                callback.invoke(newExtractorLink("Tokianime", "Tokianime [$langLabel]", apiUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl; this.quality = quality
                                })
                                found = true
                            } else {
                                found = true
                            }
                        } else if (headStr.contains("<!DOCTYPE html", ignoreCase = true) || headStr.contains("<html", ignoreCase = true)) {
                            tryFallbackIframe(apiUrl, langLabel, quality, callback, mainUrl)
                        } else {
                            Log.w("Tokianime", "loadLinks: respuesta no reconocida para match[$idx]: '${headStr.take(100)}'")
                        }
                    } catch (e: Exception) {
                        Log.e("Tokianime", "loadLinks: error al consultar API player source match[$idx]: ${e.message}")
                    }
            }

            val processedSids = matches.map { m ->
                Regex("sid=([^&]+)").find(m.groupValues[3].replace("""\u0026""", "&"))?.groupValues?.get(1) ?: ""
            }.filter { it.isNotEmpty() }.toSet()
            for (extraSrc in allPlayUrls) {
                val extraSid = Regex("sid=([^&]+)").find(extraSrc)?.groupValues?.get(1) ?: extraSrc
                if (extraSid in processedSids) continue

                var extraLang = "SRV"
                var extraQuality = Qualities.Unknown.value
                val sidIdx = normalized.indexOf(extraSid)
                if (sidIdx >= 0) {
                    val before = normalized.substring(maxOf(0, sidIdx - 400), sidIdx)
                    val langNear = Regex(""""lang"\s*:\s*"([^"]+)""").findAll(before).toList()
                    val qualNear = Regex(""""quality"\s*:\s*"([^"]+)""").findAll(before).toList()
                    if (langNear.isNotEmpty()) extraLang = labelFor(langNear.last().groupValues[1])
                    if (qualNear.isNotEmpty()) {
                        extraQuality = qualNear.last().groupValues[1]
                            .takeWhile { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                    }
                }
                Log.i("Tokianime", "loadLinks: extra server sid=$extraSid lang=$extraLang q=$extraQuality")
                val extraUrl = "$mainUrl$extraSrc"
                try {
                    val extraCall = app.get(extraUrl, headers = apiHeaders(watchUrl))
                    val extraBuf = ByteArray(10240)
                    val extraRead = extraCall.body.byteStream().use { s -> s.read(extraBuf) }
                    val extraHead = if (extraRead > 0) String(extraBuf, 0, extraRead) else ""
                    if (extraHead.trimStart().startsWith("#EXTM3U")) {
                        callback.invoke(newExtractorLink("Tokianime", "Tokianime [$extraLang]", extraUrl, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl; this.quality = extraQuality
                        })
                        found = true
                        Log.i("Tokianime", "loadLinks: extra server M3U8 [$extraLang] q=$extraQuality")
                    } else if (extraHead.contains("ftyp")) {
                        val iframeOk = tryFallbackIframe(extraUrl, extraLang, extraQuality, callback, mainUrl)
                        if (!iframeOk) {
                            callback.invoke(newExtractorLink("Tokianime", "Tokianime [$extraLang]", extraUrl, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl; this.quality = extraQuality
                            })
                            found = true
                        } else { found = true }
                    }
                } catch (e: Exception) {
                    Log.e("Tokianime", "loadLinks: extra server error: ${e.message}")
                }
            }
            Log.i("Tokianime", "loadLinks: resultado final=${if (found) "OK" else "SIN_ENLACES"}")
            return found
        } catch (e: Exception) {
            Log.e("Tokianime", "loadLinks error: ${e.message}")
            return false
        }
    }

    private fun labelFor(raw: String): String {
        return when (raw.uppercase()) {
            "SUB" -> "SUB"
            "LAT" -> "LAT"
            "ES" -> "ES"
            "CAST" -> "CAST"
            else -> raw.uppercase()
        }
    }

    private fun parseRankedServers(html: String): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val idx = html.indexOf("\"rankedServers\":")
        if (idx < 0) return result
        val arrStart = html.indexOf('[', idx)
        if (arrStart < 0) return result
        var depth = 0
        var end = -1
        var inString = false
        var i = arrStart
        while (i < html.length) {
            val ch = html[i]
            if (inString) {
                if (ch == '\\') {
                    i += 2
                    continue
                }
                if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
            i++
        }
        if (end < 0) return result
        val arrayJson = html.substring(arrStart, end + 1)
        return try {
            val array = JSONArray(arrayJson)
            for (j in 0 until array.length()) {
                val obj = array.optJSONObject(j) ?: continue
                val lang = obj.optString("lang", "")
                val quality = obj.optString("quality", "")
                val play = obj.optJSONObject("play")
                val src = play?.optString("src", "") ?: ""
                if (src.isNotEmpty() && src.contains("mode=play")) {
                    result.add(Triple(lang, quality, src))
                }
            }
            result
        } catch (e: Exception) {
            Log.e("Tokianime", "loadLinks: parseRankedServers error: ${e.message}")
            result
        }
    }

    private suspend fun tryFallbackIframe(apiUrl: String, langLabel: String, quality: Int, callback: (ExtractorLink) -> Unit, mainUrl: String): Boolean {
        val iframeUrl = apiUrl.replace("mode=play", "mode=iframe")
        Log.i("Tokianime", "loadLinks: probando iframe fallback: $iframeUrl")
        return try {
            val iframeCall = app.get(iframeUrl, headers = apiHeaders(iframeUrl))
            val iframeBuf = ByteArray(10240)
            val iframeRead = iframeCall.body.byteStream().use { s -> s.read(iframeBuf) }
            val iframeStr = if (iframeRead > 0) String(iframeBuf, 0, iframeRead) else ""
            Log.i("Tokianime", "loadLinks: iframe code=${iframeCall.code} len=$iframeRead head='${iframeStr.take(120)}'")
            val m3u8InIframe = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(iframeStr)?.groupValues?.get(1)
            if (m3u8InIframe != null) {
                callback.invoke(newExtractorLink("Tokianime", "Tokianime [$langLabel]", m3u8InIframe, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl; this.quality = quality
                })
                Log.i("Tokianime", "loadLinks: M3U8 extraído de iframe [$langLabel]")
                true
            } else {
                Log.w("Tokianime", "loadLinks: iframe sin M3U8 para $langLabel")
                false
            }
        } catch (e: Exception) {
            Log.w("Tokianime", "loadLinks: error en iframe fallback: ${e.message}")
            false
        }
    }

    private fun fixPoster(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}