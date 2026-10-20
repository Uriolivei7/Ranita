package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import kotlin.collections.ArrayList
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.Response

class DoramasytProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.Movie
            else TvType.TvSeries
        }
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }

        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""

        const val chrome127Ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://www.doramasyt.com"
    override var name = "DoramasYT"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private fun cleanPoster(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val cleaned = url.substringBefore("?v=")
        return fixUrl(cleaned)
    }

    private suspend fun getToken(url: String): Map<String, String> {
        try {
            val maintas = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val token = maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
            latestToken = token
            latestCookie = maintas.cookies
            Log.d("Doramasyt", "Token obtenido correctamente")
        } catch (e: Exception) {
            Log.e("Doramasyt", "Error obteniendo Token: ${e.message}")
        }
        return latestCookie
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        try {
            val response = app.get(mainUrl, timeout = 120)
            val latestChapters = response.document.select("h2:contains(últimos capítulos) + ul li.col article").mapNotNull { element ->
                val title = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val posterRaw = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

                val url = element.selectFirst("a")?.attr("href")
                    ?.replace("ver/", "dorama/")
                    ?.replace(Regex("-episodio-\\d+"), "-sub-espanol") ?: return@mapNotNull null

                newAnimeSearchResponse(title, fixUrl(url)) {
                    this.posterUrl = cleanPoster(posterRaw)
                    addDubStatus(getDubStatus(title))
                }
            }
            if (latestChapters.isNotEmpty()) {
                items.add(HomePageList("Últimos capítulos", latestChapters, true))
            }
        } catch (e: Exception) {
            Log.e("Doramasyt", "Error en Últimos Capítulos: ${e.message}")
        }

        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair("$mainUrl/doramas", "Doramas"),
            Pair("$mainUrl/doramas?categoria=pelicula", "Películas")
        )

        coroutineScope {
            urls.map { (url, name) ->
                async {
                    try {
                        val response = app.get(url)
                        val homeList = response.document.select("li.col").mapNotNull { element ->
                            val title = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                            val imgElement = element.selectFirst("img")
                            val posterRaw = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

                            newAnimeSearchResponse(title, fixUrl(element.selectFirst("a")!!.attr("href"))) {
                                this.posterUrl = cleanPoster(posterRaw)
                            }
                        }
                        if (homeList.isNotEmpty()) {
                            items.add(HomePageList(name, homeList, true))
                        }
                    } catch (e: Exception) {
                        Log.e("Doramasyt", "Error en sección $name: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("Doramasyt", "Buscando: $query")
        return app.get("$mainUrl/buscar?q=$query").document.select("li.col").map {
            val title = it.selectFirst("h3")!!.text()
            val posterRaw = it.selectFirst("img")?.attr("data-src")
            newAnimeSearchResponse(title, it.selectFirst("a")!!.attr("href")) {
                this.posterUrl = cleanPoster(posterRaw)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("Doramasyt", "Cargando dorama: $url")
        getToken(url)
        val doc = app.get(url, timeout = 120).document

        val title = doc.selectFirst(".fs-2")?.text() ?: ""
        val posterRaw = doc.selectFirst("img.rounded-3")?.attr("data-src")
        val backRaw = doc.selectFirst("img.w-100")?.attr("data-src")

        val caplistUrl = doc.selectFirst(".caplist")?.attr("data-ajax") ?: throw ErrorLoadingException("No se encontró lista de episodios")

        val capJson = app.post(caplistUrl,
            headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest"),
            cookies = latestCookie,
            data = mapOf("_token" to latestToken)).parsed<CapList>()

        val caps = capJson.paginateUrl?.let { pu ->
            try {
                app.post(pu,
                    headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest"),
                    cookies = latestCookie,
                    data = mapOf("_token" to latestToken)).parsed<CapList>().caps
            } catch (e: Exception) {
                Log.e("Doramasyt", "Error en paginate_url: ${e.message}")
                null
            }
        } ?: capJson.caps

        val epList = if (!caps.isNullOrEmpty()) {
            caps.map { cap ->
                val epUrl = cap.url?.takeIf { it.isNotBlank() } ?: buildEpisodeUrl(url, cap.episodio)

                newEpisode(epUrl) {
                    this.episode = cap.episodio
                    this.posterUrl = cleanPoster(cap.thumb)
                }
            }
        } else {
            capJson.eps?.map { ep ->
                newEpisode(buildEpisodeUrl(url, ep.num)) { this.episode = ep.num }
            } ?: emptyList()
        }

        return newAnimeLoadResponse(title, url, TvType.AsianDrama) {
            this.posterUrl = cleanPoster(posterRaw)
            this.backgroundPosterUrl = cleanPoster(backRaw)
            this.plot = doc.selectFirst("div.mb-3")?.text()?.replace("Ver menos", "")

            this.episodes = mutableMapOf(DubStatus.Subbed to epList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Doramasyt", "Solicitando links para: $data")
        try {
            val session = app.get(mainUrl)
            val cookies = session.cookies

            val response = app.get(data,
                cookies = cookies,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
            )

            if (response.document.select("h1").text().isNullOrEmpty()) {
                Log.e("Doramasyt", "Redirección detectada. No estamos en la página del episodio.")
            }

            val buttons = response.document.select("button[data-player], li[data-player], .play-video")
            Log.d("Doramasyt", "Botones encontrados: ${buttons.size}")

            val allCookies = cookies + response.cookies
            var found = false
            buttons.forEach { button ->
                val encoded = button.attr("data-player")
                val serverName = button.text().trim().ifBlank { "server" }
                if (encoded.isNotBlank()) {
                    val iframeUrl = "$mainUrl/reproductor?video=$encoded"

                    val iframeRes = app.get(iframeUrl,
                        cookies = allCookies,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    )

                    val finalUrl = iframeRes.document.selectFirst("iframe")?.attr("src")
                    if (!finalUrl.isNullOrEmpty()) {
                        Log.d("Doramasyt", "Link final ($serverName): $finalUrl")
                        if (tryByseExtraction(finalUrl, data, serverName, subtitleCallback, callback)) {
                            found = true
                        } else if (tryLuluExtraction(finalUrl, data, serverName, callback)) {
                            found = true
                        } else {
                            customLoadExtractor(fixUrl(finalUrl), iframeUrl, subtitleCallback, callback)
                            found = true
                        }
                    } else {
                        Log.w("Doramasyt", "Server '$serverName' sin iframe")
                    }
                }
            }
            return found
        } catch (e: Exception) {
            Log.e("Doramasyt", "Error en loadLinks: ${e.message}")
            return false
        }
    }

    private suspend fun tryByseExtraction(
        url: String,
        referer: String,
        serverName: String,
        sub: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("byse")) return false
        return try {
            val sources = ByseHttpExtractor().extract(url, referer, "https://${runCatching { java.net.URI(url).host }.getOrDefault("")}")
            Log.d("Doramasyt", "byse sources=${sources.size}")
            var found = false
            for (s in sources) {
                for (sb in s.subtitles) sub(sb)
                val isHls = s.url.contains(".m3u8")
                cb(newExtractorLink(name, "$serverName - Byse ${(s.label ?: "").trim()}".trim(), s.url,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = mapOf(
                        "User-Agent" to chrome127Ua,
                        "Referer" to referer,
                    )
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
            if (!found) Log.w("Doramasyt", "byse: 0 sources")
            found
        } catch (e: Exception) {
            Log.e("Doramasyt", "byse fallo: ${e.message}")
            false
        }
    }

    private val luluPackedRegex = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    private fun unpackLuluPacked(html: String): String? {
        val m = luluPackedRegex.find(html) ?: return null
        return try {
            val dict = m.groupValues[4].split("|")
            val base = m.groupValues[2].toIntOrNull() ?: 36
            val count = m.groupValues[3].toIntOrNull() ?: 0
            val sb = StringBuilder(m.groupValues[1])
            for (idx in count - 1 downTo 0) {
                val key = idx.toString(base)
                val value = dict.getOrElse(idx) { "" }
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    val replaced = Regex("\\b${Regex.escape(key)}\\b").replace(sb, Regex.escapeReplacement(value))
                    sb.clear()
                    sb.append(replaced)
                }
            }
            sb.toString().replace("\\'", "'")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryLuluExtraction(
        url: String,
        referer: String,
        serverName: String,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("lulu")) return false
        return try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to chrome127Ua,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to url,
            )).text
            Log.d("Doramasyt", "lulu GET len=${html.length}")

            val decoded = unpackLuluPacked(html)
            if (decoded == null) {
                Log.w("Doramasyt", "lulu: no packed JS (len=${html.length})")
                return false
            }

            val linkRegex = Regex("""(?:file|src|source)\s*:\s*["']((?:https?:)?//[^"']+\.(?:m3u8|mp4|txt)[^"']*)["']""", RegexOption.IGNORE_CASE)
            var emitted = 0
            for (m in linkRegex.findAll(decoded)) {
                var u = m.groupValues[1].replace("\\/", "/").trim()
                if (u.startsWith("//")) u = "https:$u"
                val type = if (u.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                Log.d("Doramasyt", "lulu emit ${u.take(110)}")
                cb(newExtractorLink(name, "$serverName - LuluStream", u, type) {
                    this.referer = "https://luluvdo.com/"
                    this.headers = mapOf("User-Agent" to chrome127Ua)
                })
                emitted++
            }

            if (emitted == 0) {
                val m3u8 = Regex("""(https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""", RegexOption.IGNORE_CASE).find(decoded)?.value
                if (m3u8 != null) {
                    Log.d("Doramasyt", "lulu emit (scan) ${m3u8.take(110)}")
                    cb(newExtractorLink(name, "$serverName - LuluStream", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://luluvdo.com/"
                        this.headers = mapOf("User-Agent" to chrome127Ua)
                    })
                    emitted++
                }
            }
            emitted > 0
        } catch (e: Exception) {
            Log.e("Doramasyt", "lulu fallo: ${e.message}")
            false
        }
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val url = extractorLink.url
        if (!(url.contains("tnmr") || url.contains("lulu"))) return null
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("User-Agent", chrome127Ua)
                    .removeHeader("Accept-Language")
                    .build()
                return chain.proceed(request)
            }
        }
    }

    private suspend fun customLoadExtractor(
        url: String,
        ref: String?,
        sub: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("filemoon.link", "filemoon.sx")

        Log.d("Doramasyt", "Llamando extractor para: $fixedUrl")
        try {
            loadExtractor(fixedUrl, ref, sub, cb)
        } catch (e: Exception) {
            Log.e("Doramasyt", "Extractor falló para $fixedUrl: ${e.message}")
        }
    }

    private fun buildEpisodeUrl(dramaUrl: String, ep: Int): String {
        var epUrl = "${dramaUrl.replace("-sub-espanol","").replace("/dorama/","/ver/")}-episodio-$ep"
        if (epUrl.contains("twinkling-watermelon")) {
            epUrl = epUrl.replace("twinkling-watermelon", "winkling-watermelon")
        }
        return epUrl
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<EpisodeJson>? = null,
        @JsonProperty("caps") val caps: List<CapDetail>? = null,
        @JsonProperty("paginate_url") val paginateUrl: String? = null,
    )

    data class EpisodeJson(
        @JsonProperty("num") val num: Int
    )

    data class CapDetail(
        @JsonProperty("episodio") val episodio: Int,
        @JsonProperty("thumb") val thumb: String?,
        @JsonProperty("url") val url: String? = null,
    )
}