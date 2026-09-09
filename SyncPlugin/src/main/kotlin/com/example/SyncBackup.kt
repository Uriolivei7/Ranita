package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

object SyncBackup {

    private const val ACCOUNTS_KEY = "data_store_helper/account"

    private const val TOMBSTONE_TTL_SECONDS = 30L * 24 * 3600

    private const val POSITION_LEAD_SECONDS = 2.0

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

    private val accountScopedSections = setOf(
        "video_pos_dur", "result_resume_watching", "result_resume_watching_2",
        "result_season", "result_dub", "result_episode", "download_header_cache",
        "video_watch_state",
    )

    private class ScopedKey(
        val account: String,
        val section: String,
        val id: String,
    )

    private fun scopedKey(key: String): ScopedKey? {
        val parts = key.split("/")
        if (parts.size < 3) return null
        val section = parts[1]
        if (section !in accountScopedSections) return null
        return ScopedKey(parts[0], section, parts[2])
    }

    private fun keyIndex(account: String): String? {
        if (account.isNotEmpty() && account.all { it.isDigit() }) return account
        return Regex("keyIndex=(\\d+)").find(account)?.groupValues?.get(1)
    }

    private fun localAccountPrefixes(context: Context): Map<String, String> {
        val digit = HashMap<String, String>()
        val other = HashMap<String, String>()
        val seen = HashMap<String, String>()
        for (key in context.getSharedPrefs().all.keys + context.getDefaultSharedPrefs().all.keys) {
            val sc = scopedKey(key) ?: continue
            val ki = keyIndex(sc.account) ?: continue
            if (seen.containsKey(ki)) continue
            seen[ki] = sc.account
            if (sc.account.all { it.isDigit() }) digit[ki] = sc.account else other[ki] = sc.account
        }
        val result = HashMap<String, String>()
        for (ki in seen.keys) {
            result[ki] = digit[ki] ?: other[ki] ?: ki
        }
        return result
    }

    private fun localForm(key: String, localPrefixes: Map<String, String>): String {
        val sc = scopedKey(key) ?: return key
        val ki = keyIndex(sc.account) ?: return key
        val prefix = localPrefixes[ki] ?: ki
        return "$prefix/${sc.section}/${sc.id}"
    }

    private fun deletionTs(deletions: Map<String, Long>, key: String): Long? {
        deletions[key]?.let { return it }
        val sc = scopedKey(key) ?: return null
        val ki = keyIndex(sc.account) ?: return null
        for ((delKey, delTs) in deletions) {
            val delSc = scopedKey(delKey) ?: continue
            val delKi = keyIndex(delSc.account) ?: continue
            if (delKi == ki && delSc.section == sc.section && delSc.id == sc.id) {
                return delTs
            }
        }
        return null
    }

    fun classifyKey(key: String): SyncCategory? {
        val lowerKey = key.lowercase()
        if (!key.isTransferable()) return null

        if (lowerKey.contains("result_favorites_state_data") || lowerKey.contains("result_watch_state")) {
            return SyncCategory.BOOKMARKS
        }
        if (lowerKey.contains("result_resume_watching") || lowerKey.contains("video_pos_dur") ||
            lowerKey.contains("download_header_cache") || lowerKey.contains("result_season") ||
            lowerKey.contains("result_dub") || lowerKey.contains("result_episode") ||
            lowerKey.contains("video_watch_state")
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
                isResumeRelevant(entry.key, entry.value, resumeIndex)
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, entry.value, resumeIndex)
        }
        val deletions = pruneTombstones(SyncStorage.tombstones())
            .filterKeys { key -> key.isTransferable() && classifyKey(key) in enabled }
        return BackupFile(
            datastore = buildVars(allData),
            settings = buildVars(allSettings),
            deletions = deletions,
        )
    }

    fun recordDeletion(key: String) {
        if (!key.isTransferable() || classifyKey(key) == null) return
        val ts = SyncTime.nowEpochSeconds()
        val merged = HashMap(SyncStorage.tombstones())
        merged[key] = ts
        SyncStorage.setTombstones(pruneTombstones(merged))
    }

    fun removeTombstone(key: String) {
        val current = SyncStorage.tombstones()
        if (!current.containsKey(key)) return
        val merged = HashMap(current)
        merged.remove(key)
        SyncStorage.setTombstones(pruneTombstones(merged))
    }

    private fun pruneTombstones(map: Map<String, Long>): Map<String, Long> {
        val now = SyncTime.nowEpochSeconds()
        return map.filterValues { now - it < TOMBSTONE_TTL_SECONDS }
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
        value: Any?,
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
            // Las posiciones se sincronizan siempre: alimentan la barra del "Continuar viendo"
            // (getViewPos(episodeId)) y la reanudación en el player de cualquier episodio.
            val id = parts.getOrNull(2)?.toIntOrNull() ?: return false
            if (id !is Int) return false
            return true
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
        applyDeletions(context, backupFile.deletions, enabled, backupFile)
    }

    private fun applyDeletions(
        context: Context,
        deletions: Map<String, Long>,
        enabled: Set<SyncCategory>,
        mergedBackup: BackupFile?,
    ) {
        if (deletions.isEmpty()) return
        val dataPrefs = context.getSharedPrefs()
        val settingsPrefs = context.getDefaultSharedPrefs()
        val now = SyncTime.nowEpochSeconds()
        val localPrefixes = localAccountPrefixes(context)
        val dataRemove = mutableListOf<String>()
        val settingsRemove = mutableListOf<String>()
        val keepKeys = mutableSetOf<String>()
        if (mergedBackup != null) {
            mergedBackup.datastore.string?.let { keys -> keepKeys += keys.keys.map { localForm(it, localPrefixes) } }
            mergedBackup.datastore.bool?.let { keys -> keepKeys += keys.keys.map { localForm(it, localPrefixes) } }
            mergedBackup.datastore.int?.let { keys -> keepKeys += keys.keys.map { localForm(it, localPrefixes) } }
            mergedBackup.datastore.long?.let { keys -> keepKeys += keys.keys.map { localForm(it, localPrefixes) } }
            mergedBackup.datastore.float?.let { keys -> keepKeys += keys.keys.map { localForm(it, localPrefixes) } }
            mergedBackup.settings.string?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.bool?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.int?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.long?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.float?.let { keys -> keepKeys += keys.keys }
        }
        for ((key, delTs) in deletions) {
            val k = localForm(key, localPrefixes)
            if (!k.isTransferable() || classifyKey(k) !in enabled) continue
            if (now - delTs >= TOMBSTONE_TTL_SECONDS) continue
            if (k in keepKeys) continue
            if (dataPrefs.contains(k)) {
                if (dataTimestamp(dataPrefs.all[k]) < delTs) dataRemove.add(k)
            } else if (settingsPrefs.contains(k)) {
                if (dataTimestamp(settingsPrefs.all[k]) < delTs) settingsRemove.add(k)
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
        val merged = HashMap(SyncStorage.tombstones())
        deletions.forEach { (k, v) -> if (v > (merged[k] ?: 0L)) merged[k] = v }
        SyncStorage.setTombstones(pruneTombstones(merged))
    }

    private fun dataTimestamp(value: Any?): Long =
        when (value) {
            is String -> SyncTime.toEpochSeconds(SyncKeyPath.extractTimestamp(value))
            else -> 0L
        }

    private fun restoreVars(
        context: Context,
        vars: BackupVars,
        isSettings: Boolean,
        enabled: Set<SyncCategory>,
    ) {
        val prefs = if (isSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        val editor = prefs.edit()
        val localPrefixes = if (isSettings) emptyMap() else localAccountPrefixes(context)
        var restoredPositions = 0
        var diagSameLogged = 0
        var diagDiffLogged = 0

        vars.bool?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putBoolean(mk, v)
        }
        vars.int?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putInt(mk, v)
        }
        vars.float?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putFloat(mk, v)
        }
        vars.long?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putLong(mk, v)
        }
        vars.stringSet?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putStringSet(mk, v)
        }
        vars.string?.forEach { (k, v) ->
            val mk = localForm(k, localPrefixes)
            if (mk.isTransferable() && classifyKey(mk) in enabled) {
                val localVal = prefs.getString(mk, null)
                val cloudTs = SyncKeyPath.extractTimestamp(v)
                val localTs = SyncKeyPath.extractTimestamp(localVal)
                val cloudNewer = SyncTime.toEpochSeconds(cloudTs) > SyncTime.toEpochSeconds(localTs)
                val willWrite = localVal == null ||
                    (localVal != v && (cloudNewer || SyncTime.shouldRestore(cloudTs, localTs))) ||
                    cloudNewer

                if (mk.contains("video_pos_dur") && !willWrite && localVal != null && diagSameLogged < 3) {
                    val pos = resumePosition(v)
                    val dur = resumeDuration(v)
                    val id = if (pos >= 0.0 && dur > 0.0) mk.split("/").last().toIntOrNull() else null
                    if (id != null) {
                        diagSameLogged++
                        Log.i("SyncStream", "[rw] pos $id: ${(pos / 1000.0).roundToInt()}s / ${(dur / 1000.0).roundToInt()}s, mismo valor")
                    }
                }

                if (willWrite) {
                    editor.putString(mk, v)
                    if (mk.contains("video_pos_dur")) {
                        val pos = resumePosition(v)
                        val dur = resumeDuration(v)
                        val id = if (pos >= 0.0 && dur > 0.0) mk.split("/").last().toIntOrNull() else null
                        if (id != null) {
                            runCatching { DataStoreHelper.setViewPos(id, pos.toLong(), dur.toLong()) }
                                .onSuccess {
                                    restoredPositions++
                                    if (diagDiffLogged < 4) {
                                        diagDiffLogged++
                                        val prevPos = resumePosition(localVal)
                                        val prev = if (localVal == null) "antes nada" else if (prevPos >= 0.0) "antes ${(prevPos / 1000.0).roundToInt()}s" else "antes otro valor"
                                        Log.i("SyncStream", "[rw] pos $id: ${(pos / 1000.0).roundToInt()}s / ${(dur / 1000.0).roundToInt()}s, $prev")
                                    }
                                }
                        }
                    }
                }
            }
        }
        editor.apply()
        if (restoredPositions > 0) {
            Log.i("SyncStream", "[rw] restore: $restoredPositions posiciones restauradas")
        }
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
            backupFile.settings.stringSet.isNullOrEmpty() &&
            backupFile.deletions.isNullOrEmpty()
    }

    fun mergeBackupFiles(
        local: BackupFile,
        cloud: BackupFile,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): BackupFile {
        val deletions = mergeDeletions(local.deletions, cloud.deletions)
        return BackupFile(
            datastore = mergeVars(local.datastore, cloud.datastore, deletions, localCategoryTs, cloudPayloadTs),
            settings = mergeVars(local.settings, cloud.settings, deletions, localCategoryTs, cloudPayloadTs),
            deletions = deletions,
        )
    }

    private fun mergeDeletions(
        local: Map<String, Long>,
        cloud: Map<String, Long>,
    ): Map<String, Long> {
        val out = HashMap<String, Long>()
        local.forEach { (k, v) -> out[k] = v }
        cloud.forEach { (k, v) -> if (v > (out[k] ?: 0L)) out[k] = v }
        return out
    }

    private fun mergeVars(
        local: BackupVars,
        cloud: BackupVars,
        deletions: Map<String, Long>,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): BackupVars = BackupVars(
        bool = mergeValueMap(local.bool, cloud.bool, local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
        int = mergeValueMap(local.int, cloud.int, local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
        float = mergeValueMap(local.float, cloud.float, local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
        long = mergeValueMap(local.long, cloud.long, local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
        string = mergeStringMap(local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
        stringSet = mergeValueMap(local.stringSet, cloud.stringSet, local.string, cloud.string, deletions, localCategoryTs, cloudPayloadTs),
    )

    private fun <T> mergeValueMap(
        local: Map<String, T>?,
        cloud: Map<String, T>?,
        localStrings: Map<String, String>?,
        cloudStrings: Map<String, String>?,
        deletions: Map<String, Long>,
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
                val delTs = deletionTs(deletions, key)
                if (delTs == null || delTs <= resumeSiblingTs(key, localStrings)) {
                    merged[key] = localVal
                }
            } else {
                merged[key] = if (
                    cloudValueWins(key, localStrings, cloudStrings, localCategoryTs, cloudPayloadTs)
                ) cloudVal else localVal
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                val delTs = deletionTs(deletions, key)
                if (delTs == null || delTs <= resumeSiblingTs(key, cloudStrings)) {
                    merged[key] = cloudVal
                }
            }
        }
        return merged
    }

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
        val type = if (parts.size >= 3) parts[parts.lastIndex - 1] else ""
        val siblingType = when (type) {
            "result_watch_state", "result_watch_state_data" -> "result_watch_state_data"
            else -> "result_resume_watching_2"
        }
        val siblingKey = if (account.isEmpty()) {
            "$siblingType/$episodeId"
        } else {
            "$account/$siblingType/$episodeId"
        }
        return SyncTime.toEpochSeconds(SyncKeyPath.extractTimestamp(stringMap[siblingKey]))
    }

    private fun mergeStringMap(
        local: Map<String, String>?,
        cloud: Map<String, String>?,
        deletions: Map<String, Long>,
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
                val delTs = deletionTs(deletions, key)
                if (delTs == null || delTs <= episodeTimestampFor(key, local, localEpisodeTs)) {
                    merged[key] = localVal
                }
            } else {
                if (key == ACCOUNTS_KEY) {
                    merged[key] = if (accountCount(cloudVal) >= accountCount(localVal)) cloudVal else localVal
                    continue
                }
                merged[key] = when (resolveWinner(
                    key, localVal, cloudVal, local, cloud,
                    localEpisodeTs, cloudEpisodeTs, localCategoryTs, cloudPayloadTs,
                )) {
                    Winner.CLOUD -> cloudVal
                    Winner.LOCAL -> localVal
                }
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                val delTs = deletionTs(deletions, key)
                if (delTs == null || delTs <= episodeTimestampFor(key, cloud, cloudEpisodeTs)) {
                    merged[key] = cloudVal
                }
            }
        }
        return merged
    }

    private fun buildEpisodeTimestampIndex(stringMap: Map<String, String>): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        for ((key, value) in stringMap) {
            val parts = key.split("/")
            if (parts.size != 3) continue
            if (parts[1] != "result_resume_watching_2") continue
            val updateTime = SyncTime.toEpochSeconds(SyncKeyPath.extractTimestamp(value))
            if (updateTime <= 0L) continue
            val episodeId = resumeEpisodeId(value) ?: continue
            val prev = result[episodeId]
            if (prev == null || updateTime > prev) result[episodeId] = updateTime
        }
        return result
    }

    private enum class Winner { CLOUD, LOCAL }

    private fun resolveWinner(
        key: String,
        localVal: String,
        cloudVal: String,
        localMap: Map<String, String>,
        cloudMap: Map<String, String>,
        localEpisodeTs: Map<Int, Long>,
        cloudEpisodeTs: Map<Int, Long>,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
    ): Winner {
        val lower = key.lowercase()
        val isPositionKey = lower.contains("video_pos_dur") || lower.contains("result_resume_watching")
        val localTs = episodeTimestampFor(key, localMap, localEpisodeTs)
        val cloudTs = episodeTimestampFor(key, cloudMap, cloudEpisodeTs)

        if (isPositionKey) {
            if (lower.contains("result_resume_watching")) {
                val localEp = resumeEpisodeId(localVal)
                val cloudEp = resumeEpisodeId(cloudVal)
                if (localEp != null && cloudEp != null && localEp != cloudEp) {
                    if (localTs > 0L || cloudTs > 0L) {
                        return if (cloudTs >= localTs) Winner.CLOUD else Winner.LOCAL
                    }
                }
            }

            val localPos = resumePosition(localVal)
            val cloudPos = resumePosition(cloudVal)
            val localDone = isCompletedValue(localPos, resumeDuration(localVal))
            val cloudDone = isCompletedValue(cloudPos, resumeDuration(cloudVal))

            if (localPos >= 0.0 && cloudPos >= 0.0 && localDone != cloudDone) {
                val doneIsCloud = cloudDone
                val doneTs = if (cloudDone) cloudTs else localTs
                val incompleteTs = if (cloudDone) localTs else cloudTs

                if (incompleteTs <= doneTs) {
                    return if (doneIsCloud) Winner.CLOUD else Winner.LOCAL
                }

                return if (doneIsCloud) Winner.LOCAL else Winner.CLOUD
            }

            if (localPos >= 0.0 && cloudPos >= 0.0 && abs(localPos - cloudPos) <= POSITION_LEAD_SECONDS) {
                if (localDone != cloudDone) {
                    return if (cloudDone) Winner.CLOUD else Winner.LOCAL
                }
                return if (cloudTs > localTs) Winner.CLOUD else Winner.LOCAL
            }
        }

        if (localTs > 0L || cloudTs > 0L) {

            if (isPositionKey && abs(cloudTs - localTs) <= POSITION_LEAD_SECONDS.toLong()) {
                val localPos = resumePosition(localVal)
                val cloudPos = resumePosition(cloudVal)
                if (localPos >= 0.0 && cloudPos >= 0.0 && abs(localPos - cloudPos) > POSITION_LEAD_SECONDS) {
                    return if (cloudPos > localPos) Winner.CLOUD else Winner.LOCAL
                }
            }
            return if (cloudTs > localTs) Winner.CLOUD else Winner.LOCAL
        }

        if (isPositionKey) {
            val localPos = resumePosition(localVal)
            val cloudPos = resumePosition(cloudVal)
            if (localPos >= 0.0 && cloudPos >= 0.0 && abs(localPos - cloudPos) > POSITION_LEAD_SECONDS) {
                return if (cloudPos > localPos) Winner.CLOUD else Winner.LOCAL
            }
        }
        return if (cloudPayloadTs > localCategoryTs) Winner.CLOUD else Winner.LOCAL
    }

    fun resumePosition(json: Any?): Double {
        if (json !is String) return -1.0
        return try {
            "\"position\":\\s*([\\d.]+)".toRegex().find(json)?.groupValues?.get(1)?.toDouble() ?: -1.0
        } catch (_: Exception) {
            -1.0
        }
    }

    fun resumeDuration(json: Any?): Double {
        if (json !is String) return -1.0
        return try {
            "\"duration\":\\s*([\\d.]+)".toRegex().find(json)?.groupValues?.get(1)?.toDouble() ?: -1.0
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun isCompletedValue(pos: Double, dur: Double): Boolean =
        pos >= 0.0 && dur > 0.0 && pos >= 0.9 * dur
    private fun episodeTimestampFor(
        key: String,
        stringMap: Map<String, String>,
        episodeTs: Map<Int, Long>,
    ): Long {
        if (key.lowercase().contains("video_pos_dur")) {
            val parts = key.split("/")
            val episodeId = parts.getOrNull(parts.lastIndex)?.toIntOrNull() ?: return 0L
            return episodeTs[episodeId] ?: 0L
        }
        val category = when {
            key.lowercase().contains("result_watch_state") -> SyncCategory.BOOKMARKS
            key.lowercase().contains("result_favorites") -> SyncCategory.BOOKMARKS
            else -> SyncCategory.SETTINGS
        }
        return SyncKeyPath.itemTimestamp(key, category, stringMap)
    }

    fun resumeEpisodeId(json: String): Int? =
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

    fun debugTs(value: Any?): Long =
        if (value is String) SyncTime.toEpochSeconds(SyncKeyPath.extractTimestamp(value)) else 0L

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