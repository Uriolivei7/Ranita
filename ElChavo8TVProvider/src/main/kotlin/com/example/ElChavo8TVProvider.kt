package com.example

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

class ElChavo8TVProvider : MainAPI() {
    companion object {
        var pluginContext: Context? = null
        private const val TAG = "ElChavo8TV"
        private const val MEDIA_REFERER = "https://pokemonlaserielatino.xyz/"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://elchavodel8tv.com"
    override var name = "ElChavo8TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Cartoon)
    override var lang = "mx"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val browserHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9",
        "Referer" to mainUrl,
    )

    private val mediaHeaders = mapOf(
        "User-Agent" to UA,
        "Referer" to MEDIA_REFERER,
        "Origin" to "https://pokemonlaserielatino.xyz",
        "Accept" to "*/*",
    )

    private val mediaBases = mapOf(
        "chavo" to "https://media.pokemonlaserielatino.xyz/Series/El Chavo del 8 y Chapulin Colorado/El chavo del 8/",
        "animado" to "https://media.pokemonlaserielatino.xyz/Series/El Chavo del 8 y Chapulin colorado animado/El Chavo animado/",
        "chapulinreal" to "https://media.pokemonlaserielatino.xyz/Series/El Chavo del 8 y Chapulin Colorado/El Chapulin Colorado/",
        "chapulinanimado" to "https://media.pokemonlaserielatino.xyz/Series/El Chavo del 8 y Chapulin colorado animado/El Chapulin colorado animado/",
        "chespirito" to "https://media.pokemonlaserielatino.xyz/Series/Chespirito/",
        "sinquererqueriendo" to "https://media.pokemonlaserielatino.xyz/Series/Sin querer queriendo/",
        "Pelis" to "https://media.pokemonlaserielatino.xyz/Series/Pelis chesp/",
        "kiko" to "https://media.pokemonlaserielatino.xyz/Series/Programas de Kiko/",
    )

    override val mainPage = mainPageOf(
        "el-chapulin-colorado-capitulos-completos/" to "El Chapulín Colorado",
        "el-chavo-del-8-capitulos-completos/" to "El Chavo del 8",
        "chespirito-capitulos-completos/" to "Serie Chespirito",
        "el-chapulin-colorado-animado-serie-completa/" to "El Chapulín Animado",
        "el-chavo-animado-serie-completa/" to "El Chavo Animado",
        "los-programas-de-kiko-carlos-villagran/" to "Programas de Kiko",
        "el-chanfle-y-el-chanfle-2/" to "Películas",
    )

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val req = chain.request()
            if (req.url.host.contains("pokemonlaserielatino")) {
                val b = req.newBuilder()
                for ((k, v) in mediaHeaders) b.header(k, v)
                chain.proceed(b.build())
            } else {
                chain.proceed(req)
            }
        }
    }

    // ------------------------------------------------------------------
    // Home: UNA fila por serie con sus temporadas como items (con póster)
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        val path = request.data
        val doc = try {
            app.get("$mainUrl/$path", headers = browserHeaders, timeout = 60L).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "getMainPage $path falló: ${e.message}")
            return null
        }

        val seriesPoster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img.wp-post-image, article img, .post_thumb img")?.attr("abs:src")

        val items = mutableListOf<SearchResponse>()
        val seasonLinks = doc.select("a[href*='temporada-'], a[href*=/temporada/]")
            .map { it.attr("abs:href") }
            .filter { it.isNotBlank() }
            .distinct()

        if (seasonLinks.isNotEmpty()) {
            for (u in seasonLinks) {
                val seasonName = cleanSeasonName(u)
                Log.d(TAG, "temporada name='$seasonName' url='$u'")

                val nearImg = doc.selectFirst("a[href='$u'] img")?.attr("abs:src")
                    ?: doc.selectFirst("a[href*='${u.substringAfterLast("/")}'] img")?.attr("abs:src")
                val poster = nearImg?.takeIf { it.isNotBlank() } ?: seriesPoster
                items.add(newTvSeriesSearchResponse(seasonName, u) {
                    this.posterUrl = poster
                })
            }
        } else {
            val pageTitle = doc.selectFirst("h1")?.text()?.trim() ?: request.name
            items.add(newTvSeriesSearchResponse(pageTitle, "$mainUrl/$path") {
                this.posterUrl = seriesPoster
            })
        }

        Log.d(TAG, "getMainPage '${request.name}' $path -> ${items.size} temporadas ${System.currentTimeMillis() - t0}ms")
        return if (items.isNotEmpty()) newHomePageResponse(request.name, items, false) else null
    }

    private fun cleanSeasonName(url: String): String {
        val path = url.substringBefore("?").trimEnd('/')
        val slug = path.substringAfterLast("/").removeSuffix(".html")
        val lower = slug.lowercase()
        val idx = lower.indexOf("temporada")
        if (idx >= 0) {
            val tail = lower.substring(idx + "temporada".length).trim('-', '_')
            val nums = Regex("\\d+").findAll(tail).map { it.value }.toList()
            if (nums.isNotEmpty()) {
                return if (nums.size >= 2) {
                    if (nums[0].length == 4) "Temporada ${nums[0]}-${nums[1]}"
                    else "Temporada ${nums[0]} (${nums[1]})"
                } else {
                    "Temporada ${nums[0]}"
                }
            }
        }
        val readable = slug.replace("-", " ").replace("_", " ").trim()
        return readable.ifBlank { url }
    }

    // ------------------------------------------------------------------
    // search: cards del theme (div.card > .post_title h2 a + img)
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d(TAG, "search() llamado: '$query'")
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val out = LinkedHashMap<String, SearchResponse>()
            var page = 1
            while (page <= 6 && out.size < 60) {
                val pageUrl = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
                val doc = try {
                    app.get(pageUrl, headers = browserHeaders, timeout = 60L).document
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "search page $page falló: ${e.message}")
                    break
                }
                val found = parseSearchDoc(doc)
                var added = 0
                for (r in found) {
                    if (!out.containsKey(r.url)) {
                        out[r.url] = r
                        added++
                    }
                }
                Log.d(TAG, "search page $page -> ${found.size} cards, nuevos=$added, total=${out.size}")
                if (added == 0) break
                page++
            }
            Log.d(TAG, "search '$query' -> ${out.size} resultados totales")
            out.values.toList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "search falló: ${e.message}")
            null
        }
    }

    private fun parseSearchDoc(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.card, article")
            .mapNotNull { el ->
                val a = el.selectFirst("div.post_title h2 a")
                    ?: el.selectFirst(".post_title a")
                    ?: el.selectFirst("h2 a")
                    ?: el.selectFirst("a[href*='capitulos'], a[href*='temporada'], a[href*='serie']")
                    ?: return@mapNotNull null
                val href = a.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                var title = a.text().trim()
                if (title.isEmpty()) title = el.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // load: temporada -> episodios
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "load($url)")

        val baseUrl = url.substringBefore("#")
        val doc = try {
            app.get(baseUrl, headers = browserHeaders, timeout = 120L).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "load página falló: ${e.message}")
            return null
        }

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: "Desconocido"
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("p")?.text()?.trim()
            ?: ""
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")

        val epMap = LinkedHashMap<Int, String>()
        for (el in doc.select("a[href^='javascript:Servidor']")) {
            val m = Regex("""Servidor\w+\((\d+)\)""").find(el.attr("href")) ?: continue
            val id = m.groupValues[1].toIntOrNull() ?: continue
            if (!epMap.containsKey(id)) {
                epMap[id] = precedingTitle(el) ?: "Episodio $id"
            }
        }

        if (epMap.isEmpty()) {
            Log.d(TAG, "load '$title' sin episodios (película) ${System.currentTimeMillis() - t0}ms")
            return newMovieLoadResponse(title, baseUrl, TvType.Movie, baseUrl) {
                this.posterUrl = posterUrl
                this.plot = description.ifBlank { null }
            }
        }

        val episodes = mutableListOf<Episode>()
        var n = 0
        for ((id, epTitle) in epMap.entries.sortedBy { it.key }) {
            n++
            val epUrl = "$baseUrl#ep$id"
            episodes.add(newEpisode(epUrl) {
                this.name = cleanEpTitle(epTitle)
                this.season = 1
                this.episode = n
                this.posterUrl = posterUrl
            })
        }

        Log.d(TAG, "load '$title' episodios=${episodes.size} ${System.currentTimeMillis() - t0}ms")
        return newTvSeriesLoadResponse(title, baseUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description.ifBlank { null }
        }
    }

    private fun precedingTitle(el: Element): String? {
        var node = el.previousSibling()
        var hops = 0
        while (node != null && hops < 8) {
            if (node is TextNode) {
                val t = node.text().trim()
                if (t.isNotEmpty() && t != "|" && t != "Opción 1" && t != "Opción 2") return t
            }
            node = node.previousSibling()
            hops++
        }
        return null
    }

    private fun cleanEpTitle(s: String): String =
        s.replace(Regex("^\\s*Cap[ií]tulo\\s*\\d+\\.\\s*", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { s }

    // ------------------------------------------------------------------
    // loadLinks: MP4 directo (Servidor 2) usando array[epId]
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "loadLinks data='$data'")

        val baseUrl = data.substringBefore("#")
        val wantedId = data.substringAfter("#ep", "").toIntOrNull()
        Log.d(TAG, "loadLinks wantedId=$wantedId")
        if (wantedId == null) {
            Log.w(TAG, "loadLinks sin id de episodio (#ep). Nada que resolver.")
            return false
        }

        val html = try {
            app.get(baseUrl, headers = browserHeaders, timeout = 120L).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "loadLinks página no disponible: ${e.message}")
            return false
        }
        val doc = Jsoup.parse(html, baseUrl)
        val defaultTipo = inferTipo(baseUrl)

        val funcs = mutableListOf<String>()
        for (el in doc.select("a[href^='javascript:Servidor']")) {
            val m = Regex("""(Servidor\w+)\((\d+)\)""").find(el.attr("href")) ?: continue
            val id = m.groupValues[2].toIntOrNull() ?: continue
            if (id != wantedId) continue
            val f = m.groupValues[1]
            if (!funcs.contains(f)) funcs.add(f)
        }
        Log.d(TAG, "loadLinks funciones: $funcs defaultTipo=$defaultTipo")

        var found = false
        for (func in funcs) {
            val entry = extractArrayEntry(html, func, wantedId) ?: continue
            Log.d(TAG, "loadLinks entry[$func]=${entry.take(90)}")
            if (entry.startsWith("http")) {

                if (emitByse(entry, baseUrl, subtitleCallback, callback)) found = true
                continue
            }

            val filePart = entry.substringBefore("&tipo=").trim()
            if (filePart.isEmpty()) continue
            val tipo = if (entry.contains("&tipo=")) entry.substringAfter("&tipo=").substringBefore("&").trim() else defaultTipo
            val base = mediaBases[tipo] ?: mediaBases[defaultTipo] ?: continue
            val mediaUrl = encodePath(base + filePart)
            Log.d(TAG, "MP4[$func] tipo=$tipo -> ${mediaUrl.take(140)}")
            callback(newExtractorLink(name, "$func MP4", mediaUrl, ExtractorLinkType.VIDEO) {
                this.referer = MEDIA_REFERER
                this.headers = mediaHeaders
                this.quality = Qualities.Unknown.value
            })
            found = true
        }

        Log.d(TAG, "loadLinks $baseUrl -> ${if (found) "OK" else "SIN LINKS"} ${System.currentTimeMillis() - t0}ms")
        return found
    }

    private suspend fun emitByse(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "[Byse] embed=$embedUrl")
        val html = try {
            app.get(embedUrl, headers = browserHeaders + ("Referer" to pageUrl), timeout = 20L).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[Byse] embed falló: ${e.message}")
            return false
        }
        if (!html.contains("Byse Frontend")) {
            Log.d(TAG, "[Byse] no es Byse Frontend")
            return false
        }
        return try {
            val hubHost = runCatching { "https://${java.net.URI(pageUrl).host}" }.getOrDefault(mainUrl)
            val sources = ByseHttpExtractor().extract(embedUrl, pageUrl, hubHost)
            Log.d(TAG, "[Byse] sources=${sources.size}")
            var ok = false
            for (s in sources) {
                for (sub in s.subtitles) subtitleCallback(sub)
                val isHls = s.url.contains(".m3u8")
                callback(newExtractorLink(name, "Servidor 1 Byse", s.url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = hubHost
                    this.headers = browserHeaders + ("Referer" to pageUrl)
                    this.quality = Qualities.Unknown.value
                })
                ok = true
            }
            ok
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[Byse] error: ${e.message}")
            false
        }
    }

    private fun inferTipo(url: String): String {
        val l = url.lowercase()
        return when {
            l.contains("chapulin-colorado-animado") -> "chapulinanimado"
            l.contains("chapulin-colorado") -> "chapulinreal"
            l.contains("chavo-animado") || l.contains("chavo-del-8-animado") -> "animado"
            l.contains("chespirito") -> "chespirito"
            l.contains("sin-querer") || l.contains("sinquerer") -> "sinquererqueriendo"
            l.contains("chanfle") -> "Pelis"
            l.contains("kiko") -> "kiko"
            else -> "chavo"
        }
    }

    private fun extractArrayEntry(html: String, funcName: String, index: Int): String? {
        val pat = Regex("function\\s+" + Regex.escape(funcName) + "\\s*\\(id\\)\\s*\\{[\\s\\S]*?var\\s+urls\\s*=\\s*\\[([\\s\\S]*?)\\];")
        val match = pat.findAll(html).lastOrNull()
        if (match == null) {
            Log.d(TAG, "extractArrayEntry: no array para $funcName")
            return null
        }
        val items = Regex("\"(.*?)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
        if (index < 0 || index >= items.size) {
            Log.d(TAG, "extractArrayEntry: $funcName index=$index fuera de rango (${items.size})")
            return null
        }
        return items[index]
    }

    private fun encodePath(url: String): String {
        val sb = StringBuilder()
        for (ch in url) {
            if (ch.isLetterOrDigit() || ch in "-._~/:") {
                sb.append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
        }
        return sb.toString()
    }
}
