package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PoseidonHDPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PoseidonHDProvider())
    }
}
