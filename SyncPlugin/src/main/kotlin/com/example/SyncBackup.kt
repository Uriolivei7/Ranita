package com.example

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.security.MessageDigest

object SyncBackup {

    private val resumeWatchingCache = mutableListOf<DataStoreHelper.ResumeWatchingResult>()

    suspend fun cachedResumeWatching(): List<DataStoreHelper.ResumeWatchingResult> =
        try {
            HomeViewModel.getResumeWatching()
                ?.also { resumeWatchingCache.clear(); resumeWatchingCache.addAll(it) }
            resumeWatchingCache
        } catch (e: Exception) { resumeWatchingCache }

    val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list",
        "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token",
        "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time",
        "simkl_accounts", "simkl_active", "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC",
        "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active",
        "biometric_key", "nginx_user",
        "download_path_key", "download_path_key_visual", "backup_path_key", "backup_dir_path_key",
        "cs3-votes", "last_sync_api", "last_click_action", "last_opened_id", "library_folder",
        "result_resume_watching_migrated", "jsdelivr_proxy_key",
        "device_id", "sync_token", "sync_project_num", "sync_project_id",
        "sync_item_id", "sync_device_id", "restore_device", "backup_device",
        "sync_backup_", "sync_restore_",
        "download_info", "download_resume", "download_q_resume", "download_episode_cache",
        "prerelease_update",
        "data_store_helper/account_key_index", "VERSION_NAME", "FILES_TO_DELETE_KEY",
        "HAS_DONE_SETUP", "PLUGINS_KEY",
        "used_fstream_providers_v3", "fstream_version",
        "home_api_used", "home_api", "user_selected_homepage_api",
        "last_sync_api_key", "home_pref_homepage", "library_sorting_mode",
        "results_sorting_mode", "viewpager_item_key",
        "app_layout_key",
    )

    private fun String.isTransferable(): Boolean {
        val lower = this.lowercase()
        return !nonTransferableKeys.any { lower.contains(it.lowercase()) }
    }

    fun classifyKey(key: String): SyncCategory? {
        val lowerKey = key.lowercase()
        if (!key.isTransferable()) return null

        if (lowerKey.contains("result_favorites_state_data") || lowerKey.contains("result_watch_state")) {
            return SyncCategory.BOOKMARKS
        }
        if (lowerKey.contains("result_resume_watching") || lowerKey.contains("video_pos_dur") ||
            lowerKey.contains("download_header_cache") || lowerKey.contains("result_season") ||
            lowerKey.contains("result_dub") || lowerKey.contains("result_episode")
        ) {
            return SyncCategory.RESUME_WATCHING
        }
        if (lowerKey.contains("search_history")) {
            return SyncCategory.SEARCH_HISTORY
        }
        if (lowerKey.contains("plugins_key")) return null
        if (lowerKey.contains("plugins_repositories") || lowerKey.contains("repositories")) {
            return SyncCategory.EXTENSIONS
        }
        return SyncCategory.SETTINGS
    }

    fun isDynamicCategory(category: SyncCategory): Boolean =
        category == SyncCategory.BOOKMARKS ||
            category == SyncCategory.RESUME_WATCHING ||
            category == SyncCategory.SEARCH_HISTORY

    fun computeHash(data: String): String =
        MessageDigest.getInstance("MD5").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun buildBackup(
        context: Context,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?,
        enabled: Set<SyncCategory>,
    ): BackupFile {
        val allData = context.getSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, resumeWatching)
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, resumeWatching)
        }
        return BackupFile(
            datastore = buildVars(allData),
            settings = buildVars(allSettings),
        )
    }

    private fun isResumeRelevant(
        key: String,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?,
    ): Boolean {
        if (resumeWatching == null) return true
        val lowerKey = key.lowercase()
        if (lowerKey.contains("download_header_cache")) {
            val id = key.split("/").getOrNull(1)?.toIntOrNull()
            return id?.let { intId ->
                resumeWatching.any { if (it.parentId != null) it.parentId == intId else it.id == intId }
            } ?: false
        } else if (lowerKey.contains("video_pos_dur")) {
            val id = key.split("/").getOrNull(2)?.toIntOrNull()
            return id?.let { intId -> resumeWatching.any { it.id == intId } } ?: false
        } else if (lowerKey.contains("result_season") || lowerKey.contains("result_dub") ||
            lowerKey.contains("result_episode")
        ) {
            val id = key.split("/").getOrNull(2)?.toIntOrNull()
            return id?.let { intId -> resumeWatching.any { it.parentId == intId } } ?: false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildVars(data: Map<String, *>): BackupVars = BackupVars(
        bool = data.filter { it.value is Boolean } as? Map<String, Boolean>,
        int = data.filter { it.value is Int } as? Map<String, Int>,
        string = data.filter { it.value is String } as? Map<String, String>,
        float = data.filter { it.value is Float } as? Map<String, Float>,
        long = data.filter { it.value is Long } as? Map<String, Long>,
        stringSet = data.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
    )

    fun restore(
        context: Context,
        backupFile: BackupFile,
        enabled: Set<SyncCategory>,
    ) {
        restoreVars(context, backupFile.datastore, isSettings = false, enabled)
        restoreVars(context, backupFile.settings, isSettings = true, enabled)
    }

    private fun restoreVars(
        context: Context,
        vars: BackupVars,
        isSettings: Boolean,
        enabled: Set<SyncCategory>,
    ) {
        val prefs = if (isSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        val editor = prefs.edit()

        vars.bool?.forEach { (k, v) -> if (k.isTransferable() && classifyKey(k) in enabled) editor.putBoolean(k, v) }
        vars.int?.forEach { (k, v) -> if (k.isTransferable() && classifyKey(k) in enabled) editor.putInt(k, v) }
        vars.float?.forEach { (k, v) -> if (k.isTransferable() && classifyKey(k) in enabled) editor.putFloat(k, v) }
        vars.long?.forEach { (k, v) -> if (k.isTransferable() && classifyKey(k) in enabled) editor.putLong(k, v) }
        vars.stringSet?.forEach { (k, v) -> if (k.isTransferable() && classifyKey(k) in enabled) editor.putStringSet(k, v) }
        vars.string?.forEach { (k, v) ->
            if (k.isTransferable() && classifyKey(k) in enabled) {
                val localVal = prefs.getString(k, null)
                val cloudTs = SyncKeyPath.extractTimestamp(v)
                val localTs = SyncKeyPath.extractTimestamp(localVal)
                if (localVal == null || SyncTime.shouldRestore(cloudTs, localTs)) {
                    editor.putString(k, v)
                }
            }
        }
        editor.apply()
    }

    fun isEmpty(backupFile: BackupFile?): Boolean {
        if (backupFile == null) return true
        return backupFile.datastore.bool.isNullOrEmpty() &&
            backupFile.datastore.int.isNullOrEmpty() &&
            backupFile.datastore.string.isNullOrEmpty() &&
            backupFile.datastore.float.isNullOrEmpty() &&
            backupFile.datastore.long.isNullOrEmpty() &&
            backupFile.datastore.stringSet.isNullOrEmpty() &&
            backupFile.settings.bool.isNullOrEmpty() &&
            backupFile.settings.int.isNullOrEmpty() &&
            backupFile.settings.string.isNullOrEmpty() &&
            backupFile.settings.float.isNullOrEmpty() &&
            backupFile.settings.long.isNullOrEmpty() &&
            backupFile.settings.stringSet.isNullOrEmpty()
    }

    fun mergeBackupFiles(
        local: BackupFile,
        cloud: BackupFile,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        isLocallyDirty: Boolean,
    ): BackupFile = BackupFile(
        datastore = mergeVars(local.datastore, cloud.datastore, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        settings = mergeVars(local.settings, cloud.settings, localCategoryTs, cloudPayloadTs, isLocallyDirty),
    )

    private fun mergeVars(
        local: BackupVars,
        cloud: BackupVars,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        isLocallyDirty: Boolean,
    ): BackupVars = BackupVars(
        bool = mergeMap(local.bool, cloud.bool, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        int = mergeMap(local.int, cloud.int, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        float = mergeMap(local.float, cloud.float, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        long = mergeMap(local.long, cloud.long, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        string = mergeStringMap(local.string, cloud.string, localCategoryTs, cloudPayloadTs, isLocallyDirty),
        stringSet = mergeMap(local.stringSet, cloud.stringSet, localCategoryTs, cloudPayloadTs, isLocallyDirty),
    )

    private fun <T> mergeMap(
        local: Map<String, T>?,
        cloud: Map<String, T>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        isLocallyDirty: Boolean,
    ): Map<String, T>? {
        if (local == null && cloud == null) return null
        if (local == null) return cloud
        if (cloud == null) return local

        val merged = HashMap<String, T>()
        for ((key, localVal) in local) {
            val cloudVal = cloud[key]
            if (cloudVal == null) {
                merged[key] = localVal
            } else {
                merged[key] = if (cloudPayloadTs > localCategoryTs && !isLocallyDirty) cloudVal else localVal
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                merged[key] = cloudVal
            }
        }
        return merged
    }

    private fun mergeStringMap(
        local: Map<String, String>?,
        cloud: Map<String, String>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        isLocallyDirty: Boolean,
    ): Map<String, String>? {
        if (local == null && cloud == null) return null
        if (local == null) return cloud
        if (cloud == null) return local

        val merged = HashMap<String, String>()
        for ((key, localVal) in local) {
            val cloudVal = cloud[key]
            if (cloudVal == null) {
                merged[key] = localVal
            } else {
                val localTs = SyncKeyPath.itemTimestamp(key, SyncCategory.SETTINGS, local)
                val cloudTs = SyncKeyPath.itemTimestamp(key, SyncCategory.SETTINGS, cloud)
                if (localTs > 0L || cloudTs > 0L) {
                    merged[key] = if (cloudTs > localTs) cloudVal else localVal
                } else {
                    merged[key] = if (cloudPayloadTs > localCategoryTs && !isLocallyDirty) cloudVal else localVal
                }
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                merged[key] = cloudVal
            }
        }
        return merged
    }

    fun getBackupFileKeys(backupFile: BackupFile): Set<String> {
        val keys = mutableSetOf<String>()
        backupFile.datastore.bool?.keys?.let { keys.addAll(it) }
        backupFile.datastore.int?.keys?.let { keys.addAll(it) }
        backupFile.datastore.float?.keys?.let { keys.addAll(it) }
        backupFile.datastore.long?.keys?.let { keys.addAll(it) }
        backupFile.datastore.stringSet?.keys?.let { keys.addAll(it) }
        backupFile.datastore.string?.keys?.let { keys.addAll(it) }
        backupFile.settings.bool?.keys?.let { keys.addAll(it) }
        backupFile.settings.int?.keys?.let { keys.addAll(it) }
        backupFile.settings.float?.keys?.let { keys.addAll(it) }
        backupFile.settings.long?.keys?.let { keys.addAll(it) }
        backupFile.settings.stringSet?.keys?.let { keys.addAll(it) }
        backupFile.settings.string?.keys?.let { keys.addAll(it) }
        return keys
    }

    private fun Context.getSharedPrefs(): SharedPreferences =
        getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)

    private fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
}