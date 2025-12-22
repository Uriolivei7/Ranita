package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class PopularModel(val props: Props = Props())

@Serializable
data class Props(val pageProps: PageProps = PageProps())

@Serializable
data class PageProps(
    val results: Results = Results(),
    val post: SeasonPost? = null,
    val episode: EpisodeData? = null
)

@Serializable
data class Results(val data: List<Daum> = emptyList())

@Serializable
data class Daum(
    val titles: Titles = Titles(),
    val images: Images = Images(),
    val slug: Slug = Slug(),
    val url: Url = Url()
)

@Serializable data class Titles(val name: String? = null)
@Serializable data class Images(val poster: String? = null)
@Serializable data class Slug(val name: String? = null)
@Serializable data class Url(val slug: String? = null)

@Serializable
data class SeasonModel(val props: SeasonProps = SeasonProps())

@Serializable
data class SeasonProps(val pageProps: PageProps = PageProps())

@Serializable
data class SeasonPost(
    val titles: Titles = Titles(),
    val images: Images = Images(),
    val overview: String? = null,
    val seasons: List<Season> = emptyList(),
    val players: Players? = null
)

@Serializable
data class Season(val number: Long? = null, val episodes: List<SeasonEpisode> = emptyList())

@Serializable
data class SeasonEpisode(
    val title: String? = null,
    val number: Long? = null,
    val slug: Slug2 = Slug2()
)

@Serializable data class Slug2(val name: String? = null, val season: String? = null, val episode: String? = null)

@Serializable
data class EpisodeData(val players: Players? = null)

@Serializable
data class Players(
    val latino: List<Region> = emptyList(),
    val spanish: List<Region> = emptyList(),
    val english: List<Region> = emptyList()
)

@Serializable
data class Region(val result: String = "")


class GnulaProvider : MainAPI() {
    override var mainUrl = "https://gnula.life"
    override var name = "GNULA"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    private val TAG = "GNULA"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage: Cargando página principal $page")
        val items = mutableListOf<HomePageList>()

        val catalogs = listOf(
            Pair("$mainUrl/archives/series/page/$page", "Series"),
            Pair("$mainUrl/archives/series/releases/page/$page", "Series: Estrenos"),
            Pair("$mainUrl/archives/series/top/week/page/$page", "Series: Top Semana"),
            Pair("$mainUrl/archives/series/top/day/page/$page", "Series: Top Hoy"),
            Pair("$mainUrl/archives/movies/page/$page", "Películas"),
            Pair("$mainUrl/archives/movies/releases/page/$page", "Películas: Estrenos"),
            Pair("$mainUrl/archives/movies/top/week/page/$page", "Películas: Top Semana"),
            Pair("$mainUrl/archives/movies/top/day/page/$page", "Películas: Top Hoy"),
        )

        for ((url, title) in catalogs) {
            try {
                Log.d(TAG, "getMainPage: Extrayendo sección $title")
                val res = app.get(url).text
                if (!res.contains("{\"props\":{\"pageProps\":")) {
                    Log.w(TAG, "getMainPage: No se encontró JSON en $url")
                    continue
                }

                val jsonStr = res.substringAfter("{\"props\":{\"pageProps\":").substringBefore("</script>")
                val data = parseJson<PopularModel>("{\"props\":{\"pageProps\":$jsonStr")

                val results = data.props.pageProps.results.data.map {
                    val isMovie = it.url.slug?.contains("movies") == true
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries

                    newMovieSearchResponse(
                        it.titles.name ?: "",
                        "$mainUrl/${if(isMovie) "movies" else "series"}/${it.slug.name}",
                        type
                    ) {
                        this.posterUrl = it.images.poster
                    }
                }

                if (results.isNotEmpty()) {
                    items.add(HomePageList(title, results))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en getMainPage para sección $title: ${e.message}")
            }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: Iniciando búsqueda para -> $query")
        return try {
            val url = "$mainUrl/search?q=$query"
            val res = app.get(url).text

            val jsonStr = res.substringAfter("{\"props\":{\"pageProps\":").substringBefore("</script>")
            val data = parseJson<PopularModel>("{\"props\":{\"pageProps\":$jsonStr")

            data.props.pageProps.results.data.map {
                val isMovie = it.url.slug?.contains("movies") == true
                val type = if (isMovie) TvType.Movie else TvType.TvSeries

                newMovieSearchResponse(
                    it.titles.name ?: "",
                    "$mainUrl/${if(isMovie) "movies" else "series"}/${it.slug.name}",
                    type
                ) {
                    this.posterUrl = it.images.poster
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url).text
        val jsonStr = res.substringAfter("{\"props\":{\"pageProps\":").substringBefore("</script>")
        val data = parseJson<SeasonModel>("{\"props\":{\"pageProps\":$jsonStr")
        val post = data.props.pageProps.post ?: throw ErrorLoadingException("No post data")

        val title = post.titles.name ?: ""
        val isMovie = url.contains("/movies/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = post.images.poster
                this.plot = post.overview
            }
        } else {
            val episodes = post.seasons.flatMap { season ->
                season.episodes.map { ep ->
                    newEpisode("$mainUrl/series/${ep.slug.name}/seasons/${ep.slug.season}/episodes/${ep.slug.episode}") {
                        this.name = ep.title
                        this.season = season.number?.toInt()
                        this.episode = ep.number?.toInt()
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = post.images.poster
                this.plot = post.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).text
        val jsonStr = res.substringAfter("{\"props\":{\"pageProps\":").substringBefore("</script>")

        val players = if (data.contains("/movies/")) {
            parseJson<SeasonModel>("{\"props\":{\"pageProps\":$jsonStr").props.pageProps.post?.players
        } else {
            parseJson<SeasonModel>("{\"props\":{\"pageProps\":$jsonStr").props.pageProps.episode?.players
        }

        players?.let {
            processLinks(it.latino, "Latino", callback)
            processLinks(it.spanish, "Castellano", callback)
            processLinks(it.english, "Subtitulado", callback)
        }
        return true
    }

    private suspend fun processLinks(list: List<Region>, lang: String, callback: (ExtractorLink) -> Unit) {
        list.forEach { region ->
            try {
                Log.d(TAG, "processLinks: Consultando: ${region.result}")
                val playerPage = app.get(region.result).text
                val videoUrl = playerPage.substringAfter("var url = '").substringBefore("';")

                if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                    Log.d(TAG, "processLinks: Video detectado: $videoUrl")

                    loadExtractor(videoUrl, mainUrl, subtitleCallback = { }, callback = { link ->
                        runBlocking {
                            callback.invoke(
                                newExtractorLink(
                                    source = link.source,
                                    name = "${link.name} [$lang]",
                                    url = link.url,
                                    type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = link.referer
                                    this.quality = link.quality
                                }
                            )
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en processLinks ($lang): ${e.message}")
            }
        }
    }
}