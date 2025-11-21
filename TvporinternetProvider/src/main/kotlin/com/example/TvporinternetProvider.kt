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
    private val nowAllowed = listOf("Red Social", "Donacion")

    private val deportesCat = setOf(
        "TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS", "Fox Sports Premium",
        "TYC Sports", "Movistar Deportes", "Dazn F1", "Bein", "Directv Sports", "Espn", "Fox Sports"
    )

    private val entretenimientoCat = setOf(
        "Telefe", "El Trece", "Televisión Pública", "Telemundo", "Univisión", "Pasiones", "Caracol",
        "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision",
        "Azteca", "Canal 5", "Distrito Comedia", "MTV", "E!"
    )

    private val noticiasCat = setOf(
        "Telemundo 51", "CNN", "Noticias", "RTVE"
    )

    private val peliculasSeriesCat = setOf(
        "Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series", "Star Channel",
        "Star Action", "Star Series", "Cinemax", "Space", "Syfy", "Warner Channel", "Cinecanal", "FX",
        "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Sony", "DHE", "NEXT HD", "HBO"
    )

    private val infantilCat = setOf(
        "Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick", "Discovery Kids"
    )

    private val educacionCat = setOf(
        "Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia",
        "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo"
    )

    private val localLatinoCat = setOf(
        "Canal", "Televisa", "TV Azteca", "TV Publica", "TV Perú"
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
            deportesCat.any { normalizedTitle.contains(it.uppercase()) } -> "Deportes"
            peliculasSeriesCat.any { normalizedTitle.contains(it.uppercase()) } -> "Películas y Series"
            infantilCat.any { normalizedTitle.contains(it.uppercase()) } -> "Infantil"
            educacionCat.any { normalizedTitle.contains(it.uppercase()) } -> "Documentales/Educación"
            noticiasCat.any { normalizedTitle.contains(it.uppercase()) } -> "Noticias"
            entretenimientoCat.any { normalizedTitle.contains(it.uppercase()) } -> "Entretenimiento"
            localLatinoCat.any { normalizedTitle.contains(it.uppercase()) } -> "Latinoamérica"
            else -> "Otros Canales"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val html = safeAppGet(mainUrl) ?: return null
        val doc = Jsoup.parse(html)
        val categoryMap = mutableMapOf<String, MutableList<SearchResponse>>()

        doc.select("div.p-2.rounded.bg-slate-200.border").forEach { channelDiv ->
            val linkElement = channelDiv.selectFirst("a.channel-link")
            val link = linkElement?.attr("href")
            val imgElement = linkElement?.selectFirst("img")
            val titleRaw = imgElement?.attr("alt") ?: linkElement?.selectFirst("p.des")?.text()

            if (titleRaw != null && link != null) {
                val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
                val img = fixUrl(imgElement?.attr("src") ?: "")

                if (nowAllowed.any { title.contains(it, ignoreCase = true) }) return@forEach

                val channelResponse = newTvSeriesSearchResponse(
                    name = title,
                    url = fixUrl(link)
                ) {
                    this.type = TvType.Live
                    this.posterUrl = img
                }

                val category = getCategory(title)
                categoryMap.getOrPut(category) { ArrayList() }.add(channelResponse)
            }
        }

        val homePageList = categoryMap.entries
            .sortedByDescending { it.value.size }
            .map { (category, items) ->
                HomePageList(category, items)
            }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = safeAppGet(mainUrl) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div.p-2.rounded.bg-slate-200.border").filterNot { element ->
            val text = element.selectFirst("p.des")?.text() ?: ""
            nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
        }.filter { element ->
            element.selectFirst("p.des")?.text()?.contains(query, ignoreCase = true) ?: false
        }.mapNotNull {
            val titleRaw = it.selectFirst("p.des")?.text()
            val linkRaw = it.selectFirst("a")?.attr("href")
            val imgRaw = it.selectFirst("a img.w-28")?.attr("src")

            if (titleRaw != null && linkRaw != null && imgRaw != null) {
                val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
                newLiveSearchResponse(
                    name = title,
                    url = fixUrl(linkRaw),
                    type = TvType.Live
                ) {
                    this.posterUrl = fixUrl(imgRaw)
                }
            } else {
                null
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

        try {
            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "Referer" to targetUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )

            val mainPageResponse = app.get(targetUrl, headers = mainHeaders, timeout = 20L)
            val cookies = mainPageResponse.cookies.toMutableMap()
            val doc = Jsoup.parse(mainPageResponse.text)

            val playerIframeSrc = doc.selectFirst("iframe[name=player]")?.attr("src")

            val optionLinks = doc.select("a[href*=/live], a[href*=/live/], a[href*=/live2/], a[href*=/live3/], a[href*=/live4/], a[href*=/live5/], a[href*=/live6/]")
                .mapNotNull { it.attr("href") }
                .filter { !it.contains("favicon") && !it.contains("chat") && it != "#" }
                .distinct()

            val playerUrls = mutableListOf<String>()
            playerIframeSrc?.let { playerUrls.add(fixUrl(it)) }
            playerUrls.addAll(optionLinks.map { fixUrl(it) })

            if (playerUrls.isEmpty()) return false

            val isWSA = context?.resources?.configuration?.screenLayout?.and(Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE
            val forceSD = isWSA

            var success = false

            for ((index, rawPlayerUrl) in playerUrls.withIndex()) {
                val playerUrl = fixUrl(rawPlayerUrl)

                if (forceSD && playerUrl.contains("FHD", ignoreCase = true)) continue

                try {
                    val currentReferer = playerUrl

                    val playerResponse = app.get(playerUrl, headers = mainHeaders + mapOf("Referer" to targetUrl), cookies = cookies, timeout = 20L)
                    cookies.putAll(playerResponse.cookies)
                    val playerHtml = playerResponse.text

                    val embedUrl = Regex("""(https?://[^"']*saohgdasregions\.fun[^"']*)""").find(playerHtml)?.groupValues?.get(1)
                        ?: Regex("""src=["']([^"']*stream[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)

                    val embedHtml = if (embedUrl != null) {
                        app.get(fixUrl(embedUrl), headers = mainHeaders + mapOf("Referer" to currentReferer), cookies = cookies, timeout = 20L).text
                    } else playerHtml

                    val m3u8Url = extractM3u8FromHtml(embedHtml)?.let { fixUrl(it) }

                    if (!m3u8Url.isNullOrEmpty() && m3u8Url.startsWith("http")) {
                        val m3u8UrlFinal = if (m3u8Url.contains("?")) "$m3u8Url&buffer=1" else "$m3u8Url?buffer=1"

                        val qualityName = if (forceSD) "SD" else if (playerUrl.contains("FHD")) "FHD" else "HD"
                        val qualityValue = if (qualityName == "FHD") 1080 else if (qualityName == "SD") 480 else 720

                        val streamingHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Origin" to "https://live.saohgdasregions.fun",
                            "Accept" to "*/*"
                        )

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "${this.name} - $qualityName (Opción ${index + 1})",
                                url = m3u8UrlFinal,
                                referer = "https://live.saohgdasregions.fun/",
                                quality = qualityValue,
                                type = ExtractorLinkType.M3U8,
                                headers = streamingHeaders
                            )
                        )
                        success = true
                    }

                } catch (e: Exception) {
                    Log.e("TvporInternet", "Error procesando opción $playerUrl: ${e.message}")
                }
            }

            return success

        } catch (e: Exception) {
            Log.e("TvporInternet", "Error crítico en loadLinks: ${e.message}")
            return false
        }
    }

    private fun extractM3u8FromHtml(html: String): String? {
        val doc = Jsoup.parse(html)

        val patterns = listOf(
            """(https?://[^"'\s]+\.m3u8[^"'\s]*)""",
            """["'](https?:(?:\\\/\\\/|\/\/)[^"']+\.m3u8[^"']*)["']""",
            """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """src\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """url\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""
        )

        val source = doc.selectFirst("video > source, source[src]")
        if (source != null && source.attr("src").contains(".m3u8")) {
            return source.attr("src")
        }

        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                return match.groupValues.getOrNull(1)?.replace("\\/", "/") ?: match.value.replace("\\/", "/")
            }
        }

        val dataSource = doc.selectFirst("[data-src], [data-url], [data-source], [data-hls]")
        if (dataSource != null) {
            val url = dataSource.attr("data-src").ifBlank { null }
                ?: dataSource.attr("data-url").ifBlank { null }
                ?: dataSource.attr("data-source").ifBlank { null }
                ?: dataSource.attr("data-hls").ifBlank { null }

            if (!url.isNullOrEmpty() && url.contains(".m3u8")) {
                return url
            }
        }
        return null
    }
}