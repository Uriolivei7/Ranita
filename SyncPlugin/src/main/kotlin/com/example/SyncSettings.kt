package com.example

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

class SyncSettings(private val plugin: SyncPlugin) {

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()

    private fun isDarkTheme(ctx: android.content.Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun alphaOf(color: Int, alpha: Int): Int =
        color and 0x00FFFFFF or (alpha shl 24)

    private fun inputBackground(ctx: android.content.Context, corner: Float): GradientDrawable {
        val isDark = isDarkTheme(ctx)
        val fill = ContextCompat.getColor(ctx, android.R.color.white)
        val strokeColor = if (isDark) alphaOf(fill, 0x33) else alphaOf(0xFF000000.toInt(), 0x26)
        return GradientDrawable().apply {
            cornerRadius = corner
            setColor(if (isDark) alphaOf(fill, 0x1F) else alphaOf(0xFF000000.toInt(), 0x0D))
            setStroke(dpToPx(ctx, 1), strokeColor)
        }
    }

    fun show(activity: AppCompatActivity) {
        val ctx: android.content.Context = activity
        val corner = dpToPx(ctx, 12).toFloat()
        val inputBg = inputBackground(ctx, corner)

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(ctx, 20), dpToPx(ctx, 12), dpToPx(ctx, 20), dpToPx(ctx, 12))
        }
        scroll.addView(root)

        fun addSectionTitle(text: String) {
            root.addView(AppCompatTextView(ctx).apply {
                this.text = text
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
                setPadding(0, dpToPx(ctx, 16), 0, dpToPx(ctx, 6))
            })
        }

        fun addBody(text: String, size: Float = 13f) {
            root.addView(AppCompatTextView(ctx).apply {
                this.text = text
                textSize = size
                setLineSpacing(0f, 1.15f)
                setPadding(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
            })
        }

        fun addFieldLabel(text: String) {
            root.addView(AppCompatTextView(ctx).apply {
                this.text = text
                setTypeface(null, Typeface.BOLD)
                textSize = 13f
                setPadding(0, dpToPx(ctx, 10), 0, dpToPx(ctx, 4))
            })
        }

        fun addSpace(h: Int) = root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, dpToPx(ctx, h))
        })

        addSectionTitle("CloudStream Sync")
        addBody("Sincroniza favoritos, progreso, historial, repos y ajustes entre tus dispositivos usando un proyecto GitHub (ProjectV2).")
        addBody("Paso 1: crea un token en GitHub → Settings → Developer settings → Personal access tokens (classic) con permisos repo + read:org.")

        addSectionTitle("Conexión")

        addFieldLabel("Token de GitHub")
        val tokenInput = AppCompatEditText(ctx).apply {
            hint = "ghp_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
            setText(SyncStorage.token.orEmpty())
            background = inputBg
            setPadding(dpToPx(ctx, 12), dpToPx(ctx, 10), dpToPx(ctx, 12), dpToPx(ctx, 10))
        }
        root.addView(tokenInput)

        val showToken = CheckBox(ctx).apply {
            text = "Mostrar token"
            isChecked = false
            setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
                tokenInput.transformationMethod =
                    if (checked) null else PasswordTransformationMethod.getInstance()
                tokenInput.setSelection(tokenInput.text?.length ?: 0)
            }
        }
        root.addView(showToken)

        addFieldLabel("Número de proyecto")
        val projectNumInput = AppCompatEditText(ctx).apply {
            hint = "Ej: 3"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(SyncStorage.projectNum.orEmpty())
            background = inputBg
            setPadding(dpToPx(ctx, 12), dpToPx(ctx, 10), dpToPx(ctx, 12), dpToPx(ctx, 10))
        }
        root.addView(projectNumInput)

        addBody("Abre GitHub → Projects → tu proyecto, y mira la URL: el último número es el número de proyecto (ej: /projects/3)")

        addSpace(6)

        addSectionTitle("Estado / Diagnóstico")
        val statusView = AppCompatTextView(ctx).apply {
            text = plugin.lastStatus
            textSize = 13f
        }
        root.addView(statusView)
        val errorView = AppCompatTextView(ctx).apply {
            text = plugin.lastError?.let { "Último error: $it" } ?: ""
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#E5484D"))
            visibility = if (plugin.lastError == null) View.GONE else View.VISIBLE
        }
        root.addView(errorView)
        addBody(
            "Draft propio: " + if (SyncStorage.ownContentId == null) {
                "NO registrado (se creará al sincronizar)"
            } else {
                "registrado (${SyncStorage.ownChunkContentIds.size} trozo/s)"
            },
            12f,
        )
        addBody("Para ver todos los pasos: adb logcat -s SyncStream", 12f)

        addSpace(6)

        addSectionTitle("Categorías")
        val catCheckboxes = mutableMapOf<SyncCategory, Pair<CheckBox, CheckBox>>()
        for (cat in SyncCategory.entries) {
            if (cat == SyncCategory.SEARCH_HISTORY) continue
            val title = when (cat) {
                SyncCategory.SETTINGS -> "Ajustes"
                SyncCategory.BOOKMARKS -> "Favoritos (marcadores)"
                SyncCategory.RESUME_WATCHING -> "En curso / progreso"
                SyncCategory.SEARCH_HISTORY -> "Historial de búsqueda"
                SyncCategory.EXTENSIONS -> "Repos de extensiones"
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
            }
            row.addView(AppCompatTextView(ctx).apply {
                text = title
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val backupBox = CheckBox(ctx).apply {
                text = "Backup"
                isChecked = SyncStorage.isBackupEnabled(cat)
            }
            val restoreBox = CheckBox(ctx).apply {
                text = "Restaurar"
                isChecked = SyncStorage.isRestoreEnabled(cat)
            }
            row.addView(backupBox)
            row.addView(restoreBox)
            root.addView(row)
            catCheckboxes[cat] = backupBox to restoreBox
        }

        addSpace(6)

        addSectionTitle("Acciones")
        addBody("Sincroniza automáticamente cada 15s mientras la app esté abierta, al abrir la app y ~2s después de cada cambio local.")

        val syncRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(ctx, 8).toFloat()
                setColor(ContextCompat.getColor(ctx, android.R.color.holo_blue_dark))
            }
            setPadding(dpToPx(ctx, 16), dpToPx(ctx, 12), dpToPx(ctx, 16), dpToPx(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPx(ctx, 16) }
        }
        val syncSpinner = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, 22), dpToPx(ctx, 22))
        }
        val syncLabel = AppCompatTextView(ctx).apply {
            text = "Guardar y sincronizar"
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        syncRow.addView(syncSpinner)
        syncRow.addView(syncLabel)
        root.addView(syncRow)

        fun setSyncing(syncing: Boolean) {
            syncSpinner.visibility = if (syncing) View.VISIBLE else View.GONE
            syncLabel.text = if (syncing) "Sincronizando..." else "Guardar y sincronizar"
            syncRow.isEnabled = !syncing
            syncRow.isClickable = !syncing
            syncRow.alpha = if (syncing) 0.7f else 1f
        }

        syncRow.setOnClickListener {
            SyncStorage.token = tokenInput.text.toString().trim().ifEmpty { null }
            SyncStorage.projectNum = projectNumInput.text.toString().trim().ifEmpty { null }
            for ((cat, pair) in catCheckboxes) {
                SyncStorage.setBackupEnabled(cat, pair.first.isChecked)
                SyncStorage.setRestoreEnabled(cat, pair.second.isChecked)
            }
            SyncStorage.projectId = null
            SyncStorage.ownItemId = null
            SyncStorage.ownContentId = null
            SyncStorage.ownChunkContentIds = emptyMap()
            if (!SyncStorage.isLoggedIn()) {
                plugin.activity?.let {
                    android.widget.Toast.makeText(
                        it, "Completa el token y número de proyecto", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@setOnClickListener
            }
            setSyncing(true)
            plugin.forceSync(showToastResult = true)
        }

        val handler = Handler(Looper.getMainLooper())
        val statusRunnable = object : Runnable {
            override fun run() {
                setSyncing(plugin.isSyncing)
                statusView.text = plugin.lastStatus
                val err = plugin.lastError
                errorView.text = if (err == null) "" else "Último error: $err"
                errorView.visibility = if (err == null) View.GONE else View.VISIBLE
                handler.postDelayed(this, 250L)
            }
        }
        handler.post(statusRunnable)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("CloudStream Sync")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .show()
        dialog.setOnDismissListener {
            handler.removeCallbacks(statusRunnable)
        }
    }
}