package com.example

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DanimadosProvider : MainAPI() {
    companion object {
        private const val BASE_URL = "https://danimados.cc"
        var pluginContext: Context? = null
        private val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        )

        private fun unpackPackedJs(html: String): String? {
            val packedRegex = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\s*\.split\s*\(\s*'\|'\s*\)"""
            )
            val match = packedRegex.find(html) ?: return null
            val packed = match.groupValues[1]
            val base = match.groupValues[2].toIntOrNull() ?: return null
            val count = match.groupValues[3].toIntOrNull() ?: return null
            val keywords = match.groupValues[4].split("|")
            if (count != keywords.size) return null
            var result = packed
            for (i in count - 1 downTo 0) {
                val kw = keywords.getOrElse(i) { "" }
                if (kw.isNotEmpty()) {
                    val keyRegex = Regex("\\b${Regex.escape(i.toString(base))}\\b")
                    result = result.replace(keyRegex, Regex.escapeReplacement(kw))
                }
            }
            return result.replace("\\'", "'")
        }

        private fun hexToBytes(hex: String): ByteArray {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return bytes
        }

        private fun decryptAesCbc(hexInput: String, key: String, iv: String): String {
            val keySpec = SecretKeySpec(key.encodeToByteArray(), "AES")
            val ivSpec = IvParameterSpec(iv.encodeToByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(hexToBytes(hexInput))
            return String(decrypted, Charsets.UTF_8)
        }
    }

    override var mainUrl = BASE_URL
    override var name = "Danimados"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "Últimas Series Agregadas",
        "/network/cartoon-network/" to "Cartoon Network",
        "/network/disney-channel/" to "Disney Channel",
        "/network/disney-xd/" to "Disney XD",
        "/network/nickelodeon/" to "Nickelodeon",
        "/network/fox-kids/" to "FOX Kids",
        "/network/boomerang/" to "Boomerang",
        "/network/fox/" to "FOX",
        "/network/fx/" to "FX",
        "/network/the-wb/" to "Warner",
        "/network/adult-swim/" to "Adult Swim",
        "/network/mtv/" to "MTV",
        "/network/comedy-central/" to "Comedy Central",
        "/network/locomotion-tv/" to "Locomotion",
        "/network/hbo-max/" to "HBO Max",
        "/network/apple-tv/" to "Apple TV",
        "/network/disney/" to "Disney+",
        "/network/hulu/" to "Hulu",
        "/network/paramount/" to "Paramount+",
        "/network/netflix/" to "Netflix",
        "/network/prime-video/" to "Prime Video",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val base = if (request.data == "/") {
                mainUrl
            } else {
                "$mainUrl${request.data.removeSuffix("/")}/"
            }

            val url = if (page <= 1) base else "${base}page/$page/"
            val doc = app.get(url, headers = browserHeaders).document
            val items = doc.select("article.item.tvshows").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) {
                Log.w("Danimados", "getMainPage '${request.name}' p$page: 0 items (html=${doc.html().length})")
            }
            val hasNext = doc.select("div.pagination a.arrow_pag, div.pagination a[href*=/page/]").isNotEmpty()
                || items.size >= 20
            newHomePageResponse(
                list = HomePageList(request.name, items),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            Log.e("Danimados", "getMainPage '${request.name}' p$page fallo: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$BASE_URL/?s=$query", headers = browserHeaders).document
            val results = mutableListOf<SearchResponse>()
            for (selector in listOf("article.item.tvshows", "article.item", ".result-item", ".search-item")) {
                val found = doc.select(selector).mapNotNull { it.toSearchResponse() }
                results.addAll(found)
                if (results.isNotEmpty()) break
            }

            if (results.isEmpty()) {
                for (article in doc.select("article")) {
                    val link = article.select(".data h3 a[href*='/series/']").first()
                        ?: article.select("h3 a[href*='/series/']").first()
                        ?: article.select("a[href*='/series/']").first() ?: continue
                    val href = link.attr("abs:href")
                    val title = link.text().trim()
                    if (title.isBlank() || !href.contains("/series/")) continue
                    val poster = article.select("img").first()?.attr("src")?.let { fixUrl(it) }
                    results.add(newMovieSearchResponse(title, href, TvType.Cartoon) { this.posterUrl = poster })
                }
            }

            if (results.isEmpty()) Log.w("Danimados", "search '$query': sin resultados")
            results.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("Danimados", "search '$query' fallo: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = browserHeaders).document

            val title = doc.selectFirst(".sheader .data h1")?.text()
                ?: doc.selectFirst("title")?.text()?.substringBefore(" –")?.substringBefore(" -")?.trim()
            if (title == null) {
                Log.w("Danimados", "load $url: sin título")
                return null
            }

            val poster = doc.selectFirst(".sheader .poster img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: ""

            val background = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

            val description = doc.selectFirst(".hero-overview, .wp-content p")?.text()
                ?: doc.selectFirst("meta[name='description']")?.attr("content")

            val year = doc.selectFirst(".sheader .data .meta-top span")?.text()?.let { extractYear(it) }

            val rating = doc.selectFirst("#repimdb strong")?.text()?.toDoubleOrNull()

            val episodes = extractEpisodes(doc)
            Log.d("Danimados", "load '$title': ${episodes.size} eps")


            val recommendations = doc.select("#single_relacionados article").mapNotNull { art ->
                val a = art.selectFirst("a[href*='/series/']") ?: return@mapNotNull null
                val href = a.attr("abs:href")
                if (href.isBlank() || !href.contains("/series/")) return@mapNotNull null
                val img = a.selectFirst("img")
                val t = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newMovieSearchResponse(t, href, TvType.Cartoon) {
                    this.posterUrl = img.attr("src")?.let { fixUrl(it) }
                }
            }.distinctBy { it.url }.take(12).ifEmpty { null }

            if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.backgroundPosterUrl = background
                    this.plot = description
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    this.recommendations = recommendations
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Cartoon, url) {
                    this.posterUrl = fixUrl(poster)
                    this.backgroundPosterUrl = background
                    this.plot = description
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("Danimados", "load $url fallo: ${e.message}")
            null
        }
    }

    private val challengeHosts = listOf("voe.", "hglink", "streamwish", "johnbeyondnation")

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cdnDomains = listOf("acek-cdn", "dramiyos", "vidhidepro", "vidhide", "premilkyway", "cyou", "okcdn", "ok.ru")
        val cdnPaths = listOf("/hls2/", "/hls3/", ".urlset/")
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val isCdn = cdnDomains.any { url.contains(it, ignoreCase = true) } ||
                cdnPaths.any { url.contains(it, ignoreCase = true) }
            if (!isCdn) return@Interceptor chain.proceed(request)

            Log.d("Danimados", "[intercept] CDN request: ${url.take(120)}")
            val newRequest = request.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                .header("Referer", extractorLink.referer ?: BASE_URL)
                .header("Origin", runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}" } }.getOrDefault(BASE_URL))
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(newRequest)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = try {
            app.get(data, headers = browserHeaders)
        } catch (e: Exception) {
            Log.e("Danimados", "loadLinks $data fallo: ${e.message}")
            return false
        }
        val doc = resp.document

        val ajaxUrl = doc.selectFirst("#dt_main_ajax-js-extra")?.html()?.let { html ->
            Regex("""url["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
                ?.let { fixRelativeUrl(it) }
        } ?: "$BASE_URL/wp-admin/admin-ajax.php"

        val playerOptions = doc.select(".dooplay_player_option")
        if (playerOptions.isEmpty()) {
            Log.w("Danimados", "loadLinks $data: sin player options")
            return false
        }

        var anySuccess = false
        for (option in playerOptions) {
            val postId = option.attr("data-post")
            val nume = option.attr("data-nume").toIntOrNull() ?: 1
            val type = option.attr("data-type").ifBlank { "tv" }

            val playerResp = try {
                app.post(
                    ajaxUrl,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Referer" to data,
                    ),
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume.toString(),
                        "type" to type,
                    )
                ).parsedSafe<PlayerResponse>()
            } catch (e: Exception) {
                Log.e("Danimados", "loadLinks ajax nume=$nume fallo: ${e.message}")
                null
            }
            if (playerResp == null) continue
            val embedHtml = playerResp.embedUrl ?: continue

            val videoUrl = if (embedHtml.startsWith("http://") || embedHtml.startsWith("https://")) {
                embedHtml
            } else {
                Regex("""src=["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            }
            if (videoUrl.isNullOrBlank()) continue

            val embedLabel = when {
                videoUrl.contains("callistanise.com") || videoUrl.contains("minochinos.com") -> "VidHide"
                videoUrl.contains("cubeembed") -> "CubeEmbed"
                videoUrl.contains("turbovid") -> "TurboVid"
                videoUrl.contains("waaw") -> "WaaW"
                videoUrl.contains("bysedikamoum.com") || videoUrl.contains("4meplayer.pro") -> "Byse"
                videoUrl.contains("hglink.to") || videoUrl.contains("hgglink") -> "HgLink"
                videoUrl.contains("voe.sx") || videoUrl.contains("voe.") -> "VOE"
                videoUrl.contains("ok.ru") -> "Okru"
                else -> "Server$nume"
            }
            Log.d("Danimados", "loadLinks $embedLabel: $videoUrl")

            val customHandled = try {
                when {
                    videoUrl.contains("bysedikamoum.com") || videoUrl.contains("4meplayer.pro") -> {
                        Log.d("Danimados", "loadLinks Byse -> flujo HTTP")
                        tryByseHttpDanimados(videoUrl, data, subtitleCallback, callback)
                    }

                    videoUrl.contains("callistanise.com") || videoUrl.contains("minochinos.com") ||
                        videoUrl.contains("vidhidepro") || videoUrl.contains("vidhide") -> {
                        Log.d("Danimados", "loadLinks VidHide -> unpack links{hls}")
                        tryVidHideProExtractDanimados(videoUrl, data, subtitleCallback, callback)
                    }

                    videoUrl.contains("voe.") -> {
                        Log.d("Danimados", "loadLinks VOE -> solver + mirrors")
                        tryVoeExtractDanimados(videoUrl, data, subtitleCallback, callback)
                    }

                    videoUrl.contains("hglink.to") || videoUrl.contains("hgglink") ||
                        videoUrl.contains("streamwish") || videoUrl.contains("swdyu.com") ||
                        videoUrl.contains("cybervynx.com") || videoUrl.contains("dumbalag.com") ||
                        videoUrl.contains("awish.pro") -> {
                        Log.d("Danimados", "loadLinks StreamWish -> parse + WebView")
                        tryStreamwishDanimados(videoUrl, data, subtitleCallback, callback)
                    }

                    videoUrl.contains("ok.ru") -> {
                        Log.d("Danimados", "loadLinks Okru -> extractor propio")
                        tryOkruDanimados(videoUrl, data, callback)
                    }

                    else -> false
                }
            } catch (e: Exception) {
                Log.e("Danimados", "loadLinks $embedLabel custom fallo: ${e.message}")
                false
            }
            if (customHandled) {
                anySuccess = true
                continue
            }

            val extracted = try {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("Danimados", "loadLinks $embedLabel extractor fallo: ${e.message}")
                false
            }
            if (extracted) anySuccess = true


            if (challengeHosts.any { videoUrl.contains(it) }) continue

            try {
                if (videoUrl.contains("cubeembed.rpmvid.com") || videoUrl.contains("cubeembed.")) {
                    val hash = videoUrl.substringAfterLast("#").substringAfter("/")
                    val baseUrl = "https://cubeembed.rpmvid.com"
                    val cubeHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
                    val encrypted = try {
                        app.get("$baseUrl/api/v1/video?id=$hash", headers = cubeHeaders).text.trim()
                    } catch (_: Exception) { null }
                    if (!encrypted.isNullOrEmpty()) {
                        val key = "kiemtienmua911ca"
                        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")
                        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
                            try { decryptAesCbc(encrypted, key, iv) }
                            catch (_: Exception) { null }
                        }
                        if (decryptedText != null) {
                            val cubeLinkName = "Danimados ($embedLabel)"
                            val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)
                            subtitleSection?.let { section ->
                                Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"").findAll(section).forEach { match ->
                                    val lang = match.groupValues[1]
                                    val rawPath = match.groupValues[2].split("#")[0]
                                    if (rawPath.isNotEmpty()) {
                                        val path = rawPath.replace("\\/", "/")
                                        val subUrl = "$baseUrl$path"
                                        subtitleCallback(newSubtitleFile(lang, subUrl))
                                    }
                                }
                            }
                            val source = Regex("\"source\":\"(.*?)\"").find(decryptedText)
                                ?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                            if (source.isNotEmpty()) {
                                val sourceHttp = source.replaceFirst("https://", "http://")
                                callback.invoke(ExtractorLink(
                                    source = "Danimados", name = cubeLinkName,
                                    url = sourceHttp,
                                    type = ExtractorLinkType.M3U8, quality = 720,
                                    referer = data,
                                ))
                                anySuccess = true
                            }
                            val hlsTiktok = Regex("\"hlsVideoTiktok\":\"(.*?)\"").find(decryptedText)
                                ?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                            if (hlsTiktok.isNotEmpty()) {
                                callback.invoke(ExtractorLink(
                                    source = "Danimados", name = cubeLinkName,
                                    url = "$baseUrl$hlsTiktok",
                                    type = ExtractorLinkType.M3U8, quality = 720,
                                    referer = data,
                                ))
                                anySuccess = true
                            }
                        }
                    }
                    continue
                }

                val embedResp = app.get(videoUrl, headers = browserHeaders + mapOf(
                    "Referer" to data,
                    "Accept" to "*/*",
                ))

                val unpacked = unpackPackedJs(embedResp.text)
                val allVideoCandidates = mutableListOf<String>()
                if (unpacked != null) {
                    allVideoCandidates.addAll(
                        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(unpacked)
                            .map { it.value }.distinct()
                    )

                    val fileUrls = Regex(""""file"\s*:\s*"(https?://[^"]+)""").findAll(unpacked)
                        .map { it.groupValues[1] }.filter { !it.contains(".m3u8") && !it.contains(".mp4") }.toList()
                    allVideoCandidates.addAll(fileUrls)

                    val urlsetUrls = Regex("""urlset\s*['=]\s*'(https?://[^']+)'""").findAll(unpacked)
                        .map { it.groupValues[1] }.distinct().toList()
                    for (urlsetUrl in urlsetUrls) {
                        try {
                            val resolvedResp = app.get(urlsetUrl,
                                headers = browserHeaders + mapOf("Referer" to data))
                            val bodyText = resolvedResp.text
                            val m3u8FromRedirect = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(bodyText)?.value
                            if (m3u8FromRedirect != null) {
                                allVideoCandidates.add(0, m3u8FromRedirect)
                            }
                            if (bodyText.trimStart().startsWith("#EXTM3U")) {
                                allVideoCandidates.add(0, urlsetUrl)
                            }
                        } catch (_: Exception) { }
                    }
                }

                val directSrc = Regex("""(?:src|file|source|url|link)\s*[=:]\s*["']([^"']+\.(?:m3u8|mp4))["']""",
                    RegexOption.IGNORE_CASE).find(embedResp.text)?.groupValues?.get(1)
                if (directSrc != null && directSrc !in allVideoCandidates) {
                    allVideoCandidates.add(0, directSrc)
                }

                val linkLabel = "Danimados ($embedLabel)"
                for (candidate in allVideoCandidates.distinct()) {

                    val embedHost = runCatching { java.net.URL(videoUrl).host }.getOrNull()
                    val fullUrl = when {
                        candidate.startsWith("http") -> candidate
                        candidate.startsWith("//") -> "https:$candidate"
                        candidate.startsWith("/") && embedHost != null -> "https://$embedHost$candidate"
                        else -> "https://$candidate"
                    }

                    var probeType: ExtractorLinkType? = null
                    try {
                        val headCall = app.get(fullUrl, headers = browserHeaders + mapOf("Referer" to data))
                        val headBuf = ByteArray(100)
                        val headRead = headCall.body.byteStream().use { s -> s.read(headBuf) }
                        val headStr = if (headRead > 0) String(headBuf, 0, headRead) else ""
                        probeType = when {
                            headStr.trimStart().startsWith("#EXTM3U") -> ExtractorLinkType.M3U8
                            headStr.contains("ftyp") -> ExtractorLinkType.VIDEO
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.w("Danimados", "loadLinks $embedLabel validación falló (${e.message ?: "error"}), emitiendo fallback")
                    }
                    val validated = probeType != null

                    val linkType = probeType ?: when {
                        fullUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        fullUrl.contains(".mp4") -> ExtractorLinkType.VIDEO
                        else -> null
                    }
                    if (linkType == null) continue

                    callback.invoke(ExtractorLink(
                        source = "Danimados",
                        name = linkLabel + if (validated) "" else " ⚠",
                        url = fullUrl,
                        type = linkType,
                        quality = if (validated) 720 else Qualities.Unknown.value,
                        referer = data,
                    ))
                    anySuccess = true
                }
                val b64Match = Regex("""(?:src|file|source|url)\s*[=:]\s*["']([A-Za-z0-9+/=]{20,})["']""",
                    RegexOption.IGNORE_CASE).find(embedResp.text)
                if (b64Match != null) {
                    val decoded = try {
                        String(android.util.Base64.decode(b64Match.groupValues[1], android.util.Base64.DEFAULT))
                    } catch (_: Exception) { null }
                    if (decoded?.contains("m3u8") == true || decoded?.contains("mp4") == true) {
                        callback.invoke(ExtractorLink(
                            source = "Danimados",
                            name = linkLabel,
                            url = decoded,
                            type = ExtractorLinkType.M3U8,
                            quality = 720,
                            referer = data,
                        ))
                        anySuccess = true
                    }
                }
            } catch (e: Exception) {
                Log.e("Danimados", "loadLinks $embedLabel fetch directo fallo: ${e.message}")
            }
        }

        Log.d("Danimados", "loadLinks fin: success=$anySuccess (${playerOptions.size} players)")
        return anySuccess
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val seasonContainers = doc.select("#seasons > .se-c")
        if (seasonContainers.isNotEmpty()) {
            for (seasonContainer in seasonContainers) {
                val seasonNum = seasonContainer.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: continue
                val episodeItems = seasonContainer.select(".se-a ul.episodios > li")
                for ((epIdx, li) in episodeItems.withIndex()) {
                    val link = li.selectFirst(".episodiotitle a") ?: continue
                    val epHref = link.attr("abs:href")
                    val epTitle = link.text().trim()
                    val epPoster = li.selectFirst(".imagen img")?.attr("src")?.let { fixUrl(it) }
                    val numText = li.selectFirst(".numerando")?.text()
                    val epNum = Regex("""\d+\s*-\s*(\d+)""").find(numText ?: "")?.groupValues?.get(1)?.toIntOrNull()
                        ?: (epIdx + 1)

                    if (epHref.isNotBlank()) {
                        episodes.add(newEpisode(epHref) {
                            this.name = epTitle.ifEmpty { "Episodio $epNum" }
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                        })
                    }
                }
            }
            return episodes
        }

        val flatEps = doc.select("#seasons .se-a ul.episodios > li")
        for ((ei, li) in flatEps.withIndex()) {
            val link = li.selectFirst(".episodiotitle a") ?: continue
            val epHref = link.attr("abs:href")
            val epTitle = link.text().trim()
            val epPoster = li.selectFirst(".imagen img")?.attr("src")?.let { fixUrl(it) }
            val numText = li.selectFirst(".numerando")?.text()
            val sMatch = Regex("""(\d+)\s*-\s*(\d+)""").find(numText ?: "")
            val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = sMatch?.groupValues?.get(2)?.toIntOrNull() ?: (ei + 1)
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle.ifEmpty { "Episodio $epNum" }
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = epPoster
                })
            }
        }

        return episodes
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = select(".data h3 a[href*='/series/']").first()
            ?: select("h3 a[href*='/series/']").first()
            ?: select(".title").first()
        if (titleEl == null) return null
        val linkEl = if (titleEl.tagName() == "a") titleEl
            else titleEl.selectFirst("a[href*='/series/']") ?: titleEl.closest("a[href*='/series/']")
        val href = linkEl?.attr("abs:href") ?: titleEl.attr("abs:href")
        val title = titleEl.text().trim()
        if (title.isBlank()) return null
        if (!href.contains("/series/")) return null
        val poster = select("img").first()?.attr("src")?.let { fixUrl(it) }
        val year = select(".data span, .year, span.date").first()?.text()?.let { extractYear(it) }
        val rating = select(".rating").first()?.text()?.toDoubleOrNull()

        return newMovieSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = poster
            this.year = year
            this.score = rating?.let { Score.from10(it) }
        }
    }

    private fun extractYear(text: String): Int? {
        return Regex("""(19\d{2}|20\d{2})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String?.fixUrl(): String? {
        if (this.isNullOrBlank()) return null
        return if (startsWith("//")) "https:$this" else this
    }

    private fun fixRelativeUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "${BASE_URL}${if (url.startsWith("/")) "" else "/"}$url"
    }

    data class PlayerResponse(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
