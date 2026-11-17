package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.ShortcutHelper
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppMenuInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val ui = PlatformUiDriver(instrumentation)
    private val camera = AppModel(testContext.packageName, "Open Camera", AppMenuShortcutTarget::class.java.name)
    private lateinit var calculator: AppModel
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String): String = instrumentation.platformAutomation.boundedShell(command)
    private fun resolvedHome(): android.content.ComponentName? {
        val info = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo ?: return null
        return if (info.packageName == "android") null else android.content.ComponentName(info.packageName, info.name)
    }

    private fun fixture(test: (MainActivity, MainViewModel) -> Unit) {
        val home = resolvedHome()
        val role = context.getSystemService(android.app.role.RoleManager::class.java)
        val heldHomeRole = role.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        val hadShortcutPermission = ShortcutHelper.hasHostPermission(context)
        var pinnedBefore = emptyList<String>()
        ApplicationPreferencesFixture(context).use { preferences ->
            lateinit var model: MainViewModel
            var modelCreated = false
            var activity: MainActivity? = null
            withFixtureCleanup(body = {
                assertEquals("", shell("cmd role add-role-holder android.app.role.HOME ${context.packageName}"))
                ui.await("Launcher shortcut permission missing") { ShortcutHelper.hasHostPermission(context) }
                pinnedBefore = ShortcutHelper.queryShortcuts(context, camera.packageName).filter { it.isPinned }.map { it.id }
                main {
                    preferences.reset {
                        putBoolean("onboarding_complete", true)
                        putInt("theme_mode", PrefsManager.THEME_LIGHT)
                        putInt("app_sort_mode", PrefsManager.APP_SORT_MANUAL)
                        putBoolean("home_extra_row", false)
                    }
                }
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val current = activity!!
                main { model = current.viewModel; modelCreated = true }
                assertFalse("App-menu fixture must not adopt an existing reorder draft", model.reorderMode)
                ui.await("App loading did not settle") { model.orderedApps.any { it.key == camera.key } }
                calculator = model.orderedApps.first { candidate ->
                    candidate.packageName != camera.packageName &&
                        ShortcutHelper.queryShortcuts(context, candidate.packageName).isEmpty() &&
                        context.packageManager.getApplicationInfo(candidate.packageName, 0).flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                }
                main {
                    model.orderedApps.filterNot { it.key == camera.key || it.key == calculator.key }
                        .forEach { model.prefs.hideApp(it.key) }
                    model.prefs.saveAppOrder(listOf(camera.key, calculator.key))
                    model.loadApps()
                    model.navigateTo(Screen.ALL_APPS)
                }
                ui.await("Fixture apps missing") { ui.has(camera.label) && ui.has(calculator.label) }
                test(current, model)
            }, actions = arrayOf<() -> Unit>(
                { main { fixtureCleanup(
                    { if (modelCreated && model.reorderMode) model.discardReorderExit() },
                    { activity?.finish() },
                ) } },
                { activity?.let { current -> ui.await("App-menu Activity did not release its ViewModel") { current.isDestroyed } } },
                { context.getSystemService(android.content.pm.LauncherApps::class.java)
                    .pinShortcuts(camera.packageName, pinnedBefore, android.os.Process.myUserHandle()) },
                { assertEquals("App-menu fixture leaked shortcut pins", pinnedBefore.toSet(),
                    ShortcutHelper.queryShortcuts(context, camera.packageName).filter { it.isPinned }.map { it.id }.toSet()) },
                { if (!heldHomeRole) { fixtureCleanup(
                    { assertEquals("", shell("cmd role remove-role-holder android.app.role.HOME ${context.packageName}")) },
                    { context.packageManager.clearPackagePreferredActivities(context.packageName) },
                ) } },
                { if (home != null && home.packageName != context.packageName) {
                    assertTrue(shell("cmd package set-home-activity ${home.flattenToString()}").contains("Success"))
                } },
                { ui.await("Fixture changed the resolved Home app or shortcut permission") {
                    resolvedHome() == home && role.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == heldHomeRole &&
                        ShortcutHelper.hasHostPermission(context) == hadShortcutPermission
                } },
            ))
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        val bitmap = instrumentation.platformAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "app-menu-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun noShortcutsListsBackAndMove() = fixture { _, model ->
        ui.click(calculator.label, longPress = true)
        ui.await("App tiles missing") { ui.has("Kotatsu") && ui.has("Android") && ui.has("Move") }
        assertFalse(ui.has("App shortcuts"))
        capture("no-shortcuts")
        ui.click("Kotatsu")
        ui.await("Kotatsu list missing") { ui.has("Name") && ui.has("App icon") }
        capture("kotatsu")
        ui.back()
        ui.await("Back lost the main menu") { ui.has("Move") }
        ui.click("Android")
        ui.await("Android list missing") { ui.has("App info") && ui.has("Notifications") }
        assertFalse("System apps must not offer uninstall", ui.has("Uninstall"))
        capture("android")
        ui.click("Back")
        ui.await("Chevron lost the main menu") { ui.has("Move") }
        ui.click("Kotatsu")
        ui.click("Dismiss")
        ui.await("Dismiss must close the entire app menu") { model.contextMenuApp == null && !ui.has("Name") }
        ui.click(calculator.label, longPress = true)
        ui.click("Move")
        ui.await("Move did not select its app") { model.reorderMode &&
            model.orderedApps.getOrNull(model.reorderHighlightIndex)?.key == calculator.key }
    }

    @Test fun allAppsMoveIsAvailableOnlyInManualSortForAppsAndShortcuts() = fixture { _, model ->
        main { model.setShortcutEnabled(camera.packageName, "camera", "Camera", true) }
        ui.await("Added shortcut missing from All Apps") { ui.has("Camera") }
        val move = context.getString(R.string.menu_move)
        val kotatsu = context.getString(R.string.kotatsu_title)
        val android = context.getString(R.string.menu_android)
        val remove = context.getString(R.string.menu_remove)
        for (mode in listOf(PrefsManager.APP_SORT_MANUAL, PrefsManager.APP_SORT_RECENT, PrefsManager.APP_SORT_USED)) {
            main { model.prefs.appSortMode = mode; model.loadApps() }
            ui.click(calculator.label, longPress = true)
            ui.await("App menu missing in sort mode $mode") { ui.has(kotatsu) && ui.has(android) }
            assertEquals("Move availability for app in sort mode $mode", mode == PrefsManager.APP_SORT_MANUAL, ui.has(move))
            ui.back()
            ui.await("App menu did not dismiss") { model.contextMenuApp == null }
            ui.click("Camera", longPress = true)
            ui.await("Added shortcut menu missing in sort mode $mode") { ui.has(remove) }
            assertEquals("Move availability for shortcut in sort mode $mode", mode == PrefsManager.APP_SORT_MANUAL, ui.has(move))
            assertFalse(ui.has(kotatsu))
            assertFalse(ui.has(android))
            assertFalse(model.reorderMode)
            ui.back()
            ui.await("Shortcut menu did not dismiss") { model.contextMenuApp == null }
        }
    }

    @Test fun shortcutNamesLaunchAndCheckboxesOwnSavedIcons() = fixture { _, model ->
        ui.click(camera.label, longPress = true)
        ui.await("Published shortcuts missing") { ui.has("App shortcuts") &&
            listOf("Camera", "Gallery", "Record video", "Selfie", "Settings").all(ui::has) }
        assertEquals(false, ui.find { it.isCheckable }?.isChecked)
        capture("shortcuts")
        assertTrue(ui.find { it.isCheckable }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        ui.await("Checking did not save an app icon") {
            ui.find { it.isCheckable }?.isChecked == true && model.prefs.getSavedShortcuts().any { it.shortcutId == "camera" }
        }
        capture("shortcuts-checked")
        assertTrue(ui.find { it.isCheckable }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        ui.await("Unchecking did not remove the app icon") {
            ui.find { it.isCheckable }?.isChecked == false && model.prefs.getSavedShortcuts().none { it.shortcutId == "camera" }
        }
        ui.click("Camera")
        ui.await("Shortcut did not launch its consumer") { ui.has("Shortcut launched") }
    }

    @Test fun addedShortcutHasOnlyRemoveAndMove() = fixture { _, model ->
        val shortcut = camera.copy(label = "Camera", shortcutId = "camera")
        main { model.setShortcutEnabled(camera.packageName, "camera", "Camera", true) }
        ui.await("Saved icon missing") { model.prefs.getSavedShortcuts().any { it.shortcutId == "camera" } }
        main { model.contextMenuApp = shortcut }
        ui.await("Added shortcut menu missing") { ui.has("App icon") && ui.has("Remove") && ui.has("Move") }
        assertFalse(ui.has("Kotatsu")); assertFalse(ui.has("Android")); assertFalse(ui.has("App shortcuts"))
        capture("added-shortcut")
        ui.click("Remove")
        ui.await("Remove did not remove its saved icon") {
            model.contextMenuApp == null && model.prefs.getSavedShortcuts().none { it.shortcutId == "camera" }
        }
    }

    @Test fun appListsHideAndAndroidHandoff() = fixture { _, model ->
        AndroidSettingsTaskFixture.assertEmpty()
        ui.click(camera.label, longPress = true)
        ui.click("Android")
        ui.await("Non-system app must offer uninstall") { ui.has("Uninstall") }
        capture("android-app")
        ui.click("App info")
        ui.await("App info did not reach Android") { model.contextMenuApp == null && ui.packageVisible("com.android.settings") }
        ui.back()
        ui.await("Android Back did not return to the grid") { ui.has(camera.label) }
        ui.click(camera.label, longPress = true)
        ui.click("Kotatsu")
        ui.await("Kotatsu app list missing") { ui.has("Name") && ui.has("Hide") }
        assertFalse("No dot means no Clear action", ui.has("Notification dot"))
        capture("kotatsu-app")
        ui.click("Hide")
        ui.await("Hide did not close the menu and hide its app") {
            model.contextMenuApp == null && model.orderedApps.none { it.key == camera.key }
        }
    }

    @Test fun holdingHomeAppAlwaysOpensChangeAndChangeOpensItsSpotPicker() = fixture { _, model ->
        main {
            model.prefs.setAppRename(camera.key, camera.label)
            model.saveShortcut("phone", camera.packageName, camera.activityName)
            model.navigateTo(Screen.HOME)
        }
        ui.await("Assigned Home app missing") { ui.has(camera.label) }
        ui.click(camera.label, longPress = true)
        ui.await("Home app menu missing Change") { ui.has("Change") && ui.has("Kotatsu") && ui.has("Android") }
        assertFalse(ui.has("Move"))
        capture("home")
        ui.click("Change")
        ui.await("Change did not open this spot's picker") { ui.has(context.getString(R.string.set_left_app)) }
        ui.click(calculator.label)
        ui.await("Picked app did not reach the Home spot") {
            model.getShortcutPackage("phone") == calculator.packageName && !ui.has(context.getString(R.string.set_left_app))
        }
        assertEquals(calculator.packageName, model.prefs.loadShortcut("phone")?.first)
    }

    @Test fun homeShortcutMenuHasRemoveAndChangeOnly() = fixture { _, model ->
        main {
            model.setShortcutEnabled(camera.packageName, "camera", "Camera", true)
            model.saveShortcut("phone", camera.packageName, camera.activityName, "camera")
            model.navigateTo(Screen.HOME)
        }
        ui.await("Home shortcut icon missing") { ui.has("Camera") }
        ui.click("Camera", longPress = true)
        ui.await("Home shortcut menu missing") { ui.has("Remove") && ui.has("Change") }
        assertFalse(ui.has("Move")); assertFalse(ui.has("Kotatsu")); assertFalse(ui.has("Android"))
        capture("home-added-shortcut")
        ui.click("Change")
        ui.await("Home shortcut Change lost its spot") { ui.has(context.getString(R.string.set_left_app)) }
    }
}
