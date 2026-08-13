package com.example

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
        val backupEnabled = SyncCategory.entries.any { SyncStorage.isBackupEnabled(it) }
        val restoreEnabled = SyncCategory.entries.any { SyncStorage.isRestoreEnabled(it) }
        if (!backupEnabled && !restoreEnabled) return

        val appCtx = appContext ?: return
        val token = SyncStorage.token ?: return
        val projectNum = SyncStorage.projectNum?.toIntOrNull() ?: return
        val deviceId = SyncStorage.deviceId
            ?: SyncNetwork.getDeviceId(appCtx.packageName, appCtx).also {
                SyncStorage.deviceId = it
            }

        val projectId = SyncStorage.projectId
            ?: SyncNetwork.fetchProjectId(token, projectNum)?.also { SyncStorage.projectId = it }
            ?: return

        val devices = SyncNetwork.fetchDevices(token, projectNum)
        val ownDevice = devices.firstOrNull { it.deviceId == deviceId }
        if (ownDevice != null) {
            SyncStorage.ownItemId = ownDevice.itemId
            SyncStorage.ownContentId = ownDevice.itemContentId
        }

        val enabledBackup = SyncCategory.entries.filter { SyncStorage.isBackupEnabled(it) }.toSet()
        val enabledRestore = SyncCategory.entries.filter { SyncStorage.isRestoreEnabled(it) }.toSet()

        val localBackup = SyncBackup.buildBackup(appCtx, enabledBackup)

        // --- restore from cloud ---
        if (restoreEnabled) {
            val others = devices
                .filter { it.deviceId != deviceId && !it.syncedData.isNullOrBlank() }
                .maxByOrNull { it.updatedAt }
            if (others != null) {
                val cloudBackup = try {
                    SyncNetwork.json.decodeFromString(BackupFile.serializer(), others.syncedData!!)
                } catch (_: Exception) {
                    null
                }
                if (cloudBackup != null) {
                    isRestoring = true
                    var restoredAny = false
                    var restoredSettings = false
                    var restoredExtensions = false
                    var restoredBookmarks = false
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
                                    else -> {}
                                }
                            }
                            SyncStorage.setCategoryTimestamp(cat, others.updatedAt)
                        }
                    } finally {
                        isRestoring = false
                    }
                    if (restoredAny) {
                        Handler(Looper.getMainLooper()).post {
                            if (restoredSettings) {
                                MainActivity.reloadHomeEvent(true)
                                MainActivity.reloadAccountEvent(true)
                            } else if (restoredExtensions) {
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
                val contentId = SyncStorage.ownContentId
                if (contentId == null) {
                    val (itemId, newContentId) = SyncNetwork.registerDevice(token, projectId, deviceId, data)
                    if (itemId != null && newContentId != null) {
                        SyncStorage.ownItemId = itemId
                        SyncStorage.ownContentId = newContentId
                        SyncStorage.lastPushedHash = hash
                    }
                } else if (hash != SyncStorage.lastPushedHash) {
                    val ok = SyncNetwork.updateDevice(token, contentId, deviceId, data)
                    if (ok) SyncStorage.lastPushedHash = hash
                }
            }
        }
    }

    fun forceSync(showToastResult: Boolean) {
        scope.launch {
            runSync()
            if (showToastResult) {
                showToast(if (SyncStorage.isLoggedIn()) "Sync completado" else "Configura el plugin primero")
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