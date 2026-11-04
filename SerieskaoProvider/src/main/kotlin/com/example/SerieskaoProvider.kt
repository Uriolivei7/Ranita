package com.example

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.*
import okhttp3.Interceptor
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class SerieskaoProvider : MainAPI() {
    override var mainUrl = "https://serieskao.top"
    override var name = "SeriesKao"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )

    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val TAG = "SeriesKao"

    companion object {
        var pluginContext: Context? = null

        private val fixHosts = mapOf(
            "https://hglink.to" to "https://streamwish.to",
            "https://swdyu.com" to "https://streamwish.to",
            "https://cybervynx.com" to "https://streamwish.to",
            "https://dumbalag.com" to "https://streamwish.to",
            "https://awish.pro" to "https://streamwish.to",
            "https://mivalyo.com" to "https://vidhidepro.com",
            "https://dinisglows.com" to "https://vidhidepro.com",
            "https://dhtpre.com" to "https://vidhidepro.com",
            "https://filemoon.link" to "https://filemoon.sx",
            "https://sblona.com" to "https://watchsb.com",
            "https://lulu.st" to "https://lulustream.com",
            "https://uqload.io" to "https://uqload.com",
            "https://do7go.com" to "https://dood.la",
            "https://doodstream.com" to "https://dood.la",
            "https://streamtape.com" to "https://streamtape.cc",
            "https://minochinos.com" to "https://vidhidepro.com",
        )
    }

    private fun fixHostsTitle(url: String): String {
        var result = url
        fixHosts.forEach { (old, new) ->
            result = result.replaceFirst(old, new)
        }
        return result
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cdnDomains = listOf("dramiyos", "acek-cdn", "vidhidepro", "vidhide", "premilkyway", "cyou", "cdnsync.cloud")
        val cdnPaths = listOf("/hls2/", "/hls3/", ".urlset/", "/stream/")
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val isCdn = cdnDomains.any { url.contains(it, ignoreCase = true) } ||
                cdnPaths.any { url.contains(it, ignoreCase = true) }
            if (!isCdn) return@Interceptor chain.proceed(request)

            val requestPath = request.url.encodedPath
            Log.d(TAG, "[intercept] CDN request: ${request.url.host}$requestPath")
            val newRequest = request.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .header("Referer", extractorLink.referer)
                .header("Origin", request.header("Origin") ?: "https://vidhidepro.com")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            try {
                val response = chain
                    .withConnectTimeout(30, TimeUnit.SECONDS)
                    .withReadTimeout(30, TimeUnit.SECONDS)
                    .proceed(newRequest)
                val contentType = response.header("content-type", "?")
                Log.d(TAG, "[intercept] CDN response: ${response.code} $contentType url=${request.url.host}$requestPath")
                if (requestPath.endsWith(".m3u8", ignoreCase = true)) {
                    val body = runCatching { response.peekBody(64 * 1024L).string() }.getOrDefault("")
                    val playlistLines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(8).map { line ->
                        if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("/")) {
                            val uri = runCatching { java.net.URL(java.net.URL(url), line).toURI() }.getOrNull()
                            "URI=${uri?.host ?: "?"}${uri?.rawPath ?: line.substringBefore('?')}"
                        } else {
                            line.take(100)
                        }
                    }.toList()
                    Log.d(TAG, "[intercept] HLS playlist path=$requestPath bytes=${body.length} lines=$playlistLines")
                }
                response
            } catch (t: Throwable) {
                Log.e(TAG, "[intercept] CDN network failure ${t::class.simpleName} host=${request.url.host} path=$requestPath")
                throw t
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/generos/dorama"),
        )

        Log.d(TAG, "getMainPage page=$page, categorías=${urls.size}")
        val homePageLists = urls.amap { (name, url) ->
            try {
                val doc = app.get(url).document
                val items = doc.select("article.card")
                Log.d(TAG, "getMainPage[$name] article.card=${items.size}")
                val homeItems = items.mapNotNull { element ->
                    try {
                        val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                        val link = linkEl.attr("href")
                        val title = element.selectFirst("h2.card__title")?.text()?.trim()
                        val img = element.selectFirst("figure.card__poster img")?.attr("src")
                        val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                        if (title == null || link.isBlank()) {
                            Log.w(TAG, "getMainPage[$name] saltado: title='$title' link='$link'")
                            return@mapNotNull null
                        }
                        newAnimeSearchResponse(title, fixUrl(link)) {
                            this.posterUrl = fixUrlNull(img)
                            this.type = when (typeBadge) {
                                "PEL" -> TvType.Movie
                                "ANI" -> TvType.Anime
                                else -> TvType.TvSeries
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "getMainPage[$name] error item: ${e.message}")
                        null
                    }
                }
                Log.d(TAG, "getMainPage[$name] items válidos=${homeItems.size}")
                HomePageList(name, homeItems)
            } catch (e: Exception) {
                Log.e(TAG, "getMainPage[$name] error url='$url': ${e.message}")
                HomePageList(name, emptyList())
            }
        }
        Log.d(TAG, "getMainPage total=${homePageLists.sumOf { it.list.size }}")
        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) {
            Log.w(TAG, "search query vacía")
            return emptyList()
        }
        val url = "$mainUrl/search?s=$query"
        Log.d(TAG, "search query='$query' url=$url")
        return try {
            val doc = app.get(url).document
            val items = doc.select("article.card")
            Log.d(TAG, "search article.card=${items.size}")
            items.mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                    val link = linkEl.attr("href")
                    val title = element.selectFirst("h2.card__title")?.text()?.trim()
                    val img = element.selectFirst("figure.card__poster img")?.attr("src")
                    val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                    if (title == null || link.isBlank()) return@mapNotNull null
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = fixUrlNull(img)
                        this.type = when (typeBadge) {
                            "PEL" -> TvType.Movie
                            "ANI" -> TvType.Anime
                            else -> TvType.TvSeries
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "search error item: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error '$url': ${e.message}")
            emptyList()
        }
    }

    private fun parseRecommendations(doc: org.jsoup.nodes.Document): List<SearchResponse>? {
        return try {
            val section = doc.selectFirst("section.section:has(h3.section__title)")
            if (section == null) return null
            section.select("article.card").mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                    val link = linkEl.attr("href")
                    val title = element.selectFirst("h4.card__title")?.text()?.trim()
                    val img = element.selectFirst("figure.card__poster img")?.attr("src")
                    val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                    if (title == null || link.isBlank()) return@mapNotNull null
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = fixUrlNull(img)
                        this.type = when (typeBadge) {
                            "PEL" -> TvType.Movie
                            "ANI" -> TvType.Anime
                            else -> TvType.TvSeries
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parseRecommendations error item: ${e.message}")
                    null
                }
            }.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "parseRecommendations error: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load url=$url")
        return try {
            val doc = app.get(url).document
            val isMovie = url.contains("/pelicula/")
            Log.d(TAG, "load isMovie=$isMovie")

            val title = doc.selectFirst("h1.detail-hero__title")?.text()?.trim() ?: ""
            Log.d(TAG, "load title='$title'")

            val year = doc.selectFirst("div.detail-hero__meta > span:not([class])")?.text()?.toIntOrNull()
                ?: Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            Log.d(TAG, "load year=$year")

            val genres = doc.select("a.detail-hero__genre").mapNotNull { it.text().trim().ifBlank { null } }
            Log.d(TAG, "load genres=${genres.joinToString()}")

            val ratingText = doc.selectFirst("span.detail-hero__rating")?.text()?.trim()
            val score = ratingText?.toDoubleOrNull()?.let { Score.from10(it) }
            Log.d(TAG, "load rating='$ratingText' score=$score")

            val poster = fixUrl(doc.selectFirst("figure.detail-hero__poster img")?.attr("src") ?: "")
            val description = doc.selectFirst("h2.detail-hero__desc")?.text()?.trim() ?: ""
            Log.d(TAG, "load poster='$poster' desc len=${description.length}")

            val recommendations = parseRecommendations(doc)
            Log.d(TAG, "load recommendations=${recommendations?.size}")

            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    this.score = score
                    this.recommendations = recommendations
                }
            } else {
                val episodes = mutableListOf<Episode>()

                val seasonSection = doc.selectFirst("section.seasons-section")
                if (seasonSection != null) {
                    val seasonContainers = seasonSection.select("div.episodes-list[id^='season-']")
                    Log.d(TAG, "load seasonContainers=${seasonContainers.size}")
                    for (container in seasonContainers) {
                        val seasonNum = container.id().removePrefix("season-").toIntOrNull() ?: continue
                        val epItems = container.select("a.episode-item")
                        Log.d(TAG, "load season $seasonNum episodios=${epItems.size}")
                        for (epItem in epItems) {
                            try {
                                val epUrl = fixUrl(epItem.attr("href") ?: "")
                                val epTitle = epItem.selectFirst("span.episode-item__title")?.text()?.trim() ?: ""
                                val epNum = epItem.selectFirst("span.episode-item__number")?.text()?.toIntOrNull()
                                if (epUrl.isBlank()) continue
                                episodes.add(newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.episode = epNum
                                    this.season = seasonNum
                                })
                            } catch (e: Exception) {
                                Log.e(TAG, "load error episodio season $seasonNum: ${e.message}")
                            }
                        }
                    }
                } else {

                    val epItems = doc.select("a.episode-item")
                    Log.d(TAG, "load flat episodios HTML=${epItems.size}")
                    for ((ei, element) in epItems.withIndex()) {
                        try {
                            val epUrl = fixUrl(element.attr("href") ?: "")
                            val epTitle = element.selectFirst("span.episode-item__title")?.text()?.trim() ?: ""
                            val epNum = element.selectFirst("span.episode-item__number")?.text()?.toIntOrNull() ?: (ei + 1)
                            if (epUrl.isBlank()) continue
                            episodes.add(newEpisode(epUrl) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = 1
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "load error episodio: ${e.message}")
                        }
                    }
                }
                Log.d(TAG, "load -> TvSeries, episodios=${episodes.size} temporadas=${episodes.distinctBy { it.season }.size}")
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    this.score = score
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error '$url': ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val emitTracked: (ExtractorLink) -> Unit = { link ->
            val number = emitted.incrementAndGet()
            val host = runCatching { java.net.URL(link.url).host }.getOrDefault("?")
            Log.d(TAG, "loadLinks EMIT #$number source=${link.source} name=${link.name} type=${link.type} q=${link.quality} host=$host")
            callback(link)
        }
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            Log.d(TAG, "loadLinks FIN emitidos=${emitted.get()} resultado=${if (cause == null) "ok" else "error:${cause.message}"}")
        }

        Log.d(TAG, "loadLinks START casting=$isCasting data='${data.take(180)}'")
        val pageUrl = data.substringBefore('#')
        val doc = try {
            val response = app.get(pageUrl)
            val pageHtml = response.text
            Log.d(TAG, "loadLinks ficha HTTP ${response.code} len=${pageHtml.length} url=${response.url}")
            Jsoup.parse(pageHtml, pageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error fetching page: ${e.message}")
            return@coroutineScope false
        }

        var iframeSrc = doc.selectFirst("iframe#player-iframe")?.attr("src")
        Log.d(TAG, "loadLinks iframe inicial=${!iframeSrc.isNullOrBlank()} hash=${data.substringAfter('#', "").take(30)}")
        if (iframeSrc.isNullOrBlank()) {
            val episodeHash = Regex("""#s(\d+)-e(\d+)$""").find(data)
            val pageRoute = Regex("""/(serie|anime)/([^/?#]+)""").find(pageUrl)
            if (episodeHash != null && pageRoute != null) {
                val type = pageRoute.groupValues[1]
                val slug = pageRoute.groupValues[2]
                val season = episodeHash.groupValues[1]
                val episode = episodeHash.groupValues[2]
                val apiUrl = "$mainUrl/api/episodio/$type/$slug/$season/$episode"
                Log.d(TAG, "loadLinks router request type=$type slug=$slug season=$season episode=$episode")
                try {
                    val apiResp = app.get(
                        apiUrl,
                        headers = mapOf("Accept" to "application/json"),
                        referer = pageUrl,
                    )
                    val apiBody = apiResp.text
                    val episodeData = tryParseJson<EpisodeRouterResponse>(apiBody)
                    iframeSrc = episodeData?.html?.let { Jsoup.parse(it) }
                        ?.selectFirst("iframe#player-iframe")?.attr("src")
                    Log.d(TAG, "loadLinks episode API HTTP ${apiResp.code} len=${apiBody.length} json=${episodeData != null} success=${episodeData?.success} html=${episodeData?.html?.length ?: 0} iframe=${!iframeSrc.isNullOrBlank()}")
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks episode API error: ${e.message}")
                }
            } else {
                Log.w(TAG, "loadLinks no router params: episodeHash=${episodeHash?.value} pageRoute=${pageRoute?.value}; iframe absent")
            }
        }
        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks no se encontró iframe#player-iframe ni en HTML inicial ni en API de episodio")
            return@coroutineScope false
        }

        val playerUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
        Log.d(TAG, "loadLinks playerUrl=$playerUrl")

        if (!playerUrl.contains(mainUrl.removePrefix("https://").removePrefix("http://"))) {
            Log.d(TAG, "loadLinks embed externo: $playerUrl")
            when {
                playerUrl.contains("embed69.org") -> {
                    Log.d(TAG, "loadLinks embed69 externo -> extractKaoEmbed69")
                    extractKaoEmbed69(playerUrl, data, subtitleCallback) { link ->
                        emitTracked(link)
                    }
                }

                else -> {
                    Log.d(TAG, "loadLinks embed externo embeds directo -> loadExtractor")
                    when {
                        playerUrl.contains("xupalace.org/video/") -> {
                            Log.d(TAG, "loadLinks xupalace video -> extrayendo go_to_playerVast")
                            try {
                                val xDoc = app.get(playerUrl, referer = data).document
                                val links = xDoc.select("*[onclick*='go_to_playerVast']").mapNotNull { el ->
                                    Regex("""go_to_playerVast\('([^']+)'""").find(el.attr("onclick"))?.groupValues?.get(1)
                                }
                                if (links.isEmpty()) {
                                    Log.w(TAG, "loadLinks sin go_to_playerVast en xupalace")
                                    loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
                                } else {
                                    Log.d(TAG, "loadLinks xupalace links=${links.size}: $links")
                                    links.amap { link ->
                                        loadExtractor(fixHostsTitle(link), playerUrl, subtitleCallback, emitTracked)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "loadLinks xupalace error: ${e.message}")
                                loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
                            }
                        }

                        playerUrl.contains("xupalace.org/uqlink.php") || playerUrl.contains("xupalace.org/ggtz") -> {
                            Log.d(TAG, "loadLinks xupalace uqlink -> siguiendo iframe")
                            try {
                                val xDoc = app.get(playerUrl, referer = data).document
                                val iframeSrc = xDoc.selectFirst("iframe")?.attr("src")
                                if (iframeSrc != null) {
                                    loadExtractor(fixHostsTitle(fixUrl(iframeSrc)), playerUrl, subtitleCallback, emitTracked)
                                } else {
                                    loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "loadLinks xupalace uqlink error: ${e.message}")
                                loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
                            }
                        }

                        else -> {
                            Log.d(TAG, "loadLinks embed directo -> loadExtractor")
                            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
                        }
                    }
                }
            }
            return@coroutineScope true
        }

        var playerHtml = try {
            val response = app.get(playerUrl, referer = data)
            Log.d(TAG, "loadLinks player HTTP ${response.code} len=${response.text.length} host=${runCatching { java.net.URL(playerUrl).host }.getOrDefault("?")}")
            response.text
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error fetching playerUrl: ${e.message}")
            return@coroutineScope false
        }

        fun extractDataLink(html: String): String? =
            Regex("""dataLink\s*=\s*(\[.*?\])\s*;""").find(html)?.groupValues?.get(1)
                ?: Regex("""dataLink\s*=\s*([^;]+);""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)?.let { resolveDataLinkExpr(it) }

        fun logPlayerShape(label: String, html: String) {
            val doc = org.jsoup.Jsoup.parse(html, playerUrl)
            val inlineScripts = doc.select("script:not([src])").map { it.data().ifBlank { it.html() } }
                .filter { it.isNotBlank() }
            Log.d(TAG, "loadLinks $label title='${doc.title().take(120)}' body='${doc.body()?.text()?.take(450)}'")
            Log.d(TAG, "loadLinks $label scripts=${doc.select("script").size} inline=${inlineScripts.size} iframes=${doc.select("iframe").size} dataLink=${html.contains("dataLink")} pow=${html.contains("POW_CHALLENGE")}/${html.contains("POW_SALT")} serverWords=${listOf("dood", "vidhide", "streamwish").filter { html.contains(it, true) }}")
            inlineScripts.take(6).forEachIndexed { index, script ->
                Log.d(TAG, "loadLinks $label inlineScript#$index len=${script.length} start='${script.take(220)}'")
            }
        }

        fun findPlayerServerUrls(html: String): List<String> {
            val normalized = html.replace("\\/", "/").replace("&amp;", "&")
            val knownHosts = listOf(
                "dood", "do7go", "vidhide", "morencius", "minochinos", "mivalyo", "dinisglows", "dhtpre",
                "streamwish", "hglink", "swdyu", "cybervynx", "dumbalag", "awish"
            )
            return Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+""").findAll(normalized)
                .mapNotNull { match ->
                    val candidate = match.value.trimEnd(')', ',', ';', '.')
                    val url = if (candidate.startsWith("//")) "https:$candidate" else candidate
                    val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
                    url.takeIf { host != "serieskao.top" && host != "embed69.org" && knownHosts.any(host::contains) }
                }
                .distinct()
                .toList()
        }

        var rawMatch = extractDataLink(playerHtml)
        if (rawMatch == null || !playerHtml.contains("POW_CHALLENGE") || !playerHtml.contains("POW_SALT")) {
            Log.d(TAG, "loadLinks player sin dataLink/PoW; intentando WebView: $playerUrl")
            val renderedHtml = renderViaWebView(
                pageUrl = playerUrl,
                referer = data,
                waitMs = 12000L,
                readyJs = if (playerHtml.contains("EMBED69", true)) {
                    "h.includes('dood')&&h.includes('vidhide')&&h.includes('streamwish')"
                } else {
                    "h.includes('dataLink')&&h.includes('POW_CHALLENGE')&&h.includes('POW_SALT')"
                },
                renderInIframe = playerHtml.contains("EMBED69", true)
            )
            if (!renderedHtml.isNullOrBlank()) {
                val renderedMatch = extractDataLink(renderedHtml)
                Log.d(TAG, "loadLinks player WebView len=${renderedHtml.length} dataLink=${renderedMatch != null} pow=${renderedHtml.contains("POW_CHALLENGE") && renderedHtml.contains("POW_SALT")}")
                logPlayerShape("player WebView", renderedHtml)
                if (renderedMatch != null) {
                    playerHtml = renderedHtml
                    rawMatch = renderedMatch
                } else if (playerHtml.contains("EMBED69", true)) {
                    val serverUrls = findPlayerServerUrls(renderedHtml)
                    Log.d(TAG, "loadLinks Embed69 servidores WebView=${serverUrls.size}: $serverUrls")
                    if (serverUrls.isNotEmpty()) {
                        serverUrls.amap { serverUrl ->
                            loadKaoSourceExtractor(
                                "Embed69",
                                fixHostsLinksPreservingVidHide(serverUrl),
                                playerUrl,
                                subtitleCallback,
                                emitTracked,
                            )
                        }
                        return@coroutineScope true
                    }
                }
            } else {
                Log.w(TAG, "loadLinks player WebView sin HTML")
            }
        }
        if (rawMatch == null) {
            logPlayerShape("player estático", playerHtml)
            Log.e(TAG, "loadLinks sin dataLink tras HTTP/WebView (htmlLen=${playerHtml.length}) -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
            return@coroutineScope true
        }

        val jsonStr = rawMatch
        Log.d(TAG, "loadLinks dataLink JSON len=${jsonStr.length}")
        val items = tryParseJson<List<DataLinkEntry>>(jsonStr)
        if (items == null) {
            Log.e(TAG, "loadLinks error parseando dataLink JSON: ${jsonStr.take(200)}")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks items=${items.size} resumen=${items.map { it.videoLanguage to (it.sortedEmbeds?.size ?: 0) }}")

        val embedChallenge = Regex("""POW_CHALLENGE\s*=\s*'([^']+)'""").find(playerHtml)?.groupValues?.get(1)
        val embedSalt = Regex("""POW_SALT\s*=\s*'([^']+)'""").find(playerHtml)?.groupValues?.get(1)
        if (embedChallenge == null || embedSalt == null) {
            Log.e(TAG, "loadLinks sin POW_CHALLENGE/SALT -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks PoW challenge presente=${!embedChallenge.isNullOrBlank()} len=${embedChallenge.length} salt presente=${!embedSalt.isNullOrBlank()} len=${embedSalt.length}")

        val langMap = mapOf("LAT" to "LATINO", "ESP" to "CASTELLANO", "SUB" to "SUBTITULADO")
        Log.d(TAG, "loadLinks resolviendo PoW...")
        val aesKey = try {

            withTimeout(30000L) {
                withContext(Dispatchers.Default) { solveEmbed69PoW(embedChallenge, embedSalt) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks PoW error/timeout (${e.message}) -> loadExtractor directo")
            null
        }
        if (aesKey == null) {
            Log.e(TAG, "loadLinks PoW failed -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, emitTracked)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks PoW solved, items=${items.size}")

        var decryptedCount = 0
        items.forEach { item ->
            val langTag = langMap[item.videoLanguage] ?: item.videoLanguage ?: "??"
            Log.d(TAG, "loadLinks item idioma=${item.videoLanguage} mapped=$langTag embeds=${item.sortedEmbeds?.size ?: 0}")
            item.sortedEmbeds?.forEach { embed ->
                val encrypted = embed.link ?: return@forEach
                val decrypted = decryptAESLocal(encrypted, aesKey)
                if (decrypted != null) {
                    decryptedCount++
                    Log.d(TAG, "loadLinks decrypted ${embed.servername}: $decrypted")
                    loadKaoSourceExtractor(langTag, fixHostsLinksPreservingVidHide(decrypted), playerUrl, subtitleCallback) { link ->
                        emitTracked(link)
                    }
                } else {
                    Log.w(TAG, "loadLinks decrypt failed server=${embed.servername} lang=${item.videoLanguage}")
                }
            }
        }
        Log.d(TAG, "loadLinks decrypted total=$decryptedCount")
        return@coroutineScope true
    }

    private fun resolveDataLinkExpr(rawExpression: String?): String? {
        if (rawExpression.isNullOrBlank()) return null
        var expr = rawExpression.trim().trimEnd(';')
        fun String.removeOuterCall(prefix: String): String? {
            if (!startsWith(prefix, ignoreCase = true) || !endsWith(')')) return null
            val start = indexOf('(')
            val end = lastIndexOf(')')
            if (start == -1 || end == -1 || end <= start) return null
            return substring(start + 1, end).trim()
        }
        fun String.trimMatchingQuotes(): String =
            if ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\''))) {
                substring(1, length - 1)
            } else this
        while (true) {
            val next = expr.removeOuterCall("window.JSON.parse")
                ?: expr.removeOuterCall("JSON.parse")
                ?: expr.removeOuterCall("window.decodeURIComponent")?.trimMatchingQuotes()?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
                }
                ?: expr.removeOuterCall("decodeURIComponent")?.trimMatchingQuotes()?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
                }
                ?: expr.removeOuterCall("window.atob")?.trimMatchingQuotes()?.let {
                    runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull()
                }
                ?: expr.removeOuterCall("atob")?.trimMatchingQuotes()?.let {
                    runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull()
                }
                ?: break
            expr = next
        }
        return expr.trim().trimMatchingQuotes().takeIf { it.isNotBlank() }
    }

    data class DataLinkEntry(
        @JsonProperty("file_id") val fileId: String? = null,
        @JsonProperty("video_language") val videoLanguage: String? = null,
        @JsonProperty("sortedEmbeds") val sortedEmbeds: List<SortedEmbed>? = emptyList(),
    )

    data class SortedEmbed(
        @JsonProperty("servername") val servername: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class EpisodeRouterResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("html") val html: String? = null,
    )
}
