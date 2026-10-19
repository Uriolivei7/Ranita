package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URL
import java.util.regex.Pattern

class PlushdProvider : MainAPI() {
    companion object {
        var pluginContext: android.content.Context? = null
    }

    override var mainUrl = "https://tioplus.app"
    override var name = "PlusHD"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Movie,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "series" to "Series",
        "animes" to "Animes",
        "peliculas" to "Peliculas",
        "doramas" to "Doramas",
    )

    private val chrome127Ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    private val luluChromeCiphers = listOf(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
    )

    private val luluClients = java.util.concurrent.ConcurrentHashMap<Int, okhttp3.OkHttpClient>()

    private val luluIpv4Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = okhttp3.Dns.SYSTEM.lookup(hostname)
            return all.filter { it is java.net.Inet4Address }.ifEmpty { all }
        }
    }

    private val luluIpv6Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = okhttp3.Dns.SYSTEM.lookup(hostname)
            return all.filter { it is java.net.Inet6Address }.ifEmpty { all }
        }
    }

    @Volatile
    private var luluTlsProfile = 0

    @Volatile
    private var luluWebViewProbed = false

    private suspend fun probeLuluWebView(url: String): String {
        val ctx = pluginContext ?: return "sin contexto"
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val res = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                val done = kotlinx.coroutines.CompletableDeferred<String>()
                var webView: android.webkit.WebView? = null
                try {
                    val wv = android.webkit.WebView(ctx)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.userAgentString = chrome127Ua
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onReceivedHttpError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            Log.d("PlushdProvider-Lulu", "[Wv] http error ${errorResponse?.statusCode} ${request?.url}")
                            if (!done.isCompleted) done.complete("http ${errorResponse?.statusCode}")
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                            val js =
                                "(function(){if(window.__lulu)return;window.__lulu=1;var U='" + url + "';" +
                                    "var out={ua:navigator.userAgent,lang:navigator.language};var left=2;" +
                                    "function done(k,v){out[k]=v;if(--left===0){window.__pr=JSON.stringify(out);}}" +
                                    "function run(tag,h,cb){fetch(U,{cache:'no-store',credentials:'omit',headers:h})" +
                                    ".then(function(r){return r.text().then(function(t){" +
                                    "var m=t.match(/^(?!#)(\\S+)/m);var seg=m?new URL(m[1],U).href:'';" +
                                    "if(!seg){cb(tag+'|'+r.status+'|'+t.length+'|0|0');return;}" +
                                    "return fetch(seg,{cache:'no-store',credentials:'omit',headers:h})" +
                                    ".then(function(r2){return r2.arrayBuffer().then(function(b){cb(tag+'|'+r.status+'|'+t.length+'|'+r2.status+'|'+b.byteLength);});});});})" +
                                    ".catch(function(e){cb(tag+'|err|'+e);});}" +
                                    "run('plain',{},function(v){done('plain',v);});" +
                                    "run('noal',{'Accept-Language':''},function(v){done('noal',v);});})()"
                            view?.evaluateJavascript(js, null)
                            view?.postDelayed({
                                view?.evaluateJavascript("window.__pr") { pr ->
                                    Log.d("PlushdProvider-Lulu", "[Wv] fetch -> $pr")
                                    if (!done.isCompleted) done.complete("fetch $pr")
                                }
                            }, 6000L)
                        }
                    }
                    wv.loadUrl(url)
                    done.await()
                } finally {
                    webView?.destroy()
                }
            }
            res ?: "timeout"
        }
    }

    private fun luluClient(profile: Int): okhttp3.OkHttpClient {
        return luluClients.getOrPut(profile) {
            val builder = okhttp3.OkHttpClient.Builder()
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            when (profile) {
                2 -> builder.connectionSpecs(
                    listOf(
                        okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                            .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
                            .build()
                    )
                )
                3 -> builder.connectionSpecs(
                    listOf(
                        okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                            .cipherSuites(*luluChromeCiphers.toTypedArray())
                            .build()
                    )
                )
                4 -> builder.connectionSpecs(listOf(okhttp3.ConnectionSpec.COMPATIBLE_TLS))
                5 -> builder.dns(luluIpv4Dns)
                6 -> builder.dns(luluIpv6Dns)
            }
            builder.build()
        }
    }

    private fun luluRequest(url: String, referer: String, range: String? = null): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", chrome127Ua)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .removeHeader("Accept-Language")
            .removeHeader("accept-language")
        if (range != null) builder.header("Range", range)
        return builder.build()
    }

    private suspend fun probeLuluTls(url: String, referer: String): Int {
        if (luluTlsProfile != 0) return luluTlsProfile
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val host = runCatching { java.net.URL(url).host }.getOrDefault("")
            val inets = runCatching { okhttp3.Dns.SYSTEM.lookup(host) }.getOrNull()
            Log.d("PlushdProvider-Lulu", "DNS $host -> ${inets?.joinToString { it.hostAddress } ?: "error"}")
            val cronet = runCatching { Class.forName("org.chromium.net.CronetEngine"); true }.getOrDefault(false)
            Log.d("PlushdProvider-Lulu", "cronet disponible: $cronet")
            val found = (1..6).firstOrNull { profile ->
                val res = runCatching {
                    luluClient(profile).newCall(luluRequest(url, referer)).execute().use { r ->
                        val detail = if (profile == 1) {
                            val srv = r.headers["Server"] ?: "-"
                            val via = r.headers["Via"] ?: r.headers["X-Cache"] ?: "-"
                            val body = runCatching { r.body?.string()?.take(200) ?: "" }.getOrDefault("")
                            " server=$srv via=$via proto=${r.protocol} body=${body.replace("\n", " ")}"
                        } else ""
                        r.code to detail
                    }
                }.onFailure {
                    Log.w("PlushdProvider-Lulu", "perfil TLS $profile excepcion: ${it.message}")
                }.getOrNull()
                Log.d("PlushdProvider-Lulu", "perfil TLS $profile -> ${res?.first}${res?.second ?: ""}")
                res?.first != null && res.first in 200..299
            }
            if ((found ?: -1) <= 0 && !luluWebViewProbed) {
                luluWebViewProbed = true
                Log.d("PlushdProvider-Lulu", "probando Chromium/WebView del dispositivo...")
                val wv = runCatching { probeLuluWebView(url) }.getOrElse { "excepcion ${it.message}" }
                Log.d("PlushdProvider-Lulu", "[Wv] resultado: $wv")
            }
            found ?: -1
        }
        luluTlsProfile = result
        Log.d("PlushdProvider-Lulu", "perfil TLS elegido: $luluTlsProfile")
        return result
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/$page").document
        val home = document.select(".articlesList article").filter { !it.selectFirst("a")?.attr("target").equals("_blank") }
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a h2")?.text() ?: return null
        val link = this.selectFirst("a.itemA")?.attr("href") ?: return null
        val img = this.selectFirst("picture img")?.attr("data-src")

        val yearRegex = Regex("""\s*\((\d{4})\)$""")
        val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

        val searchType = when {
            link.contains("/serie") -> TvType.TvSeries
            link.contains("/anime") -> TvType.Anime
            link.contains("/pelicula") -> TvType.Movie
            link.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return when (searchType) {
            TvType.Movie -> newMovieSearchResponse(title, link, searchType) {
                this.posterUrl = img
                this.year = year
            }
            else -> newTvSeriesSearchResponse(title, link, searchType) {
                this.posterUrl = img
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/api/search/$query").document
        val results = document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
    }

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val tvType = when {
            url.contains("/serie") -> TvType.TvSeries
            url.contains("/anime") -> TvType.Anime
            url.contains("/pelicula") -> TvType.Movie
            url.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.TvSeries
        }

        val title = doc.selectFirst(".slugh1")?.text()?.trim() ?: ""
        val year = doc.selectFirst("span:contains(Año) a")?.text()?.toIntOrNull()

        val backimage = doc.selectFirst(".bg")?.attr("style")?.let {
            Regex("url\\(\"?(.*?)\"?\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""

        var verticalPoster = doc.select(".poster img, .data img").firstNotNullOfOrNull {
            val src = it.attr("data-src").ifBlank { it.attr("src") }

            if (src.isNotBlank() &&
                !src.contains("nGfjgUlES2WuYrHXNNF4fbGe2Eq") &&
                src.contains("tmdb.org") &&
                !src.contains("/episodes/") &&
                !src.contains("/seasons/")
            ) {
                src
            } else null
        }?.replace("original", "w342")

        if (verticalPoster.isNullOrBlank()) {
            verticalPoster = doc.selectFirst(".poster img")?.attr("src")?.replace("original", "w342") ?: backimage
        }

        val description = doc.selectFirst("div.description")?.text() ?: ""
        val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
        val epi = ArrayList<Episode>()

        if (tvType != TvType.Movie) {
            val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
            if (!script.isNullOrEmpty()) {
                val jsonRegex = Regex("seasonsJson\\s*=\\s*(\\{[^;]*\\});")
                val matchJson = jsonRegex.find(script)
                val jsonscript = matchJson?.groupValues?.get(1)

                if (!jsonscript.isNullOrEmpty()) {
                    try {
                        val seasonsMap = parseJson<Map<String, List<MainTemporadaElement>>>(jsonscript)
                        seasonsMap.values.forEach { list ->
                            list.forEach { info ->
                                val epTitle = info.title
                                val seasonNum = info.season
                                val epNum = info.episode
                                val img = info.image
                                val realimg = if (img.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                                val epurl = "$url/season/$seasonNum/episode/$epNum"
                                if (epTitle != null && seasonNum != null && epNum != null) {
                                    epi.add(newEpisode(epurl) {
                                        this.name = epTitle
                                        this.season = seasonNum
                                        this.episode = epNum
                                        this.posterUrl = realimg
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PlushdProvider", "Error al parsear seasonsJson: ${e.message}")
                    }
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> {
                newTvSeriesLoadResponse(title, url, tvType, epi) {
                    this.posterUrl = verticalPoster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = verticalPoster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
            else -> null
        }
    }

    private fun fixPelisplusHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
            .replaceFirst("https://doodstream.com", "https://dood.la")
            .replaceFirst("https://streamtape.com", "https://streamtape.cc")
            .replace("https://sblanh.com", "https://lvturbo.com")
    }

    private val REGEX_LINK = Pattern.compile(
        "^(https?:)?//(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*)$"
    )

    private fun fetchUrls(text: String): List<String> {
        val urls = ArrayList<String>()
        val regex = Regex("""(https?://[^\s"']+)""")
        regex.findAll(text).forEach { urls.add(it.value) }
        return urls
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val isLulu = extractorLink.url.contains("tnmr") || extractorLink.url.contains("lulu")
        if (isLulu && luluTlsProfile <= 0) {
            Log.d("PlushdProvider", "lulu -> sin interceptor (Cronet) perfil=$luluTlsProfile ${extractorLink.url.take(80)}")
            return null
        }
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()

                val isPlaylist = url.contains(".m3u8") || url.contains(".txt") || url.contains(".urlset")
                if (!isPlaylist && !url.contains(".ts") && !url.contains(".woff2")) {
                    return chain.proceed(request)
                }

                val response: Response
                if (isLulu) {
                    val profile = luluTlsProfile
                    Log.d("PlushdProvider", "lulu req headers=${request.headers.names().joinToString(",")}")
                    val fetched = runCatching {
                        val referer = extractorLink.referer.ifBlank { "https://luluvdo.com/" }
                        luluClient(profile).newCall(luluRequest(url, referer, request.header("Range"))).execute()
                    }.onFailure {
                        Log.e("PlushdProvider", "lulu perfil $profile fallo: ${it.message}")
                    }.getOrNull()
                    if (fetched == null) {
                        Log.w("PlushdProvider", "lulu perfil $profile null, cadena OkHttp")
                        response = chain.proceed(request.newBuilder().header("User-Agent", chrome127Ua).build())
                    } else {
                        Log.d("PlushdProvider", "lulu perfil $profile -> ${fetched.code} ${url.take(120)}")
                        response = fetched
                    }
                } else {
                    val newRequest = request.newBuilder()
                        .header("User-Agent", chrome127Ua)
                        .header("Referer", extractorLink.referer)
                        .header("Origin", mainUrl)
                        .build()
                    response = chain.proceed(newRequest)
                }

                try {
                    if (isPlaylist) {
                        Log.d("PlushdProvider", "intercept playlist ${response.code} ${url.take(120)}")
                        val peek = response.peekBody(2097152L)
                        val html = peek.string()
                        if (!isLulu && (html.contains("Just a moment", ignoreCase = true) ||
                                html.contains("Attention Required", ignoreCase = true))) {
                            Log.d("PlushdProvider", "Cloudflare detected in video stream, resolving...")
                            return cloudflareKiller.intercept(chain)
                        }
                        val streamInfCount = html.lines().count { it.contains("#EXT-X-STREAM-INF") }
                        if (url.contains(".m3u8") && html.startsWith("#EXTM3U") && html.contains("RESOLUTION=") && streamInfCount > 1) {
                            val lines = html.lines()
                            val filtered = mutableListOf<String>()
                            var skip = false
                            for (line in lines) {
                                if (line.contains("RESOLUTION=") && (
                                            line.contains("1920x1080") ||
                                                    line.contains("1920x800") ||
                                                    line.contains("1080p") ||
                                                    line.matches(Regex(".*RESOLUTION=\\d+x1080.*"))
                                            )) {
                                    Log.d("PlushdProvider", "Filtering out 1080p variant: $line")
                                    skip = true
                                    continue
                                }
                                if (skip) {
                                    if (line.startsWith("http") || line.startsWith("/") || line.startsWith("https")) {
                                        Log.d("PlushdProvider", "Filtering out 1080p URL: $line")
                                        skip = false
                                        continue
                                    }
                                    skip = false
                                }
                                filtered.add(line)
                            }
                            val filteredBody = filtered.joinToString("\n")
                            if (filteredBody.length != html.length) {
                                Log.d("PlushdProvider", "M3U8 filtered: ${html.length} -> ${filteredBody.length} chars (removed 1080p)")
                                val mediaType = response.body?.contentType()
                                if (mediaType != null) {
                                    return response.newBuilder().body(okhttp3.ResponseBody.create(mediaType, filteredBody)).build()
                                }
                            }
                        } else if (url.contains(".m3u8") && streamInfCount <= 1) {
                            Log.d("PlushdProvider", "M3U8 con $streamInfCount variante(s), sin filtrar (len=${html.length})")
                        }
                    }
                } catch (_: Exception) { }

                return response
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Referer" to data,
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache"
        )

        val doc = app.get(data, headers = headers).document
        val serverItems = doc.select("div ul.subselect li")
        Log.d("PlushdProvider", "=== loadLinks: ${serverItems.size} servidores encontrados en página ===")

        val loggingSubtitleCallback: (SubtitleFile) -> Unit = { file ->
            Log.d("PlushdProvider", "Subtítulo encontrado. URL: ${file.url}")
            subtitleCallback.invoke(file)
        }

        val pendingLinks = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()

        var hasValidServer = false
        val foundLinks = java.util.concurrent.atomic.AtomicInteger(0)

        val wrappedCallback2: (ExtractorLink) -> Unit = { link ->
            foundLinks.incrementAndGet()
            pendingLinks.add(link)
        }

        suspend fun emitPendingLinks() {
            while (true) {
                val link = pendingLinks.poll() ?: break
                val extraHeaders = mapOf(
                    "Referer" to data,
                    "Origin" to "$mainUrl",
                    "User-Agent" to chrome127Ua,
                    "Accept" to "*/*"
                )
                Log.d("PlushdProvider", "callback -> ${link.name} (source=$name)")
                callback(newExtractorLink(name, link.name, link.url) {
                    this.referer = link.referer ?: data
                    this.quality = link.quality
                    this.headers = link.headers + extraHeaders
                })
            }
        }

        suspend fun processServer(serverData: String, playerReferer: String, extractorReferer: String) {
            val tag = "PlushdProvider-Server"
            try {
                if (serverData.isNullOrEmpty()) return

                val decoded = String(Base64.decode(serverData, Base64.DEFAULT))
                Log.d(tag, "decoded: ${decoded.take(120)}")

                val isPlayerPath = !REGEX_LINK.matcher(decoded).matches()
                val url = if (isPlayerPath) {
                    "$mainUrl/player/${base64Encode(serverData.toByteArray())}"
                } else {
                    decoded
                }
                Log.d(tag, "usará ${if (isPlayerPath) "PLAYER" else "DIRECT"}: ${url.take(120)}")

                var videoUrl: String
                if (url.contains("/player/")) {
                    val playerHeaders = headers + mapOf("Referer" to playerReferer)
                    Log.d(tag, "fetcheando player page: $url")
                    val try1 = runCatching { app.get(url, headers = playerHeaders).document }
                    val playerDoc = try1.getOrNull() ?: run {
                        Log.w(tag, "intento 1 falló, reintentando con user-agent móvil...")
                        val retryHeaders = headers + mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                        )
                        runCatching { app.get(url, headers = retryHeaders).document }.getOrNull()
                    }
                    if (playerDoc == null) {
                        Log.w(tag, "player page inaccesible")
                        return
                    }
                    Log.d(tag, "HTML player page: ${playerDoc.html().length} chars")
                    videoUrl = extractUrlFromPlayerPage(playerDoc)
                } else {
                    videoUrl = url
                }

                if (videoUrl.isBlank()) {
                    Log.w(tag, "videoUrl en blanco después de extracción")
                    return
                }
                Log.d(tag, "videoUrl raw: ${videoUrl.take(120)}")

                val fixedLink = fixPelisplusHostsLinks(videoUrl)
                    .replace(Regex("""([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https://ww3.pelisplus.to.*"""), "")
                Log.d(tag, "fixedLink: ${fixedLink.take(120)}")

                if (fixedLink.isBlank()) {
                    Log.w(tag, "fixedLink en blanco después de fixPelisplusHostsLinks")
                    return
                }

                if (fixedLink.contains("#") && (
                            fixedLink.contains("upns.pro") ||
                                    fixedLink.contains("rpmstream.live") ||
                                    fixedLink.contains("strp2p.com") ||
                                    fixedLink.contains("4meplayer.pro") ||
                                    fixedLink.contains("pelisplusto")
                            )) {
                    Log.w(tag, "SPA hash (error 2001), saltando")
                    return
                }

                hasValidServer = true

                if (fixedLink.contains("turbovid")) {
                    val embUrl = fixedLink.replace("https://turbovidhls.com", "https://emturbovid.com")
                    Log.d(tag, "turbovid -> EmturbovidExtractor: ${embUrl.take(120)}")
                    loadExtractor(
                        url = embUrl,
                        referer = extractorReferer,
                        subtitleCallback = loggingSubtitleCallback,
                        callback = wrappedCallback2
                    )
                } else if (fixedLink.contains("vidhide")) {
                    Log.d(tag, "URL VidHide detectada, usando extractor directo...")
                    val ok = tryVidHideExtraction(
                        url = fixedLink,
                        referer = fixedLink,
                        subtitleCallback = loggingSubtitleCallback,
                        callback = wrappedCallback2
                    )
                    if (!ok) {
                        Log.w(tag, "VidHide directo fallo, probando loadExtractor...")
                        loadExtractor(
                            url = fixedLink,
                            referer = extractorReferer,
                            subtitleCallback = loggingSubtitleCallback,
                            callback = wrappedCallback2
                        )
                    }
                } else if (fixedLink.contains("lulu")) {
                    Log.d(tag, "URL LuLu detectada, usando desempaquetado propio...")
                    val ok = tryLuluExtraction(
                        url = fixedLink,
                        referer = extractorReferer,
                        callback = wrappedCallback2
                    )
                    if (!ok) {
                        Log.w(tag, "LuLu directo fallo, probando loadExtractor...")
                        loadExtractor(
                            url = fixedLink,
                            referer = extractorReferer,
                            subtitleCallback = loggingSubtitleCallback,
                            callback = wrappedCallback2
                        )
                    }
                } else {
                    Log.d(tag, "llamando loadExtractor...")
                    loadExtractor(
                        url = fixedLink,
                        referer = extractorReferer,
                        subtitleCallback = loggingSubtitleCallback,
                        callback = wrappedCallback2
                    )
                }
                Log.d(tag, "OK")
            } catch (e: Exception) {
                Log.e(tag, "Error: ${e.message}")
            }
        }

        coroutineScope {
            serverItems.toList().forEach { serverLi ->
                launch { processServer(serverLi.attr("data-server"), data, data) }
            }
        }
        emitPendingLinks()

        if (foundLinks.get() == 0 && hasValidServer) {
            Log.d("PlushdProvider", "=== loadLinks === No se encontraron links (1ª pasada), reintentando (2ª pasada)...")
            coroutineScope {
                serverItems.toList().forEach { serverLi ->
                    launch { processServer(serverLi.attr("data-server"), "$mainUrl/", "$mainUrl/") }
                }
            }
            emitPendingLinks()
        }

        Log.d("PlushdProvider", "=== loadLinks FIN: hasValidServer=$hasValidServer linksFound=${foundLinks.get()} ===")
        return foundLinks.get() > 0
    }

    private suspend fun tryVidHideExtraction(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("vidhide")) return false
        val tag = "PlushdProvider-VidHide"
        try {
            val vidReferer = if (url.contains("vidhide")) url else referer
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to vidReferer,
                "Accept-Language" to "es",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            val html = app.get(url, headers = headers).text
            var unpacked = ""
            var m3u8Url: String? = Regex("""(https?://[^"'<>\s]+\.m3u8[^"'<>\s]*)""").find(html)?.value

            if (m3u8Url == null) {
                val evalInvoke = Regex("""eval\(function\(p,a,c,k,e,d\)\{.+?\}\('""", RegexOption.DOT_MATCHES_ALL)
                for (em in evalInvoke.findAll(html)) {
                    val argStart = em.range.last + 1
                    val argsMatch = Regex("""',(\d+),(\d+),'([^']+)'\.split\('\|'\)""").find(html, argStart) ?: continue
                    val p = html.substring(argStart, argsMatch.range.first)
                    val a = argsMatch.groupValues[1].toIntOrNull() ?: 36
                    val c = argsMatch.groupValues[2].toIntOrNull() ?: 0
                    val k = argsMatch.groupValues[3].split("|").toTypedArray()
                    var decoded = p
                    for (idx in c - 1 downTo 0) {
                        val key = idx.toString(a)
                        val value = k.getOrElse(idx) { "" }
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            decoded = decoded.replace(
                                Regex("\\b${Regex.escape(key)}\\b"),
                                Regex.escapeReplacement(value)
                            )
                        }
                    }
                    val unescaped = decoded.replace("\\'", "'")
                    unpacked = unescaped
                    m3u8Url = Regex("""(https?://[^"'<>\s]+\.m3u8[^"'<>\s]*)""").find(unescaped)?.value
                    if (m3u8Url != null) break
                }
            }

            val linksMap = mutableMapOf<String, String>()
            for (src in listOf(html, unpacked)) {
                if (linksMap.isNotEmpty()) break
                Regex("""links\s*[=:]\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(src)?.let { block ->
                    Regex(""""(hls\d)"\s*:\s*"([^"]+)"""").findAll(block.groupValues[1]).forEach { m ->
                        linksMap[m.groupValues[1]] = m.groupValues[2]
                    }
                }
                if (linksMap.isEmpty()) {
                    Regex(""""(hls\d)"\s*:\s*"([^"]+)"""").findAll(src).forEach { m ->
                        linksMap[m.groupValues[1]] = m.groupValues[2]
                    }
                }
            }

            if (linksMap.isNotEmpty()) {
                val preferOrder = listOf("hls3", "hls2", "hls4")
                val orderedKeys = preferOrder.filter { linksMap.containsKey(it) } + linksMap.keys.filter { it !in preferOrder }
                val baseHost = runCatching {
                    val uri = java.net.URI(vidReferer)
                    "${uri.scheme}://${uri.host}"
                }.getOrNull() ?: "https://vidhidepro.com"
                data class Variant(val key: String, val url: String)
                val resolved = orderedKeys.mapNotNull { key ->
                    var u = linksMap[key] ?: return@mapNotNull null
                    if (u.startsWith("/")) {
                        u = "$baseHost$u"
                        Log.d(tag, "URL relativa, prependido base: ${u.take(120)}")
                    }
                    Variant(key, u)
                }
                if (resolved.isNotEmpty()) {
                    val probeHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
                        "Referer" to vidReferer,
                    )
                    val reachable = java.util.Collections.synchronizedSet(mutableSetOf<Variant>())
                    resolved.forEach { v ->
                        try {
                            Log.d(tag, "probando ${v.key} ${v.url.take(120)}")
                            val probe = withTimeoutOrNull(10000L) {
                                val resp = app.get(v.url, headers = probeHeaders, timeout = 10000L)
                                val body = resp.text
                                val ok = resp.code in 200..299 && body.trimStart().startsWith("#EXTM3U")
                                Triple(resp.code, body.length, ok)
                            }
                            if (probe != null) {
                                Log.d(tag, "probe ${v.key} -> ${probe.first} (${probe.second}B, m3u8=${probe.third})")
                                if (probe.third) reachable.add(v)
                            } else {
                                Log.d(tag, "probe ${v.key} -> timeout")
                            }
                        } catch (_: Exception) { }
                    }
                    val toEmit = if (reachable.isNotEmpty()) {
                        orderedKeys.mapNotNull { k -> reachable.firstOrNull { it.key == k } }
                    } else {
                        Log.w(tag, "ningún master responde, emitiendo todas las variantes")
                        resolved
                    }
                    for (v in toEmit) {
                        Log.d(tag, "emit ${v.key} url=${v.url.take(120)}")
                        callback(newExtractorLink("PlusHD", "VidHidePro - ${v.key}", v.url, ExtractorLinkType.M3U8) {
                            this.referer = vidReferer
                        })
                    }
                    Log.d(tag, "emitted ${toEmit.size} variantes (hls keys=${linksMap.keys.sorted()})")
                    return true
                }
            }

            if (m3u8Url != null) {
                Log.d(tag, "M3U8 encontrado: ${m3u8Url.take(100)}")
                callback(newExtractorLink("PlusHD", "VidHide", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = vidReferer
                })
                return true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
        return false
    }

    private val luluPackedRegex = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    private fun unpackLuluPacked(html: String): String? {
        val m = luluPackedRegex.find(html) ?: return null
        return try {
            val dict = m.groupValues[4].split("|")
            val base = m.groupValues[2].toIntOrNull() ?: 36
            val count = m.groupValues[3].toIntOrNull() ?: 0
            val sb = StringBuilder(m.groupValues[1])
            for (idx in count - 1 downTo 0) {
                val key = idx.toString(base)
                val value = dict.getOrElse(idx) { "" }
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    val replaced = Regex("\\b${Regex.escape(key)}\\b").replace(sb, Regex.escapeReplacement(value))
                    sb.clear()
                    sb.append(replaced)
                }
            }
            sb.toString().replace("\\'", "'")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryLuluExtraction(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("lulu")) return false
        val tag = "PlushdProvider-Lulu"
        try {
            val pageHeaders = mapOf(
                "User-Agent" to chrome127Ua,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to url,
            )
            val html = app.get(url, headers = pageHeaders).text
            Log.d(tag, "GET ${url.take(110)} len=${html.length}")

            val decoded = unpackLuluPacked(html)
            if (decoded == null) {
                Log.w(tag, "no packed JS encontrado (len=${html.length})")
                return false
            }
            Log.d(tag, "decoded len=${decoded.length}")

            val linkRegex = Regex("""(?:file|src|source)\s*:\s*["']((?:https?:)?//[^"']+\.(?:m3u8|mp4|txt)[^"']*)["']""", RegexOption.IGNORE_CASE)
            var emitted = 0
            for (m in linkRegex.findAll(decoded)) {
                var u = m.groupValues[1].replace("\\/", "/").trim()
                if (u.startsWith("//")) u = "https:$u"
                if (u.contains(".m3u8") && luluTlsProfile == 0) probeLuluTls(u, "https://luluvdo.com/")
                val type = if (u.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                Log.d(tag, "emit ${u.take(120)}")
                callback(newExtractorLink(name, "LuluStream", u, type) {
                    this.referer = "https://luluvdo.com/"
                    this.headers = mapOf("User-Agent" to chrome127Ua)
                })
                emitted++
            }

            if (emitted == 0) {
                val m3u8 = Regex("""(https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""", RegexOption.IGNORE_CASE).find(decoded)?.value
                if (m3u8 != null) {
                    if (luluTlsProfile == 0) probeLuluTls(m3u8, "https://luluvdo.com/")
                    Log.d(tag, "emit (scan) ${m3u8.take(120)}")
                    callback(newExtractorLink(name, "LuluStream", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://luluvdo.com/"
                        this.headers = mapOf("User-Agent" to chrome127Ua)
                    })
                    emitted++
                }
            }

            Log.d(tag, "emitted=$emitted")
            return emitted > 0
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            return false
        }
    }

    private suspend fun extractUrlFromPlayerPage(playerDoc: org.jsoup.nodes.Document): String {
        val tag = "PlushdProvider-Player"
        Log.d(tag, "HTML total: ${playerDoc.html().length} chars")

        val strategies = listOf(
            "window.onload" to { doc: org.jsoup.nodes.Document ->
                val script = doc.selectFirst("script:containsData(window.onload)")?.data() ?: ""
                fetchUrls(script).firstOrNull() ?: ""
            },
            "iframe[src]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("iframe[src]")?.attr("src") ?: ""
            },
            "script.mp4" to { doc: org.jsoup.nodes.Document ->
                doc.select("script").firstOrNull { it.data().contains("https://") && it.data().contains(".mp4") }
                    ?.let { fetchUrls(it.data()).firstOrNull() } ?: ""
            },
            "script.m3u8" to { doc: org.jsoup.nodes.Document ->
                doc.select("script").firstOrNull { it.data().contains("https://") && it.data().contains("m3u8") }
                    ?.let { fetchUrls(it.data()).firstOrNull() } ?: ""
            },
            "video source[src]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("video source[src], video[src]")?.attr("src") ?: ""
            },
            "a[href]" to { doc: org.jsoup.nodes.Document ->
                doc.selectFirst("a[href$=\".mp4\"], a[href$=\".m3u8\"]")?.attr("href") ?: ""
            },
            "data-player" to { doc: org.jsoup.nodes.Document ->
                doc.select("[data-player], [data-video], [data-src]").firstNotNullOfOrNull {
                    val v = it.attr("data-player").ifBlank { it.attr("data-video").ifBlank { it.attr("data-src") } }
                    v.takeIf { it.isNotBlank() }
                } ?: ""
            },
        )

        for ((name, extract) in strategies) {
            val url = extract(playerDoc)
            if (url.isNotBlank()) {
                Log.d(tag, "Estrategia '$name' OK: ${url.take(100)}")
                return url
            } else {
                Log.d(tag, "Estrategia '$name' no encontró URL")
            }
        }

        Log.w(tag, "Ninguna estrategia encontró URL. HTML sample: ${playerDoc.html().take(500)}")
        return ""
    }
}