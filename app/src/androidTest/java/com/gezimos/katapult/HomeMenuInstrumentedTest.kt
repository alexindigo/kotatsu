package com.gezimos.katapult

import android.app.UiAutomation
import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeMenuInstrumentedTest {
    @get:Rule val usageAccess = UsageAccessRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val raw = context.getSharedPreferences("katapult_prefs", 0)
    private val menu = PlatformUiDriver(instrumentation, context.getString(R.string.home_button_menu))
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun fixture(test: (RecentOverlayFixture) -> Unit) {
        val before = raw.all
        try {
            main { raw.edit().putString("wallpaper_source", "KOTATSU")
                .putBoolean("hide_all_apps_button", false).commit() }
            RecentOverlayFixture().use { f ->
                main { raw.edit().putBoolean("home_button_menu", true).commit() }
                test(f)
            }
        } finally {
            main { raw.edit().clear().apply { before.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit() }
        }
    }

    private fun hold(f: RecentOverlayFixture, paired: Boolean = false, body: () -> Unit = {}) {
        val keyboard = f.shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") }
        val path = Regex("/dev/input/event[0-9]+").find(keyboard)!!.value
        fun edge(value: Int) {
            assertEquals("", f.shell("su 0 sendevent $path 1 172 $value"))
            assertEquals("", f.shell("su 0 sendevent $path 0 0 0"))
        }
        val native = File(context.getExternalFilesDir(null), "home-menu-pair.sh")
        try {
            if (paired) {
                // Keep the entire pair in one native stream. Shell round trips can exceed 500 ms.
                native.writeText("set -e\n" +
                    "sendevent $path 1 172 1\nsendevent $path 0 0 0\nsleep 0.03\n" +
                    "sendevent $path 1 172 0\nsendevent $path 0 0 0\nsleep 0.12\n" +
                    "sendevent $path 1 172 1\nsendevent $path 0 0 0\n")
                assertEquals("", f.shell("su 0 sh ${native.absolutePath}"))
            } else edge(1)
            menu.await("Held Home did not attach the Home menu", 10000) { menu.windowVisible() }
            body()
        } finally { edge(0); if (paired) native.delete() }
        SystemClock.sleep(650)
        assertTrue("Releasing held Home must not dismiss or go Home", menu.windowVisible())
    }

    private fun capture(name: String, dark: Boolean) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "home-menu-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
        assertEquals("Sheet interior must use the app paper", palette.paperArgb,
            bitmap.getPixel(bitmap.width / 2, bitmap.height - 80))
        bitmap.recycle()
    }

    private fun pictures(): Set<Long> {
        val result = mutableSetOf<Long>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(context.packageName, "Screenshot_%"), null)?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.getLong(0)
        }
        return result
    }

    @Test fun bothHostsShowKotatsuHeadingAndRuleInBothPalettes() = fixture { f ->
      for (dark in listOf(false, true)) {
        main {
            PrefsManager(context).themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
            f.home.viewModel.screen = Screen.HOME
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        f.ui.homeMenu()
        val committed = java.util.concurrent.CountDownLatch(1)
        main {
            f.home.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            f.home.window.decorView.invalidate()
        }
        assertTrue("Activity capture requires a committed frame", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        assertSheetHeading(f.ui, context.getString(R.string.kotatsu_title), context.getString(R.string.settings), dark)
        capture("heading-home-${if (dark) "dark" else "light"}", dark)
        f.ui.back()
        f.ui.await("Activity menu did not close") { !f.ui.has(context.getString(R.string.wallpaper)) }
        f.settings()
        hold(f)
        assertSheetHeading(menu, context.getString(R.string.kotatsu_title), context.getString(R.string.settings), dark)
        capture("heading-held-${if (dark) "dark" else "light"}", dark)
        f.back()
        menu.await("Held menu did not close") { !menu.windowVisible() }
      }
    }

    @Test fun holdAndBothDismissalsLeaveTheAppAndFocusUntouchedInBothPalettes() = fixture { f ->
        for (dark in listOf(false, true)) {
            main { PrefsManager(context).themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT }
            for (dismiss in listOf("back", "outside")) {
                f.settings()
                val resumed = f.resumedTask()
                val focus = f.focusedWindow()
                hold(f) {
                    assertEquals("Opening a sheet cannot start an Activity", resumed, f.resumedTask())
                    assertEquals("Opening cannot steal app focus", focus, f.focusedWindow())
                }
                assertEquals(resumed, f.resumedTask())
                if (dismiss == "back") {
                    capture("held-${if (dark) "dark" else "light"}", dark)
                    f.back()
                } else menu.outside(context.getString(R.string.wallpaper))
                menu.await("Dismissal left the Home menu attached") { !menu.windowVisible() }
                assertEquals(resumed, f.resumedTask())
                assertEquals(focus, f.focusedWindow())
                assertTrue(f.ui.has("Brightness level"))
            }
        }
    }

    @Test fun homeLongPressUsesTheSameSheetAndBothDestinationsOpen() = fixture { f ->
        for (dark in listOf(false, true)) {
            main {
                PrefsManager(context).themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
                f.home.viewModel.screen = Screen.HOME
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            f.ui.homeMenu()
            capture("home-${if (dark) "dark" else "light"}", dark)
            f.ui.back()
            f.ui.await("Home sheet did not close on Back") { !f.ui.has(context.getString(R.string.wallpaper)) }
            f.ui.homeMenu()
            f.ui.outside(context.getString(R.string.wallpaper))
            f.ui.await("Home sheet did not close above the sheet") { !f.ui.has(context.getString(R.string.wallpaper)) }
        }
        for (destination in listOf(R.string.settings, R.string.wallpaper)) {
            f.settings()
            hold(f)
            menu.click(context.getString(destination))
            menu.await("Handoff did not close overlay") { !menu.windowVisible() }
            f.ui.await("Handoff did not open Kotatsu") { f.ui.packageVisible(context.packageName) }
            main { assertEquals(Screen.SETTINGS, f.home.viewModel.screen) }
            if (destination == R.string.wallpaper) f.ui.await("Wallpaper choice not shown") {
                f.ui.has(context.getString(R.string.wallpaper_none)) && f.ui.has(context.getString(R.string.wallpaper_photos))
            }
        }
    }

    @Test fun screenshotChordBeforeHoldNeverOpensMenu() = fixture { f ->
        val before = pictures()
        main { PrefsManager(context).homeButtonScreenshot = true }
        f.settings()
        val keyboard = f.shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") }
        val path = Regex("/dev/input/event[0-9]+").find(keyboard)!!.value
        fun key(scan: Int, value: Int) {
            f.shell("su 0 sendevent $path 1 $scan $value"); f.shell("su 0 sendevent $path 0 0 0")
        }
        try {
            key(172, 1)
            try {
                SystemClock.sleep(150)
                key(114, 1)
                try { SystemClock.sleep(1200); assertFalse(menu.windowVisible()) }
                finally { key(114, 0) }
            } finally { key(172, 0) }
            f.ui.await("Screenshot chord did not save its picture") { pictures().size == before.size + 1 }
            assertTrue("Screenshot chord must not navigate away", f.ui.has("Brightness level"))
        } finally {
            (pictures() - before).forEach { id -> context.contentResolver.delete(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    @Test fun allThreeTogglesOffPassesHomeAndMenuDefaultImportsAsBoolean() = fixture { f ->
        val prefs = PrefsManager(context)
        main { raw.edit().remove("home_button_menu").commit() }
        assertTrue(prefs.homeButtonMenu)
        assertFalse(prefs.importFromJson(org.json.JSONObject(prefs.exportToJson(context)).put("home_button_menu", "false").toString()))
        assertTrue(prefs.importFromJson(org.json.JSONObject(prefs.exportToJson(context)).put("home_button_menu", false).toString()))
        assertFalse(prefs.homeButtonMenu)
        main {
            prefs.homeButtonMenu = false; prefs.homeButtonRecentApps = false; prefs.homeButtonScreenshot = false
        }
        f.settings()
        f.homeKey()
        val defaultHome = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName
        f.ui.await("All toggles Off must leave normal system Home alone") { f.ui.packageVisible(defaultHome) }
        assertFalse(menu.windowVisible())
    }

    @Test fun secondPressHeldBecomesMenuAndScreenOffRemovesIt() = fixture { f ->
        f.settings()
        val origin = f.resumedTask()
        hold(f, paired = true)
        assertFalse("The deferred Recent apps toggle must not replace the held menu", f.overlay.windowVisible())
        assertEquals(origin, f.resumedTask())
        try {
            f.shell("input keyevent KEYCODE_SLEEP")
            menu.await("Screen off retained the held menu") { !menu.windowVisible() }
        } finally {
            NativeKeyguardFixture.wakeAndUnlock()
        }
    }

    @Test fun hiddenAllAppsButtonAddsTheSharedMenuDestination() = fixture { f ->
        main { PrefsManager(context).hideAllAppsButton = true }
        f.settings()
        hold(f)
        menu.click(context.getString(R.string.all_apps))
        menu.await("All Apps handoff retained overlay") { !menu.windowVisible() }
        f.ui.await("All Apps handoff did not open Kotatsu") { f.ui.packageVisible(context.packageName) }
        main { assertEquals(Screen.ALL_APPS, f.home.viewModel.screen) }
    }
}
