package com.cloudsync.provider

import android.content.Context
import com.cloudsync.backup.CloudSyncBackup
import com.cloudsync.model.*
import com.cloudsync.storage.CloudSyncStorage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.*

class CloudSyncProvider : MainAPI() {

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(
            HomePageList(request.name, emptyList(), isHorizontalImages = false),
            hasNext = false
        )
    }

    fun startSync(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val creds = CloudSyncStorage.getCreds()
        val desc = if (creds != null) "present (syncKey=${creds.syncKey})" else "null"
        Log.d("CloudSync", "startSync: creds=$desc, loggedIn=${creds?.isLoggedIn()}")
        if (creds == null || !creds.isLoggedIn()) {
            Log.w("CloudSync", "startSync: No creds or not logged in")
            onComplete(false, "No credentials")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performFullSync(context, creds)
                withContext(Dispatchers.Main) { onComplete(true, null) }
            } catch (e: Exception) {
                Log.e("CloudSync", "performFullSync exception: ${e.message}")
                withContext(Dispatchers.Main) { onComplete(false, e.message) }
            }
        }
    }

    private suspend fun fetchManifest(creds: CloudSyncCreds): Map<String, String?> {
        return try {
            val url = "${creds.activeUrl()}sync/${creds.syncKey}/manifest.json"
            val res = app.get(url)
            if (!res.isSuccessful || res.text.trim() == "null") emptyMap()
            else {
                val m: Map<String, Any> = mapper.readValue(res.text)
                @Suppress("UNCHECKED_CAST")
                val cats = (m["categories"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
                cats.mapValues { it.value["hash"] as? String }
            }
        } catch (_: Exception) { emptyMap() }
    }

    private suspend fun performFullSync(context: Context, creds: CloudSyncCreds) {
        try { registerDevice(creds) } catch (e: Exception) { Log.w("CloudSync", "registerDevice failed: ${e.message}") }
        val manifestHashes = fetchManifest(creds)
        Log.d("CloudSync", "manifest hashes: $manifestHashes")

        for (category in SyncCategory.values()) {
            val backupEnabled = creds.isBackupEnabled(category)
            val restoreEnabled = creds.isRestoreEnabled(category)
            Log.d("CloudSync", "Category ${category.key}: backup=$backupEnabled restore=$restoreEnabled")
            if (!backupEnabled && !restoreEnabled) continue

            val remoteHash = manifestHashes[category.key]
            val localHash = CloudSyncStorage.getCategoryHash(category)
            val needsPull = restoreEnabled && remoteHash != null && remoteHash != localHash
            val needsPushCheck = backupEnabled

            var remote: BackupFile? = null
            if (needsPull) {
                try {
                    remote = pullCategory(category, creds)
                    if (remote != null) {
                        val local = CloudSyncBackup.buildBackupForCategory(context, category, creds)
                        val toRestore = if (local != null) {
                            if (remote != local) remote else local
                        } else remote
                        CloudSyncBackup.restoreCategory(context, category, toRestore, creds)
                        Log.d("CloudSync", "Pulled & restored ${category.key}: ${toRestore.allKeys().size} keys")
                    } else {
                        Log.d("CloudSync", "No remote data for ${category.key}")
                    }
                } catch (e: Exception) {
                    Log.e("CloudSync", "pull ${category.key} failed: ${e.message}")
                }
            } else if (restoreEnabled) {
                Log.d("CloudSync", "Skip pull ${category.key}: remoteHash==localHash")
            }

            if (backupEnabled) {
                try {
                    val backup = CloudSyncBackup.buildBackupForCategory(context, category, creds)
                    if (backup != null) {
                        val force = remote == null && manifestHashes[category.key] == null
                        pushCategory(category, backup, creds, force = force)
                    } else {
                        Log.d("CloudSync", "No local data for ${category.key}, skip push")
                    }
                } catch (e: Exception) {
                    Log.e("CloudSync", "push ${category.key} failed: ${e.message}")
                }
            }
        }
        try { updateManifest(creds) } catch (e: Exception) { Log.w("CloudSync", "manifest failed: ${e.message}") }
        Log.d("CloudSync", "performFullSync completed")
    }

    private suspend fun pushCategory(category: SyncCategory, backup: BackupFile, creds: CloudSyncCreds, force: Boolean = false) {
        val json = mapper.writeValueAsString(backup)
        val hash = CloudSyncBackup.computeHash(json)
        val cur = CloudSyncStorage.getCategoryHash(category)
        if (!force && hash == cur) {
            Log.d("CloudSync", "No changes for ${category.key}, skip push (force=$force)")
            return
        }
        val url = "${creds.activeUrl()}sync/${creds.syncKey}/${category.key}.json"
        Log.d("CloudSync", "PUT $url hash=$hash force=$force")
        val res = app.put(url, json = backup.toMap())
        if (!res.isSuccessful) throw Exception("PUT ${category.key} failed: ${res.code} ${res.text.take(200)}")
        CloudSyncStorage.setCategoryHash(category, hash)
        CloudSyncStorage.setCategoryTimestamp(category, System.currentTimeMillis())
        Log.d("CloudSync", "Pushed ${category.key} OK")
    }

    private suspend fun pullCategory(category: SyncCategory, creds: CloudSyncCreds): BackupFile? {
        val url = "${creds.activeUrl()}sync/${creds.syncKey}/${category.key}.json"
        Log.d("CloudSync", "GET $url")
        val res = app.get(url)
        if (!res.isSuccessful) {
            if (res.code == 404) return null
            throw Exception("GET ${category.key} failed: ${res.code}")
        }
        val text = res.text.trim()
        if (text == "null" || text.isBlank()) return null
        Log.d("CloudSync", "GET ${category.key} got ${text.length} chars")

        val map: Map<String, Any> = mapper.readValue(text)
        return BackupFile.fromMap(map)
    }

    private suspend fun registerDevice(creds: CloudSyncCreds) {
        val url = "${creds.activeUrl()}devices/${creds.syncKey}/${creds.deviceId}.json"
        val data = mapOf(
            "name" to (creds.deviceName ?: "Device-${creds.deviceId.take(8)}"),
            "lastActive" to System.currentTimeMillis(),
            "deviceId" to creds.deviceId
        )
        try {
            app.put(url, json = data)
            Log.d("CloudSync", "registerDevice OK")
        } catch (e: Exception) {
            Log.w("CloudSync", "registerDevice failed: ${e.message}")
        }
    }

    private suspend fun updateManifest(creds: CloudSyncCreds) {
        val url = "${creds.activeUrl()}sync/${creds.syncKey}/manifest.json"
        val cats = SyncCategory.values().associate { cat ->
            cat.key to mapOf(
                "ts" to CloudSyncStorage.getCategoryTimestamp(cat),
                "hash" to CloudSyncStorage.getCategoryHash(cat)
            )
        }
        val manifest = mapOf("categories" to cats, "updatedAt" to System.currentTimeMillis())
        app.put(url, json = manifest)
        Log.d("CloudSync", "manifest updated")
    }

    private fun BackupFile.allKeys(): Set<String> {
        val s = mutableSetOf<String>()
        datastore.toMap().keys.forEach { s.add(it) }
        settings.toMap().keys.forEach { s.add(it) }
        return s
    }
}
