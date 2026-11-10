package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.App
import org.junit.Assert.*
import org.junit.Test

class HomeSwipeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    private fun withHome(vertical: Boolean, test: (MainViewModel) -> Unit) {
        WidgetHostTestFixture(context).use { fixture ->
            val owner = ViewModelStore()
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, fixture.raw))
                owner.put("home-swipe", model)
                model.screen = Screen.HOME
                model.prefs.verticalAppGestures = vertical
                model.prefs.swipeUpAllApps = true
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                instrumentation.runOnMainSync { activity.setContent {
                    App(model, activity.imagePicker, activity.iconPicker, activity.configExporter,
                        activity.configImporter, activity.screensaverWallpaperPicker, {})
                } }
                ui.await("Home not rendered") { ui.has(context.getString(R.string.all_apps)) }
                test(model)
            } finally { instrumentation.runOnMainSync { activity.finish(); owner.clear() } }
        }
    }

    private fun slowSwipe(vertical: Boolean) {
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val down = SystemClock.uptimeMillis()
        val duration = ViewConfiguration.getLongPressTimeout() + 300L
        for (step in 0..20) {
            val position = .75f - .5f * step / 20
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }, if (vertical) bounds.width() / 2f else bounds.width() * position,
                if (vertical) bounds.height() * position else bounds.height() / 2f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
            if (step < 20) SystemClock.sleep(duration / 20)
        }
        assertTrue("Regression input must exceed the long-press timeout",
            SystemClock.uptimeMillis() - down > ViewConfiguration.getLongPressTimeout())
        instrumentation.waitForIdleSync()
    }

    private fun assertSlowSwipe(vertical: Boolean) = withHome(vertical) { model ->
        slowSwipe(vertical)
        ui.await("Slow ${if (vertical) "vertical" else "horizontal"} swipe must open All Apps, not the Home menu") {
            model.screen == Screen.ALL_APPS && !ui.has(context.getString(R.string.wallpaper))
        }
    }

    @Test fun slowVerticalSwipeCancelsTheHomeLongPress() = assertSlowSwipe(true)
    @Test fun slowHorizontalSwipeCancelsTheHomeLongPress() = assertSlowSwipe(false)
    @Test fun stationaryLongPressStillOpensTheHomeMenu() = withHome(true) { model ->
        ui.homeMenu()
        assertEquals(Screen.HOME, model.screen)
        assertTrue(ui.has(context.getString(R.string.wallpaper)))
    }
}
