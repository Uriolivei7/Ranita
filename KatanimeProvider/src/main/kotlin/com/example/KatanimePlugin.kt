package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class KatanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KatanimeProvider())
    }
}
