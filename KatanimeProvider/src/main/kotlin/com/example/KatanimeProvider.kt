package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.MainAPI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Base64 as AndroidBase64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import javax.crypto.Cipher.DECRYPT_MODE
import android.net.Uri
import java.security.MessageDigest

class KatanimeProvider : MainAPI() {
    override var mainUrl = "https://katanime.net"
    override var name = "Katanime"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img.lozad")?.attr("data-src")
        val yearText = element.selectFirst("div._2y8kd")?.text()?.trim()
        val year = yearText?.takeLast(4)?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = posterUrl
                this.year = year
            }
        }
        return null
    }

    private fun extractSearchItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a._1A2Dc._38LRT")
        val titleElement = element.selectFirst("div._2NNxg a._2uHIS")
        val link = linkElement?.attr("href")
        val title = titleElement?.text()?.trim()
        val posterUrl = element.selectFirst("img")?.attr("src")
        val yearText = element.selectFirst("div._2y8kd:not(.etag)")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = posterUrl
                this.year = year
            }
        }
        return null
    }

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                val res = app.get(url, timeout = timeoutMs)
                if (res.isSuccessful) return res.text
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "safeAppGet error for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = mainUrl
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        // Capítulos recientes
        doc.selectFirst("div#content-left div#article-div")?.let { container ->
            val animes = container.select("div._135yj._2FQAt.chap").mapNotNull {
                val titleElement = it.selectFirst("div._2NNxg a")
                val link = titleElement?.attr("href")
                val title = titleElement?.text()?.trim()
                val posterUrl = it.selectFirst("img.lozad")?.attr("data-src")

                if (title != null && link != null) {
                    val animeSlug = link.substringAfter("/capitulo/").substringBeforeLast("-").substringBeforeLast("/")
                    val animeUrl = "$mainUrl/anime/$animeSlug/"

                    newAnimeSearchResponse(
                        title,
                        animeUrl
                    ) {
                        this.type = TvType.Anime
                        this.posterUrl = posterUrl
                    }
                } else null
            }
            if (animes.isNotEmpty()) items.add(HomePageList("Capítulos recientes", animes))
        }

        doc.selectFirst("div.content-right div#widget")?.let { container ->
            val animes = container.select("div._type3").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Animes populares", animes))
        }

        doc.selectFirst("div#content-full div#article-div.recientes")?.let { container ->
            val animes = container.select("div._135yj._2FQAt.extra").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Animes recientes", animes))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div._135yj._2FQAt.full._2mJki").mapNotNull {
            extractSearchItem(it)
        }
    }

    data class EpisodeLoadData(
        val episodeUrl: String,
    )

    data class EpisodeData (
        @JsonProperty("numero") val numero: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class EpisodePage (
        @JsonProperty("current_page") val current_page: Int? = null,
        @JsonProperty("data") val data: List<EpisodeData> = emptyList(),
    )

    data class EpisodesJson (
        @JsonProperty("ep") val ep: EpisodePage,
        @JsonProperty("last") val last: Map<String, String>? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("KatanimeProvider", "Iniciando load para URL: $url")

        val response = app.get(url)
        val html = response.text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.comics-title.ajp")?.text()?.trim() ?: doc.selectFirst("h3.comics-alt")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div#animeinfo img")?.attr("data-src") ?: ""
        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text() }
        val yearText = doc.selectFirst("div.details-by")?.text()?.substringAfter("•")?.trim()
        val year = yearText?.takeLast(4)?.toIntOrNull()
        val status = parseStatus(doc.selectFirst("span#estado")?.text()?.trim() ?: "")

        val token = doc.selectFirst("input[name=_token]")?.attr("value")
        val episodeListUrlElement = doc.selectFirst("div#c_list")
        val episodeListApiUrl = episodeListUrlElement?.attr("data-url")

        val allEpisodes = if (!episodeListApiUrl.isNullOrBlank() && !token.isNullOrBlank()) {
            Log.d("KatanimeProvider", "Token y URL de API encontrados. Token: $token, API URL: $episodeListApiUrl")
            val headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to mainUrl
            )

            val data = mapOf(
                "_token" to token,
                "pagina" to "1"
            )

            try {
                val episodesJson = app.post(
                    episodeListApiUrl,
                    headers = headers,
                    data = data,
                    cookies = response.cookies
                ).parsed<EpisodesJson>()

                val episodes = episodesJson.ep.data
                Log.d("KatanimeProvider", "Se encontraron ${episodes.size} elementos de episodios.")

                episodes.mapNotNull { episode ->
                    val epUrl = fixUrl(episode.url ?: "")
                    val epNum = episode.numero?.toIntOrNull()

                    if (epUrl.isNotBlank() && epNum != null) {
                        val episodeData = EpisodeLoadData(epUrl)
                        newEpisode(episodeData.toJson()) {
                            this.name = "Episodio $epNum"
                            this.episode = epNum
                        }
                    } else {
                        Log.e("KatanimeProvider", "Fallo al extraer episodio. URL: $epUrl, Número: $epNum")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("KatanimeProvider", "Fallo al obtener los episodios: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val recommendations = doc.select("div#slidebar-anime div._type3.np").mapNotNull { element ->
            val recLink = element.selectFirst("a._1A2Dc._38LRT")?.attr("href")
            val recTitle = element.selectFirst("div._2NNxg a._2uHIS")?.text()?.trim()
            val recPoster = element.selectFirst("img.lozad")?.attr("data-src")
            val recYearText = element.selectFirst("div._2y8kd")?.text()?.trim()
            val recYear = recYearText?.split(" - ")?.firstOrNull()?.toIntOrNull()

            if (recLink != null && recTitle != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.posterUrl = recPoster
                    this.year = recYear
                }
            } else null
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = allEpisodes.reversed()
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
            //this.status = status
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = tryParseJson<EpisodeLoadData>(data)?.episodeUrl ?: data
        val response = app.get(episodeUrl)
        val doc = response.document

        val players = doc.select("ul.ul-drop.dropcaps li a.play-video.cap")
        var linksFound = false

        val allowedPlayers = listOf("FileMoon", "Mp4Upload", "Mega", "StreamW", "Streamtape", "LuluStream", "Hexupload", "VidGuard")

        if (players.isNotEmpty()) {
            players.apmap { player ->
                val playerName = player.attr("data-player-name")
                val playerPayload = player.attr("data-player")

                if (playerPayload.isNotBlank() && allowedPlayers.any { playerName.contains(it, ignoreCase = true) }) {
                    try {
                        val iframeUrl = decryptPlayerUrl(playerPayload)
                        Log.d("KatanimeProvider", "Procesando reproductor: $playerName")
                        Log.d("KatanimeProvider", "URL de Iframe desencriptada: $iframeUrl")

                        if (!iframeUrl.isNullOrBlank()) {
                            if (loadExtractor(iframeUrl, episodeUrl, subtitleCallback, callback)) {
                                linksFound = true
                                Log.d("KatanimeProvider", "Enlaces encontrados por loadExtractor para $playerName")
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("KatanimeProvider", "Error al procesar $playerName: ${e.message}")
                    }
                }
            }
        }

        Log.d("KatanimeProvider", "Finalizando loadLinks. ¿Se encontraron enlaces? $linksFound")
        return linksFound
    }

    // Función para desencriptar el payload AES-256-CBC
    private fun decryptPlayerUrl(encodedPayload: String): String? {
        return try {
            data class PlayerData(
                @JsonProperty("iv") val iv: String? = null,
                @JsonProperty("ct") val value: String? = null,
                @JsonProperty("s") val salt: String? = null
            )

            // Decodificar el payload de Base64 para obtener el JSON.
            val json = AndroidBase64.decode(encodedPayload, AndroidBase64.DEFAULT).toString(Charsets.UTF_8)
            val playerData = tryParseJson<PlayerData>(json)

            val password = "hanabi".toByteArray(Charsets.UTF_8)

            // Corregido: La sal, el IV y el valor cifrado se decodifican desde Base64.
            val salt = playerData?.salt?.let { AndroidBase64.decode(it, AndroidBase64.DEFAULT) }
            val iv = playerData?.iv?.let { AndroidBase64.decode(it, AndroidBase64.DEFAULT) }
            val encryptedValue = playerData?.value?.let { AndroidBase64.decode(it, AndroidBase64.DEFAULT) }

            if (salt == null || iv == null || encryptedValue == null) {
                Log.e("KatanimeProvider", "Datos de desencriptación incompletos (Sal, IV o valor nulo)")
                return null
            }

            val derivedKeyAndIv = deriveKeyAndIv(password, salt, 32, 16)
            val key = derivedKeyAndIv.first
            val derivedIv = derivedKeyAndIv.second

            val ivSpec = IvParameterSpec(derivedIv)
            val keySpec = SecretKeySpec(key, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(DECRYPT_MODE, keySpec, ivSpec)

            val decryptedBytes = cipher.doFinal(encryptedValue)
            return decryptedBytes.toString(Charsets.UTF_8)

        } catch (e: Exception) {
            Log.e("KatanimeProvider", "Error al desencriptar el payload: ${e.message}")
            null
        }
    }

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray, keyLength: Int, ivLength: Int): Pair<ByteArray, ByteArray> {
        val keyAndIvLength = keyLength + ivLength
        val keyAndIv = ByteArray(keyAndIvLength)
        val md = MessageDigest.getInstance("MD5")
        var currentHash = md.digest(password + salt)
        System.arraycopy(currentHash, 0, keyAndIv, 0, currentHash.size)

        var currentLength = currentHash.size
        while (currentLength < keyAndIvLength) {
            md.reset()
            currentHash = md.digest(currentHash + password + salt)
            val remainingLength = keyAndIvLength - currentLength
            val toCopy = minOf(currentHash.size, remainingLength)
            System.arraycopy(currentHash, 0, keyAndIv, currentLength, toCopy)
            currentLength += toCopy
        }

        val key = keyAndIv.copyOfRange(0, keyLength)
        val iv = keyAndIv.copyOfRange(keyLength, keyAndIvLength)

        return Pair(key, iv)
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emision" -> ShowStatus.Ongoing
            "en emision - " -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }
}
