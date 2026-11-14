package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.util.ShortcutHelper
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppMenuInstrumentedTest {
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

    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    private fun fixture(test: (MainActivity, MainViewModel) -> Unit) {
        val home = resolvedHome()
        val role = context.getSystemService(android.app.role.RoleManager::class.java)
        val heldHomeRole = role.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        val hadShortcutPermission = ShortcutHelper.hasHostPermission(context)
        var pinnedBefore = emptyList<String>()
        WidgetHostTestFixture(context).use { f ->
            val owner = ViewModelStore()
            lateinit var model: MainViewModel
            var activity: MainActivity? = null
            try {
                assertEquals("", shell("cmd role add-role-holder android.app.role.HOME ${context.packageName}"))
                ui.await("Launcher shortcut permission missing") { ShortcutHelper.hasHostPermission(context) }
                pinnedBefore = ShortcutHelper.queryShortcuts(context, camera.packageName).filter { it.isPinned }.map { it.id }
                main {
                    f.raw.edit().putBoolean("onboarding_complete", true)
                        .putInt("theme_mode", com.gezimos.katapult.util.PrefsManager.THEME_LIGHT).commit()
                    model = MainViewModel(TestApplication(context.applicationContext, f.raw))
                    model.prefs.homeExtraRow = false
                    owner.put("app-menu", model)
                }
                ui.await("App loading did not settle") { model.orderedApps.any { it.key == camera.key } }
                calculator = model.orderedApps.first { candidate ->
                    candidate.packageName != camera.packageName &&
                        ShortcutHelper.queryShortcuts(context, candidate.packageName).isEmpty() &&
                        context.packageManager.getApplicationInfo(candidate.packageName, 0).flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                }
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val current = activity!!
                main {
                    model.orderedApps.filterNot { it.key == camera.key || it.key == calculator.key }
                        .forEach { model.prefs.hideApp(it.key) }
                    model.prefs.saveAppOrder(listOf(camera.key, calculator.key))
                    model.loadApps()
                    model.navigateTo(Screen.ALL_APPS)
                    current.setContent {
                        App(model, current.imagePicker, current.iconPicker, current.configExporter,
                            current.configImporter, current.screensaverWallpaperPicker, {})
                    }
                }
                ui.await("Fixture apps missing") { ui.has(camera.label) && ui.has(calculator.label) }
                test(current, model)
            } finally {
                main { activity?.finish(); owner.clear() }
                context.getSystemService(android.content.pm.LauncherApps::class.java)
                    .pinShortcuts(camera.packageName, pinnedBefore, android.os.Process.myUserHandle())
                if (!heldHomeRole) {
                    assertEquals("", shell("cmd role remove-role-holder android.app.role.HOME ${context.packageName}"))
                    context.packageManager.clearPackagePreferredActivities(context.packageName)
                }
                if (home != null && home.packageName != context.packageName) {
                    assertTrue(shell("cmd package set-home-activity ${home.flattenToString()}").contains("Success"))
                }
                ui.await("Fixture changed the resolved Home app or shortcut permission") {
                    resolvedHome() == home && role.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == heldHomeRole &&
                        ShortcutHelper.hasHostPermission(context) == hadShortcutPermission
                }
            }
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

}
