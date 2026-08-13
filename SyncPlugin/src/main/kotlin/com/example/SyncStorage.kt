package com.example

import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey

object SyncStorage {

    private fun keyFor(prefix: String, category: SyncCategory?) = "${prefix}_${category?.key ?: "global"}"

    var token: String?
        get() = getKey<String>("sync_token")
        set(value) = setKey("sync_token", value)

    var projectNum: String?
        get() = getKey<String>("sync_project_num")
        set(value) = setKey("sync_project_num", value)

    var projectId: String?
        get() = getKey<String>("sync_project_id")
        set(value) = setKey("sync_project_id", value)

    var deviceId: String?
        get() = getKey<String>("sync_device_id")
        set(value) = setKey("sync_device_id", value)

    var ownItemId: String?
        get() = getKey<String>("sync_own_item_id")
        set(value) = setKey("sync_own_item_id", value)

    var ownContentId: String?
        get() = getKey<String>("sync_own_content_id")
        set(value) = setKey("sync_own_content_id", value)

    var lastPushedHash: String?
        get() = getKey<String>("sync_last_pushed_hash")
        set(value) = setKey("sync_last_pushed_hash", value)

    fun isLoggedIn(): Boolean =
        !token.isNullOrBlank() && !projectNum.isNullOrBlank()

    /**** Flags ****/

    fun isBackupEnabled(cat: SyncCategory): Boolean =
        getKey<String>(keyFor("sync_backup", cat)) == "true"

    fun setBackupEnabled(cat: SyncCategory, enabled: Boolean) =
        setKey(keyFor("sync_backup", cat), enabled.toString())

    fun isRestoreEnabled(cat: SyncCategory): Boolean =
        getKey<String>(keyFor("sync_restore", cat)) == "true"

    fun setRestoreEnabled(cat: SyncCategory, enabled: Boolean) =
        setKey(keyFor("sync_restore", cat), enabled.toString())

    /**** Timestamps (1e6 seconds) ****/

    fun categoryTimestamp(cat: SyncCategory): Long =
        (getKey<String>(keyFor("sync_ts", cat)) as? String)?.toLongOrNull() ?: 0L

    fun setCategoryTimestamp(cat: SyncCategory, ts: Long) =
        setKey(keyFor("sync_ts", cat), ts.toString())
}