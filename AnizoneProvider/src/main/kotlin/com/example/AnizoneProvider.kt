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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Connection
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log

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
        "latest_anime" to "Últimos Animes",
        "latest_episodes" to "Últimos Episodios"
    )

    private var initialCookies = mutableMapOf<String, String>()
    private var initialWireData = mutableMapOf(
        "wireSnapshot" to "",
        "token" to ""
    )

    init {
        try {
            val initReq = Jsoup.connect("$mainUrl/anime")
                .method(Connection.Method.GET)
                .execute()
            this.initialCookies = initReq.cookies().toMutableMap()
            val doc = initReq.parse()
            initialWireData["token"] = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
                ?: doc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: ""
            initialWireData["wireSnapshot"] = getSnapshot(doc)
            Log.d(name, "init: Livewire inicializado globalmente con token: ${initialWireData["token"]}")
        } catch (e: Exception) {
            Log.e(name, "init: Error durante la inicialización global de Livewire: ${e.message}", e)
        }
    }

    private suspend fun sortAnimeLatest() {
        liveWireBuilder(
            mapOf("sort" to "release-desc"),
            mutableListOf(),
            initialCookies,
            initialWireData,
            true
        )
    }

    private fun getSnapshot(doc: Document): String {
        return doc.selectFirst("div[wire:snapshot][wire:id]")
            ?.attr("wire:snapshot")?.replace("&quot;", "\"")
            ?: doc.selectFirst("main div[wire:snapshot]")
                ?.attr("wire:snapshot")?.replace("&quot;", "\"") ?: ""
    }

    private fun getSnapshot(json: JSONObject): String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    private suspend fun liveWireBuilder(
        updates: Map<String, String>,
        calls: List<Map<String, Any>>,
        currentCookies: MutableMap<String, String>,
        currentWireCreds: MutableMap<String, String>,
        remember: Boolean
    ): JSONObject {
        val maxRetries = 2
        var attempt = 0
        var lastException: Exception? = null

        while (attempt < maxRetries) {
            try {
                val currentToken = currentWireCreds["token"] ?: throw IllegalStateException("Livewire token is missing.")
                val currentSnapshot = currentWireCreds["wireSnapshot"] ?: throw IllegalStateException("Livewire snapshot is missing.")

                val payloadMap: Map<String, Any> = mapOf(
                    "_token" to currentToken,
                    "components" to listOf(
                        mapOf("snapshot" to currentSnapshot, "updates" to updates, "calls" to calls)
                    )
                )

                val jsonPayloadString: String = payloadMap.toJson()
                Log.d(name, "liveWireBuilder: Intento $attempt - Payload JSON enviado: ${jsonPayloadString.take(200)}")

                val req = Jsoup.connect("$mainUrl/livewire/update")
                    .method(Connection.Method.POST)
                    .header("Content-Type", "application/json")
                    .header("X-Livewire", "true")
                    .header("X-CSRF-TOKEN", currentToken)
                    .header("Accept", "application/json")
                    .cookies(currentCookies)
                    .ignoreContentType(true)
                    .requestBody(jsonPayloadString)
                    .execute()

                val responseText = req.body()
                Log.d(name, "liveWireBuilder: Respuesta de Livewire (parcial): ${responseText.take(500)}")

                if (req.statusCode() == 500) {
                    throw org.jsoup.HttpStatusException("HTTP error fetching URL", 500, "$mainUrl/livewire/update")
                }

                val jsonResponse = JSONObject(responseText)

                if (remember) {
                    val newSnapshot = try {
                        getSnapshot(jsonResponse)
                    } catch (e: Exception) {
                        Log.e(name, "liveWireBuilder: No se pudo obtener el nuevo snapshot: ${e.message}", e)
                        throw e
                    }
                    currentWireCreds["wireSnapshot"] = newSnapshot
                    currentCookies.putAll(req.cookies())
                    Log.d(name, "liveWireBuilder: Cookies y wireSnapshot actualizados (remember=true).")
                } else {
                    Log.d(name, "liveWireBuilder: Cookies y wireSnapshot NO actualizados (remember=false).")
                }

                return jsonResponse
            } catch (e: Exception) {
                Log.w(name, "liveWireBuilder: Error en el intento $attempt: ${e.message}", e)
                lastException = e
                attempt++

                if (attempt < maxRetries) {
                    try {
                        val refreshReq = Jsoup.connect("$mainUrl/anime")
                            .method(Connection.Method.GET)
                            .cookies(currentCookies)
                            .execute()
                        currentCookies.putAll(refreshReq.cookies())
                        val doc = refreshReq.parse()
                        currentWireCreds["token"] = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
                            ?: doc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: ""
                        currentWireCreds["wireSnapshot"] = getSnapshot(doc)
                        Log.d(name, "liveWireBuilder: Token y snapshot refrescados para el intento $attempt: ${currentWireCreds["token"]}")
                    } catch (refreshEx: Exception) {
                        Log.e(name, "liveWireBuilder: Error al refrescar token y snapshot: ${refreshEx.message}", refreshEx)
                    }
                }
            }
        }

        Log.e(name, "liveWireBuilder: Falló después de $maxRetries intentos.", lastException)
        throw lastException ?: IllegalStateException("No se pudo ejecutar liveWireBuilder después de $maxRetries intentos.")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            if (page == 1) {
                sortAnimeLatest()
            }

            val doc = getHtmlFromWire(
                liveWireBuilder(
                    mapOf("type" to request.data),
                    mutableListOf(
                        mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                    ),
                    initialCookies,
                    initialWireData,
                    true
                )
            )

            val home = doc.select("div[wire:key]").toList()
            val paginatedHome = if (page > 1) home.takeLast(12) else home

            Log.d(name, "getMainPage: Se encontraron ${paginatedHome.size} resultados para ${request.name}.")
            return newHomePageResponse(
                HomePageList(request.name, paginatedHome.mapNotNull { toResult(it) }, isHorizontalImages = false),
                hasNext = doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage: Error al cargar la página principal: ${e.message}", e)
            throw e
        }
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("img")?.attr("alt") ?: ""
        val url = post.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = post.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun toResultFromEpisodeElement(episodeElement: Element): SearchResponse {
        val animeTitle = episodeElement.selectFirst("div.line-clamp-1 > a.title")?.attr("title")
            ?: episodeElement.selectFirst("div.line-clamp-1 > a.title")?.text() ?: ""
        val animeUrl = episodeElement.selectFirst("div.line-clamp-1 > a.title")?.attr("href") ?: ""
        val posterUrl = episodeElement.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(animeTitle, animeUrl, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch: Ejecutando búsqueda rápida para: $query")
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search: Ejecutando búsqueda para: $query")
        try {
            val docLivewire = getHtmlFromWire(
                liveWireBuilder(
                    mutableMapOf("search" to query),
                    mutableListOf(),
                    initialCookies,
                    initialWireData,
                    true
                )
            )
            val results = docLivewire.select("div[wire:key]").toList().mapNotNull { toResult(it) }
            Log.d(name, "search: Se encontraron ${results.size} resultados Livewire para '$query'.")
            if (results.isEmpty()) {
                Log.w(name, "search: Livewire no encontró resultados. HTML devuelto (parcial): ${docLivewire.html().take(1000)}")
            }
            return results
        } catch (e: Exception) {
            Log.e(name, "search: Error al ejecutar la búsqueda: ${e.message}", e)
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val r = app.get(url)
            var doc = r.document

            val localCookies = r.cookies.toMutableMap()
            val localWireData = mutableMapOf<String, String>()
            localWireData["wireSnapshot"] = getSnapshot(doc=doc)
            localWireData["token"] = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
                ?: doc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: ""
            Log.d(name, "load: Cargando URL: $url, wireData local inicial: $localWireData")

            val title = doc.selectFirst("h1")?.text()
                ?: throw NotImplementedError("Unable to find title")

            val bgImage = doc.selectFirst("main img")?.attr("src")
            val synopsis = doc.selectFirst(".sr-only + div")?.text() ?: ""

            val rowLines = doc.select("span.inline-block").map { it.text() }
            val releasedYear = rowLines.getOrNull(3)
            val status = if (rowLines.getOrNull(1) == "Completed") ShowStatus.Completed
            else if (rowLines.getOrNull(1) == "Ongoing") ShowStatus.Ongoing else null

            val genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }

            while (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null) {
                Log.d(name, "load: Detectado 'Load More', intentando cargar más episodios.")
                try {
                    val liveWireResponse = liveWireBuilder(
                        mutableMapOf(),
                        mutableListOf(
                            mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                        ),
                        localCookies,
                        localWireData,
                        true
                    )
                    doc = getHtmlFromWire(liveWireResponse)
                } catch (e: Exception) {
                    Log.w(name, "load: Error al cargar más episodios con Livewire: ${e.message}. Continuando con episodios disponibles.", e)
                    break
                }
            }

            val epiElms = doc.select("li[x-data]").toList()
            Log.d(name, "load: Se encontraron ${epiElms.size} elementos de episodio después de cargar más.")

            val episodes = epiElms.mapNotNull { elt ->
                try {
                    newEpisode(
                        data = elt.selectFirst("a")?.attr("href") ?: run {
                            Log.w(name, "load: Elemento de episodio sin URL de enlace: ${elt.html().take(200)}")
                            return@mapNotNull null
                        }
                    ) {
                        this.name = elt.selectFirst("h3")?.text()
                            ?.substringAfter(":")?.trim() ?: run {
                            Log.w(name, "load: Episodio sin nombre: ${elt.html().take(200)}")
                            null
                        }
                        this.season = 0
                        this.posterUrl = elt.selectFirst("img")?.attr("src")

                        val dateText = elt.select("span.line-clamp-1").getOrNull(1)?.text()?.trim()
                        if (dateText.isNullOrBlank()) {
                            Log.w(name, "load: No se encontró texto de fecha válido para el episodio.")
                            this.date = null
                        } else {
                            val rawDate = dateText
                            Log.d(name, "load: Fecha cruda encontrada: '$rawDate'")
                            this.date = try {
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(rawDate)?.time
                            } catch (e: Exception) {
                                Log.w(name, "load: No se pudo parsear la fecha '$rawDate'. Estableciendo fecha a null.")
                                null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "load: Error al procesar episodio: ${e.message}. Elemento: ${elt.html().take(200)}", e)
                    null
                }
            }
            Log.d(name, "load: Se procesaron ${episodes.size} episodios válidos.")

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = bgImage
                this.plot = synopsis
                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.showStatus = status
                addEpisodes(DubStatus.None, episodes)
            }
        } catch (e: Exception) {
            Log.e(name, "load: Error al cargar los detalles del anime: ${e.message}", e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val web = app.get(data).document
            val sourceName = web.selectFirst("span.truncate")?.text() ?: "Anizone"
            val mediaPlayer = web.selectFirst("media-player")

            val m3U8 = mediaPlayer?.attr("src")
            if (m3U8.isNullOrEmpty()) {
                Log.w(name, "loadLinks: No se encontró la fuente M3U8 en media-player para $data")
                return false
            }

            mediaPlayer?.select("track")?.forEach { trackElement ->
                val label = trackElement.attr("label")
                val src = trackElement.attr("src")
                if (label.isNotBlank() && src.isNotBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(label, src)
                    )
                    Log.d(name, "loadLinks: Subtítulo encontrado: $label, URL: $src")
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = name,
                    url = m3U8,
                    type = ExtractorLinkType.M3U8
                )
            )
            Log.d(name, "loadLinks: Enlace extractor añadido para $sourceName: $m3U8")
            return true
        } catch (e: Exception) {
            Log.e(name, "loadLinks: Error al cargar enlaces para $data: ${e.message}", e)
            return false
        }
    }
}