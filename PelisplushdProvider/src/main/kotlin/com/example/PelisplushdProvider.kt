package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TraktProvider
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class Pelisplushd : TraktProvider() {
    override var name = "PelisPlusHD"
    override var mainUrl = "https://embed69.org"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "mx"
    override val supportedSyncNames = setOf(SyncIdName.Trakt)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val traktApiUrl = "https://api.trakt.tv"

    override val mainPage =
        mainPageOf(
            "$traktApiUrl/movies/trending?extended=cloud9,full&limit=25" to "Películas en Tendencia",
            "$traktApiUrl/movies/popular?extended=cloud9,full&limit=25" to "Películas Populares",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25" to "Series en Tendencia",
            "$traktApiUrl/shows/popular?extended=cloud9,full&limit=25" to "Series Populares",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=41,2018,2566,2567,2597" to "Disney+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=256" to "Apple TV+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=87" to "Hulu",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=1623" to "Paramount+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=550,3027" to "Peacock",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=53,1465" to "Netflix",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=47,2385" to "Amazon Prime Video",
        )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataObj = AppUtils.parseJson<LinkData>(data)
        val season = dataObj.season
        val episode = dataObj.episode
        val id = dataObj.imdbId

        val iframe = if (season == null || episode == null) {
            "$mainUrl/f/$id"
        } else {
            val epStr = if (episode < 10) "0$episode" else "$episode"
            "$mainUrl/f/$id-${season}x$epStr"
        }
        Log.d("Pelisplushd", "Fetching iframe URL: $iframe")

        val res= app.get(iframe).document
        val jsonString = res.selectFirst("script:containsData(dataLink)")?.data()?.substringAfter("dataLink = ")?.substringBefore(";")

        val allLinksByLanguage = mutableMapOf<String, MutableList<String>>()

        if (jsonString != null) {
            Log.d("Pelisplushd", "dataLink JSON found. Length: ${jsonString.length}. Starting decryption loop.")
            val jsonArray = JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val fileObject = jsonArray.getJSONObject(i)
                val language = fileObject.getString("video_language")
                val embeds = fileObject.getJSONArray("sortedEmbeds")

                Log.d("Pelisplushd", "Processing language: $language with ${embeds.length()} embeds.")

                val serverLinks = mutableListOf<String>()
                for (j in 0 until embeds.length()) {
                    val embedObj = embeds.getJSONObject(j)
                    embedObj.optString("link").let { link ->
                        if (link.isNotBlank()) serverLinks.add("\"$link\"")
                    }
                }

                val json = """ {"links":$serverLinks} """.trimIndent()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val decrypted = app.post("$mainUrl/api/decrypt", requestBody = json).parsedSafe<Loadlinks>()

                if (decrypted?.success == true) {
                    val links = decrypted.links.map { it.link }

                    Log.d("Pelisplushd", "SUCCESS: Decrypted ${links.size} links for language: $language")
                    Log.d("Pelisplushd", "Sample decrypted link: ${links.firstOrNull()?.substring(0, 30)}...")

                    val listForLang = allLinksByLanguage.getOrPut(language) { mutableListOf() }
                    listForLang.addAll(links)
                } else {
                    if (decrypted != null) {
                        Log.d("Pelisplushd", "FAILURE: Decryption failed for language: $language. Success was false. Response: ${decrypted.toJson()}")
                    } else {
                        Log.d("Pelisplushd", "FAILURE: Decryption failed for language: $language. Response object was NULL (parsing error).")
                    }
                }
            }
        } else {
            Log.d("Pelisplushd", "FATAL: dataLink not found in response. Check iframe URL and HTML structure.")
            println("dataLink not found in response")
        }

        for ((language, links) in allLinksByLanguage) {
            links.forEach { link ->
                loadSourceNameExtractor(language.capitalize(),link,"",subtitleCallback,callback)
            }
        }

        Log.d("Pelisplushd","Final links by language (JSON): ${allLinksByLanguage.toJson()}")
        Log.d("Phisher",allLinksByLanguage.toJson())

        // Subtitles
        val subApiUrl = "https://opensubtitles-v3.strem.io"
        val url = if (season == null) "$subApiUrl/subtitles/movie/$id.json"
        else "$subApiUrl/subtitles/series/$id:$season:$episode.json"

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )

        app.get(url, headers = headers, timeout = 100L)
            .parsedSafe<Subtitles>()?.subtitles?.amap {
                val lan = getLanguage(it.lang) ?: it.lang
                subtitleCallback(
                    SubtitleFile(
                        lan,
                        it.url
                    )
                )
            }

        return true
    }
}

// Clase de datos necesaria para parsear la entrada de loadLinks
data class LinkData(
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
)

data class Subtitles(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long,
)

data class Subtitle(
    val id: String,
    val url: String,
    @JsonProperty("SubEncoding")
    val subEncoding: String,
    val lang: String,
    val m: String,
    val g: String,
)


data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
)

data class Link(
    val index: Long,
    val link: String,
)