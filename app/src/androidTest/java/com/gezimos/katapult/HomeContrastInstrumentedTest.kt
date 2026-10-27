package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.ui.ContrastInkBox
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.WallpaperContrast
import com.gezimos.katapult.ui.contrastInk
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SystemChrome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Consumer pixel probes: real owned wallpaper file -> model -> actual Home framebuffer. */
@RunWith(AndroidJUnit4::class)
class HomeContrastInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private class TestApplication(base: Context, val store: SharedPreferences, val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
        override fun getFilesDir(): File = root
    }

    @Test fun bareInkFollowsWallpaperAndSolidsRemainIdentical() {
        val name = "contrast-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val root = File(context.filesDir, name).apply { check(mkdir()) }
        val app = TestApplication(context.applicationContext, store, root)
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        var activity: MainActivity? = null
        try {
            store.edit().putString("clock_format", "'10:45'").putString("date_format", "'October 18, 2026'")
                .putBoolean("show_battery", false).putBoolean("show_alarm", false)
                .putBoolean("show_weather", false).putBoolean("home_extra_row", true).commit()
            instrumentation.runOnMainSync {
                model = MainViewModel(app)
                model.screen = Screen.HOME
                owner.put("contrast", model)
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
            ui.await("Home clock not rendered") { ui.has("10:45") && ui.has("Phone") }
            val clock = bounds("10:45")
            val labelNames = listOf("extra_left" to "Music", "extra_center" to "Calendar", "extra_right" to "Camera",
                "phone" to "Phone", "sms" to "SMS").map { (slot, fallback) -> model.getShortcutLabel(slot, fallback) }
            ui.await("Home labels not rendered: $labelNames") { labelNames.all(ui::has) }
            val labels = labelNames.map(::bounds)
            val allApps = bounds(context.getString(R.string.all_apps))
            val bareRegions = listOf(clock, bounds("October 18, 2026"), allApps) + labels
            for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    model.themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
                    SystemChrome.apply(current.window, dark, false)
                    model.clearWallpaper()
                }
                val baseline = stableFrame()
                save(baseline, "none-${if (dark) "dark" else "light"}-home")
                for (kind in listOf("vertical", "horizontal", "white", "black", "midgrey")) {
                    val wallpaper = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
                    val splitY = clock.centerY()
                    for (y in 0 until 800) for (x in 0 until 480) wallpaper.setPixel(x, y, when (kind) {
                        "vertical" -> if (x < 240) Color.rgb(30, 30, 30) else Color.WHITE
                        "horizontal" -> if (y < splitY) Color.rgb(30, 30, 30) else Color.WHITE
                        "white" -> Color.WHITE
                        "black" -> Color.BLACK
                        else -> Color.rgb(128, 128, 128)
                    })
                    val file = File(root, "source.png")
                    file.outputStream().use { assertTrue(wallpaper.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val previousMap = model.wallpaperContrast.map
                    // An old exported island preference must not plate/repaint picture content.
                    store.edit().putBoolean("home_islands", kind == "black").commit()
                    instrumentation.runOnMainSync { model.setWallpaper(context, Uri.fromFile(file)) }
                    ui.await("Map not published for $kind", 15000) {
                        model.wallpaperContrast.map != null && model.wallpaperContrast.map !== previousMap
                    }
                    val frame = stableFrame()
                    save(frame, "$kind-${if (dark) "dark" else "light"}-home")
                    fun expected(x: Int, y: Int): Int = when (kind) {
                        "vertical" -> if (x < 240) Color.WHITE else Color.BLACK
                        "horizontal" -> if (y < splitY) Color.WHITE else Color.BLACK
                        "black" -> Color.WHITE
                        else -> Color.BLACK
                    }
                    for (rect in bareRegions) {
                        var checked = 0
                        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                            if (kind == "vertical" && kotlin.math.abs(x - 240) < 20) continue
                            if (kind == "horizontal" && kotlin.math.abs(y - splitY) < 20) continue
                            val original = Color.red(baseline.getPixel(x, y))
                            val coverage = if (dark) original else 255 - original
                            val background = Color.red(wallpaper.getPixel(x, y))
                            val ink = Color.red(expected(x, y))
                            val composite = (ink * coverage + background * (255 - coverage) + 127) / 255
                            val drawn = Color.red(frame.getPixel(x, y))
                            val vectorFringe = rect == allApps && !(-1..1).all { dy -> (-1..1).all { dx ->
                                Color.red(baseline.getPixel((x + dx).coerceIn(rect.left, rect.right - 1),
                                    (y + dy).coerceIn(rect.top, rect.bottom - 1))) == original
                            } }
                            if (vectorFringe) {
                                // Analytic vector AA is rasterized against transparent layer pixels,
                                // whereas the reference is against opaque paper. A one-pixel fringe
                                // includes coverage rounded to an endpoint in either rasterization.
                                // Eroded ring cores and transparent holes still use exact probes.
                                assertTrue("All Apps AA escaped ink/background range ($x,$y)",
                                    drawn in minOf(ink, background)..maxOf(ink, background))
                            } else {
                                assertTrue("Mask/background $kind dark=$dark ($x,$y): expected $composite got $drawn",
                                    kotlin.math.abs(composite - drawn) <= 2)
                            }
                            if (rect == allApps) {
                                // Thin rings have no 3x3 fully covered core. Observe the rendered
                                // ink cores at their actual raster positions, with the correct map side.
                                if (drawn == ink && drawn != background) checked++
                            } else if (if (dark) original > 250 else original < 5) {
                                assertTrue("$kind dark=$dark ($x,$y)",
                                    kotlin.math.abs(ink - Color.red(frame.getPixel(x, y))) <= 5)
                                checked++
                            }
                        }
                        assertTrue("No glyph pixels checked in $rect", checked > 15)
                    }
                    // Separate negative control: the same wallpaper, with its map disabled.
                    val mapSize = model.wallpaperContrast.map!!.let { IntSize(it.width, it.height) }
                    instrumentation.runOnMainSync { model.wallpaperContrast.resize(IntSize.Zero) }
                    val unpainted = stableFrame()
                    for (label in labels) {
                        val cx = label.centerX()
                        val cy = label.top - 40
                        for (y in cy - 15..cy + 15) for (x in cx - 15..cx + 15) {
                            assertSolidPixel("Baseline solid $kind ($x,$y)", baseline.getPixel(x, y), frame.getPixel(x, y))
                            assertSolidPixel("Map-disabled solid $kind ($x,$y)", unpainted.getPixel(x, y), frame.getPixel(x, y))
                        }
                    }
                    instrumentation.runOnMainSync { model.wallpaperContrast.resize(mapSize) }
                    ui.await("Map not restored", 15000) { model.wallpaperContrast.map != null }
                    wallpaper.recycle()
                }
                store.edit().putBoolean("home_islands", false).commit()
                instrumentation.runOnMainSync { model.clearWallpaper() }
                assertNull(model.wallpaperContrast.map)
                val cleared = stableFrame()
                // Full app area (exclude the live Android status/navigation bars).
                val content = Rect(0, clock.top, baseline.width, labels.maxOf { it.bottom })
                assertRegionIdentical(baseline, cleared, content)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); owner.clear() }
            assertTrue(root.deleteRecursively())
            deleteTestPreferences(context, name)
        }
    }

    @Test fun movingBareContentUsesItsNewWallpaperPositionWithoutRebuildingMap() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val contrast = WallpaperContrast(scope)
        val shift = mutableIntStateOf(20)
        val wallpaper = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, if (x < 240) Color.BLACK else Color.WHITE)
        }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try {
            instrumentation.runOnMainSync {
                contrast.updateWallpaper(wallpaper)
                activity.setContent {
                    ContrastInkBox(contrast, Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Gray)) {
                        Text("MMMM", fontFamily = LatoFamily, fontSize = 32.sp,
                            color = androidx.compose.ui.graphics.Color.Black,
                            modifier = Modifier.offset { IntOffset(shift.intValue, 300) }.contrastInk())
                    }
                }
            }
            ui.await("Moving ink map missing") { contrast.map != null && ui.has("MMMM") }
            val map = contrast.map
            fun checkInk(color: Int) {
                val rect = bounds("MMMM")
                val frame = stableFrame()
                var cores = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    if (frame.getPixel(x, y) == color) cores++
                    val opposite = if (color == Color.WHITE) Color.BLACK else Color.WHITE
                    assertNotEquals("Stale wallpaper position at $x,$y", opposite, frame.getPixel(x, y))
                }
                assertTrue("No ink cores at moved position", cores > 100)
                frame.recycle()
            }
            checkInk(Color.WHITE)
            instrumentation.runOnMainSync { shift.intValue = 300 }
            ui.await("Bare element did not move") { bounds("MMMM").left >= 300 }
            checkInk(Color.BLACK)
            assertSame("Layout movement rebuilt wallpaper map", map, contrast.map)
        } finally {
            instrumentation.runOnMainSync { activity.finish(); scope.cancel() }
            wallpaper.recycle()
        }
    }

    private fun bounds(label: String): Rect = Rect().also { ui.find(label)!!.getBoundsInScreen(it) }

    private fun stableFrame(): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        var previous = instrumentation.uiAutomation.takeScreenshot()!!
        repeat(20) {
            Thread.sleep(100)
            val next = instrumentation.uiAutomation.takeScreenshot()!!
            if (previous.sameAs(next)) { previous.recycle(); return next }
            previous.recycle()
            previous = next
        }
        throw AssertionError("Framebuffer did not settle")
    }

    private fun assertRegionIdentical(a: Bitmap, b: Bitmap, rect: Rect) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            assertEquals("No-wallpaper pixel changed at $x,$y", a.getPixel(x, y), b.getPixel(x, y))
        }
    }

    private fun assertSolidPixel(message: String, reference: Int, actual: Int) {
        assertEquals("$message alpha", Color.alpha(reference), Color.alpha(actual))
        val channels = listOf(Color.red(reference), Color.green(reference), Color.blue(reference))
        val drawn = listOf(Color.red(actual), Color.green(actual), Color.blue(actual))
        val opaqueCore = channels.all { it == 0 || it == 255 }
        for (i in channels.indices) {
            val tolerance = if (opaqueCore) 0 else 1
            assertTrue("$message channel=$i reference=${channels[i]} actual=${drawn[i]} tolerance=$tolerance",
                kotlin.math.abs(channels[i] - drawn[i]) <= tolerance)
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null), "contrast-probes").apply { mkdirs() }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        File(dir, "$name.png").outputStream().use { assertTrue("Screenshot PNG failed: $name config=${bitmap.config}", copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        copy.recycle()
    }
}
