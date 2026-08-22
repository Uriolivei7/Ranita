package com.cloudsync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cloudsync.backup.CloudSyncBackup
import com.cloudsync.model.SyncCategory
import com.cloudsync.provider.CloudSyncProvider
import com.cloudsync.storage.CloudSyncStorage
import com.cloudsync.ui.CloudSyncSettingsDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.*

@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var bookmarksObserver: (Boolean) -> Unit = {}
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var pollingJob: Job? = null
    private var debounceJob: Job? = null
    private val dirtyCategories = mutableSetOf<SyncCategory>()
    var isRestoring = false
    companion object { var isRestoringGlobal = false }
    private var foregroundActivities = 0
    private var lastPushToastMs = 0L

    override fun load(context: Context) {
        appContext = context
        CloudSyncStorage.init(context)
        registerMainAPI(CloudSyncProvider())
        openSettings = { ctx ->
            val act = ctx as? androidx.appcompat.app.AppCompatActivity
            act?.let { CloudSyncSettingsDialog(it).show() }
        }
        registerListeners()
        registerLifecycle()
        startPolling()
        val creds = CloudSyncStorage.getCreds()
        if (creds != null && creds.isLoggedIn()) {
            scope.launch { runSync() }
        }
    }

    override fun beforeUnload() {
        pollingJob?.cancel(); debounceJob?.cancel()
        unregisterListeners(); unregisterLifecycle()
        scope.cancel()
    }

    private fun markDirty(key: String) {
        if (isRestoring || isRestoringGlobal) return
        val cat = CloudSyncBackup.classifyKey(key) ?: return
        synchronized(dirtyCategories) { dirtyCategories.add(cat) }
        if (cat != SyncCategory.RESUME_WATCHING) scheduleDebouncedSync()
    }

    private fun scheduleDebouncedSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2000)
            if (CloudSyncStorage.getCreds()?.isLoggedIn() == true) runSync()
        }
    }

    suspend fun runSync() {
        val ctx = appContext ?: return
        val creds = CloudSyncStorage.getCreds() ?: return
        if (!creds.isLoggedIn()) return
        val cb: (Boolean, String?) -> Unit = { success, _ ->
            if (success) {
                val now = System.currentTimeMillis()
                if (now - lastPushToastMs > 180_000) {
                    lastPushToastMs = now
                    Handler(Looper.getMainLooper()).post { showToast("Sincronizado") }
                }
                synchronized(dirtyCategories) { dirtyCategories.clear() }
            }
        }

        CloudSyncProvider().startSync(ctx, cb)

        delay(300)
    }

    private fun registerListeners() {
        val ctx = appContext ?: return
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) markDirty(key)
        }
        try {
            ctx.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener)
            ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Exception) {}
        bookmarksObserver = { markDirty("result_favorites_state_data/0") }
        MainActivity.bookmarksUpdatedEvent += bookmarksObserver
    }

    private fun unregisterListeners() {
        val ctx = appContext ?: return
        prefsListener?.let {
            try {
                ctx.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
                ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            } catch (_: Exception) {}
        }
        MainActivity.bookmarksUpdatedEvent -= bookmarksObserver
    }

    private fun registerLifecycle() {
        val app = appContext?.applicationContext as? Application ?: return
        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                foregroundActivities++
                scope.launch { delay(1500); runSync() }
            }
            override fun onActivityPaused(a: Activity) { foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0) }
            override fun onActivityStopped(a: Activity) {
                if (dirtyCategories.any { it == SyncCategory.RESUME_WATCHING }) {
                    scope.launch { runSync() }
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun unregisterLifecycle() {
        val app = appContext?.applicationContext as? Application ?: return
        lifecycleCallbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (CloudSyncStorage.getCreds()?.isLoggedIn() == true) {
                    try { runSync() } catch (_: Exception) {}
                }
            }
        }
    }
}
