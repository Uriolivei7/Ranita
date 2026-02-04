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
            val jsonStr = res.substringAfter("id=\"__NEXT_DATA__\" type=\"application/json\">").substringBefore("</script>")
            val model = parseJson<PopularModel>(jsonStr)
            model.pageProps ?: model.props?.pageProps
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val catalogs = listOf(
            Pair("$mainUrl/archives/series/page/$page", "Series"),
            Pair("$mainUrl/archives/series/releases/page/$page", "Series: Estrenos"),
            Pair("$mainUrl/archives/movies/page/$page", "Películas"),
            Pair("$mainUrl/archives/movies/releases/page/$page", "Películas: Estrenos")
        )

        for ((url, title) in catalogs) {
            try {
                val res = app.get(url).text
                val pProps = getNextData(res)
                val results = pProps?.results?.data?.mapNotNull { item ->
                    val slugPath = item.url.slug ?: item.slug.name ?: return@mapNotNull null
                    val finalUrl = "$mainUrl/${slugPath.removePrefix("/")}"
                    newMovieSearchResponse(item.titles.name ?: "", finalUrl, if (finalUrl.contains("/series/")) TvType.TvSeries else TvType.Movie) {
                        this.posterUrl = fixImageUrl(item.images.poster)
                        this.year = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                    }
                } ?: emptyList()
                if (results.isNotEmpty()) items.add(HomePageList(title, results))
            } catch (e: Exception) { Log.e(TAG, "MainPage Error: ${e.message}") }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search?q=${query.trim().replace(" ", "+")}"
            val res = app.get(url).text
            val pProps = getNextData(res)
            pProps?.results?.data?.mapNotNull { item ->
                val slugPath = item.url.slug ?: item.slug.name ?: return@mapNotNull null
                val finalUrl = "$mainUrl/${slugPath.removePrefix("/")}"
                newMovieSearchResponse(item.titles.name ?: "Sin título", finalUrl, if (finalUrl.contains("/series/")) TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = fixImageUrl(item.images.poster)
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: Iniciando carga -> $url")

        val nextJsHeaders = mapOf(
            "accept" to "*/*",
            "x-nextjs-data" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
            "referer" to mainUrl
        )

        val slugRaw = url.trimEnd('/').substringAfterLast("/")
        var response = app.get(url, headers = nextJsHeaders)
        var pProps = getNextData(response.text)
        var actualUrl = url

        if (pProps?.post == null && pProps?.data == null) {
            val trials = listOf("$mainUrl/series/$slugRaw", "$mainUrl/movies/$slugRaw")
            for (trial in trials) {
                if (trial == url) continue
                val nextRes = app.get(trial, headers = nextJsHeaders)
                val nextProps = getNextData(nextRes.text)
                if (nextProps?.post != null || nextProps?.data != null) {
                    pProps = nextProps
                    actualUrl = trial
                    break
                }
            }
        }

        val finalProps = pProps ?: throw ErrorLoadingException("No se encontró pProps")
        val post = finalProps.post ?: finalProps.data ?: throw ErrorLoadingException("No se encontró post/data")

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
            val episodes = post.seasons.flatMap { season ->
                season.episodes.map { ep ->
                    val sNum = ep.slug.season ?: season.number?.toString() ?: "1"
                    val eNum = ep.slug.episode ?: ep.number?.toString() ?: "1"
                    val epSlug = ep.slug.name ?: slugRaw


                    val cleanName = ep.title?.replace(title, "")?.replace(Regex("""\d+x\d+"""), "")?.trim()
                        ?.removePrefix("-")?.trim()
                        .let { if (it.isNullOrBlank()) "Episodio $eNum" else it }

                    newEpisode("$mainUrl/series/$epSlug/seasons/$sNum/episodes/$eNum") {
                        this.name = cleanName
                        this.season = sNum.toIntOrNull()
                        this.episode = eNum.toIntOrNull()
                        this.posterUrl = ep.image ?: fixImageUrl(ep.images.poster) ?: mainPoster
                    }
                }
            }

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
            val pProps = getNextData(res)

            val players = pProps?.episode?.players ?: pProps?.post?.players ?: pProps?.data?.players

            if (players == null) {
                Log.w(TAG, "loadLinks: No se encontraron reproductores (players es null)")
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
                }
            }

            if (!found) Log.w(TAG, "loadLinks: Listas de idiomas vacías")
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
        list.forEach { region ->
            try {
                val targetUrl = if (!region.result.isNullOrBlank()) region.result else region.url ?: region.link ?: ""
                if (targetUrl.isBlank()) return@forEach

                val playerPage = app.get(targetUrl, referer = refererUrl).text
                if (playerPage.contains("var url = '")) {
                    val videoUrl = playerPage.substringAfter("var url = '").substringBefore("';")

                    Log.d(TAG, "Cargando extractor para: $videoUrl")

                    loadExtractor(videoUrl, refererUrl, subtitleCallback = { }) { link ->
                        ioSafe {
                            val finalLink = newExtractorLink(
                                source = link.source,
                                name = "${link.name} [$lang]",
                                url = link.url,
                                type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                            callback.invoke(finalLink)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en processLinks ($lang): ${e.message}")
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