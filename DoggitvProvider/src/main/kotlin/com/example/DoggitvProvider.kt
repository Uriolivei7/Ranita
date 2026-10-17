package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

class DoggitvProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io"
    override var name = "DoggiTV"
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false

    private data class Country(val name: String, val listUrl: String)

    private data class ChannelInfo(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
    )

    private val countries = listOf(
        Country("Per\u00fa", "https://iptv-org.github.io/iptv/countries/pe.m3u"),
        Country("Argentina", "https://iptv-org.github.io/iptv/countries/ar.m3u"),
        Country("Colombia", "https://iptv-org.github.io/iptv/countries/co.m3u"),
        Country("Estados Unidos", "https://iptv-org.github.io/iptv/countries/us.m3u"),
        Country("Brasil", "https://iptv-org.github.io/iptv/countries/br.m3u"),
        Country("M\u00e9xico", "https://iptv-org.github.io/iptv/countries/mx.m3u"),
        Country("Chile", "https://iptv-org.github.io/iptv/countries/cl.m3u"),
        Country("Bolivia", "https://iptv-org.github.io/iptv/countries/bo.m3u"),
        Country("Uruguay", "https://iptv-org.github.io/iptv/countries/uy.m3u"),
        Country("Ecuador", "https://iptv-org.github.io/iptv/countries/ec.m3u"),
        Country("Panam\u00e1", "https://iptv-org.github.io/iptv/countries/pa.m3u"),
        Country("Honduras", "https://iptv-org.github.io/iptv/countries/hn.m3u"),
        Country("Cuba", "https://iptv-org.github.io/iptv/countries/cu.m3u"),
        Country("Nicaragua", "https://iptv-org.github.io/iptv/countries/ni.m3u"),
        Country("Venezuela", "https://iptv-org.github.io/iptv/countries/ve.m3u"),
        Country("Costa Rica", "https://iptv-org.github.io/iptv/countries/cr.m3u"),
        Country("Francia", "https://iptv-org.github.io/iptv/countries/fr.m3u"),
        Country("Rusia", "https://iptv-org.github.io/iptv/countries/ru.m3u"),
        Country("Puerto Rico", "https://iptv-org.github.io/iptv/countries/pr.m3u"),
        Country("Deportes", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
        Country("Deportes Latam", "https://sswwswsw.github.io/sports.m3u"),
        Country("Espa\u00f1a", "https://iptv-org.github.io/iptv/countries/es.m3u"),
    )

    override val mainPage = mainPageOf(
        *countries.map { it.listUrl to it.name }.toTypedArray()
    )

    private val channelNames = ConcurrentHashMap<String, String>()
    private val channelLogos = ConcurrentHashMap<String, String>()

    private fun channelItem(ch: ChannelInfo): SearchResponse =
        newLiveSearchResponse(ch.name, ch.url, TvType.Live) {
            this.posterUrl = posterFor(ch.name, ch.logo)
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val listUrl = request.data.ifBlank { countries.firstOrNull()?.listUrl ?: return null }
        val channels = fetchChannels(listUrl)
        if (channels.isEmpty()) {
            Log.e(TAG, "getMainPage '${request.name}': sin canales")
            return null
        }
        val items = channels.map { ch -> channelItem(ch) }
        Log.d(TAG, "getMainPage '${request.name}': ${items.size} canales")
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val qn = fold(q)
        return try {
            val lists = countries.amap { c -> fetchChannels(c.listUrl) }
            val results = lists.flatten()
                .asSequence()
                .filter { fold(it.name).contains(qn) }
                .distinctBy { it.url }
                .take(80)
                .map { ch -> channelItem(ch) }
                .toList()
            Log.d(TAG, "search '$q' -> ${results.size}")
            results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search '$q' fallo: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val u = url.trim()
        if (u.isBlank()) return null
        return try {
            if (isPlaylist(u)) {
                val channels = fetchChannels(u)
                if (channels.isEmpty()) return null
                val title = countries.firstOrNull { it.listUrl == u }?.name ?: "Canales"
                val episodes = channels.map { ch ->
                    newEpisode(ch.url) {
                        this.name = ch.name
                        this.posterUrl = posterFor(ch.name, ch.logo)
                        this.description = ch.group
                    }
                }
                Log.d(TAG, "load pais '$title': ${episodes.size} canales")
                newTvSeriesLoadResponse(title, u, TvType.Live, episodes) {
                    this.plot = "$title - ${channels.size} canales (iptv-org)"
                    this.posterUrl = posterFor(title, null)
                }
            } else {
                val title = channelNames[u] ?: "En Vivo"
                val logo = channelLogos[u]
                Log.d(TAG, "load canal '$title' -> $u")
                newTvSeriesLoadResponse(title, u, TvType.Live, listOf(newEpisode(u) {
                    this.name = title
                    this.posterUrl = posterFor(title, logo)
                })) {
                    this.plot = title
                    this.posterUrl = posterFor(title, logo)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load '$u' fallo: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val u = data.trim()
        if (u.isBlank() || !u.startsWith("http")) {
            Log.e(TAG, "loadLinks: url invalida ($u)")
            return false
        }
        val path = u.substringBefore('?').lowercase()
        val type = when {
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
        var target = u
        if (type == ExtractorLinkType.M3U8) {
            muxedVariant(u)?.let { mux ->
                target = mux
                Log.d(TAG, "master con grupo de audio -> variant muxado $mux")
            }
        }
        Log.d(TAG, "loadLinks -> $type $target")
        callback(newExtractorLink(name, name, target, type) {
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private suspend fun muxedVariant(master: String): String? {
        return try {
            val body = app.get(master, timeout = 15L).text
            if (!body.contains("#EXT-X-MEDIA:TYPE=AUDIO")) return null
            val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val infos = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
            if (infos.size != 1) return null
            val idx = infos[0]
            val variant = if (idx + 1 < lines.size) lines[idx + 1] else null
            if (variant.isNullOrBlank() || variant.startsWith("#")) return null
            java.net.URI(master).resolve(variant).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "muxedVariant '$master' fallo: ${e.message}")
            null
        }
    }

    private suspend fun fetchChannels(listUrl: String): List<ChannelInfo> {
        return try {
            val txt = app.get(listUrl, timeout = 40L).text
            parseM3U(txt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels '$listUrl' fallo: ${e.message}")
            emptyList()
        }
    }

    private fun parseM3U(content: String): List<ChannelInfo> {
        val out = ArrayList<ChannelInfo>()
        val rxGroup = Regex("""group-title="([^"]+)"""")
        val rxId = Regex("""tvg-id="([^"]+)"""")
        val rxLogo = Regex("""tvg-logo="([^"]+)"""")
        var group: String? = null
        var id: String? = null
        var logo: String? = null
        var nm: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                group = rxGroup.find(line)?.groupValues?.get(1)
                id = rxId.find(line)?.groupValues?.get(1)
                logo = rxLogo.find(line)?.groupValues?.get(1)
                nm = line.substringAfterLast("\",", "").ifBlank { line.substringAfter(",", "").trim() }
            } else if (line.startsWith("http")) {
                val channelName = nm?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: "Canal"
                val g = group
                val i = id
                val l = logo
                group = null; id = null; logo = null; nm = null
                if (isAdult(channelName, g, i)) continue
                out.add(ChannelInfo(channelName, line, l, g))
                channelNames[line] = channelName
                l?.takeIf { it.isNotBlank() }?.let { channelLogos[line] = it }
            }
        }
        return out
    }

    private fun posterFor(name: String, logo: String?): String {
        val clean = name.replace(Regex("""\s*[\(\[]\s*\d{3,4}p\s*[\)\]]"""), "").trim().ifBlank { name }
        val placeholder = "https://placehold.co/320x320/1e1e2e/e0e0e0/png?text=" + enc(clean)
        val l = logo?.trim()
        if (l.isNullOrBlank()) return placeholder
        return "https://wsrv.nl/?url=${enc(l)}&w=320&h=320&fit=contain&default=${enc(placeholder)}"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val adultKeywords = listOf(
        "xxx", "adult", "nsfw", "porn", "erotic", "erotico", "er\u00f3tico",
        "18+", "+18", "brazzers", "hustler", "penthouse", "playboy",
        "sextreme", "dorcel", "blue hustler", "adults only", "sex tv",
    )

    private fun isAdult(name: String?, group: String?, id: String?): Boolean {
        val s = listOfNotNull(name, group, id).joinToString(" ").lowercase()
        if (s.contains("adult swim")) return false
        return adultKeywords.any { s.contains(it) }
    }

    private fun isPlaylist(u: String): Boolean =
        u.substringBefore('?').lowercase().endsWith(".m3u")

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    companion object {
        const val TAG = "DoggiTV"
    }
}
