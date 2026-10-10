package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "VerAnime"


class VeranimeProvider : MainAPI() {
    companion object {
        var pluginContext: Context? = null
        const val SW_READY_JS = "h.includes('.m3u8')||h.includes('jwplayer')"
        const val SW_DUMP_JS = "(function(){try{NativeBridge.onHtml(document.documentElement.outerHTML);}catch(e){NativeBridge.onHtml('ERR:'+e);}})()"
    }

    override var mainUrl = "https://veranime.ninja"
    override var name = "AnimeNINJA"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/" to "Catálogo",
        "genero/accion/" to "Acción",
        "genero/aventura/" to "Aventura",
        "genero/comedia/" to "Comedia",
        "genero/drama/" to "Drama",
        "genero/fantasia/" to "Fantasía",
        "genero/romance/" to "Romance",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val host = request.url.host
                if (host.contains("zilla-networks.com")) {
                    val builder = request.newBuilder()
                    zillaHeaders.forEach { (k, v) -> builder.header(k, v) }
                    return chain.proceed(builder.build())
                }
                if (host.contains("premilkyway")) {
                    val ref = extractorLink.referer?.takeIf { it.isNotBlank() } ?: "https://streamwish.to/"
                    val origin = try {
                        val u = java.net.URL(ref)
                        "${u.protocol}://${u.host}"
                    } catch (_: Exception) { "https://streamwish.to" }
                    Log.d(TAG, "[intercept] SW CDN: ${request.url.toString().take(120)}")
                    val newRequest = request.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Referer", ref)
                        .header("Origin", origin)
                        .header("Accept", "*/*")
                        .build()
                    val response = chain
                        .withConnectTimeout(30, TimeUnit.SECONDS)
                        .withReadTimeout(30, TimeUnit.SECONDS)
                        .proceed(newRequest)
                    Log.d(TAG, "[intercept] SW CDN response: ${response.code} ${response.header("content-type", "?")}")
                    return response
                }
                return chain.proceed(request)
            }
        }
    }

    // ------------------------------------------------------------------
    // Listados: article.item > div.poster img[data-src] + div.data h3 a
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        val path = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = try {
            app.get("$mainUrl/$path", headers = browserHeaders, timeout = 60L).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "getMainPage ${request.name} falló: ${e.message}")
            return null
        }
        val home = document.select("article.item").mapNotNull { it.toCard() }.distinctBy { it.url }
        val hasNext = document.selectFirst("a[href\$=\"page/${page + 1}/\"]") != null
        Log.d(TAG, "getMainPage ${request.name} page=$page -> ${home.size} items hasNext=$hasNext ${System.currentTimeMillis() - t0}ms")
        if (home.isEmpty()) return null
        return newHomePageResponse(HomePageList(request.name, home), hasNext = hasNext)
    }

    private fun Element.toCard(): SearchResponse? {
        val link = this.selectFirst("div.data h3 a") ?: this.selectFirst("a[href*=/anime/]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/anime/")) return null
        val title = link.text().trim().takeIf { !it.isNullOrEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { !it.isNullOrEmpty() }
            ?: return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")?.takeIf { !it.isNullOrBlank() } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSearchCard(): SearchResponse? {
        val link = this.selectFirst("div.title a") ?: this.selectFirst("a[href*=/anime/]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/anime/")) return null
        val title = link.text().trim().takeIf { !it.isNullOrEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { !it.isNullOrEmpty() }
            ?: return null
        val img = this.selectFirst(".image img") ?: this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")?.takeIf { !it.isNullOrBlank() } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "search() llamado: '$query'")
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val doc = app.get("$mainUrl/?s=$encoded", headers = browserHeaders, timeout = 60L).document

            val containers = doc.select("div.result-item")
            val all = if (containers.isNotEmpty()) {
                val parsed = containers.mapNotNull { it.toSearchCard() }
                if (parsed.isEmpty()) Log.w(TAG, "search: ${containers.size} result-item sin parsear (markup cambió?)")
                parsed
            } else {
                doc.select("article.item").mapNotNull { it.toCard() }.distinctBy { it.url }
            }

            val results = all.filter { matchesQuery(it.name, query) }
            Log.d(TAG, "search '$query' -> ${all.size} total, ${results.size} filtrados ${System.currentTimeMillis() - t0}ms")
            results
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
    // Detalle: #seasons .se-c (multi-temporada) > ul.episodios li > /ver/...-episodio-N/
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "load($url)")
        val doc = try {

            app.get(url, headers = browserHeaders, timeout = 120L).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "load página falló: ${e.message}")
            return null
        }


        if ("/ver/" in url && "-episodio-" in url) {
            val epTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { !it.isNullOrEmpty() }
                ?: doc.selectFirst("head meta[property=og:title]")?.attr("content")?.trim()
                ?: "Episodio"
            val poster = fixUrlNull(
                doc.selectFirst("head meta[property=og:image]")?.attr("content")
            )
            Log.d(TAG, "load -> Episodio $epTitle ${System.currentTimeMillis() - t0}ms")
            return newMovieLoadResponse(epTitle, url, TvType.Anime, url) {
                this.posterUrl = poster
            }
        }

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { !it.isNullOrEmpty() }
            ?: doc.selectFirst("head meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Anime Online")?.trim()?.takeIf { !it.isNullOrEmpty() }
            ?: return null
        val plot = doc.selectFirst("#info .wp-content p")?.text()?.trim()?.takeIf { !it.isNullOrEmpty() }
            ?: doc.selectFirst("head meta[property=og:description]")?.attr("content")?.trim()
        val posterImg = doc.selectFirst("div.poster img")
        val poster = fixUrlNull(posterImg?.attr("data-src")?.takeIf { !it.isNullOrBlank() } ?: posterImg?.attr("src"))
        val year = doc.select("div.custom_fields").firstOrNull { it.text().contains("primera emisi", ignoreCase = true) }
            ?.selectFirst("span.valor")?.text()?.takeLast(4)?.toIntOrNull()
        val tags = doc.select("nav.genres a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val trailer = doc.selectFirst("div#trailer iframe, iframe[src*=youtube.com]")
            ?.attr("src")?.takeIf { !it.isNullOrBlank() }
            ?.replaceFirst("https://www.youtube.com/embed/", "https://www.youtube.com/watch?v=")
            ?.substringBefore("?")
            .let { if (it?.contains("watch?v=") == true) it else null }
        val recommendations = doc.select("article.item").mapNotNull { it.toCard() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(12)

        val episodes = mutableListOf<Episode>()
        val seasonBlocks = doc.select("#seasons .se-c")
        seasonBlocks.forEachIndexed { sIdx, block ->
            val season = block.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull() ?: (sIdx + 1)
            block.select("ul.episodios li").forEach { li ->
                val a = li.selectFirst(".episodiotitle a, a[href*=/ver/]") ?: return@forEach
                val epUrl = fixUrlNull(a.attr("href")) ?: return@forEach

                val numTxt = li.selectFirst(".numerando")?.text()?.trim()
                val epNum = numTxt?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: Regex("-episodio-(\\d+)").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@forEach
                val epName = a.text().trim().takeIf { !it.isNullOrEmpty() } ?: "Episodio $epNum"
                val epImg = li.selectFirst(".imagen img")
                episodes.add(newEpisode(fixUrl(epUrl)) {
                    this.name = epName
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epImg?.attr("data-src")?.takeIf { !it.isNullOrBlank() } ?: epImg?.attr("src"))
                })
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        Log.d(TAG, "load $title temporadas=${seasonBlocks.size} episodios=${episodes.size} ${System.currentTimeMillis() - t0}ms")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags.takeIf { !it.isNullOrEmpty() }
            this.year = year
            this.recommendations = recommendations
            addTrailer(trailer)
        }
    }

    // ------------------------------------------------------------------
    // Reproducción: AJAX doo_player_ajax -> saidochesto hub -> mirrors por idioma
    // ------------------------------------------------------------------

    private suspend fun dooPlayerAjax(pageUrl: String, post: String, nume: String, type: String): String? {
        return try {
            val body = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                ),
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type,
                ),
                timeout = 60L,
            ).text
            JSONObject(body).optString("embed_url").takeIf { !it.isNullOrBlank() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "doo_player_ajax falló: ${e.message}")
            null
        }
    }

    private fun fixMirrorHost(url: String): String {
        return url
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://filemooon.link", "https://filemoon.sx")
            .replaceFirst("https://filemoon0.top", "https://filemoon.sx")
            .replaceFirst("https://uqload.io", "https://uqload.cx")
            .replaceFirst("https://uqload.is", "https://uqload.cx")
            .replaceFirst("https://uqload.vc", "https://uqload.cx")
            .replaceFirst("https://uqload.com", "https://uqload.cx")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
            .replaceFirst("https://dood.sh", "https://playmogo.com")
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

    private val webViewMutex = Mutex()

    private suspend fun renderViaWebView(pageUrl: String, referer: String?, waitMs: Long = 12000L, readyJs: String? = null): String? {
        webViewMutex.withLock {
            return withContext(Dispatchers.Main) {
            val appCtx = pluginContext?.applicationContext ?: run {
                Log.w(TAG, "[WebView] sin context")
                return@withContext null
            }
            var webView: WebView? = null
            val mainHandler = Handler(Looper.getMainLooper())
            try {
                webView = WebView(appCtx)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }
                val deferred = CompletableDeferred<String?>()
                val polls = java.util.concurrent.atomic.AtomicInteger(0)
                val maxPolls = (waitMs / 2000L).toInt().coerceAtLeast(1)
                fun dump() {
                    if (deferred.isCompleted) return
                    try {
                        webView?.evaluateJavascript(SW_DUMP_JS, null)
                    } catch (_: Exception) {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                }
                fun pollOnce() {
                    if (deferred.isCompleted) return
                    try {
                        webView?.evaluateJavascript(
                            "(function(){try{var h=document.documentElement.outerHTML;NativeBridge.onPoll(($readyJs));}catch(e){NativeBridge.onPoll(false);}})()",
                            null
                        )
                    } catch (_: Exception) {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                }
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHtml(html: String) {
                        if (!deferred.isCompleted) deferred.complete(html)
                    }

                    @JavascriptInterface
                    fun onPoll(ready: Boolean) {
                        mainHandler.post {
                            if (deferred.isCompleted) return@post
                            if (ready) {
                                Log.d(TAG, "[WebView] listo antes de tiempo, dumpeando")
                                dump()
                                return@post
                            }
                            if (polls.incrementAndGet() >= maxPolls) {
                                dump()
                            } else {
                                mainHandler.postDelayed({ pollOnce() }, 2000L)
                            }
                        }
                    }
                }, "NativeBridge")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        polls.set(0)
                        if (readyJs != null) {
                            mainHandler.postDelayed({ pollOnce() }, 2000L)
                        } else {
                            mainHandler.postDelayed({ dump() }, waitMs)
                        }
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                }
                if (!referer.isNullOrBlank()) webView.loadUrl(pageUrl, mapOf("Referer" to referer))
                else webView.loadUrl(pageUrl)
                Log.d(TAG, "[WebView] renderizando ${pageUrl.take(100)}")
                withTimeoutOrNull(waitMs + 15000L) { deferred.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "[WebView] error grave: ${t::class.simpleName}: ${t.message}")
                null
            } finally {
                try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
                try { webView?.destroy() } catch (_: Exception) {}
            }
            }
        }
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
        val doc = try {
            app.get(pageUrl, headers = browserHeaders + ("Referer" to mainUrl), timeout = 120L).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "loadLinks pagina no disponible: ${e.message}")
            return false
        }

        val options = doc.select("li.dooplay_player_option")
        Log.d(TAG, "loadLinks opciones=${options.size}")
        var found = false
        for (opt in options) {
            val post = opt.attr("data-post").trim()
            val nume = opt.attr("data-nume").trim()
            val type = opt.attr("data-type").trim().ifBlank { "tv" }
            val optTitle = opt.selectFirst(".title")?.text()?.trim().takeIf { !it.isNullOrEmpty() } ?: "Servidor"
            if (post.isBlank() || nume.isBlank()) {
                Log.w(TAG, "opción sin ids: $optTitle")
                continue
            }
            val embedUrl = dooPlayerAjax(pageUrl, post, nume, type)
            if (embedUrl.isNullOrBlank()) {
                Log.w(TAG, "sin embed para $optTitle")
                continue
            }

            val hub = try {
                app.get(embedUrl, headers = browserHeaders + ("Referer" to pageUrl), timeout = 60L).text
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "hub falló: ${e.message}")
                continue
            }

            if ("OD_SUB" in hub || "OD_LAT" in hub || "OD_ES" in hub) {
                if (emitSaidochesto(hub, embedUrl, optTitle, subtitleCallback, callback)) found = true
            } else if (!emitCyberlockerJson(hub, embedUrl, optTitle, subtitleCallback, callback)) {

                var hubFound = false
                Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").findAll(hub)
                    .map { it.value }.distinct().forEach { u ->
                        callback(newExtractorLink(name, "$optTitle HLS", u, ExtractorLinkType.M3U8) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                        })
                        hubFound = true
                    }
                if (!hubFound) {
                    val iframes = Jsoup.parse(hub, embedUrl).select("iframe[src]")
                    Log.d(TAG, "hub genérico: ${iframes.size} iframes")
                    for (frame in iframes) {
                        val src = frame.attr("abs:src").trim()
                        if (src.isBlank()) continue
                        if (loadExtractorCollect(fixMirrorHost(src), embedUrl, subtitleCallback, callback, "$optTitle Mirror")) {
                            hubFound = true
                        }
                    }
                }
                if (hubFound) found = true
                else Log.w(TAG, "hub sin mirrors conocidos: ${embedUrl.take(80)}")
            } else {
                found = true
            }
        }
        Log.d(TAG, "loadLinks $pageUrl -> ${if (found) "OK" else "SIN LINKS"} ${System.currentTimeMillis() - t0}ms")
        return found
    }

    private suspend fun emitSaidochesto(
        hub: String,
        embedUrl: String,
        optTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false

        val langs = listOf(
            "OD_LAT" to "Latino",
            "OD_ES" to "Castellano",
            "OD_SUB" to "Subtitulado",
        )
        for ((block, langLabel) in langs) {
            val blockHtml = Regex("<div class=\"OD $block[^\"]*\">(.*?)</div>\\s*</div>", RegexOption.DOT_MATCHES_ALL)
                .find(hub)?.groupValues?.getOrNull(1) ?: continue
            val mirrors = Regex("go_to_player\\('([^']+)'\\)").findAll(blockHtml)
                .map { it.groupValues[1] }.distinct().toList()
            Log.d(TAG, "hub $langLabel: ${mirrors.size} mirrors")

            val results = mirrors.amap { mirror -> resolveSaidoMirror(mirror, optTitle, langLabel, embedUrl, subtitleCallback, callback) }
            if (results.any { it }) found = true
        }
        return found
    }

    private suspend fun resolveSaidoMirror(
        mirror: String,
        optTitle: String,
        langLabel: String,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val fixed = fixMirrorHost(mirror)
        val label = "$optTitle $langLabel"
        val isPlaylist = fixed.contains(".m3u8") || fixed.contains("/m3u8/")
        if (isPlaylist) {
            callback(newExtractorLink(name, label, fixed, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.headers = if (fixed.contains("zilla-networks.com")) zillaHeaders else browserHeaders + ("Referer" to embedUrl)
                this.quality = Qualities.Unknown.value
            })
            Log.d(TAG, "hub playlist directa -> $label")
            return true
        }
        if (loadExtractorCollect(fixed, embedUrl, subtitleCallback, callback, label)) return true
        if (fixed.contains("streamwish", ignoreCase = true)) {
            if (emitStreamwishFresh(fixed, embedUrl, label, callback)) return true
            if (emitStreamwishWebView(fixed, embedUrl, label, callback)) return true
        }
        Log.w(TAG, "sin links para $label (${fixed.take(80)})")
        return false
    }

    private suspend fun emitCyberlockerJson(
        hub: String,
        embedUrl: String,
        optTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        val items = Regex("\\{\\s*\"cyberlocker\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"link\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"language\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\"\\s*\\}")
            .findAll(hub).toList()
        if (items.isEmpty()) return false
        Log.d(TAG, "hub cyberlocker: ${items.size} mirrors")

        val sorted = items.sortedBy {
            val lang = it.groupValues[3].lowercase()
            when {
                lang.contains("lat") -> 0
                lang.contains("cast") || lang == "español" || lang == "espanol" -> 1
                else -> 2
            }
        }

        val results = sorted.amap { m -> resolveCyberMirror(m, embedUrl, optTitle, subtitleCallback, callback) }
        return results.any { it }
    }

    private suspend fun resolveCyberMirror(
        m: MatchResult,
        embedUrl: String,
        optTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
            val langRaw = m.groupValues[3]
            val langLabel = when {
                langRaw.contains("lat", ignoreCase = true) -> "Latino"
                langRaw.contains("cast", ignoreCase = true) -> "Castellano"
                langRaw.equals("español", ignoreCase = true) -> "Castellano"
                langRaw.contains("jap", ignoreCase = true) -> "Subtitulado"
                else -> langRaw
            }
            val fixed = fixMirrorHost(m.groupValues[2])
            val label = "$optTitle $langLabel [${m.groupValues[1]}]"
            val isPlaylist = fixed.contains(".m3u8") || fixed.contains("/m3u8/")
            if (isPlaylist) {
                callback(newExtractorLink(name, label, fixed, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.headers = if (fixed.contains("zilla-networks.com")) zillaHeaders else browserHeaders + ("Referer" to embedUrl)
                    this.quality = Qualities.Unknown.value
                })
                Log.d(TAG, "hub playlist directa -> $label")
                return true
            }
            if (fixed.contains("filemoon", ignoreCase = true) || fixed.contains("byse", ignoreCase = true)) {
                
                if (emitByse(fixed, embedUrl, subtitleCallback, callback, label)) return true
                if (loadExtractorCollect(fixed, embedUrl, subtitleCallback, callback, label)) return true
                Log.w(TAG, "sin links para $label (${fixed.take(80)})")
                return false
            }
            if (loadExtractorCollect(fixed, embedUrl, subtitleCallback, callback, label)) return true
            if (fixed.contains("streamwish", ignoreCase = true)) {
                if (emitStreamwishFresh(fixed, embedUrl, label, callback)) return true
                if (emitStreamwishWebView(fixed, embedUrl, label, callback)) return true
            }
            Log.w(TAG, "sin links para $label (${fixed.take(80)})")
            return false
    }


    private suspend fun emitByse(
        url: String,
        hubUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String,
    ): Boolean {
        val html = try {
            app.get(
                url,
                headers = browserHeaders + ("Referer" to hubUrl),
                timeout = 20L,
            ).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "byse embed falló (${e.message}): ${url.take(80)}")
            null
        }
        if (html == null || !html.contains("Byse Frontend")) {
            Log.d(TAG, "byse: no es Byse Frontend, extractor por defecto")
            return false
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
                    this.headers = browserHeaders + ("Referer" to hubHost)
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
            if (!found) Log.w(TAG, "byse: 0 sources")
            found
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "byse error: ${e.message}")
            false
        }
    }
    
    private val freshHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun fetchStreamwishFresh(pageUrl: String, hubUrl: String): String? {
        return try {
            withTimeoutOrNull(25_000L) {
                withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url(pageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Referer", hubUrl)
                        .build()
                    freshHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "[SW] fresh fetch code=${resp.code}")
                            null
                        } else {
                            resp.body?.string()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[SW] fresh fetch falló: ${e.message}")
            null
        }
    }

    private suspend fun emitStreamwishFresh(
        pageUrl: String,
        hubUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = fetchStreamwishFresh(pageUrl, hubUrl) ?: return false
        Log.d(TAG, "[SW] fresh fetch len=${html.length}")
        val urls = try {
            parseStreamwishHtml(html, pageUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[SW] fresh parse falló: ${e.message}")
            emptyList()
        }
        if (urls.isEmpty()) return false
        for (u in urls) {
            val isHls = u.contains(".m3u8")
            callback(newExtractorLink(name, "$label [SW]", u, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = pageUrl
                this.headers = browserHeaders + ("Referer" to pageUrl)
                this.quality = Qualities.Unknown.value
            })
        }
        Log.d(TAG, "[SW] fresh OK: ${urls.size} urls -> $label")
        return true
    }

    private fun unpackDeanEdwards(packed: String, base: Int, count: Int, dictRaw: String): String? {
        return try {
            val k = dictRaw.split("|").toTypedArray()
            val result = StringBuilder(packed)
            for (idx in count - 1 downTo 0) {
                val key = idx.toString(base)
                val value = k.getOrElse(idx) { "" }
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    val replaced = Regex("\\b${Regex.escape(key)}\\b").replace(result, Regex.escapeReplacement(value))
                    result.clear()
                    result.append(replaced)
                }
            }
            result.toString().replace("\\'", "'")
        } catch (_: Exception) { null }
    }

    private suspend fun parseStreamwishHtml(html: String, pageUrl: String): List<String> {
        val out = mutableListOf<String>()
        fun resolveUrl(u: String): String {
            var r = u.replace("\\/", "/").trim()
            if (r.startsWith("//")) r = "https:$r"
            if (r.startsWith("/")) {
                val base = try {
                    val uu = java.net.URL(pageUrl)
                    "${uu.protocol}://${uu.host}"
                } catch (_: Exception) { "" }
                r = base + r
            }
            return r
        }
        val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
        val mp4Regex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m4v)[^"'\s<>]*)""")
        val fileRegex = Regex("""(?:file|src)\s*:\s*["']((?:https?:)?//[^"']+)["']""")
        for (m in m3u8Regex.findAll(html)) out.add(m.value)
        if (out.isEmpty()) {
            for (m in fileRegex.findAll(html)) {
                val f = resolveUrl(m.groupValues[1])
                if (f.contains(".m3u8") || f.contains(".mp4") || f.contains(".m4v")) out.add(f)
            }
        }
        if (out.isEmpty()) {
            for (m in mp4Regex.findAll(html)) out.add(m.value)
        }
        if (out.isEmpty()) {
            val packerRegex = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            for (pm in packerRegex.findAll(html)) {
                try {
                    val decoded = unpackDeanEdwards(
                        pm.groupValues[1],
                        pm.groupValues[2].toIntOrNull() ?: 36,
                        pm.groupValues[3].toIntOrNull() ?: 0,
                        pm.groupValues[4]
                    ) ?: continue
                    for (m in m3u8Regex.findAll(decoded)) {
                        Log.d(TAG, "[SW] M3U8 (eval): ${m.value.take(120)}")
                        out.add(m.value)
                    }
                    if (out.isEmpty()) for (m in fileRegex.findAll(decoded)) {
                        val f = resolveUrl(m.groupValues[1])
                        if (f.contains(".m3u8") || f.contains(".mp4") || f.contains(".m4v")) out.add(f)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                if (out.isNotEmpty()) break
            }
        }
        if (out.isEmpty()) {
            Log.w(TAG, "[SW] No M3U8/MP4 pageHasJW=${html.contains("jwplayer")} hasSources=${html.contains("sources")} hasEval=${html.contains("eval(")} len=${html.length}")
        }
        return out.distinct()
    }

    private suspend fun emitStreamwishWebView(
        pageUrl: String,
        hubUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(TAG, "[SW] WebView fallback: $pageUrl")
        val rendered = try {
            renderViaWebView(pageUrl, hubUrl, readyJs = SW_READY_JS)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[SW] WebView falló: ${e.message}")
            null
        } ?: return false
        val urls = try {
            parseStreamwishHtml(rendered, pageUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[SW] parse falló: ${e.message}")
            emptyList()
        }
        if (urls.isEmpty()) return false
        for (u in urls) {
            val isHls = u.contains(".m3u8")
            callback(newExtractorLink(name, "$label [SW-Web]", u, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = pageUrl
                this.headers = browserHeaders + ("Referer" to pageUrl)
                this.quality = Qualities.Unknown.value
            })
        }
        Log.d(TAG, "[SW] WebView OK: ${urls.size} urls -> $label")
        return true
    }
}
