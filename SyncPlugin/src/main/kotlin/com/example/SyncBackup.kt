package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.security.MessageDigest
import kotlin.math.abs

object SyncBackup {

    private const val ACCOUNTS_KEY = "data_store_helper/account"

    private const val TOMBSTONE_TTL_SECONDS = 30L * 24 * 3600

    private const val POSITION_LEAD_SECONDS = 2.0

    /** Ventana en la que una posición menor tras un completado cuenta como re-watch genuino. */
    private const val COMPLETION_GRACE_SECONDS = 1800L

    private val resumeMapper = ObjectMapper()

    /** Secciones de reproducción con clave por-cuenta ({keyIndex}/{seccion}/{id}). */
    private val ACCOUNT_SCOPED_SECTIONS = setOf(
        "video_pos_dur", "result_resume_watching", "result_resume_watching_2",
        "result_season", "result_dub", "result_episode", "download_header_cache",
    )

    private data class ScopedKey(val account: String, val section: String, val id: Int)

    /** Descompone {cuenta}/{seccion}/{id} tolerando "/" dentro de la cuenta (p.ej. URLs de imagen). */
    private fun scopedKey(key: String): ScopedKey? {
        val m = Regex("^(.*)/([a-zA-Z0-9_]+)/([-]?\\d+)$").find(key) ?: return null
        val id = m.groupValues[3].toIntOrNull() ?: return null
        if (m.groupValues[2].lowercase() !in ACCOUNT_SCOPED_SECTIONS) return null
        return ScopedKey(m.groupValues[1], m.groupValues[2], id)
    }

    /** Prefijo de cuenta activa que CloudStream usa en las claves: el keyIndex del perfil activo. */
    fun currentAccount(): String? {
        try {
            val acc = DataStoreHelper.getCurrentAccount() ?: return null
            val viaReflection = runCatching {
                val cls = Class.forName("com.lagradost.cloudstream3.utils.DataStoreHelper\$Account")
                if (cls.isInstance(acc)) cls.getMethod("getKeyIndex").invoke(acc)?.toString() else null
            }.getOrNull()
            if (!viaReflection.isNullOrBlank()) return viaReflection
            return Regex("keyIndex=(\\d+)").find(acc.toString())?.groupValues?.get(1)
        } catch (t: Throwable) {
            return null
        }
    }

    /** keyIndex -> nombre de perfil desde el JSON de cuentas de CloudStream (array u objeto). */
    private fun parseAccounts(json: String?): Map<String, String> {
        val out = HashMap<String, String>()
        if (json.isNullOrBlank()) return out
        try {
            val node = resumeMapper.readTree(json)
            if (node.isArray) {
                for (el in node) {
                    val ki = el.get("keyIndex")?.asInt() ?: continue
                    val name = el.get("name")?.asText() ?: continue
                    out[ki.toString()] = name
                }
            } else if (node.isObject) {
                for (f in node.fields()) {
                    val value = f.value
                    val ki = value.get("keyIndex")?.asInt() ?: f.key.toIntOrNull() ?: continue
                    val name = value.get("name")?.asText() ?: continue
                    out[ki.toString()] = name
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Lista de perfiles del dispositivo que envió el backup (keyIndex -> nombre). */
    fun sourceAccounts(backupFile: BackupFile): Map<String, String> =
        parseAccounts(backupFile.datastore.string?.get(ACCOUNTS_KEY))

    /** nombre -> keyIndex de los perfiles locales. */
    private fun localAccountsByName(context: Context): Map<String, String> =
        parseAccounts(context.getSharedPrefs().getString(ACCOUNTS_KEY, null))
            .entries.associate { (ki, name) -> name to ki }

    /**
     * Mapea una clave de reproducción entrante al perfil local con el mismo nombre.
     * sourceAccounts: keyIndex->nombre del dispositivo que envió. Si llega vacía
     * (dispositivo en versión antigua), cae al perfil activo (comportamiento previo).
     * Devuelve null para descartar la clave (perfil inexistente en origen o en destino).
     */
    private fun mapIncomingKey(
        key: String,
        localAccount: String?,
        sourceAccounts: Map<String, String>?,
        localByName: Map<String, String>,
    ): String? {
        if (localAccount == null) return key
        val sc = scopedKey(key) ?: return key
        if (sc.account == localAccount) return key
        val srcIdx = when {
            sc.account.all { it.isDigit() } -> sc.account
            sc.account.startsWith("Account(", ignoreCase = true) ->
                Regex("keyIndex=(\\d+)").find(sc.account)?.groupValues?.get(1)
            else -> null
        } ?: return null
        if (sourceAccounts.isNullOrEmpty()) {
            return "$localAccount/${sc.section}/${sc.id}"
        }
        val name = sourceAccounts[srcIdx] ?: return null
        val localKi = localByName[name] ?: return null
        return "$localKi/${sc.section}/${sc.id}"
    }

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
                isResumeRelevant(entry.key, entry.value, resumeIndex)
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) in enabled &&
                isResumeRelevant(entry.key, entry.value, resumeIndex)
        }
        val deletions = pruneTombstones(SyncStorage.tombstones())
            .filterKeys { key -> key.isTransferable() && classifyKey(key) in enabled }
        val accountVal = context.getSharedPrefs().getString(ACCOUNTS_KEY, null)
        return BackupFile(
            datastore = buildVars(allData).let { vars ->
                if (!accountVal.isNullOrBlank()) {
                    vars.copy(string = vars.string?.plus(ACCOUNTS_KEY to accountVal))
                } else vars
            },
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
            val id = parts.getOrNull(2)?.toIntOrNull() ?: false
            if (id !is Int) return false
            if (id in index.episodeIds) return true
            return isCompletedValue(resumePosition(value), resumeDuration(value))
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
        sourceAccounts: Map<String, String> = sourceAccounts(backupFile),
    ) {
        val localByName = localAccountsByName(context)
        restoreVars(context, backupFile.datastore, isSettings = false, enabled, sourceAccounts, localByName)
        restoreVars(context, backupFile.settings, isSettings = true, enabled, emptyMap(), emptyMap())
        context.getDefaultSharedPrefs().edit()
            .putInt("auto_download_plugins_key2", 2).apply()
        applyDeletions(context, backupFile.deletions, enabled, backupFile, sourceAccounts, localByName)
    }

    private fun applyDeletions(
        context: Context,
        deletions: Map<String, Long>,
        enabled: Set<SyncCategory>,
        mergedBackup: BackupFile?,
        sourceAccounts: Map<String, String>,
        localByName: Map<String, String>,
    ) {
        if (deletions.isEmpty()) return
        val dataPrefs = context.getSharedPrefs()
        val settingsPrefs = context.getDefaultSharedPrefs()
        val now = SyncTime.nowEpochSeconds()
        val localAccount = currentAccount()
        val dataRemove = mutableListOf<String>()
        val settingsRemove = mutableListOf<String>()
        val keepKeys = mutableSetOf<String>()
        if (mergedBackup != null) {
            mergedBackup.datastore.string?.let { keys -> keepKeys += keys.keys.mapNotNull { mapIncomingKey(it, localAccount, sourceAccounts, localByName) } }
            mergedBackup.datastore.bool?.let { keys -> keepKeys += keys.keys.mapNotNull { mapIncomingKey(it, localAccount, sourceAccounts, localByName) } }
            mergedBackup.datastore.int?.let { keys -> keepKeys += keys.keys.mapNotNull { mapIncomingKey(it, localAccount, sourceAccounts, localByName) } }
            mergedBackup.datastore.long?.let { keys -> keepKeys += keys.keys.mapNotNull { mapIncomingKey(it, localAccount, sourceAccounts, localByName) } }
            mergedBackup.datastore.float?.let { keys -> keepKeys += keys.keys.mapNotNull { mapIncomingKey(it, localAccount, sourceAccounts, localByName) } }
            mergedBackup.settings.string?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.bool?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.int?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.long?.let { keys -> keepKeys += keys.keys }
            mergedBackup.settings.float?.let { keys -> keepKeys += keys.keys }
        }
        for ((key, delTs) in deletions) {
            val k = mapIncomingKey(key, localAccount, sourceAccounts, localByName) ?: continue
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
        sourceAccounts: Map<String, String>,
        localByName: Map<String, String>,
    ) {
        val prefs = if (isSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        val editor = prefs.edit()
        val localAccount = if (isSettings) null else currentAccount()

        vars.bool?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putBoolean(mk, v)
        }
        vars.int?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putInt(mk, v)
        }
        vars.float?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putFloat(mk, v)
        }
        vars.long?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putLong(mk, v)
        }
        vars.stringSet?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) editor.putStringSet(mk, v)
        }
        vars.string?.forEach { (k, v) ->
            val mk = mapIncomingKey(k, localAccount, sourceAccounts, localByName) ?: return@forEach
            if (mk.isTransferable() && classifyKey(mk) in enabled) {
                val localVal = prefs.getString(mk, null)
                val cloudTs = SyncKeyPath.extractTimestamp(v)
                val localTs = SyncKeyPath.extractTimestamp(localVal)
                if (localVal == null || SyncTime.shouldRestore(cloudTs, localTs)) {
                    editor.putString(mk, v)
                    if (mk.contains("video_pos_dur")) {
                        val pos = resumePosition(v)
                        val dur = resumeDuration(v)
                        if (pos >= 0.0 && dur > 0.0) {
                            val id = mk.split("/").last().toIntOrNull()
                            if (id != null) {
                                runCatching { DataStoreHelper.setViewPos(id, pos.toLong(), dur.toLong()) }
                                    .onSuccess {
                                        if (pos < 0.9 * dur) {
                                            Log.i("SyncStream", "[rw] restore bajo $id ${(100 * pos / dur).toInt()}%")
                                        }
                                    }
                            }
                        }
                    }
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
                val delTs = deletions[key]
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
                val delTs = deletions[key]
                if (delTs == null || delTs <= resumeSiblingTs(key, cloudStrings)) {
                    merged[key] = cloudVal
                }
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
                val delTs = deletions[key]
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
                val delTs = deletions[key]
                if (delTs == null || delTs <= episodeTimestampFor(key, cloud, cloudEpisodeTs)) {
                    merged[key] = cloudVal
                }
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
                if (incompleteTs - doneTs > COMPLETION_GRACE_SECONDS) {
                    return if (doneIsCloud) Winner.CLOUD else Winner.LOCAL
                }
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

    private fun resumePosition(json: Any?): Double {
        if (json !is String) return -1.0
        return try {
            "\"position\":\\s*([\\d.]+)".toRegex().find(json)?.groupValues?.get(1)?.toDouble() ?: -1.0
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun resumeDuration(json: Any?): Double {
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

    /** Diagnóstico: timestamp embebido (updateTime) del valor de una clave, en epoch seconds (0 si no tiene). */
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