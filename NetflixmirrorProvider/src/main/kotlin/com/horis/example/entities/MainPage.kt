package com.horis.example.entities

data class MainPage(
    val post: List<PostCategory>
)

data class HomePageData(
    val post: List<PostCategory>? = null
)
