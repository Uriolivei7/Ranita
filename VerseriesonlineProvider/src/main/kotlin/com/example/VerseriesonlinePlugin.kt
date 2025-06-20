package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Importa la anotación CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // Importa la clase base Plugin
import com.lagradost.cloudstream3.MainAPI // Necesario para registrar MainAPI

/**
 * Esta es la clase principal de tu plugin para CloudStream.
 * Debe estar anotada con @CloudstreamPlugin.
 * Aquí es donde registrarás todos tus proveedores (MainAPI).
 */
@CloudstreamPlugin
class VerseriesonlinePlugin : Plugin() {
    /**
     * Aquí debes registrar todos tus proveedores (MainAPI).
     *
     * @param context El contexto de la aplicación.
     */
    override fun load(context: Context) {
        // Inicializa cualquier configuración global si es necesario.
        // Por ejemplo, si tu SoloLatinoProvider necesitara una inicialización global:
        // SoloLatinoStorage.init(context.applicationContext) // Si tuvieras una clase de almacenamiento similar a NetflixMirror

        // Registra tu SoloLatinoProvider.
        // Asegúrate de que SoloLatinoProvider no tenga la anotación @CloudstreamPlugin.
        registerMainAPI(VerseriesonlineProvider())

        // Si tuvieras otros proveedores, los registrarías aquí también:
        // registerMainAPI(OtroProveedor())
        // registerMainAPI(TercerProveedor())
    }
}
