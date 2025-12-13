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
    @JsonProperty("parent_id") val parent_id: Int,
)

data class Stream(
    @JsonProperty("num") val num: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("stream_type") val stream_type: String? = null,
    @JsonProperty("stream_id") val stream_id: Int,
    @JsonProperty("stream_icon") val stream_icon: String? = null,
    @JsonProperty("epg_channel_id") val epg_channel_id: String? = null,
    @JsonProperty("added") val added: String? = null,
    @JsonProperty("is_adult") val is_adult: String? = null,
    @JsonProperty("category_id") val category_id: String,
    @JsonProperty("custom_sid") val custom_sid: String? = null,
    @JsonProperty("tv_archive") val tv_archive: Int,
    @JsonProperty("direct_source") val direct_source: String? = null,
    @JsonProperty("tv_archive_duration") val tv_archive_duration: Int,
)

data class Data(
    val num: Int,
    val name: String,
    val stream_type: String? = null,
    val stream_id: Int,
    val stream_icon: String? = null,
    val epg_channel_id: String? = null,
    val added: String? = null,
    val is_adult: String? = null,
    val category_id: String,
    val custom_sid: String? = null,
    val tv_archive: Int,
    val direct_source: String? = null,
    val tv_archive_duration: Int,
)