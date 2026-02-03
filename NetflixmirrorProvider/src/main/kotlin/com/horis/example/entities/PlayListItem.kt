package com.horis.example.entities

data class PlayListItem(
    val sources: List<Source>,
    val tracks: List<Tracks>?,
    val title: String
)
