package com.example

import android.util.Log

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

object MoviePlusTmdb {
    private const val API_BASE = "https://api.themoviedb.org/3"
    private const val API_KEY = "ec4ff1b6182572d3e74735e74ca3a8ef"
    private const val LANGUAGE = "es-MX"
    const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbMovie(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbTv(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbGenre(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCastMember>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCastMember(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("character") val character: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("season_number") val seasonNumber: Int = 0,
        @JsonProperty("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null
    )

    private fun url(path: String, extra: String = ""): String =
        "$API_BASE$path?api_key=$API_KEY&language=$LANGUAGE$extra"

    suspend fun movie(id: Int): TmdbMovie? = try {
        parseJson<TmdbMovie>(app.get(url("/movie/$id", "&append_to_response=credits")).text)
    } catch (e: Exception) {
        Log.w("MoviePlusTmdb", "movie $id fallo: ${e.message}")
        null
    }

    suspend fun tv(id: Int): TmdbTv? = try {
        parseJson<TmdbTv>(app.get(url("/tv/$id", "&append_to_response=credits")).text)
    } catch (e: Exception) {
        Log.w("MoviePlusTmdb", "tv $id fallo: ${e.message}")
        null
    }

    suspend fun season(tvId: Int, season: Int): List<TmdbEpisode> = try {
        parseJson<TmdbSeasonDetail>(app.get(url("/tv/$tvId/season/$season")).text)?.episodes ?: emptyList()
    } catch (e: Exception) {
        Log.w("MoviePlusTmdb", "season $tvId/$season fallo: ${e.message}")
        emptyList()
    }

    fun poster(path: String?, size: String = "w342"): String? =
        if (!path.isNullOrBlank()) "$IMAGE_BASE/$size$path" else null

    fun backdrop(path: String?, size: String = "w1280"): String? =
        if (!path.isNullOrBlank()) "$IMAGE_BASE/$size$path" else null
}
