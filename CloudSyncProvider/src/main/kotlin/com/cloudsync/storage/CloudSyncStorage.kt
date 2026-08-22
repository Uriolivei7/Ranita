package com.cloudsync.storage

import android.content.Context
import android.content.SharedPreferences
import com.cloudsync.model.CloudSyncCreds
import com.cloudsync.model.SyncCategory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object CloudSyncStorage {
    private var prefs: SharedPreferences? = null
    
    private const val KEY_CREDS = "cloudsync_creds"
    private const val KEY_DEVICE_REGISTERED = "cloudsync_device_registered"
    private const val PREFIX_TS = "cloudsync_ts_"
    private const val PREFIX_HASH = "cloudsync_hash_"
    private const val PREFIX_SYNCED_KEYS = "cloudsync_keys_"
    
    private val mapper = jacksonObjectMapper()
    
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences("cloudsync_plugin", Context.MODE_PRIVATE)
    }
    
    private fun get(key: String): String? = prefs?.getString(key, null)
    
    private fun set(key: String, value: String?) {
        val p = prefs ?: return
        p.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
    
    private fun getLong(key: String, default: Long): Long = get(key)?.toLongOrNull() ?: default
    
    private fun putLong(key: String, value: Long) { set(key, value.toString()) }
    
    private fun getSet(key: String): Set<String>? {
        val arr = prefs?.getStringSet(key, null)
        return arr?.toSet()
    }
    
    private fun putSet(key: String, value: Set<String>?) {
        val p = prefs ?: return
        p.edit().apply {
            if (value == null) remove(key) else putStringSet(key, value)
        }.apply()
    }
    
    fun getCreds(): CloudSyncCreds? {
        val json = get(KEY_CREDS)
        return if (json != null) mapper.readValue(json, CloudSyncCreds::class.java) else null
    }
    
    fun setCreds(creds: CloudSyncCreds) {
        set(KEY_CREDS, mapper.writeValueAsString(creds))
    }
    
    fun isDeviceRegistered(): Boolean {
        return get(KEY_DEVICE_REGISTERED) == "true"
    }
    
    fun setDeviceRegistered(registered: Boolean) {
        set(KEY_DEVICE_REGISTERED, registered.toString())
    }
    
    fun getCategoryTimestamp(category: SyncCategory): Long {
        return getLong("$PREFIX_TS${category.key}", 0)
    }
    
    fun setCategoryTimestamp(category: SyncCategory, timestamp: Long) {
        putLong("$PREFIX_TS${category.key}", timestamp)
    }
    
    fun getCategoryHash(category: SyncCategory): String? {
        return get("$PREFIX_HASH${category.key}")
    }
    
    fun setCategoryHash(category: SyncCategory, hash: String) {
        set("$PREFIX_HASH${category.key}", hash)
    }
    
    fun getSyncedKeys(category: SyncCategory): Set<String> {
        return getSet("$PREFIX_SYNCED_KEYS${category.key}") ?: emptySet()
    }
    
    fun setSyncedKeys(category: SyncCategory, keys: Set<String>) {
        putSet("$PREFIX_SYNCED_KEYS${category.key}", keys)
    }
    
    fun clearAll() {
        set(KEY_CREDS, null)
        set(KEY_DEVICE_REGISTERED, null)
        SyncCategory.values().forEach { cat ->
            set("$PREFIX_TS${cat.key}", null)
            set("$PREFIX_HASH${cat.key}", null)
            set("$PREFIX_SYNCED_KEYS${cat.key}", null)
        }
    }
}