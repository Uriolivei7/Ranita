package com.example

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.util.*
import kotlin.collections.ArrayList

class MonoschinosProvider : MainAPI() {
    companion object {
        private val TAG = "MonosChinos"
        fun getType(t: String): TvType {
            val lower = t.lowercase(Locale.ROOT)
            return if (lower.contains("ova") || lower.contains("especial")) TvType.OVA
            else if (lower.contains("película") || lower.contains("pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
        var pluginContext: Context? = null
    }

    override var mainUrl = "https://monoschinos.st"
    override var name = "MonosChinos"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.AnimeMovie,
            TvType.OVA,
            TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
                Pair("$mainUrl/emision", "Recientes"),
                Pair("$mainUrl/animes", "Animes"),
        )

        items.add(
                HomePageList(
                        "Últimos capítulos",
                        app.get(mainUrl, timeout = 120).document.select("a.card-wrap").map {
                            val title = it.selectFirst("h3.card-title")?.text() ?: ""
                            val poster = it.selectFirst("img.card-img")?.attr("data-src") ?: ""
                            val href = it.attr("href")
                            val url = href.replace("ver/", "anime/")
                                    .replace(Regex("episodio-\\d+"), "sub-espanol")
                            val epNum = Regex("""episodio-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                            newAnimeSearchResponse(title, url) {
                                this.posterUrl = fixUrl(poster)
                                addDubStatus(getDubStatus(title), epNum)
                            }
                        }, isHorizontalImages = true)
        )

        urls.amap { (url, name) ->

            val home = app.get(url, timeout = 120).document.select("a.card-wrap").map {
                val title = it.selectFirst("h3.card-title")?.text() ?: ""
                val poster = it.selectFirst("img.card-img")?.attr("data-src") ?: ""
                val year = it.selectFirst("div.mt-1 span")?.text()?.toIntOrNull()
                val typeBadge = it.select("div.absolute.top-2 span").firstOrNull()?.text() ?: it.select("div.absolute.top-2").firstOrNull()?.text() ?: ""

                newAnimeSearchResponse(title, fixUrl(it.attr("href")), getType(typeBadge)) {
                    this.posterUrl = fixUrl(poster)
                    this.year = year
                    addDubStatus(getDubStatus(title))
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            Log.d(TAG, "search: query='$query' encoded='$encodedQuery'")
            val resp = app.get("$mainUrl/buscar?q=$encodedQuery",
                timeout = 120,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            Log.d(TAG, "search: HTTP ${resp.code}, len=${resp.text.length}")
            val doc = resp.document
            val items = doc.select("a.card-wrap")
            Log.d(TAG, "search: found ${items.size} items")

            val results = items.mapNotNull { el ->
                try {
                    val title = el.selectFirst("h3.card-title")?.text() ?: return@mapNotNull null
                    val href = el.attr("href") ?: return@mapNotNull null
                    val image = el.selectFirst("img.card-img")?.attr("data-src") ?: el.selectFirst("img.card-img")?.attr("src") ?: ""
                    val year = el.selectFirst("div.mt-1 span")?.text()?.toIntOrNull()
                    val typeBadge = el.select("div.absolute.top-2 span").firstOrNull()?.text() ?: el.select("div.absolute.top-2").firstOrNull()?.text() ?: ""
                    Log.d(TAG, "search: item title='$title' href=$href year=$year type='$typeBadge'")
                    newAnimeSearchResponse(title, fixUrl(href), getType(typeBadge)) {
                        this.posterUrl = fixUrl(image)
                        this.year = year
                        addDubStatus(if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "search: item error: ${e.message}")
                    null
                }
            }
            Log.d(TAG, "search: returning ${results.size} results")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "search: failed: ${e.message}")
            return emptyList()
        }
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<Ep>? = null,
        @JsonProperty("caps") val caps: List<Ep>? = null,
    )

    data class Ep(
        @JsonProperty("num") val num: Int? = null,
        @JsonProperty("episodio") val episodio: Int? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: url=$url")
        val firstReq = app.get(url, timeout = 120)
        Log.d(TAG, "load: HTTP ${firstReq.code}, url final=${firstReq.url}")
        val doc = firstReq.document
        val cookies = firstReq.cookies
        val token = doc.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
        Log.d(TAG, "load: token='${token.take(20)}', cookies=${cookies.size}")
        latestCookie = cookies
        latestToken = token

        Log.d(TAG, "load: HTML title='${doc.title()}', body length=${doc.text().length}")
        Log.d(TAG, "load: .caplist found=${doc.selectFirst(".caplist") != null}")
        Log.d(TAG, "load: h1 found=${doc.selectFirst("h1") != null}")

        if (doc.selectFirst(".caplist") == null) {
            Log.e(TAG, "load: .caplist NO ENCONTRADO — posible bloqueo Cloudflare o cambio de HTML")
            Log.d(TAG, "load: HTML snippet (500 chars): ${doc.html().take(500)}")
            throw ErrorLoadingException("Cloudflare bloqueó el acceso o la página cambió de estructura")
        }

        val caplist = doc.selectFirst(".caplist")!!.attr("data-ajax")
        Log.d(TAG, "load: caplist url=$caplist")
        val poster = doc.selectFirst("img.lazy")?.attr("data-src") ?: doc.selectFirst("img[data-src]")?.attr("data-src") ?: ""
        val title = doc.selectFirst("h1")?.text() ?: doc.title()
        val type = doc.select("span.uppercase.text-brand").firstOrNull()?.text() ?: ""
        val description = doc.select("main p").firstOrNull()?.text() ?: ""
        val genres = doc.select("a[href^='/genero/']").map { it.text() }
        val status = when (doc.select("span.uppercase").firstOrNull()?.text()?.trim()) {
            "Finalizado" -> ShowStatus.Completed
            "En emisión" -> ShowStatus.Ongoing
            else -> null
        }
        val year = doc.select("dd.text-right").mapNotNull { dd ->
            Regex("(\\d{4})").find(dd.text())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()
        Log.d(TAG, "load: title='$title' type='$type' status=$status year=$year poster=$poster")

        val caplistHost = caplist.substringAfter("://").substringBefore("/")
        Log.d(TAG, "load: POST a caplist (host=$caplistHost) con token='${token.take(20)}' y ${cookies.size} cookies")
        val capJson = app.post(caplist,
                headers = mapOf(
                        "Host" to caplistHost,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to url,
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl,
                        "DNT" to "1",
                        "Alt-Used" to caplistHost,
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "TE" to "trailers"
                ),
                cookies = cookies,
               data = mapOf("_token" to token)).parsed<CapList>()
        Log.d(TAG, "load: POST OK, eps=${capJson.eps?.size ?: 0} caps=${capJson.caps?.size ?: 0}")

        val allEpisodes = capJson.eps ?: capJson.caps ?: emptyList()

        val epList = allEpisodes.map { ep ->
            val episodeNumber = ep.episodio ?: ep.num
            val epUrl = ep.url ?: "${url.replace("-sub-espanol","").replace("/anime/","/ver/")}-episodio-$episodeNumber"

            val thumbUrl = if (!ep.thumb.isNullOrBlank()) {
                if (ep.thumb.startsWith("http")) ep.thumb else "$mainUrl${ep.thumb}"
            } else {
                poster
            }

            newEpisode(epUrl) {
                this.episode = episodeNumber
                this.posterUrl = thumbUrl
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, epList)
            showStatus = status
            plot = description
            this.year = year
            tags = genres
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        app.get(data).document.select(".play-video").amap {
            val encodedurl = it.attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            val url = (urlDecoded).replace("https://monoschinos2.com/reproductor?url=", "")
                    .replace("https://sblona.com","https://watchsb.com").replace("https://swdyu.com","https://streamwish.to")

            val host = url.substringAfter("://").substringBefore("/")
            Log.d(TAG, "loadLinks: server $host -> $url")
            var serverEmitted = false
            val countingCallback: (ExtractorLink) -> Unit = { link ->
                serverEmitted = true
                callback(link)
            }
            when {
                host.contains("luluvdo") -> {
                    MonosLuluvdo().getUrl(url, mainUrl, subtitleCallback, countingCallback)
                }
                host.contains("mixdrop") -> {
                    MonosMixdrop().getUrl(url, mainUrl, subtitleCallback, countingCallback)
                }
                host.contains("voe") -> {
                    MonosVoe().getUrl(url, mainUrl, subtitleCallback, countingCallback)
                }
                host.contains("filemoon") || host.contains("byse") -> {
                    MonosFilemoon().getUrl(url, mainUrl, subtitleCallback, countingCallback)
                }
                host.contains("mega") -> {
                    Log.w(TAG, "loadLinks: mega.nz requiere proxy AES local, omitido: $host")
                }
                else -> {
                    val byseHandled = MonosFilemoon().tryResolveByseGeneric(url, mainUrl, subtitleCallback, countingCallback)
                    if (!byseHandled) {
                        loadExtractor(url, mainUrl, subtitleCallback, countingCallback)
                    }
                }
            }
            found = found || serverEmitted
        }
        return found
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val isCdn = url.contains("cdn-tnmr", ignoreCase = true) ||
                url.contains("mxcontent", ignoreCase = true) ||
                url.contains("cloudwindow-route", ignoreCase = true) ||
                url.contains(".urlset", ignoreCase = true) ||
                url.contains("abyss", ignoreCase = true) ||
                url.contains("filemoon", ignoreCase = true) ||
                url.contains(".sx/", ignoreCase = true) ||
                url.contains("/hls2/", ignoreCase = true) ||
                url.contains("/hls/", ignoreCase = true)
            if (!isCdn) return@Interceptor chain.proceed(request)

            val referer = when {
                url.contains("cdn-tnmr") -> "https://luluvdo.com/"
                url.contains("mxcontent") -> "https://mixdrop.top/"
                url.contains("cloudwindow-route") -> "https://eugenemakedraw.com/"
                else -> extractorLink.referer
            }
            Log.d(TAG, "[intercept] CDN request: ${url.take(120)} referer=$referer")
            val newRequest = request.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .header("Referer", referer)
                .header("Origin", referer.removeSuffix("/"))
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = chain.proceed(newRequest)
            Log.d(TAG, "[intercept] CDN response: ${response.code} ${response.header("content-type","?")} url=${url.take(100)}")
            response
        }
    }
}