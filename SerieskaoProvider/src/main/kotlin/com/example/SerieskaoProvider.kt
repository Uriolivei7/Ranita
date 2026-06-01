package com.example

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SerieskaoProvider : MainAPI() {
    override var mainUrl = "https://serieskao.top"
    override var name = "SeriesKao"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )

    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val TAG = "SeriesKao"

    companion object {
        private val fixHosts = mapOf(
            "https://hglink.to" to "https://streamwish.to",
            "https://swdyu.com" to "https://streamwish.to",
            "https://cybervynx.com" to "https://streamwish.to",
            "https://dumbalag.com" to "https://streamwish.to",
            "https://awish.pro" to "https://streamwish.to",
            "https://mivalyo.com" to "https://vidhidepro.com",
            "https://dinisglows.com" to "https://vidhidepro.com",
            "https://dhtpre.com" to "https://vidhidepro.com",
            "https://filemoon.link" to "https://filemoon.sx",
            "https://sblona.com" to "https://watchsb.com",
            "https://lulu.st" to "https://lulustream.com",
            "https://uqload.io" to "https://uqload.com",
            "https://do7go.com" to "https://dood.la",
            "https://doodstream.com" to "https://dood.la",
            "https://streamtape.com" to "https://streamtape.cc",
            "https://minochinos.com" to "https://vidhidepro.com",
        )
    }

    private fun fixHostsTitle(url: String): String {
        var result = url
        fixHosts.forEach { (old, new) ->
            result = result.replaceFirst(old, new)
        }
        return result
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/generos/dorama"),
        )

        Log.d(TAG, "getMainPage page=$page, categorías=${urls.size}")
        val homePageLists = urls.amap { (name, url) ->
            try {
                val doc = app.get(url).document
                val items = doc.select("article.card")
                Log.d(TAG, "getMainPage[$name] article.card=${items.size}")
                val homeItems = items.mapNotNull { element ->
                    try {
                        val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                        val link = linkEl.attr("href")
                        val title = element.selectFirst("h2.card__title")?.text()?.trim()
                        val img = element.selectFirst("figure.card__poster img")?.attr("src")
                        val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                        if (title == null || link.isBlank()) {
                            Log.w(TAG, "getMainPage[$name] saltado: title='$title' link='$link'")
                            return@mapNotNull null
                        }
                        newAnimeSearchResponse(title, fixUrl(link)) {
                            this.posterUrl = fixUrlNull(img)
                            this.type = when (typeBadge) {
                                "PEL" -> TvType.Movie
                                "ANI" -> TvType.Anime
                                else -> TvType.TvSeries
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "getMainPage[$name] error item: ${e.message}")
                        null
                    }
                }
                Log.d(TAG, "getMainPage[$name] items válidos=${homeItems.size}")
                HomePageList(name, homeItems)
            } catch (e: Exception) {
                Log.e(TAG, "getMainPage[$name] error url='$url': ${e.message}")
                HomePageList(name, emptyList())
            }
        }
        Log.d(TAG, "getMainPage total=${homePageLists.sumOf { it.list.size }}")
        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) {
            Log.w(TAG, "search query vacía")
            return emptyList()
        }
        val url = "$mainUrl/search?s=$query"
        Log.d(TAG, "search query='$query' url=$url")
        return try {
            val doc = app.get(url).document
            val items = doc.select("article.card")
            Log.d(TAG, "search article.card=${items.size}")
            items.mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                    val link = linkEl.attr("href")
                    val title = element.selectFirst("h2.card__title")?.text()?.trim()
                    val img = element.selectFirst("figure.card__poster img")?.attr("src")
                    val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                    if (title == null || link.isBlank()) return@mapNotNull null
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = fixUrlNull(img)
                        this.type = when (typeBadge) {
                            "PEL" -> TvType.Movie
                            "ANI" -> TvType.Anime
                            else -> TvType.TvSeries
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "search error item: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error '$url': ${e.message}")
            emptyList()
        }
    }

    private fun parseRecommendations(doc: org.jsoup.nodes.Document): List<SearchResponse>? {
        return try {
            val section = doc.selectFirst("section.section:has(h3.section__title)")
            if (section == null) return null
            section.select("article.card").mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a.card__link") ?: return@mapNotNull null
                    val link = linkEl.attr("href")
                    val title = element.selectFirst("h4.card__title")?.text()?.trim()
                    val img = element.selectFirst("figure.card__poster img")?.attr("src")
                    val typeBadge = element.selectFirst("span.card__badge--type")?.text()
                    if (title == null || link.isBlank()) return@mapNotNull null
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.posterUrl = fixUrlNull(img)
                        this.type = when (typeBadge) {
                            "PEL" -> TvType.Movie
                            "ANI" -> TvType.Anime
                            else -> TvType.TvSeries
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parseRecommendations error item: ${e.message}")
                    null
                }
            }.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "parseRecommendations error: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load url=$url")
        return try {
            val doc = app.get(url).document
            val isMovie = url.contains("/pelicula/")
            Log.d(TAG, "load isMovie=$isMovie")

            val title = doc.selectFirst("h1.detail-hero__title")?.text()?.trim() ?: ""
            Log.d(TAG, "load title='$title'")

            val year = doc.selectFirst("div.detail-hero__meta > span:not([class])")?.text()?.toIntOrNull()
                ?: Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            Log.d(TAG, "load year=$year")

            val genres = doc.select("a.detail-hero__genre").mapNotNull { it.text().trim().ifBlank { null } }
            Log.d(TAG, "load genres=${genres.joinToString()}")

            val ratingText = doc.selectFirst("span.detail-hero__rating")?.text()?.trim()
            val score = ratingText?.toDoubleOrNull()?.let { Score.from10(it) }
            Log.d(TAG, "load rating='$ratingText' score=$score")

            val poster = fixUrl(doc.selectFirst("figure.detail-hero__poster img")?.attr("src") ?: "")
            val description = doc.selectFirst("h2.detail-hero__desc")?.text()?.trim() ?: ""
            Log.d(TAG, "load poster='$poster' desc len=${description.length}")

            val recommendations = parseRecommendations(doc)
            Log.d(TAG, "load recommendations=${recommendations?.size}")

            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    this.score = score
                    this.recommendations = recommendations
                }
            } else {
                val epItems = doc.select("a.episode-item")
                Log.d(TAG, "load episodios HTML=${epItems.size}")
                val episodes = epItems.mapNotNull { element ->
                    try {
                        val epUrl = fixUrl(element.attr("href") ?: "")
                        val epTitle = element.selectFirst("span.episode-item__title")?.text()?.trim() ?: ""
                        val epNum = element.selectFirst("span.episode-item__number")?.text()?.toIntOrNull()
                        if (epUrl.isBlank()) return@mapNotNull null
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "load error episodio: ${e.message}")
                        null
                    }
                }
                Log.d(TAG, "load -> TvSeries, episodios=${episodes.size}")
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    this.score = score
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error '$url': ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        Log.d(TAG, "loadLinks data='${data.take(120)}'")
        val doc = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error fetching page: ${e.message}")
            return@coroutineScope false
        }

        val iframeSrc = doc.selectFirst("iframe#player-iframe")?.attr("src")
        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks no se encontró iframe#player-iframe")
            return@coroutineScope false
        }

        val playerUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
        Log.d(TAG, "loadLinks playerUrl=$playerUrl")

        if (!playerUrl.contains(mainUrl.removePrefix("https://").removePrefix("http://"))) {
            Log.d(TAG, "loadLinks embed externo: $playerUrl")
            when {
                playerUrl.contains("xupalace.org/video/") -> {
                    Log.d(TAG, "loadLinks xupalace video -> extrayendo go_to_playerVast")
                    try {
                        val xDoc = app.get(playerUrl, referer = data).document
                        val links = xDoc.select("*[onclick*='go_to_playerVast']").mapNotNull { el ->
                            Regex("""go_to_playerVast\('([^']+)'""").find(el.attr("onclick"))?.groupValues?.get(1)
                        }
                        if (links.isEmpty()) {
                            Log.w(TAG, "loadLinks sin go_to_playerVast en xupalace")
                            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
                        } else {
                            Log.d(TAG, "loadLinks xupalace links=${links.size}: $links")
                            links.amap { link ->
                                loadExtractor(fixHostsTitle(link), playerUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks xupalace error: ${e.message}")
                        loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
                    }
                }

                playerUrl.contains("xupalace.org/uqlink.php") || playerUrl.contains("xupalace.org/ggtz") -> {
                    Log.d(TAG, "loadLinks xupalace uqlink -> siguiendo iframe")
                    try {
                        val xDoc = app.get(playerUrl, referer = data).document
                        val iframeSrc = xDoc.selectFirst("iframe")?.attr("src")
                        if (iframeSrc != null) {
                            loadExtractor(fixHostsTitle(fixUrl(iframeSrc)), playerUrl, subtitleCallback, callback)
                        } else {
                            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks xupalace uqlink error: ${e.message}")
                        loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
                    }
                }

                else -> {
                    Log.d(TAG, "loadLinks embed directo -> loadExtractor")
                    loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
                }
            }
            return@coroutineScope true
        }

        val playerHtml = try {
            app.get(playerUrl, referer = data).text
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error fetching playerUrl: ${e.message}")
            return@coroutineScope false
        }

        val dataLinkMatch = Regex("""dataLink\s*=\s*(\[.*?\])\s*;""").find(playerHtml)
        if (dataLinkMatch == null) {
            Log.e(TAG, "loadLinks sin dataLink en playerUrl -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
            return@coroutineScope true
        }

        val jsonStr = dataLinkMatch.groupValues[1]
        Log.d(TAG, "loadLinks dataLink JSON len=${jsonStr.length}")
        val items = tryParseJson<List<DataLinkEntry>>(jsonStr)
        if (items == null) {
            Log.e(TAG, "loadLinks error parseando dataLink JSON: ${jsonStr.take(200)}")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks items=${items.size}")

        val embedChallenge = Regex("""POW_CHALLENGE\s*=\s*'([^']+)'""").find(playerHtml)?.groupValues?.get(1)
        val embedSalt = Regex("""POW_SALT\s*=\s*'([^']+)'""").find(playerHtml)?.groupValues?.get(1)
        if (embedChallenge == null || embedSalt == null) {
            Log.e(TAG, "loadLinks sin POW_CHALLENGE/SALT -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks challenge=$embedChallenge salt=$embedSalt")

        val langMap = mapOf("LAT" to "LATINO", "ESP" to "CASTELLANO", "SUB" to "SUBTITULADO")
        val aesKey = withContext(Dispatchers.Default) { solveEmbed69PoW(embedChallenge, embedSalt) }
        if (aesKey == null) {
            Log.e(TAG, "loadLinks PoW failed -> loadExtractor directo")
            loadExtractor(fixHostsTitle(playerUrl), data, subtitleCallback, callback)
            return@coroutineScope true
        }
        Log.d(TAG, "loadLinks PoW solved, items=${items.size}")

        items.forEach { item ->
            val langTag = langMap[item.videoLanguage] ?: item.videoLanguage ?: "??"
            item.sortedEmbeds?.forEach { embed ->
                val encrypted = embed.link ?: return@forEach
                val decrypted = decryptAESLocal(encrypted, aesKey)
                if (decrypted != null) {
                    Log.d(TAG, "loadLinks decrypted ${embed.servername}: $decrypted")
                    loadExtractor(fixHostsTitle(decrypted), playerUrl, subtitleCallback) { link ->
                        CoroutineScope(Dispatchers.IO).launch {
                            callback(newExtractorLink(
                                "$langTag[${link.source}]",
                                "$langTag[${link.source}]",
                                link.url
                            ) {
                                this.quality = link.quality
                                this.type = link.type
                                this.referer = link.referer
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            })
                        }
                    }
                }
            }
        }
        return@coroutineScope true
    }

    private suspend fun solveEmbed69PoW(challenge: String, salt: String): ByteArray? {
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        val maxAttempts = 500000L
        while (nonce < maxAttempts) {
            val input = "$challenge$nonce".toByteArray(Charsets.UTF_8)
            val hash = md.digest(input).joinToString("") { "%02x".format(it) }
            if (hash.startsWith("000")) {
                Log.d(TAG, "PoW nonce=$nonce hash=${hash.take(8)} attempts=$nonce")
                return MessageDigest.getInstance("SHA-256")
                    .digest("$challenge$nonce$salt".toByteArray(Charsets.UTF_8))
            }
            nonce++
        }
        Log.e(TAG, "PoW no solution after $maxAttempts attempts")
        return null
    }

    private fun decryptAESLocal(encryptedBase64: String, aesKey: ByteArray): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (raw.size < 17) return null
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            if (ciphertext.size % 16 != 0) return null
            val keySpec = SecretKeySpec(aesKey.copyOfRange(0, 32), "AES")
            val cipher = try { Cipher.getInstance("AES/CBC/PKCS5Padding") } catch (e: Throwable) { Cipher.getInstance("AES/CBC/PKCS7PADDING") }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "AES error: ${e.message}")
            null
        }
    }

    data class DataLinkEntry(
        @JsonProperty("file_id") val fileId: String? = null,
        @JsonProperty("video_language") val videoLanguage: String? = null,
        @JsonProperty("sortedEmbeds") val sortedEmbeds: List<SortedEmbed>? = emptyList(),
    )

    data class SortedEmbed(
        @JsonProperty("servername") val servername: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
