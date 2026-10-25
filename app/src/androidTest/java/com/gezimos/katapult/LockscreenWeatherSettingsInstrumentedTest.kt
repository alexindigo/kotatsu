package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.weather.WeatherSources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LockscreenWeatherSettingsInstrumentedTest {
    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    @Test fun absentSourceHidesWeatherRowsButRetainsStoredChoiceForRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNull("Requires the non-Mudita emulator", WeatherSources.active(context))
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val name = "l3-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, raw))
                owner.put("l3", model)
                assertFalse(model.prefs.lockscreenWeather)
                assertFalse(model.prefs.weatherCelsius)
                model.prefs.lockscreenWeather = true
                model.screen = Screen.HOME
                model.checkLockscreenService(false)
                assertTrue(model.showLockscreenReEnable)
                assertTrue(model.lockscreenServiceFeatureTitles().contains(R.string.lockscreen_weather))
                activity.setContent {
                    SettingsScreen(model, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker)
                }
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.click(context.getString(R.string.cat_lockscreen))
            ui.await("Lock screen chapter did not open") { ui.has(context.getString(R.string.lockscreen_widget_desc)) }
            if (!ui.has(context.getString(R.string.lockscreen_music_widget_desc))) ui.click(context.getString(R.string.paper_next_page))
            ui.await("Lock screen widgets chapter did not open") { ui.has(context.getString(R.string.lockscreen_music_widget_desc)) }
            assertFalse("Unavailable weather row was rendered", ui.has(context.getString(R.string.lockscreen_weather)))
            // The following row proves this is the weather row's actual position,
            // rather than an assertion against an unopened/off-screen page.
            assertTrue(ui.has(context.getString(R.string.lockscreen_message_desc)))
            assertFalse(ui.has(context.getString(R.string.lockscreen_weather)))
            ui.back()
            ui.click(context.getString(R.string.section_home))
            ui.scrollTo(context.getString(R.string.show_alarm))
            assertFalse(ui.has(context.getString(R.string.show_weather)))
            ui.click(context.getString(R.string.paper_next_page))
            ui.await("Home layout chapter did not open") { ui.has(context.getString(R.string.extra_dock_row)) }
            assertFalse(ui.has(context.getString(R.string.show_weather)))
            assertFalse("Unavailable Celsius row was rendered", ui.has(context.getString(R.string.weather_celsius)))
            instrumentation.runOnMainSync {
                model.disableLockscreenServiceFeatures()
                assertFalse(model.prefs.lockscreenWeather)
            }
        } finally {
            instrumentation.runOnMainSync {
                owner.clear()
                activity.finish()
            }
            deleteTestPreferences(context, name)
        }
    }
}
