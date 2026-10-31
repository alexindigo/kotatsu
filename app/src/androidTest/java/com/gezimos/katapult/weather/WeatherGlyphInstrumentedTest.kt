package com.gezimos.katapult.weather

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.PlatformUiDriver
import com.gezimos.katapult.Screen
import com.gezimos.katapult.deleteTestPreferences
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Mudita-like normalized source -> shared feed -> real Home -> framebuffer. */
@RunWith(AndroidJUnit4::class)
class WeatherGlyphInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private class TestApplication(base: Context, val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    @Test fun fewCloudsOnHomeMatchesPartlyCloudyAndDiffersFromClearDayAndNight() {
        val name = "weather-glyph-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val singleton = WeatherFeed::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val reading = AtomicReference(Weather(64, "F", WeatherCondition.CLEAR, true))
        val changed = AtomicReference<(() -> Unit)?>()
        val worker = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val feed = WeatherFeed({ WeatherFeed.Source(reading, { reading.get() }, {
            changed.set(it); AutoCloseable { }
        }) }, worker, Executor { handler.post(it) }, SystemClock::elapsedRealtime)
        val owner = ViewModelStore()
        var activity: MainActivity? = null
        try {
            assertTrue(prefs.edit().putString("clock_format", "'10:45'")
                .putString("date_format", "'October 31, 2026'").putInt("theme_mode", 0)
                .putBoolean("show_weather", true).putBoolean("show_battery", false)
                .putBoolean("show_alarm", false).commit())
            singleton.set(null, feed)
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, prefs))
                model.screen = Screen.HOME
                owner.put("weather-glyph", model)
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val current = activity
            instrumentation.runOnMainSync { current.setContent {
                App(model, current.imagePicker, current.iconPicker, current.configExporter,
                    current.configImporter, current.screensaverWallpaperPicker, {})
            } }
            ui.await("Home and feed ready") { ui.has("10:45") && ui.has("64°F") && changed.get() != null }
            val clock = Rect().also { ui.find("10:45")!!.getBoundsInScreen(it) }
            // The weather is above the clock; exclude Android's live status bar.
            val line = Rect(0, clock.top - (40 * context.resources.displayMetrics.density).toInt(),
                context.resources.displayMetrics.widthPixels, clock.top)
            for (daytime in listOf(true, false)) {
                fun show(condition: WeatherCondition): Bitmap {
                    reading.set(Weather(64, "F", condition, daytime))
                    changed.get()!!.invoke()
                    ui.await("Home consumed $condition/$daytime") { model.weather == reading.get() }
                    return stableFrame()
                }
                val clear = show(WeatherCondition.CLEAR)
                val few = show(WeatherCondition.FEW_CLOUDS)
                val partly = show(WeatherCondition.PARTLY_CLOUDY)
                var changedPixels = 0
                for (y in line.top.coerceAtLeast(0) until line.bottom) for (x in line.left until line.right) {
                    assertEquals("Few clouds must render the partly-cloudy glyph ($x,$y)",
                        partly.getPixel(x, y), few.getPixel(x, y))
                    if (few.getPixel(x, y) != clear.getPixel(x, y)) changedPixels++
                }
                assertTrue("Few clouds still draws clear", changedPixels > 5)
                if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
                    val dir = File(context.getExternalFilesDir(null), "weather-df").apply { mkdirs() }
                    for ((label, bitmap) in listOf("clear" to clear, "801" to few)) {
                        val file = File(dir, "$label-${if (daytime) "day" else "night"}.png")
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        try { file.outputStream().use { assertTrue(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                        finally { copy.recycle() }
                        assertTrue("Screenshot PNG must contain encoded pixels", file.length() > 0)
                    }
                }
                clear.recycle(); few.recycle(); partly.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); owner.clear() }
            feed.close()
            worker.shutdown()
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS))
            singleton.set(null, previous)
            deleteTestPreferences(context, name)
        }
    }

    private fun stableFrame(): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        var previous = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        repeat(30) {
            Thread.sleep(100)
            val next = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            if (previous.sameAs(next)) { previous.recycle(); return next }
            previous.recycle(); previous = next
        }
        previous.recycle()
        throw AssertionError("Home framebuffer did not settle")
    }
}
