package com.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class SyncPlugin : Plugin() {

    var activity: AppCompatActivity? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirtyCategories = mutableSetOf<SyncCategory>()
    private var pollingJob: Job? = null
    private var debounceJob: Job? = null
    private var stopSyncJob: Job? = null
    private var bookmarksObserver: (Boolean) -> Unit = {}
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    @Volatile private var isRestoring = false
    private val pollMs = 10_000L
    private val syncMutex = Mutex()

    @Volatile private var foregroundActivities = 0

    @Volatile var lastStatus = "Sin sincronizar"
    @Volatile var lastError: String? = null
    @Volatile var isSyncing = false
    @Volatile private var lastResumeMs = 0L
    private var lastPushToastMs = 0L
    @Volatile private var pendingPushToast = false

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private fun toastSync(msg: String) {
        Handler(Looper.getMainLooper()).post { showToast(msg) }
    }

    private fun toastPushSync() {
        val now = System.currentTimeMillis()
        if (now - lastPushToastMs < 180_000L) return
        lastPushToastMs = now
        Handler(Looper.getMainLooper()).post { showToast("Cambios guardados") }
    }

    private fun maybePushToast() {
        if (!pendingPushToast) return
        pendingPushToast = false
        toastPushSync()
    }

    companion object {
        private const val TAG = "SyncStream"
    }

    override fun load(context: Context) {
        appContext = context
        SyncStorage.init(context)
        activity = context as? AppCompatActivity
        registerMainAPI(SyncProvider(this))
        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null) SyncSettings(this).show(act)
        }
        registerListeners()
        registerLifecycle()
        startPolling()
        if (SyncStorage.isLoggedIn()) {
            scope.launch { runSync() }
        }
    }

    override fun beforeUnload() {
        pollingJob?.cancel()
        debounceJob?.cancel()
        stopSyncJob?.cancel()
        val hasDirty = synchronized(dirtyCategories) { dirtyCategories.isNotEmpty() }
        if (hasDirty && SyncStorage.isLoggedIn()) {
            try {
                runBlocking { runSync(forceRestore = false, forcePush = true) }
            } catch (_: Exception) {}
        }
        unregisterListeners()
        unregisterLifecycle()
        scope.cancel()
    }

    /***************** listeners *****************/

    private fun registerListeners() {
        val appCtx = appContext ?: return
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (isRestoring || key == null) return@OnSharedPreferenceChangeListener
            if (prefs.contains(key)) {
                SyncBackup.removeTombstone(key)
            } else {
                SyncBackup.recordDeletion(key)
            }
            markDirty(key)
        }
        appCtx.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        bookmarksObserver = { markDirty("0/result_favorites_state_data") }
        MainActivity.bookmarksUpdatedEvent += bookmarksObserver
    }

    private fun unregisterListeners() {
        val appCtx = appContext ?: return
        prefsListener?.let {
            appCtx.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
            appCtx.getSharedPreferences(appCtx.packageName + "_preferences", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        MainActivity.bookmarksUpdatedEvent -= bookmarksObserver
    }

    private fun markDirty(key: String) {
        val cat = SyncBackup.classifyKey(key) ?: return
        synchronized(dirtyCategories) {
            if (dirtyCategories.add(cat)) {
                log("[dirty] $cat")
            }
        }
        if (cat != SyncCategory.RESUME_WATCHING) {
            pendingPushToast = true
            scheduleDebouncedSync()
        }
    }

    private fun scheduleDebouncedSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2_000L)
            if (SyncStorage.isLoggedIn()) {
                withContext(NonCancellable) {
                    try { runSync() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun registerLifecycle() {
        val appCtx = appContext ?: return
        val application = appCtx.applicationContext as? Application ?: return
        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivities++
                lastResumeMs = System.currentTimeMillis()
                stopSyncJob?.cancel()
                if (SyncStorage.isLoggedIn()) {
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(1_500L)
                        try { runSync() } catch (_: Exception) {}
                    }
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityStopped(activity: Activity) {
                if (!SyncStorage.isLoggedIn()) return
                val hasDirty = synchronized(dirtyCategories) { dirtyCategories.isNotEmpty() }
                if (hasDirty) {
                    pendingPushToast = true
                    scope.launch {
                        withContext(NonCancellable) {
                            try { runSync(forceRestore = true, forcePush = true) } catch (_: Exception) {}
                            maybePushToast()
                        }
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun unregisterLifecycle() {
        val appCtx = appContext ?: return
        val application = appCtx.applicationContext as? Application ?: return
        lifecycleCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(pollMs)
                if (SyncStorage.isLoggedIn()) {
                    val inForeground = foregroundActivities > 0
                    try { runSync(forcePush = !inForeground) } catch (_: Exception) {}
                }
            }
        }
    }

    /***************** sync logic *****************/

    suspend fun runSync(forceRestore: Boolean = false, forcePush: Boolean = false) {
        if (!SyncStorage.isLoggedIn()) return
        syncMutex.withLock {
            isSyncing = true
            lastError = null
            lastStatus = "Sincronizando..."
            try {
                runSyncInternal(forceRestore, forcePush)
            } catch (e: Exception) {
                lastStatus = "Error de sync"
                lastError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "runSync", e)
            } finally {
                isSyncing = false
            }
        }
    }

    private suspend fun runSyncInternal(forceRestore: Boolean = false, forcePush: Boolean = false) {
        val backupEnabled = SyncCategory.entries.any { SyncStorage.isBackupEnabled(it) }
        val restoreEnabled = SyncCategory.entries.any { SyncStorage.isRestoreEnabled(it) }
        if (!backupEnabled && !restoreEnabled) {
            lastStatus = "Sin categorías activadas"
            return
        }

        val appCtx = appContext ?: return
        val token = SyncStorage.token ?: return
        val projectNum = SyncStorage.projectNum?.toIntOrNull() ?: return
        val deviceId = SyncStorage.deviceId
            ?: SyncNetwork.getDeviceId(appCtx.packageName, appCtx).also {
                SyncStorage.deviceId = it
            }

        val projectId = SyncStorage.projectId
            ?: SyncNetwork.fetchProjectId(token, projectNum)?.also { SyncStorage.projectId = it }
            ?: run {
                lastStatus = "No se encontró el proyecto $projectNum"
                lastError = SyncNetwork.lastError ?: "Revisa el token y el número de proyecto"
                return
            }

        val devices = SyncNetwork.fetchDevices(token, projectNum)
        if (devices == null) {
            lastStatus = "No se pudo consultar el proyecto"
            lastError = SyncNetwork.lastError
            return
        }
        log("[sync] fetchDevices: ${devices.size} items, ${SyncNetwork.mainDrafts(devices).size} device(s)")

        val ownDevice = SyncNetwork.mainDrafts(devices)
            .filter { it.deviceId == deviceId }
            .maxByOrNull { it.gen ?: it.updatedAt }
        if (ownDevice != null && !SyncStorage.forceReRegister) {
            val ownChunks = devices.filter { it.deviceId == deviceId }
            SyncStorage.ownChunkContentIds = ownChunks
                .filter { it.itemContentId != null }
                .groupBy { it.chunkIndex }
                .mapValues { (_, ds) -> ds.maxByOrNull { it.gen ?: it.updatedAt }!!.itemContentId!! }
            SyncStorage.ownItemId = ownDevice.itemId
            SyncStorage.ownContentId = ownDevice.itemContentId
        } else if (ownDevice == null) {
            SyncStorage.ownItemId = null
            SyncStorage.ownContentId = null
            SyncStorage.ownChunkContentIds = emptyMap()
            SyncStorage.forceReRegister = false
        }

        val enabledBackup = SyncCategory.entries.filter { it != SyncCategory.SEARCH_HISTORY && SyncStorage.isBackupEnabled(it) }.toSet()
        val enabledRestore = SyncCategory.entries.filter { it != SyncCategory.SEARCH_HISTORY && SyncStorage.isRestoreEnabled(it) }.toSet()

        val localBackup = SyncBackup.buildBackup(appCtx, enabledBackup)
        var justRestored = false

        // --- restore from cloud ---
        if (restoreEnabled) {
            val consumed = SyncStorage.lastRestoredFrom
            val allOthers = SyncNetwork.mainDrafts(devices).filter { it.deviceId != deviceId }
            val othersList = allOthers
                .filter { forceRestore || (it.gen ?: it.updatedAt) > (consumed[it.deviceId] ?: 0L) }
                .sortedByDescending { it.gen ?: it.updatedAt }

            if (othersList.isEmpty()) {
                log("[restore] sin cambios pendientes")
            } else {
                isRestoring = true
                val candidates = mutableMapOf<SyncCategory, MutableList<Pair<SyncDevice, BackupFile>>>()
                val consumedNow = mutableMapOf<String, Long>()
                for (other in othersList) {
                    val payload = SyncNetwork.assemblePayload(token, devices, other.deviceId)
                    val cloudBackup = if (payload == null) null else try {
                        SyncNetwork.json.decodeFromString(BackupFile.serializer(), SyncNetwork.decompressData(payload))
                    } catch (_: Exception) { null }
                    if (cloudBackup == null) {
                        log("[restore] ${other.name}: payload incompleto, se reintentará")
                        continue
                    }
                    consumedNow[other.deviceId] = other.gen ?: other.updatedAt
                    for (cat in enabledRestore) {
                        val cloudCat = filterBackup(cloudBackup, cat)
                        if (SyncBackup.isEmpty(cloudCat)) continue
                        candidates.getOrPut(cat) { mutableListOf() }.add(other to cloudCat)
                    }
                }
                if (consumedNow.isNotEmpty()) {
                    val mergedConsumed = HashMap(SyncStorage.lastRestoredFrom)
                    consumedNow.forEach { (k, v) -> if (v > (mergedConsumed[k] ?: 0L)) mergedConsumed[k] = v }
                    SyncStorage.lastRestoredFrom = mergedConsumed
                }
                var restoredAny = false
                var restoredSettings = false
                var restoredExtensions = false
                var restoredBookmarks = false
                var restoredResume = false
                val restoredSources = mutableMapOf<SyncCategory, SyncDevice>()
                try {
                    for (cat in enabledRestore) {
                        val list = candidates[cat] ?: continue
                        val best = list.maxWithOrNull(
                            compareBy<Pair<SyncDevice, BackupFile>> {
                                SyncBackup.getBackupFileKeys(it.second).size
                            }.thenBy { it.first.updatedAt }
                        ) ?: continue
                        val (source, cloudCat) = best
                        val localCat = filterBackup(localBackup, cat)
                        val merged = SyncBackup.mergeBackupFiles(
                            localCat, cloudCat,
                            localCategoryTs = SyncStorage.categoryTimestamp(cat),
                            cloudPayloadTs = source.updatedAt,
                        )
                        if (merged != localCat) {
                            log("[restore] $cat: cambio detectado desde ${source.name}")
                            SyncBackup.restore(appCtx, merged, setOf(cat))
                            restoredAny = true
                            when (cat) {
                                SyncCategory.SETTINGS -> restoredSettings = true
                                SyncCategory.EXTENSIONS -> restoredExtensions = true
                                SyncCategory.BOOKMARKS -> restoredBookmarks = true
                                SyncCategory.RESUME_WATCHING -> restoredResume = true
                                else -> {}
                            }
                            SyncStorage.setCategoryTimestamp(cat, source.updatedAt)
                            restoredSources[cat] = source
                        }
                    }
                } finally {
                    if (!restoredAny) isRestoring = false
                }
                if (restoredAny) {
                    val cats = restoredSources.keys.joinToString { it.key }
                    val src = restoredSources.values.joinToString { it.name }
                    lastStatus = "Restaurado desde $src"
                    log("[restore] OK: $cats desde $src")
                    toastSync("Sincronizado: datos actualizados desde otro dispositivo")
                    justRestored = true
                    val freshBackup = SyncBackup.buildBackup(appCtx, enabledBackup)
                    val freshData = SyncNetwork.json.encodeToString(BackupFile.serializer(), freshBackup)
                    SyncStorage.lastPushedHash = SyncBackup.computeHash(freshData)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (restoredSettings) {
                                MainActivity.reloadHomeEvent(true)
                                MainActivity.reloadAccountEvent(true)
                            } else if (restoredExtensions) {
                                MainActivity.reloadHomeEvent(true)
                            }
                            if (restoredResume) {
                                MainActivity.reloadHomeEvent(true)
                            }
                            if (restoredBookmarks) {
                                MainActivity.reloadLibraryEvent(true)
                            }
                        } finally {
                            isRestoring = false
                            synchronized(dirtyCategories) {
                                for (cat in restoredSources.keys) {
                                    dirtyCategories.remove(cat)
                                }
                            }
                        }
                    }
                } else {
                    log("[restore] sin cambios")
                }
            }
        }

        // --- push to cloud ---
        if (justRestored) {
            lastStatus = "Restaurado (push diferido)"
            log("[push] omitido: se acaba de restaurar, no se sube")
            return
        }
        if (backupEnabled) {
            val toPush = SyncBackup.buildBackup(appCtx, enabledBackup)
            if (SyncBackup.isEmpty(toPush)) {
                lastStatus = "Backup vacío"
                return
            }
            val onlyResumeWatching = synchronized(dirtyCategories) {
                dirtyCategories.isNotEmpty() && dirtyCategories.all { it == SyncCategory.RESUME_WATCHING }
            }
            if (!forcePush && onlyResumeWatching) {
                lastStatus = "Esperando cierre de app para push"
                log("[push] omitido: solo RESUME_WATCHING, esperando background")
                return
            }
            val data = SyncNetwork.json.encodeToString(BackupFile.serializer(), toPush)
            val hash = SyncBackup.computeHash(data)
            val chunks = SyncNetwork.splitChunks(SyncNetwork.compressData(data))
            val ownIds = SyncStorage.ownChunkContentIds

            if (ownIds.isEmpty() || SyncStorage.forceReRegister) {
                val newGen = SyncTime.nowEpochSeconds()
                val ids = SyncNetwork.registerDevice(token, projectId, deviceId, chunks, newGen)
                if (ids != null) {
                    SyncStorage.ownChunkContentIds = ids.mapIndexed { i, id -> i to id }.toMap()
                    SyncStorage.ownContentId = ids.getOrNull(0)
                    SyncStorage.ownItemId = null
                    SyncStorage.syncGen = newGen
                    SyncStorage.lastPushedHash = hash
                    SyncStorage.forceReRegister = false
                    clearDirtyCategories()
                    updateCategoryTimestamps(enabledBackup)
                    lastStatus = "Sync OK (${ids.size} trozo/s)"
                    log("[push] draft creado: ${ids.size} trozo(s)")
                    maybePushToast()
                    SyncNetwork.cleanupStaleDrafts(token, projectId, deviceId, devices, removeAll = true)
                } else {
                    lastStatus = "No se pudo crear draft"
                    lastError = SyncNetwork.lastError
                    log("[push] ERROR: registerDevice: ${lastError}")
                }
            } else if (hash != SyncStorage.lastPushedHash) {
                val gen = SyncTime.nowEpochSeconds()
                val updated = SyncNetwork.updateDevice(token, projectId, deviceId, chunks, ownIds, gen)
                if (updated != null) {
                    SyncStorage.ownChunkContentIds = updated
                    SyncStorage.ownContentId = updated[0]
                    SyncStorage.ownItemId = null
                    SyncStorage.syncGen = gen
                    SyncStorage.lastPushedHash = hash
                    clearDirtyCategories()
                    updateCategoryTimestamps(enabledBackup)
                    lastStatus = "Sync OK (${updated.size} trozo/s)"
                    log("[push] draft actualizado: ${updated.size} trozo(s)")
                    maybePushToast()
                    SyncNetwork.cleanupStaleDrafts(token, projectId, deviceId, devices)
                } else {
                    SyncStorage.ownContentId = null
                    SyncStorage.ownItemId = null
                    SyncStorage.ownChunkContentIds = emptyMap()
                    SyncStorage.forceReRegister = true
                    lastStatus = "Fallo al actualizar draft"
                    lastError = SyncNetwork.lastError
                    log("[push] ERROR: updateDevice: ${lastError}")
                }
            } else {
                lastStatus = "Sin cambios que subir"
                log("[push] omitido: sin cambios")
            }
        }

        if (lastStatus == "Sincronizando...") {
            lastStatus = "Sin cambios"
        }
    }

    fun forceSync(showToastResult: Boolean, onDone: (() -> Unit)? = null) {
        scope.launch {
            runSync(forceRestore = true)
            if (showToastResult) {
                showToast(if (SyncStorage.isLoggedIn()) "Sync completado" else "Configura el plugin primero")
            }
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
        }
    }

    private fun clearDirtyCategories() {
        synchronized(dirtyCategories) {
            dirtyCategories.clear()
        }
    }

    private fun updateCategoryTimestamps(categories: Set<SyncCategory>) {
        val now = SyncTime.nowEpochSeconds()
        for (cat in categories) {
            SyncStorage.setCategoryTimestamp(cat, now)
        }
    }

    private fun filterBackup(backup: BackupFile, cat: SyncCategory): BackupFile {
        fun filterVars(vars: BackupVars): BackupVars = BackupVars(
            bool = vars.bool?.filterKeys { SyncBackup.classifyKey(it) == cat },
            int = vars.int?.filterKeys { SyncBackup.classifyKey(it) == cat },
            string = vars.string?.filterKeys { SyncBackup.classifyKey(it) == cat },
            float = vars.float?.filterKeys { SyncBackup.classifyKey(it) == cat },
            long = vars.long?.filterKeys { SyncBackup.classifyKey(it) == cat },
            stringSet = vars.stringSet?.filterKeys { SyncBackup.classifyKey(it) == cat },
        )
        return BackupFile(
            datastore = filterVars(backup.datastore),
            settings = filterVars(backup.settings),
            deletions = backup.deletions.filterKeys { SyncBackup.classifyKey(it) == cat },
        )
    }

    private fun backupMaps(backup: BackupFile): List<Map<String, *>> =
        listOf(
            backup.datastore.bool,
            backup.datastore.int,
            backup.datastore.string,
            backup.datastore.float,
            backup.datastore.long,
            backup.datastore.stringSet,
            backup.settings.bool,
            backup.settings.int,
            backup.settings.string,
            backup.settings.float,
            backup.settings.long,
            backup.settings.stringSet,
        ).filterNotNull()
}
