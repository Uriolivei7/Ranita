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

    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "2" to "Últimas Series TV",
        "4" to "Últimas Películas",
        "6" to "Últimas Web"
    )

    // Estas variables de instancia serán inicializadas una vez,
    // pero las funciones que usan Livewire ahora pasarán copias mutables para trabajar localmente.
    // Aunque se mantendrán como variables de instancia para el mutext y la referencia inicial.
    private var initialCookies = mutableMapOf<String, String>()
    private var initialWireData = mutableMapOf(
        "wireSnapshot" to "",
        "token" to ""
    )
    private var isLivewireInitializedOnce = false // Indica si la inicialización inicial ha ocurrido.

    // Mutex para sincronizar el acceso a la inicialización de Livewire (solo la primera vez que se accede)
    private val livewireInitMutex = Mutex()

    /**
     * Inicializa las credenciales de Livewire (cookies, token, snapshot) desde la página principal.
     * Esta función solo se ejecutará realmente una vez. Las llamadas posteriores usarán los datos cacheados.
     * Si necesitas una inicialización "fresca" para cada operación (getMainPage, search, load),
     * entonces los datos de `cookies` y `wireData` deben ser gestionados localmente por esas funciones.
     */
    private suspend fun initializeLivewireGlobal() {
        livewireInitMutex.withLock {
            if (isLivewireInitializedOnce && !initialWireData["token"].isNullOrBlank() && !initialWireData["wireSnapshot"].isNullOrBlank()) {
                Log.d(name, "initializeLivewireGlobal: Livewire ya inicializado globalmente. Saltando.")
                return // Ya inicializado globalmente, salimos
            }

            Log.d(name, "initializeLivewireGlobal: Intentando inicializar Livewire globalmente...")
            try {
                val initReq = app.get("$mainUrl/anime")
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

    /**
     * Función auxiliar para obtener el snapshot de Livewire de un documento Jsoup.
     */
    private fun getSnapshot(doc : Document) : String {
        return doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot").replace("&quot;", "\"")
    }

    /**
     * Función auxiliar para obtener el snapshot de Livewire de un objeto JSON de respuesta.
     */
    private fun getSnapshot(json : JSONObject) : String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    /**
     * Función auxiliar para obtener el HTML de la respuesta JSON de Livewire.
     */
    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    /**
     * Constructor principal para las peticiones Livewire.
     * Ahora recibe `currentCookies` y `currentWireCreds` para trabajar con un estado local,
     * en lugar de modificar variables de instancia globales directamente.
     *
     * @param updates Un mapa de actualizaciones de estado para el componente Livewire.
     * @param calls Una lista de llamadas a métodos de Livewire.
     * @param currentCookies El mapa de cookies actual que se enviará con la petición. Será modificado por referencia si `remember` es true.
     * @param currentWireCreds El mapa que contiene wireSnapshot y _token. Será modificado por referencia si `remember` es true.
     * @param remember Si es true, actualizará `currentWireCreds` y `currentCookies` con la nueva respuesta.
     * @param retryCount Contador de reintentos para evitar bucles infinitos.
     * @return El objeto JSONObject de la respuesta de Livewire.
     * @throws IllegalStateException Si el token o snapshot faltan después de la inicialización, o si se exceden los reintentos.
     * @throws Exception Si ocurre un error de red o de parsing.
     */
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
            // Asegurarse de que las credenciales no sean nulas antes de usarlas
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
                // Forzar la re-inicialización para el siguiente intento
                if (retryCount < maxRetries) {
                    val freshState = initializeLivewireForOperation(mainUrl) // Obtener un nuevo estado fresco
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
                currentCookies.putAll(req.cookies.toMutableMap()) // Actualizar las cookies con las últimas
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

    /**
     * Función para obtener un estado de Livewire fresco para una operación.
     * Devuelve un par: (cookies, wireData)
     */
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
            // Obtener un estado Livewire fresco para esta operación de página principal
            val (localCookies, localWireData) = initializeLivewireForOperation("$mainUrl/anime")
            Log.d(name, "getMainPage: Iniciando con estado Livewire fresco para ${request.name}.")

            // Realizar la primera llamada para obtener la página principal o aplicar el tipo
            var initialLivewireResponse = liveWireBuilder(
                mutableMapOf("type" to request.data),
                mutableListOf(),
                localCookies, // Usar las cookies locales
                localWireData, // Usar los datos de Livewire locales
                true // Recordar para futuras llamadas dentro de esta operación
            )
            var doc = getHtmlFromWire(initialLivewireResponse)
            var home: List<Element> = doc.select("div[wire:key]")

            // Manejo de paginación si se solicita una página mayor a 1
            if (page > 1) {
                Log.w(name, "getMainPage: Paginación para página principal, intentando cargar más páginas. Página solicitada: $page")
                // Iterar para cargar las páginas adicionales
                for (i in 1 until page) {
                    val loadMoreResponse = liveWireBuilder(
                        mutableMapOf(),
                        mutableListOf(mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())),
                        localCookies, // Seguir usando las cookies locales
                        localWireData, // Seguir usando los datos de Livewire locales
                        true
                    )
                    val newDoc = getHtmlFromWire(loadMoreResponse)
                    val newElements = newDoc.select("div[wire:key]")
                    if (newElements.isEmpty()) {
                        Log.w(name, "getMainPage: No se encontraron más elementos al cargar la página ${i + 1}.")
                        break
                    }
                    home = newElements // Reemplazar con los elementos de la nueva página.
                }
            }


            Log.d(name, "getMainPage: Se encontraron ${home.size} resultados para ${request.name}.")
            return newHomePageResponse(
                HomePageList(request.name, home.map { toResult(it)}, isHorizontalImages = false),
                hasNext = (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null)
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

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch: Ejecutando búsqueda rápida para: $query")
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search: Intentando búsqueda directa por URL para: $query")
        try {
            // Obtener un estado Livewire fresco para esta operación de búsqueda
            val (localCookies, localWireData) = initializeLivewireForOperation("$mainUrl/anime")
            Log.d(name, "search: Iniciando con estado Livewire fresco para búsqueda de '$query'.")


            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            // Intentar una búsqueda directa por URL primero, ya que es más simple y a menudo funciona.
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

            // Si la búsqueda directa no funcionó o no es el método principal, usar Livewire.
            val docLivewire = getHtmlFromWire(
                liveWireBuilder(
                    mutableMapOf("search" to query),
                    mutableListOf(),
                    localCookies, // Usar las cookies locales
                    localWireData, // Usar los datos de Livewire locales
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

            // Aquí ya estás creando localCookies y localWireData, lo cual es correcto para 'load'.
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

                        this.date = elt.selectFirst("span.span-tiempo")?.text()?.let { dateText ->
                            try {
                                SimpleDateFormat("dd MMM.yyyy", Locale.US).parse(dateText)?.time
                            } catch (e: Exception) {
                                Log.e(name, "load: Error al analizar la fecha '$dateText': ${e.message}", e)
                                null
                            }
                        } ?: 0L
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