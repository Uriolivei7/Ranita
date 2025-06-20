package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.Qualities // Importación correcta para Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName // Importación necesaria para getQualityFromName
import java.net.URL // Importación necesaria para URL

class TvporinternetProvider : MainAPI() {

    override var mainUrl = "https://www.tvporinternet2.com"
    override var name = "TVporInternet"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Live,
    )

    // User-Agent común para simular un navegador Chrome/Brave
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // Esta función decodificará una sola capa de Base64
    private fun decodeBase64(encodedString: String): String {
        return try {
            val cleanedString = encodedString.replace('-', '+').replace('_', '/')
            val paddedString = if (cleanedString.length % 4 == 0) cleanedString else cleanedString + "====".substring(0, 4 - (cleanedString.length % 4))
            val decodedBytes = Base64.decode(paddedString, Base64.DEFAULT)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(name, "Error decodificando Base64: ${e.message} - Cadena: ${encodedString.take(50)}...")
            return ""
        }
    }

    // Esta función decodificará Base64 múltiples veces
    private fun decodeBase64MultipleTimes(encodedString: String, times: Int): String {
        var currentDecoded = encodedString
        for (i in 0 until times) {
            currentDecoded = decodeBase64(currentDecoded)
            if (currentDecoded.isEmpty()) { // Si falla en alguna decodificación, retorna vacío
                return ""
            }
        }
        return currentDecoded
    }

    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Vuelvete Premium (No ADS)", "Únete a Whatsapp", "Únete a Telegram", "¿Nos invitas el cafe?")
    val deportesCat = setOf("TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS", "Fox Sports Premium", "TYC Sports", "Movistar Deportes (Perú)", "Movistar La Liga", "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga", "Bein La Liga", "Bein Sports Extra", "Directv Sports", "Directv Sports 2", "Directv Sports Plus", "Espn Deportes", "Espn Extra", "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports", "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico")
    val entretenimientoCat = setOf("Telefe", "El Trece", "Televisión Pública", "Telemundo Puerto rico", "Univisión", "Univisión Tlnovelas", "Pasiones", "Caracol", "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision", "Azteca 7", "Azteca Uno", "Canal 5", "Distrito Comedia")
    val noticiasCat = setOf("Telemundo 51")
    val peliculasCat = setOf("Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series", "Star Channel", "Star Action", "Star Series", "Cinemax", "Space", "Syfy", "Warner Channel", "Warner Channel (México)", "Cinecanal", "FX", "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Golden Plus", "Golden Edge", "Golden Premier", "Golden Premier 2", "Sony", "DHE", "NEXT HD")
    val infantilCat = setOf("Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick")
    val educacionCat = setOf("Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia", "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo")
    val dos47Cat = setOf("24/7")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Deportes", mainUrl),
            Pair("Entretenimiento", mainUrl),
            Pair("Noticias", mainUrl),
            Pair("Peliculas", mainUrl),
            Pair("Infantil", mainUrl),
            Pair("Educacion", mainUrl),
            Pair("24/7", mainUrl),
            Pair("Todos", mainUrl),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("div.p-2").filterNot { element ->
                val text = element.selectFirst("p.des")?.text() ?: ""
                nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
            }.filter {
                val text = it.selectFirst("p.des")?.text()?.trim() ?: ""
                when (name) {
                    "Deportes" -> deportesCat.any { text.contains(it, ignoreCase = true) }
                    "Entretenimiento" -> entretenimientoCat.any { text.contains(it, ignoreCase = true) }
                    "Noticias" -> noticiasCat.any { text.contains(it, ignoreCase = true) }
                    "Peliculas" -> peliculasCat.any { text.contains(it, ignoreCase = true) }
                    "Infantil" -> infantilCat.any { text.contains(it, ignoreCase = true) }
                    "Educacion" -> educacionCat.any { text.contains(it, ignoreCase = true) }
                    "24/7" -> dos47Cat.any { text.contains(it, ignoreCase = true) }
                    "Todos" -> true
                    else -> true
                }
            }.map {
                val title = it.selectFirst("p.des")?.text() ?: ""
                val img = it.selectFirst("a img.w-28")?.attr("src") ?: ""
                val link = it.selectFirst("a")?.attr("href") ?: ""
                LiveSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Live,
                    fixUrl(img),
                    null,
                    null,
                )
            }
            items.add(HomePageList(name, home, true))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl
        val doc = app.get(url).document
        return doc.select("div.p-2").filterNot { element ->
            val text = element.selectFirst("p.des")?.text() ?: ""
            nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
        }.filter { element ->
            element.selectFirst("p.des")?.text()?.contains(query, ignoreCase = true) ?: false
        }.map {
            val title = it.selectFirst("p.des")?.text() ?: ""
            val img = it.selectFirst("a img.w-28")?.attr("src") ?: ""
            val link = it.selectFirst("a")?.attr("href") ?: ""
            LiveSearchResponse(
                title,
                link,
                this.name,
                TvType.Live,
                fixUrl(img),
                null,
                null,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.text-3xl.font-bold.mb-4")?.text() ?: ""

        val poster = doc.selectFirst("link[rel=\"shortcut icon\"]")?.attr("href") ?: ""

        val desc: String? = null

        return newMovieLoadResponse(
            title,
            url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "Iniciando loadLinks para la URL del canal: $data")

        // Headers comunes para todas las solicitudes, simulando un navegador
        val commonHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.8",
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-GPC" to "1",
            "Upgrade-Insecure-Requests" to "1"
        )

        // 1. Obtener la página principal (donde se encuentra el iframe del video)
        val mainPageRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Cache-Control", "max-age=0")
            put("Priority", "u=0, i")
            put("Referer", mainUrl)
            put("Sec-Fetch-Dest", "document")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-User", "?1")
        }

        val mainPageResponse = app.get(data, headers = mainPageRequestHeaders)
        val mainPageHtml = mainPageResponse.document.html()
        Log.d(name, "HTML recibido para la página del canal (principal): ${mainPageHtml.take(500)}...")

        val videoIframeElement = mainPageResponse.document.selectFirst("iframe[name=player]")
        val videoIframeSrc = videoIframeElement?.attr("src")

        if (videoIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontró la URL del iframe del video en la página principal.")
            return false
        }

        Log.d(name, "URL del iframe del video encontrada: $videoIframeSrc")

        // 2. Obtener el contenido del iframe del video
        val videoIframeRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Priority", "u=0, i")
            put("Referer", data) // Referer es la URL de la página principal del canal
            put("Sec-Fetch-Dest", "iframe")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "same-origin")
        }

        val videoIframeHtml = app.get(videoIframeSrc, headers = videoIframeRequestHeaders).document.html()
        Log.d(name, "HTML recibido del iframe del video: ${videoIframeHtml.take(500)}...")

        val clapprSourceRegex = Regex("""source:\s*atob\(atob\(atob\(atob\(['"]([^'"]+)['"]\)\)\)\)""")
        val match = clapprSourceRegex.find(videoIframeHtml)

        // Si el Clappr se encuentra directamente en el primer iframe
        if (match != null) {
            val encodedSource = match.groupValues[1]
            Log.d(name, "Cadena Clappr codificada (4 capas) encontrada en primer iframe: $encodedSource")

            try {
                val finalUrl = decodeBase64MultipleTimes(encodedSource, 4)

                Log.d(name, "URL de stream decodificada: $finalUrl")

                if (finalUrl.startsWith("http")) {
                    // *** INICIO DE LA LÓGICA DE PARSEO M3U8 ***
                    val m3u8Headers = mapOf(
                        "Referer" to videoIframeSrc, // El referer para el M3U8 sigue siendo la URL del iframe padre
                        "User-Agent" to USER_AGENT // Usa el mismo User-Agent
                    )
                    val m3u8Response = app.get(finalUrl, headers = m3u8Headers)
                    val m3u8Content = m3u8Response.text

                    if (m3u8Content.isNullOrBlank()) {
                        Log.w(name, "Contenido M3U8 maestro vacío o nulo para: $finalUrl")
                        return false
                    }

                    Log.d(name, "Contenido M3U8 maestro: ${m3u8Content.take(500)}...")

                    val baseUrl = getBaseUrl(finalUrl) // Función auxiliar para obtener la base URL del M3U8

                    // Regex para encontrar #EXT-X-STREAM-INF y la URL del stream
                    // Aseguramos que la captura de la resolución sea opcional con (?:,RESOLUTION=(\d+x\d+))?
                    val streamRegex = Regex("""#EXT-X-STREAM-INF:BANDWIDTH=(\d+)(?:,RESOLUTION=(\d+x\d+))?.*?\n(.*)""")
                    var foundStreams = false

                    streamRegex.findAll(m3u8Content).forEach { matchResult ->
                        foundStreams = true
                        val resolution = matchResult.groupValues.getOrNull(2).orEmpty() // Usar getOrNull para captura opcional
                        val streamRelativeUrl = matchResult.groupValues[3].trim()

                        // Construir la URL absoluta del stream de calidad
                        val streamFullUrl = if (streamRelativeUrl.startsWith("http")) {
                            streamRelativeUrl
                        } else {
                            val base = if (baseUrl.endsWith("/") && streamRelativeUrl.startsWith("/")) {
                                baseUrl.dropLast(1) // Eliminar la barra si ambas la tienen
                            } else if (!baseUrl.endsWith("/") && !streamRelativeUrl.startsWith("/")) {
                                "$baseUrl/" // Añadir barra si ninguna la tiene
                            } else {
                                baseUrl
                            }
                            "$base$streamRelativeUrl"
                        }

                        val quality = getQualityFromName(resolution.ifBlank { "Auto" }) // Obtener el Int de calidad

                        callback(newExtractorLink(
                            source = this.name,
                            name = resolution.ifBlank { "Auto" }, // Muestra la resolución o "Auto"
                            url = streamFullUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            // Dentro del bloque lambda, asigna directamente
                            this.quality = quality // <--- CORRECCIÓN CLAVE AQUÍ: NO .ordinal, 'quality' YA ES INT
                            this.referer = finalUrl
                            this.headers = mapOf("Referer" to finalUrl)
                        })
                    }

                    if (!foundStreams) {
                        // Si no se encontraron #EXT-X-STREAM-INF, puede que sea un M3U8 directo sin calidades adaptativas
                        // O solo un stream directo. En este caso, reportamos el original.
                        Log.d(name, "No se encontraron sub-streams en el M3U8 maestro, reportando el stream maestro directo.")
                        callback(newExtractorLink(
                            source = this.name,
                            name = "Auto", // Nombre predeterminado si no hay calidades explícitas
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            // Dentro del bloque lambda, asigna directamente
                            quality = Qualities.Unknown.ordinal // Esta línea YA ESTABA BIEN. No cambiar.
                            referer = videoIframeSrc
                            headers = mapOf("Referer" to videoIframeSrc)
                        })
                    }
                    // *** FIN DE LA LÓGICA DE PARSEO M3U8 ***
                    return true
                }
            } catch (e: Exception) {
                Log.e(name, "Error al decodificar la URL Base64 de Clappr del primer iframe o al parsear M3U8: ${e.message}", e)
            }
        } else {
            Log.w(name, "No se encontró el patrón de source de Clappr (4 capas) en el primer iframe: $videoIframeSrc")

            // Si el primer iframe carga *otro* iframe que contiene el stream final
            val finalStreamIframeSrcRegex = Regex("""<iframe\s+[^>]*src=["'](https?://live\.saohgdasregions\.fun/[^"']+)["']""")
            val finalIframeMatch = finalStreamIframeSrcRegex.find(videoIframeHtml)

            if (finalIframeMatch != null) {
                val finalStreamIframeSrcWithAmp = finalIframeMatch.groupValues[1]
                val finalStreamIframeSrc = finalStreamIframeSrcWithAmp.replace("&amp;", "&")
                Log.d(name, "Segundo iframe de stream encontrado: $finalStreamIframeSrc")

                // El Referer para el iframe final del stream debe ser el mainUrl del sitio.
                val finalStreamIframeRequestHeaders = commonHeaders.toMutableMap().apply {
                    put("Connection", "keep-alive")
                    put("Referer", mainUrl) // Referer es el mainUrl.
                    put("Sec-Fetch-Dest", "iframe")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", "cross-site")
                    put("Sec-Fetch-Storage-Access", "active")
                }

                val finalStreamResponse = app.get(finalStreamIframeSrc, headers = finalStreamIframeRequestHeaders)
                val finalStreamHtml = finalStreamResponse.document.html()


                Log.d(name, "HTML recibido del iframe final del stream: ${finalStreamHtml.take(500)}...")

                val finalCombinedScripts = finalStreamHtml.substringAfter("<head>").substringBefore("</body>")
                    .replace("\\n", "")
                    .replace("\\t", "")
                    .replace("\\r", "")

                val finalMatch = clapprSourceRegex.find(finalCombinedScripts)

                if (finalMatch != null) {
                    val finalEncodedSource = finalMatch.groupValues[1]
                    Log.d(name, "Cadena Clappr codificada (4 capas) encontrada en iframe final: $finalEncodedSource")

                    try {
                        val finalUrl = decodeBase64MultipleTimes(finalEncodedSource, 4)

                        Log.d(name, "URL de stream decodificada del iframe final (maestra M3U8): $finalUrl")

                        if (finalUrl.startsWith("http")) {
                            // *** INICIO DE LA LÓGICA DE PARSEO M3U8 (para el segundo iframe) ***
                            val m3u8Headers = mapOf(
                                "Referer" to finalStreamIframeSrc, // El referer para el M3U8 es la URL del iframe que lo contiene
                                "User-Agent" to USER_AGENT // Usa el mismo User-Agent
                            )
                            val m3u8Response = app.get(finalUrl, headers = m3u8Headers)
                            val m3u8Content = m3u8Response.text

                            if (m3u8Content.isNullOrBlank()) {
                                Log.w(name, "Contenido M3U8 maestro vacío o nulo para: $finalUrl (segundo iframe)")
                                return false
                            }

                            Log.d(name, "Contenido M3U8 maestro (segundo iframe): ${m3u8Content.take(500)}...")

                            val baseUrl = getBaseUrl(finalUrl) // Función auxiliar para obtener la base URL del M3U8

                            val streamRegex = Regex("""#EXT-X-STREAM-INF:BANDWIDTH=(\d+)(?:,RESOLUTION=(\d+x\d+))?.*?\n(.*)""")
                            var foundStreams = false

                            streamRegex.findAll(m3u8Content).forEach { matchResult ->
                                foundStreams = true
                                val resolution = matchResult.groupValues.getOrNull(2).orEmpty()
                                val streamRelativeUrl = matchResult.groupValues[3].trim()

                                val streamFullUrl = if (streamRelativeUrl.startsWith("http")) {
                                    streamRelativeUrl
                                } else {
                                    val base = if (baseUrl.endsWith("/") && streamRelativeUrl.startsWith("/")) {
                                        baseUrl.dropLast(1)
                                    } else if (!baseUrl.endsWith("/") && !streamRelativeUrl.startsWith("/")) {
                                        "$baseUrl/"
                                    } else {
                                        baseUrl
                                    }
                                    "$base$streamRelativeUrl"
                                }

                                val quality = getQualityFromName(resolution.ifBlank { "Auto" }) // Obtener el Int de calidad

                                callback(newExtractorLink(
                                    source = this.name,
                                    name = resolution.ifBlank { "Auto" },
                                    url = streamFullUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    // Dentro del bloque lambda, asigna directamente
                                    this.quality = quality // <--- CORRECCIÓN CLAVE AQUÍ: NO .ordinal, 'quality' YA ES INT
                                    this.referer = finalUrl
                                    this.headers = mapOf("Referer" to finalUrl)
                                })
                            }

                            if (!foundStreams) {
                                Log.d(name, "No se encontraron sub-streams en el M3U8 maestro (segundo iframe), reportando el stream maestro directo.")
                                callback(newExtractorLink(
                                    source = this.name,
                                    name = "Auto",
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    // Dentro del bloque lambda, asigna directamente
                                    quality = Qualities.Unknown.ordinal // Esta línea YA ESTABA BIEN. No cambiar.
                                    referer = finalStreamIframeSrc
                                    headers = mapOf("Referer" to finalStreamIframeSrc)
                                })
                            }
                            // *** FIN DE LA LÓGICA DE PARSEO M3U8 ***
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error al decodificar la URL Base64 de Clappr desde iframe final o al parsear M3U8: ${e.message}", e)
                    }
                } else {
                    Log.w(name, "No se encontró el patrón de source de Clappr (4 capas) en el iframe final del stream: $finalStreamIframeSrc")
                }
            }
        }

        Log.w(name, "No se encontró ninguna URL de stream válida tras analizar la página del canal: $data")
        return false
    }

    // Función auxiliar para obtener la base URL de un M3U8
    private fun getBaseUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            val path = url.path
            // Eliminar la última parte del path para obtener la base URL del directorio si es un archivo
            val lastSlash = url.path.lastIndexOf('/')
            val basePath = if (lastSlash != -1) url.path.substring(0, lastSlash + 1) else "/"
            "${url.protocol}://${url.host}$basePath"
        } catch (e: Exception) {
            Log.e(name, "Error al obtener base URL de $urlString: ${e.message}")
            urlString // Retornar la original si falla, o una cadena vacía
        }
    }
}