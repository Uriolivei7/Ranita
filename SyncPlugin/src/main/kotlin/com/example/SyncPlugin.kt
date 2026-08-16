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
    private val pollMs = 20_000L
    private val syncMutex = Mutex()

    // Solo se consulta GitHub mientras hay actividad en primer plano.
    @Volatile private var foregroundActivities = 0

    @Volatile var lastStatus = "Sin sincronizar"
    @Volatile var lastError: String? = null
    @Volatile var isSyncing = false
    @Volatile private var lastResumeMs = 0L

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private fun toastSync(msg: String, onlyIfRecentlyResumed: Boolean = false) {
        if (onlyIfRecentlyResumed && System.currentTimeMillis() - lastResumeMs > 10_000L) return
        showToast(msg)
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
        log("dirty: $key -> ${cat.key}")
        synchronized(dirtyCategories) {
            dirtyCategories.add(cat)
        }
        scheduleDebouncedSync()
    }

    private fun scheduleDebouncedSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2_000L)
            if (SyncStorage.isLoggedIn()) {
                try { runSync() } catch (_: Exception) {}
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
                // Push final al salir del reproductor o cerrar la app, para no perder
                // el último cambio antes de que el proceso muera.
                stopSyncJob?.cancel()
                stopSyncJob = scope.launch {
                    delay(2_000L)
                    try { runSync() } catch (_: Exception) {}
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
                if (SyncStorage.isLoggedIn() && foregroundActivities > 0) {
                    try { runSync() } catch (_: Exception) {}
                }
            }
        }
    }

    /***************** sync logic *****************/

    suspend fun runSync(forceRestore: Boolean = false) {
        if (!SyncStorage.isLoggedIn()) return
        syncMutex.withLock {
            if (isRestoring) return@withLock
            isSyncing = true
            lastError = null
            lastStatus = "Sincronizando..."
            try {
                runSyncInternal(forceRestore)
            } catch (e: Exception) {
                lastStatus = "Error de sync"
                lastError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "runSync", e)
            } finally {
                isSyncing = false
            }
        }
    }

    private suspend fun runSyncInternal(forceRestore: Boolean = false) {
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
        log("proyecto $projectId, ${devices.size} item(s), ${SyncNetwork.mainDrafts(devices).size} dispositivo(s)")

        val ownDevice = SyncNetwork.mainDrafts(devices)
            .filter { it.deviceId == deviceId }
            .maxByOrNull { it.updatedAt }
        if (ownDevice != null && !SyncStorage.forceReRegister) {
            val ownChunks = devices.filter { it.deviceId == deviceId }
            SyncStorage.ownChunkContentIds = ownChunks
                .filter { it.itemContentId != null }
                .groupBy { it.chunkIndex }
                .mapValues { (_, ds) -> ds.maxByOrNull { it.updatedAt }!!.itemContentId!! }
            SyncStorage.ownItemId = ownDevice.itemId
            SyncStorage.ownContentId = ownDevice.itemContentId
            log("draft propio encontrado: ${ownChunks.size} trozo(s)")
        } else if (ownDevice == null) {
            SyncStorage.ownItemId = null
            SyncStorage.ownContentId = null
            SyncStorage.ownChunkContentIds = emptyMap()
            SyncStorage.forceReRegister = false
            lastStatus = "Draft propio no encontrado; se creará uno nuevo"
            log("draft propio NO encontrado -> se registrará uno nuevo")
        }

        val enabledBackup = SyncCategory.entries.filter { SyncStorage.isBackupEnabled(it) }.toSet()
        val enabledRestore = SyncCategory.entries.filter { SyncStorage.isRestoreEnabled(it) }.toSet()

        val localBackup = SyncBackup.buildBackup(appCtx, enabledBackup)

        // --- restore from cloud ---
        if (restoreEnabled) {
            val consumed = SyncStorage.lastRestoredFrom
            val othersList = SyncNetwork.mainDrafts(devices)
                .filter { it.deviceId != deviceId }
                .filter { forceRestore || it.updatedAt > (consumed[it.deviceId] ?: 0L) }
                .sortedByDescending { it.updatedAt }
            log("restore: otros dispositivos = ${othersList.map { "${it.name}@${it.updatedAt}" }}")
            if (othersList.isNotEmpty()) {
                val candidates = mutableMapOf<SyncCategory, MutableList<Pair<SyncDevice, BackupFile>>>()
                val consumedNow = mutableMapOf<String, Long>()
                for (other in othersList) {
                    val payload = SyncNetwork.assemblePayload(token, devices, other.deviceId)
                    val cloudBackup = if (payload == null) null else try {
                        SyncNetwork.json.decodeFromString(
                            BackupFile.serializer(),
                            SyncNetwork.decompressData(payload)
                        )
                    } catch (_: Exception) {
                        null
                    }
                    if (cloudBackup == null) continue
                    consumedNow[other.deviceId] = other.updatedAt
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
                isRestoring = true
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
                        )
                        if (best == null) continue
                        val (source, cloudCat) = best
                        val localCat = filterBackup(localBackup, cat)
                        val merged = SyncBackup.mergeBackupFiles(
                            localCat, cloudCat,
                            localCategoryTs = SyncStorage.categoryTimestamp(cat),
                            cloudPayloadTs = source.updatedAt,
                        )
                        log(
                            "merge $cat: local=${SyncBackup.getBackupFileKeys(localCat).size} " +
                                "cloud=${SyncBackup.getBackupFileKeys(cloudCat).size} " +
                                "localTs=${SyncStorage.categoryTimestamp(cat)} cloudTs=${source.updatedAt} " +
                                "cambio=${merged != localCat}"
                        )
                        if (merged != localCat) {
                            val localMaps = backupMaps(localCat)
                            val cloudMaps = backupMaps(cloudCat)
                            val allKeys = (localMaps.flatMap { it.keys } + cloudMaps.flatMap { it.keys }).toSet()
                            val cloudOnly = mutableListOf<String>()
                            val localOnly = mutableListOf<String>()
                            val different = mutableListOf<String>()
                            for (key in allKeys) {
                                val lv = localMaps.firstNotNullOfOrNull { it[key] }
                                val cv = cloudMaps.firstNotNullOfOrNull { it[key] }
                                when {
                                    cv == null -> localOnly.add(key)
                                    lv == null -> cloudOnly.add(key)
                                    lv != cv -> different.add(key)
                                }
                            }
                            log(
                                "    cambio $cat: cloudOnly=${cloudOnly.size} localOnly=${localOnly.size} " +
                                    "distintos=${different.size}"
                            )
                            log("    cloudOnly: ${cloudOnly.take(3)}")
                            log("    localOnly: ${localOnly.take(3)}")
                            log("    distintos: ${different.take(3)}")
                            for (k in different.take(3)) {
                                val lv = localMaps.firstNotNullOfOrNull { it[k] }
                                val cv = cloudMaps.firstNotNullOfOrNull { it[k] }
                                log(
                                    "      $k localTs=${SyncBackup.debugTs(lv)} " +
                                        "cloudTs=${SyncBackup.debugTs(cv)} diffSecs=${SyncBackup.debugTs(cv) - SyncBackup.debugTs(lv)}"
                                )
                            }
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
                    isRestoring = false
                }
                if (restoredAny) {
                    lastStatus = "Restaurado desde ${restoredSources.values.map { it.name }.joinToString(",")}"
                    log("restaurado: ${restoredSources.map { (cat, src) -> "${cat.key}:${src.name}" }.joinToString(", ")}")
                    toastSync("Sincronizado: datos actualizados desde otro dispositivo")
                    Handler(Looper.getMainLooper()).post {
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
                    }
                }
            }
        }

        // --- push to cloud ---
        if (backupEnabled) {
            val toPush = SyncBackup.buildBackup(appCtx, enabledBackup)
            if (!SyncBackup.isEmpty(toPush)) {
                val data = SyncNetwork.json.encodeToString(BackupFile.serializer(), toPush)
                val hash = SyncBackup.computeHash(data)
                val chunks = SyncNetwork.splitChunks(SyncNetwork.compressData(data))
                log("payload: ${data.length} chars -> ${chunks.size} trozo(s)")
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
                        lastStatus = "Draft(s) creado(s): sync OK (${ids.size} trozo/s)"
                        log("nuevo draft registrado: ${ids.size} trozo(s)")
                        toastSync("Sincronizado: cambios subidos", onlyIfRecentlyResumed = true)
                        SyncNetwork.cleanupStaleDrafts(token, projectId, deviceId, devices, removeAll = true)
                    } else {
                        lastStatus = "No se pudo crear el draft"
                        lastError = SyncNetwork.lastError
                    }
                } else if (hash != SyncStorage.lastPushedHash) {
                    val gen = SyncStorage.syncGen ?: SyncTime.nowEpochSeconds().also { SyncStorage.syncGen = it }
                    val updated = SyncNetwork.updateDevice(token, projectId, deviceId, chunks, ownIds, gen)
                    if (updated != null) {
                        SyncStorage.ownChunkContentIds = updated
                        SyncStorage.ownContentId = updated[0]
                        SyncStorage.ownItemId = null
                        SyncStorage.lastPushedHash = hash
                        clearDirtyCategories()
                        updateCategoryTimestamps(enabledBackup)
                        lastStatus = "Draft(s) actualizado(s): sync OK (${updated.size} trozo/s)"
                        log("draft actualizado: ${updated.size} trozo(s)")
                        toastSync("Sincronizado: cambios subidos", onlyIfRecentlyResumed = true)
                        SyncNetwork.cleanupStaleDrafts(token, projectId, deviceId, devices)
                    } else {
                        SyncStorage.ownContentId = null
                        SyncStorage.ownItemId = null
                        SyncStorage.ownChunkContentIds = emptyMap()
                        SyncStorage.forceReRegister = true
                        lastStatus = "Fallo al actualizar el draft; se reintentará crearlo"
                        lastError = SyncNetwork.lastError
                    }
                } else {
                    lastStatus = "Sin cambios que subir"
                }
            } else {
                lastStatus = "Backup vacío (nada que subir)"
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