package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking

class DoramasflixProvider:MainAPI() {
    companion object  {
        private const val doraflixapi = "https://doraflix.fluxcedene.net/api/gql"
        private val mediaType = "application/json; charset=utf-8".toMediaType()
    }

    override var mainUrl = "https://doramasflix.co"
    override var name = "DoramasFlix"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
    )

    data class MainDoramas (
        @JsonProperty("data" ) var data : DataDoramas? = DataDoramas()
    )
    data class DataDoramas (
        @JsonProperty("listDoramas" ) var listDoramas : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("searchDorama" ) var searchDorama : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("searchMovie"  ) var searchMovie  : ArrayList<ListDoramas>?  = arrayListOf(),
        @JsonProperty("listSeasons" ) var listSeasons : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("detailDorama" ) var detailDorama : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("detailMovie" ) var detailMovie : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("paginationEpisode" ) var paginationEpisode : PaginationEpisode? = PaginationEpisode(),
        @JsonProperty("detailEpisode" ) var detailEpisode : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("carrouselMovies" ) var carrouselMovies : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("paginationDorama" ) var paginationDorama : ListDoramas? = ListDoramas(),
        @JsonProperty("paginationMovie" ) var paginationMovie : ListDoramas? = ListDoramas()
    )

    data class ListDoramas (
        @JsonProperty("_id"         ) var Id         : String?  = null,
        @JsonProperty("name"        ) var name       : String?  = null,
        @JsonProperty("name_es"     ) var nameEs     : String?  = null,
        @JsonProperty("slug"        ) var slug       : String?  = null,
        @JsonProperty("poster_path" ) var posterPath : String?  = null,
        @JsonProperty("isTVShow"    ) var isTVShow   : Boolean? = null,
        @JsonProperty("poster"      ) var poster     : String?  = null,
        @JsonProperty("__typename"  ) var _typename  : String?  = null,
        @JsonProperty("season_number" ) var seasonNumber : Int?    = null,
        @JsonProperty("items"      ) var items     : ArrayList<ListDoramas>? = arrayListOf(),
    )

    data class DetailDoramaandDoramaMeta (
        @JsonProperty("_id"              ) var Id             : String?           = null,
        @JsonProperty("name"             ) var name           : String?           = null,
        @JsonProperty("slug"             ) var slug           : String?           = null,
        @JsonProperty("names"            ) var names          : String?           = null,
        @JsonProperty("name_es"          ) var nameEs         : String?           = null,
        @JsonProperty("overview"         ) var overview       : String?           = null,
        @JsonProperty("languages"        ) var languages      : ArrayList<String>? = arrayListOf(),
        @JsonProperty("poster_path"      ) var posterPath     : String?           = null,
        @JsonProperty("backdrop_path"    ) var backdropPath   : String?           = null,
        @JsonProperty("isTVShow"         ) var isTVShow       : Boolean?          = null,
        @JsonProperty("poster"           ) var poster         : String?           = null,
        @JsonProperty("trailer"          ) var trailer        : String?           = null,
        @JsonProperty("videos"           ) var videos         : ArrayList<String>? = arrayListOf(),
        @JsonProperty("backdrop"         ) var backdrop       : String?           = null,
        @JsonProperty("genres"           ) var genres         : ArrayList<GenresAndLabels>? = arrayListOf(),
        @JsonProperty("labels"           ) var labels         : ArrayList<GenresAndLabels>? = arrayListOf(),
        @JsonProperty("__typename"       ) var _typename      : String?           = null,
        @JsonProperty("links_online"  ) var linksOnline  : ArrayList<LinksOnline>? = arrayListOf(),
        @JsonProperty("still_path"     ) var stillPath     : String? = null,
        @JsonProperty("episode_number" ) var episodeNumber : Int?    = null,
        @JsonProperty("season_number"  ) var seasonNumber  : Int?    = null,
        @JsonProperty("air_date"       ) var airDate       : String? = null,
        @JsonProperty("serie_id"       ) var serieId       : String? = null,
        @JsonProperty("season_poster"  ) var seasonPoster  : String? = null,
        @JsonProperty("serie_poster"   ) var seriePoster   : String? = null,
        @JsonProperty("views") var views: String? = null,
        @JsonProperty("quality") var quality: String? = null,
        @JsonProperty("country") var country: String? = null,
        @JsonProperty("content_rating") var contentRating: String? = null,
        @JsonProperty("rating") var rating: Double? = null,
        @JsonProperty("status") var status: String? = null,
        @JsonProperty("premiere") var premiere: Boolean? = null,
        @JsonProperty("first_air_date") var firstAirDate: String? = null,
        @JsonProperty("release_date") var releaseDate: String? = null,
        @JsonProperty("runtime") var runtime: Int? = null,
        @JsonProperty("episode_run_time") var episodeRunTime: ArrayList<Int>? = arrayListOf(),
    )


    data class LinksOnline (
        @JsonProperty("page"   ) var page   : String? = null,
        @JsonProperty("server" ) var server : String? = null,
        @JsonProperty("link"   ) var link   : String? = null,
        @JsonProperty("lang"   ) var lang   : String? = null
    )

    data class GenresAndLabels (
        @JsonProperty("name"       ) var name      : String? = null,
        @JsonProperty("slug"       ) var slug      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )

    data class DoramasInfo (
        @JsonProperty("id"   ) var id   : String? = null,
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("type" ) var type : String? = null,
        @JsonProperty("isTV" ) var isTV : Boolean? = null
    )

    data class PaginationEpisode (
        @JsonProperty("items"      ) var items     : ArrayList<DetailDoramaandDoramaMeta> = arrayListOf(),
        @JsonProperty("__typename" ) var _typename : String?          = null
    )

    data class DoramasServer(
        @JsonProperty("server") var server: String? = null,
        @JsonProperty("lang") var lang: String? = null,
        @JsonProperty("link") var link: String? = null,
        @JsonProperty("name") var name: String? = null
    )

    private fun getImageUrl(link: String?): String? {
        if (link.isNullOrEmpty()) {
            Log.d("DoramasflixProvider", "getImageUrl: link es null o vacío")
            return null
        }

        return when {
            link.startsWith("http://") || link.startsWith("https://") -> {
                Log.d("DoramasflixProvider", "getImageUrl: URL completa: $link")
                link
            }
            link.startsWith("/") -> {
                val url = "https://image.tmdb.org/t/p/w1280$link"
                Log.d("DoramasflixProvider", "getImageUrl: Path con /: $link -> $url")
                url
            }
            else -> {
                val url = "https://image.tmdb.org/t/p/w1280/$link"
                Log.d("DoramasflixProvider", "getImageUrl: Path sin /: $link -> $url")
                url
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val doramasBody = "{\"operationName\":\"listDoramasMobile\",\"variables\":{\"filter\":{\"isTVShow\":false},\"limit\":32,\"sort\":\"_ID_DESC\"},\"query\":\"query listDoramasMobile(\$limit: Int, \$skip: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  listDoramas(limit: \$limit, skip: \$skip, sort: \$sort, filter: \$filter) {\\n    _id\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    isTVShow\\n    poster\\n    __typename\\n  }\\n}\\n\"}"
        val peliculasBody = "{\"operationName\":\"paginationMovie\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{},\"page\":1},\"query\":\"query paginationMovie(\$page: Int, \$perPage: Int, \$sort: SortFindManyMovieInput, \$filter: FilterFindManyMovieInput) {\\n  paginationMovie(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      names\\n      poster_path\\n      poster\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val variedadesBody = "{\"operationName\":\"paginationDorama\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{\"isTVShow\":true},\"page\":1},\"query\":\"query paginationDorama(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      names\\n      poster_path\\n      backdrop_path\\n      isTVShow\\n      poster\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val doraresponse = app.post(doraflixapi, requestBody = doramasBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val pelisrresponse = app.post(doraflixapi, requestBody = peliculasBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val variedadesresponse = app.post(doraflixapi, requestBody = variedadesBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val listdoramas = doraresponse.data?.listDoramas
        val pelis = pelisrresponse.data?.paginationMovie?.items
        val vari = variedadesresponse.data?.paginationDorama?.items
        val home1 = listdoramas?.map { info ->
            tasa(info)
        }
        val home2 = pelis?.map { info ->
            tasa(info)
        }
        val home3 = vari?.map {info ->
            tasa(info)
        }

        items.add(HomePageList("Doramas", home1!!))
        items.add(HomePageList("Peliculas", home2!!))
        items.add(HomePageList("Más Doramas", home3!!))
        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    private fun tasa(
        info: ListDoramas
    ): SearchResponse {
        val title = info.name
        val slug = info.slug
        val poster = info.posterPath
        val realposter = getImageUrl(poster)
        val id = info.Id
        val typename = info._typename
        val istvShow = info.isTVShow
        val data = "{\"id\":\"$id\",\"slug\":\"$slug\",\"type\":\"$typename\",\"isTV\":$istvShow}"

        return newTvSeriesSearchResponse(title!!, data) {
            this.posterUrl = realposter
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        val bodyjson = "{\"operationName\":\"searchAll\",\"variables\":{\"input\":\"$query\"},\"query\":\"query searchAll(\$input: String!) {\\n  searchDorama(input: \$input, limit: 5) {\\n    _id\\n    slug\\n    name\\n    name_es\\n    poster_path\\n  isTVShow\\n  poster\\n    __typename\\n  }\\n  searchMovie(input: \$input, limit: 5) {\\n    _id\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    poster\\n    __typename\\n  }\\n}\\n\"}"
        val response = app.post(doraflixapi, requestBody = bodyjson.toRequestBody(mediaType)).parsed<MainDoramas>()
        val searchDorama = response.data?.searchDorama
        val searchMovie = response.data?.searchMovie
        if (searchDorama!!.isNotEmpty() || searchMovie!!.isNotEmpty())  {
            searchDorama.map { info->
                search.add(tasa(info))
            }
            searchMovie?.map {info ->
                search.add(tasa(info))
            }
        }
        return search
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixed = url.substringAfter("https://www.comamosramen.com/")
        val parse = parseJson<DoramasInfo>(fixed)
        val type = parse.type
        val tvType = if (type!!.contains("Dorama")) TvType.AsianDrama else TvType.Movie
        val sluginfo = parse.slug
        val isMovie = tvType == TvType.Movie
        val id = parse.id

        val detailMovieBody = """{"operationName":"detailMovieExtra","variables":{"slug":"$sluginfo"},"query":"query detailMovieExtra(${"$"}slug: String!) { detailMovie(filter: {slug: ${"$"}slug}) { name name_es overview languages popularity poster_path poster backdrop_path backdrop links_online runtime release_date genres { name } labels { name } } }"}"""

        val detailDoramaRequestbody = """{"operationName":"detailDorama","variables":{"slug":"$sluginfo"},"query":"query detailDorama(${"$"}slug: String!) { detailDorama(filter: {slug: ${"$"}slug}) { _id name slug names name_es overview languages poster_path backdrop_path first_air_date episode_run_time status premiere isTVShow poster trailer backdrop genres { name } labels { name } } }"}"""

        val metadataRequestBody = if (!isMovie) detailDoramaRequestbody.toRequestBody(mediaType) else detailMovieBody.toRequestBody(mediaType)
        val metadatarequest = app.post(doraflixapi, requestBody = metadataRequestBody).parsed<MainDoramas>()
        val metaInfo = if (isMovie) metadatarequest.data?.detailMovie else metadatarequest.data?.detailDorama

        val title = metaInfo?.name ?: ""
        val plot = metaInfo?.overview

        val dateString = metaInfo?.firstAirDate ?: metaInfo?.releaseDate
        val releaseYear = dateString?.split("-")?.firstOrNull()?.toIntOrNull()
        val duration = if (isMovie) metaInfo?.runtime else metaInfo?.episodeRunTime?.firstOrNull()

        val status = when {
            metaInfo?.status?.lowercase()?.contains("emisión") == true ||
                    metaInfo?.status?.lowercase()?.contains("ongoing") == true -> ShowStatus.Ongoing
            metaInfo?.premiere == true -> ShowStatus.Ongoing
            metaInfo?.firstAirDate != null -> ShowStatus.Completed
            else -> null
        }

        val tags = ArrayList<String>()
        metaInfo?.genres?.forEach { it.name?.let { name -> tags.add(name) } }
        metaInfo?.labels?.forEach { it.name?.let { name -> tags.add(name) } }

        if (!isMovie) {
            if (status == ShowStatus.Ongoing) tags.add("En Emisión")
            else if (status == ShowStatus.Completed) tags.add("Finalizado")
        }

        val poster = getImageUrl(metaInfo?.poster?.takeIf { it.isNotEmpty() } ?: metaInfo?.posterPath)
        val bgposter = getImageUrl(metaInfo?.backdrop?.takeIf { it.isNotEmpty() } ?: metaInfo?.backdropPath)

        val episodes = ArrayList<Episode>()
        var movieData: String? = ""
        val datatwo = "{\"id\":\"${parse.id}\",\"slug\":\"${parse.slug}\",\"type\":\"${parse.type}\",\"isTV\":${parse.isTV}}"

        if (!isMovie) {
            val listSeasonsbody = """{"operationName":"listSeasons","variables":{"serie_id":"$id"},"query":"query listSeasons(${"$"}serie_id: MongoID!) { listSeasons(sort: NUMBER_ASC, filter: {serie_id: ${"$"}serie_id}) { slug season_number poster_path poster backdrop __typename } }"}"""
            val response = app.post(doraflixapi, requestBody = listSeasonsbody.toRequestBody(mediaType)).parsed<MainDoramas>()
            response.data?.listSeasons?.map { season ->
                val paginationepisodesBody = """{"operationName":"listEpisodesPagination","variables":{"serie_id":"$id","season_number":${season.seasonNumber},"page":1},"query":"query listEpisodesPagination(${"$"}page: Int!, ${"$"}serie_id: MongoID!, ${"$"}season_number: Float!) { paginationEpisode(page: ${"$"}page, perPage: 1000, sort: NUMBER_ASC, filter: {type_serie: \"dorama\", serie_id: ${"$"}serie_id, season_number: ${"$"}season_number}) { items { name still_path episode_number season_number slug } } }"}"""
                val episodesReq = app.post(doraflixapi, requestBody = paginationepisodesBody.toRequestBody(mediaType)).parsed<MainDoramas>()
                episodesReq.data?.paginationEpisode?.items?.map { item ->
                    episodes.add(newEpisode(item.slug!!) {
                        this.name = item.name
                        this.season = item.seasonNumber
                        this.episode = item.episodeNumber
                        this.posterUrl = getImageUrl(item.stillPath)
                    })
                }
            }
        } else {
            movieData = metaInfo?.linksOnline?.toJson()
        }

        return if (isMovie) {
            newMovieLoadResponse(title, datatwo, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
                this.plot = plot
                this.tags = tags.distinct()
                this.year = releaseYear
                this.duration = duration
            }
        } else {
            newTvSeriesLoadResponse(title, datatwo, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
                this.plot = plot
                this.tags = tags.distinct()
                this.year = releaseYear
                this.duration = duration
                this.showStatus = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val links = if (data.contains("link")) {
                parseJson<List<LinksOnline>>(data)
            } else {
                val episodeSlug = data.removePrefix("https://doramasflix.co/")
                    .removePrefix("http://doramasflix.co/")
                    .removePrefix(mainUrl + "/")

                val episodeslinkRequestbody = """{"operationName":"GetEpisodeLinks","variables":{"episode_slug":"$episodeSlug"},"query":"query GetEpisodeLinks(${"$"}episode_slug: String!) { detailEpisode(filter: {slug: ${"$"}episode_slug, type_serie: \"dorama\"}) { links_online } }"}"""
                val responseText = app.post(doraflixapi, requestBody = episodeslinkRequestbody.toRequestBody(mediaType)).text
                parseJson<MainDoramas>(responseText).data?.detailEpisode?.linksOnline
            }

            if (links.isNullOrEmpty()) return false

            links.forEach { linkInfo ->
                var link = linkInfo.link
                if (link.isNullOrEmpty()) return@forEach

                link = link.replace("https://swdyu.com", "https://streamwish.to")
                    .replace("https://uqload.to", "https://uqload.co")

                val language = when (linkInfo.lang) {
                    "13109", "coreano", "sub" -> "Subtitulado"
                    "38", "latino" -> "Latino"
                    else -> "Sub"
                }

                val rawServer = linkInfo.server ?: ""
                val serverName = when {
                    link.contains("dood") -> "Doodstream"
                    link.contains("filemoon") -> "Filemoon"
                    link.contains("ok.ru") -> "Ok.ru"
                    link.contains("voe") -> "Voe"
                    link.contains("uqload") -> "Uqload"
                    link.contains("streamwish") -> "Streamwish"
                    link.contains("fplayer") || link.contains("fkplayer") -> "FPlayer"
                    rawServer.matches(Regex("\\d+")) -> "Server"
                    else -> rawServer
                }

                val finalServerName = "$serverName [$language]"

                val success = loadExtractor(link, data, subtitleCallback) { extractorLink ->
                    runBlocking {
                        callback.invoke(
                            newExtractorLink(
                                source = finalServerName,
                                name = finalServerName,
                                url = extractorLink.url,
                                type = extractorLink.type
                            ) {
                                this.referer = extractorLink.referer
                                this.quality = extractorLink.quality
                                this.headers = extractorLink.headers
                                this.extractorData = extractorLink.extractorData
                            }
                        )
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

}