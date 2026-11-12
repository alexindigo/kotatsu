package com.gezimos.katapult

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.util.HomeAppSwipe
import com.gezimos.katapult.util.PrefsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SwipeAllAppsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun realStoreAndImportMigrateOnceAndRoundTripWithoutLegacyState() {
        for (existing in listOf(false, true)) for (enabled in listOf<Boolean?>(null, false, true))
            for (vertical in listOf<Boolean?>(null, false, true)) {
                if (!existing && (enabled != null || vertical != null)) continue
                val name = "home-swipe-${UUID.randomUUID()}"
                val raw = context.getSharedPreferences(name, 0)
                val wrapped = object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this
                    override fun getSharedPreferences(name: String?, mode: Int) = raw
                }
                try {
                    if (existing) raw.edit().putBoolean("onboarding_complete", true).apply()
                    if (enabled != null) raw.edit().putBoolean("swipe_up_all_apps", enabled).apply()
                    if (vertical != null) raw.edit().putBoolean("vertical_app_gestures", vertical).apply()
                    val prefs = PrefsManager(wrapped)
                    val expected = if (!existing) HomeAppSwipe.DEFAULT else if (enabled == false) HomeAppSwipe.NONE
                        else if (vertical == true) HomeAppSwipe.UP else HomeAppSwipe.LEFT
                    assertEquals(expected, prefs.swipeAllApps)
                    assertEquals(expected.id, raw.getString("swipe_all_apps", null))
                    assertFalse(raw.contains("swipe_up_all_apps"))
                    prefs.verticalAppGestures = !prefs.verticalAppGestures
                    assertEquals("App direction must not rerun migration", expected, PrefsManager(wrapped).swipeAllApps)
                    for (choice in HomeAppSwipe.entries) {
                        prefs.swipeAllApps = choice
                        val exported = JSONObject(prefs.exportToJson(wrapped))
                        assertFalse(exported.has("swipe_up_all_apps"))
                        assertEquals(choice.id, exported.getString("swipe_all_apps"))
                        assertTrue(prefs.importFromJson(exported.toString()))
                        assertEquals(choice, prefs.swipeAllApps)
                    }
                    for (on in listOf(false, true)) for (axis in listOf(false, true)) {
                        val legacy = JSONObject().put("_type", "katapult_config").put("swipe_up_all_apps", on).put("vertical_app_gestures", axis)
                        assertTrue(prefs.importFromJson(legacy.toString()))
                        assertEquals(if (!on) HomeAppSwipe.NONE else if (axis) HomeAppSwipe.UP else HomeAppSwipe.LEFT, prefs.swipeAllApps)
                        legacy.put("swipe_all_apps", HomeAppSwipe.UP_AND_LEFT.id)
                        assertTrue(prefs.importFromJson(legacy.toString()))
                        assertEquals(HomeAppSwipe.UP_AND_LEFT, prefs.swipeAllApps)
                    }
                    assertTrue(raw.edit().remove("swipe_all_apps").commit())
                    assertEquals("Getter omission must use the declared default", HomeAppSwipe.DEFAULT, prefs.swipeAllApps)
                    assertTrue(prefs.importFromJson("{\"_type\":\"katapult_config\"}"))
                    assertEquals(HomeAppSwipe.DEFAULT, prefs.swipeAllApps)
                    for (invalid in listOf("bad", true, 3, JSONObject.NULL)) {
                        val before = raw.all
                        assertFalse(prefs.importFromJson(JSONObject().put("_type", "katapult_config").put("swipe_all_apps", invalid).toString()))
                        assertEquals(before, raw.all)
                    }
                } finally { deleteTestPreferences(context, name) }
            }
    }

    @Test fun settingsUsesTheFourValueSheetAndPersistsEveryChoice() {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        var activity: MainActivity? = null
        try {
            main { raw.edit().putBoolean("onboarding_complete", true).putInt("theme_mode", PrefsManager.THEME_LIGHT)
                .putString("swipe_all_apps", HomeAppSwipe.DEFAULT.id).putBoolean("vertical_app_gestures", false)
                .putBoolean("notice_action_service_dismissed", true).commit() }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val current = activity
            main {
                val vm = ViewModelProvider(current)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                current.setContent { SettingsScreen(vm, current.configExporter, current.configImporter, current.screensaverWallpaperPicker) }
            }
            val ui = PlatformUiDriver(instrumentation)
            val prefs = PrefsManager(context)
            val title = context.getString(R.string.swipe_up_all_apps)
            ui.click(context.getString(R.string.section_gestures))
            ui.await("Swipe choice missing") { ui.has(title) && ui.has(context.getString(R.string.swipe_up_all_apps_desc)) }
            capture(current, "gestures-light")
            val ids = listOf(R.string.swipe_apps_none, R.string.swipe_apps_up, R.string.swipe_apps_left, R.string.swipe_apps_up_left)
            for ((index, choice) in HomeAppSwipe.entries.withIndex()) {
                ui.click(title)
                ui.await("Four selectable options missing") { ids.all { ui.checked(context.getString(it)) != null } }
                assertEquals(ids.map(context::getString), ui.gridLabels(ids.map(context::getString)))
                if (index == 0) {
                    assertEquals(true, ui.checked(context.getString(R.string.swipe_apps_up_left)))
                    val first = android.graphics.Rect().also { ui.find(context.getString(R.string.swipe_apps_none))!!.getBoundsInScreen(it) }
                    assertTrue("Static list of four must be bottom anchored", first.top > 200)
                    capture(current, "sheet-light")
                    ui.back()
                    ui.await("Back did not restore chapter") { ui.has(context.getString(R.string.app_gesture_direction)) }
                    assertEquals(HomeAppSwipe.DEFAULT, prefs.swipeAllApps)
                    ui.click(title)
                    ui.await("Sheet did not reopen") { ids.all { ui.checked(context.getString(it)) != null } }
                }
                ui.click(context.getString(ids[index]))
                ui.await("Choice not persisted or sheet not dismissed") {
                    prefs.swipeAllApps == choice && ui.find { it.className == "android.widget.RadioButton" } == null
                }
                assertFalse(prefs.verticalAppGestures)
            }
        } finally {
            main {
                activity?.finish()
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

    private fun capture(activity: MainActivity, name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.platformAutomation.waitForIdle(100, 5000)
        val committed = java.util.concurrent.CountDownLatch(1)
        main {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue("Capture requires a committed frame", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = instrumentation.platformAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "swipe-apps-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
