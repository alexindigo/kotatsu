package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import com.gezimos.katapult.setPaperContent as setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperIndexSettingsInstrumentedTest {
    private fun withSettings(test: (MainActivity, MainViewModel, PrefsManager, PlatformUiDriver) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val shared = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = shared.all
        var activity: MainActivity? = null
        try {
            instrumentation.runOnMainSync {
                shared.edit().putBoolean("notice_action_service_dismissed", false)
                    .putInt("theme_mode", PrefsManager.THEME_LIGHT).putBoolean("hide_app_names", false)
                    .remove("home_button_recent_apps").remove("home_button_screenshot").commit()
            }
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            lateinit var vm: MainViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(current)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                current.setContent { SettingsScreen(vm, current.configExporter, current.configImporter, current.screensaverWallpaperPicker) }
            }
            test(current, vm, PrefsManager(context), PlatformUiDriver(instrumentation))
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                val edit = shared.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(edit.commit())
            }
        }
    }

    @Test fun chapterHasBothPagesAndBackReturnsToContents() = withSettings { _, _, _, ui ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ui.click(context.getString(R.string.section_appearance))
        ui.await("Icons page missing") { ui.has(context.getString(R.string.icon_shape)) }
        ui.click(context.getString(R.string.paper_next_page))
        ui.await("E-ink did not move into Appearance") { ui.has(context.getString(R.string.eink_refresh)) }
        assertTrue(ui.has(context.getString(R.string.theme_mode)))
        ui.click(context.getString(R.string.paper_previous_page))
        ui.await("Previous page missing") { ui.has(context.getString(R.string.icon_shape)) }
        ui.back()
        ui.await("Back did not return to Settings root") { ui.has(context.getString(R.string.section_system)) }
    }

    @Test fun contentsKeepsAllTenDestinationsOnOneScreen() = withSettings { _, _, _, ui ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val titles = listOf(R.string.section_appearance, R.string.section_home, R.string.section_all_apps,
            R.string.section_gestures, R.string.section_notifications, R.string.cat_lockscreen,
            R.string.section_screensaver, R.string.section_system, R.string.section_about,
            R.string.app_store_title).map { context.getString(it) }
        ui.await("Existing contents destinations are missing") { titles.all { ui.has(it) } }
        assertEquals(titles, ui.gridLabels(titles))
        assertFalse(ui.has(context.getString(R.string.paper_next_page)))
        assertFalse(ui.has(context.getString(R.string.paper_previous_page)))
        // E-ink remains in Appearance rather than regaining a root row.
        assertFalse(ui.has(context.getString(R.string.section_eink)))
    }

    @Test fun choiceAppliesAndRootSummaryObservesIt() = withSettings { _, vm, prefs, ui ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ui.click(context.getString(R.string.section_appearance))
        val title = context.getString(R.string.icon_shape)
        val current = context.getString(if (prefs.roundedIcons) R.string.icon_shape_rounded else R.string.icon_shape_circle)
        ui.await("Value row is missing its button role and current-value name") {
            val button = ui.findAncestor(title) { it.isClickable }
            // Compose exports role and contentDescription as synthetic children of a merging
            // control. Assert both within this actual clickable row, rather than assume either
            // property must be duplicated on its outer Android View.
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo,
                predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean {
                if (!node.refresh()) return false
                if (predicate(node)) return true
                return (0 until node.childCount).any { index -> node.getChild(index)?.let { contains(it, predicate) } == true }
            }
            button != null && contains(button) { it.className == "android.widget.Button" } &&
                contains(button) { it.contentDescription?.toString() == "$title, $current" }
        }
        ui.click(context.getString(R.string.icon_shape))
        val requested = !prefs.roundedIcons
        ui.click(context.getString(if (requested) R.string.icon_shape_rounded else R.string.icon_shape_circle))
        ui.await("Choice did not reach preferences and launcher consumer") { prefs.roundedIcons == requested && vm.roundedIcons == requested }
        ui.back()
        val shape = context.getString(if (requested) R.string.icon_shape_rounded else R.string.icon_shape_circle)
        ui.await("Root summary did not update") { ui.find { it.text?.contains(shape) == true } != null }
    }

    @Test fun muditaAppearanceKeepsWholeGroupsWithTheWallpaperEntry() = withSettings { activity, _, _, ui ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            activity.setContent {
                com.gezimos.katapult.ui.settings.PaperIndexRenderer(
                    com.gezimos.katapult.ui.settings.appearanceCategory(
                        isMudita = true, rounded = true, iconSize = 80, themeMode = PrefsManager.THEME_LIGHT,
                        hideAppNames = false, hideStatusBar = false, hideStatusClock = false, einkMode = 0, refreshHome = false,
                        onShape = {}, onSize = {}, onTheme = {}, onHideNames = {}, onHideStatus = {}, onHideClock = {}, onEink = {}, onRefreshHome = {}),
                    onBack = {}, noticeGroup = "screen", modifier = Modifier.requiredSize(360.dp, 564.dp),
                    notice = com.gezimos.katapult.ui.settings.NoticeContent(
                        context.getString(R.string.paper_service_off), context.getString(R.string.paper_notice_region),
                        context.getString(R.string.paper_notice_dismiss), {}, context.getString(R.string.paper_turn_on), {}))
            }
        }
        ui.await("Expected three whole-group pages with the full Mudita chapter") { ui.has(context.getString(R.string.paper_page, 1, 3)) }
        ui.click(context.getString(R.string.paper_next_page))
        ui.await("Screen group lost its wallpaper entry") { ui.has(context.getString(R.string.wallpaper_help)) }
        assertTrue(ui.has(context.getString(R.string.hide_status_bar_clock)))
        assertTrue(ui.has(context.getString(R.string.theme_mode)))
        ui.click(context.getString(R.string.paper_next_page))
        ui.await("Last page lost the E-ink group") { ui.has(context.getString(R.string.eink_auto_mode)) && ui.has(context.getString(R.string.eink_refresh)) }
        assertFalse(ui.has(context.getString(R.string.paper_next_page)))
    }

    @Test fun switchHasCheckableStateAndPersistsItsChange() = withSettings { _, _, prefs, ui ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ui.click(context.getString(R.string.section_appearance))
        val title = context.getString(R.string.hide_app_names)
        ui.await("Switch lacks an accessible off state") { ui.checked(title) == false }
        ui.click(title)
        ui.await("Switch did not persist or announce on state") {
            prefs.hideAppNames && ui.checked(title) == true &&
                ui.findAncestor(title) { it.isCheckable }?.stateDescription == context.getString(R.string.paper_on)
        }
    }

    @Test fun gesturesHomeButtonsPersistAcrossWholeGroupPagesWithSharedNotice() = withSettings { activity, vm, prefs, ui ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(com.gezimos.katapult.lockscreen.LockscreenWidgetService.isEnabled(context))
        assertTrue(prefs.homeButtonRecentApps)
        assertTrue(prefs.homeButtonScreenshot)
        val title = context.getString(R.string.recent_apps)
        val screenshot = context.getString(R.string.home_button_screenshot)
        fun capture(name: String) {
            val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        val groups = linkedMapOf(
            context.getString(R.string.paper_group_app_pages) to listOf(R.string.swipe_up_all_apps, R.string.app_gesture_direction),
            context.getString(R.string.paper_group_on_home) to listOf(R.string.double_tap_action, R.string.home_long_press),
            context.getString(R.string.paper_group_home_button) to listOf(R.string.recent_apps, R.string.home_button_screenshot),
        ).mapValues { (_, entries) -> entries.map { context.getString(it) } }
        fun firstPage() {
            instrumentation.waitForIdleSync()
            repeat(12) {
                if (!ui.has(context.getString(R.string.paper_previous_page))) return
                ui.click(context.getString(R.string.paper_previous_page))
                instrumentation.waitForIdleSync()
            }
            fail("Gestures did not return to its first measured page")
        }
        fun reveal(label: String) {
            instrumentation.waitForIdleSync()
            if (ui.has(label)) return
            firstPage()
            repeat(12) {
                if (ui.has(label)) return
                assertTrue("No Gestures page contains $label", ui.has(context.getString(R.string.paper_next_page)))
                ui.click(context.getString(R.string.paper_next_page))
                instrumentation.waitForIdleSync()
            }
            fail("Gestures pages did not reveal $label")
        }
        fun assertMeasuredPages(noticeVisible: Boolean, capturePrefix: String) {
            firstPage()
            val seen = mutableListOf<String>()
            repeat(12) { page ->
                val visible = groups.keys.filter { ui.has(it) }
                assertTrue("Gestures page has no group", visible.isNotEmpty())
                assertEquals(visible, ui.gridLabels(visible))
                groups.forEach { (group, entries) -> entries.forEach { entry ->
                    assertEquals("Whole group $group was split at $entry", group in visible, ui.has(entry))
                } }
                seen += visible
                assertEquals("Shared Notice visibility changed between pages", noticeVisible, ui.has(context.getString(R.string.paper_service_off)))
                if (!noticeVisible) assertFalse(ui.has(context.getString(R.string.paper_notice_dismiss)))
                capture("$capturePrefix-${page + 1}.png")
                if (!ui.has(context.getString(R.string.paper_next_page))) {
                    assertEquals("Measured pages lost, duplicated or reordered groups", groups.keys.toList(), seen)
                    return
                }
                ui.click(context.getString(R.string.paper_next_page))
                instrumentation.waitForIdleSync()
            }
            fail("Gestures measured pages did not end")
        }
        fun openHomeButtons() {
            ui.click(context.getString(R.string.section_gestures))
            ui.await("Gestures missing") { ui.has(context.getString(R.string.paper_group_app_pages)) }
            reveal(title)
            ui.await("Home button group missing") { ui.checked(title) != null && ui.checked(screenshot) != null }
        }
        openHomeButtons()
        assertTrue(ui.checked(title) == true && ui.checked(screenshot) == true)
        assertMeasuredPages(true, "gestures-home-button")
        reveal(title)
        assertFalse(ui.has(context.getString(R.string.paper_next_page)))
        assertTrue(ui.has(context.getString(R.string.paper_service_off)))
        capture("gestures-home-button-2.png")
        ui.click(title)
        ui.await("Recent apps Off not saved or exposed") { !prefs.homeButtonRecentApps && ui.checked(title) == false }
        ui.click(screenshot)
        ui.await("Screenshot Off not saved or exposed") { !prefs.homeButtonScreenshot && ui.checked(screenshot) == false }
        assertFalse(PrefsManager(context).homeButtonRecentApps)
        assertFalse(PrefsManager(context).homeButtonScreenshot)
        assertFalse(org.json.JSONObject(prefs.exportToJson(context)).getBoolean("home_button_recent_apps"))
        assertFalse(org.json.JSONObject(prefs.exportToJson(context)).getBoolean("home_button_screenshot"))
        instrumentation.runOnMainSync { activity.setContent { androidx.compose.material3.Text("Closed settings") } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            activity.setContent { SettingsScreen(vm, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
        }
        openHomeButtons()
        ui.await("Recent apps Off lost on reopening") { ui.checked(title) == false }
        assertFalse(ui.checked(screenshot)!!)
        ui.click(title)
        ui.await("Recent apps On not saved") { prefs.homeButtonRecentApps && ui.checked(title) == true }
        ui.click(screenshot)
        ui.await("Screenshot On not saved") { prefs.homeButtonScreenshot && ui.checked(screenshot) == true }
        ui.click(context.getString(R.string.paper_notice_dismiss))
        ui.await("Shared Notice did not stay dismissed") { prefs.noticeActionServiceDismissed && !ui.has(context.getString(R.string.paper_service_off)) }
        assertMeasuredPages(false, "gestures-home-button-no-notice")
        reveal(title)
        assertTrue(ui.checked(title) == true && ui.checked(screenshot) == true)
        capture("gestures-home-button-no-notice.png")
    }

    @Test fun noticeDismissalSurvivesSettingsRecreationAndStaysLocal() = withSettings { activity, vm, prefs, ui ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(com.gezimos.katapult.lockscreen.LockscreenWidgetService.isEnabled(context))
        val dismiss = context.getString(R.string.paper_notice_dismiss)
        ui.click(dismiss)
        ui.await("Dismissal was not saved") { prefs.noticeActionServiceDismissed && !ui.has(dismiss) }
        assertFalse(prefs.exportToJson(context).contains("notice_action_service_dismissed"))
        instrumentation.runOnMainSync { activity.setContent { androidx.compose.material3.Text("Closed settings") } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            activity.setContent { SettingsScreen(vm, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
        }
        ui.await("Settings did not reopen") { ui.has(context.getString(R.string.section_system)) }
        assertFalse(ui.has(dismiss))
    }
}
