package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import kotlinx.coroutines.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64 as AndroidBase64
import kotlinx.serialization.*

class KatanimeProvider : MainAPI() {
    override var mainUrl = "https://katanime.net"
    override var name = "KATANIME"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "mx"
    override val hasMainPage = true

    private val TAG = "KatanimeProvider"
    private val DECRYPTION_PASSWORD = "hanabi"

    @Serializable
    data class CryptoDto(val ct: String? = null, val s: String? = null)

    @Serializable
    data class EpisodeLoadData(val url: String)

    @Serializable
    data class EpisodeList(
        val ep: EpData? = null
    )

    @Serializable
    data class EpData(
        @SerialName("last_page") val lastPage: Int? = null,
        val total: Int? = null,
        val data: List<EpisodeDto> = emptyList()
    )

    @Serializable
    data class EpisodeDto(
        val numero: String? = null,
        val thumb: String? = null,
        val url: String? = null,
        val created_at: String? = null
    )

    @Serializable
    data class Ep(
        @SerialName("last_page") val lastPage: Int? = null,
        val total: Int? = null,
        val data: List<DataEpisode> = emptyList()
    )

    @Serializable
    data class DataEpisode(
        val numero: String? = null,
        val thumb: String? = null,
        val url: String? = null
    )

    private fun cleanTitle(title: String?): String? {
        return title?.replace(Regex("(?i)^(Ver|Ver Online)\\s+|\\s+Online\\s+Gratis.*$|\\s+\\(\\d{4}\\)$"), "")?.trim()
    }

    private fun String.decodeHex(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun safeBase64Decode(input: String): ByteArray {
        val cleaned = input.replace("\\", "").replace("\"", "").replace(" ", "").trim()
        return try {
            AndroidBase64.decode(cleaned, AndroidBase64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR BASE64: ${e.message}")
            ByteArray(0)
        }
    }

    private fun decryptWithSalt(cipherText: String, salt: String, password: String): String {
        return try {
            val ctBytes = AndroidBase64.decode(cipherText, AndroidBase64.DEFAULT)
            val saltBytes = salt.decodeHex()
            val passBytes = password.toByteArray(Charsets.UTF_8)

            val md = MessageDigest.getInstance("MD5")
            val generatedData = ByteArray(48)
            var generatedLength = 0
            var lastDigest = ByteArray(0)

            while (generatedLength < 48) {
                md.reset()
                if (generatedLength > 0) md.update(lastDigest)
                md.update(passBytes)
                md.update(saltBytes, 0, 8)
                lastDigest = md.digest()
                val copyLen = minOf(lastDigest.size, generatedData.size - generatedLength)
                System.arraycopy(lastDigest, 0, generatedData, generatedLength, copyLen)
                generatedLength += copyLen
            }

            val key = generatedData.copyOfRange(0, 32)
            val iv = generatedData.copyOfRange(32, 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = String(cipher.doFinal(ctBytes), Charsets.UTF_8)

            val finalUrl = decrypted.replace("\\/", "/").replace("\"", "").trim()
            Log.d(TAG, "DECRYPT-SUCCESS: $finalUrl")
            finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "DECRYPT-FAILED: ${e.message}")
            ""
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = mutableListOf<HomePageList>()

        try {
            val resRecientes = app.get("$mainUrl/animes").document
            val recientes = resRecientes.select("div._135yj").mapNotNull {
                val title = it.selectFirst("a._2uHIS")?.text() ?: return@mapNotNull null
                val animeLink = it.selectFirst("a._1A2Dc")?.attr("href") ?: ""
                val imgElem = it.selectFirst("img")
                val img = imgElem?.attr("data-src")?.ifBlank { imgElem.attr("src") } ?: ""

                newAnimeSearchResponse(cleanTitle(title)!!, fixUrl(animeLink)) {
                    this.posterUrl = fixUrl(img)
                }
            }
            if (recientes.isNotEmpty()) homePageLists.add(HomePageList("Animes Recientes", recientes))
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando recientes: ${e.message}")
        }

        try {
            val resPopulares = app.get("$mainUrl/populares").document
            val populares = resPopulares.select("div._135yj").mapNotNull {
                val title = it.selectFirst("a._2uHIS")?.text() ?: return@mapNotNull null
                val animeLink = it.selectFirst("a._1A2Dc")?.attr("href") ?: ""
                val imgElem = it.selectFirst("img")
                val img = imgElem?.attr("data-src")?.ifBlank { imgElem.attr("src") } ?: ""

                newAnimeSearchResponse(cleanTitle(title)!!, fixUrl(animeLink)) {
                    this.posterUrl = fixUrl(img)
                }
            }
            if (populares.isNotEmpty()) homePageLists.add(HomePageList("Animes Populares", populares))
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando populares: ${e.message}")
        }

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/buscar?q=$query").document
        return doc.select("div._135yj").mapNotNull {
            val title = it.selectFirst("a._2uHIS")?.text() ?: return@mapNotNull null
            val imgElem = it.selectFirst("img")
            val img = imgElem?.attr("data-src")?.ifBlank { imgElem.attr("src") } ?: ""
            newAnimeSearchResponse(cleanTitle(title)!!, fixUrl(it.selectFirst("a._1A2Dc")?.attr("href") ?: "")) {
                this.posterUrl = fixUrl(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)

        val title = cleanTitle(doc.selectFirst("h1.comics-title")?.text()) ?: ""
        val imgElem = doc.selectFirst("div#animeinfo img")
        val mainPoster = fixUrl(imgElem?.attr("data-src")?.ifBlank { imgElem.attr("src") } ?: "")

        val statusText = doc.selectFirst("span#estado")?.text()?.lowercase() ?: ""
        val status = when {
            statusText.contains("en emision") || statusText.contains("emisión") -> ShowStatus.Ongoing
            statusText.contains("finalizado") -> ShowStatus.Completed
            else -> null
        }

        val detailsBy = doc.selectFirst("div.details-by")?.text() ?: ""
        val year = Regex("(\\d{4})").find(detailsBy)?.groupValues?.get(1)?.toIntOrNull()

        val genres = doc.select("div.anime-genres a").map { it.text() }

        val apiToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        val apiUrl = doc.selectFirst("div._pagination")?.attr("data-url")
        val episodesList = mutableListOf<Episode>()

        if (apiToken != null && apiUrl != null) {
            var page = 1
            var totalProcessed = 0
            var maxEpisodes = 0
            do {
                val apiRes = app.post(apiUrl,
                    data = mapOf("_token" to apiToken, "pagina" to page.toString()),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url),
                    cookies = response.cookies
                )
                val parsed = tryParseJson<EpisodeList>(apiRes.text)
                val items = parsed?.ep?.data ?: emptyList()

                if (page == 1) maxEpisodes = parsed?.ep?.total ?: 0

                items.forEach { ep ->
                    episodesList.add(newEpisode(EpisodeLoadData(fixUrl(ep.url ?: "")).toJson()) {
                        this.name = "Episodio ${ep.numero}"
                        this.episode = ep.numero?.toIntOrNull()
                        this.posterUrl = fixUrl(ep.thumb ?: mainPoster)
                        this.addDate(ep.created_at)
                    })
                }
                totalProcessed += items.size
                page++
            } while (totalProcessed < maxEpisodes && items.isNotEmpty())
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = mainPoster
            this.plot = doc.selectFirst("#sinopsis p")?.text()
            this.showStatus = status
            this.year = year
            this.tags = genres

            val finalEpisodes = episodesList.distinctBy { it.episode }.sortedBy { it.episode }
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val episodeUrl = tryParseJson<EpisodeLoadData>(data)?.url ?: data
        val doc = app.get(episodeUrl).document
        val players = doc.select("ul.ul-drop li a[data-player]")

        players.amap { element ->
            val serverName = element.text()
            try {
                val dataPlayer = element.attr("data-player")
                val playerPage = app.get("$mainUrl/reproductor?url=$dataPlayer", referer = episodeUrl).text
                val encrypted = playerPage.substringAfter("var e = '", "").substringBefore("';", "")

                if (encrypted.isNotEmpty()) {
                    val cleanJson = encrypted.replace("\\", "")
                    val crypto = tryParseJson<CryptoDto>(cleanJson)

                    if (crypto?.ct != null && crypto.s != null) {
                        val decryptedUrl = decryptWithSalt(crypto.ct, crypto.s, DECRYPTION_PASSWORD)

                        if (decryptedUrl.startsWith("http")) {
                            Log.d(TAG, "LOG-FINAL-OK ($serverName): $decryptedUrl")

                            if (serverName.contains("SendVid", ignoreCase = true)) {
                                try {
                                    val response = app.get(decryptedUrl, referer = mainUrl).text

                                    var videoUrl = response.substringAfter("source src=\"", "").substringBefore("\"", "")
                                        .ifEmpty { response.substringAfter("video_url: '", "").substringBefore("'", "") }
                                        .replace("\\/", "/")

                                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                                        Log.d(TAG, "SENDVID: ¡MP4 encontrado! -> $videoUrl")
                                        callback(
                                            newExtractorLink(
                                                source = this@KatanimeProvider.name,
                                                name = "SendVid",
                                                url = videoUrl,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://sendvid.com/"
                                                this.quality = Qualities.P720.value
                                            }
                                        )
                                    } else {
                                        Log.w(TAG, "SENDVID: MP4 no hallado. El HTML cambió o está protegido.")
                                        callback(
                                            newExtractorLink(
                                                source = this@KatanimeProvider.name,
                                                name = "$serverName (Embed)",
                                                url = decryptedUrl,
                                                type = ExtractorLinkType.VIDEO
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error en SendVid manual: ${e.message}")
                                }
                            } else {
                                val finalUrl = when {
                                    decryptedUrl.contains("mediafire.com") -> {
                                        decryptedUrl.replace("/file/", "/download/")
                                    }
                                    decryptedUrl.contains("sfastwish.com") -> {
                                        decryptedUrl.replace("/e/", "/v/")
                                    }
                                    else -> decryptedUrl
                                }
                                loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FALLO EN SERVER $serverName: ${e.message}")
            }
        }
        true
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }
}