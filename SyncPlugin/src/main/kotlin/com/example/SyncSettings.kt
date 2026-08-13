package com.example

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

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

        addSectionTitle("Categorías")
        val catCheckboxes = mutableMapOf<SyncCategory, Pair<CheckBox, CheckBox>>()
        for (cat in SyncCategory.entries) {
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
        addBody("Sincroniza cada 30s mientras la app esté abierta. Los cambios locales se suben ~2s después de producirse.")

        val save = AppCompatButton(ctx).apply {
            text = "Guardar y sincronizar"
            isAllCaps = false
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            ViewCompat.setBackgroundTintList(
                this,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, android.R.color.holo_blue_dark)),
            )
            stateListAnimator = null
            setPadding(dpToPx(ctx, 16), dpToPx(ctx, 12), dpToPx(ctx, 16), dpToPx(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPx(ctx, 16) }
            setOnClickListener {
                SyncStorage.token = tokenInput.text.toString().trim().ifEmpty { null }
                SyncStorage.projectNum = projectNumInput.text.toString().trim().ifEmpty { null }
                for ((cat, pair) in catCheckboxes) {
                    SyncStorage.setBackupEnabled(cat, pair.first.isChecked)
                    SyncStorage.setRestoreEnabled(cat, pair.second.isChecked)
                }
                SyncStorage.projectId = null
                SyncStorage.ownItemId = null
                SyncStorage.ownContentId = null
                if (!SyncStorage.isLoggedIn()) {
                    plugin.activity?.let {
                        android.widget.Toast.makeText(
                            it, "Completa el token y número de proyecto", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@setOnClickListener
                }
                plugin.forceSync(showToastResult = true)
            }
        }
        root.addView(save)

        AlertDialog.Builder(activity)
            .setTitle("CloudStream Sync")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .show()
    }
}