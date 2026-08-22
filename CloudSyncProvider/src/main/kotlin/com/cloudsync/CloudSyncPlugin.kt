package com.cloudsync

import android.content.Context
import com.cloudsync.provider.CloudSyncProvider
import com.cloudsync.storage.CloudSyncStorage
import com.cloudsync.ui.CloudSyncSettingsDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.*

@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun load(context: Context) {
        CloudSyncStorage.init(context)
        registerMainAPI(CloudSyncProvider())
        openSettings = { ctx ->
            val act = ctx as? androidx.appcompat.app.AppCompatActivity
            act?.let { CloudSyncSettingsDialog(it).show() }
        }
        val creds = CloudSyncStorage.getCreds()
        if (creds != null && creds.isLoggedIn()) {
            scope.launch { CloudSyncProvider().startSync(context) { _, _ -> } }
        }
    }
}
