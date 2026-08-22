package com.cloudsync.provider

import com.cloudsync.backup.CloudSyncBackup
import com.cloudsync.model.*
import com.cloudsync.storage.CloudSyncStorage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudSyncProvider : MainAPI() {

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false
    
    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return withContext(Dispatchers.IO) {
            newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    fun startSync(context: android.content.Context) {
        val creds = CloudSyncStorage.getCreds()
        if (creds == null || !creds.isLoggedIn()) return
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (!CloudSyncStorage.isDeviceRegistered()) {
                    CloudSyncStorage.setDeviceRegistered(true)
                }
                
                performFullSync(context, creds)
                
            } catch (e: Exception) {
                com.lagradost.api.Log.e("CloudSync", "Sync error: ${e.message}")
            }
        }
    }
    
    private fun performFullSync(context: android.content.Context, creds: CloudSyncCreds) {
        val categories = SyncCategory.values()
        
        for (category in categories) {
            val backupEnabled = creds.isBackupEnabled(category)
            if (!backupEnabled) continue
            
            val backup = CloudSyncBackup.buildBackupForCategory(context, category, creds)
            if (backup != null) {
                pushCategory(category, backup)
            }
            
            pullCategory(context, category, creds)
        }
    }
    
    private fun pushCategory(category: SyncCategory, backup: BackupFile) {
        val json = mapper.writeValueAsString(backup)
        val hash = CloudSyncBackup.computeHash(json)
        
        if (hash != CloudSyncStorage.getCategoryHash(category)) {
            CloudSyncStorage.setCategoryHash(category, hash)
            CloudSyncStorage.setCategoryTimestamp(category, System.currentTimeMillis())
        }
    }
    
    private fun pullCategory(context: android.content.Context, category: SyncCategory, creds: CloudSyncCreds) {
        // TODO: Pull from Firebase
    }
}