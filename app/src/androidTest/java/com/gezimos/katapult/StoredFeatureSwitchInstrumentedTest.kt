package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ActionServiceSheet
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StoredFeatureSwitchInstrumentedTest {
    @Test fun notificationsCanBeTurnedOffWithoutService() = checkSwitch(
        R.string.cat_lockscreen, R.string.lockscreen_widget, R.string.lockscreen_widget_desc, "lockscreen_widget",
        { lockscreenWidget }, { lockscreenWidget = it },
    )

    @Test fun musicCanBeTurnedOffWithoutService() = checkSwitch(
        R.string.cat_lockscreen, R.string.lockscreen_music_widget, R.string.lockscreen_music_widget_desc, "lockscreen_music_widget",
        { lockscreenMusicWidget }, { lockscreenMusicWidget = it },
    )

    @Test fun powerScreensaverCanBeTurnedOffWithoutService() = checkSwitch(
        R.string.screensaver, R.string.screensaver_on_power, R.string.screensaver_on_power_desc, "screensaver_on_power",
        { screensaverOnPower }, { screensaverOnPower = it },
    )

    private fun checkSwitch(
        category: Int, title: Int, description: Int, key: String,
        read: PrefsManager.() -> Boolean, write: PrefsManager.(Boolean) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Test requires the existing disabled Action Service", LockscreenWidgetService.isEnabled(context))
        val prefs = PrefsManager(context)
        val shared = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = shared.all
        val saved = listOf(key, "screensaver_enabled").associateWith { before[it] }
        assertTrue("Preserve unexpected stored preference types", saved.values.all { it == null || it is Boolean })
        val writes = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) writes.incrementAndGet()
        }
        var activity: MainActivity? = null
        try {
            instrumentation.runOnMainSync {
                prefs.write(true)
                prefs.screensaverEnabled = true
            }
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(current)[MainViewModel::class.java]
                current.setContent {
                    SettingsScreen(viewModel, current.configExporter, current.configImporter, current.screensaverWallpaperPicker)
                    ActionServiceSheet(viewModel)
                }
                // The stored-on switch with the service off correctly offers recovery on start; dismiss it here.
                viewModel.resolveLockscreenReEnable()
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.click(context.getString(category))
            val chapterAnchor = if (category == R.string.cat_lockscreen) R.string.lockscreen_widget_desc else R.string.screensaver_enable_desc
            ui.await("Feature chapter did not open") { ui.has(context.getString(chapterAnchor)) }
            val label = context.getString(description)
            if (!ui.has(label)) ui.click(context.getString(R.string.paper_next_page))
            ui.await("Chapter did not expose the stored feature row") { ui.has(label) }
            ui.await("Stored-on ${context.getString(title)} was shown off without the service") { ui.checked(label) == true }
            ui.click(label)
            ui.await("Turning off did not persist false") { !prefs.read() && ui.checked(label) == false }
            shared.registerOnSharedPreferenceChangeListener(listener)
            ui.click(label)
            // Turning on without the service first explains the setup (steps, shortcut warning), then opens Android's list.
            ui.await("Turning on without service did not show the setup sheet") {
                ui.has(context.getString(R.string.a11y_setup_step_shortcut))
            }
            ui.click(context.getString(R.string.a11y_setup_open))
            ui.await("Setup sheet did not open Accessibility settings") {
                ui.packageVisible("com.android.settings")
            }
            instrumentation.waitForIdleSync()
            assertFalse("Unavailable feature was enabled", prefs.read())
            assertEquals("Unavailable enable attempt wrote a preference", 0, writes.get())
        } finally {
            shared.unregisterOnSharedPreferenceChangeListener(listener)
            instrumentation.runOnMainSync {
                activity?.finish()
                // The runner can end this process next: restoration must reach disk,
                // and absent preferences must remain absent rather than acquire defaults.
                val editor = shared.edit()
                saved.forEach { (name, value) ->
                    if (value is Boolean) editor.putBoolean(name, value) else editor.remove(name)
                }
                assertTrue("Could not restore stored feature preferences", editor.commit())
            }
        }
    }
}
