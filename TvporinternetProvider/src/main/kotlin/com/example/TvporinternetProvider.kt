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