package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "VerAnime"

class VeranimeProvider : MainAPI() {
    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val KEYBASE = "1d54a1d53c1d56e1d6811d53c1d6831d4da1d4d41d4e81d7d91d7da1d7db"
        private const val IVBASE = "1f5101f5dd1f5111f6aa1f6e126a1"
        private val HEX = "0123456789abcdef".toCharArray()
    }

    override var mainUrl = "https://verani.me"
    override var name = "VerAnime"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "ultimos" to "Últimos Animes",
        "top" to "Top Animes",
        "peliculas" to "Últimas Películas",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private val zillaHeaders = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://player.zilla-networks.com",
        "Referer" to "https://player.zilla-networks.com/",
        "Sec-Fetch-Dest" to "video",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "same-origin",
        "Priority" to "u=1, i",
    )

    private suspend fun getDoc(
        url: String,
        timeout: Long,
        referer: String? = null,
        logTag: String = "page"
    ): Document? {
        val resp = try {
            val headers = if (referer.isNullOrBlank()) browserHeaders
                else browserHeaders + ("Referer" to referer)
            app.get(url, headers = headers, timeout = timeout)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[$logTag] app.get falló: ${e.message}")
            null
        } ?: return null
        Log.d(TAG, "[$logTag] HTTP ${resp.code} len=${resp.text.length}")
        return resp.document
    }

    // ------------------------------------------------------------------
    // Cards y home
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList(), false), hasNext = false)
        }
        val doc = getDoc("$mainUrl/", 60L, logTag = "home") ?: return null
        val items = when (request.data) {
            "ultimos" -> doc.select(".animes-section .content-card.anime-card").mapNotNull { it.toCard() }
            "peliculas" -> doc.select(".peliculas-section .content-card.pelicula-card").mapNotNull { it.toCard(true) }
            "top" -> parseTop(doc)
            else -> emptyList()
        }.distinctBy { it.url }
        Log.d(TAG, "getMainPage ${request.name} -> ${items.size} items")
        if (items.isEmpty()) return null
        return newHomePageResponse(HomePageList(request.name, items, false), hasNext = false)
    }

    private fun parseTop(doc: Document): List<SearchResponse> =
        doc.select("a.home-top-animes-item").mapNotNull { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            if (!href.contains("verani.me")) return@mapNotNull null
            val title = el.selectFirst(".home-top-animes-item__title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img.home-top-animes-item__img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }

    private fun Element.toCard(movie: Boolean = false): SearchResponse? {
        val link = selectFirst("a.card-link") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("verani.me")) return null
        val title = selectFirst("span.card-title")?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: selectFirst("img.card-img")?.attr("alt")?.trim().takeIf { !it.isNullOrBlank() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img.card-img")?.attr("src"))
        return if (movie || className().contains("pelicula-card")) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val t0 = System.currentTimeMillis()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = getDoc("$mainUrl/?s=$encoded", 60L, logTag = "search") ?: return null
        val all = doc.select("article.content-card").mapNotNull { it.toCard() }.distinctBy { it.url }
        val results = all.filter { matchesQuery(it.name, query) }
        Log.d(TAG, "search '$query' -> ${results.size} (de ${all.size}) ${System.currentTimeMillis() - t0}ms")
        return results
    }

    private fun matchesQuery(title: String, query: String): Boolean {
        val norm = title.lowercase()
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return true
        return tokens.all { tok ->
            norm.contains(tok) || (tok.length > 3 && tok.endsWith("s") && norm.contains(tok.dropLast(1)))
        }
    }

    // ------------------------------------------------------------------
    // Detalle
    // ------------------------------------------------------------------

    private fun isSiteLogo(url: String): Boolean =
        url.contains("Gemini_Generated_Image_", ignoreCase = true)

    private fun parseRecommendations(doc: Document): List<SearchResponse> =
        doc.select(".anime-recommendation-card").mapNotNull { card ->
            val a = card.selectFirst("a.card-link") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.contains("verani.me")) return@mapNotNull null
            val title = a.selectFirst("h3.card-title")?.text()?.trim().takeIf { !it.isNullOrBlank() }
                ?: a.selectFirst("img.card-img")?.attr("alt")?.trim().takeIf { !it.isNullOrBlank() }
                ?: return@mapNotNull null
            val poster = fixUrlNull(a.selectFirst("img.card-img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        for (group in doc.select(".temporada-group")) {
            val seasonNum = group.selectFirst(".temporada-name")?.attr("data-season-num")?.toIntOrNull() ?: 1
            for (card in group.select(".capitulo-card")) {
                val a = card.selectFirst("a.capitulo-card-link") ?: continue
                val href = fixUrlNull(a.attr("href")) ?: continue
                val titleText = card.selectFirst("h3.capitulo-card-title")?.text()?.trim() ?: continue
                val epNum = Regex("(\\d+)").find(titleText)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val subtitle = card.selectFirst("p.capitulo-card-subtitle")?.text()?.trim()?.takeIf { it.isNotBlank() }
                val poster = fixUrlNull(card.selectFirst("img.capitulo-card-img")?.attr("src"))
                out.add(newEpisode(href) {
                    this.name = subtitle ?: "Capítulo $epNum"
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url, 120L, logTag = "load") ?: return null
        val trailer = doc.selectFirst("iframe[src*=youtube]")?.attr("src")?.takeIf { it.isNotBlank() }
        val recommendations = parseRecommendations(doc)

        if (url.contains("/pelicula/")) {
            val title = doc.selectFirst("h1.pelicula-title")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1.pelicula-title")?.text()?.trim()?.substringBefore("(")?.trim()
                ?: return null
            val year = Regex("\\d{4}").find(doc.selectFirst(".pelicula-year-line")?.text() ?: "")?.value?.toIntOrNull()
            val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }

            val poster = fixUrlNull(doc.selectFirst("img.pelicula-poster-image")?.attr("src"))
                ?: ogPoster?.takeIf { !isSiteLogo(it) }
            val backdrop = fixUrlNull(doc.selectFirst("img.pelicula-backdrop-image")?.attr("src"))
            val plot = doc.selectFirst(".pelicula-overview-text")?.text()?.replace("&hellip;", "…")?.trim()
            val tags = doc.selectFirst(".pelicula-meta-generos .pelicula-meta-item")?.text()
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            Log.d(TAG, "load película '$title'")
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags ?: emptyList()
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                addTrailer(trailer)
            }
        }

        val title = doc.selectFirst("h1.anime-hero-title")?.text()?.trim() ?: return null
        val backdrop = fixUrlNull(doc.selectFirst("img.anime-hero-bg-image")?.attr("src"))
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }

        val poster = ogPoster?.takeIf { !isSiteLogo(it) } ?: backdrop
        val plot = doc.selectFirst("p.anime-hero-description")?.text()?.replace("&hellip;", "…")?.trim()
        val year = Regex("\\d{4}").find(doc.selectFirst(".anime-hero-year")?.text() ?: "")?.value?.toIntOrNull()
        val statusText = doc.selectFirst(".anime-hero-status")?.text()?.lowercase() ?: ""
        val status = when {
            statusText.contains("finalizado") -> ShowStatus.Completed
            statusText.contains("emision") || statusText.contains("emisión") -> ShowStatus.Ongoing
            else -> null
        }
        val episodes = parseEpisodes(doc)
        Log.d(TAG, "load '$title': ${episodes.size} eps")
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.showStatus = status
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            addTrailer(trailer)
        }
    }

    // ------------------------------------------------------------------
    // Reproducción
    // ------------------------------------------------------------------

    private fun langTag(idioma: String, subtitulos: String): String {
        val i = idioma.lowercase()
        val s = subtitulos.lowercase()
        return when {
            i.contains("latino") -> "LAT"
            i.contains("cast") || (i.contains("espa") && !i.contains("latino")) -> "CAS"
            i.contains("japon") -> if (s.isNotBlank()) "SUB" else "JPN"
            i.contains("coreano") -> if (s.isNotBlank()) "SUB" else "COR"
            i.contains("ingles") -> "ENG"
            idioma.isNotBlank() -> idioma.take(12)
            else -> ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks data='$data'")
        val doc = getDoc(data, 120L, referer = mainUrl, logTag = "loadLinks") ?: return false

        val tabs = doc.select("button.servidor-tab")
        Log.d(TAG, "loadLinks servidores=${tabs.size}")

        val servers = mutableListOf<Pair<String, String>>()
        for (tab in tabs) {
            val u = tab.attr("data-url")
            if (u.isBlank()) continue
            val tag = langTag(tab.attr("data-idioma"), tab.attr("data-subtitulos"))
            val label = tab.attr("data-label").ifBlank { "Servidor" } + if (tag.isNotBlank()) " [$tag]" else ""
            servers.add(label to u)
        }
        if (servers.isEmpty()) {
            for (iframe in doc.select("iframe[data-src]")) {
                val src = iframe.attr("data-src")
                if (src.isNotBlank()) servers.add(iframe.attr("title").ifBlank { "Servidor" } to src)
            }
            Log.d(TAG, "loadLinks (iframes) servidores=${servers.size}")
        }

        var emitted = 0
        for ((label, url) in servers) {
            if (resolveServer(url, label, data, subtitleCallback, callback)) emitted++
        }
        Log.d(TAG, "loadLinks emitted=$emitted/${servers.size}")
        return emitted > 0
    }

    private suspend fun resolveServer(
        url: String,
        label: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        Log.d(TAG, "resolveServer $label host=$host")
        return when {
            host.contains("zilla-networks.com") -> {
                callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.headers = zillaHeaders
                    this.quality = Qualities.Unknown.value
                })
                true
            }
            host.contains("uns.bio") -> {
                val resolved = resolveUns(url)
                if (resolved == null) {
                    Log.w(TAG, "uns sin resolver")
                    false
                } else {
                    resolveServer(resolved, label, pageReferer, subtitleCallback, callback)
                }
            }
            host.contains("turboembed.com") -> {
                val res = resolveTurbovio(url, subtitleCallback)
                if (res == null) {
                    Log.w(TAG, "turbovio sin fuente")
                    false
                } else {
                    callback(newExtractorLink(name, label, res.streamUrl, if (res.streamUrl.contains(".m3u8") || res.streamUrl.contains(".txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = res.referer
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to res.referer,
                            "Origin" to res.origin,
                            "Accept" to "*/*",
                        )
                        this.quality = Qualities.Unknown.value
                    })
                    true
                }
            }
            host.contains("byse") -> emitByse(url, pageReferer, subtitleCallback, callback, label)
            else -> loadExt(url, pageReferer, subtitleCallback, callback, label)
        }
    }

    private suspend fun loadExt(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String
    ): Boolean {
        val collected = mutableListOf<ExtractorLink>()
        try {
            loadExtractor(url, referer, subtitleCallback) { link -> collected.add(link) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "loadExtractor $label error: ${e.message}")
        }
        for (link in collected) {
            callback(newExtractorLink(name, label, link.url, link.type) {
                this.referer = link.referer ?: referer
                this.headers = link.headers ?: browserHeaders
                this.quality = link.quality
            })
        }
        Log.d(TAG, "loadExt $label -> emitted=${collected.size}")
        return collected.isNotEmpty()
    }

    private suspend fun emitByse(
        url: String,
        hubUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String,
    ): Boolean {
        val html = try {
            app.get(url, headers = browserHeaders + ("Referer" to hubUrl), timeout = 20L).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "byse embed falló (${e.message})")
            null
        }
        if (html == null || !html.contains("Byse Frontend")) {
            Log.d(TAG, "byse: no es Byse Frontend, extractor por defecto")
            return loadExt(url, hubUrl, subtitleCallback, callback, label)
        }
        return try {
            val hubHost = runCatching { "https://${java.net.URI(hubUrl).host}" }.getOrDefault(mainUrl)
            val sources = ByseHttpExtractor().extract(url, hubUrl, hubHost)
            Log.d(TAG, "byse sources=${sources.size}")
            var found = false
            for (s in sources) {
                for (sub in s.subtitles) subtitleCallback(sub)
                val isHls = s.url.contains(".m3u8")
                callback(newExtractorLink(name, "$label Byse", s.url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = hubHost
                    this.headers = browserHeaders + ("Referer" to hubUrl)
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
            if (!found) {
                Log.w(TAG, "byse: 0 sources")
                loadExt(url, hubUrl, subtitleCallback, callback, label)
            } else true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "byse error: ${e.message}")
            loadExt(url, hubUrl, subtitleCallback, callback, label)
        }
    }

    private suspend fun resolveUns(url: String): String? {
        val hash = url.substringAfter("#", "").substringBefore("?").trim()
        if (hash.isBlank()) return null
        val base = try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { "" }
        if (base.isBlank()) return null
        val resp = try {
            app.get("$base/api/public/resolve?file1=$hash", headers = browserHeaders + ("Referer" to url), timeout = 20L)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "uns resolve falló: ${e.message}")
            null
        } ?: return null
        return try { JSONObject(resp.text).optString("url").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    private suspend fun resolveTurbovio(
        inputUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): TurbovioResolved? {
        val parsed = try { java.net.URL(inputUrl) } catch (_: Exception) { return null }
        val host = parsed.host.lowercase()
        if (!host.contains("turboembed")) return null
        val slug = parsed.path.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null
        val pageUrl = inputUrl

        val c = aesEncrypt(slug, host)
        val resp = try {
            app.get(
                "https://$host/api/stream/tracking?c=$c",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to pageUrl,
                    "Origin" to "https://$host",
                    "Accept" to "*/*",
                ),
                timeout = 25L,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "turbovio tracking falló: ${e.message}")
            null
        } ?: return null
        val xt = resp.headers["x-tracking"] ?: resp.headers["X-Tracking"]
        if (xt.isNullOrBlank()) {
            Log.w(TAG, "turbovio sin x-tracking (http ${resp.code})")
            return null
        }
        val decrypted = aesDecrypt(xt, host)
        val obj = JSONObject(decrypted)
        val stream = obj.optJSONObject("stream") ?: obj
        val src = listOf("hls", "tiktok", "direct")
            .map { stream.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
        if (src.isNullOrBlank()) {
            Log.w(TAG, "turbovio stream sin hls")
            return null
        }
        Log.d(TAG, "turbovio src host=${try { java.net.URL(src).host } catch (_: Exception) { "?" }}")

        val subs = stream.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val subUrl = s.optString("url", "")
                if (subUrl.isBlank()) continue
                val lang = s.optString("language", s.optString("lang", "es"))
                subtitleCallback(com.lagradost.cloudstream3.SubtitleFile(lang, subUrl))
            }
        }
        return TurbovioResolved(src, referer = "https://$host/", origin = "https://$host")
    }

    private fun dayHex(): String = (System.currentTimeMillis() / 86_400_000L).toString(16)

    private fun buildKey(): String {
        val raw = KEYBASE + dayHex()
        return if (raw.length >= 32) raw.substring(0, 32) else raw.padEnd(32, '0')
    }

    private fun buildIv(host: String): String {
        val raw = IVBASE + toHex(host.toByteArray(Charsets.UTF_8))
        return if (raw.length >= 32) raw.substring(0, 32) else raw.padEnd(32, '0')
    }

    private fun aesEncrypt(text: String, host: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fromHex(buildKey()), "AES"), IvParameterSpec(fromHex(buildIv(host))))
        return toHex(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun aesDecrypt(hex: String, host: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fromHex(buildKey()), "AES"), IvParameterSpec(fromHex(buildIv(host))))
        return String(cipher.doFinal(fromHex(hex)), Charsets.UTF_8)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "hex invalido" }
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }

    private data class TurbovioResolved(val streamUrl: String, val referer: String, val origin: String)

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? {
        return object : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                val host = request.url.host
                if (host.contains("zilla-networks.com")) {
                    val builder = request.newBuilder()
                    zillaHeaders.forEach { (k, v) -> builder.header(k, v) }
                    return chain.proceed(builder.build())
                }
                val ref = extractorLink.referer?.takeIf { it.isNotBlank() }
                if (ref != null && (host.contains("northpeakdigitalworks") || host.endsWith(".cfd"))) {
                    val builder = request.newBuilder()
                    builder.header("User-Agent", UA)
                    builder.header("Referer", ref)
                    builder.header("Origin", ref.trimEnd('/'))
                    builder.header("Accept", "*/*")
                    return chain.proceed(builder.build())
                }
                return chain.proceed(request)
            }
        }
    }
}
