package com.gezimos.katapult.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import android.view.View
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.Appearance
import com.gezimos.katapult.ui.PaperPalette
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemChrome {
    fun isDark(context: Context, themeMode: Int): Boolean = com.gezimos.katapult.ui.Appearance.isDark(context, themeMode)

    fun apply(window: Window, surface: Appearance.Surface, hideStatusBar: Boolean, prefs: PrefsManager = PrefsManager(window.context)) {
        val decor = window.decorView
        (decor.getTag(R.id.paper_window_appearance) as? WindowAppearance)?.close()
        val binding = WindowAppearance(window, surface, hideStatusBar, prefs)
        decor.setTag(R.id.paper_window_appearance, binding)
        decor.addOnAttachStateChangeListener(binding)
        if (decor.isAttachedToWindow) binding.onViewAttachedToWindow(decor)
        else render(window, Appearance.palette(window.context, surface, prefs), hideStatusBar)
    }

    /** The decor owns this subscription; detached windows never retain a preference listener. */
    private class WindowAppearance(val window: Window, val surface: Appearance.Surface, val hideStatusBar: Boolean, val prefs: PrefsManager) :
        View.OnAttachStateChangeListener, AutoCloseable {
        private var subscription: AutoCloseable? = null
        override fun onViewAttachedToWindow(view: View) {
            subscription?.close()
            subscription = Appearance.observe(window.context, surface, prefs) { render(window, it, hideStatusBar) }
        }
        override fun onViewDetachedFromWindow(view: View) { subscription?.close(); subscription = null }
        override fun close() {
            onViewDetachedFromWindow(window.decorView)
            window.decorView.removeOnAttachStateChangeListener(this)
        }
    }

    /** Compose-owned windows already observe their parent's palette. */
    fun apply(window: Window, palette: PaperPalette, hideStatusBar: Boolean, paintBackground: Boolean = true) {
        render(window, palette, hideStatusBar, paintBackground)
    }

    private fun render(window: Window, palette: PaperPalette, hideStatusBar: Boolean, paintBackground: Boolean = true) {
        val color = palette.paperArgb
        val dark = palette.isDark
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.statusBarColor = color
        window.navigationBarColor = color
        if (paintBackground) window.setBackgroundDrawable(ColorDrawable(color))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
            if (hideStatusBar) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
