package com.example

import android.util.Log

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.Locale

class MoviePlusProvider : MainAPI() {
    override var mainUrl = "https://api.appsxploit.dev"
    override var name = "MoviePlus"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "mx"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "type_2" to "Series",
        "type_4" to "Cartoons",
        "type_3" to "Animes",
        "type_6" to "Doramas",
        "type_5" to "Telenovelas",
        "latest_movies" to "Películas",
        "genre_1" to "Acción",
        "genre_11" to "Terror"
    )

    private fun movieUrl(id: Int) = "$mainUrl/movie/$id"
    private fun seriesUrl(id: Int) = "$mainUrl/serie/$id"

    private fun toSearch(item: MoviePlusCatalog.CatalogItem): SearchResponse {
        val poster = MoviePlusTmdb.poster(item.image)
        return if (item.type == MoviePlusCatalog.TYPE_MOVIE) {
            newMovieSearchResponse(item.name, movieUrl(item.id), TvType.Movie, false) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(item.name, seriesUrl(item.id), TvType.TvSeries, false) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("MoviePlus", "getMainPage page=$page data=${request.data}")
        val (movies, series) = MoviePlusCatalog.loadCatalog()
        Log.d("MoviePlus", "getMainPage catalog movies=${movies.size} series=${series.size}")
        val data = request.data
        val items = when {
            data == "latest_movies" -> movies.sortedByDescending { it.date }
            data == "latest_series" -> series.sortedByDescending { it.date }
            data.startsWith("type_") -> {
                val t = data.removePrefix("type_").toIntOrNull()
                series.filter { t != null && it.type == t }.sortedByDescending { it.date }
            }
            data.startsWith("genre_") -> {
                val gid = data.removePrefix("genre_").toIntOrNull()
                if (gid == null) emptyList()
                else (movies + series).filter { gid in it.genres }.sortedByDescending { it.date }
            }
            else -> emptyList()
        }
        val start = (page - 1) * 40
        val slice = items.drop(start).take(40)
        val avail = MoviePlusWorker.filterAvailable(slice.map { it.id to (it.type == MoviePlusCatalog.TYPE_MOVIE) })
        val shown = slice.filter { it.id in avail }.map { toSearch(it) }
        Log.d("MoviePlus", "getMainPage ${request.name} items=${items.size} slice=${slice.size} avail=${avail.size} shown=${shown.size}")
        return newHomePageResponse(HomePageList(request.name, shown, false), start + 40 < items.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("MoviePlus", "search '$query' start")
        val (movies, series) = MoviePlusCatalog.loadCatalog()
        val q = MoviePlusCatalog.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val matches = (movies + series).asSequence()
            .filter { MoviePlusCatalog.normalize(it.name).contains(q) || MoviePlusCatalog.normalize(it.otherNames).contains(q) }
            .sortedByDescending { it.date }
            .take(60)
            .toList()
        val avail = MoviePlusWorker.filterAvailable(matches.map { it.id to (it.type == MoviePlusCatalog.TYPE_MOVIE) })
        val result = matches.filter { it.id in avail }.map { toSearch(it) }
        Log.d("MoviePlus", "search '$query' -> ${result.size}")
        return result
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("MoviePlus", "load $url")
        val id = url.substringAfterLast('/').substringBefore('?').toIntOrNull() ?: return null
        val isMovie = url.contains("/movie/")
        val item = if (isMovie) MoviePlusCatalog.findMovie(id) else MoviePlusCatalog.findSeries(id)

        return if (isMovie) {
            val tmdb = MoviePlusTmdb.movie(id)
            val title = item?.name ?: tmdb?.title ?: "Movie! Plus $id"
            newMovieLoadResponse(title, movieUrl(id), TvType.Movie, "mp|$id") {
                this.posterUrl = MoviePlusTmdb.poster(item?.image?.ifBlank { null } ?: tmdb?.posterPath)
                this.backgroundPosterUrl = MoviePlusTmdb.backdrop(tmdb?.backdropPath)
                val plot = listOfNotNull(
                    tmdb?.tagline?.takeUnless { it.isNullOrBlank() },
                    tmdb?.overview
                ).joinToString("\n\n").takeIf { it.isNotBlank() }
                this.plot = plot
                this.year = tmdb?.releaseDate?.take(4)?.toIntOrNull() ?: item?.date?.take(4)?.toIntOrNull()
                this.duration = tmdb?.runtime
                this.score = tmdb?.voteAverage?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                this.tags = tmdb?.genres?.mapNotNull { it.name }
                    ?.takeIf { it.isNotEmpty() }
                    ?: item?.genres?.mapNotNull { MoviePlusCatalog.GENRE_NAMES[it] }
                this.actors = tmdb?.credits?.cast?.take(12)?.map { c ->
                    ActorData(
                        Actor(c.name ?: "", MoviePlusTmdb.poster(c.profilePath, "w185")),
                        null,
                        c.character,
                        null
                    )
                }
            }
        } else {
            val tmdb = MoviePlusTmdb.tv(id)
            val title = item?.name ?: tmdb?.name ?: "Movie! Plus $id"
            val episodes = mutableListOf<Episode>()
            val seasons = tmdb?.seasons.orEmpty().filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
            for (s in seasons) {
                val eps = MoviePlusTmdb.season(id, s.seasonNumber)
                for (ep in eps) {
                    episodes.add(newEpisode("mp|$id|${ep.seasonNumber}|${ep.episodeNumber}") {
                        this.name = ep.name
                        this.season = ep.seasonNumber
                        this.episode = ep.episodeNumber
                        this.posterUrl = MoviePlusTmdb.poster(ep.stillPath, "w300")
                        this.description = ep.overview
                        ep.airDate?.let { this.addDate(it) }
                    })
                }
            }
            if (episodes.isEmpty()) {
                val envelopes = MoviePlusWorker.episodeEnvelopes(id)
                val unique = envelopes.distinctBy { it.season to it.episode }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
                for (e in unique) {
                    episodes.add(newEpisode("mp|$id|${e.season}|${e.episode}") {
                        this.name = "Episodio ${e.episode}"
                        this.season = e.season
                        this.episode = e.episode
                    })
                }
            }
            newTvSeriesLoadResponse(title, seriesUrl(id), TvType.TvSeries, episodes) {
                this.posterUrl = MoviePlusTmdb.poster(item?.image?.ifBlank { null } ?: tmdb?.posterPath)
                this.backgroundPosterUrl = MoviePlusTmdb.backdrop(tmdb?.backdropPath)
                this.plot = tmdb?.overview
                this.year = tmdb?.firstAirDate?.take(4)?.toIntOrNull() ?: item?.date?.take(4)?.toIntOrNull()
                this.duration = tmdb?.episodeRunTime?.firstOrNull()
                this.score = tmdb?.voteAverage?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                this.tags = tmdb?.genres?.mapNotNull { it.name }
                    ?.takeIf { it.isNotEmpty() }
                    ?: item?.genres?.mapNotNull { MoviePlusCatalog.GENRE_NAMES[it] }
                this.actors = tmdb?.credits?.cast?.take(12)?.map { c ->
                    ActorData(
                        Actor(c.name ?: "", MoviePlusTmdb.poster(c.profilePath, "w185")),
                        null,
                        c.character,
                        null
                    )
                }
                this.showStatus = when (tmdb?.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended", "Canceled" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
    }

    private fun qualityFromLabel(label: String?): Int {
        val l = (label ?: "").lowercase(Locale.ROOT)
        return when {
            "2160" in l || "4k" in l -> Qualities.P2160.value
            "1080" in l -> Qualities.P1080.value
            "720" in l -> Qualities.P720.value
            "480" in l -> Qualities.P480.value
            "360" in l -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("MoviePlus", "loadLinks data=$data")
        val parts = data.substringAfterLast('/').split("|")
        if (parts.size < 2 || parts[0] != "mp") return false
        val id = parts[1].toIntOrNull() ?: return false
        var found = false

        if (parts.size < 4) {
            val urls = MoviePlusCatalog.findMovie(id)?.urls ?: emptyList()
            MoviePlusWorker.resolveMovie(id, urls) { entry ->
                val label = (entry.quality.takeIf { it.isNotBlank() } ?: "Movie! Plus") +
                    (if (entry.size.isNotBlank()) " · ${entry.size}" else "")
                callback(newExtractorLink(name, "$name $label", entry.url, ExtractorLinkType.VIDEO) {
                    this.quality = qualityFromLabel(entry.quality)
                })
                found = true
            }
        } else {
            val season = parts[2].toIntOrNull() ?: return false
            val episode = parts[3].toIntOrNull() ?: return false
            val envelopes = MoviePlusWorker.episodeEnvelopes(id)
            val eps = envelopes.filter { it.season == season && it.episode == episode }
            val seen = HashSet<String>()
            for (e in eps) {
                val link = MoviePlusWorker.resolveEnvelope(e.envelope) ?: continue
                if (!seen.add(link)) continue
                callback(newExtractorLink(name, "$name ${e.quality}".trim(), link, ExtractorLinkType.VIDEO) {
                    this.quality = qualityFromLabel(e.quality)
                })
                found = true
            }
        }
        Log.d("MoviePlus", "loadLinks FIN found=$found")
        return found
    }
}
