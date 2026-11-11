package com.alexindigo.kotatsu.notes

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import com.gezimos.katapult.ui.LocalPalette

/** Activity policy, matching Kotatsu's App: the kit's ink ring is the only focus indication. */
private object NotesNoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) = object : Modifier.Node() {}
    override fun hashCode() = 0
    override fun equals(other: Any?) = other === this
}

open class NotesSettingsActivity : ComponentActivity() {
    protected open val kind: WidgetKind? = null
    private lateinit var owner: NotesSettingsOwner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        val palette = NotesAppearance.palette(this)
        window.setBackgroundDrawable(ColorDrawable(palette.paperArgb))
        window.statusBarColor = palette.paperArgb
        window.navigationBarColor = palette.paperArgb
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        owner = NotesSettingsOwner(this)
        setContent {
          CompositionLocalProvider(
            LocalOnBackPressedDispatcherOwner provides this@NotesSettingsActivity,
            LocalIndication provides NotesNoIndication,
            LocalPalette provides palette,
          ) {
            Box(Modifier.fillMaxSize().background(palette.paper)
                .statusBarsPadding().navigationBarsPadding()) {
                NotesSettingsBook(owner, kind, ::finish)
            }
          }
        }
        // Own initial native focus at the page boundary, not on its first control. Keyboard
        // traversal can still enter Compose; clearing child focus alone would auto-refocus it.
        findViewById<ViewGroup>(android.R.id.content).apply {
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        owner.refresh()
    }
}
