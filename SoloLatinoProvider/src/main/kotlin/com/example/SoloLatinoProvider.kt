package com.example

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

//yeji
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.5",
        "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "none",
        "sec-gpc" to "1",
        "Referer" to mainUrl
    )

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 5,
        delayMs: Long = 5000L,
        timeoutMs: Long = 30000L
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("SoloLatino", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, timeout = timeoutMs, headers = baseHeaders)
                Log.d("SoloLatino", "safeAppGet - HTTP ${res.code} para URL: $url")
                when {
                    res.isSuccessful -> return res.text
                    res.code == 429 -> {
                        Log.w("SoloLatino", "safeAppGet - Rate limit 429, esperando 15s para: $url")
                        delay(15000L)
                        continue
                    }
                    else -> Log.w("SoloLatino", "safeAppGet - HTTP ${res.code} no exitoso para: $url")
                }
            } catch (e: Exception) {
                Log.e("SoloLatino", "safeAppGet - Error intento ${i + 1}: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        Log.e("SoloLatino", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    private suspend fun safeAppGetDoc(url: String, timeoutMs: Long = 30000L) =
        app.get(url, timeout = timeoutMs, headers = baseHeaders).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("SoloLatino", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}")
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Series Recientes", "$mainUrl/series"),
            Pair("Animes Recientes", "$mainUrl/animes"),
            Pair("Doramas Recientes", "$mainUrl/doramas"),
            Pair("Películas Recientes", "$mainUrl/peliculas")
        )

        val homePageLists = urls.map { (name, url) ->
            val tvType = when (name) {
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Películas" -> TvType.Movie
                else -> TvType.Others
            }
            val html = safeAppGet(url)
            if (html == null) {
                Log.e("SoloLatino", "getMainPage - No se pudo obtener HTML para $url")
                return@map null
            }
            val doc = Jsoup.parse(html)
            val homeItems = doc.select("div.card").mapNotNull { card ->
                val title = card.selectFirst("span.card__title")?.text()
                val link = card.selectFirst("a")?.attr("href")
                val img = card.selectFirst("img.card__poster")?.attr("src") ?: ""
                Log.d("SoloLatino", "DEBUG: título=$title, link=$link, img=$img")
                if (title != null && link != null) {
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else {
                    Log.w("SoloLatino", "ADVERTENCIA: Elemento incompleto para URL: $url")
                    null
                }
            }
            Log.d("SoloLatino", "DEBUG: $name -> ${homeItems.size} items encontrados")
            HomePageList(name, homeItems)
        }.filterNotNull()

        items.addAll(homePageLists)
        Log.d("SoloLatino", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("SoloLatino", "DEBUG: search para query: $query")
        val url = "$mainUrl/buscar?q=$query"
        val html = safeAppGet(url) ?: run {
            Log.e("SoloLatino", "search - No se pudo obtener HTML para: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)
        return doc.select("div.card").mapNotNull { card ->
            val title = card.selectFirst("span.card__title")?.text()
            val link = card.selectFirst("a")?.attr("href")
            val img = card.selectFirst("img.card__poster")?.attr("src") ?: ""
            if (title != null && link != null) {
                val isMovie = link.contains("/pelicula/")
                newAnimeSearchResponse(title, fixUrl(link)) {
                    this.type = if (isMovie) TvType.Movie else TvType.TvSeries
                    this.posterUrl = img
                }
            } else null
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        delay(1000L)
        Log.d("SoloLatino", "load - URL: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
        } else if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://" + cleanUrl.removePrefix("//")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SoloLatino", "load - ERROR: URL en blanco.")
            return null
        }

        val html = safeAppGet(cleanUrl) ?: run {
            Log.e("SoloLatino", "load - No se pudo obtener HTML para: $cleanUrl")
            return null
        }
        val doc = Jsoup.parse(html)

        val rawTitle = doc.selectFirst("div.flex-1 h1")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore("—")?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: ""

        val title = rawTitle
            .replace(Regex("(?i)Ver\\s+"), "")
            .replace(Regex("(?i)\\s+online.*"), "")
            .replace(Regex("(?i)\\s+latino.*"), "")
            .replace(Regex("(?i)\\s+en\\s+español.*"), "")
            .replace(Regex("(?i)\\s+solo\\s+en.*"), "")
            .trim()

        Log.d("SoloLatino", "load - Título limpio: $title")

        val tvType = if (cleanUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

        val description = doc.selectFirst("p.text-sm.leading-relaxed")?.text() ?: ""
        val detail = doc.selectFirst("div.flex-1.min-w-0")
        val tags = detail?.select("a[href*='/genero/']")?.map { it.text().trim() } ?: emptyList()
        val averageScore = doc.selectFirst("span.rating-badge__val")?.text()?.toDoubleOrNull()
        val durationMain = doc.select("div.flex.flex-wrap.items-center.gap-4.text-sm span")
            .firstOrNull { it.text().contains(Regex("(?i)\\d+h|\\d+m|\\d+\\s?min")) }
            ?.text()
            ?.let { durText ->
                val hours = Regex("""(\d+)h""").find(durText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("""(\d+)m""").find(durText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val pureMinutes = if (hours == 0 && minutes == 0) {
                    Regex("""(\d+)""").find(durText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                } else 0

                val total = (hours * 60) + minutes + pureMinutes
                if (total > 0) total else null
            }

        val year = doc.select("div.flex.flex-wrap.items-center.gap-4.text-sm span")
            .firstOrNull { it.text().matches(Regex("""\d{4}""")) }
            ?.text()?.toIntOrNull()

        val poster = doc.selectFirst("div.flex-shrink-0 img")?.attr("src") ?: ""
        val backgroundPoster = doc.selectFirst("div.detail-hero__bg")
            ?.attr("style")
            ?.let { Regex("""url\('([^']+)'\)""").find(it)?.groupValues?.get(1) }
            ?: poster

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div[data-season-panel] a.ep-item").mapNotNull { element ->
                val epUrl = fixUrl(element.attr("href"))
                val epTitle = element.selectFirst("p.text-sm.font-semibold")?.text() ?: ""
                val epNum = element.selectFirst("p.ep-num")?.text()
                val episodeNumber = epNum?.removePrefix("E")?.toIntOrNull()
                val seasonPanel = element.parents().firstOrNull { it.hasAttr("data-season-panel") }
                val seasonNumber = seasonPanel?.attr("data-season-panel")?.toIntOrNull()
                val epPoster = element.selectFirst("img.ep-thumb")?.attr("src") ?: ""
                val epDesc = element.selectFirst("p.line-clamp-2")?.text() ?: ""
                val epDate = element.select("p.text-xs").lastOrNull()?.text() ?: ""
                val epReleaseDate = if (epDate.isNotBlank()) {
                    try {
                        SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(epDate)?.time
                    } catch (_: Exception) { null }
                } else null
                if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = epPoster
                        this.description = epDesc.ifBlank { null }
                        if (epReleaseDate != null) {
                            this.addDate(java.util.Date(epReleaseDate))
                        }
                    }
                } else null
            }
        } else listOf()

        //Log.d("SoloLatino", "load - ${episodes.size} episodios encontrados para $cleanUrl")

        val recommendations = doc.select("div.scroll-row div.card").mapNotNull { card ->
            val recLink = card.selectFirst("a")?.attr("href")
            val recTitle = card.selectFirst("span.card__title")?.text()
                ?: card.selectFirst("p.card__title")?.text()
            val recImg = card.selectFirst("img.card__poster")?.attr("src") ?: ""
            if (recTitle != null && recLink != null) {
                newAnimeSearchResponse(recTitle, fixUrl(recLink)) {
                    this.posterUrl = recImg
                    this.type = if (recLink.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
                }
            } else null
        }

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(name = title, url = cleanUrl, type = tvType, episodes = episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.year = year
                this.duration = durationMain
                this.score = averageScore?.let { Score.from10(it) }
            }
            TvType.Movie -> newMovieLoadResponse(name = title, url = cleanUrl, type = tvType, dataUrl = cleanUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.year = year
                this.duration = durationMain
                this.score = averageScore?.let { Score.from10(it) }
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
        val targetUrl = data.trim()
        Log.d("SoloLatino", "loadLinks - Iniciando en: $targetUrl")

        if (targetUrl.isBlank()) return false

        val html = safeAppGet(targetUrl) ?: return false
        val doc = Jsoup.parse(html)

        val serverButtons = doc.select("button[data-server-url], .server-btn")

        val serverUrls = serverButtons.mapNotNull { it.attr("data-server-url") }.toMutableList()

        if (serverUrls.isEmpty()) {
            doc.selectFirst("div#player-frame iframe, iframe")?.attr("src")?.let {
                if (it.isNotBlank()) serverUrls.add(it)
            }
        }

        if (serverUrls.isEmpty()) {
            Log.e("SoloLatino", "loadLinks - No se encontraron servidores en la página.")
            return false
        }

        Log.d("SoloLatino", "loadLinks - Total servidores detectados: ${serverUrls.size}")

        serverUrls.distinct().forEach { rawUrl ->
            val fixedSrc = fixUrl(rawUrl)
            Log.d("SoloLatino", "loadLinks - Procesando: $fixedSrc")

            when {
                fixedSrc.contains("embed69.org") -> {
                    Log.d("SoloLatino", "BRANCH: embed69.org")
                    val embed69Headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                        "Accept" to "*/*",
                        "Referer" to fixedSrc,
                    )
                    val embedResp = app.get(fixedSrc, headers = embed69Headers, timeout = 30000L)
                    Log.d("SoloLatino", "embed69 - HTTP ${embedResp.code}, length=${embedResp.text.length}")
                    val embedDoc = embedResp.document
                    val dataLinkScript = embedDoc.select("script")
                        .firstOrNull { it.html().contains("dataLink =") }
                    if (dataLinkScript == null) {
                        Log.e("SoloLatino", "embed69 - No se encontró script con 'dataLink ='")
                        embedDoc.select("script").forEach { s ->
                            val h = s.html()
                            if (h.length in 50..500) Log.d("SoloLatino", "embed69 script: ${h.take(200)}")
                        }
                    } else {
                        Log.d("SoloLatino", "embed69 - script con dataLink encontrado")
                        val dataLinkJson = dataLinkScript.html()
                            .substringAfter("dataLink =")
                            .substringBefore(";")
                            .trim()
                        Log.d("SoloLatino", "embed69 - dataLink JSON: ${dataLinkJson.take(300)}")

                        val pageHtml = embedResp.text
                        val embedChallenge = Regex("""POW_CHALLENGE\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
                        val embedSalt = Regex("""POW_SALT\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
                        if (embedChallenge == null || embedSalt == null) {
                            Log.e("SoloLatino", "embed69 - No se pudo extraer POW_CHALLENGE/SALT de la página")
                            return@forEach
                        }
                        Log.d("SoloLatino", "embed69 - challenge=$embedChallenge salt=$embedSalt")

                        tryParseJson<List<ServersByLang>>(dataLinkJson)?.amap { lang ->
                            val encryptedLinks = lang.sortedEmbeds.mapNotNull { it.link }
                            if (encryptedLinks.isEmpty()) return@amap
                            Log.d("SoloLatino", "embed69 - ${encryptedLinks.size} enlaces encriptados para ${lang.videoLanguage}")

                            val aesKey = withContext(Dispatchers.Default) { solveEmbed69PoW(embedChallenge, embedSalt) }
                            if (aesKey == null) {
                                Log.e("SoloLatino", "embed69 - PoW failed")
                                return@amap
                            }
                            Log.d("SoloLatino", "embed69 - PoW solved, decrypting ${encryptedLinks.size} links")
                            val langTag = when (lang.videoLanguage?.uppercase()) {
                                "LAT" -> "LATINO"
                                "SUB", "ENGLISH" -> "SUBTITULADO"
                                "CAST", "SPANISH" -> "CASTELLANO"
                                "ENG", "VOSE" -> "VOSE"
                                "JAP", "JAPANESE" -> "JAPONES"
                                else -> lang.videoLanguage ?: "??"
                            }
                            encryptedLinks.forEach { encrypted ->
                                val decryptedUrl = decryptAESLocal(encrypted, aesKey)
                                if (decryptedUrl != null) {
                                    Log.d("SoloLatino", "embed69 - decrypted: ${decryptedUrl.take(100)}")
                                    loadSourceNameExtractor(langTag, fixHostsLinks(decryptedUrl), fixedSrc, subtitleCallback, callback)
                                }
                            }
                        } ?: Log.e("SoloLatino", "embed69 - No se pudo parsear dataLink JSON")
                    }
                }

                fixedSrc.contains("xupalace.org") -> {
                    Log.d("SoloLatino", "BRANCH: xupalace.org")
                    val xupalaceHtml = app.get(fixedSrc, headers = baseHeaders).text
                    val regex = Regex("""go_to_playerVast\s*\(\s*'([^']+)'""")
                    val foundLinks = regex.findAll(xupalaceHtml).map { it.groupValues[1] }.distinct().toList()

                    if (foundLinks.isNotEmpty()) {
                        Log.d("SoloLatino", "xupalace - ${foundLinks.size} links por go_to_playerVast")
                        foundLinks.amap { link ->
                            loadExtractor(fixHostsLinks(fixUrl(link)), fixedSrc, subtitleCallback, callback)
                        }
                    } else {
                        val docX = Jsoup.parse(xupalaceHtml)
                        val liLinks = docX.select("li[onclick*='http']").mapNotNull {
                            val clickAttr = it.attr("onclick")
                            Regex("'([^']+)'").find(clickAttr)?.groupValues?.get(1)
                        }
                        Log.d("SoloLatino", "xupalace - ${liLinks.size} links por li onclick")
                        liLinks.amap { loadExtractor(fixHostsLinks(fixUrl(it)), fixedSrc, subtitleCallback, callback) }
                    }
                }

                else -> {
                    Log.d("SoloLatino", "BRANCH: Direct/Generic: $fixedSrc")
                    val cleanUrl = fixHostsLinks(fixedSrc)
                    Log.d("SoloLatino", "generic - intentando loadExtractor con: $cleanUrl")
                    try {
                        val genResp = app.get(cleanUrl, headers = baseHeaders, timeout = 15000L)
                        Log.d("SoloLatino", "generic - respuesta HTTP ${genResp.code}, length=${genResp.text.length}")
                        genResp.document.select("iframe").forEach { iframe ->
                            val iframeSrc = iframe.attr("src")
                            if (iframeSrc.isNotBlank()) {
                                Log.d("SoloLatino", "generic - iframe encontrado: $iframeSrc")
                                loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SoloLatino", "generic - error: ${e.message}")
                    }
                    loadExtractor(cleanUrl, targetUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

private suspend fun solveEmbed69PoW(challenge: String, salt: String): ByteArray? {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    var nonce = 0L
    val maxAttempts = 500000L
    while (nonce < maxAttempts) {
        val input = "$challenge$nonce".toByteArray(Charsets.UTF_8)
        val hash = md.digest(input).joinToString("") { "%02x".format(it) }
        if (hash.startsWith("000")) {
            Log.d("SoloLatino", "embed69 PoW - nonce=$nonce hash=${hash.take(8)}")
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest("$challenge$nonce$salt".toByteArray(Charsets.UTF_8))
        }
        nonce++
        if (nonce % 10000 == 0L) delay(1)
    }
    Log.e("SoloLatino", "embed69 PoW - no solution found after $maxAttempts attempts")
    return null
}

private fun decryptAESLocal(encryptedBase64: String, aesKey: ByteArray): String? {
    return try {
        val raw = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (raw.size < 17) {
            Log.e("SoloLatino", "AES decryption: raw data too short (${raw.size} bytes)")
            return null
        }
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        if (ciphertext.size % 16 != 0) {
            Log.e("SoloLatino", "AES decryption: ciphertext not multiple of block size (${ciphertext.size})")
            return null
        }
        val keySpec = SecretKeySpec(aesKey.copyOfRange(0, 32), "AES")
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            return String(decrypted, Charsets.UTF_8)
        } catch (e1: Exception) {
            Log.w("SoloLatino", "AES PKCS5 failed: ${e1.message}, trying NoPadding")
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            val padByte = decrypted.last().toInt() and 0xFF
            var padLen = if (padByte in 1..16) padByte else 0
            if (padLen > 0) {
                for (i in decrypted.size - padLen until decrypted.size) {
                    if ((decrypted[i].toInt() and 0xFF) != padByte) {
                        padLen = 0
                        break
                    }
                }
            }
            return String(decrypted.copyOfRange(0, decrypted.size - padLen), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.e("SoloLatino", "AES decrypt error: ${e.message}")
        null
    }
}

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink("$source[${link.source}]", "$source[${link.source}]", link.url) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://bysedikamoum.com", "https://filemoon.sx")
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
        .replaceFirst("https://doodstream.com", "https://dood.la")
        .replaceFirst("https://streamtape.com", "https://streamtape.cc")
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList(),
)

data class LinksRequest(val links: List<String>)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
    val reason: String? = null
)

data class Link(
    val index: Long,
    val link: String,
)