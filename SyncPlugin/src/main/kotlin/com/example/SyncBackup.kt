package com.example

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

object SyncBackup {

    private const val ACCOUNTS_KEY = "data_store_helper/account"

    private val resumeMapper = ObjectMapper()

    val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list",
        "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token",
        "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time",
        "simkl_accounts", "simkl_active", "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC",
        "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active",
        "subtitle_settings", "subs_auto_select", "subs_auto_download", "chome_subtitle_settings",
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
        "user_selected_homepage_api",
        "last_sync_api_key", "home_pref_homepage", "library_sorting_mode",
        "results_sorting_mode", "viewpager_item_key",
        "app_layout_key",
        "auto_download_plugins_key2",
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
        enabled: Set<SyncCategory>,
    ): BackupFile {
        val resumeIndex = buildResumeIndex(context.getSharedPrefs().all)
        val allData = context.getSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, resumeIndex)
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, resumeIndex)
        }
        return BackupFile(
            datastore = buildVars(allData),
            settings = buildVars(allSettings),
        )
    }

    private class ResumeIndex(
        val parentIds: Set<Int>,
        val episodeIds: Set<Int>,
    )

    private fun buildResumeIndex(allData: Map<String, *>): Map<String, ResumeIndex> {
        val parents = HashMap<String, MutableSet<Int>>()
        val episodes = HashMap<String, MutableSet<Int>>()
        for ((key, value) in allData) {
            val parts = key.split("/")
            if (parts.size != 3) continue
            if (!parts[0].all { it.isDigit() }) continue
            if (parts[1] != "result_resume_watching_2") continue
            val parentId = parts[2].toIntOrNull() ?: continue
            parents.getOrPut(parts[0]) { HashSet() }.add(parentId)
            extractEpisodeId(value)?.let { episodes.getOrPut(parts[0]) { HashSet() }.add(it) }
        }
        return parents.keys.associateWith { account ->
            ResumeIndex(
                parentIds = parents[account] ?: emptySet(),
                episodeIds = episodes[account] ?: emptySet(),
            )
        }
    }

    private fun extractEpisodeId(value: Any?): Int? {
        if (value !is String) return null
        return try {
            resumeMapper.readTree(value).get("episodeId")?.asInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun isResumeRelevant(
        key: String,
        resumeIndex: Map<String, ResumeIndex>,
    ): Boolean {
        val lowerKey = key.lowercase()
        val parts = key.split("/")
        val account = if (parts.size >= 2 && parts[0].all { it.isDigit() }) parts[0] else null
        val index = account?.let { resumeIndex[it] }
        if (index == null) return true
        if (lowerKey.contains("download_header_cache")) {
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return id in index.parentIds
        } else if (lowerKey.contains("video_pos_dur")) {
            val id = parts.getOrNull(2)?.toIntOrNull() ?: return false
            return id in index.episodeIds
        } else if (lowerKey.contains("result_season") || lowerKey.contains("result_dub") ||
            lowerKey.contains("result_episode")
        ) {
            val id = parts.getOrNull(2)?.toIntOrNull() ?: return false
            return id in index.parentIds
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
        context.getDefaultSharedPrefs().edit()
            .putInt("auto_download_plugins_key2", 2).apply()
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
    ): BackupFile = BackupFile(
        datastore = mergeVars(local.datastore, cloud.datastore, localCategoryTs, cloudPayloadTs),
        settings = mergeVars(local.settings, cloud.settings, localCategoryTs, cloudPayloadTs),
    )

    private fun mergeVars(
        local: BackupVars,
        cloud: BackupVars,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): BackupVars = BackupVars(
        bool = mergeValueMap(local.bool, cloud.bool, local.string, cloud.string, localCategoryTs, cloudPayloadTs),
        int = mergeValueMap(local.int, cloud.int, local.string, cloud.string, localCategoryTs, cloudPayloadTs),
        float = mergeValueMap(local.float, cloud.float, local.string, cloud.string, localCategoryTs, cloudPayloadTs),
        long = mergeValueMap(local.long, cloud.long, local.string, cloud.string, localCategoryTs, cloudPayloadTs),
        string = mergeStringMap(local.string, cloud.string, localCategoryTs, cloudPayloadTs),
        stringSet = mergeValueMap(local.stringSet, cloud.stringSet, local.string, cloud.string, localCategoryTs, cloudPayloadTs),
    )

    private fun <T> mergeValueMap(
        local: Map<String, T>?,
        cloud: Map<String, T>?,
        localStrings: Map<String, String>?,
        cloudStrings: Map<String, String>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
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
                merged[key] = if (
                    cloudValueWins(key, localStrings, cloudStrings, localCategoryTs, cloudPayloadTs)
                ) cloudVal else localVal
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                merged[key] = cloudVal
            }
        }
        return merged
    }

    /**
     * video_pos_dur and other numeric resume keys carry no timestamp of their own,
     * so they are resolved using the embedded updateTime of the sibling
     * result_resume_watching_2 entry for the same parent. Only when no embedded
     * timestamp exists on either side do we fall back to the draft-level timestamps.
     */
    private fun cloudValueWins(
        key: String,
        localStrings: Map<String, String>?,
        cloudStrings: Map<String, String>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): Boolean {
        val localTs = resumeSiblingTs(key, localStrings)
        val cloudTs = resumeSiblingTs(key, cloudStrings)
        if (localTs > 0L || cloudTs > 0L) return cloudTs > localTs
        return cloudPayloadTs > localCategoryTs
    }

    private fun resumeSiblingTs(key: String, stringMap: Map<String, String>?): Long {
        if (stringMap == null) return 0L
        val parts = key.split("/")
        if (parts.size < 2) return 0L
        val episodeId = parts[parts.size - 1].toIntOrNull() ?: return 0L
        val account = if (parts[0].all { it.isDigit() }) parts[0] else ""
        val resumeKey = if (account.isEmpty()) {
            "result_resume_watching_2/$episodeId"
        } else {
            "$account/result_resume_watching_2/$episodeId"
        }
        return SyncTime.toEpochSeconds(SyncKeyPath.extractTimestamp(stringMap[resumeKey]))
    }

    private fun mergeStringMap(
        local: Map<String, String>?,
        cloud: Map<String, String>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): Map<String, String>? {
        if (local == null && cloud == null) return null
        if (local == null) return cloud
        if (cloud == null) return local

        val localEpisodeTs = buildEpisodeTimestampIndex(local)
        val cloudEpisodeTs = buildEpisodeTimestampIndex(cloud)

        val merged = HashMap<String, String>()
        for ((key, localVal) in local) {
            val cloudVal = cloud[key]
            if (cloudVal == null) {
                merged[key] = localVal
            } else {
                if (key == ACCOUNTS_KEY) {
                    merged[key] = if (accountCount(cloudVal) >= accountCount(localVal)) cloudVal else localVal
                    continue
                }
                val localTs = episodeTimestampFor(key, local, localEpisodeTs)
                val cloudTs = episodeTimestampFor(key, cloud, cloudEpisodeTs)
                if (localTs > 0L || cloudTs > 0L) {
                    merged[key] = if (cloudTs > localTs) cloudVal else localVal
                } else {
                    merged[key] = if (cloudPayloadTs > localCategoryTs) cloudVal else localVal
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

    /** Builds a map episodeId -> latest updateTime from all resume watching entries. */
    private fun buildEpisodeTimestampIndex(stringMap: Map<String, String>): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        for ((key, value) in stringMap) {
            val parts = key.split("/")
            if (parts.size != 3) continue
            if (parts[1] != "result_resume_watching_2") continue
            val updateTime = SyncKeyPath.extractTimestamp(value)
            if (updateTime <= 0L) continue
            val episodeId = resumeEpisodeId(value) ?: continue
            val prev = result[episodeId]
            if (prev == null || updateTime > prev) result[episodeId] = updateTime
        }
        return result
    }

    /**
     * PosDur (video_pos_dur) has no timestamp of its own, so it is resolved using the
     * updateTime of its sibling result_resume_watching_2 entry (which bumps on every
     * progress change). Otherwise the merge would pick whichever device pushed last,
     * silently reverting progress updates on other devices.
     */
    private fun episodeTimestampFor(
        key: String,
        stringMap: Map<String, String>,
        episodeTs: Map<Int, Long>,
    ): Long {
        if (!key.lowercase().contains("video_pos_dur")) {
            return SyncKeyPath.itemTimestamp(key, SyncCategory.SETTINGS, stringMap)
        }
        val parts = key.split("/")
        val episodeId = parts.getOrNull(parts.lastIndex)?.toIntOrNull() ?: return 0L
        return episodeTs[episodeId] ?: 0L
    }

    private fun resumeEpisodeId(json: String): Int? =
        try {
            "\"episodeId\":\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
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

    private fun accountCount(json: String?): Int {
        if (json.isNullOrBlank()) return 0
        return try {
            resumeMapper.readTree(json).size()
        } catch (_: Exception) {
            0
        }
    }

    private fun Context.getSharedPrefs(): SharedPreferences =
        getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)

    private fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
}