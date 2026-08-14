package com.example

import android.content.Context
import android.content.SharedPreferences
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

@CloudstreamPlugin
class SyncPlugin : Plugin() {

    var activity: AppCompatActivity? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirtyCategories = mutableSetOf<SyncCategory>()
    private var pollingJob: Job? = null
    private var debounceJob: Job? = null
    private var bookmarksObserver: (Boolean) -> Unit = {}
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var isRestoring = false
    private val pollMs = 30_000L

    @Volatile var lastStatus = "Sin sincronizar"
    @Volatile var lastError: String? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
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
        startPolling()
        if (SyncStorage.isLoggedIn()) {
            scope.launch { runSync() }
        }
    }

    override fun beforeUnload() {
        pollingJob?.cancel()
        debounceJob?.cancel()
        unregisterListeners()
        scope.cancel()
    }

    /***************** listeners *****************/

    private fun registerListeners() {
        val appCtx = appContext ?: return
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!isRestoring && key != null) {
                markDirty(key)
            }
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
                scheduleDebouncedSync()
            }
        }
    }

    private fun scheduleDebouncedSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2_000L)
            if (SyncStorage.isLoggedIn()) runSync()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(pollMs)
                if (SyncStorage.isLoggedIn()) {
                    try { runSync() } catch (_: Exception) {}
                }
            }
        }
    }

    /***************** sync logic *****************/

    suspend fun runSync() {
        if (isRestoring || !SyncStorage.isLoggedIn()) return
        lastError = null
        lastStatus = "Sincronizando..."
        try {
            runSyncInternal()
        } catch (e: Exception) {
            lastStatus = "Error de sync"
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "runSync", e)
        }
    }

    private suspend fun runSyncInternal() {
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
        log("proyecto $projectId, ${devices.size} dispositivo(s)")

        val ownDevice = SyncNetwork.mainDrafts(devices)
            .filter { it.deviceId == deviceId }
            .maxByOrNull { it.updatedAt }
        if (ownDevice != null && !SyncStorage.forceReRegister) {
            val ownChunks = devices.filter { it.deviceId == deviceId }
            SyncStorage.ownChunkContentIds = ownChunks
                .mapNotNull { c -> if (c.itemContentId == null) null else c.chunkIndex to c.itemContentId }
                .toMap()
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
            val others = SyncNetwork.mainDrafts(devices)
                .filter { it.deviceId != deviceId }
                .maxByOrNull { it.updatedAt }
            if (others != null) {
                val payload = SyncNetwork.assemblePayload(token, devices, others.deviceId)
                val cloudBackup = if (payload == null) null else try {
                    SyncNetwork.json.decodeFromString(BackupFile.serializer(), SyncNetwork.decompressData(payload))
                } catch (_: Exception) {
                    null
                }
                if (cloudBackup != null) {
                    isRestoring = true
                    var restoredAny = false
                    var restoredSettings = false
                    var restoredExtensions = false
                    var restoredBookmarks = false
                    var restoredResume = false
                    try {
                        for (cat in enabledRestore) {
                            val localCat = filterBackup(localBackup, cat)
                            val cloudCat = filterBackup(cloudBackup, cat)
                            if (SyncBackup.isEmpty(cloudCat)) continue
                            val isDirty = synchronized(dirtyCategories) { cat in dirtyCategories }
                            val merged = SyncBackup.mergeBackupFiles(
                                localCat, cloudCat,
                                localCategoryTs = SyncStorage.categoryTimestamp(cat),
                                cloudPayloadTs = others.updatedAt,
                                isLocallyDirty = isDirty,
                            )
                            if (merged != localCat) {
                                SyncBackup.restore(appCtx, merged, setOf(cat))
                                restoredAny = true
                                when (cat) {
                                    SyncCategory.SETTINGS -> restoredSettings = true
                                    SyncCategory.EXTENSIONS -> restoredExtensions = true
                                    SyncCategory.BOOKMARKS -> restoredBookmarks = true
                                    SyncCategory.RESUME_WATCHING -> restoredResume = true
                                    else -> {}
                                }
                            }
                            SyncStorage.setCategoryTimestamp(cat, others.updatedAt)
                        }
                    } finally {
                        isRestoring = false
                    }
                    if (restoredAny) {
                        lastStatus = "Restaurado desde ${others.name}"
                        log("restaurado desde ${others.name} (${others.itemContentId})")
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
                    val ids = SyncNetwork.registerDevice(token, projectId, deviceId, chunks)
                    if (ids != null) {
                        SyncStorage.ownChunkContentIds = ids.mapIndexed { i, id -> i to id }.toMap()
                        SyncStorage.ownContentId = ids.getOrNull(0)
                        SyncStorage.ownItemId = null
                        SyncStorage.lastPushedHash = hash
                        SyncStorage.forceReRegister = false
                        lastStatus = "Draft(s) creado(s): sync OK (${ids.size} trozo/s)"
                        log("nuevo draft registrado: ${ids.size} trozo(s)")
                    } else {
                        lastStatus = "No se pudo crear el draft"
                        lastError = SyncNetwork.lastError
                    }
                } else if (hash != SyncStorage.lastPushedHash) {
                    val updated = SyncNetwork.updateDevice(token, projectId, deviceId, chunks, ownIds)
                    if (updated != null) {
                        SyncStorage.ownChunkContentIds = updated
                        SyncStorage.ownContentId = updated[0]
                        SyncStorage.ownItemId = null
                        SyncStorage.lastPushedHash = hash
                        lastStatus = "Draft(s) actualizado(s): sync OK (${updated.size} trozo/s)"
                        log("draft actualizado: ${updated.size} trozo(s)")
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
            runSync()
            if (showToastResult) {
                showToast(if (SyncStorage.isLoggedIn()) "Sync completado" else "Configura el plugin primero")
            }
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
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
        )
    }
}