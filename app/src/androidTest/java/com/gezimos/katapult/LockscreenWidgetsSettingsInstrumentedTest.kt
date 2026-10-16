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
import com.gezimos.katapult.util.DeviceHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LockscreenWidgetsSettingsInstrumentedTest {
    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    @Test fun nonMuditaHidesWidgetRowAndRecoveryTurnsOffWithoutLosingIds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Requires the non-Mudita emulator", DeviceHelper.isMuditaKompakt())
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val name = "k2-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        val list = """[{"id":27,"size":"M"}]"""
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, raw))
                owner.put("k2", model)
                model.prefs.lockscreenWidgets = list
                model.prefs.lockscreenWidgetsEnabled = true
                model.screen = Screen.HOME
                model.checkLockscreenService(false)
                assertTrue(model.showLockscreenReEnable)
                assertTrue(model.lockscreenServiceFeatureTitles().contains(R.string.lockscreen_widgets))
                activity.setContent {
                    SettingsScreen(model, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker)
                }
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.click(context.getString(R.string.cat_lockscreen))
            ui.scrollTo(context.getString(R.string.lockscreen_music_widget_desc) + "\n" + context.getString(R.string.action_service_off))
            assertFalse(ui.has(context.getString(R.string.lockscreen_widgets)))
            ui.scrollTo(context.getString(R.string.lockscreen_message_desc))
            assertFalse("Mudita-only widget row was rendered", ui.has(context.getString(R.string.lockscreen_widgets)))
            assertFalse(ui.has("Lock-screen panel (test)"))
            instrumentation.runOnMainSync {
                model.disableLockscreenServiceFeatures()
                assertFalse(model.prefs.lockscreenWidgetsEnabled)
                assertEquals(list, model.prefs.lockscreenWidgets)
            }
        } finally {
            instrumentation.runOnMainSync { owner.clear(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
