package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import com.gezimos.katapult.setPaperContent as setContent
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
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = PrefsManager(context)
    private val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private val ui = PlatformUiDriver(instrumentation)
    private val visibilityKeys = listOf("hidden_apps", "shown_default_hidden_apps", "notify_while_hidden_apps", "lockscreen_widget_excluded")

    @Test fun hiddenCheckboxNamesAStateInEveryLocale() {
        mapOf("en" to "Hidden", "cs" to "Skrytá", "de" to "Versteckt", "es" to "Oculta",
            "fr" to "Masquée", "it" to "Nascosta", "nl" to "Verborgen", "pl" to "Ukryta", "pt" to "Oculta")
            .forEach { (language, state) ->
                val config = android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(language))
                }
                val localized = context.createConfigurationContext(config)
                assertEquals(language, state, localized.getString(R.string.hidden_apps_hidden_column))
                val separator = if (language == "fr") " : " else ": "
                assertEquals(language, "$state${separator}Sample", localized.getString(R.string.paper_hidden_app, "Sample"))
            }
    }

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
        withFixtureCleanup(body = {
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
            val blankHeader = Rect().also { ui.find(context.getString(R.string.hidden_apps_notify_column))!!.getBoundsInScreen(it) }
            val visibleName = Rect().also { ui.find(app.label)!!.getBoundsInScreen(it) }
            val beforeBlank = raw.all
            tap(blankHeader.centerX().toFloat(), visibleName.centerY().toFloat())
            instrumentation.waitForIdleSync()
            assertEquals("Blank Notify tap changed preferences or fell through to Hide", beforeBlank, raw.all)
            ui.click(app.label)
            ui.await("Hiding did not reveal an unticked Notify box") { ui.checked(notify) == false }
            assertTrue(AppVisibility.isHidden(app, prefs.getHiddenApps(), prefs.getShownDefaultHiddenApps()))
            assertFalse(app.packageName in prefs.getNotifyWhileHiddenApps())

            // The drawing stays centred in its 64dp matrix column; its independent
            // hit region now also owns the leader, rather than shortening the leader.
            val header = Rect().also { ui.find(context.getString(R.string.hidden_apps_notify_column))!!.getBoundsInScreen(it) }
            assertEquals("Shared column heading retains the matrix width", 64f * context.resources.displayMetrics.density,
                header.width().toFloat(), 1f)
            val cell = Rect().also { ui.find(notify)!!.getBoundsInScreen(it) }
            val row = Rect().also { ui.findAncestor(app.label) { it.isClickable }!!.getBoundsInScreen(it) }
            val box = indexDrawingBounds("paper_index_checkbox").filter {
                it.center.y >= cell.top && it.center.y <= cell.bottom
            }.maxBy { it.right }
            assertEquals(header.exactCenterX(), box.center.x, 1f)
            assertTrue("Notify target must retain its entire column", cell.width() >= 64f * context.resources.displayMetrics.density - 1f)
            assertEquals(row.height(), cell.height())
            assertTrue("Notify cell is shorter than 64dp", cell.height() >= 64f * context.resources.displayMetrics.density - 1f)
            val hide = context.getString(R.string.paper_hidden_app, app.label)
            val hideHeader = Rect().also { ui.find(context.getString(R.string.hidden_apps_hidden_column))!!.getBoundsInScreen(it) }
            val hideCell = Rect().also { ui.findAncestor(hide) { it.isClickable }!!.getBoundsInScreen(it) }
            assertEquals(hideHeader.exactCenterX(), hideCell.exactCenterX(), 1f)
            assertEquals(64f * context.resources.displayMetrics.density, hideCell.width().toFloat(), 1f)
            assertEquals(cell.height(), hideCell.height())
            assertEquals(true, ui.checked(hide))

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
        }, actions = arrayOf<() -> Unit>({ instrumentation.runOnMainSync { activity.finish() } }))
    }

    private fun tap(x: Float, y: Float) {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true))
            event.recycle()
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
        withFixtureCleanup(body = {
            instrumentation.runOnMainSync { activity.setContent { ScreensaverView(feed) } }
            instrumentation.waitForIdleSync()
            assertFalse("Hidden notification rendered before opt-in", ui.has("987"))
            instrumentation.runOnMainSync { prefs.setNotifyWhileHidden(app.packageName, true) }
            ui.await("Opt-in did not reach the rendered screensaver consumer") { ui.has(app.label) && ui.has("987") }
            instrumentation.runOnMainSync { prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() + app.packageName) }
            ui.await("Exclusion did not clear the rendered screensaver consumer") { !ui.has("987") }
        }, actions = arrayOf<() -> Unit>({ instrumentation.runOnMainSync { activity.finish() } }))
    }

    /** Restore presence as well as values in one synchronous transaction before the runner can exit. */
    private fun preserving(keys: List<String>, block: () -> Unit) {
        val saved = keys.associateWith { raw.all[it] }
        assertTrue(saved.values.all { it == null || it is Set<*> || it is Boolean || it is Int })
        withFixtureCleanup({ restoreFixturePreferenceValues(raw, saved) }) { block() }
    }
}
