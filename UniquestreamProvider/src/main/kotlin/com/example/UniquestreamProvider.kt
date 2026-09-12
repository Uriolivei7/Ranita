package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.util.concurrent.Semaphore

class UniqueStreamProvider : MainAPI() {
    override var mainUrl = "https://anime.uniquestream.net"
    override var name = "AnimeStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val loadTimeoutMs: Long? = 480_000L

    private val apiUrl = "https://anime.uniquestream.net/api/v1"
    private val TAG = "UniqueStream"

    companion object {
        private val apiSemaphore = Semaphore(12)
        private val episodeCache = mutableMapOf<String, List<EpisodeItem>>()
        private val seriesCache = mutableMapOf<String, DetailsResponse>()
        private val movieCache = mutableMapOf<String, MovieDetails>()
        private val movieIds = mutableSetOf<String>()
        private val mainPageCache = mutableMapOf<String, Pair<HomePageList, Long>>()
        var appContext: Context? = null
        private val diskCacheDir: File by lazy {
            File(appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")), "uniquestream_cache")
        }
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val CACHE_VERSION = 2
    }

    private fun seasonCacheFile(seasonId: String): File =
        File(diskCacheDir, "season_${CACHE_VERSION}_$seasonId.json")

    private fun seriesCacheFile(seriesId: String): File =
        File(diskCacheDir, "series_$seriesId.json")

    private fun movieCacheFile(movieId: String): File =
        File(diskCacheDir, "movie_$movieId.json")

    private suspend fun readSeasonCache(seasonId: String): List<EpisodeItem>? {
        try {
            val f = seasonCacheFile(seasonId)
            if (!f.exists()) return null
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
            val parsed = AppUtils.parseJson<List<EpisodeItem>>(f.readText())
            return if (parsed.isNullOrEmpty()) null else parsed
        } catch (e: Exception) {
            Log.w(TAG, "readSeasonCache falló $seasonId: ${e.message}")
            return null
        }
    }

    private suspend fun writeSeasonCache(seasonId: String, eps: List<EpisodeItem>) {
        try {
            val f = seasonCacheFile(seasonId)
            f.parentFile?.mkdirs()
            f.writeText(eps.toJson())
        } catch (e: Exception) {
            Log.w(TAG, "writeSeasonCache falló $seasonId: ${e.message}")
        }
    }

    private suspend fun readSeriesCache(seriesId: String): String? {
        try {
            val f = seriesCacheFile(seriesId)
            if (!f.exists()) return null
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
            return f.readText()
        } catch (e: Exception) {
            Log.w(TAG, "readSeriesCache falló $seriesId: ${e.message}")
            return null
        }
    }

    private suspend fun readCacheFile(f: File): String? {
        try {
            if (!f.exists()) return null
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
            return f.readText()
        } catch (e: Exception) {
            Log.w(TAG, "readCacheFile falló ${f.name}: ${e.message}")
            return null
        }
    }

    private suspend fun writeCacheFile(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "writeCacheFile falló ${f.name}: ${e.message}")
        }
    }

    private suspend fun writeSeriesCache(seriesId: String, text: String) {
        try {
            val f = seriesCacheFile(seriesId)
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "writeSeriesCache falló $seriesId: ${e.message}")
        }
    }

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json"
    )

    private fun SeriesItem.toSearchResponse(): SearchResponse {
        val isMovie = this.type == "movie"
        if (isMovie) movieIds.add(this.content_id)
        val resp = if (isMovie) {
            newAnimeSearchResponse(this.title, this.content_id, TvType.AnimeMovie)
        } else {
            newAnimeSearchResponse(this.title, this.content_id)
        }
        resp.posterUrl = image?.replace("posters/60x90/", "posters/480x720/")
        return resp
    }

    private fun String?.upgradePoster(): String? =
        this?.replace("posters/60x90/", "posters/480x720/")

    private fun localeLabel(locale: String): String = when (locale) {
        "en-US" -> "English (US)"
        "es-419" -> "Español (LATAM)"
        "es-ES" -> "Español (España)"
        "ja-JP" -> "Japonés"
        "pt-BR" -> "Português (BR)"
        "de-DE" -> "Deutsch"
        "fr-FR" -> "Français"
        "it-IT" -> "Italiano"
        "zh-CN" -> "中文"
        "zh-HK" -> "中文 (HK)"
        "ko-KR" -> "한국어"
        "ru-RU" -> "Русский"
        "ar-SA" -> "العربية"
        "id-ID" -> "Indonesia"
        "ms-MY" -> "Melayu"
        "th-TH" -> "ไทย"
        "vi-VN" -> "Tiếng Việt"
        else -> locale
    }

    private val keyRegex = Regex("/([A-Za-z0-9]+)_[^/]+/master\\.m3u8")
    private val nfMasterRegex = Regex("/episode/[^/]+/[^/]+/([^/]+)/master_[^/]+\\.m3u8")
    private val nfKeyRegex = Regex("/episode/[^/]+/[^/]+/([^/]+)/(?:audio|video)/keys/key\\.bin")

    private fun normalizeNfMediaId(segment: String?): String? {
        if (segment == null) return null
        return Regex("^nf_(\\d+)$").find(segment)?.groupValues?.get(1) ?: segment
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv)
            )
            cipher.doFinal(data)
        } catch (e: Exception) {
            Log.w(TAG, "aesCbcDecrypt error: ${e.message}")
            null
        }
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val linkUrl = extractorLink.url
        val mediaIdFromLink = keyRegex.find(linkUrl)?.groupValues?.get(1)
            ?: Regex("/([0-9a-f]{32})_[^/]+/").find(linkUrl)?.groupValues?.get(1)
            ?: normalizeNfMediaId(nfMasterRegex.find(linkUrl)?.groupValues?.get(1))
        val mediaIdFromKeyUrl = Regex("/([A-Za-z0-9]+)_[^/]+/keys/key\\.bin").find(linkUrl)?.groupValues?.get(1)
            ?: normalizeNfMediaId(nfKeyRegex.find(linkUrl)?.groupValues?.get(1))
        var mediaId = mediaIdFromLink ?: mediaIdFromKeyUrl

        val fallbackKey: ByteArray? = try {
            if (mediaId != null && mediaId.length == 32) {
                mediaId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "getVideoInterceptor: media_id inválido $mediaId")
            null
        }

        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()
                val host = request.url.host
                val path = request.url.encodedPath
                val tracePlaylist = path.endsWith(".m3u8")

                return try {
                    if (host == "yte.mediacache.cc" || (host.endsWith("mediacache.cc") && tracePlaylist)) {
                        val response = chain.proceed(request)
                        Log.d(TAG, "CDN ${request.method} $host$path -> ${response.code} ${response.header("Content-Type", "?")}")
                        return response
                    }

                    if (url.contains("keys/") && url.contains("key.bin")) {
                        if (mediaId == null) {
                            mediaId = Regex("/([A-Za-z0-9]+)_[^/]+/keys/key\\.bin").find(url)?.groupValues?.get(1)
                                ?: normalizeNfMediaId(nfKeyRegex.find(url)?.groupValues?.get(1))
                        }
                        if (mediaId != null) {
                            val theMediaId = mediaId!!
                            val realRequest = request.newBuilder()
                                .header("x-am-media-id", theMediaId)
                                .build()
                            val rawBody = try {
                                chain.proceed(realRequest).body?.bytes()
                            } catch (e: Exception) {
                                Log.w(TAG, "key.bin fetch error: ${e.message}")
                                null
                            }

                            val derivedKey: ByteArray? = rawBody?.let { body ->
                                val b64 = String(body).trim()
                                val encrypted = try {
                                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                } catch (e: Exception) {
                                    Log.w(TAG, "key.bin base64 error: ${e.message}")
                                    null
                                }
                                if (encrypted != null) {
                                    val dek = sha256("key$theMediaId".toByteArray()).copyOfRange(0, 16)
                                    val div = sha256("iv$theMediaId".toByteArray()).copyOfRange(0, 16)
                                    aesCbcDecrypt(encrypted, dek, div)
                                } else null
                            }

                            val realKey = derivedKey ?: fallbackKey
                            if (realKey != null) {
                                Log.d(TAG, "Interceptando key.bin -> ${realKey.toHex()} (derived=${derivedKey != null})")
                                return Response.Builder()
                                    .request(request)
                                    .protocol(okhttp3.Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .header("Content-Type", "application/octet-stream")
                                    .body(ResponseBody.create("application/octet-stream".toMediaTypeOrNull(), realKey))
                                    .build()
                            }
                        }
                    }
                    return chain.proceed(request)
                } catch (e: Exception) {
                    if (host.endsWith("mediacache.cc")) {
                        Log.w(TAG, "CDN ${request.method} $host$path falló: ${e.message}")
                    }
                    throw e
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val sections = listOf(
                Triple("Nuevos", "$apiUrl/videos/new?limit=20", false),
                Triple("Populares", "$apiUrl/videos/popular?limit=20", false),
                Triple("Acción", "$apiUrl/browse?categories=action,popular&type=all&limit=20", true),
                Triple("Aventura", "$apiUrl/browse?categories=adventure,popular&type=all&limit=20", true),
                Triple("Comedia", "$apiUrl/browse?categories=comedy,popular&type=all&limit=20", true),
                Triple("Drama", "$apiUrl/browse?categories=drama,popular&type=all&limit=20", true),
                Triple("Fantasía", "$apiUrl/browse?categories=fantasy,popular&type=all&limit=20", true),
                Triple("Sci-Fi", "$apiUrl/browse?categories=sci-fi,popular&type=all&limit=20", true),
                Triple("Películas", "$apiUrl/videos/movies?sort=popular&limit=20", false),
            )

            val now = System.currentTimeMillis()
            val cachedLists = sections.mapNotNull { (name, _, _) ->
                val entry = mainPageCache[name] ?: return@mapNotNull null
                val (list, timestamp) = entry
                if (now - timestamp > 10 * 60 * 1000L) null else list
            }
            if (cachedLists.size == sections.size) {
                return newHomePageResponse(cachedLists, false)
            }

            val homeItems = coroutineScope {
                sections.map { (name, baseUrl, secondPage) ->
                    async {
                        try {
                            val cached = mainPageCache[name]?.let { (list, ts) ->
                                if (now - ts > 10 * 60 * 1000L) null else list
                            }
                            if (cached != null) return@async cached

                            val urls = buildList {
                                add(baseUrl)
                                if (secondPage) add("$baseUrl&page=2")
                            }
                            val allItems = urls.flatMap { url ->
                                getWithRetry(url, attempts = 2, timeout = 30L)?.let {
                                    AppUtils.parseJson<List<SeriesItem>>(it)
                                } ?: emptyList()
                            }
                            val list = allItems
                                .distinctBy { it.content_id }
                                .map { it.toSearchResponse() }
                            val result = if (list.isNotEmpty()) HomePageList(name, list) else null
                            if (result != null) mainPageCache[name] = result to now
                            result
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Sección '$name' falló: ${e.message}")
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }

            newHomePageResponse(homeItems, false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error en getMainPage: ${e.message}")
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(
                "$apiUrl/search?query=$query",
                headers = baseHeaders,
                timeout = 30L
            ).text

            val data = AppUtils.parseJson<SearchRoot>(response)
            val results = mutableListOf<SearchResponse>()
            data.series?.forEach { results.add(it.toSearchResponse()) }
            data.movies?.forEach { results.add(it.toSearchResponse()) }
            results.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "Error en search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanId = url.split("/").lastOrNull { it.isNotBlank() } ?: url

        val isMovie = if (cleanId in movieIds) {
            true
        } else {
            probeContentType(cleanId)
        }

        if (isMovie) return loadMovie(cleanId, url)

        var seriesText: String? = seriesCache[cleanId]?.let { null }
        if (seriesText == null) {
            seriesText = readSeriesCache(cleanId)
        }
        if (seriesText == null) {
            var lastSeriesError: String? = null
            repeat(3) { i ->
                try {
                    val response = app.get("$apiUrl/series/$cleanId", headers = baseHeaders, timeout = 30L)
                    if (response.isSuccessful) {
                        seriesText = response.text
                        writeSeriesCache(cleanId, seriesText!!)
                        return@repeat
                    }
                    lastSeriesError = "HTTP ${response.code}"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastSeriesError = e.message
                }
                if (i < 2) delay(1500L * (i + 1))
            }
            if (seriesText == null) {
                Log.e(TAG, "Serie $cleanId sin detalle (${lastSeriesError ?: "error desconocido"})")
            }
        }
        val details = seriesCache[cleanId] ?: seriesText?.let {
            AppUtils.parseJson<DetailsResponse>(it).also { cached -> seriesCache[cleanId] = cached }
        } ?: throw Exception("No se pudo cargar la serie $cleanId")
        val processedSeasonIds = mutableSetOf<String>()

        val orderedSeasons = (details.seasons ?: emptyList())
            .filter { processedSeasonIds.add(it.content_id) }
            .sortedWith(
                compareBy<SeasonItem> { it.season_seq_number ?: it.season_number }
                    .thenBy { it.season_number }
            )

        val seasonResults = mutableListOf<Pair<Int, List<EpisodeItem>>>()

        coroutineScope {
            val jobs = orderedSeasons.mapIndexed { index, season ->
                async {
                    val displaySeason = index + 1
                    val eps = loadSeasonEpisodes(season)
                    displaySeason to eps
                }
            }
            jobs.forEach { job ->
                seasonResults.add(job.await())
            }
        }

        val episodesList = mutableListOf<Episode>()
        seasonResults.sortedBy { it.first }.forEach { (displaySeason, eps) ->
            eps.forEach { ep ->
                episodesList.add(newEpisode(ep.content_id) {
                    this.name = ep.title
                    this.episode = ep.episode_number?.toInt()
                    this.season = displaySeason
                    this.posterUrl = ep.image
                    ep.duration_ms?.let { this.runTime = (it / 60000).toInt().coerceAtLeast(1) }
                })
            }
        }

        if (episodesList.isEmpty()) {
            Log.w(TAG, "Serie $cleanId sin episodios")
        }

        val audioText = details.audio_locales?.joinToString(", ") { localeLabel(it) }
        val subText = details.subtitle_locales?.joinToString(", ") { localeLabel(it) }
        val fullPlot = buildString {
            append(details.description ?: "")
            if (!audioText.isNullOrBlank() || !subText.isNullOrBlank()) {
                append("\n\n")
                if (!audioText.isNullOrBlank()) append(" -- Audio: $audioText")
                if (!audioText.isNullOrBlank() && !subText.isNullOrBlank()) append("\n")
                if (!subText.isNullOrBlank()) append(" -- Subtítulos: $subText")
            }
        }

        return newTvSeriesLoadResponse(details.title ?: "Sin Título", url, TvType.TvSeries, episodesList) {
            this.posterUrl = details.images?.find { it.type == "poster_tall" }?.url?.upgradePoster()
            this.plot = fullPlot
            this.tags = details.genre?.mapNotNull { it.name } ?: emptyList()
            if (details.rating_avg != null) this.score = Score.from10(details.rating_avg * 2f)
        }
    }

    private suspend fun probeContentType(id: String): Boolean {
        if (id in movieIds) return true
        return try {
            val response = app.get("$apiUrl/content/$id", headers = baseHeaders, timeout = 20L)
            val isMovie = response.isSuccessful &&
                    Regex("\"content_type\"\\s*:\\s*\"movie\"").containsMatchIn(response.text)
            if (isMovie) movieIds.add(id)
            isMovie
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probeContentType error $id: ${e.message}")
            false
        }
    }

    private suspend fun loadMovie(id: String, url: String): LoadResponse {
        var movieText: String? = movieCache[id]?.let { null }
        if (movieText == null) {
            movieText = readCacheFile(movieCacheFile(id))
        }
        if (movieText == null) {
            var lastMovieError: String? = null
            repeat(3) { i ->
                try {
                    val response = app.get("$apiUrl/movie/$id", headers = baseHeaders, timeout = 30L)
                    if (response.isSuccessful) {
                        movieText = response.text
                        writeCacheFile(movieCacheFile(id), movieText!!)
                        return@repeat
                    }
                    lastMovieError = "HTTP ${response.code}"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastMovieError = e.message
                }
                if (i < 2) delay(1500L * (i + 1))
            }
            if (movieText == null) {
                Log.e(TAG, "Película $id sin detalle (${lastMovieError ?: "error desconocido"})")
            }
        }
        val details = movieCache[id] ?: movieText?.let {
            AppUtils.parseJson<MovieDetails>(it).also { cached -> movieCache[id] = cached }
        } ?: throw Exception("No se pudo cargar la película $id")

        val plot = buildString {
            append(details.description ?: "")
            if (!details.studio.isNullOrBlank() || details.year != null) {
                append("\n\n")
                if (details.year != null) append(" -- Año: ${details.year}")
                if (!details.studio.isNullOrBlank() && details.year != null) append("\n")
                if (!details.studio.isNullOrBlank()) append(" -- Estudio: ${details.studio}")
            }
        }

        return newMovieLoadResponse(details.title ?: "Sin Título", url, TvType.AnimeMovie, url) {
            this.posterUrl = details.images?.find { it.type == "poster_tall" }?.url?.upgradePoster()
            this.plot = plot
            this.tags = details.genre?.mapNotNull { it.name } ?: emptyList()
            details.duration_ms?.let { this.duration = (it / 60000).toInt().coerceAtLeast(1) }
            details.year?.let { this.year = it }
            if (details.rating_avg != null) this.score = Score.from10(details.rating_avg * 2f)
        }
    }

    private suspend fun getWithRetry(
        url: String,
        attempts: Int = 3,
        timeout: Long = 45L
    ): String? {
        var lastError: Exception? = null
        var lastStatus = 0
        var lastBodyKind: String? = null
        repeat(attempts) { i ->
            val permit = try {
                apiSemaphore.acquire()
                true
            } catch (e: InterruptedException) {
                lastError = e
                false
            }
            try {
                if (permit) {
                    val response = app.get(url, headers = baseHeaders, timeout = timeout)
                    if (response.isSuccessful) {
                        val text = response.text
                        if (text.trim().startsWith("[")) return text
                        lastBodyKind = "no-JSON"
                    } else {
                        lastStatus = response.code
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                if (permit) apiSemaphore.release()
            }
            if (i < attempts - 1) delay(1500L * (i + 1))
        }
        Log.e(TAG, "getWithRetry falló tras $attempts intentos: $url (status=$lastStatus body=$lastBodyKind error=${lastError?.message})")
        return null
    }

    private suspend fun loadSeasonEpisodes(season: SeasonItem): List<EpisodeItem> {
        val seasonId = season.content_id
        episodeCache[seasonId]?.let { return it }
        readSeasonCache(seasonId)?.let { cached ->
            episodeCache[seasonId] = cached
            return cached
        }

        val episodeCount = season.episode_count ?: 0
        val totalPages = if (episodeCount > 0) {
            (episodeCount + 19) / 20
        } else {
            1
        }

        val pageResults = mutableListOf<List<EpisodeItem>>()

        coroutineScope {
            val jobs = (1..totalPages).map { page ->
                async {
                    val seasonUrl = "$apiUrl/season/$seasonId/episodes?page=$page&limit=20&order_by=asc"
                    val text = getWithRetry(seasonUrl, attempts = 3, timeout = 45L)
                    if (text != null) {
                        AppUtils.parseJson<List<EpisodeItem>>(text)
                    } else {
                        emptyList()
                    }
                }
            }
            jobs.forEach { job ->
                pageResults.add(job.await())
            }
        }

        val allEps = pageResults.flatten().toMutableList()

        if (episodeCount <= 0 && allEps.size >= 20) {
            var extraPage = totalPages + 1
            var keepLoading = true
            while (keepLoading) {
                val seasonUrl = "$apiUrl/season/${season.content_id}/episodes?page=$extraPage&limit=20&order_by=asc"
                val text = getWithRetry(seasonUrl, attempts = 2, timeout = 45L)
                if (text != null) {
                    val eps = AppUtils.parseJson<List<EpisodeItem>>(text)
                    if (eps.isEmpty()) {
                        keepLoading = false
                    } else {
                        allEps.addAll(eps)
                        extraPage++
                        if (eps.size < 20) keepLoading = false
                    }
                } else {
                    keepLoading = false
                }
            }
        }

        val isSpecialFn: (EpisodeItem) -> Boolean = { ep ->
            ep.episode?.startsWith("SP", ignoreCase = true) == true ||
                    Regex("special\\s+\\d", RegexOption.IGNORE_CASE).containsMatchIn(ep.title ?: "")
        }

        val baseEps = allEps
            .distinctBy { it.content_id }
            .filter { it.is_clip != true }

        val hasFractional = baseEps.any { (it.episode_number ?: 0.0) % 1.0 != 0.0 }

        val regulars = baseEps.filterNot(isSpecialFn)
        val specials = baseEps
            .filter(isSpecialFn)
            .sortedBy { it.episode_number ?: 0.0 }

        val maxRegular = regulars.maxOfOrNull { it.episode_number ?: 0.0 } ?: 0.0

        val merged = regulars + specials.mapIndexed { i, s ->
            s.copy(episode_number = maxRegular + i + 1)
        }

        val renumberNeeded = hasFractional || regulars.any { ep ->
            val num = ep.episode_number ?: 0.0
            val label = ep.episode
            num < 1.0 || (label != null && label.isNotEmpty() && label.toDoubleOrNull() == null)
        }

        if (renumberNeeded) {
            val renumbered = merged.mapIndexed { i, ep -> ep.copy(episode_number = (i + 1).toDouble()) }
            episodeCache[seasonId] = renumbered
            writeSeasonCache(seasonId, renumbered)
            return renumbered
        }
        episodeCache[seasonId] = merged
        writeSeasonCache(seasonId, merged)
        return merged
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = if (data.contains("/")) {
            data.substringAfterLast("/").trim()
        } else {
            data.trim()
        }

        Log.d(TAG, "Episode $episodeId")

        val isMovie = episodeId in movieIds || probeContentType(episodeId)

        return try {
            val locales = listOf("es-419", "en-US", "ja-JP")
            var linksEnviados = 0

            for (locale in locales) {
                try {
                    val mediaUrl = if (isMovie) {
                        "$apiUrl/movie/$episodeId/media/dash/$locale"
                    } else {
                        "$apiUrl/episode/$episodeId/media/dash/$locale"
                    }

                    val apiHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "*/*",
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )

                    val response = app.get(mediaUrl, headers = apiHeaders, timeout = 15L)

                    if (response.code == 200) {
                        val videoData = AppUtils.parseJson<VideoResponse>(response.text)

                        val dashVersions = videoData.versions?.dash ?: emptyList()
                        val hlsVersions = mutableListOf<HlsVersion>()
                        videoData.versions?.hls?.let { hlsVersions.addAll(it) }
                        videoData.hls?.let { hlsVersions.add(it) }

                        // DASH
                        if (dashVersions.isNotEmpty()) {
                            dashVersions.forEach { dashVersion ->
                                if (dashVersion.playlist.isNotBlank()) {
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - ${dashVersion.locale.uppercase()}",
                                            url = dashVersion.playlist,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.referer = "$mainUrl/"
                                            this.headers = mapOf(
                                                "Accept" to "*/*",
                                                "Origin" to mainUrl,
                                                "Referer" to "$mainUrl/"
                                            )
                                        }
                                    )
                                    linksEnviados++
                                }
                            }
                        }

                        if (hlsVersions.isNotEmpty()) {
                            val commonHeaders = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Accept" to "*/*",
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/"
                            )
                            for (hlsVersion in hlsVersions) {
                                if (hlsVersion.playlist.isNotBlank()) {
                                    val masterUrl = hlsVersion.playlist
                                    val localeTag = hlsVersion.locale.uppercase()
                                    if (masterUrl.contains("yte.mediacache.cc/yt/")) {
                                        try {
                                            val probe = app.get(masterUrl, headers = commonHeaders, timeout = 15L)
                                            val probeBody = probe.text
                                            val variants = Regex("#EXT-X-STREAM-INF").findAll(probeBody).count()
                                            Log.d(TAG, "YTE probe master -> ${probe.code} len=${probeBody.length} variants=$variants")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "YTE probe master falló: ${e.message}")
                                        }
                                    }
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - 1080p [${localeTag}]",
                                            url = masterUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.referer = "$mainUrl/"
                                            this.headers = commonHeaders
                                        }
                                    )
                                    linksEnviados++

                                    hlsVersion.subtitles?.forEach { sub ->
                                        if (sub.url.isNotBlank()) {
                                            subtitleCallback(newSubtitleFile(sub.language, sub.url))
                                        }
                                    }

                                    hlsVersion.hard_subs?.forEach { hs ->
                                        if (hs.playlist.isNotBlank()) {
                                            val hsUrl = hs.playlist
                                            callback(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "${this.name} - 1080p [${localeTag}] (Subs ${hs.locale.uppercase()})",
                                                    url = hsUrl,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.quality = Qualities.P1080.value
                                                    this.referer = "$mainUrl/"
                                                    this.headers = commonHeaders
                                                }
                                            )
                                            linksEnviados++
                                        }
                                    }
                                }
                            }
                        }

                        if (linksEnviados > 0) break
                    } else {
                        Log.w(TAG, "Episode $episodeId: media HTTP ${response.code} ($locale)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error con locale $locale: ${e.message}")
                    continue
                }
            }

            if (linksEnviados == 0) {
                Log.w(TAG, "Episode $episodeId: sin links")
            }

            linksEnviados > 0

        } catch (e: Exception) {
            Log.e(TAG, "ERROR: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    @Serializable
    data class SeriesItem(
        val content_id: String,
        val title: String,
        val image: String? = null,
        val type: String? = null
    )

    @Serializable
    data class SearchRoot(
        val series: List<SeriesItem>? = null,
        val movies: List<SeriesItem>? = null,
        val episodes: List<EpisodeItem>? = null
    )

    @Serializable
    data class DetailsResponse(
        val content_id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val images: List<ImageItem>? = null,
        val seasons: List<SeasonItem>? = null,
        val audio_locales: List<String>? = null,
        val subtitle_locales: List<String>? = null,
        val genre: List<GenreItem>? = null,
        val rating_avg: Double? = null,
        val rating_count: Int? = null
    )

    @Serializable
    data class MovieDetails(
        val content_id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val images: List<ImageItem>? = null,
        val duration_ms: Long? = null,
        val year: Int? = null,
        val studio: String? = null,
        val genre: List<GenreItem>? = null,
        val is_subbed: Boolean? = null,
        val is_dubbed: Boolean? = null,
        val rating_avg: Double? = null,
        val rating_count: Int? = null
    )

    @Serializable
    data class GenreItem(
        val title: String? = null,
        val name: String? = null
    )

    @Serializable
    data class SeasonItem(
        val content_id: String,
        val season_number: Int,
        val title: String? = null,
        val episode_count: Int? = null,
        val season_seq_number: Int? = null
    )

    @Serializable
    data class EpisodeItem(
        val content_id: String,
        val series_id: String? = null,
        val title: String? = null,
        val episode_number: Double? = null,
        val episode: String? = null,
        val image: String? = null,
        val is_clip: Boolean? = false,
        val duration_ms: Long? = null
    )

    @Serializable
    data class ImageItem(
        val url: String,
        val type: String
    )

    @Serializable
    data class VideoResponse(
        val versions: Versions? = null,
        val hls: HlsVersion? = null
    )

    @Serializable
    data class Versions(
        val hls: List<HlsVersion>? = null,
        val dash: List<DashVersion>? = null
    )

    @Serializable
    data class HlsVersion(
        val locale: String,
        val playlist: String,
        val subtitles: List<SubtitleItem>? = null,
        val hard_subs: List<HardSubItem>? = null
    )

    @Serializable
    data class DashVersion(
        val locale: String,
        val playlist: String,
        val subtitles: List<SubtitleItem>? = null,
        val hard_subs: List<HardSubItem>? = null
    )

    @Serializable
    data class HardSubItem(
        val locale: String,
        val playlist: String
    )

    @Serializable
    data class SubtitleItem(
        val language: String,
        val url: String,
        val mimeType: String? = null
    )
}