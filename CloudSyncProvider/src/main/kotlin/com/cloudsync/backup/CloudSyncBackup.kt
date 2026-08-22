package com.cloudsync.backup

import android.content.Context
import android.content.SharedPreferences
import com.cloudsync.model.*
import com.cloudsync.storage.CloudSyncStorage
import com.lagradost.api.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

object CloudSyncBackup {
    private const val TAG = "CloudSync"
    private const val PREF_DATASTORE = "rebuild_preference"
    private const val PREF_SETTINGS = "com.lagradost.cloudstream3_preferences"

    private const val TOMBSTONE_TTL_SECONDS = 30L * 24 * 3600
    private const val POSITION_LEAD_SECONDS = 2.0

    private val mapper = jacksonObjectMapper()
    private val resumeMapper = ObjectMapper()

    private val nonTransferableKeys = setOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list", "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token", "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active",
        "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active",
        "biometric_key", "nginx_user", "download_path_key", "download_path_key_visual", "backup_path_key", "backup_dir_path_key",
        "cs3-votes", "last_sync_api", "last_click_action", "last_opened_id", "library_folder",
        "result_resume_watching_migrated", "jsdelivr_proxy_key", "fshare_setup", "fshare_token", "bluphim_token",
        "device_id", "sync_token", "sync_project_num", "sync_project_id", "sync_item_id", "sync_device_id",
        "restore_device", "backup_device", "download_info", "download_resume", "download_q_resume", "download_episode_cache",
        "prerelease_update", "stable_update", "inappupdater",
        "data_store_helper/account_key_index", "VERSION_NAME", "FILES_TO_DELETE_KEY", "HAS_DONE_SETUP",
        "cloudsync_creds", "cloudsync_device_registered",
        "used_fstream_providers_v3", "fstream_version",
        "last_sync_api_key", "home_pref_homepage", "library_sorting_mode", "results_sorting_mode", "viewpager_item_key",
        "app_layout_key",
    )

    fun isTransferable(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return nonTransferableKeys.none { lower.contains(it, ignoreCase = true) }
    }

    fun classifyKey(key: String): SyncCategory? {
        if (!isTransferable(key)) return null
        val lower = key.lowercase(Locale.ROOT)
        return when {
            lower.contains("home_bookmarked") || lower.contains("result_favorites_state_data") || lower.contains("result_watch_state") || lower.contains("bookmarked") || lower.contains("favorites_state") -> SyncCategory.BOOKMARKS
            lower.contains("result_resume_watching") || lower.contains("video_pos_dur") ||
                    lower.contains("download_header_cache") || lower.contains("result_season") ||
                    lower.contains("result_dub") || lower.contains("result_episode") -> SyncCategory.RESUME_WATCHING
            lower.contains("search_history") -> SyncCategory.SEARCH_HISTORY
            lower.contains("plugins_key_local") -> null
            lower.contains("plugins_key") || lower.contains("plugins_repositories") ||
                    lower.contains("repositories") || lower.contains("cloudsync_extensions") -> SyncCategory.EXTENSIONS
            else -> SyncCategory.SETTINGS
        }
    }

    fun classifySettingsKey(key: String): SettingsSubCategory {
        val lower = key.lowercase(Locale.ROOT)
        return when {
            lower.contains("player") || lower.contains("video") || lower.contains("play") ||
                    lower.contains("buffer") || lower.contains("resize") || lower.contains("skip") ||
                    lower.contains("volume") || lower.contains("brightness") || lower.contains("gesture") ||
                    lower.contains("speed") || lower.contains("decoder") || lower.contains("render") ||
                    lower.contains("fit") || lower.contains("aspect") -> SettingsSubCategory.PLAYER
            lower.contains("subtitle") || lower.contains("sub") || lower.contains("caption") ||
                    lower.contains("lang") || lower.contains("font") -> SettingsSubCategory.SUBTITLES
            lower.contains("theme") || lower.contains("dark") || lower.contains("color") ||
                    lower.contains("accent") || lower.contains("primary") || lower.contains("style") -> SettingsSubCategory.THEME
            lower.contains("layout") || lower.contains("view") || lower.contains("grid") ||
                    lower.contains("list") || lower.contains("home") || lower.contains("card") ||
                    lower.contains("tab") || lower.contains("row") || lower.contains("show_") ||
                    lower.contains("homepage") -> SettingsSubCategory.LAYOUT
            lower.contains("download") || lower.contains("down") || lower.contains("path") -> SettingsSubCategory.DOWNLOADS
            else -> SettingsSubCategory.GENERAL
        }
    }

    fun isKeyBackupEnabled(key: String, category: SyncCategory, creds: CloudSyncCreds): Boolean {
        if (!creds.isBackupEnabled(category)) return false
        if (category == SyncCategory.SETTINGS) {
            return creds.isSettingsBackupEnabled(classifySettingsKey(key))
        }
        return true
    }

    fun isKeyRestoreEnabled(key: String, category: SyncCategory, creds: CloudSyncCreds): Boolean {
        if (!creds.isRestoreEnabled(category)) return false
        if (category == SyncCategory.SETTINGS) {
            return creds.isSettingsRestoreEnabled(classifySettingsKey(key))
        }
        return true
    }

    fun computeHash(data: String): String {
        return MessageDigest.getInstance("MD5").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun sanitizeKey(key: String): String = key
        .replace(".", "__DOT__").replace("$", "__DOL__").replace("#", "__HASH__")
        .replace("[", "__LB__").replace("]", "__RB__").replace("/", "__SLASH__")
    fun desanitizeKey(key: String): String = key
        .replace("__SLASH__", "/").replace("__RB__", "]").replace("__LB__", "[")
        .replace("__HASH__", "#").replace("__DOL__", "$").replace("__DOT__", ".")

    private fun getDatastorePrefs(context: Context): SharedPreferences {
        try {
            val dsClass = Class.forName("com.lagradost.cloudstream3.utils.DataStore")
            val inst = dsClass.getDeclaredField("INSTANCE").get(null)
            val m = inst.javaClass.getMethod("getSharedPrefs", Context::class.java)
            return m.invoke(inst, context) as SharedPreferences
        } catch (_: Exception) {}
        return context.getSharedPreferences(PREF_DATASTORE, Context.MODE_PRIVATE)
    }

    private fun getSettingsPrefs(context: Context): SharedPreferences {
        try {
            val dsClass = Class.forName("com.lagradost.cloudstream3.utils.DataStore")
            val inst = dsClass.getDeclaredField("INSTANCE").get(null)
            val m = inst.javaClass.getMethod("getDefaultSharedPrefs", Context::class.java)
            return m.invoke(inst, context) as SharedPreferences
        } catch (_: Exception) {}
        val fallbackName = context.packageName + "_preferences"
        try {
            val prefs = context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
            if (prefs.all.isNotEmpty()) return prefs
        } catch (_: Exception) {}
        return context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
    }

    // ---- Tombstone handling (mirror SyncBackup 85-126) ----

    fun recordDeletion(key: String) {
        if (!isTransferable(key) || classifyKey(key) == null) return
        val ts = System.currentTimeMillis() / 1000
        val merged = HashMap(CloudSyncStorage.tombstones())
        merged[key] = ts
        CloudSyncStorage.setTombstones(pruneTombstones(merged))
    }

    fun removeTombstone(key: String) {
        val current = CloudSyncStorage.tombstones()
        if (!current.containsKey(key)) return
        val merged = HashMap(current)
        merged.remove(key)
        CloudSyncStorage.setTombstones(pruneTombstones(merged))
    }

    private fun pruneTombstones(map: Map<String, Long>): Map<String, Long> {
        val now = System.currentTimeMillis() / 1000
        return map.filterValues { now - it < TOMBSTONE_TTL_SECONDS }
    }

    // ---- Resume index for filtering orphaned resume keys (mirror SyncBackup 128-184) ----

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
        val lowerKey = key.lowercase(Locale.ROOT)
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

    // ---- Build backup (mirror SyncBackup.buildBackup with per-category) ----

    fun buildBackupForCategory(context: Context, category: SyncCategory, creds: CloudSyncCreds): BackupFile? {
        val dsAll = getDatastorePrefs(context).all
        val setAll = getSettingsPrefs(context).all
        Log.d(TAG, "buildBackup ${category.key}: ds=${dsAll.size} set=${setAll.size} sample=${(dsAll.keys+setAll.keys).take(5)}")
        val resumeIndex = buildResumeIndex(dsAll)
        val pruned = pruneTombstones(CloudSyncStorage.tombstones())

        if (pruned.size != CloudSyncStorage.tombstones().size) {
            CloudSyncStorage.setTombstones(pruned)
        }
        val dels = pruned.filterKeys { isTransferable(it) && classifyKey(it) == category }
        val dataStorePrefs = dsAll.filter { (k, v) ->
            isTransferable(k) && classifyKey(k) == category && isKeyBackupEnabled(k, category, creds) && isResumeRelevant(k, resumeIndex)
        }.toMap() as Map<String, Any>
        val defaultPrefs = setAll.filter { (k, _) ->
            isTransferable(k) && classifyKey(k) == category && isKeyBackupEnabled(k, category, creds) && isResumeRelevant(k, resumeIndex)
        }.toMap() as Map<String, Any>
        Log.d(TAG, "buildBackup ${category.key}: matched ds=${dataStorePrefs.size} set=${defaultPrefs.size} dels=${dels.size}")
        if (dataStorePrefs.isEmpty() && defaultPrefs.isEmpty() && dels.isEmpty()) return null
        return BackupFile(
            datastore = BackupVars.from(dataStorePrefs),
            settings = BackupVars.from(defaultPrefs),
            deletions = dels
        )
    }

    // ---- Restore + applyDeletions (mirror SyncBackup 196-274) ----

    fun restoreCategory(context: Context, category: SyncCategory, backup: BackupFile, creds: CloudSyncCreds) {
        Log.d(TAG, "Restoring category: ${category.key}")

        val prefs = getDatastorePrefs(context)
        val defaultPrefs = getSettingsPrefs(context)
        val editor = prefs.edit()
        val defaultEditor = defaultPrefs.edit()

        restoreBackupVars(context, editor, backup.datastore, category, creds, false)
        restoreBackupVars(context, defaultEditor, backup.settings, category, creds, true)

        applyDeletions(context, backup.deletions, category, creds)

        CloudSyncStorage.setSyncedKeys(category, backup.allKeys())
    }

    private fun applyDeletions(
        context: Context,
        deletions: Map<String, Long>,
        category: SyncCategory,
        creds: CloudSyncCreds,
    ) {
        if (deletions.isEmpty()) return
        val dataPrefs = getDatastorePrefs(context)
        val settingsPrefs = getSettingsPrefs(context)
        val now = System.currentTimeMillis() / 1000
        val dataRemove = mutableListOf<String>()
        val settingsRemove = mutableListOf<String>()
        for ((key, delTs) in deletions) {
            if (!isTransferable(key) || classifyKey(key) != category) continue
            if (!isKeyRestoreEnabled(key, category, creds)) continue
            if (now - delTs >= TOMBSTONE_TTL_SECONDS) continue
            if (dataPrefs.contains(key)) {
                if (dataTimestamp(dataPrefs.all[key]) < delTs) dataRemove.add(key)
            } else if (settingsPrefs.contains(key)) {
                if (dataTimestamp(settingsPrefs.all[key]) < delTs) settingsRemove.add(key)
            }
        }
        if (dataRemove.isNotEmpty()) {
            dataPrefs.edit().apply {
                dataRemove.forEach { remove(it) }
            }.apply()
        }
        if (settingsRemove.isNotEmpty()) {
            settingsPrefs.edit().apply {
                settingsRemove.forEach { remove(it) }
            }.apply()
        }
        val merged = HashMap(CloudSyncStorage.tombstones())
        deletions.forEach { (k, v) -> if (v > (merged[k] ?: 0L)) merged[k] = v }
        CloudSyncStorage.setTombstones(pruneTombstones(merged))
    }

    private fun dataTimestamp(value: Any?): Long =
        when (value) {
            is String -> toEpochSeconds(extractTimestamp(value))
            else -> 0L
        }

    private fun toEpochSeconds(timestamp: Long): Long {
        val MILLIS_THRESHOLD = 100_000_000_000L
        return if (kotlin.math.abs(timestamp) >= MILLIS_THRESHOLD) timestamp / 1000 else timestamp
    }

    private fun resumePosition(json: String?): Double {
        if (json == null) return -1.0
        return try { "\"position\":\\s*([\\d.]+)".toRegex().find(json)?.groupValues?.get(1)?.toDouble() ?: -1.0 } catch (_:Exception){ -1.0 }
    }

    private fun restoreBackupVars(
        context: Context,
        editor: SharedPreferences.Editor,
        vars: BackupVars,
        category: SyncCategory,
        creds: CloudSyncCreds,
        isSettings: Boolean
    ) {

        com.cloudsync.CloudSyncPlugin.isRestoringGlobal = true
        try {
            vars.bool?.forEach { (k, v) ->
                if (isTransferable(k) && isKeyRestoreEnabled(k, category, creds)) {
                    editor.putBoolean(k, v)
                }
            }
            vars.int?.forEach { (k, v) ->
                if (isTransferable(k) && isKeyRestoreEnabled(k, category, creds)) {
                    editor.putInt(k, v)
                }
            }
            vars.long?.forEach { (k, v) ->
                if (isTransferable(k) && isKeyRestoreEnabled(k, category, creds)) {
                    editor.putLong(k, v)
                }
            }
            vars.float?.forEach { (k, v) ->
                if (isTransferable(k) && isKeyRestoreEnabled(k, category, creds)) {
                    editor.putFloat(k, v)
                }
            }
            vars.string?.forEach { (k, v) ->
                if (!isTransferable(k)) return@forEach
                if (k == "PLUGINS_KEY" || k == "REPOSITORIES_KEY" || k == "plugins_repositories" || k == "repositories") return@forEach
                if (isKeyRestoreEnabled(k, category, creds)) {
                    val prefs = if (isSettings) getSettingsPrefs(context) else getDatastorePrefs(context)
                    val localVal = prefs.getString(k, null)

                    val lower = k.lowercase(Locale.ROOT)
                    val isResumePos = lower.contains("video_pos_dur") || lower.contains("result_resume_watching")
                    if (isResumePos && localVal != null) {
                        val localPos = resumePosition(localVal)
                        val cloudPos = resumePosition(v)
                        if (localPos >=0 && cloudPos >=0 && abs(cloudPos - localPos) > POSITION_LEAD_SECONDS) {
                            if (cloudPos > localPos) editor.putString(k, v)

                            return@forEach
                        }
                    }
                    val cloudTs = toEpochSeconds(extractTimestamp(v))
                    val localTs = localVal?.let { toEpochSeconds(extractTimestamp(it)) } ?: 0L
                    val shouldRestore = (cloudTs == 0L && localTs == 0L) || cloudTs > localTs
                    if (localVal == null || shouldRestore) {
                        editor.putString(k, v)
                    }
                }
            }
            vars.stringSet?.forEach { (k, v) ->
                if (isTransferable(k) && isKeyRestoreEnabled(k, category, creds)) {
                    editor.putStringSet(k, v)
                }
            }
        } finally {

            editor.apply()

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.cloudsync.CloudSyncPlugin.isRestoringGlobal = false
            }, 500)
            return
        }
    }

    private fun extractTimestamp(value: String): Long {

        val parts = value.split("#ts=")
        if (parts.size == 2) return parts[1].toLongOrNull() ?: 0L

        return try {
            "\"updateTime\":\\s*(\\d+)".toRegex().find(value)?.groupValues?.get(1)?.toLong()
                ?: "\"latestUpdatedTime\":\\s*(\\d+)".toRegex().find(value)?.groupValues?.get(1)?.toLong()
                ?: "\"searchedAt\":\\s*(\\d+)".toRegex().find(value)?.groupValues?.get(1)?.toLong()
                ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun BackupFile.allKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        datastore.toMap(sanitize = false).keys.forEach { keys.add(it) }
        settings.toMap(sanitize = false).keys.forEach { keys.add(it) }
        return keys
    }
}