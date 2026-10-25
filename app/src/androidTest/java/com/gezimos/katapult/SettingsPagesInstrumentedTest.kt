package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPagesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun text(id: Int) = context.getString(id)
    private fun next() = ui.click(text(R.string.paper_next_page))
    private fun reveal(label: String) {
        repeat(12) {
            if (ui.has(label)) return
            assertTrue("No page contains $label", ui.has(text(R.string.paper_next_page)))
            next()
            instrumentation.waitForIdleSync()
        }
        fail("Page did not reveal $label")
    }
    private fun withSettings(block: (MainActivity, MainViewModel) -> Unit) {
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = raw.all
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try {
            val model = ViewModelProvider(activity)[MainViewModel::class.java]
            instrumentation.runOnMainSync {
                model.screen = Screen.SETTINGS
                model.resolveLockscreenReEnable()
                activity.setContent { SettingsScreen(model, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
            }
            ui.await("Settings contents absent") { ui.has(text(R.string.section_system)) }
            block(activity, model)
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                val editor = raw.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(editor.commit())
            }
        }
    }

    @Test fun confirmationBackPopsOneLevelAndRetainsChapterPage() = withSettings { _, model ->
        val before = context.getSharedPreferences("katapult_prefs", 0).all
        ui.click(text(R.string.section_system))
        next()
        ui.click(text(R.string.config_backup))
        ui.await("Config page missing") { ui.has(text(R.string.paper_export_help)) }
        assertFalse("Parent chapter leaked behind page", ui.has(text(R.string.lic_app_title)))
        reveal(text(R.string.paper_clear_help))
        ui.click(text(R.string.config_clear))
        ui.await("Confirmation missing") { ui.has(text(R.string.config_clear_confirm)) }
        assertFalse(ui.has(text(R.string.paper_export_help)))
        ui.back()
        ui.await("Back skipped Config parent") { ui.has(text(R.string.paper_export_help)) }
        ui.click(text(R.string.paper_back))
        ui.await("Chapter page number was lost") { ui.has(context.getString(R.string.paper_page, 2, 2)) && ui.has(text(R.string.config_backup)) }
        assertEquals(before, context.getSharedPreferences("katapult_prefs", 0).all)
        assertEquals(Screen.SETTINGS, model.screen)
        ui.back()
        ui.await("Chapter Back missed contents") { ui.has(text(R.string.section_appearance)) }
    }

    @Test fun choiceReturnsWithAppliedValueAndHelpReturnsToTheSameChapterPage() = withSettings { _, model ->
        ui.click(text(R.string.section_appearance))
        next()
        ui.click(text(R.string.theme_mode))
        ui.await("Choice page missing") { ui.checked(text(R.string.theme_light)) != null }
        assertFalse("Chapter rows remained behind choice", ui.has(text(R.string.eink_refresh)))
        ui.click(text(R.string.theme_light))
        ui.await("Choice did not apply and return to chapter page2") {
            model.prefs.themeMode == PrefsManager.THEME_LIGHT &&
                ui.has(context.getString(R.string.paper_page, 2, 2)) && ui.has(text(R.string.eink_refresh))
        }
        ui.click(text(R.string.paper_about_eink))
        ui.await("Help page missing") { !ui.has(text(R.string.eink_refresh)) && ui.has(text(R.string.paper_back)) }
        ui.click(text(R.string.paper_back))
        ui.await("Help Back lost chapter page2") { ui.has(context.getString(R.string.paper_page, 2, 2)) && ui.has(text(R.string.eink_refresh)) }
    }

    @Test fun managementAndServicePagesReturnToTheirImmediateParents() = withSettings { _, model ->
        ui.click(text(R.string.section_all_apps))
        for (entry in listOf(R.string.hidden_apps)) {
            ui.click(text(entry))
            ui.await("Visibility page not opened") { ui.has(text(R.string.hidden_apps_notify_desc)) }
            assertFalse(ui.has(text(R.string.paper_icon_shape_help)))
            assertFalse("All Apps chapter remained visible", ui.has(text(R.string.app_sort_order)))
            ui.back()
            ui.await("Visibility Back skipped chapter") { ui.has(text(R.string.app_sort_order)) }
        }
        ui.back()
        ui.click(text(R.string.section_system))
        ui.click(text(R.string.sensitive_access))
        reveal(text(R.string.policy_grants_separate))
        val page = ui.find { it.contentDescription?.toString()?.startsWith("Page ") == true }?.contentDescription?.toString()
        ui.click(text(R.string.permissions))
        ui.await("Permissions missing") { ui.has(text(R.string.paper_permissions_hint)) }
        ui.back()
        ui.await("Permissions Back skipped Sensitive access page") {
            ui.has(text(R.string.policy_grants_separate)) && (page == null || ui.has(page))
        }
        ui.back()
        ui.click(text(R.string.permissions))
        ui.click(text(R.string.perm_action_service))
        ui.await("Service setup page missing") { model.actionServiceSetup != null && ui.has(text(R.string.a11y_setup_title)) }
        assertFalse(ui.has(text(R.string.paper_permissions_hint)))
        ui.back()
        ui.await("Setup Back skipped Permissions") { model.actionServiceSetup == null && ui.has(text(R.string.paper_permissions_hint)) }
    }

    @Test fun customizationStaysOpenWhileValuesPersistAndBackRestoresChapter() = withSettings { _, model ->
        // The production toggle is the actual entry point; it also covers the OFF page.
        ui.click(text(R.string.section_screensaver))
        ui.await("Screensaver chapter not ready") { ui.has(text(R.string.screensaver_enable_desc)) }
        if (!ui.has(text(R.string.screensaver_customization))) ui.click(text(R.string.screensaver_enable_desc))
        ui.click(text(R.string.screensaver_customization))
        val before = model.prefs.screensaverShowClock
        ui.click(text(R.string.screensaver_show_clock))
        ui.await("Multi-select value did not persist in place") {
            model.prefs.screensaverShowClock == !before && ui.checked(text(R.string.screensaver_show_clock)) == !before
        }
        assertTrue(ui.has(text(R.string.screensaver_show_notifications)))
        ui.back()
        ui.await("Customization Back skipped chapter") { ui.has(text(R.string.paper_screensaver_custom_help)) }
    }
}
