package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.util.AppLoader
import com.gezimos.katapult.util.AppVisibility
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeSlotPickerInstrumentedTest {
    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    @Test fun pickerSharesGridVisibilityWithoutRemovingAssignedHiddenApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = PlatformUiDriver(instrumentation)
        val name = "home-picker-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        var activity: MainActivity? = null
        val apps = AppLoader.loadApps(context).sortedBy { it.label.lowercase() }
        assertTrue("Need two installed app keys", apps.map { it.key }.toSet().size >= 2)
        val hidden = apps[0]
        val visible = apps.first { it.key != hidden.key }
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, raw))
                owner.put("picker", model)
                model.screen = Screen.HOME
                model.prefs.homeExtraRow = false
                model.prefs.setAppRename(hidden.key, "A hidden app")
                model.prefs.setAppRename(visible.key, "B available app")
                model.prefs.setHiddenApps(apps.map { it.key }.toSet() - visible.key)
                model.prefs.setAppHidden(visible.key, false, AppVisibility.hiddenByDefault(visible))
                model.saveShortcut("phone", hidden.packageName, hidden.activityName)
                model.loadApps()
            }
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            instrumentation.runOnMainSync { current.setContent {
                App(model, current.imagePicker, current.iconPicker, current.configExporter,
                    current.configImporter, current.screensaverWallpaperPicker, {})
            } }
            ui.await("Hiding removed the assigned Home app") { ui.has("A hidden app") }
            assertEquals(hidden.packageName, model.getShortcutPackage("phone"))
            assertEquals(listOf(visible.key), model.orderedApps.map { it.key })
            assertTrue(ui.findAncestor("A hidden app") { it.isLongClickable }!!
                .performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            ui.await("Home-slot picker did not open") { ui.has(context.getString(R.string.set_left_app)) }
            assertTrue("Visible grid app absent from picker", ui.has("B available app"))
            assertFalse("Hidden grid app still offered by picker", ui.has("A hidden app"))
            instrumentation.runOnMainSync {
                model.prefs.setAppHidden(hidden.key, false, AppVisibility.hiddenByDefault(hidden))
                model.loadApps()
            }
            ui.await("Unhiding did not update the open picker") { ui.has("A hidden app") }
            instrumentation.runOnMainSync {
                model.prefs.setAppHidden(hidden.key, true, AppVisibility.hiddenByDefault(hidden))
                model.loadApps()
            }
            ui.await("Hiding did not update the open picker") { !ui.has("A hidden app") }
            ui.back()
            ui.await("Closing picker removed the assigned hidden app") { ui.has("A hidden app") }
            assertEquals(hidden.packageName, model.getShortcutPackage("phone"))
            assertEquals(hidden.activityName, model.prefs.loadShortcut("phone")?.second)
            assertTrue(ui.findAncestor("A hidden app") { it.isLongClickable }!!
                .performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            ui.await("Picker did not reopen") { ui.has(context.getString(R.string.set_left_app)) }
            ui.click("B available app")
            ui.await("Picking a visible app did not reach the Home slot") {
                !ui.has(context.getString(R.string.set_left_app)) && ui.has("B available app") &&
                    model.getShortcutPackage("phone") == visible.packageName
            }
            assertEquals(visible.packageName, model.prefs.loadShortcut("phone")?.first)
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); owner.clear() }
            deleteTestPreferences(context, name)
        }
    }
}
