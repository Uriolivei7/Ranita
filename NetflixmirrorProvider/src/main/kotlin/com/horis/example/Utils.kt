package com.horis.example

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import android.util.Base64

// ---------------------------------------------------------------------------
// JSON / HTTP
// ---------------------------------------------------------------------------

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
        mapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
        try { mapper.readValue(text, kClass.java) } catch (_: Exception) { null }

    override fun writeValueAsString(obj: Any): String =
        mapper.writeValueAsString(obj)
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T = JSONParser.parse(text, T::class)

inline fun <reified T : Any> tryParseJson(text: String): T? =
    try { JSONParser.parseSafe(text, T::class) } catch (_: Exception) { null }

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

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl
    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
                .parsed<NewTvTokenResponse>()
            val tokenHash = response.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                Log.d("NewTV", "Resolved API URL: $resolvedApiUrl")
                return resolvedApiUrl
            }
        } catch (e: Exception) {
            Log.d("NewTV", "Failed $base: ${e.message}")
        }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

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
    val suggest: List<NewTvSuggest>? = null
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
    val ep_desc: String? = null
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

data class NewTvEpisodesResponse(
    val episodes: List<NewTvApiEpisode>? = null,
    val nextPageShow: Int? = null
)

data class NewTvPlayerResponse(
    val status: String? = null,
    val video_link: String? = null,
    val referer: String? = null
)
