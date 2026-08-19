package com.example

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log
import com.lagradost.cloudstream3.newSubtitleFile

class AnizoneProvider : MainAPI() {

    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val mainPage = mainPageOf(
        "2" to "Animes",
        "4" to "Películas",
        "6" to "Más Contenido"
    )
    private var cookies = mutableMapOf<String, String>()
    private var wireData = mutableMapOf(
        "wireSnapshot" to "",
        "token" to ""
    )
    private suspend fun initializeLiveWire(): Boolean {
        if (!wireData["wireSnapshot"].isNullOrBlank()) return true

        try {
            val initReq = app.get("$mainUrl/anime")

            val doc = initReq.document

            val csrfToken = doc.select("script[data-csrf]").attr("data-csrf")
            val snapshot = getSnapshot(doc)

            if (csrfToken.isBlank() || snapshot.isBlank()) {
                Log.e("AniZone Init", "Fallo la inicialización: token o snapshot vacíos. Esto puede ser un error de HTML/CAPTCHA en la página inicial.")
                return false
            }

            this.cookies = initReq.cookies.toMutableMap()
            wireData["token"] = csrfToken
            wireData["wireSnapshot"] = snapshot

            sortAnimeLatest()
            return true

        } catch (e: Exception) {
            Log.e("AniZone Init", "Error fatal durante initializeLiveWire: ${e.message}")
            return false
        }
    }

    private suspend fun sortAnimeLatest() {
        try {
            liveWireBuilder(mapOf("sort" to "release-desc"), mutableListOf(), this.cookies, this.wireData, true)
        } catch (e: Exception) {
            Log.e("AniZone Init", "Error al ejecutar sortAnimeLatest (Livewire): ${e.message}")
        }
    }


    private fun getSnapshot(doc : Document) : String {
        return doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot").replace("&quot;", "\"")
    }
    private fun getSnapshot(json : JSONObject) : String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    private  fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    private suspend fun liveWireBuilder (
        updates : Map<String,String>, calls: List<Map<String, Any>>,
        biscuit : MutableMap<String, String>,
        wireCreds : MutableMap<String,String>,
        remember : Boolean): JSONObject {

        val payload = mapOf(
            "_token" to wireCreds["token"], "components" to listOf(
                mapOf("snapshot" to wireCreds["wireSnapshot"], "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val req = app.post(
            url = "$mainUrl/livewire/update",
            json = payload,
            headers = mapOf(
                "X-CSRF-TOKEN" to wireCreds["token"]!!
            ),
            cookies = biscuit,
            referer = "$mainUrl/anime"
        )

        val bodyString = req.text

        if (bodyString.isBlank()) {
            throw Exception("Respuesta Livewire vacía o en blanco (HTTP ${req.code}).")
        }

        if (bodyString.trim().startsWith("<!DOCTYPE", ignoreCase = true) ||
            bodyString.trim().startsWith("<html", ignoreCase = true)) {
            Log.e("AniZone", "Respuesta inesperada: Recibido HTML/<!DOCTYPE en lugar de JSON. El sitio podría estar bloqueando el acceso o mostrando un CAPTCHA/error.")

            throw Exception("Livewire no devolvió JSON. Código de estado HTTP: ${req.code}. URL: ${req.url}")
        }

        val responseJson = JSONObject(bodyString)

        if (remember) {
            wireCreds["wireSnapshot"] = getSnapshot(responseJson)
            biscuit.putAll(req.cookies)
        }

        return responseJson
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest
    ): HomePageResponse {

        val initialized = initializeLiveWire()
        if (!initialized) {
            Log.w("AniZone", "Inicialización LiveWire fallida. Retornando lista de inicio vacía.")
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
        try {
            var responseJson = liveWireBuilder(
                mapOf("type" to request.data), mutableListOf(), this.cookies, this.wireData, true
            )
            var doc = getHtmlFromWire(responseJson)
            var items = parseItemsJson(findItemsXData(doc) ?: "")

            for (i in 1 until page) {
                val xData = findItemsXData(doc)
                val nextCursor = extractNextCursor(xData)
                if (nextCursor.isBlank() || !extractHasMore(xData)) break

                responseJson = liveWireBuilder(
                    mutableMapOf(), mutableListOf(
                        mapOf("path" to "", "method" to "loadPage", "params" to listOf(nextCursor))
                    ), this.cookies, this.wireData, true
                )
                val dispatchParams = getItemsLoadedParams(responseJson) ?: break
                val newItems = dispatchParams.optJSONArray("items")
                items = if (newItems != null) {
                    (0 until newItems.length()).map { newItems.getJSONObject(it) }
                } else break
            }

            return newHomePageResponse(
                HomePageList(request.name, items.mapNotNull { toResult(it) }, isHorizontalImages = false),
                hasNext = extractHasMore(findItemsXData(doc))
            )
        } catch (e: Exception) {
            Log.e("AniZone", "Fallo al procesar LiveWire en getMainPage: ${e.message}")
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            try { sb.append(hex.toInt(16).toChar()) } catch (e: Exception) { sb.append(n) }
                            i += 6
                        } else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun parseItemsJson(xData: String): List<JSONObject> {
        val raw = Regex("items:\\s*JSON\\.parse\\('(.*?)'\\)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(xData)?.groupValues?.getOrNull(1) ?: return emptyList()
        val jsonText = unescapeJsString(raw)
        return try {
            val arr = JSONArray(jsonText)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.e("AniZone", "parseItemsJson error: ${e.message}")
            emptyList()
        }
    }

    private fun findItemsXData(doc: Document): String? {
        return doc.select("[x-data]").firstOrNull { it.attr("x-data").contains("items: JSON.parse") }
            ?.attr("x-data")
    }

    private fun extractNextCursor(xData: String?): String {
        if (xData.isNullOrBlank()) return ""
        return Regex("nextCursor:\\s*'([^']*)'").find(xData)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractHasMore(xData: String?): Boolean {
        if (xData.isNullOrBlank()) return false
        return Regex("hasMore:\\s*(true|false)").find(xData)?.groupValues?.getOrNull(1) == "true"
    }

    private fun getItemsLoadedParams(json: JSONObject): JSONObject? {
        return try {
            val effects = json.getJSONArray("components").getJSONObject(0).optJSONObject("effects") ?: return null
            val dispatches = effects.optJSONArray("dispatches") ?: return null
            for (i in 0 until dispatches.length()) {
                val d = dispatches.getJSONObject(i)
                if (d.optString("name") == "items-loaded") return d.optJSONObject("params")
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun toResult(item: JSONObject): SearchResponse? {
        val url = item.optString("url").ifBlank { return null }
        val titleList = item.optJSONObject("title_list")
        val title = titleList?.optString("1")?.takeIf { it.isNotBlank() }
            ?: item.optString("main_title").trim('"')
        if (title.isBlank()) return null
        val type = if (item.optString("type") == "Movie") TvType.AnimeMovie else TvType.Anime
        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = item.optString("cover").ifBlank { null }
            this.year = item.optInt("start_year", 0).takeIf { it > 0 }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("AniZone", "search: query='$query'")
        val doc = app.get("$mainUrl/anime?search=$query").document
        val items = findItemsXData(doc)?.let { parseItemsJson(it) } ?: emptyList()
        Log.d("AniZone", "search: found ${items.size} items via URL x-data")
        return items.mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("AniZone", "load: url='$url'")
        val req = app.get(url)
        val doc = req.document
        val cookie = req.cookies.toMutableMap()
        Log.d("AniZone", "load: HTTP ${req.code}, doc title='${doc.title()}'")
        val wireData = mutableMapOf(
            "wireSnapshot" to getSnapshot(doc),
            "token" to doc.select("script[data-csrf]").attr("data-csrf")
        )
        val title = doc.title()
            .substringBefore(" — ")
            .ifBlank { doc.title() }
        Log.d("AniZone", "load: extracted title='$title'")
        val bgImage = doc.selectFirst("main img")?.attr("src")
        val synopsis = doc.selectFirst(".sr-only + div")?.text() ?: ""
        val rowLines = doc.select("span.inline-block").map { it.text() }
        val releasedYear = rowLines.getOrNull(3)
        val status = if (rowLines.getOrNull(1) == "Completed") ShowStatus.Completed
        else if (rowLines.getOrNull(1) == "Ongoing") ShowStatus.Ongoing else null
        val genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }

        val imdbLink = doc.selectFirst("a[href*='imdb.com']")

        val imdbId = imdbLink?.attr("href")
            ?.substringAfter("title/")
            ?.trimEnd('/', ' ', '?')
            ?.let {
                if (it.startsWith("tt") && it.length > 2) it else "tt0000000"
            }
            ?: "tt0000000"

        Log.d("AniZoneIMDB", "IMDB ID encontrado en LOAD: $imdbId")

        var currentDoc = doc
        val maxAttempts = 100
        var page = 1

        val allEpiElms = mutableListOf<Element>()
        allEpiElms.addAll(currentDoc.select("li[x-data]"))

        while (page < maxAttempts) {
            val responseJson = try {
                liveWireBuilder(
                    mapOf("paginators.page" to "${page + 1}"), mutableListOf(), cookie, wireData, true
                )
            } catch (e: Exception) {
                Log.e("AniZone Load", "Error al paginar episodios (página ${page + 1}): ${e.message}")
                break
            }
            val nextDoc = getHtmlFromWire(responseJson)
            val newEpiElms = nextDoc.select("li[x-data]")
            if (newEpiElms.isEmpty()) break
            allEpiElms.addAll(newEpiElms)
            currentDoc = nextDoc
            page++
        }

        val epiElms = allEpiElms

        val episodes = epiElms.map{ elt ->
            newEpisode(
                data = elt.selectFirst("a")?.attr("href") ?: "") {
                this.name = elt.selectFirst("h3")?.text()
                    ?.substringAfter(":")?.trim()
                this.season = 0
                this.posterUrl = elt.selectFirst("img")?.attr("src")
                this.data = "${elt.selectFirst("a")?.attr("href")}|||$imdbId"

                this.date = elt.selectFirst("span[title]")
                    ?.selectFirst("span.line-clamp-1")
                    ?.text()
                    ?.trim()
                    ?.replace(Regex("\\s+"), "")
                    ?.ifEmpty { null }
                    ?.let { dateText ->
                        Log.d("AniZone", "Fecha encontrada para ${this.name}: $dateText")

                        try {
                            val parsedTime = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(dateText)?.time
                            Log.d("AniZone", "Parseo exitoso para ${this.name}: $parsedTime")
                            parsedTime
                        } catch (e: Exception) {
                            Log.e("AniZone", "FALLO de parseo para ${this.name} con texto '$dateText': ${e.message}")
                            null
                        }
                    } ?: 0L
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = bgImage
            this.plot = synopsis
            this.tags = genres
            this.year = releasedYear?.toIntOrNull()
            this.showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|||")
        val episodeUrl = parts[0]

        Log.d("AniZoneSub", "-> Iniciando loadLinks para: $episodeUrl")

        val webReq = app.get(episodeUrl)
        val web = webReq.document
        val cookie = webReq.cookies
        val sourceName = web.selectFirst("div:containsOwn(Source:)")?.nextElementSibling()?.text() ?: "Web"
        val playerData = web.select("[x-data]").firstOrNull { it.attr("x-data").contains("vidstackPlayer") }
            ?.attr("x-data") ?: return false
        val rawJson = Regex("vidstackPlayer\\(JSON\\.parse\\('(.*?)'\\)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(playerData)?.groupValues?.getOrNull(1) ?: return false
        val masterUrl = try {
            JSONObject(unescapeJsString(rawJson)).optString("src")
        } catch (e: Exception) {
            Log.e("AniZoneSub", "Error parseando vidstackPlayer JSON: ${e.message}")
            ""
        }

        Log.d("AniZoneSub", "-> Source: $sourceName, M3U8: $masterUrl")

        if (masterUrl.isBlank()) return false

        try {
            val subs = JSONObject(unescapeJsString(rawJson)).optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val s = subs.getJSONObject(i)
                    val file = s.optString("file").ifBlank { continue }
                    Log.d("AniZoneSub", "-> [AniZone] Subtítulo encontrado: ${s.optString("title")}")
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            s.optString("title").ifBlank { s.optString("language") },
                            file
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("AniZoneSub", "Error parseando subtítulos: ${e.message}")
        }

        val baseHeaders = mapOf(
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Cookie" to cookie.map { "${it.key}=${it.value}" }.joinToString("; ")
        )

        callback.invoke(
            newExtractorLink(
                sourceName,
                name,
                masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = episodeUrl
                this.quality = 0
                this.headers = baseHeaders
            }
        )
        return true
    }
}