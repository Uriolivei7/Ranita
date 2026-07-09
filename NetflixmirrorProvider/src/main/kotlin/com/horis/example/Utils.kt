package com.horis.example

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import com.lagradost.api.Log
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Protocol
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// ---------------------------------------------------------------------------
// JSON / HTTP
// ---------------------------------------------------------------------------

val jsonMapper: ObjectMapper = jacksonObjectMapper().configure(
    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
).configure(
    JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
)

val JSONParser = object : ResponseParser {
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
        jsonMapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
        try { jsonMapper.readValue(text, kClass.java) } catch (_: Exception) { null }

    override fun writeValueAsString(obj: Any): String =
        jsonMapper.writeValueAsString(obj)
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> fromJson(text: String): T =
    jsonMapper.readValue(text, T::class.java)

inline fun <reified T : Any> fromJsonSafe(text: String): T? =
    try { jsonMapper.readValue(text, T::class.java) } catch (_: Exception) { null }

inline fun <reified T : Any> parseJson(text: String): T =
    jsonMapper.readValue(text, T::class.java)

inline fun <reified T : Any> tryParseJson(text: String): T? =
    try { jsonMapper.readValue(text, T::class.java) } catch (_: Exception) { null }

inline fun <reified T : Any> tryParseJsonList(text: String): List<T>? {
    val mapper = jacksonObjectMapper().configure(
        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )
    return try { mapper.readValue(text, object : TypeReference<List<T>>() {}) } catch (_: Exception) { null }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0
    for (part in runtime.split(" ")) {
        when {
            part.endsWith("h") -> totalMinutes += (part.removeSuffix("h").trim().toIntOrNull() ?: 0) * 60
            part.endsWith("m") -> totalMinutes += part.removeSuffix("m").trim().toIntOrNull() ?: 0
        }
    }
    return totalMinutes
}

// ---------------------------------------------------------------------------
// NetflixMirrorStorage – persistent cookie storage
// ---------------------------------------------------------------------------

object NetflixMirrorStorage {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("NetflixMirrorPrefs", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        prefs?.edit()?.apply {
            putString("nf_cookie", cookie)
            putLong("nf_cookie_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs?.getString("nf_cookie", null),
            prefs?.getLong("nf_cookie_timestamp", 0L) ?: 0L
        )
    }

    fun clearCookie() {
        prefs?.edit()?.apply {
            remove("nf_cookie")
            remove("nf_cookie_timestamp")
            apply()
        }
    }

    fun saveFullCookie(cookie: String) {
        prefs?.edit()?.apply {
            putString("nf_cookie_full", cookie)
            putLong("nf_cookie_full_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getFullCookie(): Pair<String?, Long> {
        return Pair(
            prefs?.getString("nf_cookie_full", null),
            prefs?.getLong("nf_cookie_full_timestamp", 0L) ?: 0L
        )
    }

    fun clearFullCookie() {
        prefs?.edit()?.apply {
            remove("nf_cookie_full")
            remove("nf_cookie_full_timestamp")
            apply()
        }
    }
}

var appContext: Context? = null

var currentBypassToken: String = ""

// ---------------------------------------------------------------------------
// Auth bypass – gets token_hash from verify.php via WebView (bypasses Cloudflare)
// ---------------------------------------------------------------------------

suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    if (!savedCookie.isNullOrBlank() && System.currentTimeMillis() - savedTimestamp < 54_000_000) {
        Log.d("BYPASS", "Using cached cookie (15h)")
        return savedCookie
    }
    Log.d("BYPASS", "Cache expired, fetching new token")

    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to "https://net22.cc",
        "Referer" to "https://net22.cc/verify2",
        "sec-ch-ua" to "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    )
    val formBody = FormBody.Builder()
        .add("g-recaptcha-response", UUID.randomUUID().toString())
        .build()
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    val request = okhttp3.Request.Builder()
        .url("https://net52.cc/verify.php")
        .post(formBody)
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()
    try {
        val response = client.newCall(request).execute()
        val newCookie = response.headers("Set-Cookie")
            .firstOrNull { it.startsWith("t_hash_t=") }
            ?.substringAfter("t_hash_t=")
            ?.substringBefore(";")
            ?.trim()
        response.close()
        if (!newCookie.isNullOrBlank()) {
            NetflixMirrorStorage.saveCookie(newCookie)
            Log.d("BYPASS", "Got new token: ${newCookie.take(60)}")
            return newCookie
        }
        Log.d("BYPASS", "No t_hash_t in Set-Cookie")
    } catch (e: Exception) {
        Log.d("BYPASS", "verify.php failed: ${e.message}")
        NetflixMirrorStorage.clearCookie()
    }
    return ""
}

suspend fun getNewTvUserToken(apiBase: String, ott: String): String {
    val (savedToken, savedTimestamp) = NetflixMirrorStorage.getFullCookie()
    if (!savedToken.isNullOrBlank() && System.currentTimeMillis() - savedTimestamp < 54000000) {
        Log.d("USERTOKEN", "Using cached user token")
        return savedToken
    }
    Log.d("USERTOKEN", "Fetching new user token for ott=$ott")

    val tHash = try { bypass("") } catch (_: Exception) { "" }
    val baseHeaders = buildNewTvHeaders(ott, emptyMap()) + mapOf("Cookie" to "t_hash_t=$tHash")

    val step1 = try { app.get("$apiBase/newtv/otp.php?ott=$ott", headers = baseHeaders).text } catch (_: Exception) { "{}" }
    Log.d("USERTOKEN", "step1=${step1.take(300)}")
    val step1Parsed = tryParseJson<NewTvOtpResponse>(step1)
    if (step1Parsed?.usertoken != null && step1Parsed.usertoken.length > 10) {
        NetflixMirrorStorage.saveFullCookie(step1Parsed.usertoken)
        return step1Parsed.usertoken
    }

    val otpCode = Regex("""(\d{4,8})""").find(step1Parsed?.pub_msg ?: "")?.groupValues?.get(1) ?: "111111"
    Log.d("USERTOKEN", "step2 trying GET with otp=$otpCode")
    try {
        val step2 = app.get("$apiBase/newtv/otp.php?ott=$ott&otp=$otpCode", headers = baseHeaders).text
        Log.d("USERTOKEN", "step2=${step2.take(300)}")
        val step2Parsed = tryParseJson<NewTvOtpResponse>(step2)
        if (step2Parsed?.usertoken != null && step2Parsed.usertoken.length > 10) {
            NetflixMirrorStorage.saveFullCookie(step2Parsed.usertoken)
            Log.d("USERTOKEN", "Got user token: ${step2Parsed.usertoken.take(60)}")
            return step2Parsed.usertoken
        }
        Log.d("USERTOKEN", "step2 no usertoken: ${step2Parsed?.pub_msg}")
    } catch (e: Exception) {
        Log.d("USERTOKEN", "step2 GET failed: ${e.message}")
    }
    return ""
}

private suspend fun webViewBypass(mainUrl: String): String? {
    val ctx = appContext ?: return null
    return try {
        withContext(Dispatchers.Main) {
            suspendCoroutine<String?> { cont ->
                var finished = false
                val handler = Handler(Looper.getMainLooper())
                val timeout = Runnable {
                    if (!finished) {
                        finished = true
                        Log.e("BYPASS", "WebView timeout")
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeout, 5000)

                val view = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (finished) return
                        Log.e("BYPASS", "WebView onPageFinished url=${url.take(80)}")
                        view.evaluateJavascript(
                            "document.body.innerText.substring(0, 500)",
                        ) { text ->
                            if (finished) return@evaluateJavascript
                            val bodyText = text?.trim('"')?.replace("\\\"", "\"")
                                ?.replace("\\n", "\n")?.replace("\\t", "\t") ?: ""
                            Log.e("BYPASS", "WebView body=${bodyText.take(200)}")
                            if (bodyText.contains("Just a moment") || bodyText.contains("Checking")) {
                                // Still on Cloudflare — wait for more page loads
                                Log.e("BYPASS", "Still on Cloudflare challenge, waiting...")
                                return@evaluateJavascript
                            }
                            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                            Log.e("BYPASS", "WebView cookies=${cookies.take(200)}")
                            val tHash = cookies.split(";").firstOrNull {
                                it.trim().startsWith("t_hash_t=")
                            }?.substringAfter("=")?.trim()
                            if (tHash != null && tHash.length > 10) {
                                finished = true; handler.removeCallbacks(timeout)
                                cont.resume(tHash)
                            } else {
                                if (bodyText.startsWith("{")) {
                                    val parsed = tryParseJson<NewTvTokenResponse>(bodyText)
                                    val hash = parsed?.token_hash
                                    if (hash != null && hash.length > 10) {
                                        finished = true; handler.removeCallbacks(timeout)
                                        cont.resume(hash)
                                        return@evaluateJavascript
                                    }
                                }
                                Log.e("BYPASS", "No token yet — waiting for reCAPTCHA completion or timeout")
                            }
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        Log.e("BYPASS", "WebView nav to ${request.url.toString().take(80)}")
                        return false
                    }
                }
                view.loadUrl("$mainUrl/verify.php")
            }
        }
    } catch (e: Exception) {
        Log.e("BYPASS", "WebView bypass error: ${e.message}")
        null
    }
}

// ---------------------------------------------------------------------------
// Retry helper for transient MySQL "Too many connections" errors
// ---------------------------------------------------------------------------

suspend fun <T> retryOnDbError(maxRetries: Int = 3, block: suspend () -> T): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            val msg = e.message.orEmpty() + e.cause?.message.orEmpty()
            if (msg.contains("Too many connections") || msg.contains("1040") || msg.contains("08004") || msg.contains("HY000")) {
                Log.d("Retry", "DB connection pool full (attempt ${attempt + 1}/$maxRetries), retrying in ${(attempt + 1) * 2}s")
                kotlinx.coroutines.delay(((attempt + 1) * 2000L))
            } else {
                throw e
            }
        }
    }
    throw lastException ?: Exception("Retry failed after $maxRetries attempts")
}

fun checkDbError(text: String) {
    if (text.startsWith("<") && (text.contains("mysqli_connect") || text.contains("Too many connections") || text.contains("1040"))) {
        throw Exception("mysqli_connect(): Too many connections (detected in response body)")
    }
}

// ---------------------------------------------------------------------------
// NewTV shared infrastructure
// ---------------------------------------------------------------------------

val newTvBaseHeaders = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma"        to "no-cache",
    "Expires"       to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent"    to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept"        to "application/json, text/plain, */*"
)

val newTvDomains = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT))

private var resolvedApiUrl: String = ""
private var cachedRawTokenHash: String = ""

suspend fun resolveApiUrl(): String = "https://net52.cc"

fun buildVerticalPosterUrl(id: String, ott: String = "nf"): String {
    return when (ott) {
        "pv" -> "https://imgcdn.kim/pv/v/$id.jpg"
        "hs" -> "https://imgcdn.kim/hs/v/$id.jpg"
        else -> "https://imgcdn.kim/poster/v/$id.jpg"
    }
}

fun buildBackgroundPosterUrl(id: String, ott: String = "nf"): String {
    return when (ott) {
        "pv" -> "https://imgcdn.kim/pv/h/$id.jpg"
        "hs" -> "https://imgcdn.kim/hs/h/$id.jpg"
        else -> "https://imgcdn.kim/poster/h/$id.jpg"
    }
}

fun buildVerticalPosterUrlWithProxy(id: String, ott: String = "nf"): String {
    val raw = buildVerticalPosterUrl(id, ott)
    return "https://wsrv.nl/?url=$raw&w=500"
}

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val headers = newTvBaseHeaders.toMutableMap()
    headers["Ott"] = ott
    extra.forEach { (k, v) -> headers[k] = v }
    return headers
}

fun buildPosterUrl(template: String?, id: String): String? {
    if (template.isNullOrBlank()) return null
    return if (template.contains("------------------"))
        template.replace("------------------", id)
    else
        template.trimEnd('/') + "/" + id + ".jpg"
}

// ---------------------------------------------------------------------------
// Shared NewTV data classes
// ---------------------------------------------------------------------------

data class NewTvId(val id: String)

data class NewTvLoadData(val title: String, val id: String)

data class NewTvTokenResponse(val token_hash: String? = null)

data class NewTvMainResponse(
    val status: String? = null,
    val post: List<NewTvPostCategory>? = null,
    val imgcdn_h: String? = null,
    val imgcdn_v: String? = null,
    val img_referer: String? = null
)

data class NewTvPostCategory(
    val ids: String? = null,
    val cate: String? = null,
    val row: String? = null
)

data class NewTvSearchResponse(
    val searchResult: List<NewTvSearchResult>? = null,
    val detailsimgcdn: String? = null,
    val imgcdn: String? = null,
    val img_referer: String? = null
)

data class NewTvSearchResult(
    val id: String,
    val t: String
)

data class NewTvLang(
    val l: String? = null,
    val s: String? = null
)

data class NewTvPostResponse(
    val status: String? = null,
    val title: String? = null,
    val main_id: String? = null,
    val desc: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val match: String? = null,
    val runtime: String? = null,
    val episodes: List<NewTvApiEpisode?>? = null,
    val season: List<NewTvSeason>? = null,
    val nextPageSeason: String? = null,
    val nextPageShow: Int? = null,
    val type: String? = null,
    val main_poster: String? = null,
    val morelike_poster: String? = null,
    val ep_poster: String? = null,
    val suggest: List<NewTvSuggest>? = null,
    val age: String? = null,
    val certification: String? = null,
    val ua: String? = null,
    val moredetails: List<NewTvMoredetail>? = null,
    val lang: List<NewTvLang>? = null
)

data class NewTvApiEpisode(
    val id: String? = null,
    val t: String? = null,
    val ep: String? = null,
    val epNum: String? = null,
    val s: String? = null,
    val sNum: String? = null,
    val time: String? = null,
    val timeVal: String? = null,
    val ep_desc: String? = null,
    val info: List<String>? = null
)

data class NewTvSeason(
    val id: String? = null,
    val selected: Boolean? = null,
    val s: String? = null,
    val t: String? = null
)

data class NewTvSuggest(
    val id: String
)

data class NewTvMoredetail(
    val k: String? = null,
    val v: String? = null
)

data class NewTvEpisodesResponse(
    val episodes: List<NewTvApiEpisode>? = null,
    val nextPageShow: Int? = null
)

data class NewTvPlayerResponse(
    val status: String? = null,
    val video_link: String? = null,
    val referer: String? = null
)

data class NewTvOtpResponse(
    val otp: String? = null,
    val status: String? = null,
    val usertoken: String? = null,
    val pub_msg: String? = null,
    val pub_msg_f_size: Int? = null,
    val pub_msg_color: String? = null
)

// ---------------------------------------------------------------------------
// Mobile API data classes
// ---------------------------------------------------------------------------

data class MobileSearchResult(
    val id: String = "",
    val t: String = ""
)

data class MobileSearchData(
    val head: String? = null,
    val searchResult: List<MobileSearchResult>? = null,
    val type: Int? = null
)

data class MobileEpisode(
    val complate: String = "",
    val ep: String = "",
    val id: String = "",
    val s: String = "",
    val t: String = "",
    val time: String = ""
)

data class MobileSeason(
    val ep: String = "",
    val id: String = "",
    val s: String = "",
    val sele: String = ""
)

data class MobileSuggest(
    val id: String = ""
)

data class MobilePostData(
    val desc: String? = null,
    val director: String? = null,
    val ua: String? = null,
    val episodes: List<MobileEpisode?>? = null,
    val genre: String? = null,
    val nextPage: Int? = null,
    val nextPageSeason: String? = null,
    val nextPageShow: Int? = null,
    val season: List<MobileSeason>? = null,
    val title: String? = null,
    val year: String? = null,
    val cast: String? = null,
    val match: String? = null,
    val runtime: String? = null,
    val suggest: List<MobileSuggest>? = null,
    val lang: List<NewTvLang>? = null
)

data class MobileEpisodesData(
    val episodes: List<MobileEpisode>? = null,
    val nextPage: Int = 0,
    val nextPageSeason: String = "",
    val nextPageShow: Int = 0
)

fun mobileHeaders(ott: String, cookie: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val headers = mutableMapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )
    if (cookie.isNotBlank()) {
        headers["Cookie"] = "t_hash_t=$cookie; hd=on; ott=$ott"
    }
    extra.forEach { (k, v) -> headers[k] = v }
    return headers
}

fun mobileCookies(cookie: String, ott: String): Map<String, String> = mapOf(
    "t_hash_t" to cookie,
    "hd" to "on",
    "ott" to ott
)

data class PlayHashResponse(
    val h: String? = null
)

data class Source(
    val file: String? = null,
    val label: String? = null,
    val type: String? = null
)

data class PlaylistTrack(
    val kind: String? = null,
    val file: String? = null,
    val label: String? = null,
    val language: String? = null
)

data class PlaylistItem(
    val sources: List<Source>? = null,
    val tracks: List<PlaylistTrack>? = null
)

val playPhpDomains = listOf("https://net52.cc", "https://net22.cc")

val customMasters = java.util.concurrent.ConcurrentHashMap<String, String>()
private val customMastersLogged = java.util.concurrent.atomic.AtomicBoolean(false)

fun setCustomMaster(id: String, master: String) {
    customMasters[id] = master
    Log.d("Netmirror", "setCustomMaster id=$id size=${master.length}")
}

fun m3u8CdnFixInterceptor(): Interceptor {
    Log.d("Netmirror", "m3u8CdnFixInterceptor() called - creating new interceptor")
    return Interceptor { chain ->
        var req = chain.request()
        val url = req.url.toString()
        if (url.contains("__cm=1")) {
            val id = Regex("""/hls/(\d+)\.m3u8""").find(url)?.groupValues?.get(1)
            if (id != null) {
                val master = customMasters[id]
                if (master != null) {
                    Log.d("Netmirror", "Serving custom master for id=$id")
                    val mediaType: MediaType = "application/vnd.apple.mpegurl".toMediaType()
                    val body = ResponseBody.create(mediaType, master)
                    val response = Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                    return@Interceptor response
                } else {
                    Log.w("Netmirror", "No custom master found for id=$id, falling through to server")
                }
            }
        }

        val cdnHost = Regex("https://([^/]+)/").find(url)?.groupValues?.get(1).orEmpty()
        if (cdnHost.contains("nm-cdn") || cdnHost.contains("freecdn") || cdnHost.contains("imgcdn")) {
            val existing = req.header("Cookie") ?: ""
            val parts = mutableListOf<String>()
            if (existing.isNotBlank()) {
                existing.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                    if (!it.startsWith("hd=")) {
                        parts.add(it)
                    }
                }
            }
            parts.add("hd=on")
            req = req.newBuilder().header("Cookie", parts.joinToString("; ")).build()
        }
        Log.d("Netmirror", "Interceptor firing for: $url")
        val resp: Response
        try {
            resp = chain.proceed(req)
        } catch (e: Exception) {
            val cdnHost = Regex("https://([^/]+)/").find(url)?.groupValues?.get(1).orEmpty()
            if (cdnHost.contains("nm-cdn") || cdnHost.contains("imgcdn") || cdnHost.contains("freecdn")) {
                Log.d("Netmirror", "CDN unreachable: $url - ${e.message}")
            }
            Log.e("Netmirror", "NETWORK ERROR: $url - ${e.message}")
            throw e
        }
        val ct = (resp.body?.contentType()?.toString() ?: "")
        val isM3u8 = url.contains(".m3u8") || ct.contains("mpegurl") || ct.contains("vnd.apple.mpegurl")
        if (cdnHost.contains("imgcdn") || cdnHost.contains("tv.imgcdn")) {
            Log.e("Netmirror", "IMGCDN REQUEST: $url code=${resp.code} ct=$ct len=${resp.body?.contentLength()}")
        }
        if (isM3u8) {
            val body = resp.body?.string() ?: return@Interceptor resp
            if (!body.startsWith("#EXT")) {
                Log.e("Netmirror", "M3U8 NOT valid: $url status=${resp.code} len=${body.length} first100=${body.take(100)}")
                return@Interceptor resp
            }
            val inParam = Regex("[?&]in=([^&#]+)").find(url)?.groupValues?.get(1)
            Log.d("Netmirror", "M3U8 OK: $url len=${body.length} hasBrokenCdn=${body.contains("https:///files/")} in=${inParam?.take(50)}")
            var fixed = body
            if (fixed.contains("https:///files/")) {
                fixed = fixed.replace("https:///files/", "https://net11.cc/hls/")
                Log.d("Netmirror", "CDN fix broken →net11.cc/hls/: $url")
                Log.d("Netmirror", "Fixed broken CDN URLs (→net11.cc/hls/): $url")
            }
            val relSegmentRegex = Regex("^(?!#)([^\n\r]+)$", RegexOption.MULTILINE)
            var segmentFixed = 0
            fixed = relSegmentRegex.replace(fixed) { match ->
                val line = match.value.trim()
                if (line.isBlank() || line.contains("in=")) line
                else if (line.startsWith("http")) line
                else {
                    segmentFixed++
                    val base = url.substringBeforeLast("/")
                    val suffix = if (inParam != null) {
                        if (line.contains("?")) "&in=$inParam" else "?in=$inParam"
                    } else ""
                    "$base/$line$suffix"
                }
            }
            if (segmentFixed > 0) {
                Log.d("Netmirror", "Fixed $segmentFixed relative segment URLs (base=$url)")
            }
            val newBody = ResponseBody.create(resp.body?.contentType(), fixed)
            return@Interceptor resp.newBuilder().body(newBody).build()
        }
        resp
    }
}



suspend fun getPlaylistUrl(
    mainUrl: String,
    ott: String,
    id: String,
    title: String,
    cookie: String = "",
    apiBase: String? = null
): Pair<String, List<PlaylistTrack>>? {
    val domains = (listOf(mainUrl.trimEnd('/')) + playPhpDomains).distinct()
    var playHash: String? = null
    var timestamp: String = "0"
    for (domain in domains) {
        try {
            val resp = app.post(
                "$domain/play.php",
                requestBody = FormBody.Builder().add("id", id).build(),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )
            val text = resp.text.trim()
            Log.d("PlayPhp", "Domain=$domain response=$text")
            if (text.startsWith("{")) {
                val parsed = tryParseJson<PlayHashResponse>(text)
                val h = parsed?.h
                if (!h.isNullOrBlank()) {
                    playHash = h.removePrefix("in=")
                    timestamp = playHash.split("::").getOrNull(2) ?: "0"
                    Log.d("PlayPhp", "Got hash=$playHash ts=$timestamp from $domain")
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("PlayPhp", "Domain=$domain failed: ${e.message}")
        }
    }
    if (playHash.isNullOrBlank()) {
        Log.w("PlayPhp", "All domains failed to get play hash")
        return null
    }
    val cleanHash = playHash.split("::").take(3).joinToString("::")
    val hlsDomains = listOf("https://net52.cc", "https://net22.cc").distinct()
    var tracks: List<PlaylistTrack> = emptyList()
    var sourceUrl: String? = null
    val plCookie = cookie
    val baseRef = hlsDomains.first()
    val plHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "Referer" to "$baseRef/play.php?id=$id",
        "Origin" to baseRef
    ) + if (plCookie.isNotBlank()) mapOf("Cookie" to plCookie) else emptyMap()
    val m3u8Referer = "$baseRef/playlist.php?id=$id&t=$title&tm=$timestamp&h=$cleanHash"
    val m3u8Headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Referer" to m3u8Referer
    ) + if (plCookie.isNotBlank()) mapOf("Cookie" to plCookie) else emptyMap()
    for (hlsDomain in hlsDomains) {
        try {
            val plResp = app.get(
                "$hlsDomain/playlist.php?id=$id&t=$title&tm=$timestamp&h=$cleanHash",
                headers = plHeaders
            )
            Log.d("PlayPhp", "playlist.php $hlsDomain raw=${plResp.text.take(300)}")
            val parsed = tryParseJsonList<PlaylistItem>(plResp.text)
            val first = parsed?.firstOrNull()
            if (first != null) {
                tracks = first.tracks.orEmpty()
                val src = first.sources?.firstOrNull()?.file
                if (!src.isNullOrBlank()) {
                    sourceUrl = if (src.startsWith("http")) src else "${hlsDomain}${src}"
                }
            }
            Log.d("PlayPhp", "Playlist $hlsDomain tracks=${tracks.size} sourceUrl=$sourceUrl")
            if (sourceUrl != null) {
                val body = app.get(sourceUrl, headers = m3u8Headers).text
                if (body.startsWith("#EXTM3U") && !body.contains("unknown::ep")) {
                    Log.d("Netmirror", "playlist source OK len=${body.length} hasBroken=${body.contains("https:///files/")}")
                    Log.e("PLAYURL", sourceUrl)
                    return Pair(sourceUrl, tracks)
                }
                Log.d("PlayPhp", "Source M3U8 body invalid: ${body.take(200)}")
                sourceUrl = null
            }
        } catch (e: Exception) {
            Log.w("PlayPhp", "playlist.php $hlsDomain failed: ${e.message}")
        }
    }
    for (hlsDomain in hlsDomains) {
        try {
            val m3u8Url = "$hlsDomain/hls/$id.m3u8?in=$cleanHash"
            Log.d("PlayPhp", "Trying direct M3U8: $m3u8Url")
            val body = app.get(m3u8Url, headers = m3u8Headers).text
            Log.d("PlayPhp", "Direct M3U8 len=${body.length} start=${body.take(200)}")
            if (body.startsWith("#EXTM3U") && !body.contains("unknown::ep")) {
                Log.d("Netmirror", "direct M3U8 OK len=${body.length} host=$hlsDomain")
                Log.e("PLAYURL", m3u8Url)
                return Pair(m3u8Url, tracks)
            }
        } catch (e: Exception) {
            Log.w("PlayPhp", "Direct M3U8 failed ($hlsDomain): ${e.message}")
        }
    }
    return null
}