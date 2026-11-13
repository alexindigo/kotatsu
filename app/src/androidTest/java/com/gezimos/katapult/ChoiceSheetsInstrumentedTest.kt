package com.gezimos.katapult

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ChoiceSheetsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun fixture(dark: Boolean = false, test: (MainActivity, MainViewModel, PrefsManager, PlatformUiDriver, AtomicInteger) -> Unit) {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        var activity: MainActivity? = null
        try {
            main { raw.edit().putInt("theme_mode", if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT)
                .putString("wallpaper_source", "KOTATSU").putBoolean("screensaver_enabled", true)
                .putBoolean("notice_action_service_dismissed", true).putBoolean("home_button_menu", false)
                .putBoolean("home_button_recent_apps", false).putBoolean("home_button_screenshot", false).commit() }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val current = activity
            lateinit var vm: MainViewModel
            val photos = AtomicInteger()
            main {
                vm = ViewModelProvider(current)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                vm.screen = Screen.SETTINGS
                current.setContent { SettingsScreen(vm, current.configExporter, current.configImporter,
                    current.screensaverWallpaperPicker, onWallpaperPhoto = { photos.incrementAndGet() }) }
            }
            test(current, vm, PrefsManager(context), PlatformUiDriver(instrumentation), photos)
        } finally {
            main {
                activity?.finish()
                val edit = raw.edit().clear()
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

    private fun label(id: Int) = context.getString(id)
    private fun reveal(ui: PlatformUiDriver, id: Int) {
        repeat(10) {
            instrumentation.waitForIdleSync()
            if (ui.has(label(id))) return
            assertTrue("No page contains ${label(id)}", ui.has(label(R.string.paper_next_page)))
            ui.click(label(R.string.paper_next_page))
        }
        fail("Entry not found")
    }
    private fun options(ui: PlatformUiDriver, vararg ids: Int) {
        ui.await("Choice options not selectable") { ids.all { ui.checked(label(it)) != null } }
    }
    private fun closed(ui: PlatformUiDriver) {
        ui.await("Choice surface stayed open") { ui.find { it.className == "android.widget.RadioButton" } == null }
    }
    private fun capture(name: String, dark: Boolean) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
        assertEquals("Sheet paper", palette.paperArgb, bitmap.getPixel(bitmap.width / 2, bitmap.height - 80))
        File(context.getExternalFilesDir(null), "choice-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun pixels(bounds: android.graphics.Rect): IntArray {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        return IntArray(bounds.width() * bounds.height()).also {
            bitmap.getPixels(it, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
            bitmap.recycle()
        }
    }

    private fun assertChoiceGeometry(ui: PlatformUiDriver, dark: Boolean) {
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        try {
            val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
            val density = context.resources.displayMetrics.density
            val edge = bitmap.width - (16 * density).toInt()
            for (id in listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)) {
                val name = android.graphics.Rect().also { ui.find(label(id))!!.getBoundsInScreen(it) }
                val expected = if (ui.checked(label(id)) == true) palette.inkArgb else palette.mutedArgb
                val boxPixels = mutableListOf<Pair<Int, Int>>()
                for (y in name.top until name.bottom) for (x in edge - (22 * density).toInt() until edge) {
                    if (bitmap.getPixel(x, y) == expected) boxPixels += x to y
                }
                assertTrue("Option box missing", boxPixels.isNotEmpty())
                val left = boxPixels.minOf { it.first }; val right = boxPixels.maxOf { it.first } + 1
                val top = boxPixels.minOf { it.second }; val bottom = boxPixels.maxOf { it.second } + 1
                assertEquals("Shared 20 dp width", 20 * density, (right - left).toFloat(), 2f)
                assertEquals("Shared 20 dp height", 20 * density, (bottom - top).toFloat(), 2f)
                assertEquals("Box and name share a structural centre", name.exactCenterY(), (top + bottom) / 2f, 1.5f)
                val leaderPixels = (name.right + (8 * density).toInt() until left - (8 * density).toInt()).sumOf { x ->
                    (name.top until name.bottom).count { y -> bitmap.getPixel(x, y) == palette.mutedArgb }
                }
                assertTrue("Leader must use muted", leaderPixels > 10)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun sheetsKeepChapterApplyAndDismissInBothPalettes() {
        for (dark in listOf(false, true)) fixture(dark) { _, vm, prefs, ui, photos ->
            ui.click(label(R.string.section_appearance))
            reveal(ui, R.string.theme_mode)
            val chapterHeader = android.graphics.Rect().also { ui.find(label(R.string.section_appearance))!!.getBoundsInScreen(it) }
            val before = pixels(chapterHeader)
            ui.click(label(R.string.theme_mode))
            options(ui, R.string.theme_system, R.string.theme_light, R.string.theme_dark)
            assertTrue(ui.has(label(R.string.theme_system_help)))
            assertTrue(ui.has(label(R.string.theme_light_help)))
            assertTrue(ui.has(label(R.string.theme_dark_help)))
            assertEquals(true, ui.checked(label(if (dark) R.string.theme_dark else R.string.theme_light)))
            assertArrayEquals("Sheet replaced or dimmed its retained chapter", before, pixels(chapterHeader))
            assertChoiceGeometry(ui, dark)
            capture("theme-${if (dark) "dark" else "light"}", dark)
            ui.back(); closed(ui)
            assertEquals(if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT, prefs.themeMode)
            assertTrue("Back lost chapter page", ui.has(label(R.string.theme_mode)))
            ui.click(label(R.string.theme_mode)); options(ui, R.string.theme_system)
            ui.outside(label(R.string.theme_system)); closed(ui)
            assertTrue(ui.has(label(R.string.wallpaper)))
            ui.click(label(R.string.wallpaper))
            options(ui, R.string.wallpaper_none, R.string.wallpaper_kotatsu, R.string.wallpaper_photos)
            val external = ui.findAncestor(label(R.string.wallpaper_photos)) { it.isClickable }!!
            assertTrue("Photo handoff lacks action label", external.actionList.any {
                it.label?.toString() == context.getString(com.gezimos.katapult.paper.R.string.paper_external)
            })
            assertTrue(ui.has("\u2197"))
            capture("wallpaper-${if (dark) "dark" else "light"}", dark)
            ui.click(label(R.string.wallpaper_photos)); closed(ui)
            assertEquals(1, photos.get())
            assertTrue("Photo handoff lost parent", ui.has(label(R.string.theme_mode)))
            ui.click(label(R.string.wallpaper)); options(ui, R.string.wallpaper_none)
            ui.click(label(R.string.wallpaper_none)); closed(ui)
            ui.await("Wallpaper consumer did not apply") { vm.wallpaperSource == com.gezimos.katapult.util.WallpaperSource.NONE }
            ui.click(label(R.string.theme_mode)); options(ui, R.string.theme_light)
            val requested = if (dark) PrefsManager.THEME_LIGHT else PrefsManager.THEME_DARK
            ui.click(label(if (dark) R.string.theme_light else R.string.theme_dark)); closed(ui)
            ui.await("Theme choice did not reach consumer") { prefs.themeMode == requested && vm.themeMode == requested }
            ui.back()
            ui.await("Chapter Back did not return root") { ui.has(label(R.string.section_system)) }
        }
    }

    @Test fun longAndAppBuiltListsRemainPagesEvenWhenAppBuiltIsShort() = fixture { activity, _, _, ui, _ ->
        for ((count, dynamic) in listOf(4 to false, 5 to false, 2 to true)) {
            val chosen = AtomicInteger()
            val renderKey = Any()
            main { activity.setContent { androidx.compose.runtime.key(renderKey) {
                val entry = SettingsEntry.Value("value", "Test choice", "Choice help", "Option 0",
                    List(count) { SettingsChoice(it.toString(), "Option $it") }, { chosen.incrementAndGet() }, "0", appBuiltChoices = dynamic)
                PaperIndexRenderer(SettingsCategory("fixture", "Fixture chapter", listOf(SettingsGroup("group", "Group", listOf(entry)))), {})
            } } }
            ui.click("Test choice")
            ui.await("Options missing") { (0 until count).all { ui.checked("Option $it") != null } }
            assertEquals(0, chosen.get())
            val sheet = ui.findAncestor("Option 0") { it.viewIdResourceName == "paper_sheet_surface" }
            assertEquals("Only short static lists use the sheet surface", count == 4 && !dynamic, sheet != null)
            ui.back(); closed(ui)
            assertTrue(ui.has("Fixture chapter"))
            assertEquals(0, chosen.get())
        }
    }

    @Test fun retainedChapterCannotReceiveAccessibilityOrKeyboardFocus() = fixture { activity, _, _, ui, _ ->
        main { activity.setContent {
            val entry = SettingsEntry.Value("value", "Test choice", "Choice help", "Option 0",
                List(3) { SettingsChoice(it.toString(), "Option $it") }, {}, "0")
            PaperIndexRenderer(SettingsCategory("fixture", "Fixture chapter",
                listOf(SettingsGroup("group", "Group", listOf(entry)))), {})
        } }
        ui.click("Test choice")
        ui.await("Options missing") { ui.checked("Option 0") != null }
        assertFalse("Retained chapter is exposed to accessibility", ui.has("Fixture chapter"))
        assertNull("Retained choice entry is exposed to accessibility",
            ui.find { it.text?.toString() == "Test choice" && !it.isHeading })
        for (reverse in listOf(false, true)) repeat(12) {
            val time = android.os.SystemClock.uptimeMillis()
            val meta = if (reverse) android.view.KeyEvent.META_SHIFT_ON else 0
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                instrumentation.sendKeySync(android.view.KeyEvent(time, time, action, android.view.KeyEvent.KEYCODE_TAB, 0, meta))
            }
            instrumentation.waitForIdleSync()
            ui.await("Keyboard focus escaped the sheet after Tab ${it + 1}, reverse=$reverse") {
                ui.find { node -> node.isFocused && node.isCheckable } != null
            }
        }
        ui.back(); closed(ui)
        assertTrue("Dismissal did not restore chapter accessibility", ui.has("Fixture chapter"))
        assertTrue("Dismissal did not restore choice accessibility", ui.has("Test choice"))
    }

    @Test fun timedChoicePopsBeforeOpeningItsEditorAndHomeHelpIsLocalized() = fixture { _, _, prefs, ui, _ ->
        ui.click(label(R.string.section_screensaver)); reveal(ui, R.string.screensaver_update_mode)
        ui.click(label(R.string.screensaver_update_mode)); options(ui, R.string.screensaver_mode_interval)
        ui.click(label(R.string.screensaver_mode_interval))
        ui.await("Timed selection lost editor handoff") { ui.has(label(R.string.screensaver_update_interval)) }
        assertEquals(PrefsManager.SCREENSAVER_MODE_INTERVAL, prefs.screensaverUpdateMode)
        ui.back()
        ui.await("Editor Back lost chapter") { ui.has(label(R.string.screensaver_update_mode)) }
        ui.back(); ui.click(label(R.string.section_gestures)); reveal(ui, R.string.double_tap_action)
        ui.click(label(R.string.double_tap_action)); options(ui, R.string.double_tap_off)
        for (id in listOf(R.string.double_tap_off_help, R.string.double_tap_brightness_help, R.string.double_tap_lock_help)) assertTrue(ui.has(label(id)))
        for (language in listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt")) {
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(config).resources
            for (id in listOf(R.string.theme_system_help, R.string.theme_light_help, R.string.theme_dark_help,
                R.string.double_tap_off_help, R.string.double_tap_brightness_help, R.string.double_tap_lock_help))
                assertTrue("Missing $language option help", resources.getString(id).isNotBlank())
        }
    }
}
