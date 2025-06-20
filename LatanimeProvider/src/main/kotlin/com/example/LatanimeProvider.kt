package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import java.util.*

class LatanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }

        private fun base64Decode(encoded: String): String {
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.e("LatanimePlugin", "Error decoding Base64: ${e.message}")
                ""
            }
        }
    }

    override var mainUrl = "https://latanime.org"
    override var name = "LatAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        return app.get(url, interceptor = cloudflareKiller)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair(
                "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                "Peliculas"
            ),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        urls.apmap { (url, name) ->
            val doc = appGetChildMainUrl(url).document
            delay(2000) // Retardo reducido
            val home = doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
                val itemLink = it.selectFirst("a")!!
                val title = itemLink.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
                val itemUrl = itemLink.attr("href")

                val posterElement = it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
                val src = posterElement?.attr("src") ?: ""
                val dataSrc = posterElement?.attr("data-src") ?: ""
                Log.d("LatanimePlugin", "Poster data: src=$src, data-src=$dataSrc")
                val poster = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

                newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                    this.posterUrl = poster
                    addDubStatus(getDubStatus(title))
                    this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
                }
            }
            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = appGetChildMainUrl("$mainUrl/buscar?q=$query").document
        delay(2000) // Retardo reducido
        return doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
            val itemLink = it.selectFirst("a")!!
            val title = itemLink.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
            val href = fixUrl(itemLink.attr("href"))

            val imageElement = it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
            val src = imageElement?.attr("src") ?: ""
            val dataSrc = imageElement?.attr("data-src") ?: ""
            Log.d("LatanimePlugin", "Search image data: src=$src, data-src=$dataSrc")
            val image = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                fixUrl(image),
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
                posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = appGetChildMainUrl(url).document
        val posterElement = doc.selectFirst("div.p-2.cap-layout.d-flex.align-items-center.gap-2 img.lozad.rounded-3")!!
        val dataSrc = posterElement.attr("data-src")
        val src = posterElement.attr("src")
        Log.d("LatanimePlugin", "Load poster data: data-src=$dataSrc, src=$src")
        val poster = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""
        val backimage = poster
        val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")!!.text()
        val type = doc.selectFirst("div.chapterdetls2")?.text() ?: ""
        val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")!!.text().replace("Ver menos", "")
        val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
        val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select("div.row div.col-lg-9.col-md-8 div.row div a").map {
            val name = it.selectFirst("div.cap-layout")?.text()
                ?: it.selectFirst("h2")?.text()
                ?: ""
            val link = it!!.attr("href")
            val epThumb = it.selectFirst(".animeimghv")?.attr("data-src")
                ?: it.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
                ?: ""
            Episode(link, name, posterUrl = epThumb)
        }
        return newAnimeLoadResponse(title, url, getType(title)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LatanimePlugin", "loadLinks called with data: $data")

        var foundLinks = false
        val doc = appGetChildMainUrl(data).document
        delay(5000) // Retardo para carga dinámica
        try {
            doc.select("ul.cap_repro li#play-video").apmap {
                Log.d("LatanimePlugin", "Found player element: ${it.outerHtml()}")

                val encodedUrl = it.selectFirst("a.play-video")?.attr("data-player")
                Log.d("LatanimePlugin", "Encoded URL found: $encodedUrl")

                if (encodedUrl.isNullOrEmpty()) {
                    Log.w("LatanimePlugin", "Encoded URL is null or empty for $data. Could not find player data-player attribute.")
                    return@apmap
                }

                val urlDecoded = base64Decode(encodedUrl)
                Log.d("LatanimePlugin", "Decoded URL (Base64): $urlDecoded")

                val url = urlDecoded.replace("https://monoschinos2.com/reproductor?url=", "")
                    .replace("https://sblona.com", "https://watchsb.com")
                Log.d("LatanimePlugin", "Final URL for Extractor: $url")

                loadExtractor(url, mainUrl, subtitleCallback, callback)
                foundLinks = true
            }
        } catch (e: Exception) {
            Log.e("LatanimePlugin", "Error in loadLinks: ${e.message}")
        }

        Log.d("LatanimePlugin", "loadLinks finished for data: $data with foundLinks: $foundLinks")
        return foundLinks
    }

    private fun base64Decode(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e("LatanimePlugin", "Error decoding Base64: ${e.message}")
            ""
        }
    }
}