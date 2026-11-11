package com.gezimos.katapult.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.gezimos.katapult.util.PrefsManager

/** Resolves both Compose and View surfaces, including changes while a window is open. */
object Appearance {
    enum class Surface { APP, LOCK_SCREEN, SCREENSAVER }

    fun isDark(context: Context, themeMode: Int): Boolean = when (themeMode) {
        PrefsManager.THEME_DARK -> true
        PrefsManager.THEME_SYSTEM -> context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    fun palette(context: Context, surface: Surface, prefs: PrefsManager = PrefsManager(context)): PaperPalette =
        if (surface != Surface.LOCK_SCREEN && isDark(context, prefs.themeMode))
            PaperPalette.Dark(context) else PaperPalette.Light(context)

    fun observe(context: Context, surface: Surface, prefs: PrefsManager = PrefsManager(context), changed: (PaperPalette) -> Unit): AutoCloseable {
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(configuration: Configuration) { changed(palette(context, surface, prefs)) }
            override fun onLowMemory() = Unit
        }
        context.registerComponentCallbacks(callbacks)
        val subscription = prefs.subscribeChanges { changed(palette(context, surface, prefs)) }
        changed(palette(context, surface, prefs))
        return AutoCloseable { subscription.close(); context.unregisterComponentCallbacks(callbacks) }
    }

    @Composable
    fun current(surface: Surface, prefs: PrefsManager? = null): PaperPalette {
        val context = LocalContext.current
        val preferences = prefs ?: remember(context) { PrefsManager(context) }
        var value by remember(context, surface, preferences) { mutableStateOf(palette(context, surface, preferences)) }
        DisposableEffect(context, surface, preferences) {
            val subscription = observe(context, surface, preferences) { value = it }
            onDispose { subscription.close() }
        }
        return value
    }

    @Composable
    fun Provide(surface: Surface, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalPalette provides current(surface), content = content)
    }
}
