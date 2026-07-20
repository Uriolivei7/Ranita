package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

class GloboViewProvider : MainAPI() {
    override var mainUrl = "https://globoview.cam"
    override var name = "GloboView"
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val sections = listOf(
        "Perú" to "/directorio/peru/",
        "Argentina" to "/directorio/argentina/",
        "Colombia" to "/directorio/colombia/",
        "México" to "/directorio/mexico/",
        "EEUU" to "/directorio/estados-unidos/",
        "Venezuela" to "/directorio/venezuela/",
        "Chile" to "/directorio/chile/",
        "Ecuador" to "/directorio/ecuador/",
        "Rep. Dominicana" to "/directorio/republica-dominicana/",
        "Puerto Rico" to "/directorio/puerto-rico/",
        "Brasil" to "/directorio/brasil/",
        "España" to "/directorio/espana/",
        "Alemania" to "/directorio/alemania/",
        "Reino Unido" to "/directorio/united-kingdom/",
        "Francia" to "/directorio/francia/",
        "Italia" to "/directorio/italia/",
    )
    private val countryMap = sections.associate { (name, path) ->
        path.removePrefix("/directorio/").removeSuffix("/") to name
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("GloboView", "getMainPage: page=$page, request=${request.name}")
        val home = mutableListOf<HomePageList>()
        for ((name, path) in sections) {
            try {
                val url = "$mainUrl$path"
                Log.d("GloboView", "getMainPage: fetching $url")
                val doc = app.get(url, timeout = 60L).document
                val html = doc.html()
                val paginationLinks = doc.select("a[href*=/pagina], a[href*=/page], a:matches((?i)siguiente|next|anterior|prev)").map { "${it.text()}: ${it.attr("href")}" }
                if (paginationLinks.isNotEmpty()) {
                    Log.d("GloboView", "getMainPage: $name -> paginacion detectada: $paginationLinks")
                }
                val navHtml = doc.select("nav").map { n -> n.html().take(1500) }
                if (navHtml.isNotEmpty()) {
                    Log.d("GloboView", "getMainPage: $name -> nav HTML: $navHtml")
                    val pageLinks = doc.select("nav a[href]").map { a -> "${a.text()}: ${a.attr("href")}" }
                    Log.d("GloboView", "getMainPage: $name -> page links: $pageLinks")
                }
                val astroIslands = doc.select("astro-island").map { i -> "[props]=" + (i.attr("props").take(500) ?: "none") + " [component]=" + i.attr("component") }
                astroIslands.forEach { Log.d("GloboView", "getMainPage: $name -> astro-island: $it") }
                val allCards = doc.select("a.card[href*=/directorio/]").size
                val hiddenCards = doc.select("[hidden] a.card, .hidden a.card, [style*='display:none'] a.card, [style*='display: none'] a.card").size
                Log.d("GloboView", "getMainPage: $name -> total cards visible=$allCards, hidden cards=$hiddenCards")
                val preTags = doc.select("script").mapNotNull { s -> s.html().take(200).ifBlank { null } }.filter { it.contains("channel", ignoreCase=true) || it.contains("page", ignoreCase=true) }
                preTags.forEach { Log.d("GloboView", "getMainPage: $name -> script tag: $it") }
                val tail = html.takeLast(1500)
                Log.d("GloboView", "getMainPage: $name -> tail HTML: $tail")
                val channels = doc.select("a.card[href*=/directorio/]").mapNotNull { a ->
                    val link = a.attr("href")
                    val title = a.selectFirst("h3.card-title")?.text()?.trim() ?: return@mapNotNull null
                    val poster = a.selectFirst("img")?.attr("src")
                    newLiveSearchResponse(title, fixUrl(link), TvType.Live) {
                        this.posterUrl = if (poster?.startsWith("http") == true) poster else null
                    }
                }.distinctBy { it.url }
                Log.d("GloboView", "getMainPage: $name -> ${channels.size} canales")
                if (channels.isNotEmpty()) {
                    home.add(HomePageList(name, channels, isHorizontalImages = true))
                }
            } catch (e: Exception) {
                Log.e("GloboView", "getMainPage error for $name: ${e.message}")
            }
        }
        if (home.isEmpty()) {
            Log.e("GloboView", "getMainPage: 0 secciones, retornando null")
            return null
        }
        Log.d("GloboView", "getMainPage: ${home.size} secciones cargadas")
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("GloboView", "search: query=$query")
        val results = mutableListOf<SearchResponse>()
        for ((_, path) in sections) {
            try {
                val url = "$mainUrl$path"
                Log.d("GloboView", "search: scanning $url")
                val doc = app.get(url, timeout = 60L).document

                val posterMap = mutableMapOf<String, String>()
                try {
                    doc.select("astro-island").forEach { island ->
                        val raw = island.attr("props")
                        if (!raw.contains("logo", ignoreCase = true)) return@forEach
                        val chunks = raw.split("""{"id":""")
                        var parsed = 0
                        for (chunk in chunks.drop(1)) {
                            try {
                                val nameM = Regex(""""name":\[0,"([^"]+)"""").find(chunk)
                                val logoM = Regex(""""logo":\[0,"([^"]+)"""").find(chunk)
                                if (nameM != null && logoM != null) {
                                    val n = nameM.groupValues[1]
                                    val l = logoM.groupValues[1].replace("\\/", "/")
                                    if (n.isNotEmpty() && l.startsWith("http")) {
                                        var logoUrl = l
                                        if (logoUrl.contains("upload.wikimedia.org")) {
                                            if (logoUrl.endsWith(".svg")) {
                                                val dir = logoUrl.substringAfter("commons/").substringBeforeLast("/")
                                                val svgName = logoUrl.substringAfterLast("/")
                                                val pngName = svgName.replace(".svg", ".png")
                                                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/$dir/$svgName/960px-$pngName"
                                            }
                                            if (logoUrl.endsWith(".svg.png")) logoUrl = logoUrl.removeSuffix(".svg.png") + ".png"
                                            logoUrl = "https://wsrv.nl/?url=${java.net.URLEncoder.encode(logoUrl, "UTF-8")}&w=128&h=128&output=png"
                                        } else if (logoUrl.endsWith(".svg")) {
                                            logoUrl = "https://wsrv.nl/?url=${java.net.URLEncoder.encode(logoUrl, "UTF-8")}&w=128&h=128&output=png"
                                        }
                                        posterMap[n.lowercase()] = logoUrl
                                        parsed++
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        Log.d("GloboView", "search: astro-island parsed $parsed posters")
                        if (parsed > 0) {
                            Log.d("GloboView", "search: sample astro name = ${posterMap.keys.first()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d("GloboView", "search: astro-island error: ${e.message}")
                }
                doc.select("a.card[href*=/directorio/]").forEach { a ->
                    val title = a.selectFirst("h3.card-title")?.text()?.trim()
                    val poster = a.selectFirst("img")?.attr("src")
                    if (title != null && poster != null && poster.startsWith("http")) {
                        val key = title.lowercase()
                        val existente = posterMap[key]
                        if (!poster.endsWith(".svg") || existente == null || existente.endsWith(".svg")) {
                            posterMap[key] = poster
                        }
                    }
                }
                Log.d("GloboView", "search: posters totales por nombre=${posterMap.size}")

                var jsonOk = false
                val jsonLd = doc.select("script[type='application/ld+json']").firstOrNull { it.data().contains("ItemList") }
                if (jsonLd != null) {
                    try {
                        val raw = jsonLd.data()
                        val json = JSONObject(raw)
                        val mainEntity = json.optJSONObject("mainEntity") ?: json
                        val items = mainEntity.getJSONArray("itemListElement")
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val name = item.getString("name")
                            val chUrl = item.getString("url")
                            if (name.contains(query, ignoreCase = true)) {
                                var posterUrl = posterMap[name.lowercase()]
                                if (posterUrl == null) {
                                    posterUrl = posterMap.entries.firstOrNull { name.lowercase().contains(it.key) || it.key.contains(name.lowercase()) }?.value
                                }
                                Log.d("GloboView", "search: match=$name -> ${chUrl.split("/directorio/").lastOrNull()}, poster=$posterUrl")
                                results.add(newLiveSearchResponse(name, chUrl, TvType.Live) {
                                    this.posterUrl = posterUrl
                                })
                            }
                        }
                        Log.d("GloboView", "search: $path -> ${items.length()} canales totales en JSON-LD")
                        jsonOk = true
                    } catch (e: Exception) {
                        Log.e("GloboView", "search: JSON-LD error for $path: ${e.message}")
                    }
                }
                if (!jsonOk) {
                    Log.d("GloboView", "search: $path -> usando cards visibles")
                    doc.select("a.card[href*=/directorio/]").forEach { a ->
                        val link = fixUrl(a.attr("href"))
                        val title = a.selectFirst("h3.card-title")?.text()?.trim() ?: return@forEach
                        if (title.contains(query, ignoreCase = true)) {
                            results.add(newLiveSearchResponse(title, link, TvType.Live) {
                                this.posterUrl = posterMap[title.lowercase()]
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GloboView", "search error for $path: ${e.message}")
            }
        }
        Log.d("GloboView", "search: ${results.size} resultados para query=$query")
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("GloboView", "load: url=$url")
        try {
            val resp = app.get(url, timeout = 60L)
            val html = resp.text
            Log.d("GloboView", "load: code=${resp.code}, html len=${html.length}")
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.trim()
                ?: "Canal"
            Log.d("GloboView", "load: title=$title")

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".card img")?.attr("src")
            Log.d("GloboView", "load: poster=$poster")

            val desc = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: ""
            val countrySlug = url.split("/directorio/").lastOrNull()?.split("/")?.firstOrNull()
            val countryName = countrySlug?.let { countryMap[it] }
            val fullDesc = if (countryName != null) "País: $countryName -- \n$desc" else desc
            Log.d("GloboView", "load: desc=${fullDesc.take(100)}")

            val episodes = listOf(newEpisode(url) {
                this.name = "En Vivo"
                this.posterUrl = poster
            })
            return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
                this.posterUrl = poster
                this.plot = fullDesc
            }
        } catch (e: Exception) {
            Log.e("GloboView", "load error: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("GloboView", "loadLinks: data=$data")
        try {
            val resp = app.get(data, timeout = 60L)
            val html = resp.text
            Log.d("GloboView", "loadLinks: code=${resp.code}, html len=${html.length}")

            val jsonLdPattern = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
            val jsonLdMatch = jsonLdPattern.find(html)
            if (jsonLdMatch != null) {
                val rawUrl = jsonLdMatch.groupValues[1].replace("\\/", "/")
                Log.d("GloboView", "loadLinks: found via JSON-LD: $rawUrl")
                callback(newExtractorLink(name, "En Vivo", rawUrl, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }
            Log.d("GloboView", "loadLinks: JSON-LD pattern not found")

            val astroPattern = Regex(""""url"\s*:\s*\[0,\s*"([^"]+)"""")
            val astroMatch = astroPattern.find(html)
            if (astroMatch != null) {
                val rawUrl = astroMatch.groupValues[1].replace("\\/", "/")
                Log.d("GloboView", "loadLinks: found via astro-island: $rawUrl")
                callback(newExtractorLink(name, "En Vivo", rawUrl, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }
            Log.d("GloboView", "loadLinks: astro-island pattern not found")

            val m3u8Pattern = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
            val m3u8Match = m3u8Pattern.find(html)
            if (m3u8Match != null) {
                Log.d("GloboView", "loadLinks: found via generic regex: ${m3u8Match.value}")
                callback(newExtractorLink(name, "En Vivo", m3u8Match.value, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }
            Log.d("GloboView", "loadLinks: no m3u8 found in page")

            return false
        } catch (e: Exception) {
            Log.e("GloboView", "loadLinks error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}