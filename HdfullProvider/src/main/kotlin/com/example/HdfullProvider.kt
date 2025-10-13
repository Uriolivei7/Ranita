package com.example

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas Estreno", "$mainUrl/peliculas-estreno"),
            Pair("Películas Actualizadas", "$mainUrl/peliculas-actualizadas"),
//            Pair("Top IMDB", "$mainUrl/peliculas/imdb_rating"),
            Pair("Series", "$mainUrl/series"),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url, cookies = latestCookie).document
            val home =
                doc.select("div.center div.view").apmap {
                    val title = it.selectFirst("h5.left a.link")?.attr("title")
                    val link = it.selectFirst("h5.left a.link")?.attr("href")
                        ?.replaceFirst("/", "$mainUrl/")
                    val type = if (link!!.contains("/pelicula")) TvType.Movie else TvType.TvSeries
                    val img =
                        it.selectFirst("div.item a.spec-border-ie img.img-preview")?.attr("src")
                    newTvSeriesSearchResponse(title!!, link, type){
                        this.posterUrl = fixUrl(img!!)
                        this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                    }
                }
            items.add(HomePageList(name, home))
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar"
        val csfrDoc = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "menu" to "search",
                "query" to query,
            )
        ).document
        val csfr = csfrDoc.selectFirst("input[value*='sid']")!!.attr("value")
        Log.d("TAG", "search: $csfr")
        val doc = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "__csrf_magic" to csfr,
                "menu" to "search",
                "query" to query,
            )
        ).document
        Log.d("TAG", "search: $doc")
        return doc.select("div.container div.view").amap {
            val title = it.selectFirst("h5.left a.link")?.attr("title")
            val link = it.selectFirst("h5.left a.link")?.attr("href")
                ?.replaceFirst("/", "$mainUrl/")
            val type = if (link!!.contains("/pelicula")) TvType.Movie else TvType.TvSeries
            val img =
                it.selectFirst("div.item a.spec-border-ie img.img-preview")?.attr("src")
            newTvSeriesSearchResponse(title!!, link!!, type){
                this.posterUrl = fixUrl(img!!)
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }
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

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, cookies = latestCookie).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div#summary-title")?.text() ?: ""
        val backimage =
            doc.selectFirst("div#summary-fanart-wrapper")!!.attr("style").substringAfter("url(")
                .substringBefore(")").trim()
        val poster =
            doc.selectFirst("div#summary-overview-wrapper div.show-poster img.video-page-thumbnail")!!
                .attr("src")
        val description =
            doc.selectFirst("div#summary-overview-wrapper div.show-overview div.show-overview-text")!!
                .text()
        val tags =
            doc.selectFirst("div#summary-overview-wrapper div.show-details p:contains(Género:)")
                ?.text()?.substringAfter("Género:")
                ?.split(" ")
        val year = doc.selectFirst("div#summary-overview-wrapper div.show-details p")?.text()
            ?.substringAfter(":")?.trim()
            ?.toIntOrNull()
        var episodes = if (tvType == TvType.TvSeries) {
            val sid = doc.select("script").firstOrNull { it.html().contains("var sid =") }!!.html()
                .substringAfter("var sid = '").substringBefore("';")
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
                    episodesJson.apmap {
                        val episodeNumber = it.episode?.toIntOrNull()
                        val epTitle = it.title?.es?.trim() ?: "Episodio $episodeNumber"
                        val epurl = "$url/temporada-${it.season}/episodio-${it.episode}"
                        newEpisode(epurl){
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    }
                }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }

            else -> null
        }
    }

    data class ProviderCode(
        val id: String,
        val provider: String,
        val code: String,
        val lang: String,
        val quality: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDFull", "loadLinks iniciado - URL: $data")

        val doc = app.get(data, cookies = latestCookie).document

        val script = doc.select("script").firstOrNull {
            it.html().contains("var ad =")
        }

        if (script == null) {
            Log.e("HDFull", "No se encontró script con 'var ad ='")
            return false
        }

        Log.d("HDFull", "Script encontrado: ${script.html().take(200)}...")

        val hash = script.html()
            .substringAfter("var ad = '")
            .substringBefore("';")

        if (hash.isEmpty()) {
            Log.e("HDFull", "Hash vacío después de extraer")
            return false
        }

        Log.d("HDFull", "Hash extraído: $hash")

        return try {
            val providers = decodeHash(hash)
            Log.d("HDFull", "Proveedores decodificados: ${providers.size}")

            for (provider in providers) {
                Log.d("HDFull", "Proveedor: ${provider.provider}, Code: ${provider.code}, Lang: ${provider.lang}")

                val url = getUrlByProvider(provider.provider, provider.code)
                Log.d("HDFull", "URL generada: $url")

                if (url.isNotEmpty()) {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("HDFull", "Error en loadLinks: ${e.message}", e)
            false
        }
    }

    fun decodeHash(str: String): List<ProviderCode> {
        return try {
            Log.d("HDFull", "Decodificando hash de longitud: ${str.length}")

            val decodedBytes = Base64.decode(str, Base64.DEFAULT)
            val decodedString = String(decodedBytes)
            Log.d("HDFull", "String decodificado: $decodedString")

            val jsonString = decodedString.substrings(14)
            Log.d("HDFull", "JSON después de substrings: $jsonString")

            AppUtils.parseJson<List<ProviderCode>>(jsonString)
        } catch (e: Exception) {
            Log.e("HDFull", "Error decodificando hash: ${e.message}", e)
            emptyList()
        }
    }

    fun String.obfs(key: Int, n: Int = 126): String {
        if (key % 1 != 0 || n % 1 != 0) {
            return this
        }
        val chars = this.toCharArray()
        for (i in chars.indices) {
            val c = chars[i].code
            if (c <= n) {
                chars[i] = ((c + key) % n).toChar()
            }
        }
        return String(chars)
    }

    fun String.substrings(key: Int, n: Int = 126): String {
        if (key % 1 != 0 || n % 1 != 0) {
            return this
        }
        return this.obfs(n - key)
    }

    fun getUrlByProvider(providerIdx: String, id: String): String {
        val url = when (providerIdx) {
            "1" -> "https://powvideo.org/$id"
            "2" -> "https://streamplay.to/$id"
            "4" -> "https://clicknupload.com/embed/$id"
            "5" -> "https://gounlimited.to/embed-$id.html"
            "6" -> "https://streamtape.com/v/$id"
            "7" -> "https://jetload.net/e/$id"
            "9" -> "https://vivo.sx/$id"
            "10" -> "https://ok.ru/videoembed/$id"
            "11" -> "https://1fichier.com/?$id"
            "12" -> "https://gamovideo.com/$id"
            "13" -> "https://clipwatching.com/embed-$id.html"
            "14" -> "https://jawcloud.co/embed-$id.html"
            "15" -> "https://mixdrop.bz/f/$id"
            "17" -> "https://upstream.to/embed-$id.html"
            "18" -> "https://videobin.co/embed-$id.html"
            "21" -> "https://evoload.io/e/$id"
            "22" -> "https://embedsito.com/v/$id"
            "23" -> "https://dood.to/e/$id"
            "24" -> "https://sb.fcdn.stream/e/$id"
            "25" -> "https://uqload.com/embed-$id.html"
            "26" -> "https://voe.sx/e/$id"
            "27" -> "https://sendvid.com/$id"
            "31" -> "https://mega.nz/embed#!$id"
            "33" -> "https://wishfast.top/e/$id"
            "35" -> "https://streamlare.com/e/$id"
            "36" -> "https://hexload.com/embed-$id.html"
            "40" -> "https://vidmoly.me/w/$id"
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

}