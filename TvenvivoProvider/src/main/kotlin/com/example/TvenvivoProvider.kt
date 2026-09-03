package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.Interceptor
import okhttp3.Response
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class TvenvivoProvider : MainAPI() {
    companion object {
        var pluginContext: android.content.Context? = null
    }
    override var mainUrl = "https://www.tvenvivo2.com/"
    override var name = "TVenVIVO"

    override val supportedTypes = setOf(
        TvType.Live
    )

    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "es-ES,es;q=0.9",
        "Connection" to "keep-alive",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Storage-Access" to "none",
        "Sec-GPC" to "1",
        "sec-ch-ua" to "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )
    private val successfulOptionUrl = HashMap<String, String>()
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
        timeoutMs: Long = 10000L,
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

        for (attempt in 1..2) {
            try {
                val interceptor = if (attempt > 1) cfKiller else null
                val res = app.get(url, timeout = timeoutMs * attempt, headers = requestHeaders, interceptor = interceptor)
                if (res.isSuccessful) return res.text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Tvenvivo", "safeAppGet error (intento $attempt): ${e::class.simpleName} - ${e.message}")
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
        val html = safeAppGet(mainUrl) ?: run { Log.e("Tvenvivo", "getMainPage: failed to get HTML"); return null }
        val channels = extractChannelsFromHtml(html)
        if (channels.isEmpty()) Log.w("Tvenvivo", "getMainPage: 0 canales extraídos (selectors changed?)")
        val uniqueChannels = channels.distinctBy { (_, link, _) -> link }
        val channelResults = uniqueChannels.mapNotNull { (titleRaw, link, img) ->
            val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
            if (nowAllowed.any { title.contains(it, ignoreCase = true) }) return@mapNotNull null
            newTvSeriesSearchResponse(name = title, url = fixUrl(link)) {
                this.type = TvType.Live
                this.posterUrl = fixUrl(img)
            }
        }
        Log.d("Tvenvivo", "getMainPage: ${channelResults.size} canales")
        return newHomePageResponse(listOf(HomePageList("Todos los Canales", channelResults)), false)
    }

    private fun extractChannelsFromHtml(html: String): List<Triple<String, String, String>> {
        val channels = mutableListOf<Triple<String, String, String>>()
        val doc = Jsoup.parse(html)

        val selectors = listOf("a.channel", "a.channel-card", "a[class*=channel]")
        val seen = mutableSetOf<String>()
        for (sel in selectors) {
            doc.select(sel).forEach { channelCard ->
                val link = channelCard.attr("href")
                if (link.isBlank() || !seen.add(link)) return@forEach
                val imgElement = channelCard.selectFirst("img")
                val nameDiv = channelCard.selectFirst("div.channel-name")?.text()?.trim()
                val pElement = channelCard.selectFirst("p")
                val titleRaw = when {
                    imgElement?.attr("alt")?.isNotBlank() == true -> imgElement.attr("alt")
                    !nameDiv.isNullOrBlank() -> nameDiv
                    pElement?.text()?.isNotBlank() == true -> pElement.text()
                    channelCard.attr("aria-label").isNotBlank() -> channelCard.attr("aria-label").replace(" en vivo", "", true).trim()
                    else -> ""
                }
                val img = imgElement?.attr("src") ?: ""
                if (titleRaw.isNotBlank() && link.isNotBlank()) {
                    channels.add(Triple(titleRaw, link, img))
                }
            }
            if (channels.isNotEmpty()) break
        }

        return channels
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("Tvenvivo", "Search: query='$query'")
        val html = safeAppGet(mainUrl) ?: run { Log.e("Tvenvivo", "Search: failed to get HTML"); return emptyList() }
        val channels = extractChannelsFromHtml(html)
        if (channels.isEmpty()) Log.w("Tvenvivo", "Search: 0 canales (getMainPage also failing?)")
        if (query.isBlank()) return emptyList()
        val filtered = channels.filterNot { (titleRaw, _, _) ->
            val shouldFilter = nowAllowed.any { titleRaw.contains(it, ignoreCase = true) } || titleRaw.isBlank()
            shouldFilter
        }
        val matched = filtered.filter { (titleRaw, _, _) ->
            titleRaw.contains(query, ignoreCase = true)
        }
        Log.d("Tvenvivo", "Search: ${matched.size} resultados para '$query'")
        return matched.mapNotNull { (titleRaw, linkRaw, imgRaw) ->
            val title = titleRaw.replace("Ver ", "").replace(" en vivo", "").trim()
            newLiveSearchResponse(name = title, url = fixUrl(linkRaw), type = TvType.Live) {
                this.posterUrl = fixUrl(imgRaw)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = safeAppGet(url) ?: run { Log.e("Tvenvivo", "load: failed to get HTML $url"); return null }
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.font-bold")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("title")?.text()
            ?: run { Log.w("Tvenvivo", "load: title not found $url"); "Canal Desconocido" }
        val cleanTitle = title.replace(Regex("""(?i)\bVer\s+"""), "").replace(Regex("""(?i)\s*en\s+vivo(\s*hd)?"""), "").replace(Regex("""\s*\|\s*.*"""), "").trim()
        val poster = doc.selectFirst("div.info-logo img")?.attr("src") ?: doc.selectFirst("meta[name='og:image']")?.attr("content") ?: doc.selectFirst("section img[src*='/imge/']")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        val description = doc.selectFirst("section.info div.info-card")?.text() ?: doc.selectFirst("meta[property='og:description']")?.attr("content") ?: doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        if (poster.isBlank()) Log.w("Tvenvivo", "load: poster not found for $cleanTitle")
        Log.d("Tvenvivo", "load: $cleanTitle")
        val episodes = listOf(newEpisode(data = url) { this.name = "En Vivo"; this.posterUrl = fixUrlNull(poster) })
        return newTvSeriesLoadResponse(name = cleanTitle, url = url, type = TvType.Live, episodes = episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.backgroundPosterUrl = fixUrlNull(poster)
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
        Log.d("Tvenvivo", "loadLinks: $targetUrl")

        try {
            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            )

            val mainPageResponse = withTimeoutOrNull(20000L) { app.get(targetUrl, headers = mainHeaders, interceptor = cfKiller) }
                ?: run {
                    Log.w("Tvenvivo", "loadLinks: timeout al cargar página")
                    return false
                }
            val mainCookies = mainPageResponse.cookies
            val doc = Jsoup.parse(mainPageResponse.text)

            val directM3u8 = extractM3u8FromHtml(mainPageResponse.text, strict = false)
            if (directM3u8 != null) {
                Log.d("Tvenvivo", "loadLinks: M3U8 directo en página: $directM3u8")
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - Directo",
                        url = directM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to targetUrl
                        )
                    }
                )
                return true
            }

            val optionLinksBuilder = mutableListOf<String>()
            doc.select("button[data-src], a[data-src], [data-src]").forEach {
                val ds = it.attr("data-src")
                if (ds.isNotBlank()) optionLinksBuilder.add(ds)
            }
            doc.select("a[href*=/live], iframe[name=player], iframe[src*=/live], iframe#playerFrame").forEach {
                val v = if (it.tagName() == "iframe") it.attr("src") else it.attr("href")
                if (v.isNotBlank()) optionLinksBuilder.add(v)
            }
            val optionLinks = optionLinksBuilder.filter { it.isNotBlank() && !it.contains("facebook") }.distinct()

            val cachedUrl = successfulOptionUrl[targetUrl]
            val finalLinks = if (cachedUrl != null) {
                val cachedIdx = optionLinks.indexOf(cachedUrl)
                if (cachedIdx > 0) {
                    val link = optionLinks[cachedIdx]
                    listOf(link) + optionLinks.filterIndexed { i, _ -> i != cachedIdx }
                } else optionLinks
            } else optionLinks

            Log.d("Tvenvivo", "loadLinks: ${finalLinks.size} opciones")

            if (finalLinks.isEmpty()) return false

            return coroutineScope {
                for ((displayIdx, rawUrl) in finalLinks.withIndex()) {
                    if (tryLoadOption(targetUrl, rawUrl, displayIdx, mainHeaders, mainCookies, callback)) {
                        return@coroutineScope true
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.e("Tvenvivo", "loadLinks error: ${e.message}")
            return false
        }
    }

    private suspend fun tryLoadOption(
        targetUrl: String,
        rawPlayerUrl: String,
        displayIndex: Int,
        mainHeaders: Map<String, String>,
        mainCookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = fixUrl(rawPlayerUrl)
        return try {
            withTimeout(30000L) {
                Log.d("Tvenvivo", "Opción ${displayIndex + 1}: $playerUrl")

                val coreHeaders = mainHeaders.toMutableMap().apply {
                    put("Referer", targetUrl)
                    put("Sec-Fetch-Site", "same-origin")
                }
                val coreResp = withTimeoutOrNull(15000L) {
                    app.get(playerUrl, timeout = 15000L, headers = coreHeaders, cookies = mainCookies)
                }
                if (coreResp == null || !coreResp.isSuccessful) {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: core.php falló ${coreResp?.code}")
                    return@withTimeout false
                }

                val allCookies = mainCookies + coreResp.cookies

                val streamUrlRaw = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(coreResp.text)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                if (streamUrlRaw.isNullOrBlank()) {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: sin iframe en core.php")
                    return@withTimeout false
                }
                val streamUrl = fixUrl(streamUrlRaw)

                val canal = Regex("""canal=([^&]+)""").find(streamUrl)?.groupValues?.get(1) ?: ""
                val target = Regex("""target=([^&]+)""").find(streamUrl)?.groupValues?.get(1) ?: ""
                val sig = Regex("""sig=([^&]+)""").find(streamUrl)?.groupValues?.get(1) ?: ""
                if (canal.isBlank() || sig.isBlank()) {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: params incompletos canal=$canal sig=$sig")
                    return@withTimeout false
                }

                val streamOrigin = try {
                    val u = java.net.URL(streamUrl); "${u.protocol}://${u.host}"
                } catch (_: Exception) { return@withTimeout false }

                val playlistUrl = "$streamOrigin/playlist.php?canal=$canal&target=$target&sig=$sig"
                val playlistHeaders = browserHeaders + mapOf(
                    "Referer" to streamUrl,
                    "Accept" to "*/*"
                )

                val plResp = withTimeoutOrNull(15000L) {
                    app.get(playlistUrl, timeout = 15000L, headers = playlistHeaders, cookies = allCookies)
                }
                val plBody = plResp?.text
                if (plResp?.code == 403) {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist directo 403 server=${plResp.headers?.get("Server") ?: "?"}")
                }

                if (plResp != null && plResp.isSuccessful && plBody != null) {
                    if (plBody.contains("#EXTM3U")) {
                        Log.d("Tvenvivo", "Opción ${displayIndex + 1}: ¡M3U8 directo!")
                        emitLink(displayIndex, playlistUrl, streamUrl, callback)
                        successfulOptionUrl[targetUrl] = rawPlayerUrl
                        return@withTimeout true
                    }
                    val embeddedM3u8 = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").find(plBody)?.groupValues?.get(1)
                    if (embeddedM3u8 != null) {
                        Log.d("Tvenvivo", "Opción ${displayIndex + 1}: M3U8 embebido")
                        emitLink(displayIndex, embeddedM3u8, streamUrl, callback)
                        successfulOptionUrl[targetUrl] = rawPlayerUrl
                        return@withTimeout true
                    }
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist sin m3u8: ${plBody.take(200)}")
                } else if (plResp?.code != 403) {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist directo falló ${plResp?.code ?: "timeout"}")
                }

                val streamHeaders = mainHeaders.toMutableMap().apply {
                    put("Referer", targetUrl)
                    put("Sec-Fetch-Site", "cross-site")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Dest", "iframe")
                }
                val streamResp = withTimeoutOrNull(15000L) {
                    app.get(streamUrl, timeout = 15000L, headers = streamHeaders, cookies = allCookies, interceptor = cfKiller)
                }
                if (streamResp != null && streamResp.isSuccessful) {
                    val streamHtml = streamResp.text

                    val streamCookies = streamResp.cookies
                    val rawSetCookies = streamResp.headers?.names()?.filter { it.lowercase() == "set-cookie" }
                        ?.flatMap { name -> streamResp.headers.values(name) }
                        ?.associate {
                            val kv = it.split(";")[0].split("=", limit = 2)
                            kv[0].trim() to (kv.getOrNull(1)?.trim() ?: "")
                        } ?: emptyMap()
                    val streamAllCookies = allCookies + streamCookies + rawSetCookies

                    if (streamHtml.contains("#EXTM3U")) {
                        Log.d("Tvenvivo", "Opción ${displayIndex + 1}: stream.php es m3u8 directo")
                        emitLink(displayIndex, streamUrl, streamUrl, callback)
                        successfulOptionUrl[targetUrl] = rawPlayerUrl
                        return@withTimeout true
                    }

                    val playlistFromJs = Regex("""(?:var\s+src|source|file)\s*=\s*["']([^"']*playlist\.php[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .find(streamHtml)?.groupValues?.get(1)
                        ?.replace("\\/", "/")
                        ?.replace("&amp;", "&")

                    if (playlistFromJs != null) {
                        val fullPlaylistUrl = if (playlistFromJs.startsWith("http")) playlistFromJs else "$streamOrigin/$playlistFromJs"

                        val jsPlaylistHeaders = browserHeaders + mapOf(
                            "Referer" to streamUrl,
                            "Accept" to "*/*"
                        )
                        val pl2Resp = withTimeoutOrNull(15000L) {
                            app.get(fullPlaylistUrl, timeout = 15000L, headers = jsPlaylistHeaders, cookies = streamAllCookies)
                        }
                        val pl2Body = pl2Resp?.text
                        if (pl2Resp?.code == 403) {
                            Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist JS 403 server=${pl2Resp.headers?.get("Server") ?: "?"}")
                        }
                        if (pl2Resp != null && pl2Resp.isSuccessful && pl2Body != null) {
                            if (pl2Body.contains("#EXTM3U")) {
                                Log.d("Tvenvivo", "Opción ${displayIndex + 1}: ¡M3U8 desde JS playlist!")
                                emitLink(displayIndex, fullPlaylistUrl, streamUrl, callback)
                                successfulOptionUrl[targetUrl] = rawPlayerUrl
                                return@withTimeout true
                            }
                            val embedded = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").find(pl2Body)?.groupValues?.get(1)
                            if (embedded != null) {
                                Log.d("Tvenvivo", "Opción ${displayIndex + 1}: M3U8 embebido en playlist")
                                emitLink(displayIndex, embedded, streamUrl, callback)
                                successfulOptionUrl[targetUrl] = rawPlayerUrl
                                return@withTimeout true
                            }
                            Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist JS sin m3u8: ${pl2Body.take(200)}")
                        } else if (pl2Resp?.code != 403) {
                            Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist JS falló ${pl2Resp?.code ?: "timeout"}")
                        }

                        val origPlUrl = "$streamOrigin/playlist.php?canal=$canal&target=$target&sig=$sig"
                        val pl3Resp = withTimeoutOrNull(15000L) {
                            app.get(origPlUrl, timeout = 15000L, headers = jsPlaylistHeaders, cookies = streamAllCookies)
                        }
                        val pl3Body = pl3Resp?.text
                        if (pl3Resp != null && pl3Resp.isSuccessful && pl3Body != null && pl3Body.contains("#EXTM3U")) {
                            Log.d("Tvenvivo", "Opción ${displayIndex + 1}: ¡M3U8 original!")
                            emitLink(displayIndex, origPlUrl, streamUrl, callback)
                            successfulOptionUrl[targetUrl] = rawPlayerUrl
                            return@withTimeout true
                        } else if (pl3Resp?.code != 403) {
                            Log.w("Tvenvivo", "Opción ${displayIndex + 1}: playlist original falló ${pl3Resp?.code ?: "timeout"}")
                        }
                    }

                    val m3u8FromStream = extractM3u8FromHtml(streamHtml, strict = false)
                    if (m3u8FromStream != null) {
                        val fullM3u8 = if (m3u8FromStream.startsWith("http")) m3u8FromStream else "$streamOrigin/$m3u8FromStream"
                        Log.d("Tvenvivo", "Opción ${displayIndex + 1}: m3u8 directo en HTML")
                        emitLink(displayIndex, fullM3u8, streamUrl, callback)
                        successfulOptionUrl[targetUrl] = rawPlayerUrl
                        return@withTimeout true
                    }
                } else {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: stream.php falló ${streamResp?.code ?: "timeout"}")
                }

                val jsPlaylistUrlForWv = Regex("""(?:var\s+src|source|file)\s*=\s*["']([^"']*playlist\.php[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(streamResp?.text ?: "")?.groupValues?.get(1)?.replace("\\/", "/")?.replace("&amp;", "&")
                    ?.let { if (it.startsWith("http")) it else "$streamOrigin/$it" }
                val playlistInfo = interceptPlaylistViaWebView(streamUrl, mainHeaders, canal, target, sig, streamOrigin, playlistUrl = playlistUrl, altPlaylistUrl = jsPlaylistUrlForWv)
                if (playlistInfo != null) {
                    if (playlistInfo.body.contains("#EXTM3U")) {
                        Log.d("Tvenvivo", "Opción ${displayIndex + 1}: ¡M3U8 desde WebView!")
                        emitLink(displayIndex, playlistInfo.url, streamUrl, callback)
                        successfulOptionUrl[targetUrl] = rawPlayerUrl
                        return@withTimeout true
                    } else {
                        Log.w("Tvenvivo", "Opción ${displayIndex + 1}: WebView sin m3u8: ${playlistInfo.body.take(200)}")
                    }
                } else {
                    Log.w("Tvenvivo", "Opción ${displayIndex + 1}: WebView sin resultado")
                }

                Log.w("Tvenvivo", "Opción ${displayIndex + 1}: sin enlaces")
                false
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("Tvenvivo", "Opción ${displayIndex + 1}: timeout")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Tvenvivo", "Opción ${displayIndex + 1}: ${e.message}")
            false
        }
    }

    private suspend fun emitLink(displayIndex: Int, url: String, referer: String, callback: (ExtractorLink) -> Unit, extraHeaders: Map<String, String> = emptyMap()) {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to referer
        ).apply { putAll(extraHeaders) }
        callback(
            newExtractorLink(
                source = this.name,
                name = "${this.name} - Opción ${displayIndex + 1}",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = headers
            }
        )
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.host.contains("saohgdassregions.com")) {
                    val builder = request.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "es-ES,es;q=0.9")
                    val referer = extractorLink.headers["Referer"]
                    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
                    val newRequest = builder.build()
                    val response = chain.proceed(newRequest)
                    if (response.code !in 200..299) {
                        Log.w("Tvenvivo", "Intercept ${response.code} → ${request.url.toString().take(130)}")
                    }
                    return response
                }
                return chain.proceed(request)
            }
        }
    }

    private data class PlaylistInfo(val url: String, val cookies: String = "", val body: String = "")

    private suspend fun interceptPlaylistViaWebView(
        pageUrl: String,
        mainHeaders: Map<String, String>,
        canal: String,
        target: String,
        sig: String,
        streamOrigin: String,
        playlistUrl: String,
        altPlaylistUrl: String? = null
    ): PlaylistInfo? {
        return withContext(Dispatchers.Main) {
            val appCtx = pluginContext?.applicationContext ?: return@withContext null
            var webView: WebView? = null
            var requestCount = 0
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                webView = WebView(appCtx)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
                }
                val infoDeferred = CompletableDeferred<PlaylistInfo?>()

                webView.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onResult(data: String) {
                        if (!infoDeferred.isCompleted) {
                            if (data.contains("#EXTM3U")) {

                                val urlMatch = Regex("""URL:([^|]+)""").find(data)?.groupValues?.get(1)
                                val actualUrl = urlMatch ?: playlistUrl
                                val bodyStart = data.indexOf("#EXTM3U")
                                val body = if (bodyStart >= 0) data.substring(bodyStart) else data.substringAfter("|", data).substringAfter("|", data)
                                Log.d("Tvenvivo", "WebView: ¡M3U8 capturado! (${body.length} bytes)")
                                infoDeferred.complete(PlaylistInfo(actualUrl, body = body))
                            } else if (data.startsWith("STATUS:403") || data.contains("STATUS:403") || data.startsWith("STATUS:0") || data.startsWith("TIMEOUT") || data.startsWith("XHR_ERROR")) {
                                Log.w("Tvenvivo", "WebView: playlist falló → ${data.take(120)}")
                            }
                        }
                    }
                }, "NativeBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!infoDeferred.isCompleted) {
                            mainHandler.postDelayed({
                                if (!infoDeferred.isCompleted) {
                                    val altUrl = altPlaylistUrl ?: ""
                                    val js = """
                                        (function() {
                                            function doXhr(url, isRetry) {
                                                try {
                                                    var xhr = new XMLHttpRequest();
                                                    xhr.open('GET', url, true);
                                                    xhr.withCredentials = true;
                                                    try { xhr.setRequestHeader('Accept', '*/*'); } catch(e) {}
                                                    try { xhr.setRequestHeader('Accept-Language', 'es-ES,es;q=0.9'); } catch(e) {}
                                                    xhr.timeout = 8000;
                                                    xhr.onreadystatechange = function() {
                                                        if (xhr.readyState === 4) {
                                                            var txt = xhr.responseText || '';
                                                            var label = isRetry ? 'RETRY:' : '';
                                                            NativeBridge.onResult(label + 'STATUS:' + xhr.status + '|URL:' + url + '|LEN:' + txt.length + '|' + txt.substring(0,600));
                                                            if (!isRetry && xhr.status === 403 && '${altUrl}'.length > 0 && '${altUrl}' !== url) {
                                                                setTimeout(function(){ doXhr('${altUrl}', true); }, 500);
                                                            }
                                                        }
                                                    };
                                                    xhr.onerror = function() {
                                                        NativeBridge.onResult((isRetry?'RETRY:':'') + 'XHR_ERROR:' + xhr.status + '|URL:' + url);
                                                        if (!isRetry && '${altUrl}'.length > 0 && '${altUrl}' !== url) {
                                                            setTimeout(function(){ doXhr('${altUrl}', true); }, 500);
                                                        }
                                                    };
                                                    xhr.ontimeout = function() { NativeBridge.onResult((isRetry?'RETRY:':'') + 'TIMEOUT|URL:' + url); };
                                                    xhr.send();
                                                } catch(e) {
                                                    NativeBridge.onResult('ERROR:' + e.toString());
                                                }
                                            }
                                            var primary = '${altUrl}'.length > 0 ? '${altUrl}' : '$playlistUrl';
                                            doXhr(primary, false);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(js, null)
                                }
                            }, 3000)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        requestCount++
                        return null
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e("Tvenvivo", "WebView: onReceivedError code=$errorCode desc=$description url=$failingUrl")
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                        val url = request?.url?.toString() ?: "?"
                        if (!url.contains("favicon.ico")) {
                            Log.w("Tvenvivo", "WebView: HTTP ${errorResponse?.statusCode} → ${url.take(100)}")
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.contains("tvenvivo2.com") || url.contains("javascript:")) {
                            return true
                        }
                        return false
                    }
                }

                webView.loadUrl(pageUrl)

                withTimeout(30000L) { infoDeferred.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w("Tvenvivo", "WebView: timeout 30s (requests intercepted=$requestCount)")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Tvenvivo", "WebView exception: ${e::class.simpleName}: ${e.message}")
                null
            } finally {
                try { webView?.destroy() } catch (_: Exception) {}
            }
        }
    }

    private fun extractM3u8FromHtml(html: String, strict: Boolean = true): String? {
        val patterns = if (strict) {
            listOf(
                """["'](https?[:\/\/\\]+[^"']+\.m3u8[^"']*)["']""",
                """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                """var\s+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']"""
            )
        } else {
            listOf(
                """["'](https?[:\/\/\\]+[^"']+\.m3u8[^"']*)["']""",
                """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                """var\s+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
                """(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""",
                """['"]([^"']+\.m3u8[^"']*)['"]"""
            )
        }

        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                val found = match.groupValues[1].replace("\\/", "/")
                return found
            }
        }

        try {
            val b64Regex = Regex("""window\[['"][^'"]+['"]\]\s*=\s*['"]([A-Za-z0-9+/=]{100,})['"]""")
            for (m in b64Regex.findAll(html)) {
                val b64 = m.groupValues[1]

                val decodedVariants = mutableListOf<String>()
                try { decodedVariants.add(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)) } catch (_: Exception) {}
                try { decodedVariants.add(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)) } catch (_: Exception) {}
                for (decoded in decodedVariants) {
                    val innerPatterns = listOf(
                        """(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""",
                        """['"]([^"']*\.m3u8[^"']*)['"]""",
                        """\.m3u8""",
                    )
                    for (ip in innerPatterns) {
                        val im = Regex(ip, RegexOption.IGNORE_CASE).find(decoded)
                        if (im != null) {
                            val found = if (ip.contains("https")) im.groupValues[1] else {
                                val ctxStart = maxOf(0, im.range.first - 100)
                                val ctxEnd = minOf(decoded.length, im.range.last + 100)
                                val ctx = decoded.substring(ctxStart, ctxEnd)
                                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").find(ctx)?.groupValues?.get(1)
                            }
                            if (found != null) return found.replace("\\/", "/")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val apiPatterns = listOf(
            """/api/[^"'\s]*\.m3u8[^"'\s]*""",
            """/stream/[^"'\s]*\.m3u8[^"'\s]*""",
            """/live/[^"'\s]*\.m3u8[^"'\s]*""",
        )
        for (ap in apiPatterns) {
            val m = Regex(ap, RegexOption.IGNORE_CASE).find(html)
            if (m != null) {
                val found = m.value
                if (found.startsWith("/")) return "https://regionales.saohgdassregions.com$found"
                if (found.startsWith("http")) return found
            }
        }
        return null
    }
}