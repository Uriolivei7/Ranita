package com.example

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val TAG = "SeriesDonghua"

class SeriesdonghuaProvider : MainAPI() {
    companion object {
        var pluginContext: android.content.Context? = null
    }

    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val sections = listOf(
        "donghuas-en-emision" to "En emisión",
        "donghuas-finalizados" to "Finalizados",
        "accion" to "Acción",
        "aventura" to "Aventura",
        "cultivacion" to "Cultivo",
        "fantasia" to "Fantasía",
        "romance" to "Romance",
    )

    override val mainPage = mainPageOf(
        "donghuas-en-emision" to "En emisión",
        "donghuas-finalizados" to "Finalizados",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    // ------------------------------------------------------------------
    // Listados
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
        val t0 = System.currentTimeMillis()
        val suffix = if (page <= 1) "" else "?page=$page"

        val wanted = (listOf(request.data to request.name) + sections.filter { it.first != request.data }).distinctBy { it.first }
        val deferred = wanted.map { (slug, label) ->
            slug to async {
                try {
                    val doc = app.get("$mainUrl/$slug$suffix", headers = browserHeaders, timeout = 30L).document
                    val items = doc.select("article.donghua-card").mapNotNull { it.toCard() }.distinctBy { it.url }
                    val hasNext = doc.selectFirst("a[href\$=\"page=${page + 1}\"]") != null
                    Triple(label, items, hasNext)
                } catch (e: Exception) {
                    Log.w(TAG, "sección $slug falló: ${e.message}")
                    Triple(label, emptyList<SearchResponse>(), false)
                }
            }
        }
        val results = deferred.map { it.second.await() }
        val lists = results.mapNotNull { (label, items, _) ->
            items.takeIf { it.isNotEmpty() }?.let {
                val name = if (page <= 1) label else "$label p.$page"
                HomePageList(name, it)
            }
        }
        val hasNext = results.any { it.third }
        Log.d(TAG, "getMainPage page=$page ${System.currentTimeMillis() - t0}ms | " +
            results.joinToString { "${it.first}=${it.second.size}" } + " | hasNext=$hasNext")
        if (lists.isEmpty()) null else newHomePageResponse(lists, hasNext = hasNext)
    }

    private fun Element.toCard(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href")).trim()
        if (href.isBlank()) return null
        val title = a.attr("title").removePrefix("Ver ").removeSuffix(" Sub Español").trim().takeIf { it.isNotEmpty() }
            ?: a.selectFirst("h3.card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.removePrefix("Donghua ")?.removeSuffix(" Sub Español")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "search() llamado: '$query'")
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")

            val doc = app.get("$mainUrl/buscar.php?s=$encoded", headers = browserHeaders, timeout = 30L).document
            val all = doc.select("article.donghua-card").mapNotNull { it.toCard() }.distinctBy { it.url }

            val results = all.filter { matchesQuery(it.name, query) }
            Log.d(TAG, "search '$query' -> ${all.size} total, ${results.size} filtrados ${System.currentTimeMillis() - t0}ms")
            results
        } catch (e: Exception) {
            Log.w(TAG, "search falló: ${e.message}")
            null
        }
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

    private fun cleanSeriesTitle(s: String?): String? {
        if (s.isNullOrBlank()) return null
        return s.replaceFirst(Regex("^Donghua:\\s*"), "")
            .replace("🥇", "")
            .replace("【Sub Español】", "")
            .split("|").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "load($url)")
        val doc = try {
            app.get(url, headers = browserHeaders, timeout = 30L).document
        } catch (e: Exception) {
            Log.w(TAG, "load página falló: ${e.message}")
            return null
        }


        if ("/episodio-" in url) {
            val ogTitle = doc.selectFirst("head meta[property=og:title]")?.attr("content")
            val title = ogTitle?.substringBefore(" Sub Español")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Episodio"
            val poster = fixUrlNull(doc.selectFirst("head meta[property=og:image]")?.attr("content"))
            Log.d(TAG, "load -> Episodio $title ${System.currentTimeMillis() - t0}ms")
            return newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = doc.selectFirst("head meta[property=og:description]")?.attr("content")?.trim()
            }
        }

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanSeriesTitle(doc.selectFirst("head meta[property=og:title]")?.attr("content"))
            ?: return null
        val plot = doc.selectFirst("head meta[property=og:description]")?.attr("content")?.trim()
        val poster = fixUrlNull(doc.selectFirst("head meta[property=og:image]")?.attr("content"))
        val tags = doc.select("div.genre-pill-list a.genre-pill").map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodes = doc.select("article.episode-card-item").mapNotNull { art ->
            val a = art.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href")).trim()
            if (href.isBlank()) return@mapNotNull null
            val num = art.attr("data-ep").toIntOrNull()
                ?: Regex("-episodio-(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val epName = a.attr("title").removeSuffix(" Sub Español").trim().takeIf { it.isNotEmpty() }
                ?: art.selectFirst("img")?.attr("alt")?.removePrefix("Donghua ")?.removeSuffix(" Sub Español")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Episodio $num"
            val epPoster = fixUrlNull(art.selectFirst("img")?.attr("src"))
            newEpisode(href) {
                this.name = epName
                this.episode = num
                this.season = 1
                this.posterUrl = epPoster
            }
        }.sortedBy { it.episode ?: 0 }

        Log.d(TAG, "load serie=$title episodios=${episodes.size} tags=${tags.size} ${System.currentTimeMillis() - t0}ms")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags.takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // Fuentes: POST /api/player/get-server -> embed_url -> dispatch
    // ------------------------------------------------------------------

    private suspend fun postPlayerServer(
        pageUrl: String,
        csrf: String,
        cookies: String,
        videoId: Int,
        serverIndex: Int
    ): String? {
        val t0 = System.currentTimeMillis()
        val resp = try {
            app.post(
                "$mainUrl/api/player/get-server",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "X-CSRF-TOKEN" to csrf,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                    "Origin" to mainUrl,
                ) + (if (cookies.isNotBlank()) mapOf("Cookie" to cookies) else emptyMap()),
                requestBody = """{"video_id":$videoId,"server_index":$serverIndex}"""
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                timeout = 30L,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "player/get-server v=$videoId s=$serverIndex excepción: ${e.message}")
            null
        } ?: return null
        val body = try {
            resp.text
        } catch (e: Exception) {
            Log.w(TAG, "player/get-server v=$videoId s=$serverIndex body falló: ${e.message}")
            ""
        }
        val url = try {
            JSONObject(body).optString("embed_url").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "player/get-server v=$videoId s=$serverIndex no es JSON (code=${resp.code}): ${body.take(200)}")
            null
        }
        if (url != null) {
            Log.d(TAG, "player/get-server v=$videoId s=$serverIndex -> ok ${System.currentTimeMillis() - t0}ms")
        } else {
            Log.w(TAG, "player/get-server v=$videoId s=$serverIndex -> code=${resp.code} sin embed_url: ${body.take(200)}")
        }
        return url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val pageUrl = data.substringBefore("?")
        Log.d(TAG, "loadLinks data='$data' -> pagina $pageUrl")
        val pageResp = try {
            app.get(pageUrl, headers = browserHeaders + ("Referer" to mainUrl), timeout = 30L)
        } catch (e: Exception) {
            Log.w(TAG, "loadLinks pagina no disponible: ${e.message}")
            return false
        }

        val cookieHeader = pageResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(TAG, "loadLinks cookies=${pageResp.cookies.keys} code=${pageResp.code}")
        val doc = try {
            pageResp.document
        } catch (e: Exception) {
            Log.w(TAG, "loadLinks documento no disponible: ${e.message}")
            return false
        }
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        if (csrf.isNullOrBlank()) {
            Log.w(TAG, "loadLinks sin csrf-token")
            return false
        }
        val buttons = doc.select("button.server-tab-btn")
        Log.d(TAG, "loadLinks servidores=${buttons.size}")

        var found = false
        for (btn in buttons) {
            val label = btn.select("span").firstOrNull { it.className().isBlank() }?.text()?.trim()
                ?: btn.text().trim().takeIf { it.isNotEmpty() } ?: "Servidor"
            val videoId = btn.attr("data-video-id").toIntOrNull()
            val serverIndex = btn.attr("data-server-index").toIntOrNull()
            if (videoId == null || serverIndex == null) {
                Log.w(TAG, "botón sin ids: $label")
                continue
            }
            val url = postPlayerServer(pageUrl, csrf, cookieHeader, videoId, serverIndex)
            if (url.isNullOrBlank()) {
                Log.w(TAG, "sin embed para $label")
                continue
            }

            if (url.contains("ok.ru")) {
                if (emitOkru(url, callback)) found = true
                else Log.w(TAG, "ok.ru sin manifest: ${url.take(80)}")
            } else if (url.contains("dailymotion.com")) {
                if (emitDailymotion(url, subtitleCallback, callback)) found = true
                else {
                    val canonical = canonicalDailymotion(url) ?: url
                    Log.w(TAG, "dailymotion: emitDailymotion sin resultado, probando loadExtractor con $canonical")
                    if (loadExtractorCollect(canonical, pageUrl, subtitleCallback, callback, label)) found = true
                    else Log.w(TAG, "sin links para $label (${url.take(80)})")
                }
            } else if (url.contains("rumble.com")) {
                if (emitRumble(url, callback)) found = true
                else {
                    Log.w(TAG, "rumble: emitRumble sin resultado, probando loadExtractor")
                    if (loadExtractorCollect(url, pageUrl, subtitleCallback, callback, label)) found = true
                    else Log.w(TAG, "sin links para $label (${url.take(80)})")
                }
            } else if (url.contains(".m3u8")) {
                callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                    this.referer = pageUrl
                    this.headers = mapOf(
                        "User-Agent" to browserHeaders["User-Agent"]!!,
                        "Referer" to pageUrl,
                    )
                })
                found = true
            } else {
                if (loadExtractorCollect(url, pageUrl, subtitleCallback, callback, label)) found = true
                else Log.w(TAG, "sin links para $label (${url.take(80)})")
            }
        }
        Log.d(TAG, "loadLinks $pageUrl -> ${if (found) "OK" else "SIN LINKS"} ${System.currentTimeMillis() - t0}ms")
        return found
    }

    private suspend fun loadExtractorCollect(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String,
    ): Boolean {
        val collected = mutableListOf<ExtractorLink>()
        val collector: (ExtractorLink) -> Unit = { link -> collected.add(link) }

        try {
            withTimeout(25_000L) { loadExtractor(url, referer, subtitleCallback, collector) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "loadExtractor timeout 25s para $label (${url.take(80)})")
            return false
        }
        if (collected.isEmpty()) return false
        for (link in collected) {
            callback(newExtractorLink(name, "$label ${link.name}".trim(), link.url) {
                this.referer = link.referer ?: referer
                this.quality = link.quality
                this.type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.headers = link.headers
            })
        }
        return true
    }

    // ------------------------------------------------------------------
    // Extractores propios (portados de Donghualife, verificados en PC)
    // ------------------------------------------------------------------

    private fun okruQuality(okName: String?): Int = when (okName?.lowercase()) {
        "full" -> Qualities.P1080.value
        "hd" -> Qualities.P720.value
        "sd" -> Qualities.P480.value
        "low" -> Qualities.P360.value
        "lowest", "mobile" -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    private suspend fun emitOkru(videoUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = Regex("""ok\.ru/(?:videoembed|video)/(\d+)""").find(videoUrl)?.groupValues?.getOrNull(1)
            ?: return false
        val embedUrl = "https://ok.ru/videoembed/$videoId"

        val metadata = try {
            val doc = app.get(embedUrl, referer = "https://ok.ru/", headers = browserHeaders, timeout = 30L).document
            val raw = doc.selectFirst("[data-options]")?.attr("data-options")
            if (raw.isNullOrBlank()) null
            else JSONObject(raw).optJSONObject("flashvars")?.optJSONObject("metadata")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "ok.ru $videoId falló: ${e.message}")
            null
        }

        var emitted = false
        metadata?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let { hls ->
            callback(newExtractorLink(name, "ok.ru", hls, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.quality = Qualities.P1080.value
            })
            Log.d(TAG, "ok.ru $videoId -> HLS")
            emitted = true
        }

        if (!emitted) {
            val videos = metadata?.optJSONArray("videos")
            if (videos != null) {
                for (i in videos.length() - 1 downTo 0) {
                    val v = videos.optJSONObject(i) ?: continue
                    val u = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val vn = v.optString("name").takeIf { it.isNotBlank() }
                    callback(newExtractorLink(name, "ok.ru ${vn ?: ""}".trim(), u, ExtractorLinkType.VIDEO) {
                        this.referer = embedUrl
                        this.quality = okruQuality(vn)
                    })
                    emitted = true
                }
            }
        }
        if (!emitted) Log.w(TAG, "ok.ru $videoId: sin URLs")
        return emitted
    }

    private fun dailymotionQuality(key: String): Int = when (key.lowercase()) {
        "1080", "hd1080" -> Qualities.P1080.value
        "720", "hd720" -> Qualities.P720.value
        "480", "sd" -> Qualities.P480.value
        "380", "360" -> Qualities.P360.value
        "240", "low" -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }


    private fun dailymotionId(url: String): String? =
        Regex("""[?&]video=([A-Za-z0-9]{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]{6,})""").find(url)
                ?.groupValues?.getOrNull(1)


    private fun canonicalDailymotion(url: String): String? =
        dailymotionId(url)?.let { "https://www.dailymotion.com/video/$it" }


    private suspend fun fetchDmMeta(id: String, referer: String, noHeaders: Boolean): JSONObject? {
        val meta = try {
            val reqHeaders = if (noHeaders) emptyMap() else browserHeaders + ("Referer" to referer)
            app.get(
                "https://www.dailymotion.com/player/metadata/video/$id",
                headers = reqHeaders,
                timeout = 30L,
            ).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "dailymotion $id metadata falló (${e.message})")
            null
        } ?: return null
        return try {
            JSONObject(meta)
        } catch (e: Exception) {
            Log.w(TAG, "dailymotion $id metadata no es JSON (${e.message}): ${meta.take(160)}")
            null
        }
    }

    private suspend fun emitDailymotion(
        videoUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = dailymotionId(videoUrl) ?: return false
        val referer = "https://www.dailymotion.com/embed/video/$id"

        var root: JSONObject? = null
        for (attempt in 0..1) {
            root = fetchDmMeta(id, referer, attempt > 0)
            if (root?.optJSONObject("qualities") != null) break
            if (root != null) {
                Log.w(TAG, "dailymotion $id sin qualities (intento $attempt, keys=${root.keys().asSequence().toList()}, err=${root.opt("error")})")
            }
        }
        root ?: return false

        try {
            val data = root.optJSONObject("subtitles")?.optJSONObject("data")
            if (data != null) {
                for (key in data.keys()) {
                    val entry = data.optJSONObject(key) ?: continue
                    val arr = entry.optJSONArray("urls")
                    val url = when {
                        arr == null || arr.length() == 0 -> null
                        arr.optJSONObject(0) != null -> arr.optJSONObject(0).optString("url").takeIf { it.isNotBlank() }
                        else -> arr.optString(0).takeIf { it.isNotBlank() }
                    } ?: continue
                    val label = entry.optString("label").takeIf { it.isNotBlank() } ?: key
                    subtitleCallback(SubtitleFile(label, url))
                    Log.d(TAG, "dailymotion $id -> sub $label")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dailymotion $id subtítulos: ${e.message}")
        }

        val qualities = root.optJSONObject("qualities")
        if (qualities == null) {
            Log.w(TAG, "dailymotion $id: sin qualities")
            return false
        }

        var emitted = false
        for (key in qualities.keys()) {
            val arr = qualities.optJSONArray(key)
            if (arr == null) {
                val single = qualities.optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() }
                if (single != null) {
                    emitDmLink(key, single, referer, callback)
                    emitted = true
                }
                continue
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                emitDmLink(key, u, referer, callback)
                emitted = true
            }
        }
        Log.d(TAG, "dailymotion $id -> ${if (emitted) "OK" else "0 URLs"}")
        return emitted
    }

    private suspend fun emitDmLink(
        key: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val isHls = url.contains(".m3u8")
        callback(newExtractorLink(name, "Dailymotion ${key.uppercase()}".trim(), url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = referer
            this.headers = browserHeaders + ("Referer" to referer)
            this.quality = if (key.equals("auto", true)) Qualities.Unknown.value else dailymotionQuality(key)
        })
    }


    private suspend fun emitRumble(
        videoUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = try {
            app.get(
                videoUrl,
                headers = browserHeaders + ("Referer" to "https://rumble.com/"),
                timeout = 30L,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "rumble embed falló (${e.message}), reintento sin headers: ${videoUrl.take(90)}")
            try {
                app.get(videoUrl, timeout = 30L)
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                Log.w(TAG, "rumble embed falló de nuevo: ${e2.message}")
                null
            }
        }
        if (resp != null) {
            val body = try {
                resp.text
            } catch (e: Exception) {
                Log.w(TAG, "rumble body falló: ${e.message}")
                ""
            }

            Log.d(TAG, "rumble embed -> code=${resp.code} len=${body.length} ${videoUrl.take(80)}")
            if (resp.code == 403 || resp.code == 401) {
                Log.w(TAG, "rumble bloqueado por Cloudflare (${resp.code}): sin WebView ni loadExtractor")
                return false
            }
            val clean = body.replace("\\/", "/")
            if (extractRumbleLinks(clean, callback)) return true

            if (!videoUrl.contains("/embed/")) {
                Regex("""https://rumble\.com/embed/[^"'\s\\]+""").find(clean)?.value?.let { embed ->
                    Log.d(TAG, "rumble: reintentando con embed $embed")
                    return emitRumble(embed, callback)
                }
            }
        }

        Log.d(TAG, "rumble: probando WebView para ${videoUrl.take(80)}")
        val wvHtml = renderViaWebView(videoUrl)?.replace("\\/", "/")
        if (wvHtml != null) {
            Log.d(TAG, "rumble WebView -> len=${wvHtml.length}")
            if (extractRumbleLinks(wvHtml, callback)) return true
        }
        Log.d(TAG, "rumble -> 0 URLs")
        return false
    }


    private suspend fun extractRumbleLinks(
        clean: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Regex("""https://rumble\.com/hls-vod/[^"'\s\\]+?playlist\.m3u8""").findAll(clean)
            .map { it.value }.distinct().forEach { u ->
                callback(newExtractorLink(name, "Rumble HLS", u, ExtractorLinkType.M3U8) {
                    this.referer = "https://rumble.com/"
                    this.headers = browserHeaders + ("Referer" to "https://rumble.com/")
                    this.quality = Qualities.Unknown.value
                })
                Log.d(TAG, "rumble -> HLS OK")
                return true
            }

        var emitted = false
        Regex("""https://[^"'\s\\]+\.rumble\.cloud[^"'\s\\]*?\.mp4[^"'\s\\]*""").findAll(clean)
            .map { it.value }.distinct().forEach { u ->
                callback(newExtractorLink(name, "Rumble MP4", u, ExtractorLinkType.VIDEO) {
                    this.referer = "https://rumble.com/"
                    this.headers = browserHeaders + ("Referer" to "https://rumble.com/")
                    this.quality = Qualities.Unknown.value
                })
                emitted = true
            }
        if (emitted) Log.d(TAG, "rumble -> MP4 OK")
        return emitted
    }


    private suspend fun renderViaWebView(pageUrl: String): String? = withContext(Dispatchers.Main) {
        val appCtx = pluginContext?.applicationContext ?: run {
            Log.w(TAG, "WebView sin contexto")
            return@withContext null
        }
        var webView: WebView? = null
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            val htmlDeferred = CompletableDeferred<String?>()
            var latestHtml: String? = null
            webView = WebView(appCtx)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                userAgentString = browserHeaders["User-Agent"]
            }
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onHtml(html: String) {
                    latestHtml = html
                    if (!htmlDeferred.isCompleted && html.contains("hls-vod")) {
                        htmlDeferred.complete(html)
                    }
                }
            }, "NativeBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {

                    fun poll(left: Int) {
                        if (htmlDeferred.isCompleted) return
                        view?.evaluateJavascript(
                            "(function(){NativeBridge.onHtml(document.documentElement.outerHTML);})();",
                            null,
                        )
                        if (left > 0) mainHandler.postDelayed({ poll(left - 1) }, 2000)
                    }
                    mainHandler.postDelayed({ poll(10) }, 3000)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    Log.w(TAG, "WebView error $errorCode $description url=${failingUrl?.take(80)}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    val url = request?.url?.toString() ?: "?"
                    if (!url.contains("favicon.ico")) {
                        Log.w(TAG, "WebView HTTP ${errorResponse?.statusCode} -> ${url.take(100)}")
                    }
                }
            }
            webView.loadUrl(pageUrl)
            try {
                withTimeout(15000L) { htmlDeferred.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "WebView timeout, devolviendo último HTML (len=${latestHtml?.length ?: 0})")
                latestHtml
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "WebView falló: ${e::class.simpleName}: ${e.message}")
            null
        } finally {
            try {
                webView?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Interceptor
    // ------------------------------------------------------------------

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                val url = request.url.toString()
                val referer = extractorLink.referer

                if (url.contains("okcdn.ru") || url.contains("ok.ru/")) {
                    val newReq = request.newBuilder()
                        .header("User-Agent", browserHeaders["User-Agent"]!!)
                        .header("Referer", referer ?: "https://ok.ru/")
                        .header("Accept", "*/*")
                        .build()
                    return chain.proceed(newReq)
                }

                return chain.proceed(request)
            }
        }
    }
}
