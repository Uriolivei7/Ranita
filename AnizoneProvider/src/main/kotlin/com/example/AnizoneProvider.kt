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
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


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
    private var isLivewireInitializedOnce = false

    private val livewireInitMutex = Mutex()

    private suspend fun initializeLivewireGlobal() {
        livewireInitMutex.withLock {
            if (isLivewireInitializedOnce && !initialWireData["token"].isNullOrBlank() && !initialWireData["wireSnapshot"].isNullOrBlank()) {
                Log.d(name, "initializeLivewireGlobal: Livewire ya inicializado globalmente. Saltando.")
                return
            }

            Log.d(name, "initializeLivewireGlobal: Intentando inicializar Livewire globalmente...")
            try {
                val initReq = app.get(mainUrl)
                this.initialCookies = initReq.cookies.toMutableMap()
                val doc = initReq.document
                this.initialWireData["token"] = doc.select("script[data-csrf]").attr("data-csrf")
                this.initialWireData["wireSnapshot"] = getSnapshot(doc)

                isLivewireInitializedOnce = true
                Log.d(name, "initializeLivewireGlobal: Livewire inicializado globalmente exitosamente.")
            } catch (e: Exception) {
                isLivewireInitializedOnce = false
                Log.e(name, "initializeLivewireGlobal: Error durante la inicialización global de Livewire: ${e.message}", e)
                throw e
            }
        }
    }

    private fun getSnapshot(doc : Document) : String {
        return doc.selectFirst("div[wire:snapshot][wire:id]")
            ?.attr("wire:snapshot")?.replace("&quot;", "\"")
            ?: doc.select("main div[wire:snapshot]")
                .attr("wire:snapshot").replace("&quot;", "\"")
    }

    private fun getSnapshot(json : JSONObject) : String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    private suspend fun liveWireBuilder (
        updates : Map<String,String>,
        calls: List<Map<String, Any>>,
        currentCookies : MutableMap<String, String>,
        currentWireCreds : MutableMap<String,String>,
        remember : Boolean,
        retryCount: Int = 0
    ): JSONObject {
        val maxRetries = 2

        try {
            val currentToken = currentWireCreds["token"] ?: throw IllegalStateException("Livewire token is missing.")
            val currentSnapshot = currentWireCreds["wireSnapshot"] ?: throw IllegalStateException("Livewire snapshot is missing.")

            val payloadMap: Map<String, Any> = mapOf(
                "_token" to currentToken,
                "components" to listOf(
                    mapOf("snapshot" to currentSnapshot, "updates" to updates,
                        "calls" to calls
                    )
                )
            )

            val jsonPayloadString: String = payloadMap.toJson()
            Log.d(name, "liveWireBuilder: Payload JSON enviado (parcial): ${jsonPayloadString.take(200)}")

            val requestBody: RequestBody = jsonPayloadString.toRequestBody(
                "application/json".toMediaTypeOrNull() ?: "application/json".toMediaType()
            )

            val req = app.post(
                "$mainUrl/livewire/update",
                requestBody = requestBody,
                headers = mapOf("Content-Type" to "application/json"),
                cookies = currentCookies,
            )

            val responseText = req.text
            Log.d(name, "liveWireBuilder: Respuesta de Livewire (parcial, si es muy larga): ${responseText.take(500)}")

            if (!req.isSuccessful || responseText.contains("This page has expired", ignoreCase = true) ||
                responseText.contains("token mismatch", ignoreCase = true) ||
                !responseText.contains("\"snapshot\"")
            ) {
                Log.w(name, "liveWireBuilder: Posible token Livewire o snapshot expirado/inválido. Estado HTTP: ${req.code}. Reintentando (intento: ${retryCount + 1})...")
                if (retryCount < maxRetries) {
                    val freshState = initializeLivewireForOperation(mainUrl) // Re-inicializar desde la URL principal
                    currentCookies.clear()
                    currentCookies.putAll(freshState.first)
                    currentWireCreds.clear()
                    currentWireCreds.putAll(freshState.second)

                    return liveWireBuilder(updates, calls, currentCookies, currentWireCreds, remember, retryCount + 1)
                } else {
                    Log.e(name, "liveWireBuilder: Se excedió el número máximo de reintentos para Livewire. El token/snapshot sigue inválido o hay un problema persistente.")
                    throw IllegalStateException("Demasiados reintentos para Livewire, token/snapshot sigue inválido o hay un problema persistente.")
                }
            }

            val jsonResponse = JSONObject(responseText)

            if (remember) {
                val newSnapshot = try {
                    getSnapshot(jsonResponse)
                } catch (e: Exception) {
                    Log.e(name, "liveWireBuilder: No se pudo obtener el nuevo snapshot de la respuesta JSON, pero la respuesta parecía válida. ${e.message}", e)
                    throw e
                }
                currentWireCreds["wireSnapshot"] = newSnapshot
                currentCookies.putAll(req.cookies.toMutableMap())
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot actualizados (remember=true).")
            } else {
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot NO actualizados (remember=false).")
            }

            return jsonResponse
        } catch (e: Exception) {
            Log.e(name, "liveWireBuilder: Error general al ejecutar Livewire (no relacionado con token/snapshot directamente): ${e.message}", e)
            throw e
        }
    }

    private suspend fun initializeLivewireForOperation(url: String): Pair<MutableMap<String, String>, MutableMap<String, String>> {
        Log.d(name, "initializeLivewireForOperation: Obteniendo un nuevo estado Livewire para $url")
        val cookies = mutableMapOf<String, String>()
        val wireData = mutableMapOf<String, String>()
        try {
            val initReq = app.get(url)
            cookies.putAll(initReq.cookies)
            val doc = initReq.document
            wireData["token"] = doc.select("script[data-csrf]").attr("data-csrf")
            wireData["wireSnapshot"] = getSnapshot(doc)
            Log.d(name, "initializeLivewireForOperation: Nuevo estado Livewire obtenido exitosamente.")
        } catch (e: Exception) {
            Log.e(name, "initializeLivewireForOperation: Error al obtener estado Livewire: ${e.message}", e)
            throw e
        }
        return Pair(cookies, wireData)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val urlToFetch = mainUrl

            val initialReq = app.get(urlToFetch)
            val doc = initialReq.document

            var hasNextPage = false

            val results: List<SearchResponse> = when (request.data) {
                "latest_anime" -> {
                    Log.d(name, "getMainPage: Cargando la sección 'Últimos Animes' desde el index.")
                    doc.select("h2:contains(Latest Anime) + ul li[wire:key]").mapNotNull { toResult(it) }
                }
                "latest_episodes" -> {
                    Log.d(name, "getMainPage: Cargando la sección 'Últimos Episodios' desde el index.")
                    doc.select("h2:contains(Latest Episodes) + ul li").mapNotNull { toResultFromEpisodeElement(it) }
                }
                else -> {
                    Log.w(name, "getMainPage: Categoría desconocida: ${request.data}")
                    emptyList()
                }
            }

            Log.d(name, "getMainPage: Se encontraron ${results.size} resultados para ${request.name}.")
            return newHomePageResponse(
                HomePageList(request.name, results, isHorizontalImages = false),
                hasNext = hasNextPage
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
        Log.d(name, "search: Intentando búsqueda directa por URL para: $query")
        try {
            val (localCookies, localWireData) = initializeLivewireForOperation("$mainUrl/anime")
            Log.d(name, "search: Iniciando con estado Livewire fresco para búsqueda de '$query'.")

            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val searchUrlDirect = "$mainUrl/anime?search=$encodedQuery"
            val rDirect = app.get(searchUrlDirect)
            val docDirect = rDirect.document
            val directResults = docDirect.select("div[wire:key]").mapNotNull { toResult(it) }

            if (directResults.isNotEmpty()) {
                Log.d(name, "search: Búsqueda directa por URL exitosa, se encontraron ${directResults.size} resultados.")
                return directResults
            } else {
                Log.w(name, "search: Búsqueda directa por URL no encontró resultados o no es el método principal. Cayendo a búsqueda Livewire...")
            }

            val docLivewire = getHtmlFromWire(
                liveWireBuilder(
                    mutableMapOf("search" to query),
                    mutableListOf(),
                    localCookies,
                    localWireData,
                    true
                )
            )
            val resultsLivewire = docLivewire.select("div[wire:key]").mapNotNull { toResult(it) }
            Log.d(name, "search: Se encontraron ${resultsLivewire.size} resultados Livewire para '$query'.")
            if (resultsLivewire.isEmpty()) {
                Log.w(name, "search: Livewire no encontró resultados. Verificar el selector 'div[wire:key]' o la respuesta Livewire.")
                Log.d(name, "search: HTML devuelto por Livewire (parcial): ${docLivewire.html().take(1000)}")
            }
            return resultsLivewire
        } catch (e: Exception) {
            Log.e(name, "search: Error general al ejecutar la búsqueda: ${e.message}", e)
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val r = app.get(url)
            var doc = r.document

            val localCookies = r.cookies.toMutableMap()
            val localWireData = mutableMapOf(
                "wireSnapshot" to getSnapshot(doc=doc),
                "token" to doc.select("script[data-csrf]").attr("data-csrf")
            )
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

            var loadMoreCount = 0
            val maxLoadMoreRetries = 5
            while (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null && loadMoreCount < maxLoadMoreRetries) {
                Log.d(name, "load: Detectado 'Load More', intentando cargar más episodios. Intento: ${loadMoreCount + 1}")
                try {
                    val liveWireResponse = liveWireBuilder(
                        mutableMapOf(), mutableListOf(
                            mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                        ), localCookies, localWireData, true
                    )
                    doc = getHtmlFromWire(liveWireResponse)
                    loadMoreCount++
                } catch (e: Exception) {
                    Log.e(name, "load: Error al cargar más episodios con Livewire: ${e.message}", e)
                    break
                }
            }
            if (loadMoreCount >= maxLoadMoreRetries) {
                Log.w(name, "load: Se alcanzó el límite de carga de más episodios ($maxLoadMoreRetries veces). Podrían faltar episodios.")
            }

            val epiElms = doc.select("li[x-data]")
            Log.d(name, "load: Se encontraron ${epiElms.size} elementos de episodio después de cargar más.")

            val episodes = epiElms.mapNotNull{ elt ->
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
                        this.season = 1
                        this.posterUrl = elt.selectFirst("img")?.attr("src")

                        val potentialDateElements = elt.select("span.flex.items-center.gap-1")
                        var dateText: String? = null

                        val secondDateWrapper = potentialDateElements.getOrNull(1)
                        dateText = secondDateWrapper?.selectFirst("span.line-clamp-1")?.text()?.trim()

                        if (dateText.isNullOrBlank()) {
                            Log.w(name, "load: NO se encontró texto de fecha válido para el episodio. HTML del elemento padre: ${elt.html().take(500)}")
                            this.date = null
                        } else {
                            val rawDate = dateText
                            Log.d(name, "load: Raw date text found for episode: '$rawDate'")
                            var parsedTime: Long? = null
                            val dateFormats = listOf(
                                SimpleDateFormat("yyyy-MM-dd", Locale.US),
                                SimpleDateFormat("dd 'de' MMMM 'de'yyyy", Locale("es", "ES"))
                            )

                            for (format in dateFormats) {
                                try {
                                    parsedTime = format.parse(rawDate)?.time
                                    if (parsedTime != null) {
                                        Log.d(name, "load: Fecha '$rawDate' parseada exitosamente con formato: '${format.toPattern()}'")
                                        break
                                    }
                                } catch (e: Exception) {
                                }
                            }

                            if (parsedTime == null) {
                                Log.e(name, "load: No se pudo analizar la fecha '$rawDate' con ninguno de los formatos conocidos. Estableciendo fecha a null.")
                                this.date = null
                            } else {
                                this.date = parsedTime
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "load: Error al procesar elemento de episodio: ${e.message}. Elemento: ${elt.html().take(200)}", e)
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