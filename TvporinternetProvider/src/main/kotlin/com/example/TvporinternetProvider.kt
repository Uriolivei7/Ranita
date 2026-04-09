package com.example

import android.content.res.Configuration
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class TvporinternetProvider : MainAPI() {
    override var mainUrl = "https://www.tvporinternet2.com"
    override var name = "TvporInternet"

    override val supportedTypes = setOf(
        TvType.Live
    )

    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()
    private val nowAllowed = listOf("Red Social", "Donacion", "Donar con Paypal", "Mundo Latam")

    private val infantilCat = setOf(
        "Cartoon Network", "Tooncast", "Disney Channel", "Nick", "Nickelodeon", "FM Hot Kids"
    )

    private val peliculasSeriesCat = setOf(
        "Universal Channel", "Universal Premiere", "Universal Cinema", "TNT", "TNT Series", "TNT Novelas",
        "Star Channel", "Cinemax", "Space", "Syfy", "Warner Channel", "Cinecanal", "FX",
        "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Sony", "Panico", "Extrema",
        "USA", "Canal Sony"
    )

    private val educacionCat = setOf(
        "Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia",
        "Discovery H&H", "Discovery A&E", "ID Investigation",
        "History", "History 2", "Animal Planet", "Nat Geo"
    )

    private val entretenimientoCat = setOf(
        "Telefe", "El Trece", "Television Publica", "Telemundo", "Univision", "Pasiones", "Caracol",
        "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tlnovelas", "Galavision",
        "Azteca", "Canal 5", "Distrito Comedia", "MTV", "E!", "Unicable", "Imagen TV", "Azteca 7",
        "Azteca Uno", "Antena 3", "DW", "FM Hot Movies"
    )

    private val deportesCat = setOf(
        "TUDN", "WWE", "Afizzionados", "Gol Peru", "Gol TV", "TNT Sports", "Fox Sports",
        "TyC Sports", "Movistar", "Dazn", "Bein", "Directv Sports", "ESPN", "Win Sports",
        "Azteca Deportes", "Liga 1", "Sky Sports", "VIX TUDN"
    )

    private val noticiasCat = setOf(
        "Telemundo 51", "CNN", "Noticias", "RTVE"
    )

    private val localLatinoCat = setOf(
        "Canal", "Televisa", "TV Azteca", "TV Publica", "TV Peru"
    )

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        additionalHeaders: Map<String, String>? = null,
        referer: String? = null
    ): String? {
        val requestHeaders = (additionalHeaders ?: emptyMap()).toMutableMap()
        if (!requestHeaders.containsKey("User-Agent")) {
            requestHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        if (!referer.isNullOrBlank() && !requestHeaders.containsKey("Referer")) {
            requestHeaders["Referer"] = referer
        }

        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                if (res.isSuccessful) {
                    return res.text
                }
            } catch (e: Exception) {
                Log.e("TvporInternet", "safeAppGet error: ${e.message}")
            }
            if (i < retries - 1) {
                delay(delayMs)
            }
        }
        return null
    }

    private fun getCategory(title: String): String {
        val normalizedTitle = title.uppercase().replace(" EN VIVO", "").trim()

        return when {
            infantilCat.any { normalizedTitle.contains(it) } -> "Infantil"
            educacionCat.any { normalizedTitle.contains(it) } -> "Educacion"
            noticiasCat.any { normalizedTitle.contains(it) } -> "Noticias"
            entretenimientoCat.any { normalizedTitle.contains(it) } -> "Entretenimiento"
            peliculasSeriesCat.any { normalizedTitle.contains(it) } -> "Peliculas"
            deportesCat.any { normalizedTitle.contains(it) } -> "Deportes"
            localLatinoCat.any { normalizedTitle.contains(it) } -> "Latino"
            else -> "Canales"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val html = safeAppGet(mainUrl) ?: return null
        val categoryMap = mutableMapOf<String, MutableList<SearchResponse>>()

        val channels = extractChannelsFromHtml(html)

        channels.forEach { (titleRaw, link, img) ->
            val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()

            if (nowAllowed.any { title.contains(it, ignoreCase = true) }) return@forEach

            val channelResponse = newTvSeriesSearchResponse(
                name = title,
                url = fixUrl(link)
            ) {
                this.type = TvType.Live
                this.posterUrl = fixUrl(img)
            }

            val category = getCategory(title)
            categoryMap.getOrPut(category) { ArrayList() }.add(channelResponse)
        }

        val homePageList = categoryMap.entries
            .sortedByDescending { it.value.size }
            .map { (category, items) ->
                HomePageList(category, items)
            }

        return newHomePageResponse(homePageList, false)
    }

    private fun extractChannelsFromHtml(html: String): List<Triple<String, String, String>> {
        val channels = mutableListOf<Triple<String, String, String>>()

        val homeChannelsMatch = Regex("""const\s+homeChannels\s*=\s*`([^`]*)`""", RegexOption.DOT_MATCHES_ALL).find(html)

        if (homeChannelsMatch != null) {
            val channelsHtml = homeChannelsMatch.groupValues[1]
            val channelDoc = Jsoup.parse(channelsHtml)

            channelDoc.select("a.channel-card").forEach { channelCard ->
                val link = channelCard.attr("href")
                val imgElement = channelCard.selectFirst("img")
                val pElement = channelCard.selectFirst("p")
                val titleRaw = if (imgElement?.attr("alt")?.isNotBlank() == true) {
                    imgElement.attr("alt")
                } else if (pElement?.text()?.isNotBlank() == true) {
                    pElement.text()
                } else {
                    ""
                }
                val img = imgElement?.attr("src") ?: ""

                Log.d("TvporInternet", "Extract: link=$link, title='$titleRaw', img='$img'")

                if (titleRaw.isNotBlank() && link.isNotBlank()) {
                    channels.add(Triple(titleRaw, link, img))
                }
            }
        }

        return channels
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("TvporInternet", "Search: query='$query'")
        val html = safeAppGet(mainUrl) ?: run {
            Log.d("TvporInternet", "Search: failed to get HTML")
            return emptyList()
        }

        val channels = extractChannelsFromHtml(html)
        Log.d("TvporInternet", "Search: found ${channels.size} channels")

        if (query.isBlank()) {
            Log.d("TvporInternet", "Search: query is blank, returning empty")
            return emptyList()
        }

        val filtered = channels.filterNot { (titleRaw, _, _) ->
            val shouldFilter = nowAllowed.any { titleRaw.contains(it, ignoreCase = true) } || titleRaw.isBlank()
            if (shouldFilter) Log.d("TvporInternet", "Search: filtering out '$titleRaw'")
            shouldFilter
        }

        val matched = filtered.filter { (titleRaw, _, _) ->
            val matches = titleRaw.contains(query, ignoreCase = true)
            Log.d("TvporInternet", "Search: '$titleRaw' matches '$query' = $matches")
            matches
        }

        Log.d("TvporInternet", "Search: matched ${matched.size} channels")

        return matched.mapNotNull { (titleRaw, linkRaw, imgRaw) ->
            val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
            newLiveSearchResponse(
                name = title,
                url = fixUrl(linkRaw),
                type = TvType.Live
            ) {
                this.posterUrl = fixUrl(imgRaw)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.text-3xl.font-bold")?.text()?.replace(" EN VIVO", "")?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.replace(" EN VIVO", "")?.trim()
            ?: "Canal Desconocido"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img[alt*='logo'][src]")?.attr("src")
            ?: ""

        val description = doc.selectFirst("div.info.text-sm.leading-relaxed")?.text() ?: ""

        val episodes = listOf(
            newEpisode(data = url) {
                this.name = "En Vivo"
                this.posterUrl = fixUrl(poster)
            }
        )

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Live,
            episodes = episodes
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = fixUrl(data)
        Log.d("TvporInternet", "Logs: Cargando URL base -> $targetUrl")

        try {
            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Upgrade-Insecure-Requests" to "1"
            )

            val mainPageResponse = app.get(targetUrl, headers = mainHeaders)
            val doc = Jsoup.parse(mainPageResponse.text)

            val optionLinks = doc.select("a[href*=/live], iframe[name=player], iframe[src*=/live]")
                .mapNotNull { if (it.tagName() == "iframe") it.attr("src") else it.attr("href") }
                .filter { it.isNotBlank() && !it.contains("facebook") }
                .distinct()

            Log.d("TvporInternet", "Logs: Opciones detectadas: ${optionLinks.size}")

            var success = false

            for ((index, rawPlayerUrl) in optionLinks.withIndex()) {
                val playerUrl = fixUrl(rawPlayerUrl)
                try {
                    Log.d("TvporInternet", "Logs: Entrando a Opción ${index + 1} -> $playerUrl")

                    val playerHeaders = mainHeaders.toMutableMap().apply {
                        put("Referer", targetUrl)
                        put("Sec-Fetch-Site", "same-origin")
                    }

                    val playerResponse = app.get(playerUrl, headers = playerHeaders)
                    val playerHtml = playerResponse.text

                    val internalIframe = Regex("""iframe.*src=["']([^"']*saohgdasregions\.fun[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)

                    val finalHtml = if (internalIframe != null) {
                        val iframeUrl = fixUrl(internalIframe)
                        Log.d("TvporInternet", "Logs: Iframe interno hallado: $iframeUrl")
                        app.get(iframeUrl, headers = playerHeaders.apply { put("Referer", playerUrl) }).text
                    } else {
                        playerHtml
                    }

                    val m3u8Url = extractM3u8FromHtml(finalHtml)

                    if (!m3u8Url.isNullOrEmpty()) {
                        Log.d("TvporInternet", "Logs: ¡Éxito! M3U8: $m3u8Url")

                        val streamingHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Origin" to "https://regionales.saohgdasregions.fun",
                            "Referer" to "https://regionales.saohgdasregions.fun/"
                        )

                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - Opción ${index + 1}",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = streamingHeaders
                            }
                        )
                        success = true
                    } else {
                        val title = Jsoup.parse(playerHtml).title()
                        Log.w("TvporInternet", "Logs: Falló Opción ${index + 1}. Título recibido: $title")
                    }
                } catch (e: Exception) {
                    Log.e("TvporInternet", "Logs: Error en opción $index: ${e.message}")
                }
            }
            return success
        } catch (e: Exception) {
            Log.e("TvporInternet", "Logs: Error crítico: ${e.message}")
            return false
        }
    }

    private fun extractM3u8FromHtml(html: String): String? {
        val patterns = listOf(
            """["'](https?[:\/\/\\]+[^"']+\.m3u8[^"']*)["']""",
            """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""
        )

        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                val found = match.groupValues[1].replace("\\/", "/")
                return found
            }
        }
        return null
    }

}