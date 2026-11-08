package com.example

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "ElChavo8TVPlugin"

@CloudstreamPlugin
class ElChavo8TVPlugin : Plugin() {
    init {
        Log.d(TAG, "ElChavo8TVPlugin init() llamado")
    }

    override fun load(context: Context) {
        Log.d(TAG, "ElChavo8TVPlugin load() llamado con context: ${context.packageName}")
        ElChavo8TVProvider.pluginContext = context
        registerMainAPI(ElChavo8TVProvider())
        Log.d(TAG, "ElChavo8TVProvider.pluginContext seteado")
    }
}
