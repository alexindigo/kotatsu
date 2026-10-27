package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real ScreensaverActivity and its real wallpaper preference/file paths, including Home fallback. */
@RunWith(AndroidJUnit4::class)
class ScreensaverContrastInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    @Test fun clockDateAndStatusFollowMapInBothThemesAndWallpaperOffRestoresPaper() {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val original = raw.all.toMap()
        val directory = File(context.filesDir, "contrast-ss-${UUID.randomUUID()}").apply { check(mkdir()) }
        var activity: ScreensaverActivity? = null
        try {
            for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    raw.edit().putBoolean("screensaver_enabled", true).putBoolean("screensaver_show_clock", true)
                        .putBoolean("screensaver_show_notifications", false).putBoolean("screensaver_wallpaper", true)
                        .putBoolean("screensaver_islands", false).putBoolean("screensaver_eink_refresh", false)
                        .putBoolean("show_battery", true).putBoolean("show_alarm", false).putBoolean("show_weather", false)
                        .putInt("theme_mode", if (dark) 1 else 0).putBoolean("dark_mode", dark)
                        .putBoolean("hide_status_bar", false).putString("clock_format", "'10:45 AM'")
                        .putString("date_format", "'October 18, 2026'")
                        .remove("wallpaper_path").remove("screensaver_wallpaper_path").commit()
                }
                activity = instrumentation.startActivitySync(Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context.packageName, ScreensaverActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }) as ScreensaverActivity
                ui.await("Screensaver clock not visible") { ui.find { it.text?.contains("10:45") == true } != null }
                val clock = Rect().also { ui.find { it.text?.contains("10:45") == true }!!.getBoundsInScreen(it) }
                val date = bounds("October 18, 2026")
                val battery = bounds(context.getString(R.string.cd_battery))
                val baseline = frame()
                save(baseline, "none-${if (dark) "dark" else "light"}-screensaver")
                for (kind in listOf("vertical", "horizontal", "white", "black", "midgrey")) {
                    val splitY = clock.centerY()
                    val wall = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
                    for (y in 0 until 800) for (x in 0 until 480) wall.setPixel(x, y, when (kind) {
                        "vertical" -> if (x < 240) Color.rgb(30, 30, 30) else Color.WHITE
                        "horizontal" -> if (y < splitY) Color.rgb(30, 30, 30) else Color.WHITE
                        "black" -> Color.BLACK
                        "midgrey" -> Color.rgb(128, 128, 128)
                        else -> Color.WHITE
                    })
                    val source = File(directory, "$kind-${if (dark) "dark" else "light"}.png")
                    source.outputStream().use { assertTrue(wall.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    instrumentation.runOnMainSync {
                        val editor = raw.edit().putBoolean("screensaver_wallpaper", true)
                            .putBoolean("screensaver_islands", kind == "black")
                        if (kind in listOf("black", "midgrey")) {
                            editor.remove("screensaver_wallpaper_path").putString("wallpaper_path", source.path)
                        } else editor.putString("screensaver_wallpaper_path", source.path)
                        assertTrue(editor.commit())
                    }
                    ui.await("Screensaver did not show $kind wallpaper", 15000) {
                        val shot = frame()
                        shot.getPixel(8, date.bottom + 20) == wall.getPixel(8, date.bottom + 20)
                    }
                    fun inkAt(x: Int, y: Int): Int = when (kind) {
                        "vertical" -> if (x < 240) 255 else 0
                        "horizontal" -> if (y < splitY) 255 else 0
                        "black" -> 255
                        else -> 0
                    }
                    var renderedFrame: Bitmap? = null
                    ui.await("Screensaver $kind ink did not reach the clock", 15000) {
                        val shot = frame()
                        val ready = (clock.top until clock.bottom).all { y -> (clock.left until clock.right).all { x ->
                            val coverage = Color.red(baseline.getPixel(x, y)).let { if (dark) it else 255 - it }
                            val nearEdge = (kind == "vertical" && kotlin.math.abs(x - 240) < 20) ||
                                (kind == "horizontal" && kotlin.math.abs(y - splitY) < 20)
                            coverage < 250 || nearEdge || kotlin.math.abs(Color.red(shot.getPixel(x, y)) - inkAt(x, y)) <= 5
                        } }
                        if (ready) renderedFrame = shot else shot.recycle()
                        ready
                    }
                    val actual = renderedFrame!!
                    save(actual, "$kind-${if (dark) "dark" else "light"}-screensaver")
                    for (rect in listOf(clock, date, battery)) {
                        var cores = 0
                        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                            if (kind == "vertical" && kotlin.math.abs(x - 240) < 20) continue
                            if (kind == "horizontal" && kotlin.math.abs(y - splitY) < 20) continue
                            val reference = Color.red(baseline.getPixel(x, y))
                            val coverage = if (dark) reference else 255 - reference
                            val ink = inkAt(x, y)
                            val background = Color.red(wall.getPixel(x, y))
                            val expected = (ink * coverage + background * (255 - coverage) + 127) / 255
                            val rendered = Color.red(actual.getPixel(x, y))
                            assertTrue("Screensaver $kind dark=$dark ($x,$y): $expected != $rendered",
                                kotlin.math.abs(expected - rendered) <= 2)
                            if (coverage >= 250) cores++
                        }
                        assertTrue("No screensaver glyph cores in $rect", cores > 5)
                    }
                    wall.recycle()
                }
                instrumentation.runOnMainSync {
                    raw.edit().putBoolean("screensaver_islands", false).putBoolean("screensaver_wallpaper", false).commit()
                }
                ui.await("Screensaver wallpaper Off retained a map", 15000) {
                    val actual = frame()
                    (clock.top until date.bottom).all { y -> (0 until actual.width).all { x ->
                        actual.getPixel(x, y) == baseline.getPixel(x, y)
                    } }
                }
                instrumentation.runOnMainSync { activity?.finish() }
                ui.await("Screensaver did not finish") { !ScreensaverActivity.isShowing }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                val editor = raw.edit().clear()
                original.forEach { (key, value) -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(editor.commit())
                assertEquals(original, raw.all)
            }
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun bounds(label: String) = Rect().also { ui.find(label)!!.getBoundsInScreen(it) }

    private fun frame(): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        var previous = instrumentation.uiAutomation.takeScreenshot()!!
        repeat(20) {
            Thread.sleep(100)
            val current = instrumentation.uiAutomation.takeScreenshot()!!
            if (previous.sameAs(current)) { previous.recycle(); return current }
            previous.recycle(); previous = current
        }
        throw AssertionError("Screensaver framebuffer did not settle")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null), "contrast-probes").apply { mkdirs() }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        File(dir, "$name.png").outputStream().use { assertTrue(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        copy.recycle()
    }
}
