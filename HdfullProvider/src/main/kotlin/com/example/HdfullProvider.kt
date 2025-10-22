package com.example

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async // Importar para uso de asincronía no bloqueante

class HdfullProvider : MainAPI() {
    override var mainUrl = "https://hdfull.love"
    override var name = "HDFull"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    //    usr:yji0r4c6 pass:@1YU1kc1
    var latestCookie: Map<String, String> = mapOf(
        "language" to "es",
        "PHPSESSID" to "hqh4vktr8m29pfd1dsthiatpk0",
        "guid" to "1525945|2fc755227682457813590604c5a6717d",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas Estreno", "$mainUrl/peliculas-estreno"),
            Pair("Películas Actualizadas", "$mainUrl/peliculas-actualizadas"),
            // Pair("Top IMDB", "$mainUrl/peliculas/imdb_rating"), // Descomentar si es necesario
            Pair("Series", "$mainUrl/series"),
        )

        val deferredPages = urls.map { (name, url) ->
            async {
                val doc = app.get(url, cookies = latestCookie).document

                val home = doc.select("div.center div.view").mapNotNull { viewElement ->
                    val title = viewElement.selectFirst("h5.left a.link")?.attr("title")
                    val link = viewElement.selectFirst("h5.left a.link")?.attr("href")
                    val img = viewElement.selectFirst("div.item a.spec-border-ie img.img-preview")?.attr("src")

                    if (title.isNullOrEmpty() || link.isNullOrEmpty() || img.isNullOrEmpty()) {
                        return@mapNotNull null
                    }

                    val absoluteLink = link.replaceFirst("/", "$mainUrl/")
                    val type = if (absoluteLink.contains("/pelicula")) TvType.Movie else TvType.TvSeries

                    when (type) {
                        TvType.Movie -> newMovieSearchResponse(title, absoluteLink, type) {
                            this.posterUrl = fixUrl(img)
                            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                        }

                        TvType.TvSeries -> newTvSeriesSearchResponse(title, absoluteLink, type) {
                            this.posterUrl = fixUrl(img)
                            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                        }

                        else -> null
                    }
                }

                HomePageList(name, home)
            }
        }

        items.addAll(deferredPages.map { it.await() })

        newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar"
        val csfrDoc = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "menu" to "search",
                "query" to query,
            )
        ).document

        val csfr = csfrDoc.selectFirst("input[value*='sid']")?.attr("value")
            ?: run {
                Log.e(name, "CSRF token not found during search."); return emptyList()
            }

        val doc = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "__csrf_magic" to csfr,
                "menu" to "search",
                "query" to query,
            )
        ).document

        return doc.select("div.container div.view").amap {
            val link = it.selectFirst("h5.left a.link")?.attr("href")
                ?.replaceFirst("/", "$mainUrl/")

            val finalLink = link ?: return@amap null
            val title = it.selectFirst("h5.left a.link")?.attr("title") ?: ""
            val type = if (finalLink.contains("/pelicula")) TvType.Movie else TvType.TvSeries
            val img = it.selectFirst("div.item a.spec-border-ie img.img-preview")?.attr("src")

            newTvSeriesSearchResponse(title, finalLink, type){
                this.posterUrl = fixUrl(img ?: "")
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }.filterNotNull()
    }

    data class EpisodeJson(
        val episode: String?,
        val season: String?,
        @JsonProperty("date_aired") val dateAired: String?,
        val thumbnail: String?,
        val permalink: String?,
        val show: Show?,
        val id: String?,
        val title: Title?,
        val languages: List<String>? = null
    )

    data class Show(
        val title: Title?,
        val id: String?,
        val permalink: String?,
        val thumbnail: String?
    )

    data class Title(
        val es: String?,
        val en: String?
    )

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val doc = app.get(url, cookies = latestCookie).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries

        val title = doc.selectFirst("div#summary-title")?.text() ?: ""
        val backImage = doc.selectFirst("div#summary-fanart-wrapper")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.trim()
        val poster = doc.selectFirst("div#summary-overview-wrapper div.show-poster img.video-page-thumbnail")?.attr("src") ?: ""
        val description = doc.selectFirst("div#summary-overview-wrapper div.show-overview div.show-overview-text")?.text() ?: ""
        val tags = doc.selectFirst("div#summary-overview-wrapper div.show-details p:contains(Género:)")
            ?.text()?.substringAfter("Género:")
            ?.split(" ")
        val year = doc.selectFirst("div#summary-overview-wrapper div.show-details p")?.text()
            ?.substringAfter(":")?.trim()
            ?.toIntOrNull()

        var episodes = if (tvType == TvType.TvSeries) {
            val sid = doc.select("script").firstOrNull { it.html().contains("var sid =") }?.html()
                ?.substringAfter("var sid = '")?.substringBefore("';")

            if (sid == null) {
                Log.e(name, "SID not found for TV Series episodes."); return@coroutineScope null
            }

            doc.select("div#non-mashable div.main-wrapper div.container-wrap div div.container div.span-24 div.flickr")
                .flatMap { seasonDiv ->
                    val seasonNumber = seasonDiv.selectFirst("a img")?.attr("original-title")
                        ?.substringAfter("Temporada")?.trim()?.toIntOrNull()

                    val result = app.post(
                        "$mainUrl/a/episodes", cookies = latestCookie, data = mapOf(
                            "action" to "season",
                            "start" to "0",
                            "limit" to "0",
                            "show" to sid,
                            "season" to "$seasonNumber",
                        )
                    )

                    val episodesJson = AppUtils.parseJson<List<EpisodeJson>>(result.document.text())

                    episodesJson.amap {
                        val episodeNumber = it.episode?.toIntOrNull()
                        val epTitle = it.title?.es?.trim() ?: "Episodio $episodeNumber"
                        val epurl = "$url/temporada-${it.season}/episodio-${it.episode}"
                        newEpisode(epurl){
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    }
                }.filterNotNull()
        } else listOf()

        when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backImage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backImage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, cookies = latestCookie).document
        } catch (e: Exception) {
            return false
        }
        val script = doc.select("script").firstOrNull {
            it.html().contains("var ad =")
        }
        if (script == null) {
            return false
        }
        val scriptContent = script.html()
        val hash = scriptContent
            .substringAfter("var ad = '")
            .substringBefore("';")
        if (hash.isEmpty()) {
            return false
        }
        return try {
            val providers = decodeHash(hash)
            if (providers.isEmpty()) {
                return false
            }
            var success = false
            for (provider in providers) {
                val providerId = provider.provider ?: continue
                val code = provider.code
                val lang = provider.lang
                val quality = provider.quality
                val url = getUrlByProvider(providerId, code)
                if (url.isNotEmpty()) {
                    val extractorSuccess = loadExtractor(url, mainUrl, subtitleCallback, callback)
                    success = extractorSuccess || success
                }
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    fun decodeHash(hash: String): List<ProviderCode> {
        try {
            // Decodificación Base64
            val decodedBytes = try {
                Base64.decode(hash, Base64.DEFAULT)
            } catch (e: Exception) {
                return emptyList()
            }
            val decodedString = String(decodedBytes, Charsets.UTF_8)

            // Desofuscación
            val deobfuscated = StringBuilder()
            for (i in 0 until decodedString.length) {
                val char = decodedString[i]
                val code = char.code
                val newChar = when {
                    code in 32..126 -> {
                        // Preservar caracteres estructurales
                        if (char == '{' || char == '}' || char == '[' || char == ']' || char == ',' || char == ':') {
                            char
                        } else {
                            val shifted = code - 14
                            if (shifted < 32) (shifted + 95).toChar() else shifted.toChar()
                        }
                    }
                    else -> char
                }
                deobfuscated.append(newChar)
            }
            var jsonString = deobfuscated.toString()

            // Limpieza y corrección de JSON
            jsonString = jsonString
                .replace(Regex("\"[^\"]*ide[^\"]*\":\""), "\"provider\":\"")
                .replace(Regex("\"c\\}de\\\":\""), "\"code\":\"")
                .replace(Regex("\"\\u0001\\u0005ali\\u0004\\u0009\":\""), "\"quality\":\"")
                .replace(Regex("\"oide\":\""), "\"provider\":\"")
                .replace(Regex("ddi\\?\\?"), "dvdrip")
                .replace("hd\\u0004\\u0006", "hdtv")
                .replace("d\\u0006d\\u0002i", "dvdrip")
                .replace("hd72\"\"", "hd720")
                .replace("7SPSU4", "ESPSUB")
                .replace("7N9", "ENG")
                .replace("7SP", "ESP")
                .replace("L3T", "LAT")
                .replace("ddi", "dvdrip")
                .replace(Regex("\"code\":\"[^\"]*[\\{\\}][^\"]*\""), "\"code\":\"unknown\"")
                .replace(Regex("\\p{Cntrl}"), "")
                .replace(Regex("\\?\\?"), "")
                .replace('\n', ' ')
                .replace("}{", "},{")
                .replace(Regex("\"\"\""), "\"")
                .replace("\"\"", "\"")
                .replace(Regex(":\"([^\"]+)\":\"([^\"]+)"), ":\"$1\",\"$2")
                .replace(Regex(",+"), ",")
                .replace(Regex("\\s+"), " ")

            // Separar manualmente objetos para corregir la estructura del array
            val objects = mutableListOf<String>()
            val regex = Regex("\"id\":\"[^\"]+\"")
            val matches = regex.findAll(jsonString).toList()
            var lastIndex = if (jsonString.startsWith("[")) 1 else 0

            for (i in matches.indices) {
                val match = matches[i]
                val startIndex = match.range.first
                val endIndex = if (i < matches.size - 1) {
                    matches[i + 1].range.first
                } else {
                    jsonString.length
                }
                var objString = jsonString.substring(lastIndex, endIndex).trim()

                if (objString.isNotEmpty()) {
                    objString = Regex("(\"id\":\"[^\"]+\",\"provider\":\"[^\"]+\",\"code\":\"[^\"]+\",\"lang\":\"[^\"]+\",\"quality\":\"[^\"]+\").*,\"id\":")
                        .replace(objString, "$1")

                    if (objString.contains("\"id\":") && objString.contains("\"provider\":") &&
                        objString.contains("\"code\":") && objString.contains("\"lang\":") &&
                        objString.contains("\"quality\":")) {
                        if (!objString.startsWith("{")) objString = "{$objString"
                        if (!objString.endsWith("}")) objString = "$objString}"
                        objects.add(objString)
                    }
                }
                lastIndex = endIndex
            }

            // Construir el array JSON final
            var finalJson = if (objects.isNotEmpty()) {
                objects.joinToString(",", "[", "]")
            } else {
                "[]"
            }

            // Limpieza final
            finalJson = finalJson.replace(Regex(",\\{[^}]*$"), "]")
            finalJson = finalJson.replace("ddi", "dvdrip")

            if (!finalJson.contains("{")) {
                return emptyList()
            }

            // Parsear JSON
            val providers = AppUtils.parseJson<List<ProviderCode>>(finalJson)
            return providers ?: emptyList()

        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun getUrlByProvider(providerIdx: String, id: String): String {
        val url = when (providerIdx) {
            "1" -> "https://powvideo.net/embed-$id.html"
            "2" -> "https://streamplay.to/embed-$id.html"
            "4" -> "https://clicknupload.link/embed-$id.html"
            "5" -> "https://gounlimited.to/embed-$id.html"
            "6" -> "https://streamtape.com/e/$id"
            "7" -> "https://jetload.net/e/$id"
            "9" -> "https://vivo.sx/embed/$id.html"
            "10" -> "https://ok.ru/videoembed/$id"
            "11" -> "https://1fichier.com/?$id"
            "12" -> "https://gamovideo.com/embed-$id.html"
            "13" -> "https://clipwatching.com/embed-$id.html"
            "14" -> "https://jawcloud.co/embed-$id.html"
            "15" -> "https://mixdrop.co/e/$id"
            "17" -> "https://upstream.to/embed-$id.html"
            "18" -> "https://videobin.co/embed-$id.html"
            "21" -> "https://evoload.io/e/$id"
            "22" -> "https://embedsito.com/v/$id"
            "23" -> "https://dood.to/e/$id"
            "24" -> "https://streamsb.net/e/$id"
            "25" -> "https://uqload.com/embed-$id.html"
            "26" -> "https://voe.sx/e/$id"
            "27" -> "https://sendvid.com/embed/$id"
            "31" -> "https://mega.nz/embed#!$id"
            "33" -> "https://wishfast.top/e/$id"
            "35" -> "https://streamlare.com/e/$id"
            "36" -> "https://hexload.com/embed-$id.html"
            "40" -> "https://vidmoly.to/embed-$id.html"
            "41" -> "https://streamvid.net/embed-$id"
            "43" -> "https://filelions.to/v/$id"
            "44" -> "https://filemoon.sx/e/$id"
            else -> {
                Log.w("HDFull", "Proveedor no reconocido: $providerIdx")
                ""
            }
        }
        Log.d("HDFull", "Proveedor $providerIdx -> URL: $url")
        return url
    }

    data class ProviderCode(
        val id: String,
        val provider: String,
        val code: String,
        val lang: String,
        val quality: String
    )
}