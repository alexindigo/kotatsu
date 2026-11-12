package com.gezimos.katapult.lockscreen

import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.gezimos.katapult.R
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.ui.Appearance
import com.gezimos.katapult.ui.HomeMenu
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.settings.PaperSheet
import com.gezimos.katapult.util.PrefsManager

/** Reuses the service page owner, but leaves the app's window/focus/task untouched. */
internal class HomeMenuOverlay(private val context: Context, private val handler: Handler,
    eligible: () -> Boolean) : AutoCloseable {
    private val prefs = PrefsManager(context)
    private val page = EdgePageWindow(context, handler, eligible, {}, "Kotatsu Home menu",
        focusable = false, invertOnOpen = false, swipeClose = false, fitSystemBars = true) { _, _, _ ->
        object : EdgePageView(context, ::close, { _, _ -> false }, { _, _ -> false }) {
            private val paper = ComposeView(context)
            init {
                paper.setContent {
                    CompositionLocalProvider(LocalIndication provides NoIndication) {
                        Appearance.Provide(Appearance.Surface.APP) {
                            PaperSheet(::close, Modifier.semantics { paneTitle = context.getString(R.string.home_button_menu) }) {
                                HomeMenu(prefs.wallpaperSource, external = true,
                                    onSettings = { open(MainActivity.HOME_MENU_SETTINGS) },
                                    onWallpaper = { open(MainActivity.HOME_MENU_WALLPAPER) },
                                    onAllApps = if (prefs.hideAllAppsButton) {
                                        { open(MainActivity.HOME_MENU_ALL_APPS) }
                                    } else null)
                            }
                        }
                    }
                }
                addView(paper, LayoutParams(-1, -1))
            }
            override fun dispose() = paper.disposeComposition()
        }
    }
    val visible get() = page.opened
    fun show() = page.show()
    private fun open(destination: String) {
        close()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_HOME_MENU, destination))
    }
    override fun close() { handler.post { page.hide() } }
}
