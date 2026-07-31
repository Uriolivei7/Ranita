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
        val home = mutableListOf<HomePageList>()
        for ((name, path) in sections) {
            try {
                val url = "$mainUrl$path"
                val doc = app.get(url, timeout = 60L).document
                val channels = doc.select("div.card a[href*=/directorio/]").mapNotNull { a ->
                    val link = a.attr("href")
                    val title = a.selectFirst("h3.card-title")?.text()?.trim() ?: return@mapNotNull null
                    newLiveSearchResponse(title, fixUrl(link), TvType.Live) {
                        this.posterUrl = getChannelPoster(a, title)
                    }
                }.distinctBy { it.url }
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
        val results = mutableListOf<SearchResponse>()
        for ((_, path) in sections) {
            try {
                val url = "$mainUrl$path"
                val doc = app.get(url, timeout = 60L).document

                val posterMap = mutableMapOf<String, String>()
                try {
                    doc.select("astro-island").forEach { island ->
                        val raw = island.attr("props")
                        if (!raw.contains("logo", ignoreCase = true)) return@forEach
                        val chunks = raw.split("""{"id":""")
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
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                doc.select("div.card a[href*=/directorio/]").forEach { a ->
                    val title = a.selectFirst("h3.card-title")?.text()?.trim()
                    val poster = a.selectFirst("img")?.attr("src")
                    if (title != null && poster != null && poster.startsWith("http")) {
                        val key = title.lowercase()
                        val existente = posterMap[key]
                        if (!poster.endsWith(".svg") || existente == null || existente.endsWith(".svg")) {
                            posterMap[key] = unproxyImage(poster)
                        }
                    }
                }

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
                                results.add(newLiveSearchResponse(name, chUrl, TvType.Live) {
                                    this.posterUrl = posterUrl ?: avatarForTitle(name)
                                })
                            }
                        }
                        jsonOk = true
                    } catch (e: Exception) {
                        Log.e("GloboView", "search: JSON-LD error for $path: ${e.message}")
                    }
                }
                if (!jsonOk) {
                    doc.select("div.card a[href*=/directorio/]").forEach { a ->
                        val link = fixUrl(a.attr("href"))
                        val title = a.selectFirst("h3.card-title")?.text()?.trim() ?: return@forEach
                        if (title.contains(query, ignoreCase = true)) {
                            results.add(newLiveSearchResponse(title, link, TvType.Live) {
                                this.posterUrl = posterMap[title.lowercase()] ?: getChannelPoster(a, title)
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
        try {
            val resp = app.get(url, timeout = 60L)
            val html = resp.text
            if (resp.code != 200) {
                Log.e("GloboView", "load error: status=${resp.code}")
                return null
            }
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.trim()
                ?: "Canal"

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".card img")?.attr("src")

            val desc = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: ""
            val countrySlug = url.split("/directorio/").lastOrNull()?.split("/")?.firstOrNull()
            val countryName = countrySlug?.let { countryMap[it] }
            val fullDesc = if (countryName != null) "País: $countryName -- \n$desc" else desc

            val episodes = listOf(newEpisode(url) {
                this.name = "En Vivo"
                this.posterUrl = poster
            })
            Log.d("GloboView", "load: $title")
            return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
                this.posterUrl = poster
                this.plot = fullDesc
            }
        } catch (e: Exception) {
            Log.e("GloboView", "load error: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val resp = app.get(data, timeout = 60L)
            val html = resp.text

            val jsonLdPattern = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
            val jsonLdMatch = jsonLdPattern.find(html)
            if (jsonLdMatch != null) {
                val rawUrl = jsonLdMatch.groupValues[1].replace("\\/", "/")
                Log.d("GloboView", "loadLinks: JSON-LD -> $rawUrl")
                callback(newExtractorLink(name, "En Vivo", rawUrl, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }

            val astroPattern = Regex(""""url"\s*:\s*\[0,\s*"([^"]+)"""")
            val astroMatch = astroPattern.find(html)
            if (astroMatch != null) {
                val rawUrl = astroMatch.groupValues[1].replace("\\/", "/")
                Log.d("GloboView", "loadLinks: astro-island -> $rawUrl")
                callback(newExtractorLink(name, "En Vivo", rawUrl, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }

            val m3u8Pattern = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
            val m3u8Match = m3u8Pattern.find(html)
            if (m3u8Match != null) {
                Log.d("GloboView", "loadLinks: m3u8 -> ${m3u8Match.value}")
                callback(newExtractorLink(name, "En Vivo", m3u8Match.value, ExtractorLinkType.M3U8) {
                    this.referer = data
                })
                return true
            }

            Log.e("GloboView", "loadLinks: no m3u8 found in page")
            return false
        } catch (e: Exception) {
            Log.e("GloboView", "loadLinks error: ${e.message}")
            return false
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    private fun avatarForTitle(title: String): String {
        val encoded = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return "https://ui-avatars.com/api/?name=$encoded&background=1e293b&color=ffffff&size=128&bold=true"
    }

    private fun unproxyImage(url: String): String {
        if (url.startsWith("https://wsrv.nl/")) {
            val queryStart = url.indexOf('?')
            if (queryStart >= 0) {
                val query = url.substring(queryStart + 1)
                for (part in query.split("&")) {
                    if (part.startsWith("url=")) {
                        val raw = part.substring(4)
                        val decoded = try {
                            java.net.URLDecoder.decode(raw, "UTF-8")
                        } catch (_: Exception) {
                            null
                        }
                        if (!decoded.isNullOrBlank()) return decoded
                    }
                }
            }
        }
        return url
    }

    private fun getChannelPoster(a: Element, title: String): String {
        val img = a.selectFirst("img")?.attr("src")
        if (!img.isNullOrBlank() && img.startsWith("http")) return unproxyImage(img)
        return avatarForTitle(title)
    }
}