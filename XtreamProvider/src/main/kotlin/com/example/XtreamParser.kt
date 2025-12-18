package com.example

import com.fasterxml.jackson.annotation.JsonProperty

data class Link(
    val name: String,
    val mainUrl: String,
    val username: String,
    val password: String,
)

data class Category(
    @JsonProperty("category_id") val category_id: String,
    @JsonProperty("category_name") val category_name: String,
    @JsonProperty("parent_id") val parent_id: Int? = null,
)

data class Stream(
    @JsonProperty("num") val num: Int? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("stream_type") val stream_type: String? = null,
    @JsonProperty("stream_id") val stream_id: Int? = null,
    @JsonProperty("series_id") val series_id: Int? = null,
    @JsonProperty("stream_icon") val stream_icon: String? = null,
    @JsonProperty("epg_channel_id") val epg_channel_id: String? = null,
    @JsonProperty("added") val added: String? = null,
    @JsonProperty("is_adult") val is_adult: String? = null,
    @JsonProperty("category_id") val category_id: String,
    @JsonProperty("custom_sid") val custom_sid: String? = null,
    @JsonProperty("tv_archive") val tv_archive: Int? = null,
    @JsonProperty("direct_source") val direct_source: String? = null,
    @JsonProperty("tv_archive_duration") val tv_archive_duration: Int? = null,
    @JsonProperty("container_extension") val container_extension: String? = null,
    @JsonProperty("cover") val cover: String? = null,
)

data class Data(
    val name: String = "",
    val stream_id: Int? = null,
    val stream_type: String? = null,
    val stream_icon: String? = null,
    val category_id: String? = null,
    val series_id: Int? = null,
    val container_extension: String? = null,
    val num: Int? = null,
    val epg_channel_id: String? = null,
    val added: String? = null,
    val is_adult: String? = null,
    val custom_sid: String? = null,
    val tv_archive: Int? = null,
    val direct_source: String? = null,
    val tv_archive_duration: Int? = null,
)

data class SeriesInfoResponse(
    val info: SeriesGeneralInfo? = null,
    val episodes: Map<String, List<EpisodeItem>>? = null
)

data class SeriesGeneralInfo(
    val plot: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("movie_image") val movie_image: String? = null,
)

data class EpisodeItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("episode_id") val episode_id: Int? = null,
    val title: String? = null,
    val episode_num: String? = null,
    val container_extension: String? = null,
    val info: Any? = null
)

data class EpisodeInfo(
    val movie_image: String? = null,
    val plot: String? = null,
)