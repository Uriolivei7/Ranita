package com.example

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ClowniptvPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs: SharedPreferences? = try {
            context.getSharedPreferences("IPTVGlobal", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
        registerMainAPI(ClowniptvProvider(prefs))
    }
}
