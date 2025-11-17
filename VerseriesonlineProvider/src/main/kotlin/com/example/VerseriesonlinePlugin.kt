package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Importa la anotación CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // Importa la clase base Plugin
import com.lagradost.cloudstream3.MainAPI // Necesario para registrar MainAPI

@CloudstreamPlugin
class VerseriesonlinePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerseriesonlineProvider())
    }
}
