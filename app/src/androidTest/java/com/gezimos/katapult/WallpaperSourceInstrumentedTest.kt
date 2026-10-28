package com.gezimos.katapult

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import com.gezimos.katapult.util.WallpaperPicture
import com.gezimos.katapult.util.WallpaperSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Public choice/picker input, real MainActivity, and end-of-chain Home framebuffer probes. */
@RunWith(AndroidJUnit4::class)
class WallpaperSourceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private val proof get() = File(context.getExternalFilesDir(null), "wallpaper-source-probes").apply { mkdirs() }

    @Test fun legacyReadsAndConfigImportsRespectSourceAndOwnedFiles() {
        val name = "wallpaper-import-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = store
        }
        val prefs = PrefsManager(wrapped)
        val photo = File(context.filesDir, "$name.png")
        png(photo)
        try {
            assertEquals(WallpaperSource.NONE, prefs.wallpaperSource)
            store.edit().putString("wallpaper_path", photo.path).commit()
            assertEquals(WallpaperSource.PHOTO, prefs.wallpaperSource)
            val oldConfig = JSONObject().put("_type", "katapult_config").put("wallpaper_path", photo.path)
            assertTrue(prefs.importFromJson(oldConfig.toString()))
            assertEquals(WallpaperSource.PHOTO, prefs.wallpaperSource)
            for (source in WallpaperSource.entries) {
                prefs.setWallpaper(source, photo.path.takeIf { source == WallpaperSource.PHOTO })
                val config = prefs.exportToJson(context)
                assertEquals(source.name, JSONObject(config).getString("wallpaper_source"))
                prefs.setWallpaper(WallpaperSource.NONE)
                assertTrue(prefs.importFromJson(config))
                assertEquals(source, prefs.wallpaperSource)
                prefs.screensaverWallpaper = true
                assertEquals(prefs.wallpaperPicture, prefs.screensaverPicture)
            }
            assertTrue(prefs.importFromJson("{\"_type\":\"katapult_config\"}"))
            assertEquals(WallpaperSource.NONE, prefs.wallpaperSource)
            assertTrue(prefs.importFromJson(oldConfig.put("wallpaper_source", "PHOTO")
                .put("wallpaper_path", "/not/owned.png").toString()))
            assertEquals(WallpaperSource.NONE, prefs.wallpaperSource)
            assertFalse(prefs.importFromJson(oldConfig.put("wallpaper_source", "bogus").toString()))
        } finally {
            photo.delete()
            deleteTestPreferences(context, name)
        }
    }

    @Test fun choicesPickerRecreationAndHomePixels() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("wallpaperPhase")
        if (phase == "restore" || phase == "check-restored") {
            val saved = context.getSharedPreferences("wallpaper-cold-restore-v2", Context.MODE_PRIVATE).all
            assertEquals(true, saved["onboarding_complete"])
            if (phase == "restore") restorePreferences(saved)
            assertEquals("Cold proof did not restore the original typed preference snapshot", saved, raw.all)
            return
        }
        if (phase == "verify") {
            val expected = WallpaperSource.valueOf(args.getString("wallpaperSource")!!)
            assertNotEquals("Cold proof reused the same process", File(proof, "cold-$expected.pid").readText(), android.os.Process.myPid().toString())
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = it.viewModel; model.onHomeIntent() }
                assertEquals(expected, model.wallpaperSource)
                assertHome(model, expected)
                scenario.onActivity { model.openWallpaperSettings() }
                assertChoice(expected)
            }
            return
        }
        val previous = raw.all
        val destination = File(context.filesDir, "wallpaper.png")
        val previousBytes = destination.takeIf { it.isFile }?.readBytes()
        val photo = File(context.filesDir, "wallpaper-source-test.png")
        png(photo)
        try {
            raw.edit().putBoolean("onboarding_complete", true).putString("clock_format", "'10:45'")
                .putString("date_format", "'October 27, 2026'").putBoolean("show_weather", false)
                .putBoolean("show_battery", false).putBoolean("show_alarm", false).commit()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = it.viewModel; model.onHomeIntent() }
                for (source in WallpaperSource.entries) {
                    // Both public entrances must reach the same choice list and selection semantics.
                    scenario.onActivity { model.onHomeIntent() }
                    ui.await("Home missing") { ui.has(context.getString(R.string.all_apps)) }
                    ui.homeMenu()
                    ui.click(context.getString(R.string.wallpaper))
                    assertChoice(model.wallpaperSource)
                    if (source == WallpaperSource.PHOTO) pick(scenario, photo) else ui.click(label(source))
                    ui.await("Choice did not commit") { model.wallpaperSource == source }
                    scenario.onActivity { model.onHomeIntent() }
                    assertHome(model, source)
                    scenario.recreate()
                    scenario.onActivity { model = it.viewModel; model.onHomeIntent() }
                    assertHome(model, source)
                    scenario.onActivity { model.navigateTo(Screen.SETTINGS) }
                    ui.click(context.getString(R.string.section_appearance))
                    repeat(3) {
                        if (!ui.has(context.getString(R.string.wallpaper_help))) ui.click(context.getString(R.string.paper_next_page))
                    }
                    val description = "${context.getString(R.string.wallpaper)}, ${label(source)}"
                    ui.await("Appearance has stale source") { ui.has(description) }
                    ui.click(description)
                    assertChoice(source)
                    scenario.recreate()
                    scenario.onActivity { model = it.viewModel }
                    assertChoice(source)
                    // Cancelled or unreadable photos must keep every previous source and bitmap.
                    val bitmap = model.wallpaperBitmap
                    pick(scenario, null)
                    assertEquals(source, model.wallpaperSource)
                    assertSame(bitmap, model.wallpaperBitmap)
                    scenario.onActivity { model.openWallpaperSettings() }
                    assertChoice(source)
                    pick(scenario, File(context.filesDir, "nonexistent-photo.png"))
                    instrumentation.waitForIdleSync()
                    assertEquals(source, model.wallpaperSource)
                    assertSame(bitmap, model.wallpaperBitmap)
                }
                // Full-frame evidence in both themes; verify contrast at the actual consumer.
                for (dark in listOf(false, true)) {
                    scenario.onActivity {
                        model.themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
                        model.prefs.themeMode = model.themeMode
                        model.applyStatusBar(it)
                        model.clearWallpaper(); model.onHomeIntent()
                    }
                    ui.await("Clock fixture missing") { ui.has("10:45") }
                    val baseline = frame()
                    scenario.onActivity { model.openWallpaperSettings() }
                    ui.click(label(WallpaperSource.KOTATSU))
                    ui.await("Bundled choice not published") { model.wallpaperSource == WallpaperSource.KOTATSU }
                    scenario.onActivity { model.onHomeIntent() }
                    assertHome(model, WallpaperSource.KOTATSU)
                    val withPicture = frame()
                    verifyContrast(model, baseline, withPicture, dark)
                    save(withPicture, "home-${if (dark) "dark" else "light"}")
                    scenario.onActivity { model.openWallpaperSettings() }
                    assertChoice(WallpaperSource.KOTATSU)
                    save(frame(), "page-${if (dark) "dark" else "light"}")
                    scenario.onActivity {
                        model.prefs.screensaverEnabled = true
                        model.prefs.screensaverWallpaper = true
                        model.prefs.screensaverWallpaperPath = null
                        model.prefs.screensaverShowNotifications = false
                        model.prefs.screensaverEinkRefresh = false
                    }
                    val screensaver = instrumentation.startActivitySync(Intent(context, ScreensaverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScreensaverActivity
                    try {
                        ui.await("Screensaver not shown") { ScreensaverActivity.isShowing }
                        ui.await("Screensaver did not inherit Kotatsu", 15000) {
                            val screen = frame()
                            val matches = (screen.height / 2 until screen.height / 2 + 20).all { y ->
                                (3..20).all { x -> screen.getPixel(x, y) == withPicture.getPixel(x, y) }
                            }
                            screen.recycle()
                            matches
                        }
                    } finally {
                        instrumentation.runOnMainSync { screensaver.finish() }
                        ui.await("Screensaver did not close") { !ScreensaverActivity.isShowing }
                    }
                    baseline.recycle(); withPicture.recycle()
                }
                if (phase == "prepare") {
                    val source = WallpaperSource.valueOf(args.getString("wallpaperSource")!!)
                    scenario.onActivity { model.openWallpaperSettings() }
                    assertChoice(model.wallpaperSource)
                    if (source == WallpaperSource.PHOTO) pick(scenario, photo) else ui.click(label(source))
                    ui.await("Cold proof source not saved") { model.wallpaperSource == source }
                    // Restore a real exported configuration before the external process stop.
                    val config = model.prefs.exportToJson(context)
                    model.prefs.setWallpaper(WallpaperSource.NONE)
                    assertTrue(model.prefs.importFromJson(config))
                    assertEquals(source, model.prefs.wallpaperSource)
                    File(proof, "cold-$source.pid").writeText(android.os.Process.myPid().toString())
                    assertTrue(raw.edit().commit())
                    scenario.onActivity { model.onHomeIntent() }
                    assertHome(model, source)
                }
            }
        } finally {
            // Explicit cold-proof invocations retain the chosen source across the external process stop.
            if (phase != "prepare") {
                if (previousBytes == null) destination.delete() else destination.writeBytes(previousBytes)
                restorePreferences(previous)
                assertEquals(previous, raw.all)
            }
            photo.delete()
        }
    }

    private fun label(source: WallpaperSource) = context.getString(when (source) {
        WallpaperSource.NONE -> R.string.wallpaper_none
        WallpaperSource.KOTATSU -> R.string.wallpaper_kotatsu
        WallpaperSource.PHOTO -> R.string.wallpaper_photos
    })

    private fun restorePreferences(values: Map<String, *>) {
        val editor = raw.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
    }

    private fun assertChoice(source: WallpaperSource) {
        ui.await("Wallpaper choice page missing") { WallpaperSource.entries.all { ui.has(label(it)) } }
        for (choice in WallpaperSource.entries) {
            val row = ui.findAncestor(label(choice)) { it.isCheckable || it.isSelected }
            assertNotNull("Choice has no accessible selection state", row)
            assertEquals("Wrong selected source $choice", choice == source, row!!.isSelected || row.isChecked)
        }
    }

    private fun pick(scenario: ActivityScenario<MainActivity>, file: File?) {
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_GET_CONTENT).apply {
            addDataType("*/*"); addCategory(Intent.CATEGORY_OPENABLE)
        }, null, true)
        instrumentation.addMonitor(monitor)
        try {
            ui.click(label(WallpaperSource.PHOTO))
            ui.await("Photo choice did not launch the existing picker") { monitor.hits == 1 }
            scenario.recreate()
            scenario.onActivity { activity ->
                val saved = Bundle()
                activity.activityResultRegistry.onSaveInstanceState(saved)
                val keys = saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
                val codes = saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
                val key = "activity_rq#1"
                assertTrue(key in saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")!!)
                assertTrue(activity.activityResultRegistry.dispatchResult(codes[keys.indexOf(key)],
                    if (file == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    file?.let { Intent().setData(Uri.fromFile(it)) }))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun assertHome(model: MainViewModel, source: WallpaperSource) {
        ui.await("Home not rendered") { ui.has(context.getString(R.string.all_apps)) }
        if (source != WallpaperSource.NONE) ui.await("Home contrast map absent", 15000) { model.wallpaperContrast.map != null }
        val frame = frame()
        val expected = WallpaperPicture(source, model.prefs.wallpaperPath).decode(context)
        if (source == WallpaperSource.NONE) {
            assertNull(model.wallpaperBitmap); assertNull(model.wallpaperContrast.map)
        } else assertTrue("Wrong decoded picture", expected!!.sameAs(model.wallpaperBitmap))
        val background = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        if (expected == null) background.eraseColor(if (model.themeMode == PrefsManager.THEME_DARK) Color.BLACK else Color.WHITE)
        else {
            val scale = maxOf(frame.width.toFloat() / expected.width, frame.height.toFloat() / expected.height)
            val w = expected.width * scale; val h = expected.height * scale
            Canvas(background).drawBitmap(expected, null, android.graphics.RectF(
                (frame.width - w) / 2, (frame.height - h) / 2, (frame.width + w) / 2, (frame.height + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // Accessibility can publish Home before Android has presented its new surface.
        // Observe the final framebuffer rather than equating node presence with a drawn frame.
        var observed = frame
        var mismatch = ""
        try {
            ui.await("Home background $source did not render (theme=${model.themeMode})", 15000) {
                observed.recycle()
                observed = frame()
                (observed.height / 2 until observed.height / 2 + 20).all { y -> (3..20).all { x ->
                    val a = background.getPixel(x, y); val b = observed.getPixel(x, y)
                    val matches = listOf<(Int) -> Int>(Color::red, Color::green, Color::blue).all { channel ->
                        kotlin.math.abs(channel(a) - channel(b)) <= 3
                    }
                    if (!matches) mismatch = "$x,$y expected=${a.toUInt().toString(16)} observed=${b.toUInt().toString(16)}"
                    matches
                } }
            }
        } catch (failure: AssertionError) {
            save(observed, "failed-home-$source")
            throw AssertionError("${failure.message}: $mismatch", failure)
        } finally { observed.recycle() }
        expected?.recycle(); background.recycle()
    }

    private fun verifyContrast(model: MainViewModel, baseline: Bitmap, drawn: Bitmap, dark: Boolean) {
        val clock = Rect().also { ui.find("10:45")!!.getBoundsInScreen(it) }
        val map = model.wallpaperContrast.map!!.asAndroidBitmap()
        var checked = 0
        for (y in clock.top until clock.bottom) for (x in clock.left until clock.right) {
            val original = Color.red(baseline.getPixel(x, y))
            if (if (dark) original > 250 else original < 5) {
                val expected = Color.red(map.getPixel(x, y))
                assertTrue("Bundled contrast ink $dark at $x,$y", kotlin.math.abs(expected - Color.red(drawn.getPixel(x, y))) <= 5)
                checked++
            }
        }
        assertTrue("No bundled contrast glyphs tested", checked > 100)
    }

    private fun png(file: File) {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun frame(): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        return instrumentation.uiAutomation.takeScreenshot()!!
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(proof, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
