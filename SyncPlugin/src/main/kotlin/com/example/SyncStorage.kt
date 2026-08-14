package com.example

import android.content.Context
import android.content.SharedPreferences

object SyncStorage {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences("sync_plugin", Context.MODE_PRIVATE)
    }

    private fun get(key: String): String? = prefs?.getString(key, null)

    private fun set(key: String, value: String?) {
        val p = prefs ?: return
        p.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private fun keyFor(prefix: String, category: SyncCategory?) = "${prefix}_${category?.key ?: "global"}"

    var token: String?
        get() = get("sync_token")
        set(value) = set("sync_token", value)

    var projectNum: String?
        get() = get("sync_project_num")
        set(value) = set("sync_project_num", value)

    var projectId: String?
        get() = get("sync_project_id")
        set(value) = set("sync_project_id", value)

    var deviceId: String?
        get() = get("sync_device_id")
        set(value) = set("sync_device_id", value)

    var ownItemId: String?
        get() = get("sync_own_item_id")
        set(value) = set("sync_own_item_id", value)

    var ownContentId: String?
        get() = get("sync_own_content_id")
        set(value) = set("sync_own_content_id", value)

    var ownChunkContentIds: Map<Int, String>
        get() {
            val raw = get("sync_own_chunk_ids") ?: return emptyMap()
            return raw.split(';')
                .mapNotNull { seg ->
                    val parts = seg.split('|', limit = 2)
                    val idx = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val id = parts.getOrNull(1)
                    if (id.isNullOrEmpty()) null else idx to id
                }
                .toMap()
        }
        set(value) {
            val raw = value.entries.sortedBy { it.key }
                .joinToString(";") { "${it.key}|${it.value}" }
            set("sync_own_chunk_ids", raw.ifEmpty { null })
        }

    var lastPushedHash: String?
        get() = get("sync_last_pushed_hash")
        set(value) = set("sync_last_pushed_hash", value)

    var syncGen: Long?
        get() = get("sync_gen")?.toLongOrNull()
        set(value) = set("sync_gen", value?.toString())

    var forceReRegister: Boolean
        get() = get("sync_force_register") == "true"
        set(value) = set("sync_force_register", value.toString())

    fun isLoggedIn(): Boolean =
        !token.isNullOrBlank() && !projectNum.isNullOrBlank()

    /**** Flags ****/

    fun isBackupEnabled(cat: SyncCategory): Boolean =
        get(keyFor("sync_backup", cat)) == "true"

    fun setBackupEnabled(cat: SyncCategory, enabled: Boolean) =
        set(keyFor("sync_backup", cat), enabled.toString())

    fun isRestoreEnabled(cat: SyncCategory): Boolean =
        get(keyFor("sync_restore", cat)) == "true"

    fun setRestoreEnabled(cat: SyncCategory, enabled: Boolean) =
        set(keyFor("sync_restore", cat), enabled.toString())

    /**** Timestamps (1e6 seconds) ****/

    fun categoryTimestamp(cat: SyncCategory): Long =
        get(keyFor("sync_ts", cat))?.toLongOrNull() ?: 0L

    fun setCategoryTimestamp(cat: SyncCategory, ts: Long) =
        set(keyFor("sync_ts", cat), ts.toString())
}
