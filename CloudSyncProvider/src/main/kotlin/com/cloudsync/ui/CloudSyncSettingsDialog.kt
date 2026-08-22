package com.cloudsync.ui

import android.app.AlertDialog
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
import com.cloudsync.storage.CloudSyncStorage
import com.lagradost.api.Log

class CloudSyncSettingsDialog(private val activity: AppCompatActivity) {
    
    private var creds: CloudSyncCreds = CloudSyncStorage.getCreds() ?: CloudSyncCreds()
    private var firebaseUrlInput: android.widget.EditText? = null
    private var syncKeyInput: android.widget.EditText? = null
    private var deviceNameInput: android.widget.EditText? = null
    
    private val categorySwitches = mutableMapOf<SyncCategory, Switch>()
    private val categoryRestoreSwitches = mutableMapOf<SyncCategory, Switch>()
    private val subCategorySwitches = mutableMapOf<SettingsSubCategory, Switch>()
    private val subCategoryRestoreSwitches = mutableMapOf<SettingsSubCategory, Switch>()
    
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
        }
        
        val saveButton = mkButton("Save & Sync") { saveAndSync() }
        val cancelButton = mkButton("Cancel") { }
        
        buttonLayout.addView(saveButton)
        buttonLayout.addView(cancelButton)
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
        firebaseUrlInput = mkInput("Firebase URL", creds.firebaseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        layout.addView(mkLabeled("Firebase Realtime Database URL:", firebaseUrlInput!!))
        
        syncKeyInput = mkInput("Sync Key", creds.syncKey ?: "", InputType.TYPE_CLASS_TEXT).apply { hint = "Leave empty to generate new" }
        layout.addView(mkLabeled("Sync Key (shared secret):", syncKeyInput!!))
        
        deviceNameInput = mkInput("Device Name", creds.deviceName ?: "Device-${creds.deviceId.take(8)}", InputType.TYPE_CLASS_TEXT)
        layout.addView(mkLabeled("Device Name:", deviceNameInput!!))
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
    
    private fun saveAndSync() {
        var newCreds = creds.copyWith(
            firebaseUrl = firebaseUrlInput?.text.toString().trim().ifEmpty { creds.firebaseUrl },
            syncKey = syncKeyInput?.text.toString().trim().takeIf { it.isNotBlank() },
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
        com.lagradost.api.Log.d("CloudSync", "Settings saved")
        
        activity.runOnUiThread(Runnable { 
            Toast.makeText(activity, "Settings saved", Toast.LENGTH_SHORT).show()
        })
    }
}