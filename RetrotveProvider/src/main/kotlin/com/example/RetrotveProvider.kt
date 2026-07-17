package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class RetrotveProvider : MainAPI() {
    override var mainUrl = "https://retrotve.com"
    override var name = "RetroTVE"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "/category/animacion/?tr_post_type=2" to "Series Animadas",
        "/category/liveaction/" to "Series Live Action",
        "/category/animacion/?tr_post_type=1" to "Películas Animadas",
        "/peliculas/" to "Películas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data.removeSuffix("/")}/page/$page/"
        }
        Log.d("RetrotveProvider", "getMainPage: URL = $url")
        
        val document = app.get(url).document
        val home = ArrayList<SearchResponse>()
        val seenLinks = mutableSetOf<String>()
        
        document.select(".TpRwCont .MovieList > li, .TpRwCont .MovieList .TPostMv, section .MovieList > li").forEach { element ->
            val linkElement = element.selectFirst("a[href*='/serie/'], a[href*='/pelicula/']")
            val link = linkElement?.attr("href") ?: return@forEach
            if (seenLinks.contains(link)) return@forEach
            seenLinks.add(link)
            
            val title = element.selectFirst(".Title")?.text() ?: element.selectFirst("h3")?.text() ?: return@forEach
            val poster = fixPosterUrl(element.selectFirst(".Image img")?.attr("src")
                ?: element.selectFirst(".Image img")?.attr("data-src"))
            
            if (title.isNotEmpty() && title.length > 2) {
                val tvType = if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
                home.add(
                    newTvSeriesSearchResponse(title, link, tvType) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        
        Log.d("RetrotveProvider", "getMainPage: found ${home.size} items")
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RetrotveProvider", "search: query = $query")
        val document = app.get("$mainUrl/?s=$query").document
        val results = ArrayList<SearchResponse>()
        val seenLinks = mutableSetOf<String>()
        
        document.select("section .MovieList > li, section .TPostMv, .TpRwCont main .MovieList > li").forEach { element ->
            val link = element.selectFirst("a[href*='/serie/'], a[href*='/pelicula/']")?.attr("href") ?: return@forEach
            
            if (!link.contains("/serie/") && !link.contains("/pelicula/")) return@forEach
            if (seenLinks.contains(link)) return@forEach
            seenLinks.add(link)
            
            val title = element.selectFirst(".Title")?.text() ?: element.selectFirst("h3")?.text() ?: return@forEach
            val poster = fixPosterUrl(element.selectFirst(".Image img")?.attr("src")
                ?: element.selectFirst(".Image img")?.attr("data-src"))
            
            if (title.isEmpty() || title.length < 3) return@forEach
            
            val tvType = if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
            results.add(
                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            )
        }
        Log.d("RetrotveProvider", "search: ${results.size} resultados")
        return results
    }

    private fun extractEpisodePoster(row: Element): String? {
        row.selectFirst(".MvTbImg img")?.let { img ->
            val src = img.attr("src")
            if (src.isNotBlank()) return if (src.startsWith("//")) "https:$src" else src
        }
        row.selectFirst(".MvTbImg span.cnv")?.let { span ->
            val encoded = span.text()
            val decoded = encoded
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
            try {
                val doc = Jsoup.parse(decoded)
                val src = doc.selectFirst("img")?.attr("src")
                if (!src.isNullOrBlank()) return if (src.startsWith("//")) "https:$src" else src
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("RetrotveProvider", "load: url = $url")
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.Title, h1")?.text() ?: return null
        val poster = fixPosterUrl(document.selectFirst(".TPost.Single .Image img")?.attr("src")
            ?: document.selectFirst(".TPost.Single .Image img")?.attr("data-src")
            ?: document.selectFirst(".TPostBg img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst(".Description p")?.text()
        
        val year = document.selectFirst(".Year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        val isMovie = url.contains("/pelicula/")
        
        val episodes = ArrayList<Episode>()
        
        if (!isMovie) {
            document.select(".Wdgt.AABox").forEach { seasonBox ->
                val seasonTitle = seasonBox.selectFirst(".Title.AA-Season span")?.text()
                    ?: seasonBox.selectFirst(".Title")?.text()
                val seasonNum = seasonTitle?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                
                Log.d("RetrotveProvider", "Found season: $seasonNum")
                
                seasonBox.select(".TPTblCn tbody tr").forEach { row ->
                    val episodeUrl = row.selectFirst("a[href*='/seriestv/']")?.attr("href") ?: return@forEach
                    val episodeName = row.selectFirst(".MvTbTtl a")?.text() ?: "Episodio"
                    val episodeNum = row.selectFirst(".Num")?.text()?.toIntOrNull()
                    val posterUrl = extractEpisodePoster(row)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.episode = episodeNum ?: episodes.size + 1
                        this.season = seasonNum
                        this.posterUrl = posterUrl
                    })
                }
            }
            
            if (episodes.isEmpty()) {
                document.select(".TPTblCn tbody tr").forEach { row ->
                    val episodeUrl = row.selectFirst("a[href*='/seriestv/']")?.attr("href") ?: return@forEach
                    val episodeName = row.selectFirst(".MvTbTtl a")?.text() ?: "Episodio"
                    val episodeNum = row.selectFirst(".Num")?.text()?.toIntOrNull()
                    val posterUrl = extractEpisodePoster(row)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.episode = episodeNum ?: episodes.size + 1
                        this.posterUrl = posterUrl
                    })
                }
            }
        }
        
        val recommendations = document.select(".TpSbList li, .Relacionados li").mapNotNull { element ->
            val recUrl = element.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            if (!recUrl.contains("/serie/") && !recUrl.contains("/pelicula/")) return@mapNotNull null
            val recTitle = element.selectFirst(".Title")?.text() ?: return@mapNotNull null
            val recPoster = fixPosterUrl(element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src"))
            
            val recType = if (recUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
            newTvSeriesSearchResponse(recTitle, recUrl, recType) {
                this.posterUrl = recPoster
            }
        }
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun extractSendvid(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val embedUrl = if (url.contains("/embed/")) url else url.replace("/?", "/embed/?")
            Log.d("RetrotveProvider", "Sendvid: Using custom extractor for: $embedUrl")
            SendvidExtractor().getUrl(embedUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "Sendvid error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun extractTokyoVideo(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.get(url, referer = referer)
            val doc = resp.document
            Log.d("RetrotveProvider", "TokyoVideo: code=${resp.code}, len=${doc.html().length}")

            val source = doc.selectFirst("video source[src]")
            if (source != null) {
                val videoUrl = source.attr("src")
                Log.d("RetrotveProvider", "TokyoVideo: found $videoUrl")
                callback(newExtractorLink("TokyoVideo", "TokyoVideo", videoUrl) {
                    this.referer = "https://www.tokyvideo.com/"
                    this.quality = 1080
                })
            } else {
                Log.d("RetrotveProvider", "TokyoVideo: no source found, falling back to loadExtractor")
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "TokyoVideo error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun extractFilemoon(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.get(url, referer = referer)
            val page = resp.text
            Log.d("RetrotveProvider", "Filemoon: code=${resp.code}, url=${resp.url}, page len=${page.length}")
            if (page.length < 10000) Log.d("RetrotveProvider", "Filemoon: HTML=$page")

            val sources1 = Regex("""sources\s*:\s*\[?\s*\{?\s*file\s*:\s*"([^"]+)"\s*\}?\s*\]?""").findAll(page).toList()
            if (sources1.isNotEmpty()) {
                sources1.forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    Log.d("RetrotveProvider", "Filemoon: found $videoUrl")
                    callback(newExtractorLink("Filemoon", "Filemoon", videoUrl) {
                        this.referer = "https://filemoon.to/"
                        this.quality = 1080
                    })
                }
                return
            }

            val sources2 = Regex("""sources\s*:\s*\[?\s*\{?\s*file\s*:\s*'([^']+)'\s*\}?\s*\]?""").findAll(page).toList()
            if (sources2.isNotEmpty()) {
                sources2.forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    Log.d("RetrotveProvider", "Filemoon: found $videoUrl")
                    callback(newExtractorLink("Filemoon", "Filemoon", videoUrl) {
                        this.referer = "https://filemoon.to/"
                        this.quality = 1080
                    })
                }
                return
            }

            val directUrls = Regex("""https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*""").findAll(page).toList()
            if (directUrls.isNotEmpty()) {
                directUrls.forEach { match ->
                    Log.d("RetrotveProvider", "Filemoon: direct $match.value")
                    callback(newExtractorLink("Filemoon", "Filemoon", match.value) {
                        this.referer = "https://filemoon.to/"
                        this.quality = 1080
                    })
                }
                return
            }

            Log.d("RetrotveProvider", "Filemoon: SPA page, using loadExtractor")
            val cleanUrl = url.substringBeforeLast("/")
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "Filemoon error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun extractVKVideo(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val fixedUrl = url.replace("vkvideo.ru", "vk.com")
            if (fixedUrl != url) Log.d("RetrotveProvider", "VKVideo: rewritten $url -> $fixedUrl")
            val resp = app.get(fixedUrl, referer = referer, timeout = 60L)
            val page = resp.text
            Log.d("RetrotveProvider", "VKVideo: code=${resp.code}, page len=${page.length}")

            val mp4Match = Regex(""""mp4_720"\s*:\s*"([^"]+)"""").find(page)
            if (mp4Match != null) {
                val videoUrl = mp4Match.groupValues[1].replace("\\/", "/")
                Log.d("RetrotveProvider", "VKVideo: mp4_720=$videoUrl")
                callback(newExtractorLink("VKVideo", "VKVideo 720p", videoUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 720
                })
            }

            val hlsMatch = Regex(""""hls_ondemand"\s*:\s*"([^"]+)"""").find(page)
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1].replace("\\/", "/")
                Log.d("RetrotveProvider", "VKVideo: hls=$hlsUrl")
                callback(newExtractorLink("VKVideo", "VKVideo HLS", hlsUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 1080
                })
            }

            val dashMatch = Regex(""""dash_ondemand"\s*:\s*"([^"]+)"""").find(page)
            if (dashMatch != null) {
                val dashUrl = dashMatch.groupValues[1].replace("\\/", "/")
                Log.d("RetrotveProvider", "VKVideo: dash=$dashUrl")
                callback(newExtractorLink("VKVideo", "VKVideo DASH", dashUrl) {
                    this.referer = "https://vk.com/"
                    this.quality = 1080
                    this.type = ExtractorLinkType.DASH
                })
            }

            if (mp4Match == null && hlsMatch == null) {
                Log.d("RetrotveProvider", "VKVideo: no URL found")
            }
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "VKVideo error: ${e.message}")
        }
    }

    private suspend fun processPlayerPage(
        playerUrl: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RetrotveProvider", "processPlayerPage: $playerUrl (server: $serverName)")
        
        try {
            val playerResponse = app.get(playerUrl, referer = referer)
            Log.d("RetrotveProvider", "processPlayerPage: code=${playerResponse.code}, url=${playerResponse.url}")
            val playerDoc = playerResponse.document
            Log.d("RetrotveProvider", "processPlayerPage: page title=${playerDoc.title()}, html len=${playerDoc.html().length}")
            
            val iframeCount = playerDoc.select("iframe[src]").size
            Log.d("RetrotveProvider", "Found $iframeCount iframes in player page")
            
            if (iframeCount == 0) {
                Log.d("RetrotveProvider", "No iframes found - checking for alternative content")
                val pageText = playerDoc.html()
                // Check for any video-related content
                val videoSrc = Regex("""https?://[^"'\s]+\.(mp4|m3u8)[^"'\s]*""").findAll(pageText).toList()
                Log.d("RetrotveProvider", "Direct video URLs in page: ${videoSrc.map { it.value }}")
                playerDoc.select("script").forEach { script ->
                    val content = script.html()
                    if (content.contains("player") || content.contains("video") || content.contains("source")) {
                        Log.d("RetrotveProvider", "Relevant script: ${content.take(300)}")
                    }
                }
            }
            
            playerDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src") ?: return@forEach
                if (src.isBlank()) return@forEach
                
                val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                Log.d("RetrotveProvider", "Found iframe: $fixedSrc")
                
                when {
                    fixedSrc.contains("sendvid.com") -> {
                        extractSendvid(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("filemoon.") || fixedSrc.contains("filemoon.to") -> {
                        Log.d("RetrotveProvider", "-> Filemoon: using custom extractor for: $fixedSrc")
                        extractFilemoon(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("ok.ru") || fixedSrc.contains("odnoklassniki") -> {
                        Log.d("RetrotveProvider", "-> OK.RU: using loadExtractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("vk.com") || fixedSrc.contains("vkvideo") -> {
                        Log.d("RetrotveProvider", "-> VKVideo: using custom extractor for: $fixedSrc")
                        extractVKVideo(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("tokyvideo.com") -> {
                        Log.d("RetrotveProvider", "-> TokyoVideo: using custom extractor for: $fixedSrc")
                        extractTokyoVideo(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("mega.") || fixedSrc.contains("mega.nz") -> {
                        Log.d("RetrotveProvider", "-> Mega links require app installation, skipping")
                    }
                    fixedSrc.contains("yourupload.com") || fixedSrc.contains("yourupload.") -> {
                        Log.d("RetrotveProvider", "-> YourUpload: using loadExtractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("uqload.") -> {
                        Log.d("RetrotveProvider", "-> Uqload: using loadExtractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("gdriveplayer") -> {
                        Log.d("RetrotveProvider", "-> GDrivePlayer: using loadExtractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    fixedSrc.contains("mediafire") -> {
                        Log.d("RetrotveProvider", "-> MediaFire: using loadExtractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                    else -> {
                        Log.d("RetrotveProvider", "-> Using generic extractor for: $fixedSrc")
                        loadExtractor(fixedSrc, playerUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RetrotveProvider", "processPlayerPage error: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("RetrotveProvider", "loadLinks: data = $data")
        
        val document = app.get(data).document
        Log.d("RetrotveProvider", "loadLinks: page title = ${document.title()}")
        
        val trtype = if (data.contains("/pelicula/")) "1" else "2"
        
        Log.d("RetrotveProvider", "Searching for player URLs in episode page")
        
        val allEmbeds = mutableListOf<Pair<String, String>>()
        var correctTrid: String? = null
        
        document.select("iframe[src*='trembed']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("trembed") && src.contains("trid")) {
                val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                val tridMatch = Regex("""trid=(\d+)""").find(fixedSrc)
                tridMatch?.groupValues?.get(1)?.let { trid ->
                    if (correctTrid == null) {
                        correctTrid = trid
                        Log.d("RetrotveProvider", "Found correct trid from iframe: $trid")
                    }
                }
                allEmbeds.add(fixedSrc to "Iframe")
                Log.d("RetrotveProvider", "Found trembed iframe: $fixedSrc")
            }
        }
        
        document.select(".TPlayerTb[id], .TPlayer[id]").forEach { tab ->
            val tabId = tab.attr("id")
            Log.d("RetrotveProvider", "Checking tab: $tabId, html=${tab.html().take(200)}")
            
            tab.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("retrotve.com") && src.contains("trembed") && src.contains("trid")) {
                    val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                    val tridMatch = Regex("""trid=(\d+)""").find(fixedSrc)
                    tridMatch?.groupValues?.get(1)?.let { trid ->
                        if (correctTrid == null) {
                            correctTrid = trid
                            Log.d("RetrotveProvider", "Found correct trid from tab $tabId: $trid")
                        }
                    }
                    if (!allEmbeds.any { it.first == fixedSrc }) {
                        allEmbeds.add(fixedSrc to tabId)
                        Log.d("RetrotveProvider", "Found trembed iframe in $tabId: $fixedSrc")
                    }
                }
            }
            
            tab.select("div[data-src*='trembed']").forEach { div ->
                val src = div.attr("data-src")
                if (src.contains("trembed") && src.contains("trid")) {
                    val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                    val tridMatch = Regex("""trid=(\d+)""").find(fixedSrc)
                    tridMatch?.groupValues?.get(1)?.let { trid ->
                        if (correctTrid == null) {
                            correctTrid = trid
                            Log.d("RetrotveProvider", "Found correct trid from data-src: $trid")
                        }
                    }
                    if (!allEmbeds.any { it.first == fixedSrc }) {
                        allEmbeds.add(fixedSrc to tabId)
                        Log.d("RetrotveProvider", "Found trembed data-src in $tabId: $fixedSrc")
                    }
                }
            }
        }
        
        if (correctTrid != null) {
            Log.d("RetrotveProvider", "Using correct trid: $correctTrid, trying all trembed options")
            
            for (trembed in 0..5) {
                val playerUrl = "$mainUrl/?trembed=$trembed&trid=$correctTrid&trtype=$trtype"
                if (!allEmbeds.any { it.first.contains("trembed=$trembed&") && it.first.contains("trid=$correctTrid") }) {
                    allEmbeds.add(playerUrl to "Opt${trembed + 1}")
                    Log.d("RetrotveProvider", "Generated trembed $trembed: $playerUrl")
                }
            }
        } else {
            Log.d("RetrotveProvider", "No trid found! Checking page for alternative embeds...")
            document.select("iframe[src]").forEach {
                Log.d("RetrotveProvider", "Page iframe: ${it.attr("src")}")
            }
            document.select("video[src], source[src]").forEach {
                Log.d("RetrotveProvider", "Direct video: ${it.attr("src")}")
            }
        }
        
        val uniqueEmbeds = allEmbeds.distinctBy { it.first }
        Log.d("RetrotveProvider", "Found ${uniqueEmbeds.size} unique embed URLs to process")
        
        uniqueEmbeds.forEach { (playerUrl, serverName) ->
            processPlayerPage(playerUrl, data, serverName, subtitleCallback, callback)
        }
        
        Log.d("RetrotveProvider", "loadLinks completed, extractors should be processing")
        return true
    }
}

fun getBaseName(url: String): String {
    return url.substringAfter("://").substringBefore("/").substringBefore(".")
}

fun fixHostsLinks(url: String): String {
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
        .replaceFirst("https://uqload.cx", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
        .replaceFirst("https://short.ink/", "https://")
}
