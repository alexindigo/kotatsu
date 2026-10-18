package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import com.gezimos.katapult.lockscreen.ScreensaverView
import com.gezimos.katapult.service.BadgeFeed
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.util.AppLoader
import com.gezimos.katapult.util.AppVisibility
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenAppsNotifyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = PrefsManager(context)
    private val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private val ui = PlatformUiDriver(instrumentation)
    private val visibilityKeys = listOf("hidden_apps", "shown_default_hidden_apps", "notify_while_hidden_apps", "lockscreen_widget_excluded")

    @Test fun hiddenAppsOfferIndependentNotifyChoiceAndRespectExclusion() = preserving(visibilityKeys) {
        val app = AppLoader.loadApps(context).filter { !it.stock }.sortedBy { it.label.lowercase() }.first()
        instrumentation.runOnMainSync {
            prefs.setAppHidden(app.key, false, AppVisibility.hiddenByDefault(app))
            prefs.setNotifyWhileHidden(app.packageName, false)
            prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() - app.packageName)
        }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
                activity.setContent {
                    SettingsScreen(viewModel, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker)
                }
            }
            ui.click(context.getString(R.string.section_all_apps))
            ui.click(context.getString(R.string.hidden_apps))
            ui.await("App missing from first Hidden Apps page") { ui.has(app.label) }
            val notify = context.getString(R.string.hidden_apps_notify_column) + ": " + app.label
            assertFalse("Visible app has a Notify box", ui.has(notify))
            ui.click(app.label)
            ui.await("Hiding did not reveal an unticked Notify box") { ui.checked(notify) == false }
            assertTrue(AppVisibility.isHidden(app, prefs.getHiddenApps(), prefs.getShownDefaultHiddenApps()))
            assertFalse(app.packageName in prefs.getNotifyWhileHiddenApps())

            // Headers and their cells must share centerlines, with a full-height 64dp touch column.
            val header = Rect().also { ui.find(context.getString(R.string.hidden_apps_notify_column))!!.getBoundsInScreen(it) }
            val cell = Rect().also { ui.find(notify)!!.getBoundsInScreen(it) }
            val row = Rect().also { ui.findAncestor(app.label) { it.isClickable }!!.getBoundsInScreen(it) }
            assertEquals(header.exactCenterX(), cell.exactCenterX(), 1f)
            assertEquals(64f * context.resources.displayMetrics.density, cell.width().toFloat(), 1f)
            assertEquals(row.height(), cell.height())

            ui.click(notify)
            ui.await("Notify opt-in did not persist") { app.packageName in prefs.getNotifyWhileHiddenApps() && ui.checked(notify) == true }
            assertTrue("Notify tap toggled Hide", AppVisibility.isHidden(app, prefs.getHiddenApps(), prefs.getShownDefaultHiddenApps()))
            ui.click(app.label)
            ui.await("Unhiding left a Notify box") { !ui.has(notify) }
            assertTrue("Unhiding discarded the Notify choice", app.packageName in prefs.getNotifyWhileHiddenApps())
            ui.click(app.label)
            ui.await("Rehiding did not restore the Notify choice") { ui.checked(notify) == true }

            instrumentation.runOnMainSync { prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() + app.packageName) }
            ui.await("Excluded app's Notify box was not disabled and unticked") {
                ui.find(notify)?.isEnabled == false && ui.checked(notify) == false
            }
            val before = raw.all
            ui.click(notify)
            instrumentation.waitForIdleSync()
            assertEquals("Disabled Notify tap changed preferences or fell through to Hide", before, raw.all)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun screensaverRendersHiddenNotificationOnlyAfterOptIn() = preserving(visibilityKeys + listOf(
        "screensaver_enabled", "screensaver_show_notifications", "screensaver_update_mode",
    )) {
        val app = AppLoader.loadApps(context).first()
        instrumentation.runOnMainSync {
            prefs.setAppHidden(app.key, true, AppVisibility.hiddenByDefault(app))
            prefs.setNotifyWhileHidden(app.packageName, false)
            prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() - app.packageName)
            prefs.screensaverEnabled = true
            prefs.screensaverShowNotifications = true
            prefs.screensaverUpdateMode = PrefsManager.SCREENSAVER_MODE_STATIC
        }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_VIEW).apply {
            setClassName(context.packageName, ScreensaverActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as ScreensaverActivity
        val feed = object : BadgeFeed {
            override fun acquire(owner: Any) = AutoCloseable { }
            override fun subscribe(changed: () -> Unit) = AutoCloseable { }
            override fun counts() = mapOf(app.packageName to 987)
            override fun times() = emptyMap<String, Long>()
        }
        try {
            instrumentation.runOnMainSync { activity.setContent { ScreensaverView(feed) } }
            instrumentation.waitForIdleSync()
            assertFalse("Hidden notification rendered before opt-in", ui.has("987"))
            instrumentation.runOnMainSync { prefs.setNotifyWhileHidden(app.packageName, true) }
            ui.await("Opt-in did not reach the rendered screensaver consumer") { ui.has(app.label) && ui.has("987") }
            instrumentation.runOnMainSync { prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() + app.packageName) }
            ui.await("Exclusion did not clear the rendered screensaver consumer") { !ui.has("987") }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** Restore presence as well as values in one synchronous transaction before the runner can exit. */
    private fun preserving(keys: List<String>, block: () -> Unit) {
        val saved = keys.associateWith { raw.all[it] }
        assertTrue(saved.values.all { it == null || it is Set<*> || it is Boolean || it is Int })
        try { block() } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                saved.forEach { (key, value) ->
                    when (value) {
                        is Set<*> -> {
                            assertTrue(value.all { it is String })
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        else -> editor.remove(key)
                    }
                }
                assertTrue("Could not restore hidden notification preferences", editor.commit())
            }
        }
    }
}
