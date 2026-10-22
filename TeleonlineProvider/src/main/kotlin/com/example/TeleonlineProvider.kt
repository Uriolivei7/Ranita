package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TeleonlineProvider : MainAPI() {
    override var mainUrl = "https://teleonline.org"
    override var name = "Teleonline"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    private val cfKiller = CloudflareKiller()
    private val maxPagesGeneral = 2
    private val playerUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    private data class OptionCandidate(val idx: Int, val url: String, val variant: String)
    private data class ProxyPlan(val variantUrl: String, val tracks: List<TsTrackInfo>)
    private val countrySections = listOf(
        "Perú" to "/canales/peru/",
        "Argentina" to "/canales/argentina/",
        "México" to "/canales/mexico/",
        "Colombia" to "/canales/colombia/",
        "Chile" to "/canales/chile/",
        "Estados Unidos" to "/canales/estados-unidos/",
        "Brasil" to "/canales/brasil/",
        "Venezuela" to "/canales/venezuela/",
        "España" to "/canales/espana/",
    )

    private fun extractPosterFromStyle(element: org.jsoup.nodes.Element): String? {
        val style = element.attr("style")
        if (style.isBlank()) return null
        return Regex("""url\s*\(?\s*['"]?\s*([^'")\s]+)""", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)
    }

    private data class ChannelInfo(val name: String, val url: String, val poster: String?)

    private fun extractChannels(html: String): List<ChannelInfo> {
        val channels = mutableListOf<ChannelInfo>()
        val doc = Jsoup.parse(html)
        doc.select("article.article-loop").forEach { article ->
            val link = article.selectFirst("a[rel=bookmark]")?.attr("href")
                ?: article.selectFirst("a")?.attr("href")
                ?: return@forEach
            val title = article.selectFirst(".entry-title")?.text()?.trim()
                ?: article.selectFirst("a p")?.text()?.trim()
                ?: return@forEach

            var poster: String? = null
            val imgEl = article.selectFirst(".article-image")
            if (imgEl != null) poster = extractPosterFromStyle(imgEl)
            if (poster == null) {
                poster = article.selectFirst("img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else null
                }
            }

            channels.add(ChannelInfo(title, link, poster))
        }
        return channels
    }

    private suspend fun fetchSection(name: String, path: String, pages: Int = 1): List<ChannelInfo> {
        val channels = mutableListOf<ChannelInfo>()
        for (p in 1..pages) {
            val url = if (p == 1) "$mainUrl$path" else "$mainUrl${path}page/$p/"
            val html = safeGet(url) ?: continue
            channels.addAll(extractChannels(html))
        }
        return channels
    }

    private suspend fun safeGet(url: String, timeoutSec: Long = 15L): String? {
        for (attempt in 1..2) {
            try {
                val interceptor = if (attempt > 1) cfKiller else null
                val res = app.get(url, timeout = timeoutSec * attempt, interceptor = interceptor)
                if (res.isSuccessful) return res.text
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("Teleonline", "safeGet error (intento $attempt): ${e.message}")
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sections = mutableListOf<HomePageList>()

        // General channels
        val general = fetchSection("Canales", "/", maxPagesGeneral)
        if (general.isNotEmpty()) {
            sections.add(HomePageList("Canales", general.distinctBy { it.url }.map { it.toSearchResponse() }, isHorizontalImages = true))
        }

        // Country sections
        for ((name, path) in countrySections) {
            val channels = fetchSection(name, path)
            if (channels.isNotEmpty()) {
                sections.add(HomePageList(name, channels.distinctBy { it.url }.map { it.toSearchResponse() }, isHorizontalImages = true))
            }
        }

        if (sections.isEmpty()) return null
        return newHomePageResponse(sections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = safeGet("$mainUrl/?s=$query") ?: return emptyList()
        return extractChannels(html).distinctBy { it.url }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val html = safeGet(url, 20L) ?: return null
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst(".titulo-canal-manual")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("title")?.text()
                ?: "Canal"

            var poster = doc.selectFirst(".logo-channel img")?.attr("src")
            if (poster.isNullOrBlank()) {
                val jsonLd = Regex(""""logo"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)""").find(html)
                if (jsonLd != null) poster = jsonLd.groupValues[1]
            }
            if (poster.isNullOrBlank()) {
                poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            }

            val desc = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: ""

            val episodes = listOf(newEpisode(url) {
                this.name = "En Vivo"
                this.posterUrl = poster
            })
            return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
                this.posterUrl = poster
                this.plot = desc
            }
        } catch (e: Exception) {
            Log.e("Teleonline", "load error: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9"
            )

            val mainHtml = withTimeoutOrNull(20000L) {
                app.get(data, headers = mainHeaders, interceptor = cfKiller)
            }?.text ?: return false

            val b64Pattern = Regex("""(?:atob|btoa)\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]""")
            val options = b64Pattern.findAll(mainHtml).mapIndexedNotNull { idx, match ->
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                    val m3u8Url = decoded.replace("\\/", "/").trim()
                    if (m3u8Url.contains(".m3u8")) (idx + 1) to m3u8Url else null
                } catch (_: Exception) {
                    null
                }
            }.toList()
            if (options.isNotEmpty() && emitM3u8Options(options, data, callback)) return true

            val directM3u8 = extractM3u8FromHtml(mainHtml)
            if (directM3u8 != null) {
                callback(newExtractorLink(name, "$name - En Vivo", directM3u8, ExtractorLinkType.M3U8) {
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to data
                    )
                })
                return true
            }

            val doc = Jsoup.parse(mainHtml)
            val playerUrls = doc.select("iframe[src], a[href*=/live], a[href*=.php], div[data-player] a, [data-src], iframe[src*=/embed]")
                .mapNotNull { el ->
                    when {
                        el.tagName() == "iframe" -> el.attr("src")
                        el.hasAttr("data-src") -> el.attr("data-src")
                        else -> el.attr("href")
                    }
                }
                .filter { it.isNotBlank() && !it.contains("facebook") && !it.contains("twitter") && !it.contains("supportiveinvoicevarnish") }
                .distinct()
                .map { if (it.startsWith("http")) it else "$mainUrl$it" }
                .filter { it.startsWith("http") }

            Log.d("Teleonline", "playerUrls: $playerUrls")

            for ((idx, playerUrl) in playerUrls.withIndex()) {
                try {
                    if (playerUrl.contains("youtube.com") || playerUrl.contains("youtu.be")) {
                        val ytId = Regex("""embed/([^/?]+)""").find(playerUrl)?.groupValues?.get(1)
                        val ytUrl = if (ytId != null) "https://www.youtube.com/watch?v=$ytId" else playerUrl
                        callback(newExtractorLink(name, "YouTube", ytUrl, ExtractorLinkType.M3U8))
                        return true
                    }

                    val playerHtml = withTimeoutOrNull(15000L) {
                        app.get(playerUrl, headers = mainHeaders + mapOf("Referer" to data), interceptor = cfKiller)
                    }?.text ?: continue

                    val m3u8Url = extractM3u8FromHtml(playerHtml)
                    if (m3u8Url != null) {
                        callback(newExtractorLink(name, "$name - Opción ${idx + 1}", m3u8Url, ExtractorLinkType.M3U8) {
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer" to playerUrl
                            )
                        })
                        return true
                    }
                } catch (_: Exception) {}
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Teleonline", "loadLinks error: ${e.message}")
            return false
        }
    }

    private suspend fun emitM3u8Options(
        options: List<Pair<Int, String>>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = options.amap { (idx, url) ->
            if (!url.startsWith("http")) null else candidateOf(idx, url)
        }.filterNotNull()
        Log.d("Teleonline", "opciones=${options.size} candidatas=${candidates.size}")

        val plans = HashMap<Int, ProxyPlan>()
        var budget = 2
        for (c in candidates) {
            if (budget <= 0) break
            budget--
            try {
                probeCandidate(c)?.let { plans[c.idx] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Teleonline", "probe opción ${c.idx} falló: ${e.message}")
            }
        }

        var found = false
        for ((idx, url) in options) {
            val label = "En Vivo - Opción $idx"
            Log.d("Teleonline", "opción $idx: $url")
            val plan = plans[idx]
            var proxied = false
            if (plan != null) {
                val tracks = plan.tracks.sortedBy { if (isSpanish(it.lang)) 0 else 1 }
                for (t in tracks) {
                    val proxyUrl = withContext(Dispatchers.IO) {
                        TeleonlineTsProxy.playlistUrl(plan.variantUrl, t.pid)
                    } ?: continue
                    emitLink("$label [${langTag(t)}]", proxyUrl, referer, callback)
                    proxied = true
                    found = true
                }
            }
            if (!proxied) {
                emitLink(label, url, referer, callback)
                found = true
            }
        }
        return found
    }

    private suspend fun candidateOf(idx: Int, url: String): OptionCandidate? = withContext(Dispatchers.IO) {
        val text = TeleonlineTsProxy.fetchText(url, 12000L) ?: return@withContext null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("#EXT-X-MAP") || it.startsWith("#EXT-X-KEY") }) return@withContext null
        if (lines.any { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }) return@withContext null
        val streamInfCount = lines.count { it.startsWith("#EXT-X-STREAM-INF") }
        if (streamInfCount > 1) return@withContext null
        val variant = if (streamInfCount == 0) {
            url
        } else {
            val from = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val ref = lines.drop(from + 1).firstOrNull { !it.startsWith("#") } ?: return@withContext null
            TeleonlineTsProxy.absolute(url, ref) ?: return@withContext null
        }
        OptionCandidate(idx, url, variant)
    }

    private suspend fun probeCandidate(c: OptionCandidate): ProxyPlan? = withContext(Dispatchers.IO) {
        val segment = TeleonlineTsProxy.firstSegment(c.variant, 15000L) ?: return@withContext null
        val tracks = TeleonlineTsProxy.probeTracks(segment) ?: return@withContext null
        Log.d("Teleonline", "opción ${c.idx} probe ok: ${tracks.joinToString { "${it.pid}:${it.lang}" }}")
        ProxyPlan(c.variant, tracks)
    }

    private suspend fun emitLink(label: String, url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
            this.headers = mapOf("User-Agent" to playerUa, "Referer" to referer)
        })
    }

    private fun isSpanish(lang: String?): Boolean {
        val l = lang?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return l == "spa" || l == "es" || l == "esp" || l.startsWith("es-")
    }

    private fun langTag(t: TsTrackInfo): String {
        val l = t.lang?.lowercase()?.takeIf { it.isNotBlank() } ?: return "PID ${t.pid}"
        return when {
            l == "spa" || l == "es" || l == "esp" || l.startsWith("es-") -> "ESP"
            l.startsWith("en") -> "ENG"
            l.startsWith("pt") || l == "por" -> "POR"
            else -> l.uppercase().take(3)
        }
    }

    private fun extractM3u8FromHtml(html: String): String? {
        val patterns = listOf(
            """["'](https?[:\/\/\\]+[^"']+\.m3u8[^"']*)["']""",
            """source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
            """var\s+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
            """(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""",
        )
        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                return match.groupValues[1].replace("\\/", "/")
            }
        }
        return null
    }

    private fun ChannelInfo.toSearchResponse() = newLiveSearchResponse(name, url, TvType.Live) {
        this.posterUrl = poster
    }
}