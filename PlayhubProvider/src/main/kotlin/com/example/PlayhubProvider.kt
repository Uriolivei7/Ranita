package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import java.net.URI

class PlayhubProvider : MainAPI() {
    override var mainUrl = "https://www.playhubmax.com"
    override var name = "PlayHUB"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val apiUrl = "https://api.playhubmax.com/api"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "es",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Brave\";v=\"150\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-site",
        "sec-gpc" to "1",
        "priority" to "u=1, i",
    )

    private fun getImageUrl(artwork: Artwork?): String = ""

    private fun getBackdropUrl(artwork: Artwork?): String = ""

    data class ApiContent(
        val id: Int,
        val uuid: String,
        val title: String,
        val overview: String?,
        val type: String?,
        val releaseDate: String?,
        val runtime: Int?,
        val certification: String?,
        val seasonCount: Int?,
        val episodeCount: Int?,
        val languages: List<String>?,
        val artwork: Artwork?,
        val genres: List<GenreItem>?,
        val people: List<PeopleItem>?,
        val seasons: List<SeasonItem>?,
    )

    data class Artwork(
        val poster: ImageSizes?,
        val backdrop: ImageSizes?,
        val logo: ImageSizes?,
    )

    data class ImageSizes(
        val small: String?,
        val medium: String?,
        val large: String?,
    )

    data class GenreItem(
        val id: Int,
        val name: String,
    )

    data class PeopleItem(
        val id: Int,
        val name: String,
    )

    data class SeasonItem(
        val id: Int,
        @JsonProperty("seasonNumber") val seasonNumber: Int,
    )

    data class ContentListResponse(
        val data: List<ApiContent>,
        val currentPage: Int,
        val hasMore: Boolean,
    )

    data class ContentDetailResponse(
        val id: Int,
        val uuid: String,
        @JsonProperty("title") private val titleRaw: Any? = null,
        val overview: String?,
        val type: String?,
        val releaseDate: String?,
        val runtime: Int?,
        val certification: String?,
        val seasonCount: Int?,
        val episodeCount: Int?,
        val languages: List<String>?,
        val artwork: HomeArtwork?,
        val genres: List<GenreItem>?,
        val people: List<PeopleItem>?,
        val seasons: List<SeasonItem>?,
    ) {
        fun displayTitle(): String {
            @Suppress("UNCHECKED_CAST")
            fun extractFromMap(m: Any): String {
                val map = m as Map<String, String>
                return map["es-419"] ?: map["es-ES"] ?: map["en-US"] ?: map.values.firstOrNull() ?: ""
            }
            return when (titleRaw) {
                is Map<*, *> -> extractFromMap(titleRaw)
                is String -> titleRaw
                else -> ""
            }
        }
    }

    data class SectionItem(
        val id: Int,
        val name: String,
        @JsonProperty("componentType") val componentType: String,
        val path: String,
        val showAll: Boolean,
    )

    data class SectionsResponse(
        val data: List<SectionItem>,
    )

    data class HomePageData(
        val placement: String? = null,
        val sections: List<HomeSection>? = null,
    )

    data class HomeSection(
        val uuid: String? = null,
        val name: String? = null,
        val maxItems: Int? = null,
        val layout: String? = null,
        @JsonProperty("itemType") val itemType: String? = null,
        val showAll: Boolean? = null,
        val items: List<HomeItem>? = null,
    )

    data class HomeItem(
        val id: Int? = null,
        val uuid: String? = null,
        val title: String? = null,
        val isAdult: Boolean? = null,
        val overview: String? = null,
        val artwork: HomeArtwork? = null,
        val linkCount: Int? = null,
    )

    data class HomeArtwork(
        val poster: List<ArtworkUrl>? = null,
        val backdrop: List<ArtworkUrl>? = null,
        val logo: List<ArtworkUrl>? = null,
    )

    data class ArtworkUrl(
        val url: String? = null,
        val width: Int? = null,
        val blurhash: String? = null,
    )

    private fun getBestPoster(artwork: HomeArtwork?): String {
        if (artwork == null) return ""
        val sizes = listOf(320, 240, 420, 560)
        for (size in sizes) {
            val match = artwork.poster?.firstOrNull { it.width == size }?.url
            if (!match.isNullOrBlank()) return match
        }
        return artwork.poster?.firstOrNull { !it.url.isNullOrBlank() }?.url ?: ""
    }

    private fun getBestBackdrop(artwork: HomeArtwork?): String {
        if (artwork == null) return ""
        val sizes = listOf(1280, 1920, 1080, 720)
        for (size in sizes) {
            val match = artwork.backdrop?.firstOrNull { it.width == size }?.url
            if (!match.isNullOrBlank()) return match
        }
        return artwork.backdrop?.firstOrNull { !it.url.isNullOrBlank() }?.url
            ?: getBestPoster(artwork)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null,
        val uuid: String? = null,
        @JsonProperty("seasonId") val seasonId: Int? = null,
        @JsonProperty("contentId") val contentId: Int? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        val overview: String? = null,
        val runtime: Int? = null,
        val artwork: EpisodeArtwork? = null,
        @JsonProperty("contentData") val contentData: EpisodeContentData? = null,
    ) {
        fun displayName(): String {
            return name ?: title ?: "Episode ${episodeNumber ?: ""}"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeArtwork(
        val poster: List<ArtworkUrl>? = null,
        val backdrop: List<ArtworkUrl>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeContentData(
        val id: Int,
        val uuid: String,
        val title: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val data: List<EpisodeItem>? = null,
        val currentPage: Int? = null,
        val hasMore: Boolean? = null,
    )

    data class SourceResponse(
        val data: String,
    )

    data class DecryptedSource(
        val url: String,
        @JsonProperty("hostName") val hostName: String?,
        val languages: List<String>?,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("$apiUrl/pages/home", headers = headers)
        Log.d("PlayHub", "Homepage response status=${resp.code} len=${resp.text.length}")
        val homeData = try {
            resp.parsed<HomePageData>()
        } catch (e: Exception) {
            Log.e("PlayHub", "Failed to parse homepage: ${e.message}")
            Log.d("PlayHub", "first200=${resp.text.take(200)}")
            return newHomePageResponse(emptyList())
        }
        if (homeData.sections.isNullOrEmpty()) {
            Log.e("PlayHub", "Homepage has no sections")
            return newHomePageResponse(emptyList())
        }
        Log.d("PlayHub", "Homepage parsed OK: ${homeData.sections.size} sections")

        val homePageLists = homeData.sections.mapNotNull { section ->
            if (section.itemType != "content" || section.items.isNullOrEmpty()) return@mapNotNull null

            val items = section.items.mapNotNull { item ->
                if (item.uuid.isNullOrBlank() || item.title.isNullOrBlank()) return@mapNotNull null
                val poster = getBestPoster(item.artwork)
                newAnimeSearchResponse(item.title, "$mainUrl/content/${item.uuid}") {
                    this.posterUrl = poster
                }
            }

            if (items.isNotEmpty()) {
                HomePageList(section.name ?: "Section", items)
            } else null
        }

        return newHomePageResponse(homePageLists)
    }

    private val meiliUrl = "https://meili.playhub-plus.com"
    private val meiliToken = "72750fbe7a11442219b3828261ddb4d6d3cbd38c90d57178e90c32fd03e05dfd"

    data class MeiliSearchBody(
        val queries: List<MeiliQuery>,
    )

    data class MeiliQuery(
        val indexUid: String = "contents",
        val q: String,
        val limit: Int = 20,
        val filter: String = "is_active = true AND is_adult = false",
    )

    data class MeiliResponse(
        val results: List<MeiliResult>? = null,
    )

    data class MeiliResult(
        val indexUid: String? = null,
        val hits: List<MeiliHit>? = null,
    )

    data class MeiliHit(
        val uuid: String? = null,
        val title: Map<String, String>? = null,
        val poster: Map<String, List<ArtworkUrl>>? = null,
    ) {
        fun displayTitle(): String {
            return title?.get("es-419") ?: title?.get("es-ES") ?: title?.get("en-US") ?: title?.values?.firstOrNull() ?: ""
        }
        fun getPosterUrl(): String {
            if (poster == null) return ""
            val langPriority = listOf("es-419", "es-ES", "en-US")
            for (lang in langPriority) {
                val match = poster[lang]?.firstOrNull { it.width == 320 }?.url
                    ?: poster[lang]?.firstOrNull { it.width == 240 }?.url
                    ?: poster[lang]?.firstOrNull { !it.url.isNullOrBlank() }?.url
                if (!match.isNullOrBlank()) return match
            }
            for (list in poster.values) {
                val match = list.firstOrNull { !it.url.isNullOrBlank() }?.url
                if (!match.isNullOrBlank()) return match
            }
            return ""
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = MeiliSearchBody(listOf(MeiliQuery(q = query)))
        Log.d("PlayHub", "search query=$query")
        val res = app.post(
            "$meiliUrl/multi-search",
            json = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $meiliToken",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            )
        )
        Log.d("PlayHub", "search response status=${res.code} len=${res.text.length}")
        val parsed = try {
            res.parsed<MeiliResponse>()
        } catch (e: Exception) {
            Log.e("PlayHub", "Failed to parse search: ${e.message}")
            return emptyList()
        }
        val hits = parsed.results?.firstOrNull()?.hits.orEmpty()
        Log.d("PlayHub", "search hits=${hits.size}")
        if (hits.isNotEmpty()) {
            val first = hits.first()
            val posterUrl = first.getPosterUrl()
            Log.d("PlayHub", "first hit uuid=${first.uuid} title=${first.displayTitle()} posterSize=${first.poster?.size} posterUrl=${posterUrl.take(80)}")
        }
        return hits.mapNotNull { hit ->
            val t = hit.displayTitle()
            if (hit.uuid.isNullOrBlank() || t.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(t, "$mainUrl/content/${hit.uuid}") {
                this.posterUrl = hit.getPosterUrl()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlayHub", "load url=$url")
        val uuid = url.substringAfterLast("/")
        val detailRes = app.get(
            "$apiUrl/contents/$uuid",
            headers = headers
        )
        Log.d("PlayHub", "load response status=${detailRes.code} len=${detailRes.text.length}")
        val content = try {
            detailRes.parsed<ContentDetailResponse>()
        } catch (e: Exception) {
            Log.e("PlayHub", "Failed to parse load: ${e.message}")
            return null
        }

        val tvType = if (content.type == "Movie") TvType.Movie else TvType.TvSeries
        val posterUrl = getBestPoster(content.artwork)
        val backdropUrl = getBestBackdrop(content.artwork)
        val year = content.releaseDate?.substringBefore("-")?.toIntOrNull()
        Log.d("PlayHub", "load type=${content.type} tvType=$tvType seasons=${content.seasons?.size} title=${content.displayTitle()}")

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(content.displayTitle(), url, tvType, "content:$uuid") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = content.overview
                this.year = year
                this.tags = content.genres?.map { it.name }
                this.duration = content.runtime
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = content.seasons ?: emptyList()
        Log.d("PlayHub", "load episodes: ${seasons.size} seasons")

        for (season in seasons.sortedBy { it.seasonNumber }) {
            var page = 1
            var hasMore = true
            while (hasMore) {
                val epRes = app.get(
                    "$apiUrl/episodes?seasonId=${season.id}&page=$page",
                    headers = headers
                )
                Log.d("PlayHub", "episodes season=${season.id} page=$page status=${epRes.code} len=${epRes.text.length}")
                val epParsed = try {
                    jacksonObjectMapper().readValue(epRes.text, EpisodesResponse::class.java)
                } catch (e: Exception) {
                    Log.e("PlayHub", "Failed to parse episodes: ${e.message}")
                    Log.d("PlayHub", "episodes raw first 2000: ${epRes.text.take(2000)}")
                    null
                }
                if (epParsed != null && epParsed.data != null) {
                    Log.d("PlayHub", "episodes parsed: ${epParsed.data.size} items hasMore=${epParsed.hasMore}")
                    epParsed.data.forEach { ep ->
                        val epPoster = ep.artwork?.backdrop?.firstOrNull { it.width == 320 }?.url
                            ?: ep.artwork?.backdrop?.firstOrNull { it.width == 240 }?.url
                            ?: ep.artwork?.backdrop?.firstOrNull { !it.url.isNullOrBlank() }?.url
                            ?: ep.artwork?.poster?.firstOrNull { it.width == 320 }?.url
                            ?: ep.artwork?.poster?.firstOrNull { !it.url.isNullOrBlank() }?.url
                        episodes.add(
                            newEpisode("${mainUrl}/play/episode/${ep.uuid ?: ep.id}") {
                                this.name = ep.displayName()
                                this.season = ep.seasonNumber ?: 1
                                this.episode = ep.episodeNumber ?: (episodes.size + 1)
                                this.posterUrl = epPoster
                                this.description = ep.overview
                            }
                        )
                    }
                    hasMore = epParsed.hasMore ?: false
                    page++
                } else {
                    Log.e("PlayHub", "Failed to parse episodes response or empty data")
                    hasMore = false
                }
            }
        }
        Log.d("PlayHub", "load done: ${episodes.size} episodes total")

        return newTvSeriesLoadResponse(content.displayTitle(), url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.plot = content.overview
            this.year = year
            this.tags = content.genres?.map { it.name }
            this.duration = content.runtime
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = if (data.contains("/episode/")) "episode" else "content"
        val uuid = if (data.contains("/episode/")) data.substringAfterLast("/") else data.substringAfterLast(":")
        val url = "$apiUrl/$type/$uuid/sources"
        Log.d("PlayHub", "loadLinks data: $data -> type=$type uuid=$uuid url=$url")
        val sourceRes = app.get(url, headers = headers)
        Log.d("PlayHub", "Response status: ${sourceRes.code}")
        val sourceParsed = tryParseJson<SourceResponse>(sourceRes.text)
        if (sourceParsed == null || sourceParsed.data.isEmpty()) {
            Log.e("PlayHub", "Failed to parse source response or empty data")
            return false
        }

        val decrypted = decryptData(sourceParsed.data)
        Log.d("PlayHub", "Decrypted: $decrypted")
        val sources = tryParseJson<List<DecryptedSource>>(decrypted)
        if (sources.isNullOrEmpty()) {
            Log.e("PlayHub", "Failed to parse decrypted sources or empty list")
            return false
        }

        sources.forEach { source ->
            if (source.url.isNotBlank()) {
                Log.d("PlayHub", "Found link: ${source.hostName} -> ${source.url}")
                resolveEmbed(source.url, source.hostName, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractSubtitlesFromDecoded(js: String, pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracksRegex = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val tracksMatch = tracksRegex.find(js) ?: return
        val tracksContent = tracksMatch.groupValues[1]

        val vttRegex = Regex("""file:\s*["']([^"']+\.vtt[^"']*)["']""")
        val labelRegex = Regex("""label:\s*["']([^"']+)["']""")

        val fileMatches = vttRegex.findAll(tracksContent).toList()
        val labelMatches = labelRegex.findAll(tracksContent).toList()

        for (i in fileMatches.indices) {
            val rawUrl = fileMatches[i].groupValues[1].replace("\\/", "/")
            val label = labelMatches.getOrNull(i)?.groupValues?.get(1) ?: "Subtitle"
            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else {
                val uri = URI(pageUrl)
                "${uri.scheme}://${uri.host}$rawUrl"
            }
            Log.d("PlayHub", "Subtitle: $label -> $fullUrl")
            val subFile = newSubtitleFile(label, fullUrl) {
                this.headers = mapOf("Referer" to pageUrl)
            }
            subtitleCallback.invoke(subFile)
        }
    }

    private fun decodeEvalPacker(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var i = c - 1
        while (i >= 0) {
            val key = k.getOrNull(i) ?: ""
            if (key.isNotEmpty()) {
                val pattern = "\\b" + i.toString(a) + "\\b"
                result = result.replace(Regex(pattern), key)
            }
            i--
        }
        return result
    }

    private suspend fun resolveEmbed(
        url: String,
        hostName: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, headers = headers)
            val body = res.text

            val evalStartMarker = "eval(function(p,a,c,k,e,d){"
            val evalStart = body.indexOf(evalStartMarker)
            if (evalStart < 0) {
                Log.e("PlayHub", "No eval found in $url")
                return
            }

            var parenDepth = 1
            var callEnd = -1
            var i = evalStart + evalStartMarker.length
            while (i < body.length) {
                val ch = body[i]
                if (ch == '(') parenDepth++
                else if (ch == ')') {
                    parenDepth--
                    if (parenDepth == 0) {
                        callEnd = i
                        break
                    }
                }
                i++
            }
            if (callEnd <= 0) {
                Log.e("PlayHub", "Could not find eval closing paren")
                return
            }

            val fullEval = body.substring(evalStart, callEnd + 1)

            val argPatterns = listOf(
                Regex("""\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)"""),
                Regex("""\}\('([^']*)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL),
                Regex("""\}\((.+?),(\d+),(\d+),'(.+?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            )

            var argMatch: MatchResult? = null
            for (pattern in argPatterns) {
                argMatch = pattern.find(fullEval)
                if (argMatch != null) {
                    Log.d("PlayHub", "Matched with pattern: $pattern")
                    break
                }
            }

            if (argMatch == null) {
                Log.e("PlayHub", "Could not parse eval args")
                Log.d("PlayHub", "Eval last 300: ${fullEval.takeLast(300)}")
                return
            }

            val p = argMatch.groupValues[1]
            val a = argMatch.groupValues[2].toInt()
            val c = argMatch.groupValues[3].toInt()
            val kRaw = argMatch.groupValues[4]
            val k = kRaw.split("|")

            Log.d("PlayHub", "Parsed: p length=${p.length}, a=$a, c=$c, k size=${k.size}")
            Log.d("PlayHub", "k first 20: ${k.take(20)}")
            Log.d("PlayHub", "k last 20: ${k.takeLast(20)}")

            val decoded = decodeEvalPacker(p, a, c, k)
            Log.d("PlayHub", "Decoded length: ${decoded.length}")
            Log.d("PlayHub", "Decoded first 3000: ${decoded.take(3000)}")

            extractSubtitlesFromDecoded(decoded, url, subtitleCallback)

            val streamPathRegex = Regex("""(/stream/[^"'\s]+/master\.(?:m3u8|txt))""")
            val streamPathMatch = streamPathRegex.find(decoded)
            if (streamPathMatch != null) {
                val streamPath = streamPathMatch.groupValues[1]
                val uri = URI(url)
                val baseUrl = "${uri.scheme}://${uri.host}"
                val streamUrl = "$baseUrl$streamPath"
                Log.d("PlayHub", "Found relative stream (preferred): $streamUrl")
                callback.invoke(
                    newExtractorLink(
                        hostName ?: "PlayHub",
                        hostName ?: "PlayHub",
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                    }
                )
                return
            }

            val m3u8Regex = Regex("""(https?://[^"'\s]+/master\.m3u8[^"'\s]*)""")
            val m3u8Match = m3u8Regex.find(decoded)
            if (m3u8Match != null) {
                val streamUrl = m3u8Match.groupValues[1]
                Log.d("PlayHub", "Found direct m3u8: $streamUrl")
                callback.invoke(
                    newExtractorLink(
                        hostName ?: "PlayHub",
                        hostName ?: "PlayHub",
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                    }
                )
                return
            }

            val txtRegex = Regex("""(https?://[^"'\s]+/master\.txt[^"'\s]*)""")
            val txtMatch = txtRegex.find(decoded)
            if (txtMatch != null) {
                val txtUrl = txtMatch.groupValues[1]
                Log.d("PlayHub", "Found master.txt, fetching: $txtUrl")
                try {
                    val txtRes = app.get(txtUrl, headers = mapOf("Referer" to url))
                    val txtBody = txtRes.text
                    Log.d("PlayHub", "master.txt content (first 2000): ${txtBody.take(2000)}")
                    val innerM3u8Regex = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                    val innerMatch = innerM3u8Regex.find(txtBody)
                    if (innerMatch != null) {
                        val innerUrl = innerMatch.groupValues[1]
                        Log.d("PlayHub", "Found m3u8 in txt: $innerUrl")
                        callback.invoke(
                            newExtractorLink(
                                hostName ?: "PlayHub",
                                hostName ?: "PlayHub",
                                innerUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                    Log.e("PlayHub", "Failed to fetch master.txt: ${e.message}")
                }
            }

            Log.e("PlayHub", "No video URL found in decoded eval")
        } catch (e: Exception) {
            Log.e("PlayHub", "resolveEmbed failed: ${e.message}")
        }
    }

    private val sha256Hash = sha256("Dx5VYERoLOVevR9C")
    private val aesKey = sha256Hash.substring(0, 32)
    private val aesIv = sha256Hash.substring(0, 16)

    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decryptData(encrypted: String): String {
        try {
            val keySpec = javax.crypto.spec.SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(aesIv.toByteArray(Charsets.UTF_8))
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("PlayHub", "Decryption failed: ${e.message}")
            return "[]"
        }
    }
}