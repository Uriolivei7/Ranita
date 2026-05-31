package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class GnulaProvider : MainAPI() {
    override var mainUrl = "https://gnula.life"
    override var name = "GNULA"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    private val TAG = "GNULA"

    private fun getNextData(res: String): PageProps? {
        return try {
            val marker = "id=\"__NEXT_DATA__\" type=\"application/json\">"
            if (!res.contains(marker)) {
                Log.w(TAG, "getNextData: No se encontró el marcador __NEXT_DATA__ en la página")
                return null
            }
            val jsonStr = res.substringAfter(marker).substringBefore("</script>")
            Log.d(TAG, "getNextData: JSON extraído (${jsonStr.length} chars)")
            val model = parseJson<PopularModel>(jsonStr)
            val props = model.pageProps ?: model.props?.pageProps
            if (props == null) {
                Log.w(TAG, "getNextData: pageProps es null en el JSON")
                Log.d(TAG, "getNextData: JSON preview (300 chars): ${jsonStr.take(300)}")
            } else {
                val hasPost = props.post != null
                val hasData = props.data != null
                val hasResults = props.results != null
                val hasEpisode = props.episode != null
                Log.d(TAG, "getNextData: pageProps encontrado — post=$hasPost data=$hasData results=$hasResults episode=$hasEpisode")
                if (!hasPost && !hasData && !hasResults && !hasEpisode) {
                    Log.d(TAG, "getNextData: JSON preview (500 chars): ${jsonStr.take(500)}")
                }
            }
            props
        } catch (e: Exception) {
            Log.w(TAG, "getNextData: Error al parsear JSON -> ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val catalogs = listOf(
            Pair("$mainUrl/archives/series/page/$page", "Series"),
            Pair("$mainUrl/archives/series/releases/page/$page", "Series: Estrenos"),
            Pair("$mainUrl/archives/movies/page/$page", "Películas"),
            Pair("$mainUrl/archives/movies/releases/page/$page", "Películas: Estrenos")
        )
        Log.d(TAG, "getMainPage: page=$page, catalogs=${catalogs.size}")

        for ((url, title) in catalogs) {
            try {
                val isSeriesSection = url.contains("/series/", ignoreCase = true)
                val sectionPrefix = if (isSeriesSection) "series" else "movies"
                Log.d(TAG, "getMainPage: Fetching '$title' -> $url")
                val res = app.get(url).text
                Log.d(TAG, "getMainPage: '$title' respuesta ${res.length} chars")
                val pProps = getNextData(res)
                val results = pProps?.results?.data?.mapNotNull { item ->
                    val slugName = item.slug.name ?: item.url.slug?.substringAfterLast("/") ?: return@mapNotNull null
                    val finalUrl = "$mainUrl/$sectionPrefix/$slugName"
                    val tvType = if (isSeriesSection) TvType.TvSeries else TvType.Movie
                    val itemResult = newMovieSearchResponse(item.titles.name ?: "", finalUrl, tvType) {
                        this.posterUrl = fixImageUrl(item.images.poster)
                        this.year = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                    }
                    Log.d(TAG, "getMainPage: Item '${item.titles.name}' -> $finalUrl (type=$tvType, slugName=$slugName)")
                    itemResult
                } ?: emptyList()
                Log.d(TAG, "getMainPage: '$title' produjo ${results.size} resultados")
                if (results.isNotEmpty()) items.add(HomePageList(title, results))
            } catch (e: Exception) { Log.e(TAG, "MainPage Error: ${e.message}") }
        }
        Log.d(TAG, "getMainPage: Total categorías=${items.size}")
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: Iniciando búsqueda -> '$query'")
        return try {
            val url = "$mainUrl/search?q=${query.trim().replace(" ", "+")}"
            Log.d(TAG, "search: URL -> $url")
            val res = app.get(url).text
            Log.d(TAG, "search: Respuesta ${res.length} chars")
            val pProps = getNextData(res)
            if (pProps?.results?.data == null) {
                Log.w(TAG, "search: results.data es null en la respuesta")
            }
            val results = pProps?.results?.data?.mapNotNull { item ->
                val urlSlug = item.url.slug
                val nameSlug = item.slug.name
                Log.d(TAG, "search: raw — url.slug='$urlSlug' slug.name='$nameSlug' releaseDate=${item.releaseDate}")
                val slugName = nameSlug ?: urlSlug?.substringAfterLast("/")
                if (slugName == null) {
                    Log.w(TAG, "search: Item sin slug, saltando")
                    return@mapNotNull null
                }
                val typePath = when {
                    urlSlug?.startsWith("series/") == true -> "series"
                    urlSlug?.startsWith("movies/") == true -> "movies"
                    else -> null
                }
                val finalUrl = if (typePath != null) "$mainUrl/$typePath/$slugName"
                else "$mainUrl/$slugName"
                val tvType = when {
                    typePath == "series" -> TvType.TvSeries
                    else -> TvType.Movie
                }
                Log.d(TAG, "search: Item '${item.titles.name}' url.slug=$urlSlug slug.name=$nameSlug -> $finalUrl (tvType=$tvType)")
                newMovieSearchResponse(item.titles.name ?: "Sin título", finalUrl, tvType) {
                    this.posterUrl = fixImageUrl(item.images.poster)
                }
            } ?: emptyList()
            Log.d(TAG, "search: Búsqueda devolvió ${results.size} resultados")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: Error fatal -> ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: Iniciando carga -> $url")

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
        val nextJsHeaders = mapOf(
            "accept" to "*/*",
            "x-nextjs-data" to "1",
            "user-agent" to ua,
            "referer" to mainUrl
        )
        val plainHeaders = mapOf("user-agent" to ua, "referer" to mainUrl)

        val isOriginalSeries = url.contains("/series/", ignoreCase = true)
        val originalType = if (isOriginalSeries) "series" else "movies"

        val pathAfterDomain = url.removePrefix(mainUrl).trimStart('/')
        val pathSegments = pathAfterDomain.split("/").filter { it.isNotBlank() }
        val slugRaw = url.trimEnd('/').substringAfterLast("/")
        val numericId = if (pathSegments.size >= 2) pathSegments.getOrNull(1) else null
        val fullSlugPath = if (pathSegments.size >= 2) pathSegments.drop(2).joinToString("/") else slugRaw

        Log.d(TAG, "load: parsed — type=$originalType numericId=$numericId slugRaw=$slugRaw fullSlugPath=$fullSlugPath pathSegments=$pathSegments")

        var pProps: PageProps? = null
        var actualUrl = url
        val triedUrls = mutableSetOf(url)

        Log.d(TAG, "load [E1]: x-nextjs-data header -> $url")
        try {
            val res = app.get(url, headers = nextJsHeaders)
            actualUrl = res.url
            pProps = getNextData(res.text)
            if (pProps?.post != null || pProps?.data != null) Log.d(TAG, "load [E1]: OK")
        } catch (e: Exception) { Log.w(TAG, "load [E1]: Error -> ${e.message}") }

        if (pProps?.post == null && pProps?.data == null) {
            Log.d(TAG, "load [E2]: Sin header especial -> $url")
            triedUrls.add(url)
            try {
                val res = app.get(url, headers = plainHeaders)
                actualUrl = res.url
                pProps = getNextData(res.text)
                if (pProps?.post != null || pProps?.data != null) Log.d(TAG, "load [E2]: OK")
            } catch (e: Exception) { Log.w(TAG, "load [E2]: Error -> ${e.message}") }
        }

        if (pProps?.post == null && pProps?.data == null && numericId != null) {
            val otherType = if (isOriginalSeries) "movies" else "series"
            val trials = mutableListOf<String>()
            val slugPart = if (pathSegments.size >= 3) pathSegments.drop(2).joinToString("/") else slugRaw
            trials.add("$mainUrl/$otherType/$numericId/$slugPart")
            trials.add("$mainUrl/$originalType/$slugPart")
            trials.add("$mainUrl/$otherType/$slugPart")
            if (fullSlugPath != slugRaw) {
                trials.add("$mainUrl/$originalType/$fullSlugPath")
                trials.add("$mainUrl/$otherType/$fullSlugPath")
            }

            for (trial in trials) {
                if (trial in triedUrls) continue
                triedUrls.add(trial)
                try {
                    Log.d(TAG, "load [E3]: path completo -> $trial")
                    val res = app.get(trial, headers = nextJsHeaders)
                    val props = getNextData(res.text)
                    if (props?.post != null || props?.data != null) {
                        pProps = props; actualUrl = res.url
                        Log.d(TAG, "load [E3]: OK en $trial")
                        break
                    }
                } catch (e: Exception) { Log.w(TAG, "load [E3]: Error -> ${e.message}") }
            }
        }

        if (pProps?.post == null && pProps?.data == null) {
            val trials = mutableListOf<String>()
            if (numericId != null) {
                val slugPart = if (pathSegments.size >= 3) pathSegments.drop(2).joinToString("/") else slugRaw
                trials.add("$mainUrl/$originalType/$numericId/$slugPart")
                val otherType = if (isOriginalSeries) "movies" else "series"
                trials.add("$mainUrl/$otherType/$numericId/$slugPart")
            }
            trials.add("$mainUrl/$originalType/$slugRaw")
            if (fullSlugPath != slugRaw) {
                val otherType = if (isOriginalSeries) "movies" else "series"
                trials.add("$mainUrl/$otherType/$fullSlugPath")
            }
            val otherType2 = if (isOriginalSeries) "movies" else "series"
            trials.add("$mainUrl/$otherType2/$slugRaw")

            for (trial in trials) {
                if (trial in triedUrls) continue
                triedUrls.add(trial)
                try {
                    Log.d(TAG, "load [E4]: varias combinaciones -> $trial")
                    val res = app.get(trial, headers = plainHeaders)
                    val props = getNextData(res.text)
                    if (props?.post != null || props?.data != null) {
                        pProps = props; actualUrl = res.url
                        Log.d(TAG, "load [E4]: OK en $trial")
                        break
                    }
                } catch (e: Exception) { Log.w(TAG, "load [E4]: Error -> ${e.message}") }
            }
        }

        if (pProps?.post == null && pProps?.data == null) {
            val trials = mutableListOf(url)
            if (numericId != null) {
                val slugPart = if (pathSegments.size >= 3) pathSegments.drop(2).joinToString("/") else slugRaw
                val otherType = if (isOriginalSeries) "movies" else "series"
                trials.add("$mainUrl/$originalType/$slugPart")
                trials.add("$mainUrl/$otherType/$slugPart")
            }
            trials.add("$mainUrl/$originalType/$slugRaw")
            trials.add("${mainUrl}/${if (isOriginalSeries) "movies" else "series"}/$slugRaw")

            for (trialUrl in trials) {
                if (trialUrl in triedUrls) continue
                triedUrls.add(trialUrl)
                try {
                    Log.d(TAG, "load [E5]: Regex fallback -> $trialUrl")
                    val html = app.get(trialUrl).text
                    val jsonMatch = Regex("""\{\\"titles\\":\{""").find(html)
                    if (jsonMatch != null) {
                        val startIdx = maxOf(0, jsonMatch.range.first - 20)
                        val snippet = html.substring(startIdx, minOf(html.length, startIdx + 2000))
                        Log.d(TAG, "load [E5]: Posible JSON encontrado, snippet: ${snippet.take(300)}")
                    }
                } catch (e: Exception) { Log.w(TAG, "load [E5]: Error -> ${e.message}") }
            }
        }

        val finalProps = pProps ?: throw ErrorLoadingException("No se encontró pProps después de 5 estrategias")
        val post = finalProps.post ?: finalProps.data ?: throw ErrorLoadingException("No se encontró post/data")
        Log.d(TAG, "load: Estrategia exitosa, actualUrl=$actualUrl título='${post.titles.name}'")

        val finalIsSeries = !post.seasons.isNullOrEmpty()
        if (!isOriginalSeries && finalIsSeries) {
            Log.w(TAG, "load: Tipo inconsistente — URL movie pero data tiene seasons. Slug='$slugRaw'")
        }

        val title = post.titles.name ?: "Sin título"
        val year = post.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val mainPoster = fixImageUrl(post.images.poster, "w500")

        val averageScore = post.rate?.average?.toFloat()

        val duration = post.runtime

        val recommendations = mutableListOf<SearchResponse>()
        try {
            val sideMovies = finalProps.context?.contexSidebarTopWeekMovies?.data ?: emptyList()
            val sideSeries = finalProps.context?.contexSidebarTopWeekSeries?.data ?: emptyList()

            (sideMovies + sideSeries).forEach { item ->
                val recSlug = item.slug.name ?: return@forEach
                val recUrl = "$mainUrl/movies/$recSlug"
                recommendations.add(newMovieSearchResponse(item.titles.name ?: "", recUrl, TvType.Movie) {
                    this.posterUrl = item.images.backdrop ?: item.images.poster
                })
            }
            Log.d(TAG, "Recomendados cargados: ${recommendations.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar recomendados: ${e.message}")
        }

        return if (!post.seasons.isNullOrEmpty()) {
            Log.d(TAG, "load: Es serie — ${post.seasons.size} temporadas")
            val episodes = post.seasons.flatMap { season: Season ->
                season.episodes.map { ep: SeasonEpisode ->
                    val sNum = ep.slug.season ?: season.number?.toString() ?: "1"
                    val eNum = ep.slug.episode ?: ep.number?.toString() ?: "1"
                    val epSlug = ep.slug.name ?: slugRaw

                    val cleanName = ep.title?.replace(title, "")
                        ?.replace(Regex("""\d+x\d+"""), "")
                        ?.trim()
                        ?.removePrefix("-")
                        ?.trim()
                        .let { if (it.isNullOrBlank()) "Episodio $eNum" else it }

                    newEpisode("$mainUrl/series/$epSlug/seasons/$sNum/episodes/$eNum") {
                        this.name = cleanName
                        this.season = sNum.toIntOrNull()
                        this.episode = eNum.toIntOrNull()
                        this.posterUrl = ep.image ?: fixImageUrl(ep.images.poster) ?: mainPoster
                    }
                }
            }
            Log.d(TAG, "load: Total ${episodes.size} episodios generados")

            newTvSeriesLoadResponse(title, actualUrl, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = mainPoster
                this.plot = post.overview
                this.year = year
                this.tags = post.genres?.mapNotNull { it.name }
                this.score = Score.from10(averageScore)
                this.recommendations = recommendations
                this.duration = duration
            }
        } else {
            Log.d(TAG, "load: Es película — '$title' ($year)")
            newMovieLoadResponse(title, actualUrl, TvType.Movie, actualUrl) {
                this.posterUrl = mainPoster
                this.plot = post.overview
                this.year = year
                this.tags = post.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.score = Score.from10(averageScore)
                this.duration = duration
            }
        }
    }

    private fun fixImageUrl(url: String?, size: String = "w300"): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url
        else "https://image.tmdb.org/t/p/$size$url"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: Iniciando búsqueda de enlaces en $data")

        return try {
            val res = app.get(data).text
            Log.d(TAG, "loadLinks: Página cargada (${res.length} chars)")

            val pProps = getNextData(res)

            val playersEpisode = pProps?.episode?.players
            val playersPost = pProps?.post?.players
            val playersData = pProps?.data?.players
            Log.d(TAG, "loadLinks: players — episode=$playersEpisode post=$playersPost data=$playersData")

            val players = playersEpisode ?: playersPost ?: playersData
            Log.d(TAG, "loadLinks: players elegido = $players")

            if (players == null) {
                Log.w(TAG, "loadLinks: No se encontraron reproductores (players es null)")
                if (pProps?.post != null) {
                    Log.d(TAG, "loadLinks: post existe pero players es null — post.titles=${pProps.post.titles.name}")
                }
                if (pProps?.data != null) {
                    Log.d(TAG, "loadLinks: data existe pero players es null — data.titles=${pProps.data.titles.name}")
                }
                return false
            }

            val langs = listOf(
                players.latino to "Latino",
                players.spanish to "Castellano",
                players.english to "Subtitulado"
            )

            var found = false
            for ((list, langName) in langs) {
                if (list.isNotEmpty()) {
                    Log.d(TAG, "loadLinks: Procesando ${list.size} enlaces para idioma [$langName]")
                    processLinks(list, langName, data, callback)
                    found = true
                } else {
                    Log.d(TAG, "loadLinks: Lista vacía para idioma [$langName]")
                }
            }

            if (!found) Log.w(TAG, "loadLinks: Todas las listas de idiomas vacías")
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: Error fatal -> ${e.message}")
            false
        }
    }

    private suspend fun processLinks(
        list: List<Region>,
        lang: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "processLinks [$lang]: Procesando ${list.size} regiones")
        list.forEachIndexed { idx, region ->
            try {
                val targetUrl = if (!region.result.isNullOrBlank()) region.result else region.url ?: region.link ?: ""
                if (targetUrl.isBlank()) {
                    Log.w(TAG, "processLinks [$lang][$idx]: targetUrl vacío, saltando")
                    return@forEachIndexed
                }
                Log.d(TAG, "processLinks [$lang][$idx]: targetUrl=$targetUrl")

                val playerPage = app.get(targetUrl, referer = refererUrl).text
                Log.d(TAG, "processLinks [$lang][$idx]: playerPage ${playerPage.length} chars")

                if (playerPage.contains("var url = '")) {
                    val videoUrl = playerPage.substringAfter("var url = '").substringBefore("';")
                    Log.d(TAG, "processLinks [$lang][$idx]: Video URL extraído -> $videoUrl")

                    loadExtractor(videoUrl, refererUrl, subtitleCallback = { }) { link ->
                        ioSafe {
                            Log.d(TAG, "processLinks [$lang][$idx]: Extractor devolvió link source=${link.source} url=${link.url.take(80)}")
                            val finalLink = newExtractorLink(
                                source = link.source,
                                name = "${link.name} [$lang]",
                                url = link.url,
                                type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                            callback.invoke(finalLink)
                        }
                    }
                } else {
                    Log.w(TAG, "processLinks [$lang][$idx]: No se encontró 'var url =' en playerPage (primeros 500 chars): ${playerPage.take(500)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "processLinks [$lang][$idx]: Error -> ${e.message}")
            }
        }
    }

    @Serializable
    data class PopularModel(
        val props: Props? = null,
        val pageProps: PageProps? = null
    )

    @Serializable
    data class Props(val pageProps: PageProps? = null)

    @Serializable data class Results(val data: List<Daum> = emptyList())

    @Serializable data class Daum(
        val titles: Titles = Titles(),
        val images: Images = Images(),
        val slug: Slug = Slug(),
        val url: Url = Url(),
        val releaseDate: String? = null,
        @SerialName("__typename") val typeName: String? = null
    )

    @Serializable data class SeasonPost(
        val titles: Titles = Titles(),
        val images: Images = Images(),
        val overview: String? = null,
        val seasons: List<Season> = emptyList(),
        val players: Players? = null,
        val releaseDate: String? = null,
        val genres: List<Genre>? = null,
        val runtime: Int? = null,
        val rate: Rate? = null
    )

    @Serializable
    data class Rate(
        val average: Double? = null,
        val votes: Int? = null
    )

    @Serializable data class Genre(val name: String? = null)
    @Serializable data class Titles(val name: String? = null)

    @Serializable data class Images(
        val poster: String? = null,
        val backdrop: String? = null
    )

    @Serializable data class Slug(val name: String? = null)
    @Serializable data class Url(val slug: String? = null)
    @Serializable data class Season(val number: Long? = null, val episodes: List<SeasonEpisode> = emptyList())

    @Serializable data class Slug2(val name: String? = null, val season: String? = null, val episode: String? = null)
    @Serializable data class EpisodeData(val players: Players? = null)

    @Serializable data class Players(
        val latino: List<Region> = emptyList(),
        val spanish: List<Region> = emptyList(),
        val english: List<Region> = emptyList()
    )

    @Serializable
    data class Region(
        val result: String = "",
        val url: String? = null,
        val link: String? = null
    )

    @Serializable
    data class PageProps(
        val results: Results? = null,
        val post: SeasonPost? = null,
        val episode: EpisodeData? = null,
        val data: SeasonPost? = null,
        val context: ContextData? = null
    )

    @Serializable
    data class ContextData(
        val contexSidebarTopWeekMovies: SidebarData? = null,
        val contexSidebarTopWeekSeries: SidebarData? = null
    )

    @Serializable
    data class SidebarData(val data: List<Daum> = emptyList())

    @Serializable
    data class SeasonEpisode(
        val title: String? = null,
        val number: Long? = null,
        val slug: Slug2 = Slug2(),
        val images: Images = Images(),
        val image: String? = null,
        val overview: String? = null
    )
}