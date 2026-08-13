package com.example

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType

class SyncProvider(private val plugin: SyncPlugin) : MainAPI() {

    override var name = "CloudStream Sync"
    override var mainUrl = "https://github.com"
    override var lang = "es"
    override var supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? = null
}