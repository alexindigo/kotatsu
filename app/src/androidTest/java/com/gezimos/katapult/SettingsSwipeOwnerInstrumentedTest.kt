package com.gezimos.katapult

import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test

/** Regressions for the chapter pager's ownership relative to its scrollable content. */
class SettingsSwipeOwnerInstrumentedTest {
    @get:org.junit.Rule val accessibility = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun fixture(test: (MainActivity, MainViewModel) -> Unit) {
        ApplicationPreferencesFixture(context).use { preferences ->
            preferences.reset {
                putBoolean("onboarding_complete", true)
                putInt("theme_mode", PrefsManager.THEME_LIGHT)
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try { test(activity, activity.viewModel) }
            finally {
                main { activity.finish() }
                ui.await("Settings swipe fixture did not release its model") { activity.isDestroyed }
            }
        }
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 250) {
        instrumentation.platformAutomation.boundedShell("input swipe $x1 $y1 $x2 $y2 $duration")
        instrumentation.waitForIdleSync()
    }

    @Test fun settingsChapterIgnoresCrossAxisDriftButKeepsItsVerticalPager() = fixture { _, model ->
        main { model.navigateTo(Screen.SETTINGS) }
        ui.await("Settings contents missing") { ui.has(context.getString(R.string.section_appearance)) }
        ui.click(context.getString(R.string.section_appearance))
        ui.await("Appearance first page missing") { ui.has(context.getString(R.string.icon_shape)) }
        for (duration in listOf(250, 1200)) {
            swipe(380, 480, 100, 370, duration)
            assertTrue("Mostly left movement paged the vertical chapter", ui.has(context.getString(R.string.icon_shape)))
            assertFalse(ui.has(context.getString(R.string.eink_refresh)))
        }
        swipe(240, 600, 240, 220)
        ui.await("Intentional up swipe did not page the chapter") { ui.has(context.getString(R.string.eink_refresh)) }
        swipe(240, 220, 240, 600)
        ui.await("Intentional down swipe did not return the chapter") { ui.has(context.getString(R.string.icon_shape)) }
        assertEquals(Screen.SETTINGS, model.screen)
    }

    @Test fun overflowingContentKeepsItsVerticalScrollInsteadOfTurningThePage() = fixture { activity, _ ->
        val category = SettingsCategory("overflow", "Overflow chapter", listOf(
            SettingsGroup("large", "Large group", (0..5).map { index ->
                SettingsEntry.Open("row$index", "Overflow row $index", "One\nTwo\nThree\nFour\nFive", {})
            }),
            SettingsGroup("next", "Next group", listOf(
                SettingsEntry.Open("next1", "Next page row", "", {}),
                SettingsEntry.Open("next2", "Another page row", "", {}),
            )),
        ))
        main { activity.setContent { PaperIndexRenderer(category, {}, Modifier.requiredSize(360.dp, 420.dp)) } }
        ui.await("Overflowing chapter fixture missing") { ui.has("Page 1 of 2") && ui.has("Overflow row 0") }
        val viewport = Rect()
        val first = Rect()
        val scroll = ui.find { it.className == "android.widget.ScrollView" && it.isScrollable }
        assertNotNull("Fixture must have a real scroll range", scroll)
        scroll!!.getBoundsInScreen(viewport)
        ui.find("Overflow row 0")!!.getBoundsInScreen(first)
        swipe(viewport.centerX(), viewport.bottom - 20, viewport.centerX(), viewport.top + 20)
        ui.await("The overflowing content did not scroll") {
            val node = ui.find("Overflow row 0")
            node == null || Rect().also { node.getBoundsInScreen(it) }.top < first.top
        }
        assertTrue("A child-owned scroll changed pages", ui.has("Page 1 of 2"))
        assertFalse(ui.has("Next page row"))
    }
}
