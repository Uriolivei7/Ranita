package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class InfinityProvider : MainAPI() {
    override var mainUrl = "https://strymix.com"
    override var name = "Infinity"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false

    private val apiBase = "https://strymix.com/admin_panel/api/api.php"
    private val apiKey = "cda11ZC2PFbw7DxG1rMYhfn4R0v6TNUjesiocOSJBzH5utyaKg"
    private val TAG = "Infinity"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Accept" to "application/json, */*",
    )

    private val activationUrl = "https://fluxa.strymix.com/activar_25.php"
    private val activationLock = Any()
    @Volatile private var lastActivationMs = 0L
    private companion object {
        const val ACTIVATION_COOLDOWN_MS = 30L * 60L * 1000L
    }

    private fun tryActivate(reason: String): Boolean {
        synchronized(activationLock) {
            if (System.currentTimeMillis() - lastActivationMs < ACTIVATION_COOLDOWN_MS) {
                Log.d(TAG, "[activar] cooldown ($reason), omito POST")
                return false
            }
            lastActivationMs = System.currentTimeMillis()
        }
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(activationUrl)
                .post("".toRequestBody(null))
                .header("User-Agent", browserHeaders["User-Agent"] ?: "")
                .header("Referer", activationUrl)
                .header("Origin", "https://fluxa.strymix.com")
                .build()
            client.newCall(req).execute().use { resp ->
                Log.d(TAG, "[activar] POST -> ${resp.code} ($reason)")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "[activar] fallo: ${e.message}")
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (!request.url.host.contains("strymix.com")) {
                return@Interceptor chain.proceed(request)
            }
            val h = extractorLink.headers
            fun authed(): Request = request.newBuilder()
                .header("User-Agent", h["User-Agent"] ?: "")
                .header("Referer", h["Referer"] ?: "https://fluxa.strymix.com/")
                .header("Origin", "https://fluxa.strymix.com")
                .header("Accept", "*/*")
                .build()
            var response = try {
                chain.proceed(authed())
            } catch (e: Exception) {
                Log.w(TAG, "[cdn] fallo ${url.take(100)}: ${e.message}")
                throw e
            }
            Log.d(TAG, "[cdn] ${request.method} ${url.take(100)} -> ${response.code}")

            if (response.code == 403 && tryActivate("403 en ${request.url.host}")) {
                Log.w(TAG, "[cdn] 403, reintentando tras activar")
                response.close()
                response = try {
                    chain.proceed(authed())
                } catch (e: Exception) {
                    Log.w(TAG, "[cdn] retry falló: ${e.message}")
                    throw e
                }
                Log.d(TAG, "[cdn] retry -> ${response.code} ${url.take(100)}")
            }
            response
        }
    }

    private val categories = listOf(
        "5" to "24/7", "14" to "Cine 24/7", "11" to "HD",
        "19" to "Perú", "27" to "Infantiles", "26" to "Películas",
        "10" to "Entretenimiento", "12" to "USA", "13" to "Ahorro Datos",
        "25" to "Noticias", "7" to "Cultura", "6" to "Deportes",
        "2" to "Música", "29" to "Costa Rica", "28" to "Panamá",
        "17" to "Colombia", "24" to "Guatemala", "23" to "México",
        "22" to "Religión", "21" to "Puerto Rico", "20" to "Chile",
        "18" to "España", "16" to "Argentina", "15" to "El Salvador",
        "9" to "Nicaragua", "8" to "Uruguay",
    )

    override val mainPage = mainPageOf(
        *categories.map { (cid, label) -> "cat:$cid" to label }.toTypedArray()
    )

    private fun channelUrl(id: String) = "$mainUrl/infinity/channel/$id"

    private fun channelIdOf(data: String): String? =
        Regex("""/channel/(\d+)""").find(data)?.groupValues?.get(1)

    private suspend fun apiGet(params: Map<String, String>): JSONObject? {
        return try {

            val qs = params.entries.joinToString("&") { (k, v) ->
                if (v.isEmpty()) k else "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val res = app.get("$apiBase?$qs", headers = browserHeaders, timeout = 20L)
            if (!res.isSuccessful) {
                Log.w(TAG, "api ${params.toList().firstOrNull()} -> HTTP ${res.code}")
                return null
            }
            JSONObject(res.text)
        } catch (e: Exception) {
            Log.e(TAG, "api fail: ${e.message}")
            null
        }
    }

    private fun JSONObject.toChannel(): SearchResponse? {
        val id = when (val raw = opt("channel_id")) {
            is Number -> raw.toLong().toString()
            else -> (raw?.toString() ?: "").trim()
        }.trim()
        val title = optString("channel_name").trim()
        if (id.isBlank() || id == "0" || title.isBlank()) return null
        val poster = optString("channel_image").trim().takeIf { it.isNotBlank() }
        return newLiveSearchResponse(title, channelUrl(id), TvType.Live) {
            this.posterUrl = poster
        }
    }

    private fun parsePosts(json: JSONObject): List<SearchResponse> {
        val arr = json.optJSONArray("posts") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            obj.toChannel()?.let { out.add(it) }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val cid = request.data.removePrefix("cat:")
        val json = apiGet(mapOf(
            "get_category_posts" to "",
            "id" to cid,
            "page" to page.toString(),
            "count" to "50",
            "api_key" to apiKey,
        )) ?: return null
        val items = parsePosts(json)
        if (items.isEmpty()) {
            Log.w(TAG, "getMainPage '${request.name}' p$page: 0 items")
            return null
        }
        val totalPages = json.optInt("pages", page)
        Log.d(TAG, "getMainPage '${request.name}' p$page: ${items.size} canales")
        return newHomePageResponse(HomePageList(request.name, items), page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = apiGet(mapOf(
            "get_search_results" to "",
            "search" to query,
            "api_key" to apiKey,
        )) ?: return emptyList()
        val items = parsePosts(json)
        Log.d(TAG, "search '$query': ${items.size}")
        return items
    }

    private fun cleanHtml(html: String): String =
        Jsoup.parse(html).text().trim().take(500)

    override suspend fun load(url: String): LoadResponse? {
        val id = channelIdOf(url) ?: return null
        val json = apiGet(mapOf(
            "get_post_detail" to "",
            "id" to id,
            "api_key" to apiKey,
        )) ?: return null

        val obj = json.optJSONObject("post") ?: json
        val title = obj.optString("channel_name").trim().ifBlank { "Canal $id" }
        val poster = obj.optString("channel_image").trim().takeIf { it.isNotBlank() }
        val plot = obj.optString("channel_description").trim().takeIf { it.isNotBlank() }?.let { cleanHtml(it) }
        Log.d(TAG, "load: $title -> $url")
        val ep = newEpisode(url) { this.name = "En Vivo"; this.posterUrl = poster }
        return newTvSeriesLoadResponse(title, url, TvType.Live, listOf(ep)) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val id = channelIdOf(fixUrl(data)) ?: return false
        val json = apiGet(mapOf(
            "get_post_detail" to "",
            "id" to id,
            "api_key" to apiKey,
        )) ?: return false
        val obj = json.optJSONObject("post") ?: json
        val streamUrl = obj.optString("channel_url").trim()
        if (streamUrl.isBlank()) {
            Log.w(TAG, "loadLinks $id: sin channel_url")
            return false
        }
        val label = obj.optString("channel_name").trim().ifBlank { "Canal $id" }
        val type = obj.optString("channel_type").trim().uppercase()
        val videoId = obj.optString("video_id").trim()

        val ua = obj.optString("user_agent").trim().takeIf { it.isNotBlank() }
            ?: (browserHeaders["User-Agent"] ?: "")
        Log.d(TAG, "loadLinks $label: type=$type url=${streamUrl.take(110)}")
        if ((type == "YOUTUBE" || streamUrl.contains("youtube.com") || streamUrl.contains("youtu.be")) && videoId.isNotBlank()) {
            return try {
                loadExtractor("https://www.youtube.com/watch?v=$videoId", null, subtitleCallback, callback)
            } catch (_: Exception) { false }
        }
        val linkType = when {
            streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
            else -> INFER_TYPE
        }
        callback(newExtractorLink(name, "$name [$label]", streamUrl, linkType) {
            this.referer = "https://fluxa.strymix.com/"
            this.headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://fluxa.strymix.com/",
            )
        })
        return true
    }
}
