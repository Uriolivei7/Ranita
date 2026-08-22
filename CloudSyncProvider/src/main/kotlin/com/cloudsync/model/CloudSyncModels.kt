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
            val del = (map["deletions"] as? Map<String, Any>)?.mapValues { (it.value as Number).toLong() } ?: emptyMap()
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
        
        private fun mergeVars(local: BackupVars, remote: BackupVars, dels: Map<String,Long>, localTs: Long, cloudTs: Long): BackupVars {
            // reutiliza lógica de SyncBackup con tombstones
            fun <T> mergeMap(localM: Map<String,T>?, remoteM: Map<String,T>?): Map<String,T>? {
                if (localM==null && remoteM==null) return null
                if (localM==null) return remoteM?.filterKeys { dels[it]?.let { ts -> ts <= 0 } ?: true }
                if (remoteM==null) return localM
                val out = HashMap<String,T>()
                for ((k,v) in localM) {
                    val rv = remoteM[k]
                    if (rv==null) out[k]=v else {
                        val del = dels[k] ?:0L

                        if (del>0) continue
                        val useRemote = cloudTs > localTs
                        out[k] = if (useRemote) rv else v
                    }
                }
                for ((k,v) in remoteM) if (!localM.containsKey(k) && (dels[k]==null)) out[k]=v
                return out
            }
            return BackupVars(
                bool = mergeMap(local.bool, remote.bool),
                int = mergeMap(local.int, remote.int),
                long = mergeMap(local.long, remote.long),
                float = mergeMap(local.float, remote.float),
                string = mergeMap(local.string, remote.string),
                stringSet = mergeMap(local.stringSet, remote.stringSet),
            )
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