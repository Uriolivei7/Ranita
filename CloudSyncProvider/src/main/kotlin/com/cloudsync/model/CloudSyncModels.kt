package com.cloudsync.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class CloudSyncCreds(
    @JsonProperty("firebaseUrl") val firebaseUrl: String = "https://cloudstream-sync-default-rtdb.firebaseio.com/",
    @JsonProperty("syncKey") val syncKey: String? = null,
    @JsonProperty("deviceName") val deviceName: String? = null,
    @JsonProperty("deviceId") val deviceId: String = java.util.UUID.randomUUID().toString(),
    @JsonProperty("backupBookmarks") val backupBookmarks: Boolean = true,
    @JsonProperty("restoreBookmarks") val restoreBookmarks: Boolean = true,
    @JsonProperty("backupResumeWatching") val backupResumeWatching: Boolean = true,
    @JsonProperty("restoreResumeWatching") val restoreResumeWatching: Boolean = true,
    @JsonProperty("backupExtensions") val backupExtensions: Boolean = true,
    @JsonProperty("restoreExtensions") val restoreExtensions: Boolean = true,
    @JsonProperty("backupSearchHistory") val backupSearchHistory: Boolean = true,
    @JsonProperty("restoreSearchHistory") val restoreSearchHistory: Boolean = true,
    @JsonProperty("backupPlayer") val backupPlayer: Boolean = true,
    @JsonProperty("restorePlayer") val restorePlayer: Boolean = true,
    @JsonProperty("backupSubtitles") val backupSubtitles: Boolean = true,
    @JsonProperty("restoreSubtitles") val restoreSubtitles: Boolean = true,
    @JsonProperty("backupTheme") val backupTheme: Boolean = true,
    @JsonProperty("restoreTheme") val restoreTheme: Boolean = true,
    @JsonProperty("backupLayout") val backupLayout: Boolean = true,
    @JsonProperty("restoreLayout") val restoreLayout: Boolean = true,
    @JsonProperty("backupDownloads") val backupDownloads: Boolean = true,
    @JsonProperty("restoreDownloads") val restoreDownloads: Boolean = true,
    @JsonProperty("backupGeneral") val backupGeneral: Boolean = true,
    @JsonProperty("restoreGeneral") val restoreGeneral: Boolean = true,
    @JsonProperty("syncBookmarks") val syncBookmarks: Boolean = true,
    @JsonProperty("syncResumeWatching") val syncResumeWatching: Boolean = true,
    @JsonProperty("syncExtensions") val syncExtensions: Boolean = true,
    @JsonProperty("syncSearchHistory") val syncSearchHistory: Boolean = true,
    @JsonProperty("syncSettings") val syncSettings: Boolean = true,
) {
    fun isLoggedIn(): Boolean {
        val key = syncKey
        return key != null && key.isNotBlank()
    }

    fun activeUrl(): String {
        return if (firebaseUrl.endsWith("/")) firebaseUrl else "$firebaseUrl/"
    }

    fun isBackupEnabled(category: SyncCategory): Boolean = when (category) {
        SyncCategory.BOOKMARKS -> backupBookmarks
        SyncCategory.RESUME_WATCHING -> backupResumeWatching
        SyncCategory.EXTENSIONS -> backupExtensions
        SyncCategory.SEARCH_HISTORY -> backupSearchHistory
        SyncCategory.SETTINGS -> backupPlayer || backupSubtitles || backupTheme || backupLayout || backupDownloads || backupGeneral
    }

    fun isRestoreEnabled(category: SyncCategory): Boolean = when (category) {
        SyncCategory.BOOKMARKS -> restoreBookmarks
        SyncCategory.RESUME_WATCHING -> restoreResumeWatching
        SyncCategory.EXTENSIONS -> restoreExtensions
        SyncCategory.SEARCH_HISTORY -> restoreSearchHistory
        SyncCategory.SETTINGS -> restorePlayer || restoreSubtitles || restoreTheme || restoreLayout || restoreDownloads || restoreGeneral
    }

    fun isSettingsBackupEnabled(sub: SettingsSubCategory): Boolean = when (sub) {
        SettingsSubCategory.PLAYER -> backupPlayer
        SettingsSubCategory.SUBTITLES -> backupSubtitles
        SettingsSubCategory.THEME -> backupTheme
        SettingsSubCategory.LAYOUT -> backupLayout
        SettingsSubCategory.DOWNLOADS -> backupDownloads
        SettingsSubCategory.GENERAL -> backupGeneral
    }

    fun isSettingsRestoreEnabled(sub: SettingsSubCategory): Boolean = when (sub) {
        SettingsSubCategory.PLAYER -> restorePlayer
        SettingsSubCategory.SUBTITLES -> restoreSubtitles
        SettingsSubCategory.THEME -> restoreTheme
        SettingsSubCategory.LAYOUT -> restoreLayout
        SettingsSubCategory.DOWNLOADS -> restoreDownloads
        SettingsSubCategory.GENERAL -> restoreGeneral
    }

    fun copyWith(
        firebaseUrl: String? = null,
        syncKey: String? = null,
        deviceName: String? = null,
    ): CloudSyncCreds = copy(
        firebaseUrl = firebaseUrl ?: this.firebaseUrl,
        syncKey = syncKey ?: this.syncKey,
        deviceName = deviceName ?: this.deviceName,
    )
}

enum class SyncCategory(val key: String) {
    BOOKMARKS("bookmarks"),
    RESUME_WATCHING("resume_watching"),
    EXTENSIONS("extensions"),
    SEARCH_HISTORY("search_history"),
    SETTINGS("settings"),
}

enum class SettingsSubCategory {
    PLAYER, SUBTITLES, THEME, LAYOUT, DOWNLOADS, GENERAL
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupFile(
    @JsonProperty("datastore") val datastore: BackupVars,
    @JsonProperty("settings") val settings: BackupVars,
    @JsonProperty("deletions") val deletions: Map<String, Long> = emptyMap(),
) {
    companion object {
        fun fromMap(map: Map<String, Any>): BackupFile {
            val datastoreMap = map["datastore"] as? Map<String, Any>
            val settingsMap = map["settings"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val delRaw = (map["deletions"] as? Map<String, Any>)?.mapValues { (it.value as Number).toLong() } ?: emptyMap()

            val del = delRaw.mapKeys {
                it.key.replace("__SLASH__", "/").replace("__RB__", "]").replace("__LB__", "[")
                    .replace("__HASH__", "#").replace("__DOL__", "$").replace("__DOT__", ".")
            }
            val datastore = datastoreMap?.let { BackupVars.fromSanitized(it) } ?: BackupVars()
            val settings = settingsMap?.let { BackupVars.fromSanitized(it) } ?: BackupVars()
            return BackupFile(datastore, settings, del)
        }

        fun mergeBackupFiles(
            local: BackupFile,
            remote: BackupFile,
            localCategoryTs: Long,
            cloudPayloadTs: Long
        ): BackupFile {
            val dels = mergeDeletions(local.deletions, remote.deletions)
            val mergedDatastore = mergeVars(local.datastore, remote.datastore, dels, localCategoryTs, cloudPayloadTs)
            val mergedSettings = mergeVars(local.settings, remote.settings, dels, localCategoryTs, cloudPayloadTs)
            return BackupFile(mergedDatastore, mergedSettings, dels)
        }
        private fun mergeDeletions(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
            val out = HashMap<String, Long>(); a.forEach { out[it.key]=it.value }; b.forEach { if ((it.value) > (out[it.key]?:0L)) out[it.key]=it.value }; return out
        }

        private fun toEpochSeconds(ts: Long): Long {
            val MILLIS_THRESHOLD = 100_000_000_000L
            return if (kotlin.math.abs(ts) >= MILLIS_THRESHOLD) ts / 1000 else ts
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
        private fun episodeTimestampFor(key: String, stringMap: Map<String, String>, episodeTs: Map<Int, Long>): Long {
            if (!key.lowercase().contains("video_pos_dur")) {
                return toEpochSeconds(extractTimestamp(stringMap[key] ?: return 0L))
            }
            val parts = key.split("/")
            val episodeId = parts.getOrNull(parts.lastIndex)?.toIntOrNull() ?: return 0L
            return episodeTs[episodeId] ?: 0L
        }
        private fun buildEpisodeTimestampIndex(stringMap: Map<String, String>): Map<Int, Long> {
            val result = HashMap<Int, Long>()
            for ((key, value) in stringMap) {
                val parts = key.split("/")
                if (parts.size != 3) continue
                if (parts[1] != "result_resume_watching_2") continue
                val updateTime = toEpochSeconds(extractTimestamp(value))
                if (updateTime <= 0L) continue
                val episodeId = try { "\"episodeId\":\\s*(\\d+)".toRegex().find(value)?.groupValues?.get(1)?.toIntOrNull() } catch (_: Exception) { null } ?: continue
                val prev = result[episodeId]
                if (prev == null || updateTime > prev) result[episodeId] = updateTime
            }
            return result
        }
        private fun resumeSiblingTs(key: String, stringMap: Map<String, String>?): Long {
            if (stringMap == null) return 0L
            val parts = key.split("/")
            if (parts.size < 2) return 0L
            val episodeId = parts[parts.size - 1].toIntOrNull() ?: return 0L
            val account = if (parts[0].all { it.isDigit() }) parts[0] else ""
            val resumeKey = if (account.isEmpty()) "result_resume_watching_2/$episodeId" else "$account/result_resume_watching_2/$episodeId"
            return toEpochSeconds(extractTimestamp(stringMap[resumeKey] ?: return 0L))
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

        private fun mergeVars(local: BackupVars, remote: BackupVars, dels: Map<String,Long>, localTs: Long, cloudTs: Long): BackupVars {
            return BackupVars(
                bool = mergeValueMap(local.bool, remote.bool, local.string, remote.string, dels, localTs, cloudTs),
                int = mergeValueMap(local.int, remote.int, local.string, remote.string, dels, localTs, cloudTs),
                long = mergeValueMap(local.long, remote.long, local.string, remote.string, dels, localTs, cloudTs),
                float = mergeValueMap(local.float, remote.float, local.string, remote.string, dels, localTs, cloudTs),
                string = mergeStringMap(local.string, remote.string, dels, localTs, cloudTs),
                stringSet = mergeValueMap(local.stringSet, remote.stringSet, local.string, remote.string, dels, localTs, cloudTs),
            )
        }
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
            if (local == null) {

                return cloud?.filterKeys { k ->
                    val delTs = deletions[k] ?: return@filterKeys true
                    delTs <= resumeSiblingTs(k, cloudStrings)
                }
            }
            if (cloud == null) return local
            val merged = HashMap<String, T>()
            for ((key, localVal) in local) {
                val cloudVal = cloud[key]
                if (cloudVal == null) {
                    merged[key] = localVal
                } else {
                    val del = deletions[key]
                    if (del != null && del > 0L) {

                        val delWins = del > resumeSiblingTs(key, localStrings) && del > resumeSiblingTs(key, cloudStrings) && del > cloudPayloadTs && del > localCategoryTs
                        if (delWins) continue
                    }
                    merged[key] = if (cloudValueWins(key, localStrings, cloudStrings, localCategoryTs, cloudPayloadTs)) cloudVal else localVal
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
            return merged.ifEmpty { null }
        }
        private fun mergeStringMap(
            local: Map<String, String>?,
            cloud: Map<String, String>?,
            deletions: Map<String, Long>,
            localCategoryTs: Long,
            cloudPayloadTs: Long,
        ): Map<String, String>? {
            if (local == null && cloud == null) return null
            if (local == null) {
                return cloud?.filterKeys { k ->
                    val delTs = deletions[k] ?: return@filterKeys true
                    val cloudEpisodeTs = buildEpisodeTimestampIndex(cloud)
                    delTs <= episodeTimestampFor(k, cloud, cloudEpisodeTs)
                }
            }
            if (cloud == null) return local
            val localEpisodeTs = buildEpisodeTimestampIndex(local)
            val cloudEpisodeTs = buildEpisodeTimestampIndex(cloud)
            val merged = HashMap<String, String>()
            for ((key, localVal) in local) {
                val cloudVal = cloud[key]
                if (cloudVal == null) {
                    merged[key] = localVal
                } else {
                    val del = deletions[key]
                    if (del != null && del > episodeTimestampFor(key, local, localEpisodeTs) && del > episodeTimestampFor(key, cloud, cloudEpisodeTs)) {
                        continue
                    }

                    val lower = key.lowercase()
                    val isPositionKey = lower.contains("video_pos_dur") || lower.contains("result_resume_watching")
                    if (isPositionKey) {
                        val localPos = try { "\"position\":\\s*([\\d.]+)".toRegex().find(localVal)?.groupValues?.get(1)?.toDouble() ?: -1.0 } catch (_: Exception) { -1.0 }
                        val cloudPos = try { "\"position\":\\s*([\\d.]+)".toRegex().find(cloudVal)?.groupValues?.get(1)?.toDouble() ?: -1.0 } catch (_: Exception) { -1.0 }
                        if (localPos >= 0.0 && cloudPos >= 0.0 && kotlin.math.abs(localPos - cloudPos) > 2.0) {
                            merged[key] = if (cloudPos > localPos) cloudVal else localVal
                            continue
                        }
                    }
                    val localTs = episodeTimestampFor(key, local, localEpisodeTs)
                    val cloudTs = episodeTimestampFor(key, cloud, cloudEpisodeTs)
                    val winnerIsCloud = if (localTs > 0L || cloudTs > 0L) cloudTs > localTs else cloudPayloadTs > localCategoryTs
                    merged[key] = if (winnerIsCloud) cloudVal else localVal
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
            return merged.ifEmpty { null }
        }
    }

    fun toMap(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["datastore"] = datastore.toMap(sanitize = true)
        result["settings"] = settings.toMap(sanitize = true)
        if (deletions.isNotEmpty()) result["deletions"] = deletions.mapKeys { com.cloudsync.backup.CloudSyncBackup.sanitizeKey(it.key) }
        return result
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupVars(
    @JsonProperty("bool") val bool: Map<String, Boolean>? = null,
    @JsonProperty("int") val int: Map<String, Int>? = null,
    @JsonProperty("long") val long: Map<String, Long>? = null,
    @JsonProperty("float") val float: Map<String, Float>? = null,
    @JsonProperty("string") val string: Map<String, String>? = null,
    @JsonProperty("stringSet") val stringSet: Map<String, Set<String>>? = null,
) {
    companion object {
        fun from(map: Map<String, Any>, desanitize: Boolean = false): BackupVars {
            val bool = mutableMapOf<String, Boolean>()
            val int = mutableMapOf<String, Int>()
            val long = mutableMapOf<String, Long>()
            val float = mutableMapOf<String, Float>()
            val string = mutableMapOf<String, String>()
            val stringSet = mutableMapOf<String, Set<String>>()
            fun dk(k: String) = if (desanitize) k.replace("__SLASH__","/").replace("__RB__","]").replace("__LB__","[").replace("__HASH__","#").replace("__DOL__","$").replace("__DOT__",".") else k
            map.forEach { (kRaw, v) ->
                val k = dk(kRaw)
                when (v) {
                    is Boolean -> bool[k] = v
                    is Int -> int[k] = v
                    is Long -> long[k] = v
                    is Float -> float[k] = v
                    is String -> string[k] = v
                    is Set<*> -> stringSet[k] = v as Set<String>
                }
            }
            return BackupVars(bool, int, long, float, string, stringSet)
        }
        fun fromSanitized(map: Map<String, Any>): BackupVars = from(map, desanitize = true)
    }

    fun toMap(sanitize: Boolean = false): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        fun sk(k: String) = if (sanitize) k.replace(".","__DOT__").replace("$","__DOL__").replace("#","__HASH__").replace("[","__LB__").replace("]","__RB__").replace("/","__SLASH__") else k
        bool?.forEach { result[sk(it.key)] = it.value }
        int?.forEach { result[sk(it.key)] = it.value }
        long?.forEach { result[sk(it.key)] = it.value }
        float?.forEach { result[sk(it.key)] = it.value }
        string?.forEach { result[sk(it.key)] = it.value }
        stringSet?.forEach { result[sk(it.key)] = it.value }
        return result
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncManifest(
    @JsonProperty("categories") val categories: Map<String, CategoryManifest>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CategoryManifest(
    @JsonProperty("ts") val timestamp: Long = 0,
    @JsonProperty("hash") val hash: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FirebaseDevice(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("deviceId") val deviceId: String = "",
    @JsonProperty("lastActive") val lastActive: Long = 0,
)