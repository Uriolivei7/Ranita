package com.cloudsync.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudsync.model.*
import com.cloudsync.provider.CloudSyncProvider
import com.cloudsync.storage.CloudSyncStorage
import com.lagradost.api.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CloudSyncSettingsDialog(private val activity: AppCompatActivity) {
    
    private var creds: CloudSyncCreds = CloudSyncStorage.getCreds() ?: CloudSyncCreds()
    private var firebaseUrlInput: android.widget.EditText? = null
    private var syncKeyInput: android.widget.EditText? = null
    private var deviceNameInput: android.widget.EditText? = null
    
    private val categorySwitches = mutableMapOf<SyncCategory, Switch>()
    private val categoryRestoreSwitches = mutableMapOf<SyncCategory, Switch>()
    private val subCategorySwitches = mutableMapOf<SettingsSubCategory, Switch>()
    private val subCategoryRestoreSwitches = mutableMapOf<SettingsSubCategory, Switch>()
    
    private var progressDialog: ProgressDialog? = null
    
    fun show() {
        val scrollView = ScrollView(activity)
        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(mainLayout)
        
        mainLayout.addView(mkTitle("CloudSync Settings"))
        mainLayout.addView(mkSection("Connection"))
        addConnectionFields(mainLayout)
        
        mainLayout.addView(mkSection("Categories to Backup"))
        addCategoryToggles(mainLayout, true)
        
        mainLayout.addView(mkSection("Categories to Restore"))
        addCategoryToggles(mainLayout, false)
        
        mainLayout.addView(mkSection("Settings Subcategories (Backup)"))
        addSubCategoryToggles(mainLayout, true)
        
        mainLayout.addView(mkSection("Settings Subcategories (Restore)"))
        addSubCategoryToggles(mainLayout, false)

        val buttonLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 32, 0, 0)
            weightSum = 2f
        }
        
        val cancelButton = mkButton("Cancel") { }
        cancelButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { gravity = Gravity.LEFT }
        
        val saveButton = mkButton("Save & Sync") { saveAndSync() }
        saveButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { gravity = Gravity.RIGHT }
        
        buttonLayout.addView(cancelButton)
        buttonLayout.addView(saveButton)
        mainLayout.addView(buttonLayout)
        
        val dialog = AlertDialog.Builder(activity)
            .setView(scrollView)
            .setCancelable(true)
            .create()
        
        dialog.show()
    }
    
    private fun mkTitle(titleText: String): TextView {
        return TextView(activity).apply {
            text = titleText
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    
    private fun mkSection(sectionText: String): TextView {
        return TextView(activity).apply {
            text = sectionText
            textSize = 16f
            setPadding(0, 24, 0, 12)
            setTextColor(Color.parseColor("#FFD700"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    
    private fun addConnectionFields(layout: LinearLayout) {
        val firebaseUrl = mkInput("Firebase URL", creds.firebaseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        firebaseUrlInput = firebaseUrl
        layout.addView(mkLabeled("Firebase Realtime Database URL:", firebaseUrl))
        
        val syncKey = mkInput("Sync Key", creds.syncKey ?: "", InputType.TYPE_CLASS_TEXT).apply { hint = "Leave empty to generate new" }
        syncKeyInput = syncKey
        layout.addView(mkLabeled("Sync Key (shared secret):", syncKey))
        
        val deviceName = mkInput("Device Name", creds.deviceName ?: "Device-${creds.deviceId.take(8)}", InputType.TYPE_CLASS_TEXT)
        deviceNameInput = deviceName
        layout.addView(mkLabeled("Device Name:", deviceName))
    }
    
    private fun mkInput(hint: String, text: String, inputType: Int): EditText {
        return EditText(activity).apply {
            this.hint = hint
            setText(text)
            this.inputType = inputType
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(Color.parseColor("#2A2A2A"))
                setStroke(2, Color.parseColor("#444444"))
            }
            background = bg
            setPadding(16, 16, 16, 16)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun mkLabeled(label: String, field: EditText): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
            addView(TextView(activity).apply {
                text = label
                textSize = 14f
                setPadding(0, 0, 0, 8)
                setTextColor(Color.parseColor("#BBBBBB"))
            })
            addView(field)
        }
    }
    
    private fun addCategoryToggles(layout: LinearLayout, isBackup: Boolean) {
        val map = if (isBackup) categorySwitches else categoryRestoreSwitches
        val enabledMap = if (isBackup) 
            { cat: SyncCategory -> creds.isBackupEnabled(cat) } 
        else 
            { cat: SyncCategory -> creds.isRestoreEnabled(cat) }
        
        SyncCategory.values().forEach { cat ->
            val enabled = enabledMap(cat)
            val switch = mkSwitch(cat.key.replace("_", " ").capitalize(), enabled)
            map[cat] = switch
            layout.addView(switch)
        }
    }
    
    private fun addSubCategoryToggles(layout: LinearLayout, isBackup: Boolean) {
        val map = if (isBackup) subCategorySwitches else subCategoryRestoreSwitches
        val enabledMap = if (isBackup) 
            { sub: SettingsSubCategory -> creds.isSettingsBackupEnabled(sub) } 
        else 
            { sub: SettingsSubCategory -> creds.isSettingsRestoreEnabled(sub) }
        
        SettingsSubCategory.values().forEach { sub ->
            val enabled = enabledMap(sub)
            val switch = mkSwitch(sub.name.capitalize(), enabled)
            map[sub] = switch
            layout.addView(switch)
        }
    }
    
    private fun mkSwitch(switchText: String, checked: Boolean): Switch {
        return Switch(activity).apply {
            text = switchText
            textSize = 16f
            isChecked = checked
            setPadding(0, 8, 0, 8)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun mkButton(buttonText: String, onClick: () -> Unit): Button {
        return android.widget.Button(activity).apply {
            text = buttonText
            setTextColor(Color.WHITE)
            setAllCaps(false)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(Color.parseColor("#007AFF"))
            }
            background = bg
            setPadding(48, 16, 48, 16)
            setOnClickListener { onClick() }
        }
    }
    
    private fun showProgress(message: String) {
        Log.d("CloudSync", "showProgress: $message")
        progressDialog?.dismiss()
        progressDialog = ProgressDialog(activity).apply {
            setMessage(message)
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setIndeterminate(true)
        }
        progressDialog?.show()
    }
    
    private fun hideProgress() {
        Log.d("CloudSync", "hideProgress")
        progressDialog?.dismiss()
        progressDialog = null
    }
    
    private fun saveAndSync() {
        Log.d("CloudSync", "saveAndSync: starting - creds.syncKey=${creds.syncKey}, loggedIn=${creds.isLoggedIn()}")
        showProgress("Sincronizando...")

        val userEnteredSyncKey = syncKeyInput?.text.toString().trim()
        val finalSyncKey = if (userEnteredSyncKey.isNotBlank()) {
            userEnteredSyncKey
        } else if (creds.syncKey?.isNotBlank() == true) {
            creds.syncKey!!
        } else {
            java.util.UUID.randomUUID().toString()
        }
        
        var newCreds = creds.copyWith(
            firebaseUrl = firebaseUrlInput?.text.toString().trim().ifEmpty { creds.firebaseUrl },
            syncKey = finalSyncKey,
            deviceName = deviceNameInput?.text.toString().trim().ifEmpty { creds.deviceName },
        )
        
        categorySwitches.forEach { (cat, switch) ->
            newCreds = when (cat) {
                SyncCategory.BOOKMARKS -> newCreds.copy(backupBookmarks = switch.isChecked)
                SyncCategory.RESUME_WATCHING -> newCreds.copy(backupResumeWatching = switch.isChecked)
                SyncCategory.EXTENSIONS -> newCreds.copy(backupExtensions = switch.isChecked)
                SyncCategory.SEARCH_HISTORY -> newCreds.copy(backupSearchHistory = switch.isChecked)
                SyncCategory.SETTINGS -> newCreds.copy(
                    backupPlayer = subCategorySwitches[SettingsSubCategory.PLAYER]?.isChecked ?: newCreds.backupPlayer,
                    backupSubtitles = subCategorySwitches[SettingsSubCategory.SUBTITLES]?.isChecked ?: newCreds.backupSubtitles,
                    backupTheme = subCategorySwitches[SettingsSubCategory.THEME]?.isChecked ?: newCreds.backupTheme,
                    backupLayout = subCategorySwitches[SettingsSubCategory.LAYOUT]?.isChecked ?: newCreds.backupLayout,
                    backupDownloads = subCategorySwitches[SettingsSubCategory.DOWNLOADS]?.isChecked ?: newCreds.backupDownloads,
                    backupGeneral = subCategorySwitches[SettingsSubCategory.GENERAL]?.isChecked ?: newCreds.backupGeneral,
                )
                else -> newCreds
            }
        }
        
        categoryRestoreSwitches.forEach { (cat, switch) ->
            newCreds = when (cat) {
                SyncCategory.BOOKMARKS -> newCreds.copy(restoreBookmarks = switch.isChecked)
                SyncCategory.RESUME_WATCHING -> newCreds.copy(restoreResumeWatching = switch.isChecked)
                SyncCategory.EXTENSIONS -> newCreds.copy(restoreExtensions = switch.isChecked)
                SyncCategory.SEARCH_HISTORY -> newCreds.copy(restoreSearchHistory = switch.isChecked)
                SyncCategory.SETTINGS -> newCreds.copy(
                    restorePlayer = subCategoryRestoreSwitches[SettingsSubCategory.PLAYER]?.isChecked ?: newCreds.restorePlayer,
                    restoreSubtitles = subCategoryRestoreSwitches[SettingsSubCategory.SUBTITLES]?.isChecked ?: newCreds.restoreSubtitles,
                    restoreTheme = subCategoryRestoreSwitches[SettingsSubCategory.THEME]?.isChecked ?: newCreds.restoreTheme,
                    restoreLayout = subCategoryRestoreSwitches[SettingsSubCategory.LAYOUT]?.isChecked ?: newCreds.restoreLayout,
                    restoreDownloads = subCategoryRestoreSwitches[SettingsSubCategory.DOWNLOADS]?.isChecked ?: newCreds.restoreDownloads,
                    restoreGeneral = subCategoryRestoreSwitches[SettingsSubCategory.GENERAL]?.isChecked ?: newCreds.restoreGeneral,
                )
                else -> newCreds
            }
        }
        
        creds = newCreds
        CloudSyncStorage.setCreds(creds)
        Log.d("CloudSync", "Settings saved - new syncKey=${creds.syncKey}")
        
        activity.runOnUiThread {
            val msg = if (creds.syncKey?.isNotBlank() == true) {
                "Sync Key: ${creds.syncKey}\nCópiala en el otro dispositivo"
            } else {
                "Guardando y sincronizando..."
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
        
        showProgress("Sincronizando...")
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                Log.d("CloudSync", "Starting CloudSyncProvider().startSync()")
                CloudSyncProvider().startSync(activity) { success, err ->
                    activity.runOnUiThread {
                        hideProgress()
                        if (success) Toast.makeText(activity, "✅ Sincronización completada", Toast.LENGTH_LONG).show()
                        else Toast.makeText(activity, "❌ Error: $err", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Sync exception: ${e.message}")
                activity.runOnUiThread {
                    hideProgress()
                    Toast.makeText(activity, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}